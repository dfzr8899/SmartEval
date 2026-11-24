package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
class GradingCollections {
    record Collection(UUID id, UUID tenant, String title, String description, String token, String status,
                      JsonNode preset, UUID job, Instant created, Instant updated, int entries) {}
    record Entry(UUID id, String submission_id, String answer_text, String language, JsonNode asset_ids, Instant submitted_at) {}

    private static final String SELECT = """
            SELECT c.*,(SELECT COUNT(*) FROM grading_collection_entry e WHERE e.collection_id=c.id) AS entry_count
            FROM grading_collection c
            """;
    private final Database db;
    private final GradingJobs jobs;
    private final GradingAssets assets;

    GradingCollections(Database db, GradingJobs jobs, GradingAssets assets) {
        this.db=db; this.jobs=jobs; this.assets=assets;
    }

    private Collection collection(ResultSet r, int index) throws SQLException {
        String preset = r.getString("preset_config");
        return new Collection(Database.uuid(r,"id"),Database.uuid(r,"tenant_id"),r.getString("title"),r.getString("description"),
                new String(r.getBytes("share_token"),StandardCharsets.US_ASCII),r.getString("status"),
                preset==null?db.json.nullNode():db.parse(preset),Database.uuid(r,"job_id"),Database.time(r,"created_at"),
                Database.time(r,"updated_at"),r.getInt("entry_count"));
    }

    private Collection owned(UUID tenant, UUID id, boolean lock) {
        return db.required(SELECT+" WHERE c.tenant_id=? AND c.id=?"+(lock?" FOR UPDATE":""),this::collection,tenant,id);
    }

    private Collection shared(String token, boolean lock) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw missing();
        return db.required(SELECT+" WHERE c.share_token=? AND EXISTS (SELECT 1 FROM tenant t WHERE t.id=c.tenant_id AND t.status='ACTIVE')"
                +(lock?" FOR UPDATE":""),this::collection,token.getBytes(StandardCharsets.US_ASCII));
    }

    private List<Entry> entries(UUID id) {
        return db.list("SELECT * FROM grading_collection_entry WHERE collection_id=? ORDER BY submitted_at,id",
                (r,i)->new Entry(Database.uuid(r,"id"),r.getString("submission_id"),r.getString("answer_text"),
                        r.getString("language"),db.parse(r.getString("asset_ids")),Database.time(r,"submitted_at")),id);
    }

    private ObjectNode view(Collection value) {
        var out = db.json.createObjectNode().put("id",value.id().toString()).put("title",value.title())
                .put("description",value.description()).put("share_token",value.token()).put("status",value.status())
                .put("created_at",value.created().toString()).put("updated_at",value.updated().toString()).put("entry_count",value.entries());
        out.set("preset_config",value.preset());
        out.put("job_id",value.job()==null?null:value.job().toString());
        return out;
    }

    ObjectNode create(GradingAccess.Actor teacher, JsonNode body) {
        String title = text(body,"title",160);
        String desc = optionalText(body,"description",4000);
        var preset = body.path("preset_config");
        boolean hasPreset = !preset.isMissingNode() && !preset.isNull();
        UUID template = null;
        Integer version = null;
        var references = new HashSet<UUID>();
        long bytes = 0;
        if (hasPreset) {
            if (!preset.isObject()) throw error(422,"INVALID_FIELD","预置配置须为对象");
            if (preset.hasNonNull("questions")) {
                var questions = new HashSet<String>();
                for (var q : array(preset,"questions",1,80)) {
                    if (!questions.add(text(q,"id",80))) throw error(422,"DUPLICATE_QUESTION","题号重复");
                    if (q.hasNonNull("answers") || q.hasNonNull("source_ids"))
                        throw error(422,"INVALID_FIELD","预置题目只接受题目与评分标准");
                    GradingInput.standard(q);
                }
            }
            if (preset.hasNonNull("template")) {
                if (preset.hasNonNull("questions")) throw error(422,"INVALID_FIELD","请在预置题目和模板中选择一项");
                var selection = preset.path("template");
                template = id(text(selection,"id",36));
                version = number(selection,"version",1,Integer.MAX_VALUE);
            }
            if (preset.hasNonNull("materials")) for (var material : array(preset,"materials",0,GradingInput.MAX_MATERIALS)) {
                UUID id = id(text(material,"asset_id",36));
                String role = text(material,"role",10);
                if (!role.equals("QUESTION") && !role.equals("REFERENCE"))
                    throw error(422,"INVALID_ROLE","预置材料只接受题目或参考资料");
                if (!references.add(id)) throw error(422,"DUPLICATE_ASSET","预置材料重复");
                bytes += assets.owned(teacher,id).size();
            }
        }
        if (bytes>GradingInput.MAX_MATERIAL_BYTES) throw error(422,"MATERIAL_SIZE","材料合计上限为 200 MB");
        UUID templateId = template;
        Integer templateVersion = version;
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return db.transaction(() -> {
            if (templateId!=null) db.required("""
                    SELECT version_no FROM grading_template WHERE tenant_id=? AND id=? AND version_no=? FOR UPDATE
                    """,(r,i)->r.getInt(1),teacher.workspace(),templateId,templateVersion);
            references.stream().sorted().forEach(asset -> assets.lockForJob(teacher.workspace(),asset));
            db.write("""
                    INSERT INTO grading_collection(id,tenant_id,created_by,title,description,share_token,status,preset_config,
                                                   template_id,template_version,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,'OPEN',?,?,?,?,?)
                    """,id,teacher.workspace(),teacher.user(),title,desc==null?"":desc,Identity.random().getBytes(StandardCharsets.US_ASCII),
                    hasPreset ? db.jsonValue(preset) : null,templateId,templateVersion,now,now);
            db.batch("INSERT INTO grading_collection_asset(collection_id,asset_id) VALUES(?,?)",
                    references.stream().map(asset -> new Object[]{id,asset}).toList());
            return view(owned(teacher.workspace(),id,false));
        });
    }

    Object list(UUID tenant) {
        return db.list(SELECT+" WHERE c.tenant_id=? ORDER BY c.created_at DESC,c.id",this::collection,tenant)
                .stream().map(this::view).toList();
    }

    ObjectNode detail(UUID tenant, UUID id) {
        var value = view(owned(tenant,id,false));
        value.set("entries",db.json.valueToTree(entries(id)));
        return value;
    }

    ObjectNode change(UUID tenant, UUID id, JsonNode body) {
        String status = text(body,"status",12);
        if (!status.equals("OPEN") && !status.equals("CLOSED")) throw error(422,"INVALID_FIELD","状态须为 OPEN 或 CLOSED");
        return db.transaction(() -> {
            requireMutable(owned(tenant,id,true));
            db.write("UPDATE grading_collection SET status=?,updated_at=? WHERE id=?",status,Instant.now(),id);
            return view(owned(tenant,id,false));
        });
    }

    void remove(UUID tenant, UUID id, UUID entry) {
        db.transaction(() -> {
            requireMutable(owned(tenant,id,true));
            if (db.write("DELETE FROM grading_collection_entry WHERE collection_id=? AND id=?",id,entry)==0) throw missing();
            db.write("UPDATE grading_collection SET updated_at=? WHERE id=?",Instant.now(),id);
        });
    }

    Object publicView(String token) {
        var c = shared(token,false);
        return Map.of("title",c.title(),"description",c.description(),"status",c.status(),
                "allowed_file_types",GradingAssets.EXTENSIONS);
    }

    void submit(String token, String rawId, String answer, String language, List<MultipartFile> files) {
        String submission = rawId.strip();
        if (submission.isEmpty() || submission.length()>100 || submission.matches("(?s).*[\\p{Cntrl}].*"))
            throw error(422,"INVALID_SUBMISSION_ID","答卷编号须为 1 至 100 个字符");
        if (answer!=null && answer.length()>24_000) throw error(422,"INVALID_ANSWER","作答最多 24000 字符");
        if (language!=null && !GradingInput.LANGUAGES.contains(language))
            throw error(422,"INVALID_LANGUAGE","请选择支持的编程语言");
        if (files.size()>5) throw error(422,"FILE_COUNT","每份答卷最多 5 个文件");
        if (files.isEmpty() && (answer==null || answer.isBlank()))
            throw error(422,"EMPTY_SUBMISSION","请填写作答或上传文件");
        var hash = sha(submission);
        db.transaction(() -> {
            var c = shared(token,true);
            if (!c.status().equals("OPEN")) throw error(410,"COLLECTION_CLOSED","收卷已关闭");
            UUID id = c.id();
            if (db.one("SELECT id FROM grading_collection_entry WHERE collection_id=? AND submission_key=?",
                    (r,i)->Database.uuid(r,"id"),id,hash)!=null)
                throw error(409,"DUPLICATE_SUBMISSION","该答卷编号已经提交");
            if (entriesCount(id)>=GradingInput.MAX_SUBMISSIONS)
                throw error(422,"SUBMISSION_LIMIT","一个收卷最多 100 份答卷");
            var totals = db.required("""
                    SELECT COUNT(*),COALESCE(SUM(a.size_bytes),0) FROM grading_collection_asset r
                    JOIN grading_asset a ON a.id=r.asset_id WHERE r.collection_id=?
                    """,(r,i)->new long[]{r.getLong(1),r.getLong(2)},id);
            if (totals[0]+files.size()>GradingInput.MAX_MATERIALS
                    || totals[1]+files.stream().mapToLong(MultipartFile::getSize).sum()>GradingInput.MAX_MATERIAL_BYTES)
                throw error(422,"MATERIAL_LIMIT","收卷文件上限为 100 个，合计最多 200 MB");
            var actor = new GradingAccess.Actor(c.tenant(),null,null);
            var saved = new ArrayList<UUID>();
            for (var file : files) {
                try { saved.add(assets.save(actor,file).id()); }
                catch (IOException e) { throw error(503,"ASSET_STORAGE_UNAVAILABLE","文件暂时无法保存"); }
            }
            UUID entry = UUID.randomUUID();
            db.write("""
                    INSERT INTO grading_collection_entry(id,collection_id,submission_id,submission_key,answer_text,language,asset_ids,submitted_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,entry,id,submission,hash,answer,language,db.jsonValue(saved),Instant.now());
            db.batch("INSERT INTO grading_collection_asset(collection_id,asset_id,entry_id) VALUES(?,?,?)",
                    saved.stream().map(asset -> new Object[]{id,asset,entry}).toList());
            db.write("UPDATE grading_collection SET updated_at=? WHERE id=?",Instant.now(),id);
        });
    }

    private int entriesCount(UUID id) {
        return db.required("SELECT COUNT(*) FROM grading_collection_entry WHERE collection_id=?",(r,i)->r.getInt(1),id);
    }

    private static void requireMutable(Collection c) {
        if (c.status().equals("CONVERTED")) throw error(409,"COLLECTION_CONVERTED","收卷已转为批改任务");
    }

    GradingJobs.Job convert(GradingAccess.Actor teacher, UUID id) {
        return db.transaction(() -> {
            var c = owned(teacher.workspace(),id,true);
            if (c.job()!=null) return jobs.owned(teacher,c.job(),false);
            var submissions = entries(id);
            if (submissions.isEmpty()) throw error(422,"EMPTY_COLLECTION","收卷至少需要一份答卷");
            var body = db.json.createObjectNode().put("title",c.title());
            var materials = body.putArray("materials");
            c.preset().path("materials").forEach(materials::add);
            if (c.preset().hasNonNull("questions")) body.set("questions",c.preset().path("questions"));
            if (c.preset().hasNonNull("template")) body.set("template",c.preset().path("template"));
            var answers = db.json.createArrayNode();
            for (var entry : submissions) {
                var answer = answers.addObject().put("submission_id",entry.submission_id()).put("text",entry.answer_text());
                if (entry.language()!=null) answer.put("language",entry.language());
                entry.asset_ids().forEach(asset -> materials.addObject().put("asset_id",asset.asText())
                        .put("role","ANSWER").put("submission_id",entry.submission_id()));
            }
            var job = jobs.create(teacher,body,"collection:"+id,answers);
            db.write("""
                    UPDATE grading_collection SET status='CONVERTED',job_id=?,template_id=NULL,template_version=NULL,updated_at=? WHERE id=?
                    """,job.id(),Instant.now(),id);
            return job;
        });
    }
}

@RestController
@RequestMapping("/api/v2")
class GradingCollectionsController {
    private final GradingAccess access;
    private final GradingCollections collections;
    private final GradingJobs jobs;
    private final RateLimiter rateLimiter;

    GradingCollectionsController(GradingAccess access, GradingCollections collections, GradingJobs jobs, RateLimiter rateLimiter) {
        this.access=access; this.collections=collections; this.jobs=jobs; this.rateLimiter=rateLimiter;
    }

    private GradingAccess.Actor teacher(HttpServletRequest request) {
        var teacher = access.teacher(request);
        return new GradingAccess.Actor(teacher.requireTenant(),teacher.user(),null);
    }

    @PostMapping("/collections")
    ResponseEntity<?> create(HttpServletRequest request, @RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(collections.create(teacher(request),body));
    }

    @GetMapping("/collections")
    Object list(HttpServletRequest request) { return collections.list(teacher(request).workspace()); }

    @GetMapping("/collections/{id}")
    Object detail(HttpServletRequest request, @PathVariable UUID id) {
        return collections.detail(teacher(request).workspace(),id);
    }

    @PatchMapping("/collections/{id}")
    Object change(HttpServletRequest request, @PathVariable UUID id, @RequestBody JsonNode body) {
        return collections.change(teacher(request).workspace(),id,body);
    }

    @DeleteMapping("/collections/{id}/entries/{entryId}")
    ResponseEntity<Void> remove(HttpServletRequest request, @PathVariable UUID id, @PathVariable UUID entryId) {
        collections.remove(teacher(request).workspace(),id,entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/collections/{id}/create-job")
    ResponseEntity<?> convert(HttpServletRequest request, @PathVariable UUID id) {
        var job = collections.convert(teacher(request),id);
        return ResponseEntity.accepted().body(jobs.summary(job));
    }

    @GetMapping("/public/collections/{token}")
    ResponseEntity<?> publicView(@PathVariable String token) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
                .body(collections.publicView(token));
    }

    @PostMapping(value="/public/collections/{token}/submit", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Void> submit(HttpServletRequest request, @PathVariable String token, @RequestParam(name="submission_id") String id,
                                @RequestParam(name="answer_text",required=false) String answer,
                                @RequestParam(required=false) String language,
                                @RequestPart(required=false) List<MultipartFile> files) {
        String ip = RateLimiter.clientIp(request);
        if (!rateLimiter.allow("collect:ip:" + ip, 25, 300) || !rateLimiter.allow("collect:tok:" + token, 120, 300)) {
            throw error(429, "RATE_LIMITED", "提交过于频繁，请稍后再试");
        }
        String normalizedLang = language == null || language.isBlank() ? null : language.strip();
        collections.submit(token,id,answer,normalizedLang,files==null?List.of():files);
        return ResponseEntity.status(201).header("Cache-Control","no-store").build();
    }
}
