package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

final class Api {
    private Api() {}

    static byte[] key(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] key) {
        if (key == null) return null;
        var buffer = ByteBuffer.wrap(key);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static byte[] sha(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static byte[] sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    static URI httpEndpoint(String value) {
        try {
            URI uri = URI.create(value.strip().replaceAll("/+$", ""));
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() != 0 && uri.getPort() <= 65535
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) return uri;
        } catch (IllegalArgumentException ignored) {
            // Invalid service configuration is reported as unavailable by the API.
        }
        return null;
    }
    static UUID id(Object value) {
        try {
            String text = String.valueOf(value);
            UUID uuid = UUID.fromString(text);
            if (uuid.toString().equalsIgnoreCase(text)) return uuid;
        } catch (IllegalArgumentException ignored) {}
        throw error(400, "INVALID_REQUEST", "无效的资源标识");
    }

    static String text(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            throw error(422, "INVALID_FIELD", name + " 缺失或超出长度");
        }
        return value.asText();
    }
    static String bodyText(JsonNode node,String name,int maximumBytes) {
        String value=text(node,name,maximumBytes);
        if(value.getBytes(StandardCharsets.UTF_8).length>maximumBytes)throw error(422,"INVALID_FIELD",name+" 超出字节限制");
        return value;
    }

    static String optionalText(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > max) throw error(422, "INVALID_FIELD", name);
        return value.asText();
    }

    static int number(JsonNode node, String name, int min, int max) {
        var value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw error(422, "INVALID_FIELD", name + " 超出范围");
        }
        return value.intValue();
    }

    static ArrayNode array(JsonNode node, String name, int min, int max) {
        var value = node.path(name);
        if (!(value instanceof ArrayNode array) || array.size() < min || array.size() > max) {
            throw error(422, "INVALID_FIELD", name + " 数量无效");
        }
        return array;
    }

    static Instant instant(JsonNode node, String name) {
        try { return Instant.parse(text(node, name, 50)); }
        catch (java.time.format.DateTimeParseException e) { throw error(422, "INVALID_FIELD", name); }
    }

    static void match(String tag, int version) {
        if (!etag(version).equals(tag)) throw error(412, "STALE_VERSION", "请重新读取资源版本");
    }
    static String etag(int version) { return "\"" + version + "\""; }
    static Failure error(int status, String code, String message) { return new Failure(status, code, message); }
    static Failure missing() { return error(404, "RESOURCE_NOT_FOUND", "资源不存在或无权访问"); }
    static Failure forbidden() { return error(403, "FORBIDDEN", "缺少操作权限"); }

    static final class Failure extends RuntimeException {
        final int status;
        final String code;
        Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}

@RestControllerAdvice
class ApiErrors {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
    record Fault(String code, String message, UUID trace_id, List<Object> field_errors) {}

    @ExceptionHandler(Api.Failure.class)
    ResponseEntity<Fault> failure(Api.Failure e, HttpServletRequest request) {
        Object trace = request.getAttribute("traceId");
        return ResponseEntity.status(e.status).body(new Fault(e.code, e.getMessage(),
                trace instanceof UUID id ? id : UUID.randomUUID(), List.of()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Fault> invalid(Exception e, HttpServletRequest request) {
        return failure(Api.error(400, "INVALID_REQUEST", "请求格式错误"), request);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Fault> methodNotAllowed(Exception e, HttpServletRequest request) {
        return failure(Api.error(405, "METHOD_NOT_ALLOWED", "不支持该请求方法"), request);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Fault> notFound(Exception e, HttpServletRequest request) {
        return failure(Api.missing(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Fault> conflict(DuplicateKeyException e, HttpServletRequest request) {
        return failure(Api.error(409, "RESOURCE_CONFLICT", "资源已存在或有冲突"), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Fault> uploadSize(Exception e, HttpServletRequest request) {
        boolean zip = request.getRequestURI().endsWith("/assets/import-zip");
        return failure(Api.error(zip ? 422 : 413,zip ? "ZIP_SIZE" : "FILE_SIZE","上传请求超出大小限制"),request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Fault> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("Unhandled request error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return failure(Api.error(500, "INTERNAL_ERROR", "服务内部异常，请稍后重试"), request);
    }
}
