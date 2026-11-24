package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Component
class GradingAccess {
    record Actor(UUID workspace, UUID user, UUID client) {
        UUID owner() { return client == null ? user : client; }
    }

    private final Identity identity;
    private final Database db;

    GradingAccess(Identity identity, Database db) {
        this.identity = identity;
        this.db = db;
    }

    Identity.Principal teacher(HttpServletRequest request) {
        var principal = identity.authenticate(request);
        if (!principal.roles().contains("TEACHER") && !principal.roles().contains("SCHOOL_ADMIN")) throw forbidden();
        return principal;
    }

    Actor actor(HttpServletRequest request, String scope) {
        String secret = request.getHeader("X-API-Key");
        if (secret == null) {
            var teacher = teacher(request);
            return new Actor(teacher.requireTenant(), teacher.user(), null);
        }
        request.setAttribute("traceId", UUID.randomUUID());
        return authenticateKey(secret, scope);
    }

    Actor authenticateKey(String secret, String scope) {
        if (secret == null || secret.length() != 43) throw Identity.unauthenticated();
        var actor = db.one("""
                SELECT c.tenant_id,c.id,c.scopes FROM api_credential a
                JOIN integration_client c ON c.tenant_id=a.tenant_id AND c.id=a.client_id
                JOIN tenant t ON t.id=c.tenant_id
                WHERE a.secret_hash=? AND a.expires_at>? AND a.revoked_at IS NULL
                AND c.status='ACTIVE' AND t.status='ACTIVE'
                """, (row,i) -> {
            if (scope == null) {
                return new Actor(Database.uuid(row,"tenant_id"), null, Database.uuid(row,"id"));
            }
            var scopes = db.parse(row.getString("scopes"));
            for (JsonNode permission : scopes) if (scope.equals(permission.asText())) {
                return new Actor(Database.uuid(row,"tenant_id"), null, Database.uuid(row,"id"));
            }
            throw forbidden();
        }, sha(secret), Instant.now());
        if (actor == null) throw Identity.unauthenticated();
        return actor;
    }

    private Identity.Principal adminPrincipal(HttpServletRequest request) {
        var principal = teacher(request);
        principal.requireRole("SCHOOL_ADMIN");
        principal.requireTenant();
        return principal;
    }

    private UUID admin(HttpServletRequest request) {
        return adminPrincipal(request).requireTenant();
    }

    record Key(UUID id, String name, Instant expires_at, Instant revoked_at) {}

    List<Key> keys(HttpServletRequest request) {
        return db.list("""
                SELECT a.id,c.name,a.expires_at,a.revoked_at FROM api_credential a
                JOIN integration_client c ON a.tenant_id=c.tenant_id AND a.client_id=c.id
                WHERE a.tenant_id=? AND c.scopes LIKE '%grading:submit%' ORDER BY a.created_at DESC
                """,(r,i)->new Key(Database.uuid(r,"id"),r.getString("name"),
                Database.time(r,"expires_at"),Database.time(r,"revoked_at")),admin(request));
    }

    @Transactional
    public Map<String,Object> createKey(HttpServletRequest request, JsonNode body) {
        var principal = adminPrincipal(request);
        UUID workspace = principal.requireTenant();
        String name = text(body,"name",140);
        int days = number(body,"expires_in_days",1,365);
        UUID client = UUID.randomUUID(), credential = UUID.randomUUID();
        String secret = Identity.random();
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(days * 86_400L);
        db.write("INSERT INTO integration_client(tenant_id,id,name,scopes,status,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,?)",
                workspace,client,name,db.jsonValue(List.of("grading:submit","grading:read")),now,now);
        db.write("INSERT INTO api_credential(tenant_id,id,client_id,secret_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)",
                workspace,credential,client,sha(secret),expiry,now);
        db.audit(workspace, principal.user(), "API_KEY_CREATED", "api_credential", credential,
                Map.of("name", name, "expires_in_days", days));
        return Map.of("id",credential,"name",name,"api_key",secret,"expires_at",expiry);
    }

    void revoke(HttpServletRequest request, UUID credential) {
        var principal = adminPrincipal(request);
        UUID workspace = principal.requireTenant();
        if (db.write("UPDATE api_credential SET revoked_at=? WHERE tenant_id=? AND id=? AND revoked_at IS NULL",
                Instant.now(),workspace,credential) == 0) throw missing();
        db.audit(workspace, principal.user(), "API_KEY_REVOKED", "api_credential", credential, Map.of());
    }
}

@RestController
@RequestMapping("/api/v2")
class GradingKeysController {
    private final GradingAccess access;

    GradingKeysController(GradingAccess access) { this.access = access; }

    @GetMapping("/auth/key")
    ResponseEntity<Void> verifyKey(HttpServletRequest request) {
        request.setAttribute("traceId", UUID.randomUUID());
        access.authenticateKey(request.getHeader("X-API-Key"), null);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @GetMapping("/keys")
    Object list(HttpServletRequest request) { return access.keys(request); }

    @PostMapping("/keys")
    ResponseEntity<?> create(HttpServletRequest request, @RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(access.createKey(request,body));
    }

    @DeleteMapping("/keys/{id}")
    ResponseEntity<?> revoke(HttpServletRequest request, @PathVariable UUID id) {
        access.revoke(request,id);
        return ResponseEntity.noContent().build();
    }
}
