package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.AlertRuleRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.alert_rule} and {@code opd.alert_rule_state} (SAH-18). */
@Repository
public class JdbcAlertRuleRepository implements AlertRuleRepository {

    private static final String COLUMNS = "id, when_step, cond, then_action, enabled";
    private static final String LIST = "select " + COLUMNS
            + " from opd.alert_rule where tenant_id = :tenantId order by created_at, id";
    private static final String FIND = "select " + COLUMNS
            + " from opd.alert_rule where tenant_id = :tenantId and id = :id";
    private static final String SEEDED = """
            select count(*) from opd.alert_rule_state where tenant_id = :tenantId
            """;
    private static final String MARK_SEEDED = """
            insert into opd.alert_rule_state (tenant_id) values (:tenantId)
            on conflict (tenant_id) do nothing
            """;
    // clock_timestamp() keeps the five starter rules in insert order (now() is the same for a whole transaction).
    private static final String INSERT = """
            insert into opd.alert_rule (tenant_id, when_step, cond, then_action, enabled, created_at, created_by)
            values (:tenantId, :whenStep, :cond, :thenAction, :enabled, clock_timestamp(), :actor)
            on conflict (tenant_id, when_step, cond, then_action) do nothing
            returning id
            """;
    private static final String SET_ENABLED = """
            update opd.alert_rule
            set enabled = :enabled, updated_by = :actor, updated_at = now(), version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String DELETE = """
            delete from opd.alert_rule where tenant_id = :tenantId and id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcAlertRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AlertRuleRow> list(UUID tenantId) {
        return jdbc.sql(LIST).param("tenantId", tenantId).query(JdbcAlertRuleRepository::map).list();
    }

    @Override
    public boolean isSeeded(UUID tenantId) {
        Long count = jdbc.sql(SEEDED).param("tenantId", tenantId).query(Long.class).single();
        return count != null && count > 0;
    }

    @Override
    public boolean markSeeded(UUID tenantId) {
        return jdbc.sql(MARK_SEEDED).param("tenantId", tenantId).update() == 1;
    }

    @Override
    public Optional<UUID> insert(UUID tenantId, String when, String cond, String then, boolean enabled,
            UUID actorUserId) {
        return jdbc.sql(INSERT)
                .param("tenantId", tenantId)
                .param("whenStep", when)
                .param("cond", cond)
                .param("thenAction", then)
                .param("enabled", enabled)
                .param("actor", actorUserId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<AlertRuleRow> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id)
                .query(JdbcAlertRuleRepository::map).optional();
    }

    @Override
    public int setEnabled(UUID tenantId, UUID id, boolean enabled, UUID actorUserId) {
        return jdbc.sql(SET_ENABLED).param("tenantId", tenantId).param("id", id)
                .param("enabled", enabled).param("actor", actorUserId).update();
    }

    @Override
    public int delete(UUID tenantId, UUID id) {
        return jdbc.sql(DELETE).param("tenantId", tenantId).param("id", id).update();
    }

    private static AlertRuleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRuleRow(rs.getObject("id", UUID.class), rs.getString("when_step"), rs.getString("cond"),
                rs.getString("then_action"), rs.getBoolean("enabled"));
    }
}
