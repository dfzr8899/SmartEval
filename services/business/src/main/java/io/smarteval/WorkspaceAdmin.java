package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class WorkspaceAdmin {
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("TEACHER", "SCHOOL_ADMIN");
    private final Database db;
    private final Identity identity;

    WorkspaceAdmin(Database db, Identity identity) {
        this.db = db;
        this.identity = identity;
    }

    List<Map<String, Object>> members(UUID tenant) {
        var rows = db.list("""
                SELECT m.id,m.user_id,u.username_normalized,u.display_name,m.status,m.created_at
                FROM tenant_member m JOIN sys_user u ON u.id=m.user_id
                WHERE m.tenant_id=? ORDER BY m.created_at ASC,m.id ASC
                """, (r, i) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("member_id", Database.uuid(r, "id"));
            map.put("user_id", Database.uuid(r, "user_id"));
            map.put("username", r.getString("username_normalized"));
            map.put("display_name", r.getString("display_name"));
            map.put("status", r.getString("status"));
            map.put("created_at", Database.time(r, "created_at"));
            return map;
        }, tenant);

        var roleRows = db.list("SELECT member_id,role_key FROM member_role WHERE tenant_id=? ORDER BY role_key",
                (r, i) -> Map.entry(Database.uuid(r, "member_id"), r.getString("role_key")), tenant);
        Map<UUID, List<String>> rolesByMember = new HashMap<>();
        for (var entry : roleRows) {
            rolesByMember.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
        }
        for (var row : rows) {
            row.put("roles", rolesByMember.getOrDefault((UUID) row.get("member_id"), List.of()));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> createMember(UUID tenant, UUID actorUser, JsonNode body) {
        String rawUsername = text(body, "username", 120);
        String username = Identity.normalize(rawUsername);
        String displayName = text(body, "display_name", 160);
        String password = text(body, "password", 128);
        List<String> roles = parseRoles(body);

        if (db.one("SELECT id FROM sys_user WHERE username_normalized=?", (r, i) -> Database.uuid(r, "id"), username) != null) {
            throw error(409, "USERNAME_EXISTS", "该登录账号已存在");
        }
        UUID userId = identity.createAccount(username, password, displayName);
        UUID memberId = UUID.randomUUID();
        Instant now = Instant.now();
        db.write("INSERT INTO tenant_member(tenant_id,id,user_id,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",
                tenant, memberId, userId, now, now);
        for (String role : roles) {
            db.write("INSERT INTO member_role(tenant_id,member_id,role_key,created_at) VALUES(?,?,?,?)",
                    tenant, memberId, role, now);
        }
        db.audit(tenant, actorUser, "MEMBER_CREATED", "tenant_member", memberId,
                Map.of("username", username, "display_name", displayName, "roles", roles));
        var out = new LinkedHashMap<String, Object>();
        out.put("member_id", memberId);
        out.put("user_id", userId);
        out.put("username", username);
        out.put("display_name", displayName);
        out.put("status", "ACTIVE");
        out.put("roles", roles);
        out.put("created_at", now);
        return out;
    }

    @Transactional
    public Map<String, Object> updateMember(UUID tenant, UUID actorUser, UUID memberId, JsonNode body) {
        var existing = db.required("""
                SELECT m.id,m.user_id,u.username_normalized,u.display_name,m.status,m.created_at
                FROM tenant_member m JOIN sys_user u ON u.id=m.user_id
                WHERE m.tenant_id=? AND m.id=? FOR UPDATE
                """, (r, i) -> Map.<String, Object>of(
                "member_id", Database.uuid(r, "id"),
                "user_id", Database.uuid(r, "user_id"),
                "username", r.getString("username_normalized"),
                "display_name", r.getString("display_name"),
                "status", r.getString("status"),
                "created_at", Database.time(r, "created_at")), tenant, memberId);

        String newStatus = body.hasNonNull("status") ? text(body, "status", 20) : (String) existing.get("status");
        if (!Set.of("ACTIVE", "DISABLED").contains(newStatus)) {
            throw error(422, "INVALID_FIELD", "状态须为 ACTIVE 或 DISABLED");
        }
        List<String> currentRoles = db.list("SELECT role_key FROM member_role WHERE tenant_id=? AND member_id=? ORDER BY role_key",
                (r, i) -> r.getString(1), tenant, memberId);
        List<String> newRoles = body.hasNonNull("roles") ? parseRoles(body) : currentRoles;

        UUID targetUserId = (UUID) existing.get("user_id");
        if ("DISABLED".equals(newStatus) && targetUserId.equals(actorUser)) {
            throw error(409, "CANNOT_DISABLE_SELF", "不能停用当前登录的管理员账号");
        }
        boolean wasActiveAdmin = "ACTIVE".equals(existing.get("status")) && currentRoles.contains("SCHOOL_ADMIN");
        boolean willBeActiveAdmin = "ACTIVE".equals(newStatus) && newRoles.contains("SCHOOL_ADMIN");
        if (wasActiveAdmin && !willBeActiveAdmin) {
            int activeAdmins = db.required("""
                    SELECT COUNT(DISTINCT m.id) FROM tenant_member m
                    JOIN member_role r ON r.tenant_id=m.tenant_id AND r.member_id=m.id
                    WHERE m.tenant_id=? AND m.status='ACTIVE' AND r.role_key='SCHOOL_ADMIN'
                    """, (r, i) -> r.getInt(1), tenant);
            if (activeAdmins <= 1) {
                throw error(409, "LAST_ADMIN_PROTECTED", "工作空间至少需保留一名启用状态的管理员");
            }
        }

        Instant now = Instant.now();
        db.write("UPDATE tenant_member SET status=?,row_version=row_version+1,updated_at=? WHERE tenant_id=? AND id=?",
                newStatus, now, tenant, memberId);
        if (body.hasNonNull("roles")) {
            db.write("DELETE FROM member_role WHERE tenant_id=? AND member_id=?", tenant, memberId);
            for (String role : newRoles) {
                db.write("INSERT INTO member_role(tenant_id,member_id,role_key,created_at) VALUES(?,?,?,?)",
                        tenant, memberId, role, now);
            }
        }
        if ("DISABLED".equals(newStatus)) {
            db.write("UPDATE auth_session SET revoked_at=?,updated_at=? WHERE user_id=? AND active_tenant_id=? AND revoked_at IS NULL",
                    now, now, targetUserId, tenant);
        }
        db.audit(tenant, actorUser, "MEMBER_UPDATED", "tenant_member", memberId,
                Map.of("status", newStatus, "roles", newRoles));

        var out = new LinkedHashMap<String, Object>(existing);
        out.put("status", newStatus);
        out.put("roles", newRoles);
        return out;
    }

    List<Map<String, Object>> auditLogs(UUID tenant, int page, String actionFilter) {
        if (page < 1 || page > 1000) throw error(422, "INVALID_PAGE", "页码无效");
        boolean filtered = actionFilter != null && !actionFilter.isBlank();
        String sql = """
                SELECT a.id,a.actor_type,a.actor_id,u.display_name,u.username_normalized,
                       a.action,a.target_type,a.target_id,a.trace_id,a.details,a.created_at
                FROM audit_log a LEFT JOIN sys_user u ON u.id=a.actor_id AND a.actor_type='USER'
                WHERE a.tenant_id=?"""
                + (filtered ? " AND a.action=?" : "")
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT 50 OFFSET ?";
        Object[] args = filtered
                ? new Object[]{tenant, actionFilter.strip(), (page - 1) * 50}
                : new Object[]{tenant, (page - 1) * 50};
        return db.list(sql, (r, i) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", Database.uuid(r, "id"));
            row.put("actor_type", r.getString("actor_type"));
            row.put("actor_id", Database.uuid(r, "actor_id"));
            row.put("actor_name", r.getString("display_name") != null ? r.getString("display_name") : r.getString("actor_type"));
            row.put("actor_username", r.getString("username_normalized"));
            row.put("action", r.getString("action"));
            row.put("target_type", r.getString("target_type"));
            row.put("target_id", Database.uuid(r, "target_id"));
            row.put("trace_id", Database.uuid(r, "trace_id"));
            row.put("details", db.parse(r.getString("details")));
            row.put("created_at", Database.time(r, "created_at"));
            return row;
        }, args);
    }

    private static List<String> parseRoles(JsonNode body) {
        var array = array(body, "roles", 1, 2);
        var roles = new LinkedHashSet<String>();
        for (JsonNode node : array) {
            if (!node.isTextual() || !ASSIGNABLE_ROLES.contains(node.asText())) {
                throw error(422, "INVALID_ROLE", "角色须为 TEACHER 或 SCHOOL_ADMIN");
            }
            roles.add(node.asText());
        }
        return List.copyOf(roles);
    }
}

@RestController
@RequestMapping("/api/v2/workspace")
class WorkspaceAdminController {
    private final GradingAccess access;
    private final WorkspaceAdmin admin;

    WorkspaceAdminController(GradingAccess access, WorkspaceAdmin admin) {
        this.access = access;
        this.admin = admin;
    }

    private Identity.Principal requireAdmin(HttpServletRequest request) {
        var principal = access.teacher(request);
        principal.requireRole("SCHOOL_ADMIN");
        principal.requireTenant();
        return principal;
    }

    @GetMapping("/members")
    Object listMembers(HttpServletRequest request) {
        return admin.members(requireAdmin(request).requireTenant());
    }

    @PostMapping("/members")
    ResponseEntity<?> createMember(HttpServletRequest request, @RequestBody JsonNode body) {
        var principal = requireAdmin(request);
        return ResponseEntity.status(201).body(admin.createMember(principal.requireTenant(), principal.user(), body));
    }

    @PatchMapping("/members/{memberId}")
    Object updateMember(HttpServletRequest request, @PathVariable UUID memberId, @RequestBody JsonNode body) {
        var principal = requireAdmin(request);
        return admin.updateMember(principal.requireTenant(), principal.user(), memberId, body);
    }

    @GetMapping("/audit-logs")
    Object auditLogs(HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
                     @RequestParam(required = false) String action) {
        return admin.auditLogs(requireAdmin(request).requireTenant(), page, action);
    }
}
