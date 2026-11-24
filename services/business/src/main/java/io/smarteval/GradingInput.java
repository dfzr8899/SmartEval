package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

final class GradingInput {
    static final int MAX_POINTS = 10_000;
    static final int MAX_SUBMISSIONS = 100;
    static final int MAX_MATERIALS = 100;
    static final int MAX_ITEMS = 1000;
    static final long MAX_MATERIAL_BYTES = 200L * 1024 * 1024;
    static final Set<String> KINDS = Set.of("TEXT","EXACT","CODE");
    static final Set<String> LANGUAGES = Set.of("PYTHON","JAVA","CPP","C","JAVASCRIPT");

    private GradingInput() {}

    static boolean complete(JsonNode draft, JsonNode sources, JsonNode input) {
        try { validate(draft,sources,input); return true; }
        catch (Api.Failure e) { return false; }
    }

    static void validate(JsonNode draft, JsonNode sources, JsonNode input) {
        var references = new java.util.HashMap<String,JsonNode>();
        sources.forEach(s -> references.put(s.path("id").asText(),s));
        var expected = new HashSet<String>();
        input.path("submissions").forEach(s -> expected.add(s.asText()));
        var questionIds = new HashSet<String>();
        int items = 0;
        for (var question : array(draft,"questions",1,80)) {
            String questionId = text(question,"id",80);
            if (!questionIds.add(questionId)) throw error(422,"DUPLICATE_QUESTION","题号重复");
            standard(question);
            locations(question,references,"");
            var submissions = new HashSet<String>();
            for (var answer : array(question,"answers",1,MAX_SUBMISSIONS)) {
                String submission = text(answer,"submission_id",100);
                if (!submissions.add(submission)) throw error(422,"DUPLICATE_ANSWER","同一道题有重复答卷编号");
                if (!answer.path("text").isTextual() || answer.path("text").asText().length() > 24_000) {
                    throw error(422,"INVALID_ANSWER","答案必须是长度不超过 24000 的文本");
                }
                if (answer.path("needs_input").asBoolean()) throw error(422,"ANSWER_NEEDS_INPUT","请确认未识别的作答，空白答案也可明确保留");
                if (question.path("kind").asText().equals("CODE") && !answer.path("text").asText().isBlank()
                        && !LANGUAGES.contains(answer.path("language").asText())) throw error(422,"INVALID_LANGUAGE","请选择支持的编程语言");
                locations(answer,references,submission);
                items++;
            }
            if (!submissions.equals(expected)) throw error(422,"MISSING_ANSWER","每题须包含所有答卷，可明确提交空白答案");
        }
        if (items > MAX_ITEMS) throw error(422,"TOO_MANY_ITEMS","一次任务最多批改 1000 个单题答案");
    }

    private static void locations(JsonNode item, java.util.Map<String,JsonNode> sources, String submission) {
        if (!item.has("source_ids")) return;
        for (var ref : array(item,"source_ids",0,80)) {
            var source = sources.get(ref.asText());
            if (source == null || !submission.isEmpty() && (!submission.equals(source.path("submission_id").asText())
                    || !source.path("role").asText().equals("ANSWER"))) {
                throw error(422,"INVALID_SOURCE","引用的原文区块无效");
            }
        }
    }

    static void standard(JsonNode question) {
        bodyText(question,"prompt",16_000);
        optionalText(question,"reference_answer",16_000);
        String kind = text(question,"kind",10);
        if (!KINDS.contains(kind)) throw error(422,"INVALID_KIND","题型应为 TEXT、EXACT 或 CODE");
        int max = number(question,"max_score",1,MAX_POINTS);
        if (kind.equals("EXACT")) {
            for (var value : array(question,"accepted",1,20)) {
                if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>2000)
                    throw error(422,"INVALID_ACCEPTED","请填写有效的标准答案");
            }
            return;
        }
        int sum = 0;
        var ids = new HashSet<String>();
        String field = kind.equals("CODE") ? "tests" : "rubric";
        for (var criterion : array(question,field,1,kind.equals("CODE") ? 8 : 24)) {
            if (!ids.add(text(criterion,"id",80))) throw error(422,"DUPLICATE_CRITERION","评分项编号重复");
            sum += number(criterion,"points",1,max);
            if (kind.equals("CODE")) {
                if (!criterion.path("input").isTextual() || !criterion.path("expected").isTextual()
                        || criterion.path("input").asText().length()>8192 || criterion.path("expected").asText().length()>8192)
                    throw error(422,"INVALID_TEST","请填写判题用例输入与预期输出");
            } else text(criterion,"description",2000);
        }
        if (max != sum) throw error(422,"SCORE_TOTAL","各评分项或用例分值之和须等于本题满分");
    }

    static ObjectNode withoutAnswers(JsonNode question) {
        ObjectNode copy = question.deepCopy();
        copy.remove(java.util.List.of("answers","source_ids"));
        return copy;
    }
}
