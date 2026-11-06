package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.DischargeRepository;
import com.sahaj.ipd.application.RunningBill.BillLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Discharge sign-offs, discharges and final bills (SAH-27). Insert statements list only the columns the database
 * role may write; ids and times come from the database defaults.
 */
@Repository
public class JdbcDischargeRepository implements DischargeRepository {

    private static final String INSERT_SIGNOFF = """
            insert into ipd.discharge_signoff (admission_id, discharge_type, template_key, signed_by)
            values (:admissionId, :type, :template, :by)
            returning id, signed_at
            """;
    private static final String FIND_LATEST_SIGNOFF = """
            select s.id, s.admission_id, s.discharge_type, s.template_key, s.signed_by, s.signed_at
            from ipd.discharge_signoff s
            where s.tenant_id = :tenantId and s.admission_id = :admissionId
            order by s.signed_at desc, s.id
            limit 1
            """;
    private static final String FIND_LATEST_OF_ADMITTED = """
            select distinct on (s.admission_id)
                   s.id, s.admission_id, s.discharge_type, s.template_key, s.signed_by, s.signed_at
            from ipd.discharge_signoff s
            join ipd.admission a on a.tenant_id = s.tenant_id and a.id = s.admission_id
            where s.tenant_id = :tenantId and a.status = 'ADMITTED'
            order by s.admission_id, s.signed_at desc, s.id
            """;
    private static final String COUNT_DISCHARGED = """
            select count(*) from ipd.admission
            where tenant_id = :tenantId and status = 'DISCHARGED' and discharged_at >= :from and discharged_at < :to
            """;
    private static final String INSERT_FINAL_BILL = """
            insert into ipd.final_bill (admission_id, total_paise, deposits_paise, balance_paise, settle_mode,
                                        created_by, bill_days, bed_day_cut, category_key, multiplier)
            values (:admissionId, :total, :deposits, :balance, :mode, :by, :billDays, :cut, :category, :multiplier)
            returning id
            """;
    private static final String INSERT_LINE = """
            insert into ipd.final_bill_line (final_bill_id, line_no, label, qty, unit_paise, amount_paise, segment)
            values (:billId, :lineNo, :label, :qty, :unit, :amount, :segment)
            """;
    private static final String INSERT_DISCHARGE = """
            insert into ipd.discharge (admission_id, signoff_id, discharge_type, template_key, signed_by, signed_at,
                                       followup_days, followup_due, completed_by)
            values (:admissionId, :signoffId, :type, :template, :signedBy, :signedAt, :days, :due, :by)
            """;
    private static final String FIND_DISCHARGE = """
            select id, admission_id, signoff_id, discharge_type, template_key, signed_by, signed_at, followup_days,
                   followup_due, completed_by, completed_at
            from ipd.discharge
            where tenant_id = :tenantId and admission_id = :admissionId
            """;
    private static final String FIND_FINAL_BILL = """
            select id, admission_id, total_paise, deposits_paise, balance_paise, settle_mode, created_by, created_at,
                   bill_days, bed_day_cut, category_key, multiplier
            from ipd.final_bill
            where tenant_id = :tenantId and admission_id = :admissionId
            """;
    private static final String FIND_LINES = """
            select label, unit_paise, qty, amount_paise, segment
            from ipd.final_bill_line
            where tenant_id = :tenantId and final_bill_id = :billId
            order by line_no
            """;

    private final JdbcClient jdbc;

    public JdbcDischargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SignoffRow insertSignoff(UUID tenantId, UUID admissionId, String type, String templateKey, UUID signedBy) {
        return jdbc.sql(INSERT_SIGNOFF).param("admissionId", admissionId).param("type", type)
                .param("template", templateKey).param("by", signedBy)
                .query((rs, rowNum) -> new SignoffRow(rs.getObject("id", UUID.class), admissionId, type, templateKey,
                        signedBy, rs.getObject("signed_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    @Override
    public Optional<SignoffRow> findLatestSignoff(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_LATEST_SIGNOFF).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcDischargeRepository::signoff).optional();
    }

    @Override
    public Map<UUID, SignoffRow> findLatestSignoffsOfAdmitted(UUID tenantId) {
        Map<UUID, SignoffRow> result = new HashMap<>();
        for (SignoffRow row : jdbc.sql(FIND_LATEST_OF_ADMITTED).param("tenantId", tenantId)
                .query(JdbcDischargeRepository::signoff).list()) {
            result.put(row.admissionId(), row);
        }
        return result;
    }

    @Override
    public int countDischargedBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(COUNT_DISCHARGED).param("tenantId", tenantId).param("from", from).param("to", to)
                .query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public UUID insertFinalBill(UUID tenantId, NewFinalBill b) {
        return jdbc.sql(INSERT_FINAL_BILL).param("admissionId", b.admissionId()).param("total", b.totalPaise())
                .param("deposits", b.depositsPaise()).param("balance", b.balancePaise())
                .param("mode", b.settleMode(), Types.VARCHAR).param("by", b.createdBy())
                .param("billDays", b.billDays()).param("cut", b.bedDayCut()).param("category", b.categoryKey())
                .param("multiplier", b.multiplier())
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public void insertFinalBillLines(UUID tenantId, UUID finalBillId, List<BillLine> lines) {
        int lineNo = 1;
        for (BillLine line : lines) {
            jdbc.sql(INSERT_LINE).param("billId", finalBillId).param("lineNo", lineNo++).param("label", line.name())
                    .param("qty", line.qty()).param("unit", line.unitPaise()).param("amount", line.amountPaise())
                    .param("segment", line.segment()).update();
        }
    }

    @Override
    public void insertDischarge(UUID tenantId, NewDischarge d) {
        jdbc.sql(INSERT_DISCHARGE).param("admissionId", d.admissionId()).param("signoffId", d.signoffId())
                .param("type", d.type()).param("template", d.templateKey()).param("signedBy", d.signedBy())
                .param("signedAt", d.signedAt()).param("days", d.followUpDays())
                .param("due", d.followUpDue(), Types.DATE).param("by", d.completedBy()).update();
    }

    @Override
    public Optional<DischargeRow> findDischarge(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_DISCHARGE).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcDischargeRepository::discharge).optional();
    }

    @Override
    public Optional<FinalBillRow> findFinalBill(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_FINAL_BILL).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcDischargeRepository::finalBill).optional();
    }

    @Override
    public List<BillLine> findFinalBillLines(UUID tenantId, UUID finalBillId) {
        return jdbc.sql(FIND_LINES).param("tenantId", tenantId).param("billId", finalBillId)
                .query((rs, rowNum) -> new BillLine(rs.getString("label"), rs.getLong("unit_paise"),
                        rs.getInt("qty"), rs.getLong("amount_paise"), rs.getBoolean("segment")))
                .list();
    }

    private static SignoffRow signoff(ResultSet rs, int rowNum) throws SQLException {
        return new SignoffRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getString("discharge_type"), rs.getString("template_key"), rs.getObject("signed_by", UUID.class),
                rs.getObject("signed_at", OffsetDateTime.class).toInstant());
    }

    private static DischargeRow discharge(ResultSet rs, int rowNum) throws SQLException {
        return new DischargeRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("signoff_id", UUID.class), rs.getString("discharge_type"), rs.getString("template_key"),
                rs.getObject("signed_by", UUID.class), rs.getObject("signed_at", OffsetDateTime.class).toInstant(),
                rs.getInt("followup_days"), rs.getObject("followup_due", LocalDate.class),
                rs.getObject("completed_by", UUID.class),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant());
    }

    private static FinalBillRow finalBill(ResultSet rs, int rowNum) throws SQLException {
        return new FinalBillRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getLong("total_paise"), rs.getLong("deposits_paise"), rs.getLong("balance_paise"),
                rs.getString("settle_mode"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("bill_days"),
                rs.getString("bed_day_cut"), rs.getString("category_key"), rs.getBigDecimal("multiplier"));
    }
}
