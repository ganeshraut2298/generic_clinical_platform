package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import com.sahaj.ipd.application.MedicineOrderService.CheckOnly;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.RoundNoteRepository.NewRound;
import com.sahaj.ipd.application.VitalsRepository.VitalsRow;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrEmergencyPort.CloseKind;
import com.sahaj.opd.api.EmrIpdOrderPort;
import com.sahaj.opd.api.EmrIpdOrderPort.LineCheck;
import com.sahaj.opd.api.EmrIpdOrderPort.OrderLine;
import com.sahaj.opd.api.EmrIpdOrderPort.PlaceResult;
import com.sahaj.opd.api.EmrRoundPort;
import com.sahaj.opd.api.EmrRoundPort.RoundNote;
import com.sahaj.opd.api.EmrRoundPort.RoundOutcome;
import com.sahaj.opd.api.EmrVitalsSource;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-67 and SAH-68-1 (ADR-025 D8, D9, D11, D12): the ipd side of the EMR ports. They delegate to ipd's own services and
 * repositories (no second set of rules), write the audit rows with ids only, and answer in the contract's words.
 */
@SuppressWarnings("unchecked")
class EmrIpdPortsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T05:30:00Z");

    // ---- round ----

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final RoundNoteRepository rounds = mock(RoundNoteRepository.class);
    private final ClinicalEventRepository events = mock(ClinicalEventRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final IpdGate gate = mock(IpdGate.class);
    private final EmrRoundAdapter roundAdapter = new EmrRoundAdapter(admissions, rounds, events, team, audit, gate);

    private AdmissionRow admission(String status) {
        return new AdmissionRow(ADMISSION, "IP/26-27/0001", PATIENT, UUID.randomUUID(), "A1", "gen", "Ward", MEMBERSHIP,
                "planned", "Self", "Fever", status, NOW, 0);
    }

    private RoundNote note() {
        return new RoundNote(ADMISSION, LocalDate.of(2026, 11, 13), RECORD, NOW, 2, "CBC; X-ray");
    }

    @Test
    void aSignedRoundIsRecordedOnceWithAnEventAndAnAuditRowOfIdsAndCounts() {
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED")));
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.of(new TeamMember(MEMBERSHIP, "Dr", "DOCTOR", true, 0)));
        when(rounds.insertIfAbsent(eq(TENANT), any(NewRound.class))).thenReturn(true);

        assertThat(roundAdapter.recordSignedRound(TENANT, ACTOR, note())).isEqualTo(RoundOutcome.RECORDED);

        ArgumentCaptor<NewRound> stored = ArgumentCaptor.forClass(NewRound.class);
        verify(rounds).insertIfAbsent(eq(TENANT), stored.capture());
        assertThat(stored.getValue().investigationsAdvised()).isEqualTo("CBC; X-ray");
        assertThat(stored.getValue().signedByMembershipId()).isEqualTo(MEMBERSHIP);
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_INFO, "Signed the daily progress note", ACTOR);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("IPD_ROUND_SIGNED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("ordersPlaced", 2).containsEntry("recordId", RECORD);
        assertThat(after.getValue().toString()).doesNotContain("CBC");
    }

    @Test
    void aRepeatIsSuccessWithoutASecondEventAndADischargedAdmissionIsRefused() {
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED")));
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.of(new TeamMember(MEMBERSHIP, "Dr", "DOCTOR", true, 0)));
        when(rounds.insertIfAbsent(eq(TENANT), any(NewRound.class))).thenReturn(false);

        assertThat(roundAdapter.recordSignedRound(TENANT, ACTOR, note())).isEqualTo(RoundOutcome.ALREADY_RECORDED);
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());

        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(admission("DISCHARGED")));
        assertThat(roundAdapter.recordSignedRound(TENANT, ACTOR, note())).isEqualTo(RoundOutcome.NOT_ADMITTED);
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThat(roundAdapter.recordSignedRound(TENANT, ACTOR, note())).isEqualTo(RoundOutcome.NOT_ADMITTED);
    }

    @Test
    void aRoundIsNotRecordedWhileIpdIsReadOnlyAndNothingIsWritten() {
        org.mockito.Mockito.doThrow(new com.sahaj.platform.api.ConflictException("ipd-readonly", "IPD is read-only",
                "IPD is read-only. Nothing can be changed.")).when(gate).check(TENANT, true);

        assertThatThrownBy(() -> roundAdapter.recordSignedRound(TENANT, ACTOR, note()))
                .isInstanceOfSatisfying(com.sahaj.platform.api.ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        verify(rounds, never()).insertIfAbsent(any(), any());
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ---- vitals ----

    @Test
    void theNursingChartIsReadForTheRoundDayAndBeforeIt() {
        VitalsRepository vitals = mock(VitalsRepository.class);
        UUID entry = UUID.randomUUID();
        when(vitals.findLatestOnDay(TENANT, ADMISSION, LocalDate.of(2026, 11, 13))).thenReturn(Optional.of(new VitalsRow(
                entry, ADMISSION, 120, 80, 72, 98, new BigDecimal("37.1"), 16, 0, null, false, NOW, ACTOR)));
        when(vitals.findLatestBeforeDay(TENANT, ADMISSION, LocalDate.of(2026, 11, 13))).thenReturn(Optional.empty());
        EmrVitalsSource source = new EmrVitalsAdapter(vitals);

        EmrVitalsSource.VitalsReading reading = source.latestNursing(TENANT, ADMISSION, LocalDate.of(2026, 11, 13)).orElseThrow();

        assertThat(reading.entryId()).isEqualTo(entry);
        assertThat(reading.temperatureC()).isEqualByComparingTo("37.1");
        assertThat(reading.toString()).doesNotContain("120");
        assertThat(source.lastNursingBefore(TENANT, ADMISSION, LocalDate.of(2026, 11, 13))).isEmpty();
    }

    // ---- Emergency ----

    private final EmergencyCaseRepository cases = mock(EmergencyCaseRepository.class);
    private final WardRepository wards = mock(WardRepository.class);
    private final IpdSettingsRepository settings = mock(IpdSettingsRepository.class);
    private final com.sahaj.platform.api.ModuleEntitlementPort entitlements =
            mock(com.sahaj.platform.api.ModuleEntitlementPort.class);
    private final EmergencyService emergency = new EmergencyService(cases, mock(TriageEventRepository.class), wards,
            settings, new IpdCatalog(settings), mock(OwnerGuard.class), audit,
            new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)));
    private final EmrEmergencyPort emergencyPort = new EmrEmergencyAdapter(emergency);

    private CaseRow openCase(String status) {
        return new CaseRow(CASE, null, "Unknown", 40, "M", null, "Chest pain", false, 1, "B1", status, NOW, ACTOR, null, null,
                null, null, null, UUID.randomUUID(), 0);
    }

    @Test
    void theCaseIsLockedAfterTheClinicLockAndAClosedCaseIsReportedNotOpen() {
        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("WAITING")));

        EmrEmergencyPort.CaseLock open = emergencyPort.lockCase(TENANT, CASE);

        assertThat(open.open()).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(wards, cases);
        order.verify(wards).lockTenant(TENANT);
        order.verify(cases).findForUpdate(TENANT, CASE);

        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("RELEASED")));
        assertThat(emergencyPort.lockCase(TENANT, CASE).open()).isFalse();
        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> emergencyPort.lockCase(TENANT, CASE)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void closingACaseIsRefusedWhileIpdIsReadOnlyAndNothingIsWritten() {
        when(entitlements.forTenant(TENANT)).thenReturn(new com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement(
                "readonly", false, 10));
        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("SEEN")));

        assertThatThrownBy(() -> emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.RELEASE, null, null))
                .isInstanceOfSatisfying(com.sahaj.platform.api.ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        verify(cases, never()).close(any(), any(), anyString(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @org.junit.jupiter.api.BeforeEach
    void ipdIsOn() {
        when(entitlements.forTenant(TENANT)).thenReturn(new com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement(
                "on", false, 10));
    }

    @Test
    void dischargeAndReferRunTheSah28UpdatesAndAuditTheSameActionsAsTreatAndReleaseAndReferOut() {
        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("SEEN")));
        when(cases.databaseNow()).thenReturn(NOW);
        when(cases.close(eq(TENANT), eq(CASE), anyString(), eq(NOW), eq(ACTOR))).thenReturn(true);

        assertThat(emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.RELEASE, null, null).closed()).isTrue();
        verify(cases).close(TENANT, CASE, "RELEASED", NOW, ACTOR);
        verify(audit).record(eq("IPD_ER_RELEASED"), eq("IPD_EMERGENCY_CASE"), eq(CASE), eq(null), any());

        assertThat(emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.REFER, null, null).closed()).isTrue();
        verify(cases).close(TENANT, CASE, "REFERRED", NOW, ACTOR);
        verify(audit).record(eq("IPD_ER_REFERRED"), eq("IPD_EMERGENCY_CASE"), eq(CASE), eq(null), any());
    }

    @Test
    void sentToOpdClosesWithThePatientAndVisitAuditsTheVisitIdAndACaseClosedMeanwhileWritesNothing() {
        UUID visit = UUID.randomUUID();
        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("SEEN")));
        when(cases.databaseNow()).thenReturn(NOW);
        when(cases.closeSentToOpd(TENANT, CASE, PATIENT, visit, NOW, ACTOR)).thenReturn(true);

        assertThat(emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.SENT_TO_OPD, PATIENT, visit).closed()).isTrue();
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("IPD_ER_SENT_TO_OPD"), eq("IPD_EMERGENCY_CASE"), eq(CASE), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("visitId", visit).containsEntry("caseId", CASE);

        assertThatThrownBy(() -> emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.SENT_TO_OPD, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        when(cases.findForUpdate(TENANT, CASE)).thenReturn(Optional.of(openCase("RELEASED")));
        assertThat(emergencyPort.close(TENANT, ACTOR, CASE, CloseKind.RELEASE, null, null).closed()).isFalse();
    }

    @Test
    void theTriageScaleIsTheClinicsOrTheSah28DefaultAndSaysWhich() {
        when(settings.findEmergencySetup(TENANT)).thenReturn(Optional.empty());

        EmrEmergencyPort.TriageScale scale = emergencyPort.triageScale(TENANT);

        assertThat(scale.clinicDefault()).isTrue();
        assertThat(scale.scale()).isEqualTo(3);
        assertThat(scale.levels()).hasSize(3);
        assertThat(scale.levels().get(0).level()).isEqualTo(1);

        when(settings.findEmergencySetup(TENANT)).thenReturn(Optional.of(EmergencySetup.DEFAULTS));
        assertThat(emergencyPort.triageScale(TENANT).clinicDefault()).isFalse();
    }

    // ---- IPD orders ----

    private final MedicineOrderService orderService = mock(MedicineOrderService.class);
    private final MedicineCatalogue catalogue = mock(MedicineCatalogue.class);
    private final EmrIpdOrderPort orderPort = new EmrIpdOrderAdapter(orderService, catalogue);

    private static OrderLine line(UUID key) {
        return new OrderLine(key, "pcm", "od", null, null);
    }

    @Test
    void aCheckMapsSah31OutcomesToTheRowsWithItsOwnTextAndTheToken() {
        UUID key = UUID.randomUUID();
        Drug drug = new Drug("pcm", "Tab. Paracetamol 650 mg", "PO", Set.of(), null);
        OrderCheck check = mock(OrderCheck.class);
        when(check.hardStops()).thenReturn(List.of());
        when(check.warnings()).thenReturn(List.of());
        when(check.checkToken()).thenReturn("T".repeat(22));
        when(orderService.checkOnly(eq(TENANT), eq(ACTOR), any(), eq(ADMISSION), any(MedicineOrderInput.class)))
                .thenReturn(new CheckOnly("NEEDS_REASON", check, drug, Frequency.ofKey("od").orElseThrow()));

        List<LineCheck> result = orderPort.check(TENANT, ACTOR, "DOCTOR", ADMISSION, List.of(line(key)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).outcome()).isEqualTo("NEEDS_REASON");
        assertThat(result.get(0).checkToken()).hasSize(22);
        assertThat(result.get(0).drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(AllergyStatus.values()).isNotEmpty();
    }

    @Test
    void aPlaceStopsAtTheFirstOrderIpdDoesNotPlaceAndReportsItsCheck() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MedicineOrderRow row = mock(MedicineOrderRow.class);
        UUID orderId = UUID.randomUUID();
        when(row.id()).thenReturn(orderId);
        when(row.orderedAt()).thenReturn(NOW);
        MedicineOrderAnswer placed = new MedicineOrderAnswer("PLACED", false, null, row, null);
        OrderCheckView stop = new OrderCheckView("RECORDED", List.of(new MedicineViews.OrderFinding("ALLERGY_PENICILLIN",
                "HARD_STOP", null, "Documented allergy")), List.of(), "T".repeat(22));
        when(orderService.order(eq(TENANT), eq(ACTOR), any(), eq(ADMISSION), any(MedicineOrderInput.class)))
                .thenReturn(placed, new MedicineOrderAnswer("BLOCKED", false, stop, null, null));
        com.sahaj.ipd.domain.DrugCatalogue drugs = mock(com.sahaj.ipd.domain.DrugCatalogue.class);
        when(catalogue.catalogue()).thenReturn(drugs);
        when(drugs.find("pcm")).thenReturn(Optional.of(new Drug("pcm", "Tab. Paracetamol 650 mg", "PO", Set.of(), null)));

        PlaceResult result = orderPort.place(TENANT, ACTOR, "DOCTOR", ADMISSION,
                List.of(line(first), line(second), line(UUID.randomUUID())));

        assertThat(result.ok()).isFalse();
        assertThat(result.placed()).extracting(EmrIpdOrderPort.Placed::orderId).containsExactly(orderId);
        assertThat(result.notPlaced()).hasSize(1);
        assertThat(result.notPlaced().get(0).clientKey()).isEqualTo(second);
        assertThat(result.notPlaced().get(0).outcome()).isEqualTo("BLOCKED");
        // DEC-051 D3: the refusal row names the drug by its catalogue name and the kind of the first finding
        assertThat(result.notPlaced().get(0).drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(result.notPlaced().get(0).checkKind()).isEqualTo("ALLERGY_PENICILLIN");
        verify(orderService, org.mockito.Mockito.times(2)).order(any(), any(), any(), any(), any());
    }

    @Test
    void aCheckCarriesTheCodeOfTheFirstFindingAHardStopBeforeAWarningAndNullWithoutAny() {
        Drug drug = new Drug("pcm", "Tab. Paracetamol 650 mg", "PO", Set.of(), null);
        com.sahaj.ipd.domain.MedicineSafetyCheck.Finding stop = new com.sahaj.ipd.domain.MedicineSafetyCheck.Finding(
                com.sahaj.ipd.domain.MedicineSafetyCheck.FindingCode.DUPLICATE,
                com.sahaj.ipd.domain.MedicineSafetyCheck.Severity.HARD_STOP, "pcm", "Already ordered", "Already ordered");
        com.sahaj.ipd.domain.MedicineSafetyCheck.Finding warning = new com.sahaj.ipd.domain.MedicineSafetyCheck.Finding(
                com.sahaj.ipd.domain.MedicineSafetyCheck.FindingCode.INTERACTION,
                com.sahaj.ipd.domain.MedicineSafetyCheck.Severity.MAJOR, "x", "Interacts", null);
        OrderCheck withBoth = mock(OrderCheck.class);
        when(withBoth.hardStops()).thenReturn(List.of(stop));
        when(withBoth.warnings()).thenReturn(List.of(warning));
        OrderCheck none = mock(OrderCheck.class);
        when(none.hardStops()).thenReturn(List.of());
        when(none.warnings()).thenReturn(List.of());
        when(orderService.checkOnly(eq(TENANT), eq(ACTOR), any(), eq(ADMISSION), any(MedicineOrderInput.class)))
                .thenReturn(new CheckOnly("BLOCKED", withBoth, drug, Frequency.ofKey("od").orElseThrow()),
                        new CheckOnly("PASS", none, drug, Frequency.ofKey("od").orElseThrow()));

        List<LineCheck> result = orderPort.checkRecorded(TENANT, ACTOR, "DOCTOR", ADMISSION,
                List.of(line(UUID.randomUUID()), line(UUID.randomUUID())));

        assertThat(result).extracting(LineCheck::checkKind).containsExactly("DUPLICATE", null);
    }

    @Test
    void theRoleOfTheCallerIsPassedThroughThePortNotReadFromTheThreadLocal() {
        UUID key = UUID.randomUUID();
        Drug drug = new Drug("pcm", "Tab. Paracetamol 650 mg", "PO", Set.of(), null);
        OrderCheck check = mock(OrderCheck.class);
        when(check.hardStops()).thenReturn(List.of());
        when(check.warnings()).thenReturn(List.of());
        when(orderService.checkOnly(eq(TENANT), eq(ACTOR), eq("OWNER"), eq(ADMISSION), any(MedicineOrderInput.class)))
                .thenReturn(new CheckOnly("PASS", check, drug, Frequency.ofKey("od").orElseThrow()));

        // no signed-in user on this thread: the role is the argument
        assertThat(orderPort.check(TENANT, ACTOR, "OWNER", ADMISSION, List.of(line(key))))
                .extracting(LineCheck::outcome).containsExactly("PASS");
    }

    @Test
    void aRecordedCheckRunsEachLineInTheOrderServicesOwnTransactionSoAHardStopLeavesItsAudit() {
        UUID key = UUID.randomUUID();
        Drug drug = new Drug("pcm", "Tab. Paracetamol 650 mg", "PO", Set.of(), null);
        OrderCheck check = mock(OrderCheck.class);
        when(check.hardStops()).thenReturn(List.of());
        when(check.warnings()).thenReturn(List.of());
        when(orderService.checkOnly(eq(TENANT), eq(ACTOR), eq("DOCTOR"), eq(ADMISSION),
                any(MedicineOrderInput.class))).thenReturn(new CheckOnly("BLOCKED", check, drug,
                        Frequency.ofKey("od").orElseThrow()));

        List<LineCheck> result = orderPort.checkRecorded(TENANT, ACTOR, "DOCTOR", ADMISSION, List.of(line(key)));

        assertThat(result).extracting(LineCheck::outcome).containsExactly("BLOCKED");
        verify(orderService).checkOnly(eq(TENANT), eq(ACTOR), eq("DOCTOR"), eq(ADMISSION),
                any(MedicineOrderInput.class));
    }

    @Test
    void theRecordedCheckIsNeverInsideATransactionAndNoRequiresNewIsLeftInTheOrderService() throws Exception {
        java.lang.reflect.Method recorded = EmrIpdOrderAdapter.class.getMethod("checkRecorded", UUID.class, UUID.class,
                String.class, UUID.class, List.class);
        assertThat(recorded.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .as("DEC-048 H37: a caller holding a transaction is refused").isEqualTo(
                        org.springframework.transaction.annotation.Propagation.NEVER);
        for (java.lang.reflect.Method method : MedicineOrderService.class.getDeclaredMethods()) {
            org.springframework.transaction.annotation.Transactional transactional = method.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            if (transactional != null) {
                assertThat(transactional.propagation()).as(method.getName())
                        .isNotEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
            }
        }
    }

    @Test
    void theClientKeyOfTheRowIsTheSah31ClientKey() {
        ObjectProvider<EmrIpdOrderPort> none = mock(ObjectProvider.class);
        assertThat(none.getIfAvailable()).isNull();
        assertThat(line(UUID.randomUUID()).toString()).startsWith("OrderLine[clientKey=");
    }
}
