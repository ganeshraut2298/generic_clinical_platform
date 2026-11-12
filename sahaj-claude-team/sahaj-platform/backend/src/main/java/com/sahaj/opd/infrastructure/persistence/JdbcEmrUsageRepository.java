package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.EmrUsageRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code opd.emr_item_usage} (SAH-43-2, ADR-019 D10): one upsert statement per item. The insert only happens
 * when the item exists in its master, so an item that is not in its master (or a key of the wrong shape) is skipped
 * without touching the table's shape checks. The row lock of the upsert makes two visits signed at the same moment
 * both count. The table holds no patient id, visit id or text.
 */
@Repository
public class JdbcEmrUsageRepository implements EmrUsageRepository {

    private static final String UPSERT_TAIL = """
            on conflict (tenant_id, doctor_membership_id, master, item_key)
            do update set use_count = opd.emr_item_usage.use_count + 1,
                          last_used_at = greatest(opd.emr_item_usage.last_used_at, excluded.last_used_at),
                          updated_at = now()
            """;

    private final JdbcClient jdbc;

    public JdbcEmrUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int recordUse(UUID tenantId, UUID doctorMembershipId, EmrMaster master, String itemKey, Instant usedAt) {
        return jdbc.sql(upsert(master))
                .param("tenantId", tenantId).param("doctor", doctorMembershipId)
                .param("masterKey", master.key()).param("itemKey", itemKey).param("usedAt", usedAt)
                .update();
    }

    private static String upsert(EmrMaster master) {
        return """
                insert into opd.emr_item_usage (tenant_id, doctor_membership_id, master, item_key, last_used_at)
                select :tenantId, :doctor, :masterKey, :itemKey, :usedAt
                where exists (select 1 from %s)
                """.formatted(existsSource(master)) + UPSERT_TAIL;
    }

    /** The master table and the condition that names one item; fixed text, the key is always a bound parameter. */
    private static String existsSource(EmrMaster master) {
        switch (master) {
            case COMPLAINTS:
                return "opd.emr_complaint m where m.id::text = :itemKey";
            case DIAGNOSES:
                return "opd.diagnosis m where m.code = :itemKey";
            case MEDICINES:
                return "opd.medicine m where m.id::text = :itemKey";
            case INVESTIGATIONS:
                return "opd.emr_investigation m where m.id::text = :itemKey";
            case ADVICE:
                return "opd.emr_advice m where m.id::text = :itemKey";
            default:
                throw new IllegalArgumentException("Unknown master");
        }
    }
}
