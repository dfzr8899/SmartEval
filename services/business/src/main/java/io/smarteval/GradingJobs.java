package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class GradingJobs {
    private final Database db;
    private final GradingAssets assets;
    private final GradingEngine engine;
    private final Webhooks webhooks;
    private final CodeSimilarity similarity;

    record Job(UUID id, UUID workspace, UUID client, UUID owner, String title, String status, int revision, int run,
               JsonNode input, JsonNode draft, JsonNode sources, JsonNode configuration, Instant created, Integer attempts,
               String error, UUID confirmedBy, Instant confirmedAt) {}
    record ExecutionContext(JsonNode configuration, UUID owner, UUID confirmedBy) {}
    record Item(int ordinal, int revision, String status, JsonNode input, JsonNode result, String error) {}

    GradingJobs(Database db, GradingAssets assets, GradingEngine engine, Webhooks webhooks, CodeSimilarity similarity) {
        this.db = db;
        this.assets = assets;
        this.engine = engine;
        this.webhooks = webhooks;
        this.similarity = similarity;
    }

    private static Job row(Database db, ResultSet r) throws SQLException {
        return new Job(Database.uuid(r,"id"),Database.uuid(r,"tenant_id"),Database.uuid(r,"client_id"),
                Database.uuid(r,"owner_id"),
                r.getString("title"),r.getString("status"),r.getInt("revision"),r.getInt("run_no"),
                nullable(db,r,"submission_input"),nullable(db,r,"draft"),nullable(db,r,"sources"),nullable(db,r,"pinned_config"),
                Database.time(r,"created_at"),r.getInt("attempts"),r.getString("error_code"),
                Database.uuid(r,"confirmed_by"),Database.time(r,"confirmed_at"));
    }

    private static Job headline(ResultSet r) throws SQLException {
        return new Job(Database.uuid(r,"id"),Database.uuid(r,"tenant_id"),Database.uuid(r,"client_id"),null,
                r.getString("title"),r.getString("status"),r.getInt("revision"),r.getInt("run_no"),
                null,null,null,null,Database.time(r,"created_at"),r.getInt("attempts"),r.getString("error_code"),
                Database.uuid(r,"confirmed_by"),Database.time(r,"confirmed_at"));
    }

    private static JsonNode nullable(Database db, ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? db.json.nullNode() : db.parse(value);
    }

    Job load(UUID id) {
        return db.required("SELECT * FROM grading_job WHERE id=?", (r,i)->row(db,r),id);
    }

    JsonNode configuration(UUID id) {
        return db.required("SELECT pinned_config FROM grading_job WHERE id=?", (r,i)->nullable(db,r,"pinned_config"),id);
    }

    ExecutionContext executionContext(UUID id) {
        return db.required("SELECT pinned_config,owner_id,confirmed_by FROM grading_job WHERE id=?",
                (r,i)->new ExecutionContext(nullable(db,r,"pinned_config"),Database.uuid(r,"owner_id"),Database.uuid(r,"confirmed_by")),id);
    }

    Job owned(GradingAccess.Actor actor, UUID id, boolean lock) {
        var job = db.required("SELECT * FROM grading_job WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (r,i)->row(db,r),actor.workspace(),id);
        if (actor.client() != null && !actor.client().equals(job.client())) throw missing();
        return job;
    }

    Job ownedHeadline(GradingAccess.Actor actor, UUID id) {
        return db.required("""
                SELECT id,tenant_id,client_id,title,status,revision,run_no,created_at,attempts,error_code,confirmed_by,confirmed_at
                FROM grading_job WHERE tenant_id=? AND id=? AND (? IS NULL OR client_id=?)
                """,(r,i)->headline(r),actor.workspace(),id,actor.client(),actor.client());
    }

    Job create(GradingAccess.Actor actor, JsonNode body, String key) {
        return create(actor,body,key,db.json.createArrayNode());
    }

    Job create(GradingAccess.Actor actor, JsonNode body, String key, JsonNode collectedAnswers) {
        if (!key.matches("[A-Za-z0-9._:-]{1,100}")) throw error(422,"IDEMPOTENCY_KEY","请求标识格式无效");
        byte[] fingerprint = sha(db.encode(canonical(body)));
        var prior = prior(actor,key,fingerprint);
        if (prior != null) return prior;
        if (body.hasNonNull("auto_confirm") && !body.path("auto_confirm").isBoolean())
            throw error(422,"INVALID_FIELD","auto_confirm 必须为布尔值");
        boolean auto = body.path("auto_confirm").asBoolean(false);
        if (auto && (body.hasNonNull("materials") && !array(body,"materials",0,GradingInput.MAX_MATERIALS).isEmpty() || body.hasNonNull("template")))
            throw error(422,"AUTO_CONFIRM_REQUIRES_STRUCTURED_INPUT","auto_confirm 需要完整 questions，不能使用待解析材料或模板");
        String webhookUrl = body.hasNonNull("webhook_url") ? webhooks.validate(text(body,"webhook_url",2048)) : null;
        String title = text(body,"title",160);
        if (db.encode(body).length() > 1_500_000) throw error(422,"INPUT_TOO_LARGE","结构化作业超过大小限制");
        var input = db.json.createObjectNode();
        var questions = auto ? array(body,"questions",1,80)
                : body.hasNonNull("questions") ? array(body,"questions",0,80) : db.json.createArrayNode();
        var questionIds = new HashSet<String>();
        var submissions = new LinkedHashSet<String>();
        collectedAnswers.forEach(a -> submissions.add(a.path("submission_id").asText()));
        if (!collectedAnswers.isEmpty()) input.set("collected_answers",collectedAnswers);
        for (var q : questions) {
            if (!questionIds.add(text(q,"id",80))) throw error(422,"DUPLICATE_QUESTION","题号重复");
            bodyText(q,"prompt",16_000);
            if (!GradingInput.KINDS.contains(text(q,"kind",10))) throw error(422,"INVALID_KIND","题型应为 TEXT、EXACT 或 CODE");
            if (auto || q.hasNonNull("answers")) for (var a : array(q,"answers",auto?1:0,GradingInput.MAX_SUBMISSIONS))
                submissions.add(text(a,"submission_id",100));
        }
        input.set("questions",questions);
        var references = input.putArray("materials");
        var assetsSeen = new HashSet<UUID>();
        long bytes = 0;
        if (body.hasNonNull("materials")) for (var material : array(body,"materials",0,GradingInput.MAX_MATERIALS)) {
            UUID id = id(text(material,"asset_id",36));
            if (!assetsSeen.add(id)) throw error(422,"DUPLICATE_ASSET","同一材料只能添加一次");
            String role = text(material,"role",10);
            if (!Set.of("QUESTION","REFERENCE","ANSWER").contains(role)) throw error(422,"INVALID_ROLE","材料角色无效");
            String submission = role.equals("ANSWER") ? text(material,"submission_id",100) : "";
            if (role.equals("ANSWER")) submissions.add(submission);
            var asset = assets.owned(actor,id);
            bytes += asset.size();
            references.add(db.json.valueToTree(asset.reference(role,submission)));
        }
        if (bytes > GradingInput.MAX_MATERIAL_BYTES) throw error(422,"MATERIAL_SIZE","一个任务的原始材料合计最多 200 MB");
        if (submissions.isEmpty() || submissions.size()>GradingInput.MAX_SUBMISSIONS)
            throw error(422,"SUBMISSIONS_REQUIRED","请提交 1 至 100 份待批答卷");
        if (body.hasNonNull("template")) {
            var selection = body.path("template");
            var template = template(actor.workspace(),id(text(selection,"id",36)),number(selection,"version",1,Integer.MAX_VALUE));
            input.set("template_questions",template.questions());
        }
        input.set("submissions",db.json.valueToTree(submissions));
        var draft = db.json.createObjectNode().set("questions",questions);
        var sources = db.json.createArrayNode();
        if (auto) GradingInput.validate(draft,sources,input);
        var config = auto ? engine.configurationForConfirmation(questions, actor.user()) : null;
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            return db.transaction(() -> {
                assetsSeen.stream().sorted().forEach(asset -> assets.lockForJob(actor.workspace(),asset));
                db.write("""
                        INSERT INTO grading_job(id,tenant_id,client_id,owner_id,request_key,request_hash,title,status,
                            submission_input,draft,sources,pinned_config,confirmed_by,confirmed_at,run_no,created_at,updated_at,
                            webhook_url,webhook_secret)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,id,actor.workspace(),actor.client(),actor.owner(),key.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        fingerprint,title,auto?"CONFIRMED":"PREPARING",db.jsonValue(input),auto?db.jsonValue(draft):null,
                        auto?db.jsonValue(sources):null,auto?db.jsonValue(config):null,auto?actor.user():null,auto?now:null,
                        auto?1:0,now,now,webhookUrl,webhookUrl==null?null:webhooks.encryptSecret(Identity.random()));
                db.batch("INSERT INTO grading_job_asset(job_id,asset_id) VALUES(?,?)",
                        assetsSeen.stream().map(asset -> new Object[]{id,asset}).toList());
                Job created = load(id);
                if (auto) enqueue(created,1);
                return created;
            });
        } catch (DuplicateKeyException e) {
            Job existing = prior(actor,key,fingerprint);
            if (existing != null) return existing;
            throw e;
        }
    }

    ObjectNode creationResponse(Job job) {
        var view = summary(job);
        String key = webhooks.decryptSecret(db.one("SELECT webhook_secret FROM grading_job WHERE id=?",(r,i)->r.getString(1),job.id()));
        if (key != null) view.put("webhook_secret",key);
        return view;
    }

    List<Map<String, Object>> webhookDeliveries(Job job) {
        return webhooks.deliveries(job.id());
    }

    void webhook(UUID id, String previousStatus) {
        webhooks.record(id,previousStatus,summary(load(id)));
    }

    private Job prior(GradingAccess.Actor actor, String key, byte[] fingerprint) {
        return db.one("SELECT * FROM grading_job WHERE tenant_id=? AND owner_id=? AND request_key=?",
                (r,i)->{
                    if (!java.security.MessageDigest.isEqual(fingerprint,r.getBytes("request_hash"))) {
                        throw error(409,"IDEMPOTENCY_CONFLICT","同一请求标识已用于不同内容");
                    }
                    return row(db,r);
                },actor.workspace(),actor.owner(),key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String,JsonNode>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(),canonical(entry.getValue())));
            var result = db.json.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = db.json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    List<ObjectNode> list(GradingAccess.Actor actor, int page) {
        if (page < 1 || page > 10_000) throw error(422,"INVALID_PAGE","页码无效");
        record CountedJob(Job job, String status, int count) {}
        var pageRows = db.list("""
                WITH page_jobs AS (
                    SELECT id,tenant_id,client_id,title,status,revision,run_no,created_at,attempts,error_code,confirmed_by,confirmed_at
                    FROM grading_job WHERE tenant_id=? AND (? IS NULL OR client_id=?)
                    ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?
                ), item_counts AS (
                    SELECT i.job_id,i.status AS item_status,COUNT(*) AS item_count FROM grading_item i
                    JOIN page_jobs p ON p.id=i.job_id AND p.run_no=i.run_no
                    GROUP BY i.job_id,i.status
                )
                SELECT p.*,c.item_status,c.item_count FROM page_jobs p LEFT JOIN item_counts c ON p.id=c.job_id
                ORDER BY p.created_at DESC,p.id DESC
                """,(r,i)->new CountedJob(headline(r),r.getString("item_status"),r.getInt("item_count")),
                actor.workspace(),actor.client(),actor.client(),(page-1)*50);
        var views = new LinkedHashMap<UUID,ObjectNode>();
        for (var row : pageRows) {
            var job = row.job();
            var view = views.computeIfAbsent(job.id(),ignored->jobView(job));
            if (job.run()>0) {
                var counts = view.withObject("/counts");
                if (row.status()!=null) counts.put(row.status(),row.count());
                state(view,counts);
            }
        }
        return List.copyOf(views.values());
    }

    ObjectNode summary(Job job) {
        var view = jobView(job);
        if (job.run()==0) return view;
        var counts = view.putObject("counts");
        for (var entry : db.list("""
                SELECT status,COUNT(*) AS n FROM grading_item WHERE job_id=? AND run_no=? GROUP BY status
                """,(r,i)->Map.entry(r.getString(1),r.getInt(2)),job.id(),job.run())) counts.put(entry.getKey(),entry.getValue());
        return state(view,counts);
    }

    private ObjectNode jobView(Job job) {
        var view = db.json.createObjectNode().put("id",job.id().toString()).put("title",job.title())
                .put("created_at",job.created().toString()).put("revision",job.revision()).put("run",job.run())
                .put("status_url","/api/v2/grading-jobs/"+job.id());
        if (job.error()!=null) view.put("error_code",job.error());
        if (job.confirmedAt()!=null) view.put("confirmed_at",job.confirmedAt().toString());
        return view.put("status",job.status());
    }

    private static ObjectNode state(ObjectNode view, JsonNode counts) {
        boolean hasDone = counts.has("SCORED") || counts.has("REVIEW_REQUIRED") || counts.has("FAILED");
        String status = counts.has("RUNNING") || (counts.has("QUEUED") && hasDone) ? "GRADING"
                : counts.has("QUEUED") ? "QUEUED"
                : counts.has("FAILED") || counts.has("REVIEW_REQUIRED") ? "REVIEW_REQUIRED" : "COMPLETED";
        return view.put("status",status);
    }

    Object draft(Job job) {
        if (job.draft().isNull()) throw error(409,"DRAFT_UNAVAILABLE","当前任务尚未生成草案");
        return Map.of("draft",job.draft(),"sources",job.sources(),"submissions",job.input().path("submissions"),
                "materials",publicMaterials(job));
    }

    private JsonNode publicMaterials(Job job) {
        var result = db.json.createArrayNode();
        for (var material : job.input().path("materials")) {
            ObjectNode copy = material.deepCopy();
            copy.remove("sha256");
            result.add(copy);
        }
        return result;
    }

    @Transactional
    public Job edit(GradingAccess.Actor actor, UUID id, String tag, JsonNode body) {
        var job = owned(actor,id,true);
        match(tag,job.revision());
        ensureStatus(job,"AWAITING_CONFIRMATION");
        array(body,"questions",0,80);
        if (db.encode(body).length()>2_000_000) throw error(422,"INPUT_TOO_LARGE","草案超过大小限制");
        db.write("UPDATE grading_job SET draft=?,revision=revision+1,updated_at=? WHERE id=?",db.jsonValue(body),Instant.now(),id);
        return load(id);
    }

    @Transactional
    public Job confirm(GradingAccess.Actor actor, UUID id, String tag) {
        var job = owned(actor,id,true);
        match(tag,job.revision());
        ensureStatus(job,"AWAITING_CONFIRMATION");
        GradingInput.validate(job.draft(),job.sources(),job.input());
        var config = engine.configurationForConfirmation(job.draft().path("questions"), actor.user());
        var now = Instant.now();
        db.write("""
                UPDATE grading_job SET status='CONFIRMED',revision=revision+1,confirmed_by=?,confirmed_at=?,
                pinned_config=?,run_no=1,updated_at=? WHERE id=?
                """,actor.user(),now,db.jsonValue(config),now,id);
        enqueue(job,1);
        if (actor.user() != null) {
            db.audit(actor.workspace(), actor.user(), "JOB_CONFIRMED", "grading_job", id,
                    Map.of("title", job.title(), "run", 1));
        }
        return load(id);
    }

    private void enqueue(Job job, int run) {
        int ordinal=0;
        Instant now = Instant.now();
        var rows = new ArrayList<Object[]>();
        var items = new ArrayList<JsonNode>();
        for (var question : job.draft().path("questions")) for (var answer : question.path("answers")) {
            var item = GradingInput.withoutAnswers(question);
            item.set("answer",answer);
            items.add(item);
            rows.add(new Object[]{job.id(),run,++ordinal,db.jsonValue(item),now});
        }
        db.batch("INSERT INTO grading_item(job_id,run_no,ordinal_no,item_input,created_at) VALUES(?,?,?,?,?)",rows);
        similarity.schedule(job.id(),run,items);
    }

    @Transactional
    public Job retry(GradingAccess.Actor actor, UUID id, String tag, Integer ordinal, boolean regrade) {
        var job = owned(actor,id,true);
        match(tag,job.revision());
        if (job.status().equals("PREPARATION_FAILED") && !regrade && ordinal==null) {
            db.write("UPDATE grading_job SET status='PREPARING',attempts=0,available_at=NULL,error_code=NULL,revision=revision+1,updated_at=? WHERE id=?",Instant.now(),id);
            return load(id);
        }
        ensureStatus(job,"CONFIRMED");
        if (regrade) {
            var current = summary(job).path("status").asText();
            if (Set.of("GRADING","QUEUED").contains(current)) throw error(409,"JOB_ACTIVE","请等待当前批改完成");
            enqueue(job,job.run()+1);
            db.write("UPDATE grading_job SET run_no=run_no+1,revision=revision+1,updated_at=? WHERE id=?",Instant.now(),id);
        } else {
            if (ordinal==null) throw error(422,"ITEM_REQUIRED","请指定需要重试的单题");
            if (db.write("""
                    UPDATE grading_item SET status='QUEUED',attempts=0,available_at=NULL,error_code=NULL,revision=revision+1
                    WHERE job_id=? AND run_no=? AND ordinal_no=? AND status='FAILED'
                    """,id,job.run(),ordinal)==0) throw error(409,"NOT_RETRYABLE","只有失败的单题可以重试");
            db.write("UPDATE grading_job SET revision=revision+1,updated_at=? WHERE id=?",Instant.now(),id);
        }
        return load(id);
    }

    @Transactional
    public void cancel(GradingAccess.Actor actor, UUID id, String tag) {
        var job = owned(actor,id,true);
        match(tag,job.revision());
        if (job.run()>0) throw error(409,"JOB_ALREADY_CONFIRMED","已确认的任务不能取消");
        db.write("UPDATE grading_job SET status='CANCELLED',lease_id=NULL,lease_until=NULL,revision=revision+1,updated_at=? WHERE id=?",Instant.now(),id);
    }

    List<Item> items(Job job, int run) {
        return db.list("SELECT * FROM grading_item WHERE job_id=? AND run_no=? ORDER BY ordinal_no",
                (r,i)->readItem(r),job.id(),run);
    }

    private Item readItem(ResultSet r) throws SQLException {
        return new Item(r.getInt("ordinal_no"),r.getInt("revision"),r.getString("status"),
                nullable(db,r,"item_input"),nullable(db,r,"result"),r.getString("error_code"));
    }

    ObjectNode report(Job job, Integer requested) {
        int run = requested==null ? job.run() : requested;
        if (run<=0 || run>job.run()) throw missing();
        var totals = new LinkedHashMap<String,int[]>();
        var result = db.json.createObjectNode().put("run",run).put("latest_run",job.run()).put("job_id",job.id().toString());
        result.put("confirmed_at",job.confirmedAt().toString()).set("workflow",job.configuration());
        var output = result.putArray("items");
        for (Item item : items(job,run)) {
            var q = item.input();
            var answer = q.path("answer");
            int max = q.path("max_score").asInt();
            var row = output.addObject().put("ordinal",item.ordinal()).put("revision",item.revision()).put("status",item.status())
                    .put("question_id",q.path("id").asText()).put("prompt",q.path("prompt").asText())
                    .put("kind",q.path("kind").asText()).put("max_score",max).put("submission_id",answer.path("submission_id").asText())
                    .put("answer",answer.path("text").asText());
            row.set("source_ids",answer.path("source_ids").isArray()?answer.path("source_ids"):db.json.createArrayNode());
            JsonNode reportItem = item.result().deepCopy();
            for (var criterion : reportItem.path("criteria")) {
                for (var rubric : q.path("rubric")) {
                    if (rubric.path("id").equals(criterion.path("id"))) {
                        ((ObjectNode)criterion).put("description",rubric.path("description").asText());
                        break;
                    }
                }
                String quote = criterion.path("quote").asText();
                if (quote.isBlank()) continue;
                var matching = ((ObjectNode)criterion).putArray("source_ids");
                var allowed = new HashSet<String>();
                answer.path("source_ids").forEach(s -> allowed.add(s.asText()));
                job.sources().forEach(s -> {
                    String sourceId = s.path("id").asText();
                    if (allowed.contains(sourceId) && s.path("text").asText().contains(quote)) matching.add(sourceId);
                });
            }
            row.set("result",reportItem);
            if (item.error()!=null) row.put("error_code",item.error());
            String submission = answer.path("submission_id").asText();
            int[] tally = totals.computeIfAbsent(submission,ignored -> new int[6]);
            tally[0]+=max;
            tally[5]++;
            if (item.result().path("suggested_score").isIntegralNumber()) {
                tally[1]+=item.result().path("suggested_score").asInt();
                tally[2]++;
            }
            if (item.result().path("final_score").isIntegralNumber()) {
                tally[3]+=item.result().path("final_score").asInt();
                tally[4]++;
            }
        }
        var sums = result.putArray("submissions");
        totals.forEach((id,t)->sums.addObject().put("submission_id",id).put("max_score",t[0])
                .put("suggested_points_so_far",t[1]).put("scored_items",t[2]).put("final_points_so_far",t[3])
                .put("final_items",t[4]).put("total_items",t[5]));
        result.set("sources",job.sources());
        result.set("materials",publicMaterials(job));
        similarity.appendReport(job.id(),run,result);
        return result;
    }

    @Transactional
    public void review(GradingAccess.Actor actor, UUID id, int ordinal, String tag, JsonNode body) {
        var job = owned(actor,id,true);
        ensureStatus(job,"CONFIRMED");
        if (number(body,"run",1,Integer.MAX_VALUE)!=job.run()) throw error(412,"STALE_RUN","请重新读取当前报告");
        var item = db.required("SELECT * FROM grading_item WHERE job_id=? AND run_no=? AND ordinal_no=? FOR UPDATE",
                (r,i)->readItem(r),id,job.run(),ordinal);
        match(tag,item.revision());
        if (!Set.of("REVIEW_REQUIRED","SCORED","FAILED").contains(item.status())) throw error(409,"ITEM_ACTIVE","单题仍在批改");
        int score = number(body,"score",0,item.input().path("max_score").asInt());
        String reason = text(body,"reason",1000);
        ObjectNode revised = item.result().isObject() ? item.result().deepCopy() : db.json.createObjectNode();
        revised.put("final_score",score).put("decision","TEACHER").put("review_reason",reason);
        if (actor.user()==null) throw forbidden();
        String priorStatus = webhooks.configured(id) ? summary(job).path("status").asText() : null;
        db.write("""
                INSERT INTO grading_review(job_id,run_no,ordinal_no,revision,reviewer_id,reason,previous_result,revised_result,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,id,job.run(),ordinal,item.revision()+1,actor.user(),reason,db.jsonValue(item.result()),db.jsonValue(revised),Instant.now());
        db.write("""
                UPDATE grading_item SET result=?,status='SCORED',error_code=NULL,revision=revision+1
                WHERE job_id=? AND run_no=? AND ordinal_no=?
                """,db.jsonValue(revised),id,job.run(),ordinal);
        db.audit(actor.workspace(), actor.user(), "ITEM_REVIEWED", "grading_job", id,
                Map.of("run", job.run(), "ordinal", ordinal, "score", score));
        if (priorStatus != null) webhook(id,priorStatus);
    }

    Object reviewHistory(Job job, int run, int ordinal) {
        return db.list("""
                SELECT * FROM grading_review WHERE job_id=? AND run_no=? AND ordinal_no=? ORDER BY revision
                """,(r,i)->Map.of("revision",r.getInt("revision"),"reason",r.getString("reason"),
                "reviewer_id",Database.uuid(r,"reviewer_id"),"created_at",Database.time(r,"created_at"),
                "previous",nullable(db,r,"previous_result"),"revised",nullable(db,r,"revised_result")),job.id(),run,ordinal);
    }

    record Template(UUID id, int version, String title, JsonNode questions) {}

    private Template templateRow(ResultSet r) throws SQLException {
        return new Template(Database.uuid(r,"id"),r.getInt("version_no"),r.getString("title"),nullable(db,r,"questions"));
    }

    Template template(UUID workspace, UUID id, int version) {
        return db.required("SELECT * FROM grading_template WHERE tenant_id=? AND id=? AND version_no=?",
                (r,i)->templateRow(r),workspace,id,version);
    }

    void deleteTemplate(UUID workspace, UUID id) {
        db.transaction(() -> {
            var versions = db.list("""
                    SELECT version_no FROM grading_template WHERE tenant_id=? AND id=? ORDER BY version_no FOR UPDATE
                    """,(r,i)->r.getInt(1),workspace,id);
            if (versions.isEmpty()) throw missing();
            if (db.one("SELECT id FROM grading_collection WHERE tenant_id=? AND template_id=? LIMIT 1",
                    (r,i)->Database.uuid(r,"id"),workspace,id)!=null)
                throw error(409,"TEMPLATE_IN_USE","该模板仍用于收卷");
            db.write("DELETE FROM grading_template WHERE tenant_id=? AND id=?",workspace,id);
        });
    }

    List<Template> templates(UUID workspace) {
        return db.list("SELECT * FROM grading_template WHERE tenant_id=? ORDER BY created_at DESC,version_no DESC LIMIT 100",
                (r,i)->templateRow(r),workspace);
    }

    @Transactional
    public Template saveTemplate(GradingAccess.Actor actor, UUID jobId, JsonNode body) {
        var job = owned(actor,jobId,true);
        ensureStatus(job,"CONFIRMED");
        String title = text(body,"title",160);
        var questions = db.json.createArrayNode();
        job.draft().path("questions").forEach(q->questions.add(GradingInput.withoutAnswers(q)));
        UUID templateId = body.hasNonNull("template_id") ? id(text(body,"template_id",36)) : UUID.randomUUID();
        int version = body.hasNonNull("template_id") ? db.required("""
                SELECT version_no FROM grading_template WHERE tenant_id=? AND id=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE
                """,(r,i)->r.getInt(1),actor.workspace(),templateId)+1 : 1;
        db.write("INSERT INTO grading_template(tenant_id,id,version_no,title,questions,created_at) VALUES(?,?,?,?,?,?)",
                actor.workspace(),templateId,version,title,db.jsonValue(questions),Instant.now());
        return new Template(templateId,version,title,questions);
    }

    private static void ensureStatus(Job job, String status) {
        if (!job.status().equals(status)) throw error(409,"INVALID_JOB_STATE","当前阶段无法执行此操作");
    }
}

@RestController
@RequestMapping("/api/v2")
class GradingController {
    private final GradingAccess access;
    private final GradingJobs jobs;
    GradingController(GradingAccess access, GradingJobs jobs) { this.access=access; this.jobs=jobs; }

    private GradingAccess.Actor teacher(HttpServletRequest request) {
        var teacher = access.teacher(request);
        return new GradingAccess.Actor(teacher.requireTenant(),teacher.user(),null);
    }

    @PostMapping("/grading-jobs")
    ResponseEntity<?> create(HttpServletRequest request, @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        var job = jobs.create(access.actor(request,"grading:submit"),body,key);
        return ResponseEntity.accepted().location(URI.create("/api/v2/grading-jobs/"+job.id())).eTag(etag(job.revision())).body(jobs.creationResponse(job));
    }

    @GetMapping("/grading-jobs")
    Object list(HttpServletRequest request, @RequestParam(defaultValue="1") int page) {
        return jobs.list(access.actor(request,"grading:read"),page);
    }

    @GetMapping("/grading-jobs/{id}")
    ResponseEntity<?> status(HttpServletRequest request, @PathVariable UUID id) {
        return response(jobs.ownedHeadline(access.actor(request,"grading:read"),id));
    }

    @GetMapping("/grading-jobs/{id}/draft")
    ResponseEntity<?> draft(HttpServletRequest request, @PathVariable UUID id) {
        var job = jobs.owned(access.actor(request,"grading:read"),id,false);
        return ResponseEntity.ok().eTag(etag(job.revision())).body(jobs.draft(job));
    }

    @PatchMapping("/grading-jobs/{id}/draft")
    ResponseEntity<?> edit(HttpServletRequest request, @PathVariable UUID id, @RequestHeader("If-Match") String tag, @RequestBody JsonNode body) {
        return response(jobs.edit(teacher(request),id,tag,body));
    }

    @PostMapping("/grading-jobs/{id}/confirm")
    ResponseEntity<?> confirm(HttpServletRequest request, @PathVariable UUID id, @RequestHeader("If-Match") String tag) {
        return response(jobs.confirm(teacher(request),id,tag));
    }

    @PostMapping("/grading-jobs/{id}/retry")
    ResponseEntity<?> retry(HttpServletRequest request, @PathVariable UUID id, @RequestHeader("If-Match") String tag,
                            @RequestParam(required=false) Integer item) {
        return response(jobs.retry(teacher(request),id,tag,item,false));
    }

    @PostMapping("/grading-jobs/{id}/regrades")
    ResponseEntity<?> regrade(HttpServletRequest request, @PathVariable UUID id, @RequestHeader("If-Match") String tag) {
        return response(jobs.retry(teacher(request),id,tag,null,true));
    }

    @DeleteMapping("/grading-jobs/{id}")
    ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable UUID id, @RequestHeader("If-Match") String tag) {
        jobs.cancel(teacher(request),id,tag);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grading-jobs/{id}/webhooks")
    Object webhooks(HttpServletRequest request, @PathVariable UUID id) {
        return jobs.webhookDeliveries(jobs.ownedHeadline(access.actor(request,"grading:read"),id));
    }

    @GetMapping("/grading-jobs/{id}/report")
    Object report(HttpServletRequest request, @PathVariable UUID id, @RequestParam(required=false) Integer run) {
        return jobs.report(jobs.owned(access.actor(request,"grading:read"),id,false),run);
    }

    @GetMapping("/grading-jobs/{id}/export")
    ResponseEntity<byte[]> export(HttpServletRequest request, @PathVariable UUID id,
                                  @RequestParam String format, @RequestParam(required=false) Integer run) {
        var job = jobs.owned(access.actor(request,"grading:read"),id,false);
        if (!"csv".equals(format)) throw error(422,"INVALID_FORMAT","仅支持 CSV 导出");
        var report = jobs.report(job,run);
        return ResponseEntity.ok()
                .header("Content-Type","text/csv; charset=utf-8")
                .header("Content-Disposition","attachment; filename=\"grading-" + id + "-run-" + report.path("run").asInt() + ".csv\"")
                .header("Cache-Control","private, no-store")
                .body(GradingCsv.encode(report));
    }

    @PostMapping("/grading-jobs/{id}/reviews/{item}")
    ResponseEntity<?> review(HttpServletRequest request, @PathVariable UUID id, @PathVariable int item,
                             @RequestHeader("If-Match") String tag,@RequestBody JsonNode body) {
        jobs.review(teacher(request),id,item,tag,body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grading-jobs/{id}/reviews/{item}")
    Object history(HttpServletRequest request, @PathVariable UUID id, @PathVariable int item, @RequestParam int run) {
        return jobs.reviewHistory(jobs.owned(access.actor(request,"grading:read"),id,false),run,item);
    }

    @GetMapping("/grading-templates")
    Object templates(HttpServletRequest request) { return jobs.templates(access.actor(request,"grading:read").workspace()); }

    @DeleteMapping("/grading-templates/{id}")
    ResponseEntity<Void> deleteTemplate(HttpServletRequest request, @PathVariable UUID id) {
        jobs.deleteTemplate(teacher(request).workspace(),id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/grading-jobs/{id}/templates")
    ResponseEntity<?> template(HttpServletRequest request, @PathVariable UUID id, @RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(jobs.saveTemplate(teacher(request),id,body));
    }

    private ResponseEntity<?> response(GradingJobs.Job job) {
        return ResponseEntity.ok().eTag(etag(job.revision())).body(jobs.summary(job));
    }
}
