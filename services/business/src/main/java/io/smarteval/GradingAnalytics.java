package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
class GradingAnalytics {
    private final Database db;
    private final GradingJobs jobs;

    GradingAnalytics(Database db, GradingJobs jobs) {
        this.db = db;
        this.jobs = jobs;
    }

    Map<String, Object> overview(UUID workspace) {
        var jobRows = db.list("""
                SELECT id,title,status,run_no,created_at FROM grading_job
                WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200
                """, (r, i) -> Map.<String, Object>of(
                "id", Database.uuid(r, "id"),
                "title", r.getString("title"),
                "status", r.getString("status"),
                "run", r.getInt("run_no"),
                "created_at", Database.time(r, "created_at")), workspace);

        int totalJobs = db.required("SELECT COUNT(*) FROM grading_job WHERE tenant_id=?", (r, i) -> r.getInt(1), workspace);
        int awaitingConfirmation = 0;
        int activeJobs = 0;
        int completedJobs = 0;
        int reviewRequiredJobs = 0;

        var itemStatusRows = db.list("""
                SELECT i.job_id,i.status,COUNT(*) AS n FROM grading_item i
                JOIN grading_job j ON j.id=i.job_id AND j.run_no=i.run_no
                WHERE j.tenant_id=? GROUP BY i.job_id,i.status
                """, (r, i) -> new Object[]{Database.uuid(r, "job_id"), r.getString("status"), r.getInt("n")}, workspace);

        Map<UUID, Map<String, Integer>> countsByJob = new HashMap<>();
        int totalItems = 0, scoredItems = 0, reviewRequiredItems = 0, failedItems = 0;
        for (Object[] row : itemStatusRows) {
            UUID jid = (UUID) row[0];
            String st = (String) row[1];
            int n = (Integer) row[2];
            countsByJob.computeIfAbsent(jid, ignored -> new HashMap<>()).put(st, n);
            totalItems += n;
            switch (st) {
                case "SCORED" -> scoredItems += n;
                case "REVIEW_REQUIRED" -> reviewRequiredItems += n;
                case "FAILED" -> failedItems += n;
                default -> {}
            }
        }

        int humanReviewed = db.required("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT r.job_id,r.run_no,r.ordinal_no FROM grading_review r
                    JOIN grading_job j ON j.id=r.job_id AND j.run_no=r.run_no
                    WHERE j.tenant_id=?
                ) reviewed
                """, (r, i) -> r.getInt(1), workspace);

        var simRows = db.list("""
                SELECT s.job_id,s.alerts FROM grading_similarity_run s
                JOIN grading_job j ON j.id=s.job_id AND j.run_no=s.run_no
                WHERE j.tenant_id=? AND s.status='COMPLETED' AND s.alerts IS NOT NULL
                """, (r, i) -> Map.entry(Database.uuid(r, "job_id"), r.getString("alerts")), workspace);

        int highAlerts = 0, mediumAlerts = 0;
        Map<UUID, Integer> alertsByJob = new HashMap<>();
        for (var entry : simRows) {
            int count = 0;
            for (JsonNode alert : db.parse(entry.getValue())) {
                double score = alert.path("score").asDouble(0);
                if (score < 0.60) continue;
                count++;
                if (score >= 0.80) highAlerts++;
                else mediumAlerts++;
            }
            alertsByJob.put(entry.getKey(), count);
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        for (var row : jobRows) {
            UUID jid = (UUID) row.get("id");
            String rawStatus = (String) row.get("status");
            int run = (Integer) row.get("run");
            String effectiveStatus = rawStatus;
            Map<String, Integer> counts = countsByJob.getOrDefault(jid, Map.of());
            if (run > 0) {
                boolean hasDone = counts.containsKey("SCORED") || counts.containsKey("REVIEW_REQUIRED") || counts.containsKey("FAILED");
                effectiveStatus = counts.containsKey("RUNNING") || (counts.containsKey("QUEUED") && hasDone) ? "GRADING"
                        : counts.containsKey("QUEUED") ? "QUEUED"
                        : counts.containsKey("FAILED") || counts.containsKey("REVIEW_REQUIRED") ? "REVIEW_REQUIRED" : "COMPLETED";
            }
            switch (effectiveStatus) {
                case "AWAITING_CONFIRMATION" -> awaitingConfirmation++;
                case "PREPARING", "QUEUED", "GRADING" -> activeJobs++;
                case "COMPLETED" -> completedJobs++;
                case "REVIEW_REQUIRED" -> reviewRequiredJobs++;
                default -> {}
            }
            if (run > 0 && recent.size() < 10) {
                var detail = jobAnalytics(jobs.load(jid), run);
                @SuppressWarnings("unchecked")
                var summary = (Map<String, Object>) detail.get("summary");
                var entry = new LinkedHashMap<String, Object>();
                entry.put("id", jid);
                entry.put("title", row.get("title"));
                entry.put("status", effectiveStatus);
                entry.put("created_at", row.get("created_at"));
                entry.put("submission_count", detail.get("submission_count"));
                entry.put("mean_rate", summary.get("mean_rate"));
                entry.put("pass_rate", summary.get("pass_rate"));
                entry.put("review_required_count", counts.getOrDefault("REVIEW_REQUIRED", 0) + counts.getOrDefault("FAILED", 0));
                entry.put("similarity_alerts", alertsByJob.getOrDefault(jid, 0));
                recent.add(entry);
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("total_jobs", totalJobs);
        out.put("active_jobs", activeJobs);
        out.put("awaiting_confirmation_jobs", awaitingConfirmation);
        out.put("review_required_jobs", reviewRequiredJobs);
        out.put("completed_jobs", completedJobs);
        out.put("total_items", totalItems);
        out.put("scored_items", scoredItems);
        out.put("review_required_items", reviewRequiredItems);
        out.put("failed_items", failedItems);
        out.put("human_reviewed_items", humanReviewed);
        out.put("similarity_high_alerts", highAlerts);
        out.put("similarity_medium_alerts", mediumAlerts);
        out.put("recent_jobs", recent);
        return out;
    }

    Map<String, Object> jobAnalytics(GradingJobs.Job job, Integer requestedRun) {
        JsonNode report = jobs.report(job, requestedRun);
        int run = report.path("run").asInt();
        int latestRun = report.path("latest_run").asInt();

        List<Double> finalScores = new ArrayList<>();
        List<Double> scoreRates = new ArrayList<>();
        int maxTotal = 0;
        int submissionCount = 0;
        int passCount = 0;
        int excellentCount = 0;
        int[] bands = new int[5]; // <60, 60-69, 70-79, 80-89, 90-100

        for (JsonNode sub : report.path("submissions")) {
            submissionCount++;
            int max = sub.path("max_score").asInt(0);
            if (max > maxTotal) maxTotal = max;
            if (sub.path("final_items").asInt(0) > 0 && sub.path("final_items").asInt(0) == sub.path("total_items").asInt(0) && max > 0) {
                double pts = sub.path("final_points_so_far").asDouble(0);
                double rate = pts / max;
                finalScores.add(pts);
                scoreRates.add(rate);
                if (rate >= 0.60) passCount++;
                if (rate >= 0.85) excellentCount++;
                int idx = rate < 0.60 ? 0 : rate < 0.70 ? 1 : rate < 0.80 ? 2 : rate < 0.90 ? 3 : 4;
                bands[idx]++;
            }
        }

        double mean = finalScores.isEmpty() ? 0 : finalScores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanRate = scoreRates.isEmpty() ? 0 : scoreRates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = 0, min = 0, max = 0, stdDev = 0;
        if (!finalScores.isEmpty()) {
            List<Double> sorted = new ArrayList<>(finalScores);
            Collections.sort(sorted);
            int n = sorted.size();
            median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
            min = sorted.getFirst();
            max = sorted.getLast();
            double variance = sorted.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            stdDev = Math.sqrt(variance);
        }

        record QAcc(String id, String kind, int maxScore, double[] scoreSum, int[] counts) {}
        Map<String, QAcc> byQuestion = new LinkedHashMap<>();
        int autoScored = 0, teacherReviewed = 0, reviewRequired = 0, failed = 0, reviewerRevised = 0;

        for (JsonNode item : report.path("items")) {
            String qid = item.path("question_id").asText();
            String kind = item.path("kind").asText();
            int qMax = item.path("max_score").asInt(0);
            String st = item.path("status").asText();
            JsonNode res = item.path("result");
            // counts: [0]=scored, [1]=reviewRequired, [2]=failed, [3]=teacherReviewed
            QAcc acc = byQuestion.computeIfAbsent(qid, ignored -> new QAcc(qid, kind, qMax, new double[1], new int[4]));
            if ("SCORED".equals(st)) {
                if ("TEACHER".equals(res.path("decision").asText())) {
                    teacherReviewed++;
                    acc.counts()[3]++;
                } else {
                    autoScored++;
                }
            } else if ("REVIEW_REQUIRED".equals(st)) {
                reviewRequired++;
                acc.counts()[1]++;
            } else if ("FAILED".equals(st)) {
                failed++;
                acc.counts()[2]++;
            }
            if (res.path("reviewer_revised").asBoolean(false)) reviewerRevised++;
            if (res.path("final_score").isNumber()) {
                acc.scoreSum()[0] += res.path("final_score").asDouble();
                acc.counts()[0]++;
            }
        }

        List<Map<String, Object>> questionRows = new ArrayList<>();
        for (QAcc acc : byQuestion.values()) {
            double avg = acc.counts()[0] == 0 ? 0 : acc.scoreSum()[0] / acc.counts()[0];
            double rate = acc.maxScore() <= 0 || acc.counts()[0] == 0 ? 0 : avg / acc.maxScore();
            var qRow = new LinkedHashMap<String, Object>();
            qRow.put("question_id", acc.id());
            qRow.put("kind", acc.kind());
            qRow.put("max_score", acc.maxScore());
            qRow.put("scored_count", acc.counts()[0]);
            qRow.put("average_score", round2(avg));
            qRow.put("score_rate", round4(rate));
            qRow.put("review_required_count", acc.counts()[1]);
            qRow.put("failed_count", acc.counts()[2]);
            qRow.put("teacher_reviewed_count", acc.counts()[3]);
            questionRows.add(qRow);
        }

        int high = 0, medium = 0;
        for (JsonNode alert : report.path("similarity_alerts")) {
            if ("HIGH".equals(alert.path("level").asText())) high++;
            else medium++;
        }

        String[] bandLabels = {"0-59%", "60-69%", "70-79%", "80-89%", "90-100%"};
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int i = 0; i < bandLabels.length; i++) {
            distribution.add(Map.of("label", bandLabels[i], "count", bands[i]));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("mean_score", round2(mean));
        summary.put("median_score", round2(median));
        summary.put("max_score", round2(max));
        summary.put("min_score", round2(min));
        summary.put("std_dev", round2(stdDev));
        summary.put("mean_rate", round4(meanRate));
        summary.put("pass_rate", finalScores.isEmpty() ? 0 : round4((double) passCount / finalScores.size()));
        summary.put("excellent_rate", finalScores.isEmpty() ? 0 : round4((double) excellentCount / finalScores.size()));

        var out = new LinkedHashMap<String, Object>();
        out.put("job_id", job.id());
        out.put("title", job.title());
        out.put("run", run);
        out.put("latest_run", latestRun);
        out.put("submission_count", submissionCount);
        out.put("final_submission_count", finalScores.size());
        out.put("max_total_score", maxTotal);
        out.put("summary", summary);
        out.put("distribution", distribution);
        out.put("questions", questionRows);
        out.put("review_stats", Map.of(
                "auto_scored", autoScored,
                "teacher_reviewed", teacherReviewed,
                "review_required", reviewRequired,
                "failed", failed,
                "reviewer_revised", reviewerRevised));
        out.put("similarity", Map.of(
                "status", report.path("similarity_status").asText("NOT_APPLICABLE"),
                "high_count", high,
                "medium_count", medium));
        return out;
    }

    byte[] exportEvalDataset(GradingJobs.Job job, Integer requestedRun) {
        JsonNode report = jobs.report(job, requestedRun);
        int run = report.path("run").asInt();
        Map<String, String> anonMap = new LinkedHashMap<>();
        var samples = db.json.createArrayNode();
        var items = jobs.items(job, run);
        for (GradingJobs.Item item : items) {
            JsonNode input = item.input();
            JsonNode result = item.result();
            if (!result.path("final_score").isIntegralNumber()) continue;
            String rawSub = input.path("answer").path("submission_id").asText();
            String anonId = anonMap.computeIfAbsent(rawSub, ignored -> String.format("S%03d", anonMap.size() + 1));
            var sample = samples.addObject();
            sample.put("id", input.path("id").asText() + "-" + anonId);
            sample.put("question_group", input.path("id").asText());
            sample.put("kind", input.path("kind").asText());
            sample.put("question", input.path("prompt").asText());
            sample.put("max_score", input.path("max_score").asInt());
            if (input.hasNonNull("reference_answer")) sample.put("reference_answer", input.path("reference_answer").asText());
            if (input.hasNonNull("rubric")) sample.set("rubric", input.path("rubric"));
            if (input.hasNonNull("accepted")) sample.set("accepted", input.path("accepted"));
            if (input.hasNonNull("tests")) sample.set("tests", input.path("tests"));
            sample.put("student_answer", input.path("answer").path("text").asText());
            if (input.path("answer").hasNonNull("language")) sample.put("language", input.path("answer").path("language").asText());
            sample.put("human_gold_score", result.path("final_score").asInt());
            if (result.path("suggested_score").isIntegralNumber()) sample.put("model_suggested_score", result.path("suggested_score").asInt());
            sample.put("decision", result.path("decision").asText(""));
            sample.put("reviewer_revised", result.path("reviewer_revised").asBoolean(false));
            if (result.hasNonNull("review_summary")) sample.put("review_summary", result.path("review_summary").asText());
            if (result.hasNonNull("risk_signals")) sample.set("risk_signals", result.path("risk_signals"));
        }
        var root = db.json.createObjectNode();
        root.put("name", "smarteval-export-run-" + run);
        root.put("exported_at", java.time.Instant.now().toString());
        root.put("anonymized_submissions", anonMap.size());
        root.put("sample_count", samples.size());
        root.set("samples", samples);
        try {
            return (db.json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }
    private static double round4(double value) { return Math.round(value * 10000.0) / 10000.0; }
}

@RestController
@RequestMapping("/api/v2")
class GradingAnalyticsController {
    private final GradingAccess access;
    private final GradingJobs jobs;
    private final GradingAnalytics analytics;

    GradingAnalyticsController(GradingAccess access, GradingJobs jobs, GradingAnalytics analytics) {
        this.access = access;
        this.jobs = jobs;
        this.analytics = analytics;
    }

    @GetMapping("/analytics/overview")
    Object overview(HttpServletRequest request) {
        return analytics.overview(access.teacher(request).requireTenant());
    }

    @GetMapping("/grading-jobs/{id}/analytics")
    Object jobAnalytics(HttpServletRequest request, @PathVariable UUID id, @RequestParam(required = false) Integer run) {
        var job = jobs.owned(access.actor(request, "grading:read"), id, false);
        return analytics.jobAnalytics(job, run);
    }

    @GetMapping("/grading-jobs/{id}/eval-dataset")
    ResponseEntity<byte[]> evalDataset(HttpServletRequest request, @PathVariable UUID id, @RequestParam(required = false) Integer run) {
        var teacher = access.teacher(request);
        var actor = new GradingAccess.Actor(teacher.requireTenant(), teacher.user(), null);
        var job = jobs.owned(actor, id, false);
        byte[] jsonBytes = analytics.exportEvalDataset(job, run);
        int targetRun = run == null ? job.run() : run;
        return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"eval-dataset-" + id + "-run-" + targetRun + ".json\"")
                .header("Cache-Control", "private, no-store")
                .body(jsonBytes);
    }
}
