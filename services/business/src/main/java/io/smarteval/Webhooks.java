package io.smarteval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Webhooks {
    private static final Set<String> EVENTS = Set.of("AWAITING_CONFIRMATION","PREPARATION_FAILED","COMPLETED","REVIEW_REQUIRED");
    private static final String ENC_PREFIX = "enc:";
    private static final long[] BACKOFF_SECONDS = {10, 30, 120, 600};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Webhooks.class);
    private final Database db;
    private final boolean local;
    private final boolean workerEnabled;
    private final SecretKeySpec secretKey;
    private final Semaphore slots = new Semaphore(4);
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;

    Webhooks(Database db, @Value("${SMARTEVAL_WEBHOOK_ALLOW_LOOPBACK:false}") boolean local,
             @Value("${smarteval.worker-enabled}") boolean workerEnabled,
             @Value("${smarteval.secret}") String appSecret) {
        this.db=db; this.local=local; this.workerEnabled=workerEnabled;
        this.secretKey = new SecretKeySpec(Api.sha("smarteval-webhook-secret-v1:" + appSecret), "AES");
    }

    String encryptSecret(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENC_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt webhook secret", e);
        }
    }

    String decryptSecret(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) return stored;
        try {
            byte[] combined = Base64.getUrlDecoder().decode(stored.substring(ENC_PREFIX.length()));
            if (combined.length <= 12) throw new IllegalStateException("Invalid encrypted webhook secret");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt webhook secret", e);
        }
    }

    List<Map<String, Object>> deliveries(UUID job) {
        return db.list("""
                SELECT id,run_no,job_revision,event_type,status,attempts,last_http_status,created_at,delivered_at,available_at
                FROM webhook_delivery WHERE job_id=? ORDER BY created_at DESC,id DESC LIMIT 50
                """, (r, i) -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("id", Database.uuid(r, "id"));
            row.put("run", r.getInt("run_no"));
            row.put("job_revision", r.getInt("job_revision"));
            row.put("event", r.getString("event_type"));
            row.put("status", r.getString("status"));
            row.put("attempts", r.getInt("attempts"));
            row.put("last_http_status", r.getObject("last_http_status"));
            row.put("created_at", Database.time(r, "created_at"));
            row.put("delivered_at", Database.time(r, "delivered_at"));
            row.put("next_attempt_at", "PENDING".equals(r.getString("status")) ? Database.time(r, "available_at") : null);
            return row;
        }, job);
    }

    String validate(String value) {
        try {
            var uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost()==null || uri.getHost().contains("%") || uri.getRawUserInfo()!=null
                    || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException();
            destination(uri);
            return uri.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException | UnknownHostException e) {
            throw Api.error(422,"INVALID_WEBHOOK_URL","webhook_url 必须是可解析的公共 HTTP(S) 地址");
        }
    }

    private InetAddress destination(URI uri) throws UnknownHostException {
        var addresses = InetAddress.getAllByName(uri.getHost());
        for (var address : addresses) {
            if (!publicAddress(address) && !(local && address.isLoopbackAddress()))
                throw new UnknownHostException("Non-public webhook destination");
        }
        return addresses[0];
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] octet = address.getAddress();
        int a=octet[0]&255,b=octet[1]&255,c=octet[2]&255,d=octet[3]&255;
        if (address instanceof Inet4Address)
            return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && b>=64 && b<=127)
                    && !(a==169 && b==254) && !(a==172 && b>=16 && b<=31)
                    && !(a==192 && (b==0 || b==168 || b==88 && c==99))
                    && !(a==198 && (b==18 || b==19 || b==51 && c==100)) && !(a==203 && b==0 && c==113);
        return (a&224)==32 && !(a==32 && (b==2 || b==1 && (c<2 || c==13 && d==184)))
                && !(a==63 && b==255 && c<16);
    }

    boolean configured(UUID job) {
        return db.one("SELECT 1 FROM grading_job WHERE id=? AND webhook_url IS NOT NULL",(r,i)->true,job)!=null;
    }

    boolean lockJobForEvent(UUID job) {
        return Boolean.TRUE.equals(db.one("SELECT webhook_url IS NOT NULL FROM grading_job WHERE id=? FOR UPDATE",
                (r,i)->r.getBoolean(1),job));
    }

    void record(UUID job, String previousStatus, ObjectNode summary) {
        String status = summary.path("status").asText();
        if (!EVENTS.contains(status) || status.equals(previousStatus)) return;
        int run = summary.path("run").asInt(), revision = summary.path("revision").asInt();
        if (db.one("SELECT 1 FROM webhook_delivery WHERE job_id=? AND run_no=? AND job_revision=? AND event_type=?",
                (r,i)->true,job,run,revision,status) != null) return;
        var id = UUID.randomUUID();
        var now = Instant.now();
        var event = db.json.createObjectNode().put("id",id.toString()).put("event",status).put("occurred_at",now.toString());
        event.set("job",summary);
        db.write("""
                INSERT INTO webhook_delivery(id,job_id,run_no,job_revision,event_type,payload,available_at,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """,id,job,run,revision,status,db.encode(event),now,now);
    }

    private record Delivery(UUID id, UUID job, String event, String payload, int attempts, UUID lease, boolean exhausted) {}
    private record Destination(String url, String key) {}

    @Scheduled(fixedDelay=400)
    void dispatch() {
        if (!workerEnabled || stopping) return;
        int capacity = slots.availablePermits();
        for (int i=0; i<capacity && !stopping && slots.tryAcquire(); i++) {
            boolean sent = false;
            try {
                Delivery delivery = claim();
                if (delivery == null) break;
                executor.execute(() -> {
                    try { deliver(delivery); }
                    catch (RuntimeException e) { LOG.warn("Unable to persist webhook delivery {}",delivery.id(),e); }
                    finally { slots.release(); }
                });
                sent = true;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                break;
            } finally {
                if (!sent) slots.release();
            }
        }
    }

    private Delivery claim() {
        return db.transaction(() -> {
            Instant now = Instant.now();
            UUID lease = UUID.randomUUID();
            var event = db.one("""
                    SELECT d.id,d.job_id,d.event_type,d.payload,d.attempts FROM webhook_delivery d
                    WHERE d.status='PENDING' AND d.available_at<=? AND (d.lease_until IS NULL OR d.lease_until<=?)
                    AND EXISTS (SELECT 1 FROM grading_job j JOIN tenant t ON t.id=j.tenant_id
                                WHERE j.id=d.job_id AND t.status='ACTIVE')
                    ORDER BY d.available_at,d.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(r,i)->new Delivery(Database.uuid(r,"id"),Database.uuid(r,"job_id"),r.getString("event_type"),
                            r.getString("payload"),Math.min(5,r.getInt("attempts")+1),lease,r.getInt("attempts")>=5),now,now);
            if (event != null) db.write("UPDATE webhook_delivery SET lease_id=?,lease_until=?,attempts=? WHERE id=?",
                    lease,now.plusSeconds(90),event.attempts(),event.id());
            return event;
        });
    }

    private void deliver(Delivery event) {
        Integer code = null;
        if (!event.exhausted()) {
            var target = db.required("SELECT webhook_url,webhook_secret FROM grading_job WHERE id=?",
                    (r,i)->new Destination(r.getString(1),decryptSecret(r.getString(2))),event.job());
            try { code = send(URI.create(target.url()),target.key(),event); }
            catch (IOException | RuntimeException e) { LOG.debug("Webhook delivery {} failed ({})",event.id(),e.getClass().getSimpleName()); }
        }
        boolean success = code!=null && code>=200 && code<300;
        String status = success ? "DELIVERED" : event.attempts()>=5 ? "FAILED" : "PENDING";
        if ("FAILED".equals(status)) {
            LOG.warn("Webhook delivery {} for job {} permanently failed after {} attempts (lastStatus={})",
                    event.id(), event.job(), event.attempts(), code);
        }
        Instant now = Instant.now();
        long delay = BACKOFF_SECONDS[Math.min(BACKOFF_SECONDS.length - 1, Math.max(0, event.attempts() - 1))];
        db.write("""
                UPDATE webhook_delivery SET status=?,available_at=?,last_http_status=?,delivered_at=?,lease_id=NULL,lease_until=NULL
                WHERE id=? AND lease_id=? AND lease_until>?
                """, status,success?now:now.plusSeconds(delay),code,success?now:null,
                event.id(),event.lease(),now);
    }

    private int send(URI uri,String key,Delivery event) throws IOException {
        InetAddress pinned = destination(uri);
        var connection = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new DnsResolver() {
            @Override public InetAddress[] resolve(String host) { return new InetAddress[]{pinned}; }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        }).setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(4)).setSocketTimeout(Timeout.ofSeconds(5)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(connection).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(5)).build()).build()) {
            var request = new HttpPost(uri);
            request.setHeader("X-SmartEval-Event",event.event());
            request.setHeader("X-SmartEval-Delivery",event.id().toString());
            request.setHeader("X-SmartEval-Signature","sha256="+signature(key,event.payload()));
            request.setEntity(new StringEntity(event.payload(),ContentType.APPLICATION_JSON));
            try (var response = client.executeOpen(null, request, null)) {
                return response.getCode();
            }
        }
    }

    static String signature(String key,String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
