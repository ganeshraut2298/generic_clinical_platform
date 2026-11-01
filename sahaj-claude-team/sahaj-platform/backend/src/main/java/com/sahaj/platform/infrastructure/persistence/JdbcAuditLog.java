package com.sahaj.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.infrastructure.security.CurrentTenant;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code platform.audit_event} in the caller's transaction ({@link Propagation#MANDATORY}).
 * The table fills {@code tenant_id} from the bound tenant and row level security checks it, so the
 * application never supplies a tenant. The actor comes from the logged-in principal.
 */
@Repository
public class JdbcAuditLog implements AuditLog {

    private static final String INSERT = """
            insert into platform.audit_event (actor_user_id, action, entity_type, entity_id, before_json, after_json)
            values (cast(:actor as uuid), :action, :entityType, cast(:entityId as uuid),
                    cast(:before as jsonb), cast(:after as jsonb))
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditLog(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        TenantPrincipal principal = CurrentTenant.principal()
                .orElseThrow(() -> new IllegalStateException("An audit event needs a logged-in clinic user"));
        jdbc.sql(INSERT)
                .param("actor", principal.userId().toString())
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId == null ? null : entityId.toString())
                .param("before", toJson(AuditSanitizer.sanitize(before)))
                .param("after", toJson(AuditSanitizer.sanitize(after)))
                .update();
    }

    private String toJson(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Audit values could not be written as JSON", ex);
        }
    }
}
