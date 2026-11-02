package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.AuditEventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code platform.audit_event} for the change history (SAH-7). The table is append-only and
 * its values were sanitised when written. The person is named as the clinic knows them
 * ({@code membership.display_name}, else the account's name).
 */
@Repository
public class JdbcAuditEventRepository implements AuditEventRepository {

    private static final String FIND_LATEST = """
            select a.id, coalesce(m.display_name, u.full_name) as actor_name, a.action, a.entity_type,
                   a.before_json::text as before_json, a.after_json::text as after_json, a.at
            from platform.audit_event a
            left join platform.membership m on m.user_id = a.actor_user_id and m.tenant_id = a.tenant_id
            left join platform.app_user u on u.id = a.actor_user_id
            where a.tenant_id = :tenantId
            order by a.at desc, a.id desc
            limit :limit
            """;

    private final JdbcClient jdbc;

    public JdbcAuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditRow> findLatest(UUID tenantId, int limit) {
        return jdbc.sql(FIND_LATEST).param("tenantId", tenantId).param("limit", limit)
                .query(JdbcAuditEventRepository::map).list();
    }

    private static AuditRow map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp at = rs.getTimestamp("at");
        return new AuditRow(rs.getObject("id", UUID.class), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("before_json"), rs.getString("after_json"),
                at.toInstant());
    }
}
