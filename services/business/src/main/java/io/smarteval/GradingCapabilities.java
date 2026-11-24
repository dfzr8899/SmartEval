package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GradingCapabilities {
    private static final long CACHE_SECONDS = 5;
    private final GradingAccess access;
    private final Intelligence intelligence;
    private final Judge judge;
    private final ModelSettings modelSettings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Cached cached;

    private record Cached(Instant expiresAt, Map<String, Object> body) {}

    GradingCapabilities(GradingAccess access, Intelligence intelligence, Judge judge, ModelSettings modelSettings) {
        this.access=access; this.intelligence=intelligence; this.judge=judge; this.modelSettings=modelSettings;
    }

    @GetMapping("/api/v2/capabilities")
    ResponseEntity<?> read(HttpServletRequest request) {
        var principal = access.teacher(request);
        principal.requireTenant();
        var override = modelSettings.overrideFor(principal.user());
        Cached snapshot = override == null ? cached : null;
        Instant now = Instant.now();
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return ResponseEntity.ok().header("Cache-Control","no-store").body(snapshot.body());
        }
        var ai = CompletableFuture.supplyAsync(() -> readIntelligence(override), executor);
        var languages = judge.readyLanguages();
        var state = ai.join();
        Map<String, Object> body = Map.of(
                "text_model",status(state,"text_model"),"vision",status(state,"vision"),"ocr",status(state,"ocr"),
                "similarity",status(state,"similarity"),
                "judge",Map.of("ready",!languages.isEmpty(),"languages",languages));
        if (override == null) {
            cached = new Cached(Instant.now().plusSeconds(CACHE_SECONDS), body);
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(body);
    }

    void invalidateCache() { cached = null; }

    @PreDestroy
    void close() { executor.shutdown(); }

    private JsonNode readIntelligence(Map<String, Object> override) {
        if (!intelligence.enabled()) return null;
        try {
            var req = override != null ? Map.<String, Object>of("model_override", override) : Map.<String, Object>of();
            return intelligence.post("/internal/v2/grading/capabilities", req, 5);
        }
        catch (Api.Failure e) { return null; }
    }

    private static Map<String,Boolean> status(JsonNode ai, String key) {
        return Map.of("ready",ai!=null && ai.path(key).asBoolean(false));
    }
}
