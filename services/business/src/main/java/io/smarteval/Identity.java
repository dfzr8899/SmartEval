package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Identity implements ApplicationRunner {
    static final UUID GLOBAL = new UUID(0, 0);
    private static final Argon2PasswordEncoder PASSWORDS = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final String DUMMY_HASH = PASSWORDS.encode("smarteval-dummy-timing-guard-password");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final Database db;
    private final RateLimiter rateLimiter;
    private final byte[] signingKey;
    private final String bootstrapUser;
    private final String bootstrapPassword;

    Identity(Database db, RateLimiter rateLimiter, @Value("${smarteval.secret}") String secret,
             @Value("${smarteval.bootstrap-user}") String bootstrapUser,
             @Value("${smarteval.bootstrap-password}") String bootstrapPassword) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SMARTEVAL_SESSION_SECRET requires at least 32 bytes");
        }
        this.db = db;
        this.rateLimiter = rateLimiter;
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
        this.bootstrapUser = bootstrapUser;
        this.bootstrapPassword = bootstrapPassword;
    }

    record Principal(UUID user, UUID tenant, UUID member, Set<String> roles, boolean platform, UUID session) {
        UUID requireTenant() {
            if (tenant == null) throw error(403, "TENANT_REQUIRED", "请选择批改工作空间");
            return tenant;
        }
        void requireRole(String role) { if (!roles.contains(role)) throw forbidden(); }
    }
    record Session(UUID id, UUID user, UUID tenant, int version, Instant expires) {}
    record Tokens(String access, String refresh, UUID tenant) {}
    record Account(UUID user, String password, String state) {}

    @Override
    public void run(ApplicationArguments args) {
        boolean configured = !bootstrapUser.isBlank() || !bootstrapPassword.isBlank();
        if (configured && (bootstrapUser.isBlank() || bootstrapPassword.length() < 12)) {
            throw new IllegalStateException("Bootstrap requires a username and a password of at least 12 characters");
        }
        Integer count = db.one("SELECT COUNT(*) FROM sys_user WHERE platform_admin=TRUE", (r,i) -> r.getInt(1));
        if (count == 0 && configured) {
            try {
                db.transaction(() -> {
                    UUID user = createAccount(bootstrapUser, bootstrapPassword, "批改管理员");
                    db.write("UPDATE sys_user SET platform_admin=TRUE,updated_at=? WHERE id=?", Instant.now(), user);
                });
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // Concurrent instance already bootstrapped the initial administrator.
            }
        }
        var admins = db.list("""
                SELECT id FROM sys_user WHERE platform_admin=TRUE AND status='ACTIVE' ORDER BY created_at,id
                """, (r,i)->Database.uuid(r,"id"));
        if (admins.isEmpty()) return;
        UUID workspace = db.one("SELECT id FROM tenant WHERE slug='smarteval-grading'", (r,i)->Database.uuid(r,"id"));
        Instant now = Instant.now();
        if (workspace == null) {
            UUID created = UUID.randomUUID();
            try {
                db.write("INSERT INTO tenant(id,name,slug,status,created_at,updated_at) VALUES(?,?,'smarteval-grading','ACTIVE',?,?)", created,"默认批改工作空间",now,now);
                workspace = created;
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                workspace = db.required("SELECT id FROM tenant WHERE slug='smarteval-grading'", (r,i)->Database.uuid(r,"id"));
            }
        }
        UUID defaultWorkspace = workspace;
        for (UUID user : admins) {
            UUID member = db.one("SELECT id FROM tenant_member WHERE tenant_id=? AND user_id=?",
                    (r,i)->Database.uuid(r,"id"),defaultWorkspace,user);
            if (member != null) continue;
            try {
                db.transaction(() -> {
                    UUID newMember = UUID.randomUUID();
                    db.write("INSERT INTO tenant_member(tenant_id,id,user_id,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",defaultWorkspace,newMember,user,now,now);
                    db.write("INSERT INTO member_role(tenant_id,member_id,role_key,created_at) VALUES(?,?,'SCHOOL_ADMIN',?)",defaultWorkspace,newMember,now);
                });
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // Concurrent instance already added membership.
            }
        }
    }

    UUID createAccount(String username, String password, String displayName) {
        if (password.length() < 12 || password.length() > 128) throw error(422,"INVALID_PASSWORD","密码长度需为 12–128");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        db.write("INSERT INTO sys_user(id,username_normalized,password_hash,display_name,status,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,?)",
                id, normalize(username), PASSWORDS.encode(password), displayName,now,now);
        return id;
    }

    static String normalize(String username) {
        return Normalizer.normalize(username.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    String hashPassword(String password){
        if(password.length()<12||password.length()>128)throw error(422,"INVALID_PASSWORD","密码长度需为 12–128");
        return PASSWORDS.encode(password);
    }
    void verifyPassword(UUID user,String password){
        String encoded=db.required("SELECT password_hash FROM sys_user WHERE id=? AND status='ACTIVE' FOR UPDATE",(r,i)->r.getString(1),user);
        if(!PASSWORDS.matches(password,encoded))throw error(422,"CURRENT_PASSWORD_INVALID","当前密码不正确");
    }

    @Transactional
    public Tokens login(JsonNode body, HttpServletRequest request) {
        String ip = RateLimiter.clientIp(request);
        String username = normalize(text(body,"username",120));
        String password = text(body,"password",128);
        if (!rateLimiter.allow("login:ip:" + ip, 30, 300) || !rateLimiter.allow("login:user:" + username, 15, 300)) {
            throw error(429, "RATE_LIMITED", "登录尝试过于频繁，请稍后再试");
        }
        var account = db.one("SELECT id,password_hash,status FROM sys_user WHERE username_normalized=?",
                (r,i) -> new Account(Database.uuid(r,"id"),r.getString("password_hash"),r.getString("status")), username);
        String candidateHash = account == null ? DUMMY_HASH : account.password();
        boolean matched = PASSWORDS.matches(password, candidateHash);
        if (account == null || !matched || !"ACTIVE".equals(account.state())) throw unauthenticated();
        rateLimiter.reset("login:user:" + username);
        var spaces = workspaces(account.user());
        if (spaces.isEmpty()) throw forbidden();
        UUID tenant = body.hasNonNull("tenant_id") ? id(body.get("tenant_id").asText()) : spaces.getFirst().tenant_id();
        if (spaces.stream().noneMatch(w -> w.tenant_id().equals(tenant))) throw forbidden();
        UUID session = UUID.randomUUID();
        String refresh = random();
        Instant now = Instant.now();
        db.write("INSERT INTO auth_session(id,user_id,active_tenant_id,refresh_hash,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                session,account.user(),tenant,sha(refresh),now.plusSeconds(604800),now,now);
        return new Tokens(access(session,1),refresh,tenant);
    }

    @Transactional
    public Tokens refresh(String refresh) {
        if (refresh == null || refresh.length() != 43) throw unauthenticated();
        var session = session("s.refresh_hash=?", " FOR UPDATE", sha(refresh));
        if (session.tenant() != null) member(session.user(),session.tenant());
        String replacement = random();
        db.write("UPDATE auth_session SET refresh_hash=?,updated_at=? WHERE id=?",sha(replacement),Instant.now(),session.id());
        return new Tokens(access(session.id(),session.version()),replacement,session.tenant());
    }

    @Transactional
    public Tokens switchTenant(Principal principal, UUID tenant) {
        if (workspaces(principal.user()).stream().noneMatch(w -> w.tenant_id().equals(tenant))) throw forbidden();
        var session = session("s.id=?", " FOR UPDATE", principal.session());
        String refresh = random();
        int version = session.version()+1;
        db.write("UPDATE auth_session SET active_tenant_id=?,refresh_hash=?,row_version=?,updated_at=? WHERE id=?",tenant,sha(refresh),version,Instant.now(),session.id());
        return new Tokens(access(session.id(),version),refresh,tenant);
    }

    void logout(Principal principal) {
        Instant now = Instant.now();
        db.write("UPDATE auth_session SET revoked_at=?,updated_at=? WHERE id=? AND user_id=?",now,now,principal.session(),principal.user());
    }

    Principal authenticate(HttpServletRequest request) {
        request.setAttribute("traceId",UUID.randomUUID());
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 180) throw unauthenticated();
        String[] fields = auth.substring(7).split("\\.",-1);
        Session session;
        try {
            if (fields.length != 4 || !MessageDigest.isEqual(sign(fields[0]+"."+fields[1]+"."+fields[2]), Base64.getUrlDecoder().decode(fields[3]))) throw unauthenticated();
            if (Instant.now().getEpochSecond() >= Long.parseLong(fields[2])) throw unauthenticated();
            session = session("s.id=?", "",UUID.fromString(fields[0]));
            if (session.version() != Integer.parseInt(fields[1])) throw unauthenticated();
        } catch (IllegalArgumentException e) { throw unauthenticated(); }
        UUID member = session.tenant() == null ? null : member(session.user(),session.tenant());
        Set<String> roles = member == null ? Set.of() : Set.copyOf(db.list(
                "SELECT role_key FROM member_role WHERE tenant_id=? AND member_id=?", (r,i) -> r.getString(1),session.tenant(),member));
        boolean platform = db.one("SELECT 1 FROM sys_user WHERE id=? AND platform_admin=TRUE", (r,i) -> true,session.user()) != null;
        return new Principal(session.user(),session.tenant(),member,roles,platform,session.id());
    }

    UUID member(UUID user, UUID tenant) {
        UUID member = db.one("""
                SELECT m.id FROM tenant_member m JOIN tenant t ON t.id=m.tenant_id
                WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'
                """,(r,i) -> Database.uuid(r,"id"),tenant,user);
        if (member == null) throw error(403,"TENANT_UNAVAILABLE","该工作空间成员身份不可用");
        return member;
    }

    private Session session(String condition, String lock, Object arg) {
        Session found = db.one("""
                SELECT s.id,s.user_id,s.active_tenant_id,s.row_version,s.expires_at
                FROM auth_session s JOIN sys_user u ON u.id=s.user_id
                WHERE s.revoked_at IS NULL AND s.expires_at>? AND u.status='ACTIVE' AND
                """ + " " + condition + lock,
                (r,i) -> new Session(Database.uuid(r,"id"),Database.uuid(r,"user_id"),Database.uuid(r,"active_tenant_id"),
                        r.getInt("row_version"),Database.time(r,"expires_at")),Instant.now(),arg);
        if (found == null) throw unauthenticated();
        return found;
    }

    private String access(UUID session, int version) {
        String value = session+"."+version+"."+Instant.now().plusSeconds(900).getEpochSecond();
        return value+"."+B64.encodeToString(sign(value));
    }
    private byte[] sign(String value) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(signingKey,"HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
    static Failure unauthenticated() { return error(401,"AUTH_REQUIRED","请重新登录"); }
    record Workspace(UUID tenant_id,String name) {}
    List<Workspace> workspaces(UUID user) {
        return db.list("""
                SELECT t.id,t.name FROM tenant_member m JOIN tenant t ON t.id=m.tenant_id
                WHERE m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'
                AND EXISTS (SELECT 1 FROM member_role r WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id
                AND r.role_key IN ('SCHOOL_ADMIN','TEACHER'))
                ORDER BY CASE WHEN t.slug='smarteval-grading' THEN 0 ELSE 1 END,t.name,t.id
                """,(r,i)->new Workspace(Database.uuid(r,"id"),r.getString("name")),user);
    }
}

@RestController
@RequestMapping("/api/v2")
class IdentityController {
    private static final String COOKIE = "smarteval_refresh";
    private final Identity identity;
    private final Database db;
    private final boolean secure;
    private final String origin;

    IdentityController(Identity identity, Database db, @Value("${smarteval.secure-cookies}") boolean secure,
                       @Value("${smarteval.public-origin}") String origin) {
        this.identity=identity; this.db=db; this.secure=secure; this.origin=origin.strip().replaceAll("/+$", "");
    }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@RequestBody JsonNode b, HttpServletRequest r) { checkOrigin(r); return tokenResponse(identity.login(b, r)); }
    @PostMapping("/auth/refresh")
    ResponseEntity<?> refresh(@CookieValue(name=COOKIE,required=false) String cookie, HttpServletRequest r) {
        checkOrigin(r); return tokenResponse(identity.refresh(cookie));
    }
    @PostMapping("/auth/tenant")
    ResponseEntity<?> tenant(@RequestBody JsonNode b,HttpServletRequest r) {
        checkOrigin(r); return tokenResponse(identity.switchTenant(identity.authenticate(r),id(text(b,"tenant_id",36))));
    }
    @PostMapping("/auth/logout")
    ResponseEntity<?> logout(HttpServletRequest r) {
        checkOrigin(r); identity.logout(identity.authenticate(r));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0)).build();
    }
    @GetMapping("/me")
    ResponseEntity<?> me(HttpServletRequest r) {
        var p = identity.authenticate(r);
        var user = db.required("SELECT display_name,row_version FROM sys_user WHERE id=?",
                (row,i)->Map.of("display_name",row.getString(1),"version",row.getInt(2)),p.user());
        return ResponseEntity.ok().eTag(etag((int)user.get("version"))).body(Map.of("id",p.user(),
                "display_name",user.get("display_name"),"workspaces",identity.workspaces(p.user()),
                "tenant_id",p.requireTenant(),"can_manage_keys",p.roles().contains("SCHOOL_ADMIN")));
    }
    @PatchMapping("/me")
    ResponseEntity<?> updateMe(@RequestBody JsonNode b,@RequestHeader("If-Match") String tag,HttpServletRequest r) {
        var p = identity.authenticate(r);
        int version = db.required("SELECT row_version FROM sys_user WHERE id=?",(row,i)->row.getInt(1),p.user());
        match(tag,version);
        if (db.write("UPDATE sys_user SET display_name=?,row_version=row_version+1,updated_at=? WHERE id=? AND row_version=?",
                text(b,"display_name",160),Instant.now(),p.user(),version)!=1) throw error(412,"STALE_VERSION","请重新读取资源版本");
        return ResponseEntity.ok().eTag(etag(version+1)).body(Map.of("id",p.user(),"row_version",version+1));
    }

    private ResponseEntity<?> tokenResponse(Identity.Tokens tokens) {
        var view = new LinkedHashMap<String,Object>();
        view.put("access_token",tokens.access()); view.put("expires_in",900); view.put("tenant_id",tokens.tenant());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(tokens.refresh(),604800)).body(view);
    }
    private String cookie(String value,long age) {
        return ResponseCookie.from(COOKIE,value).httpOnly(true).secure(secure).sameSite("Lax").path("/api/v2/auth").maxAge(age).build().toString();
    }
    private void checkOrigin(HttpServletRequest request) {
        String supplied=request.getHeader(HttpHeaders.ORIGIN);
        if (supplied != null && !origin.equals(supplied.strip().replaceAll("/+$", ""))) throw forbidden();
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && "cross-site".equalsIgnoreCase(fetchSite.strip())) throw forbidden();
    }
}
