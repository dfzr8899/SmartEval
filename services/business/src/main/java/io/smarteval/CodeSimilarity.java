package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CodeSimilarity {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CodeSimilarity.class);
    private final Database db;
    private final Intelligence intelligence;
    private final double threshold;

    record Run(UUID job, int number, int attempts) {}

    CodeSimilarity(Database db, Intelligence intelligence,
                   @Value("${SMARTEVAL_SIMILARITY_THRESHOLD:0.60}") double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Similarity threshold must be between 0 and 1");
        this.db = db;
        this.intelligence = intelligence;
        if (threshold < 0.60) {
            LOG.warn("SMARTEVAL_SIMILARITY_THRESHOLD={} is below the 0.60 floor; clamping to 0.60", threshold);
        }
        this.threshold = Math.max(0.60,threshold);
    }

    void schedule(UUID job, int run, List<JsonNode> items) {
        var eligible = groups(items).values().stream().anyMatch(CodeSimilarity::hasPairs);
        if (eligible) db.write("INSERT INTO grading_similarity_run(job_id,run_no,available_at) VALUES(?,?,?)",
                job,run,Instant.now());
    }

    private static Map<String,List<Map<String,String>>> groups(List<JsonNode> items) {
        var groups = new LinkedHashMap<String,List<Map<String,String>>>();
        for (var item : items) {
            if (!"CODE".equals(item.path("kind").asText())) continue;
            var answer = item.path("answer");
            String text = answer.path("text").asText();
            String language = answer.path("language").asText();
            if (!text.isBlank() && !language.isBlank()) {
                groups.computeIfAbsent(item.path("id").asText(),ignored -> new ArrayList<>()).add(
                        Map.of("submission_id",answer.path("submission_id").asText(),"language",language,"text",text));
            }
        }
        return groups;
    }

    private static boolean hasPairs(List<Map<String,String>> entries) {
        var languages = new java.util.HashSet<String>();
        return entries.stream().anyMatch(entry -> !languages.add(entry.get("language")));
    }

    void appendReport(UUID job, int run, com.fasterxml.jackson.databind.node.ObjectNode report) {
        report.put("similarity_status","NOT_APPLICABLE");
        report.putArray("similarity_alerts");
        db.one("SELECT status,alerts FROM grading_similarity_run WHERE job_id=? AND run_no=?", (r,i) -> {
            report.put("similarity_status",r.getString("status"));
            String alerts = r.getString("alerts");
            if (alerts != null) {
                var items = (ArrayNode) report.path("similarity_alerts");
                for (var item : db.parse(alerts)) {
                    if (item.path("score").asDouble() < 0.60) continue;
                    classify((com.fasterxml.jackson.databind.node.ObjectNode)item);
                    items.add(item);
                }
            }
            return true;
        },job,run);
    }

    private static void classify(com.fasterxml.jackson.databind.node.ObjectNode alert) {
        alert.put("level",alert.path("score").asDouble() >= 0.80 ? "HIGH" : "MEDIUM");
    }

    boolean processOne() {
        Instant now = Instant.now();
        UUID lease = UUID.randomUUID();
        var run = db.transaction(() -> {
            var candidate = db.one("""
                    SELECT s.job_id,s.run_no,s.attempts FROM grading_similarity_run s
                    WHERE s.status='PENDING' AND s.available_at<=?
                    AND EXISTS (SELECT 1 FROM grading_job j JOIN tenant t ON t.id=j.tenant_id
                                WHERE j.id=s.job_id AND t.status='ACTIVE')
                    AND NOT EXISTS (SELECT 1 FROM grading_item i WHERE i.job_id=s.job_id AND i.run_no=s.run_no
                                    AND i.status IN ('QUEUED','RUNNING'))
                    ORDER BY s.available_at,s.job_id,s.run_no LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(r,i) -> new Run(Database.uuid(r,"job_id"),r.getInt("run_no"),r.getInt("attempts")),now);
            if (candidate != null) db.write("""
                    UPDATE grading_similarity_run SET lease_id=?,attempts=attempts+1,available_at=?
                    WHERE job_id=? AND run_no=?
                    """,lease,now.plusSeconds(300),candidate.job(),candidate.number());
            return candidate;
        });
        if (run == null) return false;
        String status = "FAILED";
        ArrayNode alerts = db.json.createArrayNode();
        if (run.attempts() < 3) {
            try {
                status = calculate(run,alerts);
            } catch (RuntimeException e) {
                LOG.warn("Code similarity failed for job {} run {} attempt {}",run.job(),run.number(),run.attempts()+1,e);
                status = run.attempts() < 2 ? "PENDING" : "FAILED";
                alerts.removeAll();
            }
        }
        db.write("""
                UPDATE grading_similarity_run SET status=?,alerts=?,available_at=?,lease_id=NULL
                WHERE job_id=? AND run_no=? AND lease_id=?
                """,status,db.jsonValue(alerts),Instant.now().plusSeconds(15),run.job(),run.number(),lease);
        return true;
    }

    private String calculate(Run run, ArrayNode alerts) {
        var items = db.list("SELECT item_input FROM grading_item WHERE job_id=? AND run_no=? ORDER BY ordinal_no",
                (r,i) -> db.parse(r.getString(1)),run.job(),run.number());
        boolean compared = false;
        for (var entry : groups(items).entrySet()) {
            if (!hasPairs(entry.getValue())) continue;
            compared = true;
            var response = intelligence.post("/internal/v2/grading/similarity",
                    Map.of("submissions",entry.getValue(),"threshold",threshold),40);
            if (!response.path("pairs").isArray()) throw new IllegalStateException("Invalid similarity response");
            for (var pair : response.path("pairs")) {
                if (!pair.isObject() || !pair.path("score").isNumber() || pair.path("score").asDouble() < threshold) continue;
                var alert = alerts.addObject();
                alert.setAll((com.fasterxml.jackson.databind.node.ObjectNode)pair);
                alert.put("question_id",entry.getKey());
                classify(alert);
            }
        }
        return compared ? "COMPLETED" : "NOT_APPLICABLE";
    }
}
