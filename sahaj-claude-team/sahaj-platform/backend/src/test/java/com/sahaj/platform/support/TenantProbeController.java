package com.sahaj.platform.support;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY controller for the database integration tests (SAH-1 criteria 2, 3, 8, 10). It is not
 * part of the product API and lives under {@code src/test}.
 *
 * <p>It is not found by component scanning ({@link TestComponent}); an integration test has to pull
 * it in with {@code @Import(TenantProbeController.class)}. All calls need a logged-in user, like any
 * other protected path, and read or write only through the request pool as {@code sahaj_app}, so
 * row level security applies.
 *
 * <p>The tenant is never read from the request: an {@code X-Tenant-Id} header or a
 * {@code tenantId} query parameter or body field has no effect on any of these calls.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/platform/test-probe")
public class TenantProbeController {

    /** Body for creating a tenant row. The row can only be created for the caller's own tenant. */
    public record NewTenant(UUID id, String name) {
    }

    private final JdbcClient jdbc;
    private final AuditLog auditLog;

    public TenantProbeController(JdbcClient jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /** Reads one tenant by id. A tenant that is not the caller's own is absent, so it answers 404. */
    @GetMapping("/tenants/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> tenantById(@PathVariable UUID id) {
        return jdbc.sql("select id, name from platform.tenant where id = :id")
                .param("id", id)
                .query((rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class), "name", rs.getString("name")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    /** Lists every tenant row the caller can see: one with a tenant bound, none without. */
    @GetMapping("/tenants")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> visibleTenants() {
        return jdbc.sql("select id, name from platform.tenant")
                .query((rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class), "name", rs.getString("name")))
                .list();
    }

    /**
     * Creates a tenant row and records the audit event in the same transaction. Row level security
     * refuses the insert when the id is not the caller's own tenant.
     */
    @PostMapping("/tenants")
    @Transactional
    public Map<String, Object> createTenant(@RequestBody NewTenant body) {
        jdbc.sql("insert into platform.tenant (id, name) values (:id, :name)")
                .param("id", body.id())
                .param("name", body.name())
                .update();
        auditLog.record("TENANT_CREATED", "TENANT", body.id(), null, Map.of("name", body.name()));
        return Map.of("id", body.id());
    }

    /** Lists the audit events of the caller's tenant. */
    @GetMapping("/audit-events")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> auditEvents() {
        return jdbc.sql("select action, entity_type, entity_id, actor_user_id, at from platform.audit_event order by at")
                .query((rs, n) -> Map.<String, Object>of(
                        "action", rs.getString("action"),
                        "entityType", rs.getString("entity_type"),
                        "entityId", String.valueOf(rs.getObject("entity_id")),
                        "actorUserId", String.valueOf(rs.getObject("actor_user_id"))))
                .list();
    }
}
