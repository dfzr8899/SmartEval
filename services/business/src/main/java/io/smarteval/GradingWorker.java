package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="smarteval.worker-enabled", havingValue="true", matchIfMissing=true)
class GradingWorker {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GradingWorker.class);
    private static final Set<String> RETRYABLE = Set.of(
            "AI_UNAVAILABLE", "JUDGE_UNAVAILABLE", "JUDGE_ERROR", "JUDGE_WAIT_TIMEOUT", "GRADING_STAGE_FAILED");
    private static final long[] RETRY_BACKOFF_SECONDS = {5, 15};
    private final Database db;
    private final GradingJobs jobs;
    private final GradingEngine engine;
    private final Webhooks webhooks;
    private final GradingAssets assets;
    private final CodeSimilarity similarity;
    private final long assetRetentionHours;
    private final Semaphore cleanupSlot = new Semaphore(1);
    private final Semaphore similaritySlot = new Semaphore(1);
    private final Semaphore slots;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private boolean prepareFirst = true;
    private volatile boolean stopping;

    record Work(UUID job, int run, int ordinal, int attempts, JsonNode input) {}
    record Preparation(UUID job, int attempts) {}

    GradingWorker(Database db, GradingJobs jobs, GradingEngine engine, Webhooks webhooks, GradingAssets assets, CodeSimilarity similarity,
                  @Value("${SMARTEVAL_ASSET_RETENTION_HOURS:24}") long assetRetentionHours,
                  @Value("${SMARTEVAL_WORKER_CONCURRENCY:8}") int concurrency) {
        if (concurrency < 1 || concurrency > 128) throw new IllegalArgumentException("Worker concurrency must be 1–128");
        if (assetRetentionHours < 1 || assetRetentionHours > 87600) throw new IllegalArgumentException("Asset retention must be 1–87600 hours");
        this.assets = assets;
        this.similarity = similarity;
        this.assetRetentionHours = assetRetentionHours;
        this.db=db; this.jobs=jobs; this.engine=engine; this.webhooks=webhooks; this.slots=new Semaphore(concurrency);
    }

    @Scheduled(fixedDelay=1000)
    void dispatchSimilarity() {
        if (stopping || !similaritySlot.tryAcquire()) return;
        try {
            executor.execute(() -> {
                try {
                    similarity.processOne();
                } catch (RuntimeException e) {
                    LOG.warn("Could not persist code similarity",e);
                } finally {
                    similaritySlot.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            similaritySlot.release();
        }
    }

    @Scheduled(fixedDelayString="${SMARTEVAL_ASSET_CLEANUP_INTERVAL_MS:3600000}", initialDelay=60000)
    void cleanOrphans() {
        if (stopping || !cleanupSlot.tryAcquire()) return;
        try {
            executor.execute(() -> {
                try {
                    assets.cleanOrphans(Instant.now().minus(java.time.Duration.ofHours(assetRetentionHours)));
                } catch (RuntimeException e) {
                    LOG.warn("Asset cleanup failed",e);
                } finally {
                    cleanupSlot.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            cleanupSlot.release();
        }
    }

    @Scheduled(fixedDelay=200)
    void dispatch() {
        if (stopping) return;
        int capacity = slots.availablePermits();
        for (int i = 0; i < capacity && !stopping && slots.tryAcquire(); i++) {
            boolean submitted = false;
            try {
                Runnable task = prepareFirst ? claimPreparation() : claimGrade();
                if (task == null) task = prepareFirst ? claimGrade() : claimPreparation();
                if (task == null) break;
                prepareFirst = !prepareFirst;
                Runnable work = task;
                executor.execute(() -> {
                    try { work.run(); }
                    catch (RuntimeException e) { LOG.warn("Worker could not persist outcome", e); }
                    finally { slots.release(); }
                });
                submitted = true;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                break;
            } finally {
                if (!submitted) slots.release();
            }
        }
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

    boolean prepareOne() { return runOne(claimPreparation()); }
    boolean gradeOne() { return runOne(claimGrade()); }

    private static boolean runOne(Runnable work) {
        if (work == null) return false;
        work.run();
        return true;
    }

    private Runnable claimPreparation() {
        return db.transaction(() -> {
            Instant now = Instant.now();
            var work = db.one("""
                    SELECT j.id,j.attempts FROM grading_job j
                    WHERE j.status='PREPARING' AND (j.available_at IS NULL OR j.available_at<=?)
                    AND (j.lease_until IS NULL OR j.lease_until<=?)
                    AND EXISTS (SELECT 1 FROM tenant t WHERE t.id=j.tenant_id AND t.status='ACTIVE')
                    ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(r,i)->new Preparation(Database.uuid(r,"id"),r.getInt("attempts")),now,now);
            if (work == null) return null;
            UUID lease = UUID.randomUUID();
            db.write("UPDATE grading_job SET attempts=attempts+1,lease_id=?,lease_until=?,updated_at=? WHERE id=?",
                    lease,now.plusSeconds(210),now,work.job());
            return () -> prepare(work,lease);
        });
    }

    private void prepare(Preparation work, UUID lease) {
        JsonNode prepared = null;
        String failure = "LEASE_EXPIRED";
        if (work.attempts() < 3) {
            try {
                prepared = engine.prepare(jobs.load(work.job()));
                if (!prepared.path("draft").path("questions").isArray() || !prepared.path("sources").isArray())
                    throw Api.error(503,"PREPARATION_INVALID","解析结果格式无效");
                failure = null;
            } catch (RuntimeException e) {
                failure = error(e);
            }
        }
        persistPreparation(work,lease,prepared,failure);
    }

    private void persistPreparation(Preparation work, UUID lease, JsonNode prepared, String failure) {
        db.transaction(() -> {
            Instant now = Instant.now();
            if (failure == null) {
                int changed = db.write("""
                        UPDATE grading_job SET status='AWAITING_CONFIRMATION',draft=?,sources=?,error_code=NULL,
                        available_at=NULL,revision=revision+1,lease_id=NULL,lease_until=NULL,updated_at=?
                        WHERE id=? AND lease_id=? AND lease_until>?
                        """,db.jsonValue(prepared.path("draft")),db.jsonValue(prepared.path("sources")),now,work.job(),lease,now);
                if (changed == 1 && webhooks.configured(work.job())) jobs.webhook(work.job(),"PREPARING");
            } else if (RETRYABLE.contains(failure) && work.attempts() < 2) {
                long delay = RETRY_BACKOFF_SECONDS[Math.min(RETRY_BACKOFF_SECONDS.length - 1, work.attempts())];
                db.write("""
                        UPDATE grading_job SET status='PREPARING',error_code=?,available_at=?,
                        lease_id=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_id=? AND lease_until>?
                        """,failure,now.plusSeconds(delay),now,work.job(),lease,now);
            } else {
                int changed = db.write("""
                        UPDATE grading_job SET status='PREPARATION_FAILED',error_code=?,available_at=NULL,
                        revision=revision+1,lease_id=NULL,lease_until=NULL,updated_at=?
                        WHERE id=? AND lease_id=? AND lease_until>?
                        """,failure,now,work.job(),lease,now);
                if (changed == 1 && webhooks.configured(work.job())) jobs.webhook(work.job(),"PREPARING");
            }
        });
    }

    private Runnable claimGrade() {
        return db.transaction(() -> {
            Instant now = Instant.now();
            var item = db.one("""
                    SELECT i.* FROM grading_item i
                    WHERE ((i.status='QUEUED' AND (i.available_at IS NULL OR i.available_at<=?))
                           OR (i.status='RUNNING' AND i.lease_until<=?))
                    AND EXISTS (SELECT 1 FROM grading_job j JOIN tenant t ON t.id=j.tenant_id
                                WHERE j.id=i.job_id AND j.run_no=i.run_no AND t.status='ACTIVE')
                    ORDER BY i.created_at,i.job_id,i.ordinal_no LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(r,i)->new Work(Database.uuid(r,"job_id"),r.getInt("run_no"),r.getInt("ordinal_no"),
                            r.getInt("attempts"),db.parse(r.getString("item_input"))),now,now);
            if (item == null) return null;
            UUID lease = UUID.randomUUID();
            db.write("""
                    UPDATE grading_item SET status='RUNNING',attempts=attempts+1,lease_id=?,lease_until=?
                    WHERE job_id=? AND run_no=? AND ordinal_no=?
                    """,lease,now.plusSeconds(180),item.job(),item.run(),item.ordinal());
            return () -> grade(item,lease);
        });
    }

    private void grade(Work item, UUID lease) {
        long start = System.nanoTime();
        LOG.debug("grade start job={} run={} item={} virtual={}",item.job(),item.run(),item.ordinal(),Thread.currentThread().isVirtual());
        try {
            if (item.attempts() >= 3) {
                finish(item,lease,"FAILED",null,"LEASE_EXPIRED");
                return;
            }
            JsonNode result;
            try {
                var ctx = jobs.executionContext(item.job());
                result = engine.score(item.input(), ctx.configuration(), ctx.owner(), ctx.confirmedBy());
            } catch (RuntimeException e) {
                String code = error(e);
                if (RETRYABLE.contains(code) && item.attempts() < 2) {
                    long delay = RETRY_BACKOFF_SECONDS[Math.min(RETRY_BACKOFF_SECONDS.length - 1, item.attempts())];
                    requeueGrade(item, lease, code, Instant.now().plusSeconds(delay));
                    return;
                }
                finish(item,lease,"FAILED",null,code);
                return;
            }
            finish(item,lease,result.path("final_score").isIntegralNumber()?"SCORED":"REVIEW_REQUIRED",result,null);
        } finally {
            LOG.debug("grade finish job={} run={} item={} elapsed_ms={}",item.job(),item.run(),item.ordinal(),
                    (System.nanoTime()-start)/1_000_000);
        }
    }

    private void requeueGrade(Work item, UUID lease, String error, Instant availableAt) {
        db.write("""
                UPDATE grading_item SET status='QUEUED',error_code=?,available_at=?,lease_id=NULL,lease_until=NULL
                WHERE job_id=? AND run_no=? AND ordinal_no=? AND lease_id=? AND lease_until>?
                """,error,availableAt,item.job(),item.run(),item.ordinal(),lease,Instant.now());
    }

    private void finish(Work item, UUID lease, String status, JsonNode result, String error) {
        if (!webhooks.configured(item.job())) {
            saveGrade(item,lease,status,result,error);
            return;
        }
        db.transaction(() -> {
            if (webhooks.lockJobForEvent(item.job()) && saveGrade(item,lease,status,result,error)==1) {
                jobs.webhook(item.job(),"GRADING");
            }
        });
    }

    private int saveGrade(Work item, UUID lease, String status, JsonNode result, String error) {
        return db.write("""
                UPDATE grading_item SET status=?,result=?,error_code=?,available_at=NULL,lease_id=NULL,lease_until=NULL,revision=revision+1
                WHERE job_id=? AND run_no=? AND ordinal_no=? AND lease_id=? AND lease_until>?
                """,status,result==null?null:db.jsonValue(result),error,item.job(),item.run(),item.ordinal(),lease,Instant.now());
    }

    private static String error(RuntimeException e) {
        if (e instanceof Api.Failure failure) return failure.code;
        LOG.warn("Grading stage failed ({})",e.getClass().getSimpleName());
        return "GRADING_STAGE_FAILED";
    }
}
