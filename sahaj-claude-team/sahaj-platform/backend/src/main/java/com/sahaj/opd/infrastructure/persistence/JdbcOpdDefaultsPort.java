package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.platform.api.OpdDefaultsPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code opd.opd_settings}: one row per tenant (SAH-4 technical design). Written in the caller's
 * own transaction ({@link Propagation#MANDATORY}) so it commits or rolls back with the rest of
 * "Create my clinic"; row level security on {@code opd_settings} (owned by the db-engineer's
 * migration) checks {@code tenant_id} again regardless of what the caller passed in.
 */
@Repository
public class JdbcOpdDefaultsPort implements OpdDefaultsPort {

    private static final String UPSERT = """
            insert into opd.opd_settings (tenant_id, fee_timing, queue_on, vitals_on, follow_up_days, follow_up_fee)
            values (:tenantId, :feeTiming, :queueOn, :vitalsOn, :followUpDays, :followUpFee)
            on conflict (tenant_id) do update set
                fee_timing = excluded.fee_timing,
                queue_on = excluded.queue_on,
                vitals_on = excluded.vitals_on,
                follow_up_days = excluded.follow_up_days,
                follow_up_fee = excluded.follow_up_fee
            """;

    private final JdbcClient jdbc;

    public JdbcOpdDefaultsPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveDefaults(UUID tenantId, String feeTiming, boolean queueOn, boolean vitalsOn, int followUpDays,
                              String followUpFee) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("feeTiming", feeTiming)
                .param("queueOn", queueOn)
                .param("vitalsOn", vitalsOn)
                .param("followUpDays", followUpDays)
                .param("followUpFee", followUpFee)
                .update();
    }
}
