package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class GradingEngine {
    private final Database db;
    private final Intelligence intelligence;
    private final Judge judge;
    private final ModelSettings modelSettings;
    private final int judgeMemoryKib;
    private final int judgeCpuTimeMs;
    private final int judgeWallTimeMs;
    private static final String VERSION = "grading-openai-v1";

    GradingEngine(Database db, Intelligence intelligence, Judge judge, ModelSettings modelSettings,
                  @Value("${SMARTEVAL_JUDGE_MEMORY_KIB:262144}") int judgeMemoryKib,
                  @Value("${SMARTEVAL_JUDGE_CPU_TIME_MS:3000}") int judgeCpuTimeMs,
                  @Value("${SMARTEVAL_JUDGE_WALL_TIME_MS:5000}") int judgeWallTimeMs) {
        this.db=db; this.intelligence=intelligence; this.judge=judge; this.modelSettings=modelSettings;
        this.judgeMemoryKib = Math.max(64 * 1024, Math.min(judgeMemoryKib, 1024 * 1024));
        this.judgeCpuTimeMs = Math.max(500, Math.min(judgeCpuTimeMs, 10_000));
        this.judgeWallTimeMs = Math.max(1000, Math.min(judgeWallTimeMs, 15_000));
    }

    ObjectNode configuration() {
        return db.json.createObjectNode().put("engine_version",VERSION).put("judge",judge.configuration());
    }

    ObjectNode configurationForConfirmation(JsonNode questions) {
        return configurationForConfirmation(questions, null);
    }

    ObjectNode configurationForConfirmation(JsonNode questions, UUID userId) {
        boolean text = false;
        boolean code = false;
        for (var question : questions) {
            text |= question.path("kind").asText().equals("TEXT");
            if (!question.path("kind").asText().equals("CODE")) continue;
            code = true;
            for (var answer : question.path("answers")) {
                String language = answer.path("language").asText();
                if (!language.isBlank() && !judge.supports(language)) {
                    throw error(503,"JUDGE_UNAVAILABLE","判题服务未配置语言："+language);
                }
            }
        }
        var config = configuration();
        if (!text && !code) return config;
        if (!intelligence.enabled()) {
            if (text) throw error(503,"MODEL_NOT_CONFIGURED","主观题评分和证据复核需要已配置的智能服务与模型");
            return config;
        }
        var override = modelSettings.overrideFor(userId);
        var reqBody = override != null ? Map.<String, Object>of("model_override", override) : Map.<String, Object>of();
        JsonNode roles;
        try {
            roles = intelligence.post("/internal/v2/grading/model-configuration", reqBody, 5).path("role_models");
        } catch (Api.Failure e) {
            if (text) throw e;
            return config;
        }
        if (text) for (String role : List.of("Grader","EvidenceReviewer")) {
            if (!hasModel(roles, role)) throw error(503,"MODEL_NOT_CONFIGURED","尚未配置可用模型，请点击顶部「模型与 Key」填入您自己的 API Key");
        }
        if (roles.isObject()) config.set("role_models", roles);
        return config;
    }

    private static boolean hasModel(JsonNode roles, String role) {
        var model = roles.path(role);
        return !model.path("model").asText().isBlank() && !model.path("configuration_id").asText().isBlank();
    }

    JsonNode prepare(GradingJobs.Job job) {
        var local = db.json.createObjectNode();
        var questions = job.input().path("questions");
        if (job.input().path("materials").isEmpty() && job.input().path("template_questions").isMissingNode()
                && job.input().path("collected_answers").isMissingNode()
                && (!intelligence.enabled() || GradingInput.complete(db.json.createObjectNode().set("questions",questions),
                        db.json.createArrayNode(),job.input()))) {
            local.putObject("draft").set("questions",questions);
            local.putArray("sources");
            return local;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("workspace", job.workspace());
        body.put("input", job.input());
        body.put("config", configuration());
        var override = modelSettings.overrideFor(job.confirmedBy(), job.owner());
        if (override != null) body.put("model_override", override);
        return intelligence.post("/internal/v2/grading/prepare", body, 175);
    }

    ObjectNode score(JsonNode item, JsonNode config) {
        return score(item, config, null, null);
    }

    ObjectNode score(JsonNode item, JsonNode config, UUID ownerId, UUID confirmedBy) {
        var answer = item.path("answer");
        String content = answer.path("text").asText();
        int max = item.path("max_score").asInt();
        if (content.isBlank()) {
            var result = db.json.createObjectNode().put("reason","空白作答").put("decision","RULE");
            result.putArray("criteria");
            return score(result,0);
        }
        if (item.path("kind").asText().equals("EXACT")) {
            boolean correct = false;
            for (var accepted : item.path("accepted")) correct |= accepted.asText().strip().equals(content.strip());
            var result = db.json.createObjectNode().put("decision","RULE");
            result.putArray("criteria").addObject().put("id","exact").put("points",correct?max:0)
                    .put("max_points",max).put("reason",correct?"与标准答案一致":"与标准答案不一致")
                    .put("start",0).put("end",content.length()).put("quote",content);
            return score(result,correct?max:0);
        }
        if (item.path("kind").asText().equals("CODE")) {
            return code(item,config,answer,ownerId,confirmedBy);
        }
        if (!hasModel(config.path("role_models"), "Grader")) throw error(503,"MODEL_NOT_CONFIGURED","主观题评分模型未配置");
        var body = new LinkedHashMap<String, Object>();
        body.put("item", item);
        body.put("config", config);
        var override = modelSettings.overrideFor(confirmedBy, ownerId);
        if (override != null) body.put("model_override", override);
        JsonNode response = intelligence.post("/internal/v2/grading/score", body, 100);
        if (!response.isObject()) throw error(503,"INVALID_GRADE","评分结果格式无效");
        ObjectNode result = (ObjectNode) response;
        int sum=0;
        boolean supported=true;
        var standards = new HashMap<String,Integer>();
        item.path("rubric").forEach(c -> standards.put(c.path("id").asText(),c.path("points").asInt()));
        var seen = new HashSet<String>();
        for (var criterion : result.path("criteria")) {
            String id = criterion.path("id").asText();
            Integer cap = standards.get(id);
            var awarded = criterion.path("points");
            if (cap==null || !seen.add(id) || !awarded.isIntegralNumber() || !awarded.canConvertToInt()
                    || awarded.intValue()<0 || awarded.intValue()>cap || !criterion.path("reason").isTextual()) {
                throw error(503,"INVALID_GRADE","模型评分超出已确认的标准");
            }
            var row = (ObjectNode) criterion;
            row.put("max_points",cap);
            sum+=awarded.intValue();
            String quote = row.path("quote").asText();
            int[] span = findQuoteRange(content, quote);
            if (span != null) {
                row.put("quote", content.substring(span[0], span[1])).put("start", span[0]).put("end", span[1]);
            } else {
                supported=false;
                row.remove(List.of("quote","start","end"));
            }
        }
        if (!seen.equals(standards.keySet())) throw error(503,"INVALID_GRADE","评分结果缺少评分项");
        boolean totalConsistent = true;
        JsonNode declaredTotal = result.path("declared_total_score");
        if (!declaredTotal.isMissingNode() && !declaredTotal.isNull()) {
            totalConsistent = declaredTotal.isIntegralNumber() && declaredTotal.canConvertToInt() && declaredTotal.intValue() == sum;
        }
        result.put("decision","AGENTS").put("suggested_score",sum);
        var signals = result.putArray("risk_signals");
        if (!supported) signals.add("ANCHOR_MISMATCH");
        if (!totalConsistent) signals.add("DECLARED_TOTAL_MISMATCH");
        if (result.path("trace").has("review_error")) signals.add("REVIEW_AGENT_UNAVAILABLE");
        else if (!(result.path("approved").isBoolean() && result.path("approved").booleanValue())) signals.add("REVIEWER_VETO");
        if (result.path("reviewer_revised").asBoolean(false)) signals.add("REVIEWER_REVISED");
        if (supported && totalConsistent && result.path("approved").isBoolean() && result.path("approved").booleanValue()) {
            result.put("final_score",sum);
        } else {
            result.putNull("final_score");
            String summary = result.path("review_summary").asText("").strip();
            String reason = !supported ? "评分项缺少可核验的原文引用"
                    : !totalConsistent ? "模型声明总分与分项求和不一致"
                    : result.path("trace").has("review_error") ? "复核 Agent 未能完成"
                    : !summary.isEmpty() ? "复核 Agent 异议：" + summary : "复核 Agent 对给分依据有异议";
            result.put("review_reason",reason);
        }
        return result;
    }

    private ObjectNode code(JsonNode item, JsonNode config, JsonNode answer, UUID ownerId, UUID confirmedBy) {
        if (!judge.supports(answer.path("language").asText()) || !Objects.equals(config.path("judge").asText(null),judge.configuration())) {
            throw error(503,"JUDGE_UNAVAILABLE","已确认的判题配置不可用");
        }
        var result = db.json.createObjectNode().put("decision","JUDGE");
        var criteria = result.putArray("criteria");
        int sum=0;
        var trials = new ArrayList<Callable<ObjectNode>>();
        item.path("tests").forEach(test -> trials.add(() -> judgeCriterion(answer,test)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start=0; start<trials.size(); start+=4) {
                var results = executor.invokeAll(trials.subList(start,Math.min(start+4,trials.size())),
                        Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                for (var outcome : results) {
                    var criterion = outcome.get();
                    criteria.add(criterion);
                    sum+=criterion.path("points").asInt();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(503,"JUDGE_ERROR","试运行已中断");
        } catch (CancellationException e) {
            throw error(503,"JUDGE_WAIT_TIMEOUT","单题判题超时");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Api.Failure failure) throw failure;
            throw error(503,"JUDGE_ERROR","判题服务异常");
        }
        if (hasModel(config.path("role_models"), "LogicReviewer")) explainCode(item,answer,config,result,ownerId,confirmedBy);
        return score(result,sum);
    }

    private ObjectNode judgeCriterion(JsonNode answer, JsonNode test) {
        var input = db.json.createObjectNode().put("language",answer.path("language").asText())
                .put("source",answer.path("text").asText()).put("stdin",test.path("input").asText());
        var limits = db.json.createObjectNode().put("cpu_time_ms",judgeCpuTimeMs)
                .put("wall_time_ms",judgeWallTimeMs).put("memory_kib",judgeMemoryKib);
        var execution = judge.trial(input,limits);
        String verdict = execution.path("verdict").asText();
        if (verdict.equals("AC") && !Judge.normalize(execution.path("stdout").asText(""))
                .equals(Judge.normalize(test.path("expected").asText()))) verdict="WA";
        int cap=test.path("points").asInt();
        var criterion = db.json.createObjectNode().put("id",test.path("id").asText()).put("points",verdict.equals("AC") ? cap : 0)
                .put("max_points",cap).put("reason",verdict).put("wall_time_ms",execution.path("wall_time_ms").asInt());
        if (!verdict.equals("AC")) {
            String diagnostic = diagnosticText(verdict, execution);
            if (!diagnostic.isBlank()) criterion.put("detail", diagnostic);
        }
        return criterion;
    }

    private static String diagnosticText(String verdict, JsonNode execution) {
        String raw = switch (verdict) {
            case "CE" -> execution.path("compile_output").asText("");
            case "RE" -> execution.path("stderr").asText("");
            case "WA" -> execution.path("stdout").asText("");
            default -> execution.path("stderr").asText("");
        };
        if (raw.isBlank()) raw = execution.path("stderr").asText("");
        if (raw.isBlank()) raw = execution.path("compile_output").asText("");
        String trimmed = raw.strip();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) + "…" : trimmed;
    }

    private void explainCode(JsonNode item, JsonNode answer, JsonNode config, ObjectNode result, UUID ownerId, UUID confirmedBy) {
        var source = answer.path("text").asText();
        try {
            var context = Map.of("prompt",item.path("prompt").asText(),"language",answer.path("language").asText(),
                    "source",source,"verdicts",result.path("criteria"));
            var body = new LinkedHashMap<String, Object>();
            body.put("item", context);
            body.put("config", config);
            var override = modelSettings.overrideFor(confirmedBy, ownerId);
            if (override != null) body.put("model_override", override);
            JsonNode response = intelligence.post("/internal/v2/grading/explain-code", body, 30);
            if (response==null || !response.path("findings").isArray()) {
                result.put("analysis_error","CODE_ANALYSIS_UNAVAILABLE");
                return;
            }
            var explanations = result.putArray("findings");
            for (var finding : response.path("findings")) {
                String quote = finding.path("quote").asText();
                String reason = finding.path("reason").asText();
                if (quote.isBlank() || reason.isBlank() || quote.length()>2000 || reason.length()>2000) continue;
                int[] span = findQuoteRange(source, quote);
                if (span != null) explanations.addObject().put("quote",source.substring(span[0], span[1])).put("reason",reason)
                        .put("line",1+(int)source.substring(0,span[0]).chars().filter(c -> c=='\n').count());
                if (explanations.size()>=10) break;
            }
            if (response.path("trace").isObject()) result.set("trace",response.path("trace"));
        } catch (Api.Failure e) {
            result.put("analysis_error","CODE_ANALYSIS_UNAVAILABLE");
        }
    }

    static int[] findQuoteRange(String content, String quote) {
        if (quote == null || quote.isBlank()) return null;
        int exact = content.indexOf(quote);
        if (exact >= 0) return new int[]{exact, exact + quote.length()};
        if (content.indexOf('\r') < 0 && quote.indexOf('\r') < 0) return null;
        String normQuote = quote.replace("\r\n", "\n").replace('\r', '\n');
        if (normQuote.isEmpty()) return null;
        StringBuilder norm = new StringBuilder(content.length());
        int[] startMap = new int[content.length()];
        int[] endMap = new int[content.length()];
        int n = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                startMap[n] = i;
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                endMap[n] = i + 1;
                norm.append('\n');
                n++;
            } else {
                startMap[n] = i;
                endMap[n] = i + 1;
                norm.append(c);
                n++;
            }
        }
        int pos = norm.indexOf(normQuote);
        if (pos < 0) return null;
        return new int[]{startMap[pos], endMap[pos + normQuote.length() - 1]};
    }

    private static ObjectNode score(ObjectNode result, int points) {
        return result.put("suggested_score",points).put("final_score",points);
    }
}
