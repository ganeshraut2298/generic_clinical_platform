package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.SaleRegisterRepository;
import java.sql.Date;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over the H1 and Schedule X register rows and the credit-note tables (SAH-80). Append-only: the application role can only
 * select and insert. The cancel copies each ENTRY row into a REVERSAL row inside the database, so a name never passes through the
 * application on a reversal.
 */
@Repository("pharmacyJdbcSaleRegisterRepository")
public class JdbcSaleRegisterRepository implements SaleRegisterRepository {

    private static final String INSERT_H1 = """
            insert into pharmacy.h1_register_row (tenant_id, unit_id, store_id, kind, bill_id, bill_line_id, bill_no, patient_id,
                   patient_name, patient_age, patient_address, prescriber_name, prescriber_reg_no, prescription_date, rx_source,
                   rx_record_id, product_id, prescribed_product_id, batch_id, qty, pharmacist_membership_id, override_reason_key)
            values (:tenantId, :unitId, :storeId, 'ENTRY', :billId, :billLineId, :billNo, :patientId, :patientName, :patientAge,
                   :patientAddress, :prescriberName, :prescriberRegNo, :prescriptionDate, :rxSource, :rxRecordId, :productId,
                   :prescribedProductId, :batchId, :qty, :pharmacist, :override)
            """;
    private static final String INSERT_X = """
            insert into pharmacy.schedule_x_register_row (tenant_id, unit_id, store_id, kind, source_type, source_id, source_no,
                   source_line_id, patient_id, patient_name, patient_age, prescriber_name, prescriber_reg_no, prescription_date,
                   product_id, batch_id, qty, first_signer_membership_id, second_signer_membership_id)
            values (:tenantId, :unitId, :storeId, 'ENTRY', 'BILL', :billId, :billNo, :billLineId, :patientId, :patientName,
                   :patientAge, :prescriberName, :prescriberRegNo, :prescriptionDate, :productId, :batchId, :qty, :first, :second)
            """;
    private static final String REVERSE_H1 = """
            insert into pharmacy.h1_register_row (tenant_id, unit_id, store_id, kind, reverses_row_id, reversal_reason, bill_id,
                   bill_line_id, bill_no, patient_id, patient_name, patient_age, patient_address, prescriber_name,
                   prescriber_reg_no, prescription_date, rx_source, rx_record_id, product_id, prescribed_product_id, batch_id,
                   qty, pharmacist_membership_id, override_reason_key)
            select tenant_id, unit_id, store_id, 'REVERSAL', id, 'CANCELLED', bill_id, bill_line_id, bill_no, patient_id,
                   patient_name, patient_age, patient_address, prescriber_name, prescriber_reg_no, prescription_date, rx_source,
                   rx_record_id, product_id, prescribed_product_id, batch_id, -qty, pharmacist_membership_id, override_reason_key
              from pharmacy.h1_register_row r
             where r.tenant_id = :tenantId and r.bill_id = :billId and r.kind = 'ENTRY'
               and not exists (select 1 from pharmacy.h1_register_row x
                                where x.tenant_id = r.tenant_id and x.reverses_row_id = r.id)
            """;
    private static final String REVERSE_X = """
            insert into pharmacy.schedule_x_register_row (tenant_id, unit_id, store_id, kind, reverses_row_id, reversal_reason,
                   source_type, source_id, source_no, source_line_id, patient_id, patient_name, patient_age, prescriber_name,
                   prescriber_reg_no, prescription_date, product_id, batch_id, qty, first_signer_membership_id,
                   second_signer_membership_id)
            select tenant_id, unit_id, store_id, 'REVERSAL', id, 'CANCELLED', source_type, source_id, source_no, source_line_id,
                   patient_id, patient_name, patient_age, prescriber_name, prescriber_reg_no, prescription_date, product_id,
                   batch_id, -qty, first_signer_membership_id, second_signer_membership_id
              from pharmacy.schedule_x_register_row r
             where r.tenant_id = :tenantId and r.source_type = 'BILL' and r.source_id = :billId and r.kind = 'ENTRY'
               and not exists (select 1 from pharmacy.schedule_x_register_row x
                                where x.tenant_id = r.tenant_id and x.reverses_row_id = r.id)
            """;
    private static final String INSERT_CREDIT_NOTE = """
            insert into pharmacy.credit_note (id, tenant_id, unit_id, store_id, credit_note_no, fiscal_year, source_kind,
                   bill_id, sales_return_id, amount_paise, issued_by)
            values (:id, :tenantId, :unitId, :storeId, :no, :fiscalYear, :sourceKind, :billId, :returnId, :amount, :by)
            """;
    private static final String INSERT_REFUND = """
            insert into pharmacy.refund (tenant_id, unit_id, store_id, source_kind, bill_id, sales_return_id, mode, amount_paise,
                   credit_note_no, actor_membership_id)
            values (:tenantId, :unitId, :storeId, :sourceKind, :billId, :returnId, :mode, :amount, :no, :by)
            """;

    private final JdbcClient jdbc;

    public JdbcSaleRegisterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertH1(UUID tenantId, H1Entry e) {
        jdbc.sql(INSERT_H1).param("tenantId", tenantId).param("unitId", e.unitId()).param("storeId", e.storeId())
                .param("billId", e.billId()).param("billLineId", e.billLineId()).param("billNo", e.billNo())
                .param("patientId", e.patientId()).param("patientName", e.patientName()).param("patientAge", e.patientAge())
                .param("patientAddress", e.patientAddress()).param("prescriberName", e.prescriberName())
                .param("prescriberRegNo", e.prescriberRegNo())
                .param("prescriptionDate", e.prescriptionDate() == null ? null : Date.valueOf(e.prescriptionDate()))
                .param("rxSource", e.rxSource()).param("rxRecordId", e.rxRecordId()).param("productId", e.productId())
                .param("prescribedProductId", e.prescribedProductId()).param("batchId", e.batchId()).param("qty", e.qty())
                .param("pharmacist", e.pharmacistMembershipId()).param("override", e.overrideReasonKey()).update();
    }

    @Override
    public void insertScheduleX(UUID tenantId, ScheduleXEntry e) {
        jdbc.sql(INSERT_X).param("tenantId", tenantId).param("unitId", e.unitId()).param("storeId", e.storeId())
                .param("billId", e.billId()).param("billNo", e.billNo()).param("billLineId", e.billLineId())
                .param("patientId", e.patientId()).param("patientName", e.patientName()).param("patientAge", e.patientAge())
                .param("prescriberName", e.prescriberName()).param("prescriberRegNo", e.prescriberRegNo())
                .param("prescriptionDate", Date.valueOf(e.prescriptionDate())).param("productId", e.productId())
                .param("batchId", e.batchId()).param("qty", e.qty()).param("first", e.firstSignerMembershipId())
                .param("second", e.secondSignerMembershipId()).update();
    }

    @Override
    public int reverseH1ForCancelledBill(UUID tenantId, UUID billId) {
        return jdbc.sql(REVERSE_H1).param("tenantId", tenantId).param("billId", billId).update();
    }

    @Override
    public int reverseScheduleXForCancelledBill(UUID tenantId, UUID billId) {
        return jdbc.sql(REVERSE_X).param("tenantId", tenantId).param("billId", billId).update();
    }

    @Override
    public int reverseH1ForReturn(UUID tenantId, UUID billLineId, int qty, String creditNoteNo) {
        return jdbc.sql("""
                insert into pharmacy.h1_register_row (tenant_id, unit_id, store_id, kind, reverses_row_id, reversal_reason,
                       reversal_ref, bill_id, bill_line_id, bill_no, patient_id, patient_name, patient_age, patient_address,
                       prescriber_name, prescriber_reg_no, prescription_date, rx_source, rx_record_id, product_id,
                       prescribed_product_id, batch_id, qty, pharmacist_membership_id, override_reason_key)
                select tenant_id, unit_id, store_id, 'REVERSAL', id, 'RETURNED', :no, bill_id, bill_line_id, bill_no, patient_id,
                       patient_name, patient_age, patient_address, prescriber_name, prescriber_reg_no, prescription_date,
                       rx_source, rx_record_id, product_id, prescribed_product_id, batch_id, -:qty, pharmacist_membership_id,
                       override_reason_key
                  from pharmacy.h1_register_row r
                 where r.tenant_id = :t and r.bill_line_id = :line and r.kind = 'ENTRY'
                """).param("no", creditNoteNo).param("qty", qty).param("t", tenantId).param("line", billLineId).update();
    }

    @Override
    public int reverseScheduleXForReturn(UUID tenantId, UUID billLineId, int qty, UUID returnId, String creditNoteNo,
                                         UUID firstSignerMembershipId, UUID secondSignerMembershipId) {
        return jdbc.sql("""
                insert into pharmacy.schedule_x_register_row (tenant_id, unit_id, store_id, kind, reverses_row_id, reversal_reason,
                       source_type, source_id, source_no, source_line_id, patient_id, patient_name, patient_age, prescriber_name,
                       prescriber_reg_no, prescription_date, product_id, batch_id, qty, first_signer_membership_id,
                       second_signer_membership_id)
                select tenant_id, unit_id, store_id, 'REVERSAL', id, 'RETURNED', 'SALES_RETURN', :ret, :no, source_line_id,
                       patient_id, patient_name, patient_age, prescriber_name, prescriber_reg_no, prescription_date, product_id,
                       batch_id, -:qty, :first, :second
                  from pharmacy.schedule_x_register_row r
                 where r.tenant_id = :t and r.source_type = 'BILL' and r.source_line_id = :line and r.kind = 'ENTRY'
                """).param("ret", returnId).param("no", creditNoteNo).param("qty", qty).param("first", firstSignerMembershipId)
                .param("second", secondSignerMembershipId).param("t", tenantId).param("line", billLineId).update();
    }

    @Override
    public void insertCreditNote(UUID tenantId, NewCreditNote n, UUID actorMembershipId) {
        jdbc.sql(INSERT_CREDIT_NOTE).param("id", n.id()).param("tenantId", tenantId).param("unitId", n.unitId())
                .param("storeId", n.storeId()).param("no", n.creditNoteNo()).param("fiscalYear", n.fiscalYear())
                .param("sourceKind", n.sourceKind()).param("billId", n.billId()).param("returnId", n.salesReturnId())
                .param("amount", n.amountPaise()).param("by", actorMembershipId).update();
    }

    @Override
    public void insertRefund(UUID tenantId, NewRefund r, UUID actorMembershipId) {
        jdbc.sql(INSERT_REFUND).param("tenantId", tenantId).param("unitId", r.unitId()).param("storeId", r.storeId())
                .param("sourceKind", r.sourceKind()).param("billId", r.billId()).param("returnId", r.salesReturnId())
                .param("mode", r.mode()).param("amount", r.amountPaise()).param("no", r.creditNoteNo())
                .param("by", actorMembershipId).update();
    }
}
