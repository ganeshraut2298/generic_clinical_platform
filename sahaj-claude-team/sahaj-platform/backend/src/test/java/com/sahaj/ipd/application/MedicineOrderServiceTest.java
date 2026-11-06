package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MedicineOrderRepository.NewOrder;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * SAH-31 the EMR sheet, placing an order, and stop: the check outcomes, the replay rules, the roles read twice, the
 * Read-only and not-admitted answers, the events and audit rows (no clinical text) and the single lock.
 */
class MedicineOrderServiceTest extends MedicineTestBase {

    private static final String MIDDOT = "·";

    private MedicineOrderService service;
    private UUID clientKey;

    @BeforeEach
    void setUp() {
        service = new MedicineOrderService(fake, admissions, events, patients, patientAccess, actors, team, auditLog,
                gate, catalogue, assembler);
        clientKey = UUID.randomUUID();
    }

    private MedicineOrderInput input(String drug, String frequency) {
        return new MedicineOrderInput(clientKey.toString(), drug, frequency, null, null);
    }

    private MedicineOrderAnswer order(UUID actor, String role, MedicineOrderInput input) {
        return service.order(TENANT, actor, role, ADMISSION, input);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), isNull(), after.capture());
        return after.getValue();
    }

    // ---- a clear order ----

    @Test
    void aClearOrderIsPlacedPendingAndTheAnswerIsTheSheet() {
        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", input("pcm", "bd"));

        assertThat(answer.outcome()).isEqualTo("PLACED");
        assertThat(answer.replayed()).isFalse();
        assertThat(answer.order().status()).isEqualTo("PENDING");
        assertThat(answer.order().drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(answer.order().frequencyLabel()).isEqualTo("Twice daily");
        assertThat(answer.order().orderedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(answer.order().overrideReason()).isNull();
        assertThat(answer.order().canStop()).isTrue();
        assertThat(answer.check().hardStops()).isEmpty();
        assertThat(answer.check().warnings()).isEmpty();
        assertThat(answer.sheet().orders()).extracting(o -> o.id()).containsExactly(answer.order().id());
        assertThat(fake.rows).hasSize(1);
        OrderRow stored = fake.rows.values().iterator().next();
        assertThat(stored.orderedBy()).isEqualTo(DOCTOR);
        assertThat(stored.clientKey()).isEqualTo(clientKey);
        assertThat(stored.overrideReasonKey()).isNull();
        assertThat(stored.warnings()).isEmpty();
        verifyNoInteractions(patientAccess);
    }

    @Test
    void anOwnerOrdersAsADoctor() {
        memberRoles.put(DOCTOR, "OWNER");

        assertThat(order(DOCTOR, "OWNER", input("pcm", "od")).outcome()).isEqualTo("PLACED");
    }

    @Test
    void theEventAndTheAuditRowCarryFixedTextAndIdsOnly() {
        allergyText = "Peanuts, shellfish";
        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "pcm", "tds",
                null, null));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Tab. Paracetamol 650 mg three times daily", DOCTOR);
        Map<String, Object> after = auditAfter("IPD_MED_ORDERED");
        assertThat(after).containsEntry("orderId", answer.order().id()).containsEntry("admissionId", ADMISSION)
                .containsEntry("drugKey", "pcm").containsEntry("frequencyKey", "tds")
                .containsEntry("status", "PENDING").containsEntry("overrideReasonKey", null)
                .containsEntry("warningCount", 0);
        assertThat(after.toString()).doesNotContain("Peanuts");
    }

    // ---- warnings and the reason ----

    @Test
    void warningsWithoutAReasonAnswerNeedsReasonAndStoreNothing() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", input("asp", "od"));

        assertThat(answer.outcome()).isEqualTo("NEEDS_REASON");
        assertThat(answer.order()).isNull();
        assertThat(answer.check().warnings()).hasSize(1);
        assertThat(answer.check().warnings().get(0).severity()).isEqualTo("MAJOR");
        assertThat(answer.check().checkToken()).hasSize(22);
        assertThat(fake.writes).isZero();
        verify(patientAccess).recordOpened(TENANT, PATIENT, DOCTOR);
        verifyNoInteractions(auditLog);
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void aReasonWithAMissingOrWrongTokenAnswersNeedsReasonAgain() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);

        MedicineOrderAnswer noToken = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp", "od",
                "CARDIOLOGY_STENT", null));
        MedicineOrderAnswer wrongToken = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp",
                "od", "CARDIOLOGY_STENT", "AAAAAAAAAAAAAAAAAAAAAA"));

        assertThat(noToken.outcome()).isEqualTo("NEEDS_REASON");
        assertThat(wrongToken.outcome()).isEqualTo("NEEDS_REASON");
        assertThat(fake.writes).isZero();
    }

    @Test
    void aTokenWithoutAReasonAnswersNeedsReason() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);
        String token = order(DOCTOR, "DOCTOR", input("asp", "od")).check().checkToken();

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp", "od",
                null, token));

        assertThat(answer.outcome()).isEqualTo("NEEDS_REASON");
    }

    @Test
    void aReasonWithTheCurrentTokenPlacesTheOrderWithTheWarningLinesAsSeen() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);
        String token = order(DOCTOR, "DOCTOR", input("asp", "od")).check().checkToken();

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp", "od",
                "CARDIOLOGY_STENT", token));

        assertThat(answer.outcome()).isEqualTo("PLACED");
        assertThat(answer.order().overrideReason().key()).isEqualTo("CARDIOLOGY_STENT");
        assertThat(answer.order().overrideReason().label()).isEqualTo("Recent coronary stent, cardiology advised");
        OrderRow stored = fake.rows.get(answer.order().id());
        assertThat(stored.overrideReasonKey()).isEqualTo("CARDIOLOGY_STENT");
        assertThat(stored.warnings()).containsExactly(
                "Major: Bleeding risk: heparin with aspirin (Inj. Heparin 5,000 IU is on the chart).");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Tab. Aspirin 75 mg once daily " + MIDDOT
                        + " override: Recent coronary stent, cardiology advised", DOCTOR);
        Map<String, Object> after = auditAfter("IPD_MED_ORDERED");
        assertThat(after).containsEntry("overrideReasonKey", "CARDIOLOGY_STENT").containsEntry("warningCount", 1);
        assertThat(after.toString()).doesNotContain("heparin");
    }

    @Test
    void ifTheWarningsChangedSinceTheDoctorSawThemTheOldReasonIsNotRecorded() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);
        String token = order(DOCTOR, "DOCTOR", input("asp", "od")).check().checkToken();
        fake.seed(ADMISSION, "ibu", "PENDING", OTHER_DOCTOR);

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp", "od",
                "CARDIOLOGY_STENT", token));

        assertThat(answer.outcome()).isEqualTo("NEEDS_REASON");
        assertThat(answer.check().warnings()).hasSize(2);
        assertThat(answer.check().checkToken()).isNotEqualTo(token);
        assertThat(fake.writes).isZero();
    }

    @Test
    void aReasonSentWithNoFindingIsIgnoredAndNotStored() {
        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "pcm", "od",
                "SPECIALIST_ADVISED", null));

        assertThat(answer.outcome()).isEqualTo("PLACED");
        OrderRow stored = fake.rows.get(answer.order().id());
        assertThat(stored.overrideReasonKey()).isNull();
        assertThat(stored.warnings()).isEmpty();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Tab. Paracetamol 650 mg once daily", DOCTOR);
    }

    @Test
    void anEmptyAllergyFieldNeedsAReasonThenPlacesWithTheNotRecordedLine() {
        allergyText = null;
        String token = order(DOCTOR, "DOCTOR", input("pcm", "od")).check().checkToken();

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "pcm", "od",
                "BENEFIT_MONITORED", token));

        assertThat(answer.outcome()).isEqualTo("PLACED");
        assertThat(fake.rows.get(answer.order().id()).warnings()).containsExactly(
                "Moderate: Allergies are not recorded. Ask the patient and record them before the first dose.");
    }

    // ---- hard stops ----

    @Test
    void anAllergyHardStopAnswers200BlockedStoresNothingAndLogsWithoutTheAllergyText() {
        allergyText = "Penicillin (rash)";

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", input("amox", "bd"));

        assertThat(answer.outcome()).isEqualTo("BLOCKED");
        assertThat(answer.order()).isNull();
        assertThat(answer.check().hardStops()).hasSize(1);
        assertThat(answer.check().hardStops().get(0).text()).startsWith("Documented allergy: Penicillin (rash). ");
        assertThat(fake.rows).isEmpty();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Order blocked: Tab. Amoxicillin + clavulanate 625 mg " + MIDDOT
                        + " Documented penicillin allergy. Tab. Amoxicillin + clavulanate 625 mg is a penicillin.",
                DOCTOR);
        Map<String, Object> after = auditAfter("IPD_MED_ORDER_BLOCKED");
        assertThat(after).containsEntry("admissionId", ADMISSION).containsEntry("drugKey", "amox")
                .containsEntry("frequencyKey", "bd").containsEntry("status", "BLOCKED");
        assertThat(after.toString()).doesNotContain("rash");
        verifyNoInteractions(patientAccess);
    }

    @Test
    void theBlockedEventTextNeverHoldsTheAllergyText() {
        allergyText = "Penicillin (rash) Anil Kumar 9876543210";
        order(DOCTOR, "DOCTOR", input("amox", "bd"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_DENY), text.capture(),
                eq(DOCTOR));
        assertThat(text.getValue()).doesNotContain("rash").doesNotContain("Anil").doesNotContain("9876543210")
                .hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void aDuplicateOnThePendingOrActiveChartIsBlocked() {
        fake.seed(ADMISSION, "pcm", "PENDING", OTHER_DOCTOR);

        MedicineOrderAnswer answer = order(DOCTOR, "DOCTOR", input("pcm", "od"));

        assertThat(answer.outcome()).isEqualTo("BLOCKED");
        assertThat(answer.check().hardStops().get(0).code()).isEqualTo("DUPLICATE");
        assertThat(fake.writes).isZero();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Order blocked: Tab. Paracetamol 650 mg " + MIDDOT + " Tab. Paracetamol 650 mg is already on the chart."
                        + " Change the existing order instead of adding a second one.", DOCTOR);
    }

    @Test
    void aRejectedOrStoppedOrderIsNotADuplicate() {
        fake.seed(ADMISSION, "pcm", "REJECTED", OTHER_DOCTOR);
        fake.seed(ADMISSION, "pcm", "STOPPED", OTHER_DOCTOR);

        assertThat(order(DOCTOR, "DOCTOR", input("pcm", "od")).outcome()).isEqualTo("PLACED");
    }

    @Test
    void aBlockedRequestRetriedWithTheSameKeyIsCheckedAgainAndStillBlocked() {
        fake.seed(ADMISSION, "pcm", "PENDING", OTHER_DOCTOR);

        assertThat(order(DOCTOR, "DOCTOR", input("pcm", "od")).outcome()).isEqualTo("BLOCKED");
        assertThat(order(DOCTOR, "DOCTOR", input("pcm", "od")).outcome()).isEqualTo("BLOCKED");
        verify(events, times(2)).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_DENY), anyString(),
                eq(DOCTOR));
    }

    // ---- the repeat of a client key ----

    @Test
    void aRepeatWithTheSameValuesAnswersTheFirstOrderAndWritesOnlyAnAccessRow() {
        MedicineOrderAnswer first = order(DOCTOR, "DOCTOR", input("pcm", "bd"));
        int writes = fake.writes;

        MedicineOrderAnswer again = order(DOCTOR, "DOCTOR", input("pcm", "bd"));

        assertThat(again.outcome()).isEqualTo("PLACED");
        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.order().id());
        assertThat(fake.writes).isEqualTo(writes);
        assertThat(fake.rows).hasSize(1);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, DOCTOR);
        verify(events, times(1)).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, times(1)).record(anyString(), anyString(), any(), any(), anyMap());
    }

    @Test
    void aRepeatWithAnotherMedicineFrequencyOrReasonIs409ClientKeyReused() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);
        String token = order(DOCTOR, "DOCTOR", input("asp", "od")).check().checkToken();
        order(DOCTOR, "DOCTOR", new MedicineOrderInput(clientKey.toString(), "asp", "od", "CARDIOLOGY_STENT", token));
        int writes = fake.writes;

        for (MedicineOrderInput other : List.of(
                new MedicineOrderInput(clientKey.toString(), "pcm", "od", "CARDIOLOGY_STENT", token),
                new MedicineOrderInput(clientKey.toString(), "asp", "bd", "CARDIOLOGY_STENT", token),
                new MedicineOrderInput(clientKey.toString(), "asp", "od", "SPECIALIST_ADVISED", token),
                new MedicineOrderInput(clientKey.toString(), "asp", "od", null, token))) {
            assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", other)).isInstanceOf(ConflictException.class)
                    .hasMessage("This order was already placed with different values. Reload the page and try again.")
                    .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("client-key-reused"));
        }
        assertThat(fake.writes).isEqualTo(writes);
    }

    @Test
    void aReasonSentWithNoWarningIsNotMistakenForADifferentRequestOnRetry() {
        MedicineOrderInput withIgnoredReason = new MedicineOrderInput(clientKey.toString(), "pcm", "od",
                "SPECIALIST_ADVISED", null);
        order(DOCTOR, "DOCTOR", withIgnoredReason);

        MedicineOrderAnswer again = order(DOCTOR, "DOCTOR", withIgnoredReason);

        assertThat(again.replayed()).isTrue();
    }

    @Test
    void aKeyUsedForAnotherAdmissionIsA400OnTheClientKeyField() {
        UUID otherAdmission = UUID.randomUUID();
        fake.seed(otherAdmission, "pcm", "PENDING", DOCTOR);
        OrderRow elsewhere = fake.rows.values().iterator().next();

        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", new MedicineOrderInput(elsewhere.clientKey().toString(),
                "pcm", "od", null, null))).isInstanceOf(InvalidInputException.class)
                .hasMessage("Reload the page and try again.")
                .satisfies(e -> assertThat(((InvalidInputException) e).field()).isEqualTo("clientKey"));
    }

    @Test
    void aRepeatIsAnsweredEvenAfterDischargeBeforeTheNotAdmittedCheck() {
        MedicineOrderAnswer first = order(DOCTOR, "DOCTOR", input("pcm", "od"));
        admission = admissionRow("DISCHARGED");

        MedicineOrderAnswer again = order(DOCTOR, "DOCTOR", input("pcm", "od"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.order().id());
        assertThat(again.order().canStop()).isFalse();
    }

    // ---- refusals ----

    @Test
    void aPatientWhoIsNotAdmittedAnyMoreIs409NotAdmittedAndNothingIsWritten() {
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(ConflictException.class)
                .hasMessage("This patient is not admitted any more. Medicines cannot be ordered or changed.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("not-admitted"));
        assertThat(fake.writes).isZero();
        verifyNoInteractions(auditLog, events);
    }

    @Test
    void everyRoleExceptOwnerAndDoctorIs403WithTheDoctorsOnlyDetail() {
        memberRoles.put(DOCTOR, "NURSE");
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR",
            "VISITING_DOCTOR", "X", null}) {
            assertThatThrownBy(() -> order(DOCTOR, role, input("pcm", "od"))).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only a doctor can do this.");
        }
        verifyNoInteractions(admissions, events, auditLog, patientAccess);
    }

    @Test
    void theRoleIsReadAgainFromTheMembership() {
        for (String member : new String[] {"NURSE", "PHARMACIST", "FRONT_DESK"}) {
            memberRoles.put(DOCTOR, member);
            assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only a doctor can do this.");
        }
        memberRoles.remove(DOCTOR);
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(ForbiddenException.class);
        memberRoles.put(DOCTOR, "DOCTOR");
        memberActive = false;
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(ForbiddenException.class);
        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        assertThat(fake.writes).isZero();
    }

    @Test
    void moduleOffIs403AndReadOnlyIs409BeforeTheBodyIsChecked() {
        ipdState("off");
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", new MedicineOrderInput(null, null, null, null, null)))
                .isInstanceOf(ModuleOffException.class);

        ipdState("readonly");
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", new MedicineOrderInput(null, null, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage("IPD is read-only. Nothing can be changed.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));
        assertThat(fake.writes).isZero();
    }

    @Test
    void aBadBodyIs400WithEveryFieldErrorAndNoLockIsTaken() {
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", new MedicineOrderInput("x", "zzz", "qd", null, null)))
                .isInstanceOf(InvalidFieldsException.class);

        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verifyNoInteractions(team);
    }

    @Test
    void aMissingAdmissionOrPatientIs404AndNothingIsWritten() {
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(NotFoundException.class);

        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(admission));
        patientFound = false;
        assertThatThrownBy(() -> order(DOCTOR, "DOCTOR", input("pcm", "od"))).isInstanceOf(NotFoundException.class);
        assertThat(fake.writes).isZero();
        verifyNoInteractions(events, auditLog);
    }

    // ---- the lock and the database backstops ----

    @Test
    void anOrderTakesOnlyTheAdmissionRowLockForNoKeyUpdate() {
        order(DOCTOR, "DOCTOR", input("pcm", "od"));

        verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        verify(admissions, never()).findForUpdate(any(), any());
    }

    @Test
    void aDuplicateKeyFromTheDatabaseIsNeverSwallowed() {
        MedicineOrderRepository failing = spy(fake);
        doThrow(new DuplicateKeyException("ux_medicine_order_tenant_admission_drug_live")).when(failing)
                .insert(any(), any(NewOrder.class));
        MedicineAssembler a = new MedicineAssembler(catalogue, failing, new FakeDoses(), patients, patientAccess, actors,
                admissionService);
        MedicineOrderService s = new MedicineOrderService(failing, admissions, events, patients, patientAccess, actors,
                team, auditLog, gate, catalogue, a);

        assertThatThrownBy(() -> s.order(TENANT, DOCTOR, "DOCTOR", ADMISSION, input("pcm", "od")))
                .isInstanceOf(IllegalStateException.class).hasMessage("The order could not be stored");
        verifyNoInteractions(events, auditLog);
    }

    // ---- the EMR sheet ----

    @Test
    void theEmrSheetHasTheContractShapeAndWritesTheViewEntryAndOneAccessRow() {
        allergyText = "Penicillin (rash)";
        fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        EmrSheet sheet = service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.admissionId()).isEqualTo(ADMISSION);
        assertThat(sheet.status()).isEqualTo("ADMITTED");
        assertThat(sheet.state()).isEqualTo("on");
        assertThat(sheet.asOf()).isEqualTo(fake.now);
        assertThat(sheet.patient().name()).isEqualTo("Test Patient");
        assertThat(sheet.patient().uhid()).isEqualTo("UH-000123");
        assertThat(sheet.patient().age()).isEqualTo(54);
        assertThat(sheet.patient().sex()).isEqualTo("F");
        assertThat(sheet.patient().bedCode()).isEqualTo("202");
        assertThat(sheet.patient().allergyText()).isEqualTo("Penicillin (rash)");
        assertThat(sheet.patient().allergyStatus()).isEqualTo("RECORDED");
        assertThat(sheet.admittingDoctorName()).isEqualTo("Dr Meera Joshi");
        assertThat(sheet.diagnosis()).isEqualTo("Chest infection");
        assertThat(sheet.dayNumber()).isEqualTo(3);
        assertThat(sheet.orders()).hasSize(1);
        assertThat(sheet.canOrder()).isTrue();
        assertThat(sheet.orderLockReason()).isNull();
        assertThat(sheet.drugs()).hasSize(14);
        assertThat(sheet.drugs().get(11).key()).isEqualTo("hep");
        assertThat(sheet.drugs().get(11).highAlert()).isEqualTo("Anticoagulant");
        assertThat(sheet.frequencies()).extracting(k -> k.key()).containsExactly("od", "bd", "tds", "q8");
        assertThat(sheet.overrideReasons()).hasSize(4);
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VIEW, "Opened the clinical record",
                DOCTOR);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, DOCTOR);
        verifyNoInteractions(auditLog);
        verify(admissions, never()).findForNoKeyUpdate(any(), any());
    }

    @Test
    void theAllergyChipFollowsTheText() {
        allergyText = "None known";
        assertThat(service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION).patient().allergyStatus()).isEqualTo("NONE_KNOWN");
        allergyText = "";
        assertThat(service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION).patient().allergyStatus())
                .isEqualTo("NOT_RECORDED");
        allergyText = "Penicillin; none known otherwise";
        assertThat(service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION).patient().allergyStatus()).isEqualTo("RECORDED");
    }

    @Test
    void aNurseOpensTheEmrButCannotOrderOrStop() {
        fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        EmrSheet sheet = service.emr(TENANT, NURSE, "NURSE", ADMISSION);

        assertThat(sheet.canOrder()).isFalse();
        assertThat(sheet.orderLockReason()).isEqualTo("ROLE");
        assertThat(sheet.orders().get(0).canStop()).isFalse();
    }

    @Test
    void theEmrIsHiddenFromEveryOtherRoleWithNothingWritten() {
        memberRoles.put(DOCTOR, "FRONT_DESK");
        for (String role : new String[] {"FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR", null}) {
            assertThatThrownBy(() -> service.emr(TENANT, DOCTOR, role, ADMISSION))
                    .isInstanceOf(ForbiddenException.class).hasMessage("Clinical details are hidden for your role.");
        }
        assertThatThrownBy(() -> service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION))
                .isInstanceOf(ForbiddenException.class).hasMessage("Clinical details are hidden for your role.");
        verifyNoInteractions(events, patientAccess, auditLog);
    }

    @Test
    void aMissingAdmissionOrPatientOpensNothingAndWritesNothing() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION))
                .isInstanceOf(NotFoundException.class);

        when(admissions.find(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(admission));
        patientFound = false;
        assertThatThrownBy(() -> service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(events, patientAccess);
    }

    @Test
    void aDischargedAdmissionOpensButNothingCanBeOrderedOrStopped() {
        fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
        admission = admissionRow("DISCHARGED");

        EmrSheet sheet = service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.status()).isEqualTo("DISCHARGED");
        assertThat(sheet.canOrder()).isFalse();
        assertThat(sheet.orderLockReason()).isEqualTo("NOT_ADMITTED");
        assertThat(sheet.orders().get(0).canStop()).isFalse();
        assertThat(sheet.orders().get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void inReadOnlyTheEmrOpensAndStopStaysAvailableButOrderingIsLocked() {
        ipdState("readonly");
        fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);

        EmrSheet sheet = service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.state()).isEqualTo("readonly");
        assertThat(sheet.canOrder()).isFalse();
        assertThat(sheet.orderLockReason()).isEqualTo("IPD_READONLY");
        assertThat(sheet.orders().get(0).canStop()).isTrue();
    }

    @Test
    void theEmrOrdersAreInChartOrderWithTheirStaffNames() {
        OrderRow first = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
        OrderRow second = fake.seed(ADMISSION, "panto", "PENDING", OTHER_DOCTOR);

        EmrSheet sheet = service.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.orders()).extracting(o -> o.id()).containsExactly(first.id(), second.id());
        assertThat(sheet.orders().get(1).orderedByName()).isEqualTo("Dr Asha Rao");
    }

    // ---- stop ----

    @Test
    void stoppingAPendingOrderRecordsTheEventTheAuditRowAndAnswersTheSheet() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", OTHER_DOCTOR);

        EmrSheet sheet = service.stop(TENANT, DOCTOR, "DOCTOR", pending.id());

        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("STOPPED");
        assertThat(fake.rows.get(pending.id()).stoppedBy()).isEqualTo(DOCTOR);
        assertThat(sheet.orders().get(0).status()).isEqualTo("STOPPED");
        assertThat(sheet.orders().get(0).stoppedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(sheet.orders().get(0).canStop()).isFalse();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_STOP, "Stopped Tab. Paracetamol 650 mg",
                DOCTOR);
        Map<String, Object> after = auditAfter("IPD_MED_STOPPED");
        assertThat(after).containsEntry("orderId", pending.id()).containsEntry("statusBefore", "PENDING")
                .containsEntry("status", "STOPPED").containsEntry("drugKey", "pcm");
        verifyNoInteractions(patientAccess);
    }

    @Test
    void anyDoctorCanStopAnActiveOrderAndTheLockIsTheAdmissionRow() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);

        service.stop(TENANT, DOCTOR, "DOCTOR", active.id());

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("STOPPED");
        assertThat(auditAfter("IPD_MED_STOPPED")).containsEntry("statusBefore", "ACTIVE");
        verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        verify(admissions, never()).findForUpdate(any(), any());
    }

    @Test
    void stopIsAllowedInReadOnlyState() {
        ipdState("readonly");
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        service.stop(TENANT, DOCTOR, "DOCTOR", active.id());

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("STOPPED");
    }

    @Test
    void aRepeatedStopByTheSameUserAnswers200WithOneAccessRowAndNoWrite() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", OTHER_DOCTOR);
        service.stop(TENANT, DOCTOR, "DOCTOR", pending.id());
        int writes = fake.writes;

        EmrSheet again = service.stop(TENANT, DOCTOR, "DOCTOR", pending.id());

        assertThat(again.orders().get(0).status()).isEqualTo("STOPPED");
        assertThat(fake.writes).isEqualTo(writes);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, DOCTOR);
        verify(events, times(1)).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, times(1)).record(anyString(), anyString(), any(), any(), anyMap());
    }

    @Test
    void stopOfAnOrderStoppedByAnotherUserOrRejectedIs409OrderState() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        service.stop(TENANT, OTHER_DOCTOR, "DOCTOR", pending.id());
        OrderRow rejected = fake.seed(ADMISSION, "panto", "REJECTED", DOCTOR);

        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", pending.id()))
                .isInstanceOf(ConflictException.class).hasMessage("This order is already stopped.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", rejected.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This order was rejected by the pharmacist. It cannot be stopped.");
    }

    @Test
    void stopOnADischargedAdmissionIs409NotAdmitted() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", active.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This patient is not admitted any more. Medicines cannot be ordered or changed.");
        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void stopRefusesOtherRolesAndAMissingOrder() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
        for (String role : new String[] {"NURSE", "PHARMACIST", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, role, active.id()))
                    .isInstanceOf(ForbiddenException.class).hasMessage("Only a doctor can do this.");
        }
        memberRoles.put(DOCTOR, "NURSE");
        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", active.id()))
                .isInstanceOf(ForbiddenException.class);
        memberRoles.put(DOCTOR, "DOCTOR");
        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
        ModuleEntitlement off = new ModuleEntitlement("off", false, 25);
        when(entitlements.forTenant(TENANT)).thenReturn(off);
        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", active.id()))
                .isInstanceOf(ModuleOffException.class);
    }
}
