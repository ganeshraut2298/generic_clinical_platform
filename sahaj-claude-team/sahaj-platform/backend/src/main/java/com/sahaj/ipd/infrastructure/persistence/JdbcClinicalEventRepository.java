package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.ClinicalEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The clinical event log (SAH-30, SAH-33). The insert is the only write; the database sets the id, the order number,
 * the clinic and the time (the transaction time, so every event of one save shares one instant). The page query is
 * keyset based on {@code (at, seq)} and uses {@code ix_clinical_event_tenant_at}; the patient id and the bed come from
 * one left join, so a page costs one query. The statements are built from fixed fragments only, never from input.
 */
@Repository
public class JdbcClinicalEventRepository implements ClinicalEventRepository {

    private static final String INSERT = """
            insert into ipd.clinical_event (admission_id, kind, text, actor)
            values (:admissionId, :kind, :text, :actor)
            """;
    private static final String PAGE_HEAD = """
            select e.id, e.seq, e.kind, e.text, e.at, e.actor, e.admission_id, a.patient_id, b.code as bed_code
            from ipd.clinical_event e
            left join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id
            left join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            where e.tenant_id = :tenantId
            """;
    private static final String NURSE_SCOPE = " and (e.actor = :nurse or a.status = 'ADMITTED')\n";
    private static final String AFTER = " and (e.at, e.seq) < (:afterAt, :afterSeq)\n";
    private static final String PAGE_TAIL = " order by e.at desc, e.seq desc limit :limit";

    private final JdbcClient jdbc;

    public JdbcClinicalEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID tenantId, UUID admissionId, String kind, String text, UUID actor) {
        jdbc.sql(INSERT).param("admissionId", admissionId).param("kind", kind).param("text", text)
                .param("actor", actor).update();
    }

    @Override
    public List<EventRow> findPage(UUID tenantId, UUID onlyForNurse, Position after, int limit) {
        String sql = PAGE_HEAD + (onlyForNurse == null ? "" : NURSE_SCOPE) + (after == null ? "" : AFTER)
                + PAGE_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (onlyForNurse != null) {
            spec = spec.param("nurse", onlyForNurse);
        }
        if (after != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("afterSeq", after.seq());
        }
        return spec.query((rs, rowNum) -> new EventRow(rs.getObject("id", UUID.class), rs.getLong("seq"),
                rs.getString("kind"), rs.getString("text"), rs.getObject("at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("bed_code"))).list();
    }
}
