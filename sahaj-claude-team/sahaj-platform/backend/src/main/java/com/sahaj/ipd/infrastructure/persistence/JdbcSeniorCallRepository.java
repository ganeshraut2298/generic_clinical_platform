package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.SeniorCallRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.ews_senior_call} (SAH-33, ADR-007 R4). The insert lists exactly the four columns the database role may
 * write; {@code id}, {@code tenant_id} and {@code called_at} always take their defaults, so the clock is the
 * database's and cannot be back-dated. The reads use {@code clock_timestamp()} and the index on
 * (tenant, escalation, user, time).
 */
@Repository
public class JdbcSeniorCallRepository implements SeniorCallRepository {

    private static final String HAS_CALLED_ABOVE = """
            select exists (
              select 1 from ipd.ews_senior_call
              where tenant_id = :tenantId and escalation_id = :escalationId and called_by = :user
                and outcome = 'CALLED' and call_no > :sent)
            """;
    private static final String HAS_ENTERED_WITHIN = """
            select exists (
              select 1 from ipd.ews_senior_call
              where tenant_id = :tenantId and escalation_id = :escalationId and called_by = :user
                and called_at > clock_timestamp() - make_interval(secs => :seconds))
            """;
    private static final String INSERT_CALLED = """
            insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no)
            values (:escalationId, :user, 'CALLED', :callNo)
            """;
    private static final String INSERT_ALREADY_CALLED = """
            insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no)
            values (:escalationId, :user, 'ALREADY_CALLED', null)
            """;

    private final JdbcClient jdbc;

    public JdbcSeniorCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasCalledAbove(UUID tenantId, UUID escalationId, UUID user, int sentCalls) {
        return jdbc.sql(HAS_CALLED_ABOVE).param("tenantId", tenantId).param("escalationId", escalationId)
                .param("user", user).param("sent", sentCalls).query(Boolean.class).single();
    }

    @Override
    public boolean hasEnteredWithin(UUID tenantId, UUID escalationId, UUID user, int seconds) {
        return jdbc.sql(HAS_ENTERED_WITHIN).param("tenantId", tenantId).param("escalationId", escalationId)
                .param("user", user).param("seconds", (double) seconds).query(Boolean.class).single();
    }

    @Override
    public void insertCalled(UUID tenantId, UUID escalationId, UUID user, int callNo) {
        jdbc.sql(INSERT_CALLED).param("escalationId", escalationId).param("user", user).param("callNo", callNo)
                .update();
    }

    @Override
    public void insertAlreadyCalled(UUID tenantId, UUID escalationId, UUID user) {
        jdbc.sql(INSERT_ALREADY_CALLED).param("escalationId", escalationId).param("user", user).update();
    }
}
