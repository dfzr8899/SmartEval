package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class ModelSettings {
    private static final String ENC_PREFIX = "enc:";
    private static final Set<String> FORMATS = Set.of("json_object", "text", "json_schema");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Database db;
    private final Webhooks webhooks;
    private final Intelligence intelligence;
    private final SecretKeySpec secretKey;

    record SavedConfig(UUID userId, String provider, String baseUrl, String modelName,
                       String apiKey, String responseFormat, Instant updatedAt) {}

    ModelSettings(Database db, Webhooks webhooks, Intelligence intelligence,
                  @Value("${smarteval.secret}") String appSecret) {
        this.db = db;
        this.webhooks = webhooks;
        this.intelligence = intelligence;
        this.secretKey = new SecretKeySpec(Api.sha("smarteval-user-model-key-v1:" + appSecret), "AES");
    }

    SavedConfig find(UUID userId) {
        if (userId == null) return null;
        return db.one("""
                SELECT user_id,provider,base_url,model_name,api_key_encrypted,response_format,updated_at
                FROM user_model_config WHERE user_id=?
                """, (r, i) -> new SavedConfig(
                Database.uuid(r, "user_id"),
                r.getString("provider"),
                r.getString("base_url"),
                r.getString("model_name"),
                decrypt(r.getString("api_key_encrypted")),
                r.getString("response_format"),
                Database.time(r, "updated_at")
        ), userId);
    }

    Map<String, Object> overrideFor(UUID primaryUser, UUID fallbackUser) {
        SavedConfig saved = find(primaryUser);
        if (saved == null && fallbackUser != null && !fallbackUser.equals(primaryUser)) {
            saved = find(fallbackUser);
        }
        return toOverride(saved);
    }

    Map<String, Object> overrideFor(UUID userId) {
        return toOverride(find(userId));
    }

    private static Map<String, Object> toOverride(SavedConfig saved) {
        if (saved == null) return null;
        var map = new LinkedHashMap<String, Object>();
        map.put("base_url", saved.baseUrl());
        map.put("api_key", saved.apiKey());
        map.put("model_name", saved.modelName());
        map.put("response_format", saved.responseFormat());
        return map;
    }

    Map<String, Object> view(UUID userId) {
        SavedConfig saved = find(userId);
        boolean serverDefault = false;
        if (intelligence.enabled()) {
            try {
                JsonNode caps = intelligence.post("/internal/v2/grading/capabilities", Map.of(), 4);
                serverDefault = caps.path("text_model").asBoolean(false);
            } catch (Api.Failure ignored) {
                serverDefault = false;
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("configured", saved != null);
        out.put("server_default_configured", serverDefault);
        if (saved != null) {
            out.put("provider", saved.provider());
            out.put("base_url", saved.baseUrl());
            out.put("model_name", saved.modelName());
            out.put("response_format", saved.responseFormat());
            out.put("api_key_masked", maskKey(saved.apiKey()));
            out.put("updated_at", saved.updatedAt());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> save(UUID tenantId, UUID userId, JsonNode body) {
        String provider = body.hasNonNull("provider") && !body.path("provider").asText("").isBlank()
                ? text(body, "provider", 40) : "custom";
        String baseUrl = validateBaseUrl(text(body, "base_url", 500));
        String modelName = text(body, "model_name", 120);
        String responseFormat = body.hasNonNull("response_format") && !body.path("response_format").asText("").isBlank()
                ? text(body, "response_format", 30) : "json_object";
        if (!FORMATS.contains(responseFormat)) {
            throw error(422, "INVALID_RESPONSE_FORMAT", "response_format 须为 json_object、text 或 json_schema");
        }
        SavedConfig existing = find(userId);
        String suppliedKey = body.hasNonNull("api_key") ? body.path("api_key").asText("").strip() : "";
        String finalKey = !suppliedKey.isEmpty() ? suppliedKey : (existing != null ? existing.apiKey() : "");
        if (finalKey.isEmpty() || finalKey.length() > 512) {
            throw error(422, "INVALID_API_KEY", "请提供有效的模型 API Key（1–512 字符）");
        }
        Instant now = Instant.now();
        String encrypted = encrypt(finalKey);
        if (existing == null) {
            db.write("""
                    INSERT INTO user_model_config(user_id,provider,base_url,model_name,api_key_encrypted,response_format,updated_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, userId, provider, baseUrl, modelName, encrypted, responseFormat, now);
        } else {
            db.write("""
                    UPDATE user_model_config SET provider=?,base_url=?,model_name=?,api_key_encrypted=?,response_format=?,updated_at=?
                    WHERE user_id=?
                    """, provider, baseUrl, modelName, encrypted, responseFormat, now, userId);
        }
        if (tenantId != null) {
            db.audit(tenantId, userId, "MODEL_CONFIG_UPDATED", "user_model_config", userId,
                    Map.of("provider", provider, "base_url", baseUrl, "model_name", modelName, "response_format", responseFormat));
        }
        return view(userId);
    }

    @Transactional
    public void delete(UUID tenantId, UUID userId) {
        db.write("DELETE FROM user_model_config WHERE user_id=?", userId);
        if (tenantId != null) {
            db.audit(tenantId, userId, "MODEL_CONFIG_CLEARED", "user_model_config", userId, Map.of());
        }
    }

    Map<String, Object> test(UUID userId, JsonNode body) {
        if (!intelligence.enabled()) {
            throw error(503, "AI_UNAVAILABLE", "智能服务未启动");
        }
        SavedConfig existing = find(userId);
        String baseUrl;
        String modelName;
        String apiKey;
        String responseFormat;
        if (body != null && body.isObject() && body.hasNonNull("base_url") && !body.path("base_url").asText("").isBlank()) {
            baseUrl = validateBaseUrl(text(body, "base_url", 500));
            modelName = text(body, "model_name", 120);
            responseFormat = body.hasNonNull("response_format") && !body.path("response_format").asText("").isBlank()
                    ? text(body, "response_format", 30) : "json_object";
            if (!FORMATS.contains(responseFormat)) {
                throw error(422, "INVALID_RESPONSE_FORMAT", "response_format 须为 json_object、text 或 json_schema");
            }
            String suppliedKey = body.hasNonNull("api_key") ? body.path("api_key").asText("").strip() : "";
            apiKey = !suppliedKey.isEmpty() ? suppliedKey : (existing != null ? existing.apiKey() : "");
            if (apiKey.isEmpty() || apiKey.length() > 512) {
                throw error(422, "INVALID_API_KEY", "请提供有效的模型 API Key");
            }
        } else if (existing != null) {
            baseUrl = existing.baseUrl();
            modelName = existing.modelName();
            apiKey = existing.apiKey();
            responseFormat = existing.responseFormat();
        } else {
            throw error(422, "MODEL_NOT_CONFIGURED", "请先填写模型接口地址、模型名与 API Key");
        }
        var override = new LinkedHashMap<String, Object>();
        override.put("base_url", baseUrl);
        override.put("api_key", apiKey);
        override.put("model_name", modelName);
        override.put("response_format", responseFormat);
        JsonNode resp = intelligence.post("/internal/v2/grading/test-model", Map.of("model_override", override), 20);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", resp.path("ok").asBoolean(false));
        if (resp.hasNonNull("model")) out.put("model", resp.path("model").asText());
        if (resp.hasNonNull("latency_ms")) out.put("latency_ms", resp.path("latency_ms").asInt());
        if (resp.hasNonNull("error")) out.put("error", resp.path("error").asText());
        return out;
    }

    private String validateBaseUrl(String raw) {
        String cleaned = raw.strip().replaceAll("/+$", "");
        String validated;
        try {
            validated = webhooks.validate(cleaned);
        } catch (Api.Failure e) {
            throw error(422, "INVALID_MODEL_URL", "模型接口地址必须是可解析的公网 HTTPS/HTTP 地址");
        }
        if (!validated.toLowerCase(Locale.ROOT).startsWith("https://") && !validated.toLowerCase(Locale.ROOT).startsWith("http://")) {
            throw error(422, "INVALID_MODEL_URL", "模型接口地址必须以 https:// 开头");
        }
        return validated.replaceAll("/+$", "");
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENC_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt model API key", e);
        }
    }

    private String decrypt(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) return stored;
        try {
            byte[] combined = Base64.getUrlDecoder().decode(stored.substring(ENC_PREFIX.length()));
            if (combined.length <= 12) throw new IllegalStateException("Invalid encrypted model API key");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt model API key", e);
        }
    }

    static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "••••";
        return key.substring(0, Math.min(4, key.length() - 4)) + "••••" + key.substring(key.length() - 4);
    }
}

@RestController
@RequestMapping("/api/v2/me/model-config")
class ModelSettingsController {
    private final GradingAccess access;
    private final ModelSettings settings;
    private final GradingCapabilities capabilities;

    ModelSettingsController(GradingAccess access, ModelSettings settings, GradingCapabilities capabilities) {
        this.access = access;
        this.settings = settings;
        this.capabilities = capabilities;
    }

    @GetMapping
    ResponseEntity<?> read(HttpServletRequest request) {
        var principal = access.teacher(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(settings.view(principal.user()));
    }

    @PutMapping
    ResponseEntity<?> save(HttpServletRequest request, @RequestBody JsonNode body) {
        var principal = access.teacher(request);
        var saved = settings.save(principal.tenant(), principal.user(), body);
        capabilities.invalidateCache();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(saved);
    }

    @DeleteMapping
    ResponseEntity<?> clear(HttpServletRequest request) {
        var principal = access.teacher(request);
        settings.delete(principal.tenant(), principal.user());
        capabilities.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    ResponseEntity<?> test(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        var principal = access.teacher(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(settings.test(principal.user(), body));
    }
}
