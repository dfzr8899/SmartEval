package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class GradingCsv {
    private GradingCsv() {}

    static byte[] encode(JsonNode report) {
        var questions = new java.util.LinkedHashSet<String>();
        var bySubmission = new LinkedHashMap<String,Map<String,JsonNode>>();
        for (var item : report.path("items")) {
            String question = item.path("question_id").asText();
            questions.add(question);
            bySubmission.computeIfAbsent(item.path("submission_id").asText(),ignored -> new LinkedHashMap<>())
                    .put(question,item);
        }
        var output = new StringBuilder("\uFEFF");
        var headers = new ArrayList<>(List.of("submission_id"));
        for (String question : questions) {
            headers.add(question + " 建议分");
            headers.add(question + " 最终分");
            headers.add(question + " 状态");
        }
        headers.addAll(List.of("总建议分","总最终分","人工复核备注"));
        row(output,headers);
        for (var summary : report.path("submissions")) {
            String id = summary.path("submission_id").asText();
            var items = bySubmission.getOrDefault(id, Map.of());
            var cells = new ArrayList<>(List.of(id));
            var notes = new StringJoiner("\n");
            for (String question : questions) {
                var item = items.getOrDefault(question,com.fasterxml.jackson.databind.node.MissingNode.getInstance());
                var result = item.path("result");
                cells.add(result.path("suggested_score").asText(""));
                cells.add(result.path("final_score").asText(""));
                cells.add(item.path("status").asText(""));
                String reason = result.path("review_reason").asText("");
                if (!reason.isEmpty()) notes.add(question + "：" + reason);
            }
            cells.add(summary.path("scored_items").asInt() == 0 ? "" : summary.path("suggested_points_so_far").asText());
            cells.add(summary.path("final_items").asInt() == 0 ? "" : summary.path("final_points_so_far").asText());
            cells.add(notes.toString());
            row(output,cells);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder output, List<String> cells) {
        var values = new StringJoiner(",");
        for (String value : cells) {
            String lead = value.stripLeading();
            boolean formula = !lead.isEmpty() && "=+-@＝＋－＠".indexOf(lead.charAt(0)) >= 0
                    || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n");
            values.add("\"" + (formula ? "'" : "") + value.replace("\"","\"\"") + "\"");
        }
        output.append(values).append("\r\n");
    }
}
