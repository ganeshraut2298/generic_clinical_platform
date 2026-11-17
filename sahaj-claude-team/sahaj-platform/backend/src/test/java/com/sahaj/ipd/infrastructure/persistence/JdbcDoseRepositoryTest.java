package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseRepository;
import com.sahaj.ipd.application.DoseRepository.NewDose;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-32 and SAH-76 the statements of the dose repository. The insert is the one statement of docs/database/SAH-76.md:
 * exactly the granted columns, never {@code id}, {@code tenant_id}, {@code recorded_at} or {@code undo_until}. The
 * correction insert names the five granted columns. There is no update and no delete statement. The reads are scoped to the clinic and use the two indexes of the table. The statements are run against
 * the real database by the QA tests.
 */
class JdbcDoseRepositoryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant SLOT = Instant.parse("2026-11-11T02:30:00Z");

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private String sqlSent(Runnable call) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.run();
        } catch (RuntimeException e) {
            // the row mapping is not under test; the mock returns nothing after the statement
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    private JdbcDoseRepository repository() {
        return new JdbcDoseRepository(jdbc);
    }

    private static NewDose newDose(String outcome, String reason, UUID second, String scan, Boolean confirmed) {
        return new NewDose(ID, ID, ID, SLOT, outcome, reason, ID, second, second != null, scan, confirmed);
    }

    @Test
    void theInsertIsTheOneStatementOfTheDatabaseDesign() {
        String sql = sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));

        assertThat(sql.replaceAll("\\s+", " ").trim()).isEqualTo("insert into ipd.dose_administration "
                + "(admission_id, order_id, client_key, slot_at, outcome, reason_key, recorded_by, second_check_by, "
                + "high_alert, identity_scan, identity_confirmed, attempt, supersedes_dose_id, order_version, dose_kind, "
                + "scan_method, override_reason_key, override_note, medicine_scan, before_verification, not_given_note) "
                + "values (:admissionId, :orderId, :clientKey, :slotAt, :outcome, :reasonKey, :recordedBy, "
                + ":secondCheckBy, :highAlert, :identityScan, :identityConfirmed, :attempt, :supersedesDoseId, "
                + ":orderVersion, :doseKind, :scanMethod, :overrideReasonKey, :overrideNote, :medicineScan, "
                + ":beforeVerification, :notGivenNote) returning id, recorded_at, undo_until");
    }

    @Test
    void theInsertNeverNamesTheIdTheClinicOrTheRecordedTime() {
        String sql = sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));
        String columns = sql.substring(0, sql.indexOf("values"));

        assertThat(columns).doesNotContain("tenant_id").doesNotContain(" id,").doesNotContain("recorded_at")
                .doesNotContain("undo_until");
        assertThat(columns.split(",")).hasSize(21);
        assertThat(sql).doesNotContain("update ").doesNotContain("delete ").doesNotContain("on conflict");
    }

    @Test
    void theNullableValuesAreBoundWithTheirTypesSoANullIsNotGuessed() {
        sqlSent(() -> repository().insert(TENANT, newDose("HELD", "NIL_BY_MOUTH", null, null, null)));

        verify(spec).param("reasonKey", "NIL_BY_MOUTH", Types.VARCHAR);
        verify(spec).param("secondCheckBy", null, Types.OTHER);
        verify(spec).param("identityScan", null, Types.VARCHAR);
        verify(spec).param("identityConfirmed", null, Types.BOOLEAN);
        verify(spec).param("outcome", "HELD");
        verify(spec).param("highAlert", false);
        verify(spec).param("supersedesDoseId", null, Types.OTHER);
        verify(spec).param("scanMethod", null, Types.VARCHAR);
        verify(spec).param("overrideReasonKey", null, Types.VARCHAR);
        verify(spec).param("overrideNote", null, Types.VARCHAR);
        verify(spec).param("medicineScan", null, Types.VARCHAR);
        verify(spec).param("notGivenNote", null, Types.VARCHAR);
        verify(spec).param("attempt", 1);
        verify(spec).param("orderVersion", 1);
        verify(spec).param("doseKind", "SCHEDULED");
        verify(spec).param("beforeVerification", false);
    }

    @Test
    void theSlotIsBoundAsAUtcInstant() {
        sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));

        verify(spec).param("slotAt", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
    }

    @Test
    void theCorrectionInsertNamesTheFiveGrantedColumnsAndNeverTheCorrectedTime() {
        String sql = sqlSent(() -> repository().insertCorrection(TENANT, new DoseRepository.NewCorrection(ID, "IN_ERROR",
                "WRONG_TIME", ID, ID)));

        assertThat(sql.replaceAll("\\s+", " ").trim()).isEqualTo("insert into ipd.dose_correction "
                + "(dose_id, kind, reason_key, corrected_by, client_key) "
                + "values (:doseId, :kind, :reasonKey, :correctedBy, :clientKey) returning corrected_at");
        verify(spec).param("reasonKey", "WRONG_TIME", Types.VARCHAR);
    }

    @Test
    void anUndoBindsANullReason() {
        sqlSent(() -> repository().insertCorrection(TENANT, new DoseRepository.NewCorrection(ID, "UNDO", null, ID, ID)));

        verify(spec).param("reasonKey", null, Types.VARCHAR);
    }

    @Test
    void theReadsJoinTheCorrectionOfEachDoseAndNeverTheTypedTexts() {
        for (String sql : new String[] {sqlSent(() -> repository().findByClientKey(TENANT, ID))}) {
            assertThat(sql).contains("left join ipd.dose_correction c on c.tenant_id = d.tenant_id and c.dose_id = d.id")
                    .doesNotContain("override_note").doesNotContain("medicine_scan").doesNotContain("not_given_note")
                    .doesNotContain("identity_scan");
        }
    }

    @Test
    void theSlotProbeTakesTheLatestAttemptOnly() {
        String sql = sqlSent(() -> repository().findByOrderAndSlot(TENANT, ID, SLOT));

        assertThat(sql).contains("order by d.attempt desc").contains("limit 1");
    }

    @Test
    void theLastDoseAndTheLastGivenTimeLeaveOutCorrectedDoses() {
        assertThat(sqlSent(() -> repository().findLastPerOrder(TENANT, ID))).contains("c.id is null");
    }

    @Test
    void theLastGivenAndTheTwentyFourHourReadsCountLiveGivenDosesOnly() {
        String last = sqlSent(() -> repository().findLastGiven(TENANT, ID));

        assertThat(last).contains("d.outcome = 'GIVEN'").contains("not exists (select 1 from ipd.dose_correction c")
                .contains("group by d.order_id");
    }

    @Test
    void theTwentyFourHourReadIsScopedToTheAdmissionAndTheTime() {
        String sql = sqlSent(() -> repository().findGivenSince(TENANT, ID, SLOT));

        assertThat(sql).contains("d.admission_id = :admissionId").contains("d.recorded_at >= :since")
                .contains("not exists (select 1 from ipd.dose_correction c");
        verify(spec).param("since", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
    }

    @Test
    void theMarListsLiveOrdersAndThoseStoppedOrRejectedInTheDay() {
        String sql = sqlSent(() -> repository().findMarHeads(TENANT, ID, SLOT, SLOT.plusSeconds(86_400)));

        assertThat(sql).contains("o.status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD')")
                .contains("o.status = 'STOPPED' and o.stopped_at >= :from and o.stopped_at < :to")
                .contains("o.status = 'REJECTED' and o.rejected_at >= :from and o.rejected_at < :to")
                .contains("order by o.ordered_at, o.id").contains("o.tenant_id = :tenantId");
    }

    @Test
    void theOpenNoticeReadIsOneStatementScopedToTheAdmission() {
        String sql = sqlSent(() -> repository().findOpenNotices(TENANT, ID));

        assertThat(sql).contains("from ipd.order_notice n").contains("n.admission_id = :admissionId")
                .contains("n.acknowledged_at is null");
    }

    @Test
    void theClientKeyLookupIsScopedToTheClinicAndUsesTheUniqueKey() {
        String sql = sqlSent(() -> repository().findByClientKey(TENANT, ID));

        assertThat(sql).contains("from ipd.dose_administration d").contains("d.tenant_id = :tenantId")
                .contains("d.client_key = :clientKey").doesNotContain("identity_scan");
    }

    @Test
    void theSlotProbeUsesTheOrderAndSlotKey() {
        String sql = sqlSent(() -> repository().findByOrderAndSlot(TENANT, ID, SLOT));

        assertThat(sql).contains("d.tenant_id = :tenantId and d.order_id = :orderId and d.slot_at = :slotAt");
    }

    @Test
    void theDayReadIsARangeOnTheSlotTimeOfOneAdmission() {
        String sql = sqlSent(() -> repository().findBetween(TENANT, ID, SLOT, SLOT.plusSeconds(86_400)));

        assertThat(sql).contains("d.tenant_id = :tenantId and d.admission_id = :admissionId")
                .contains("d.slot_at >= :from and d.slot_at < :to").contains("order by d.slot_at, d.attempt, d.id");
        verify(spec).param("from", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
        verify(spec).param("to", OffsetDateTime.ofInstant(SLOT.plusSeconds(86_400), ZoneOffset.UTC));
    }

    @Test
    void theLastDosePerOrderTakesTheLatestSlotOfEachOrderInOneQuery() {
        String sql = sqlSent(() -> repository().findLastPerOrder(TENANT, ID));

        assertThat(sql).contains("select distinct on (d.order_id)").contains("d.tenant_id = :tenantId")
                .contains("d.admission_id = :admissionId").contains("order by d.order_id, d.slot_at desc");
    }

    @Test
    void theScanStopInsertNamesOnlyTheGrantedColumnsAndNoScannedText() {
        String sql = sqlSent(() -> repository().insertScanStop(TENANT, ID, ID, SLOT, DoseRepository.STOP_MEDICINE, ID));

        assertThat(sql.replaceAll("\\s+", " ").trim()).isEqualTo("insert into ipd.dose_scan_stop "
                + "(admission_id, order_id, slot_at, check_kind, actor) "
                + "values (:admissionId, :orderId, :slotAt, :checkKind, :actor)");
        verify(spec).param("checkKind", "MEDICINE");
        verify(spec).param("slotAt", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
    }

    @Test
    void theFifteenMinuteReadUsesTheDatabaseClockAndTheSlotOnlyWhenThereIsOne() {
        String withSlot = sqlSent(() -> repository().hasScanStopWithin15Minutes(TENANT, ID, ID, SLOT));

        assertThat(withSlot).contains("now() - interval '15 minutes'").contains("s.tenant_id = :tenantId")
                .contains("s.actor = :actor").contains("s.slot_at = :slotAt");
        verify(spec).param("slotAt", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
    }

    @Test
    void theFifteenMinuteReadOfAPrnOrderTakesAnySlot() {
        String anySlot = sqlSent(() -> repository().hasScanStopWithin15Minutes(TENANT, ID, ID, null));

        assertThat(anySlot).contains("now() - interval '15 minutes'").contains("s.order_id = :orderId")
                .doesNotContain("slot_at");
    }

    @Test
    void noReadSelectsTheScannedText() {
        String sql = sqlSent(() -> repository().findLastPerOrder(TENANT, ID));

        assertThat(sql).doesNotContain("identity_scan").doesNotContain("identity_confirmed");
        verify(spec).param("tenantId", TENANT);
    }
}
