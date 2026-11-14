package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.RoundNoteRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ipd.round_note} (SAH-67-1). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcRoundNoteRepository implements RoundNoteRepository {

    private static final String INSERT = """
            insert into ipd.round_note (admission_id, round_date, emr_record_id, signed_by_user_id,
                                        signed_by_membership_id, signed_at, orders_placed, investigations_advised)
            values (:admissionId, :roundDate, :recordId, :userId, :membershipId, :signedAt, :orders,
                    :investigations)
            on conflict (tenant_id, admission_id, round_date) do nothing
            """;
    private static final String LATEST = """
            select admission_id, round_date, signed_at, signed_by_membership_id, investigations_advised
            from ipd.round_note where tenant_id = :tenantId and admission_id = :admissionId
            order by round_date desc, signed_at desc limit 1
            """;
    private static final String DONE_ON = """
            select admission_id from ipd.round_note
            where tenant_id = :tenantId and round_date = :day and admission_id in (:ids)
            """;

    private final JdbcClient jdbc;

    public JdbcRoundNoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(UUID tenantId, NewRound r) {
        return jdbc.sql(INSERT).param("admissionId", r.admissionId())
                .param("roundDate", Date.valueOf(r.roundDate())).param("recordId", r.emrRecordId())
                .param("userId", r.signedByUserId()).param("membershipId", r.signedByMembershipId())
                .param("signedAt", Timestamp.from(r.signedAt())).param("orders", r.ordersPlaced())
                .param("investigations", r.investigationsAdvised()).update() == 1;
    }

    @Override
    public Optional<RoundRow> findLatest(UUID tenantId, UUID admissionId) {
        return jdbc.sql(LATEST).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, row) -> new RoundRow(rs.getObject("admission_id", UUID.class),
                        rs.getDate("round_date").toLocalDate(), rs.getTimestamp("signed_at").toInstant(),
                        rs.getObject("signed_by_membership_id", UUID.class), rs.getString("investigations_advised")))
                .optional();
    }

    @Override
    public Set<UUID> doneOn(UUID tenantId, java.time.LocalDate day, Collection<UUID> admissionIds) {
        if (admissionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql(DONE_ON).param("tenantId", tenantId).param("day", Date.valueOf(day))
                .param("ids", admissionIds).query((rs, row) -> rs.getObject("admission_id", UUID.class)).list());
    }
}
