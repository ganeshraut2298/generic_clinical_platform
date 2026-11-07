package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseService.DoseResult;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.SlotView;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * SAH-32 giving a dose and recording that it was not given: the roles read twice, the order and slot rules, the
 * bedside checks in the order of AC6, the wristband deny entry that stays although the answer is 409, the second
 * nurse's PIN, the single lock with the clock read after it, the dose, event and audit row, and the replay rules.
 * No database: the repositories are in-memory fakes and the PIN port is a mock.
 */
class DoseServiceTest extends DoseTestBase {

    private DoseResult give(UUID actor, String role, OrderRow order, DoseInput input) {
        return service.record(TENANT, actor, role, order.id(), input);
    }

    private DoseResult nurseGives(OrderRow order, DoseInput input) {
        return give(NURSE, "NURSE", order, input);
    }

    private void assertNothingWritten() {
        assertThat(doses.writes).isZero();
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    private static void assertField(Throwable e, String field, String message) {
        assertThat(e).isInstanceOfSatisfying(InvalidInputException.class, i -> {
            assertThat(i.field()).isEqualTo(field);
            assertThat(i.getMessage()).isEqualTo(message);
        });
    }

    private static void assertConflict(Throwable e, String type, String message) {
        assertThat(e).isInstanceOfSatisfying(ConflictException.class, c -> {
            assertThat(c.problemType()).isEqualTo(type);
            assertThat(c.getMessage()).isEqualTo(message);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), isNull(), after.capture());
        return after.getValue();
    }

    // ---- a plain dose ----

    @Test
    void aValidGivenOfAMedicineThatIsNotHighAlertIsStoredAndTheAnswerIs201() {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, given(EIGHT_AM));

        assertThat(result.created()).isTrue();
        assertThat(result.answer().replayed()).isFalse();
        assertThat(doses.rows).hasSize(1);
        var stored = doses.inserted.get(0);
        assertThat(stored.outcome()).isEqualTo("GIVEN");
        assertThat(stored.reasonKey()).isNull();
        assertThat(stored.recordedBy()).isEqualTo(NURSE);
        assertThat(stored.secondCheckBy()).isNull();
        assertThat(stored.highAlert()).isFalse();
        assertThat(stored.identityScan()).isEqualTo(UHID);
        assertThat(stored.identityConfirmed()).isTrue();
        assertThat(stored.admissionId()).isEqualTo(ADMISSION);
        assertThat(stored.orderId()).isEqualTo(order.id());
        assertThat(stored.slotAt()).isEqualTo(EIGHT_AM);
        verifyNoInteractions(staffPin);
    }

    @Test
    void theAnswerHoldsTheDoseAndTheMarWithTheSlotGiven() {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, given(EIGHT_AM));

        assertThat(result.answer().dose().outcome()).isEqualTo("GIVEN");
        assertThat(result.answer().dose().slotAt()).isEqualTo(EIGHT_AM);
        assertThat(result.answer().dose().recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(result.answer().dose().secondCheckByName()).isNull();
        assertThat(result.answer().dose().reason()).isNull();
        assertThat(result.answer().dose().recordedAt()).isEqualTo(doses.rows.get(0).recordedAt());
        Mar mar = result.answer().mar();
        assertThat(mar.orders()).hasSize(1);
        SlotView slot = mar.orders().get(0).slots().get(0);
        assertThat(slot.state()).isEqualTo("GIVEN");
        assertThat(slot.canGive()).isFalse();
        assertThat(slot.dose().id()).isEqualTo(result.answer().dose().id());
        assertThat(mar.doseCount()).isZero();
    }

    @Test
    void anOwnerMayGiveToo() {
        OrderRow order = active("pcm", "od");

        DoseResult result = give(OWNER, "OWNER", order, given(EIGHT_AM));

        assertThat(result.created()).isTrue();
        assertThat(doses.inserted.get(0).recordedBy()).isEqualTo(OWNER);
    }

    @Test
    void theEventAndTheAuditRowOfAGivenDoseCarryFixedTextAndIdsOnly() {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, given(EIGHT_AM));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID", NURSE);
        Map<String, Object> after = auditAfter("IPD_DOSE_GIVEN");
        assertThat(after).containsEntry("doseId", result.answer().dose().id()).containsEntry("orderId", order.id())
                .containsEntry("admissionId", ADMISSION).containsEntry("drugKey", "pcm")
                .containsEntry("slotAt", EIGHT_AM.toString()).containsEntry("outcome", "GIVEN")
                .containsEntry("highAlert", false).containsEntry("secondCheckUserId", null);
        assertThat(after.toString()).doesNotContain("Test Patient").doesNotContain(UHID);
        verify(auditLog).record(eq("IPD_DOSE_GIVEN"), eq("IPD_DOSE"), eq(result.answer().dose().id()), isNull(), any());
    }

    @Test
    void theTimeOfADoseInTheEventIsWrittenAsTheMockupWritesIt() {
        OrderRow order = active("pcm", "tds");
        fake.now = Instant.parse("2026-11-11T08:40:00Z");

        nurseGives(order, given(TWO_PM));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Gave Tab. Paracetamol 650 mg (2 PM dose) · identity checked against UHID", NURSE);
    }

    @Test
    void theWristbandIsStoredNormalisedAndNotTheTextAsTyped() {
        OrderRow order = active("pcm", "od");

        nurseGives(order, with(given(EIGHT_AM), "  uh-000123\r\n", Boolean.TRUE, null, null, null));

        assertThat(doses.inserted.get(0).identityScan()).isEqualTo("UH-000123");
    }

    @Test
    void theLockIsTakenFirstThenTheClockIsReadThenTheDoseIsInserted() {
        OrderRow order = active("pcm", "od");

        nurseGives(order, given(EIGHT_AM));

        int lock = trace.indexOf("lock");
        int insert = trace.indexOf("insert");
        assertThat(lock).isPositive();
        assertThat(trace.subList(lock + 1, insert)).contains("clock");
        assertThat(trace.subList(0, lock)).contains("clock");
        assertThat(trace.subList(insert + 1, trace.size())).contains("clock");
        assertThat(trace.stream().filter("lock"::equals).count()).isEqualTo(1);
    }

    // ---- the roles and the state of IPD ----

    @ParameterizedTest
    @ValueSource(strings = {"DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "ADMIN"})
    void everyOtherTokenRoleIsRefusedWithTheContractText(String role) {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> give(NURSE, role, order, given(EIGHT_AM))).isInstanceOfSatisfying(
                ForbiddenException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("forbidden");
                    assertThat(e.getMessage()).isEqualTo("Only nurses and the clinic owner can give medicines.");
                });
        assertNothingWritten();
        verifyNoInteractions(staffPin, patientAccess);
    }

    @ParameterizedTest
    @NullSource
    void aMissingRoleIsRefused(String role) {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> give(NURSE, role, order, given(EIGHT_AM))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aTokenSaysNurseButTheMembershipSaysDoctorSoTheRoleReadAgainRefuses() {
        OrderRow order = active("pcm", "od");
        memberRoles.put(NURSE, "DOCTOR");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Only nurses and the clinic owner can give medicines."));
        assertNothingWritten();
    }

    @Test
    void aDeactivatedMemberOrOneWithNoMembershipIsRefused() {
        OrderRow order = active("pcm", "od");
        memberActive = false;
        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(ForbiddenException.class);

        memberActive = true;
        memberRoles.remove(NURSE);
        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(ForbiddenException.class);
        assertNothingWritten();
    }

    @Test
    void ipdOffIsRefusedWithModuleOff() {
        OrderRow order = active("pcm", "od");
        ipdState("off");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(ModuleOffException.class);
        assertNothingWritten();
    }

    @Test
    void ipdReadOnlyRefusesARecordWithIpdReadonly() {
        OrderRow order = active("pcm", "od");
        ipdState("readonly");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "ipd-readonly", "IPD is read-only. Nothing can be changed."));
        assertNothingWritten();
    }

    @Test
    void theTokenRoleIsCheckedBeforeTheModuleAndTheModuleBeforeTheBody() {
        OrderRow order = active("pcm", "od");
        ipdState("off");
        DoseInput broken = new DoseInput(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> give(NURSE, "FRONT_DESK", order, broken)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> give(NURSE, "NURSE", order, broken)).isInstanceOf(ModuleOffException.class);
        ipdState("on");
        assertThatThrownBy(() -> give(NURSE, "NURSE", order, broken)).isInstanceOf(InvalidFieldsException.class);
    }

    // ---- the body ----

    @Test
    void everyFormatErrorIsAnsweredTogetherInTheOrderClientKeySlotAtOutcome() {
        OrderRow order = active("pcm", "od");
        DoseInput broken = new DoseInput("not-a-key", "yesterday", "MAYBE", UHID, Boolean.TRUE, null, null, null);

        assertThatThrownBy(() -> nurseGives(order, broken)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
            assertThat(e.errors()).containsExactly(new FieldMessage("clientKey", "Reload the page and try again."),
                    new FieldMessage("slotAt", "Reload the page and try again."),
                    new FieldMessage("outcome", "Reload the page and try again."));
            assertThat(e.firstMessageAsDetail()).isTrue();
        });
        assertNothingWritten();
    }

    @Test
    void aNullBodyIsAFormatErrorOnAllThree() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(FieldMessage::field)
                        .containsExactly("clientKey", "slotAt", "outcome"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"given", "Given", "NOT GIVEN", "HELD", "REFUSED", ""})
    void anOutcomeOtherThanGivenOrNotGivenIsAFormatError(String outcome) {
        OrderRow order = active("pcm", "od");
        DoseInput base = given(EIGHT_AM);
        DoseInput body = new DoseInput(base.clientKey(), base.slotAt(), outcome, UHID, Boolean.TRUE, null, null, null);

        assertThatThrownBy(() -> nurseGives(order, body)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(FieldMessage::field).containsExactly("outcome"));
    }

    @Test
    void aSlotWithAnOffsetIsTheSameInstant() {
        OrderRow order = active("pcm", "od");
        DoseInput base = given(EIGHT_AM);
        DoseInput body = new DoseInput(base.clientKey(), "2026-11-11T08:00:00+05:30", "GIVEN", UHID, Boolean.TRUE, null,
                null, null);

        assertThat(nurseGives(order, body).created()).isTrue();
        assertThat(doses.inserted.get(0).slotAt()).isEqualTo(EIGHT_AM);
    }

    // ---- the order and the patient ----

    @Test
    void anOrderThatDoesNotExistIs404() {
        assertThatThrownBy(() -> service.record(TENANT, NURSE, "NURSE", UUID.randomUUID(), given(EIGHT_AM)))
                .isInstanceOf(NotFoundException.class);
        assertNothingWritten();
    }

    @Test
    void anAdmissionThatIsHiddenIs404() {
        OrderRow order = fake.seedAt(UUID.randomUUID(), "pcm", "od", "ACTIVE", DOCTOR, CHECKED_LONG_AGO);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPatientThatCannotBeFoundIs404AndNeverAPlaceholder() {
        OrderRow order = active("pcm", "od");
        patientFound = false;

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(NotFoundException.class);
        assertNothingWritten();
    }

    @Test
    void aDischargedAdmissionIsNotAdmittedSoNothingCanBeRecorded() {
        OrderRow order = active("pcm", "od");
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "not-admitted",
                "This patient is not admitted any more. Doses cannot be recorded."));
        assertNothingWritten();
    }

    @Test
    void aPendingOrderIsNotCheckedByThePharmacistYet() {
        OrderRow order = ofStatus("PENDING", null);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "order-not-verified", "This order is not checked by the pharmacist yet."));
        assertNothingWritten();
    }

    @Test
    void aStoppedOrderCannotBeGiven() {
        OrderRow order = ofStatus("STOPPED", CHECKED_LONG_AGO);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "order-state",
                "This medicine was stopped. Its doses can no longer be recorded."));
        assertNothingWritten();
    }

    @Test
    void aRejectedOrderCannotBeGiven() {
        OrderRow order = ofStatus("REJECTED", null);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "order-state",
                "This order was rejected by the pharmacist. It cannot be given."));
        assertNothingWritten();
    }

    // ---- the slot ----

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-11T03:30:00Z", "2026-11-10T02:30:00Z", "2026-11-12T02:30:00Z",
            "2026-11-11T02:30:30Z", "2026-11-11T02:31:00Z", "2026-11-11T08:30:00Z", "2026-11-11T14:30:00Z"})
    void aSlotThatIsNotADoseTimeOfThisMedicineTodayIs400OnTheSlotField(String slot) {
        OrderRow order = active("pcm", "od");
        DoseInput base = given(EIGHT_AM);
        DoseInput body = new DoseInput(base.clientKey(), slot, "GIVEN", UHID, Boolean.TRUE, null, null, null);
        fake.now = Instant.parse("2026-11-11T08:40:00Z");

        assertThatThrownBy(() -> nurseGives(order, body)).satisfies(e -> assertField(e, "slotAt",
                "This is not a dose time of this medicine today. Reload the page and try again."));
        assertNothingWritten();
    }

    @Test
    void yesterdaysTenPmSlotIsRefusedAfterMidnight() {
        OrderRow order = active("pcm", "tds");
        fake.now = Instant.parse("2026-11-11T18:40:00Z"); // 00:10 AM IST on 12 Nov

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_PM))).satisfies(e -> assertField(e, "slotAt",
                "This is not a dose time of this medicine today. Reload the page and try again."));
    }

    @Test
    void aSlotBeforeThePharmacistCheckedTheOrderIsRefused() {
        OrderRow order = fake.seedAt(ADMISSION, "pcm", "bd", "ACTIVE", DOCTOR, Instant.parse("2026-11-11T05:00:00Z"));
        fake.now = Instant.parse("2026-11-11T05:10:00Z"); // 10:40 AM IST

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "dose-before-start", "This dose time is before the pharmacist checked the order."));
        assertNothingWritten();
    }

    @Test
    void theSlotsAfterTheCheckOfTheSameOrderStillCount() {
        OrderRow order = fake.seedAt(ADMISSION, "pcm", "bd", "ACTIVE", DOCTOR, Instant.parse("2026-11-11T05:00:00Z"));
        fake.now = Instant.parse("2026-11-11T14:00:00Z"); // 7:30 PM IST, 8 PM is 30 minutes ahead

        assertThat(nurseGives(order, given(EIGHT_PM)).created()).isTrue();
    }

    @Test
    void aSlotMoreThanSixtyMinutesAheadIsNotDueYet() {
        OrderRow order = active("pcm", "od");
        fake.now = EIGHT_AM.minusSeconds(61 * 60);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "dose-not-due",
                "This dose is not due yet. It can be given from 1 hour before its time."));
        assertNothingWritten();
    }

    @Test
    void aSlotExactlySixtyMinutesAheadIsDueAndCanBeGiven() {
        OrderRow order = active("pcm", "od");
        fake.now = EIGHT_AM.minusSeconds(60 * 60);

        assertThat(nurseGives(order, given(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void aSlotSixtyMinutesAndFiftyNineSecondsAheadRoundsDownToSixtyAndIsDue() {
        OrderRow order = active("pcm", "od");
        fake.now = EIGHT_AM.minusSeconds(60 * 60 + 59);

        assertThat(nurseGives(order, given(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void aLateSlotCanStillBeGiven() {
        OrderRow order = active("pcm", "od");
        fake.now = EIGHT_AM.plusSeconds(5 * 3600);

        assertThat(nurseGives(order, given(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void aSlotThatAlreadyHasARecordIsRefusedWhoeverRecordedIt() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "HELD", "NIL_BY_MOUTH", SECOND_NURSE, null, false);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "dose-recorded", "This dose is already recorded."));
        assertNothingWritten();
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void theSameSlotCannotBeRecordedTwiceByTwoNursesWithTwoKeys() {
        OrderRow order = active("pcm", "od");
        nurseGives(order, given(EIGHT_AM));

        assertThatThrownBy(() -> give(OWNER, "OWNER", order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "dose-recorded", "This dose is already recorded."));
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void theOrderAndSlotRulesComeBeforeTheBedsideChecks() {
        OrderRow order = ofStatus("PENDING", null);
        DoseInput noBedside = with(given(EIGHT_AM), "", Boolean.FALSE, null, null, null);

        assertThatThrownBy(() -> nurseGives(order, noBedside)).satisfies(e -> assertConflict(e, "order-not-verified",
                "This order is not checked by the pharmacist yet."));
    }

    // ---- the bedside checks, in the order of AC6 ----

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\r\n", "\t"})
    void anEmptyBoxIs400OnTheWristbandField(String box) {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), box, Boolean.TRUE, null, null, null)))
                .satisfies(e -> assertField(e, "wristband", "Scan the wristband first."));
        assertNothingWritten();
    }

    @Test
    void aMissingBoxIs400OnTheWristbandField() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), null, Boolean.TRUE, null, null, null)))
                .satisfies(e -> assertField(e, "wristband", "Scan the wristband first."));
    }

    @Test
    void aWrongWristbandIs409WristbandMismatchWithNoNameInTheDetail() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), "UH-000999", Boolean.TRUE, null, null, null)))
                .satisfies(e -> {
                    assertConflict(e, "wristband-mismatch",
                            "Wristband does not match this patient. Stop. Check the patient before giving anything.");
                    assertThat(e.getMessage()).doesNotContain("Test Patient").doesNotContain(UHID)
                            .doesNotContain("UH-000999");
                });
        assertThat(doses.rows).isEmpty();
    }

    @Test
    void aMismatchWritesTheDenyEntryAndTheAuditRowAndNothingElse() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), "uh-000999\n", Boolean.TRUE, null, null, null)))
                .isInstanceOf(ConflictException.class);

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Stopped at the identity check: entered UH-000999, expected UH-000123 (Tab. Paracetamol 650 mg)",
                NURSE);
        verify(events, times(1)).insert(any(), any(), anyString(), anyString(), any());
        Map<String, Object> after = auditAfter("IPD_DOSE_WRISTBAND_MISMATCH");
        assertThat(after).containsOnlyKeys("orderId", "admissionId", "drugKey", "slotAt")
                .containsEntry("orderId", order.id()).containsEntry("admissionId", ADMISSION)
                .containsEntry("drugKey", "pcm").containsEntry("slotAt", EIGHT_AM.toString());
        verify(auditLog).record(eq("IPD_DOSE_WRISTBAND_MISMATCH"), eq("IPD_MEDICINE_ORDER"), eq(order.id()), isNull(),
                any());
        assertThat(doses.writes).isZero();
        verifyNoInteractions(staffPin, patientAccess);
    }

    @Test
    void aMismatchIsDecidedBeforeTheLockSoNoLockIsTakenForIt() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), "UH-000999", Boolean.TRUE, null, null, null)))
                .isInstanceOf(ConflictException.class);

        assertThat(trace).doesNotContain("lock");
    }

    @Test
    void aTypedNameInTheBoxNeverReachesTheEventLogOrTheAudit() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order,
                with(given(EIGHT_AM), "Ramesh Kale 9876543210", Boolean.TRUE, null, null, null)))
                .isInstanceOf(ConflictException.class);

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Stopped at the identity check: entered text that is not a UHID, expected UH-000123 "
                        + "(Tab. Paracetamol 650 mg)", NURSE);
        assertThat(auditAfter("IPD_DOSE_WRISTBAND_MISMATCH").toString()).doesNotContain("Ramesh")
                .doesNotContain("9876543210");
    }

    @Test
    void aBoxOfMoreThanTwoHundredCharactersIsAMismatchAndIsNotEchoed() {
        OrderRow order = active("pcm", "od");
        String longBox = "UH-000123" + " ".repeat(300);

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), longBox, Boolean.TRUE, null, null, null)))
                .satisfies(e -> assertConflict(e, "wristband-mismatch",
                        "Wristband does not match this patient. Stop. Check the patient before giving anything."));
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_DENY),
                org.mockito.ArgumentMatchers.contains("entered text that is not a UHID"), eq(NURSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "yes", "1", "TRUE"})
    void aBoxThatIsNotTickedIs400OnIdentityConfirmed(String value) {
        OrderRow order = active("pcm", "od");
        Object confirmed = "null".equals(value) ? null : "false".equals(value) ? Boolean.FALSE : value;

        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), UHID, confirmed, null, null, null)))
                .satisfies(e -> assertField(e, "identityConfirmed",
                        "Confirm the second identifier: name and date of birth."));
        assertNothingWritten();
    }

    @Test
    void theCheckOrderIsWristbandEmptyThenMismatchThenTheTickThenTheSecondNurse() {
        OrderRow order = active("hep", "bd");
        // everything wrong at once: the first check answers
        DoseInput all = with(given(EIGHT_AM), "", Boolean.FALSE, null, "12", null);
        assertThatThrownBy(() -> nurseGives(order, all)).satisfies(e -> assertField(e, "wristband",
                "Scan the wristband first."));

        DoseInput wrongBand = with(given(EIGHT_AM), "OTHER-00001", Boolean.FALSE, null, "12", null);
        assertThatThrownBy(() -> nurseGives(order, wrongBand)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("wristband-mismatch"));

        DoseInput unticked = with(given(EIGHT_AM), UHID, Boolean.FALSE, null, "12", null);
        assertThatThrownBy(() -> nurseGives(order, unticked)).satisfies(e -> assertField(e, "identityConfirmed",
                "Confirm the second identifier: name and date of birth."));

        DoseInput noNurse = with(given(EIGHT_AM), UHID, Boolean.TRUE, null, "12", null);
        assertThatThrownBy(() -> nurseGives(order, noNurse)).isInstanceOf(InvalidFieldsException.class);
    }

    // ---- high-alert: the second nurse ----

    @Test
    void aHighAlertDoseWithNoSecondNurseIs400OnTheNurseField() {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, null, PIN, null);

        assertThatThrownBy(() -> nurseGives(order, body)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
            assertThat(e.errors()).containsExactly(new FieldMessage("secondNurseId",
                    "High-alert medicine: a second nurse must check it and enter a 4-digit PIN."));
            assertThat(e.firstMessageAsDetail()).isTrue();
        });
        verifyNoInteractions(staffPin);
        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"482", "48211", "abcd", "48 1", "４８２１", "", "4821 "})
    void aPinThatIsNotFourAsciiDigitsIs400OnThePinField(String pin) {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, secondMembership.toString(), pin, null);

        assertThatThrownBy(() -> nurseGives(order, body)).isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                assertThat(e.errors()).containsExactly(new FieldMessage("secondNursePin",
                        "High-alert medicine: a second nurse must check it and enter a 4-digit PIN.")));
        verifyNoInteractions(staffPin);
    }

    @Test
    void aMissingPinIs400OnThePinField() {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, secondMembership.toString(), null, null);

        assertThatThrownBy(() -> nurseGives(order, body)).isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                assertThat(e.errors()).extracting(FieldMessage::field).containsExactly("secondNursePin"));
    }

    @Test
    void bothMissingGivesBothFieldErrorsWithTheOneMessage() {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, null, null, null);

        assertThatThrownBy(() -> nurseGives(order, body)).isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                assertThat(e.errors()).extracting(FieldMessage::field)
                        .containsExactly("secondNurseId", "secondNursePin"));
    }

    @Test
    void theSecondNurseBeingTheSignedInPersonIs400AndNoPinIsTried() {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, callerMembership.toString(), PIN, null);

        assertThatThrownBy(() -> nurseGives(order, body)).satisfies(e -> assertField(e, "secondNurseId",
                "The second check must be a different nurse."));
        verifyNoInteractions(staffPin);
        assertNothingWritten();
    }

    @Test
    void anOwnerCannotBeTheSecondNurse() {
        OrderRow order = active("hep", "bd");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, membership(OWNER).toString(), PIN, null);

        assertThatThrownBy(() -> nurseGives(order, body)).satisfies(e -> assertField(e, "secondNurseId",
                "Choose a nurse from the list."));
        verifyNoInteractions(staffPin);
    }

    @Test
    void aMemberOfAnotherClinicOrAnUnknownIdOrADeactivatedNurseIsNotOnTheList() {
        OrderRow order = active("hep", "bd");
        UUID stranger = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        roster.put(gone, new com.sahaj.platform.api.TeamMemberPort.TeamMember(gone, "Gone Nurse", "NURSE", false, 0L));

        for (String id : new String[] {stranger.toString(), gone.toString(), "not-a-uuid"}) {
            DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, id, PIN, null);
            assertThatThrownBy(() -> nurseGives(order, body)).satisfies(e -> assertField(e, "secondNurseId",
                    "Choose a nurse from the list."));
        }
        verifyNoInteractions(staffPin);
        assertNothingWritten();
    }

    @Test
    void theSecondNurseSPinIsCheckedThroughThePortWithTheCallerAndTheNurseRoleAndNothingElse() {
        OrderRow order = active("hep", "bd");

        nurseGives(order, givenHighAlert(EIGHT_AM));

        verify(staffPin).checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN);
        verify(staffPin, times(1)).checkColleague(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void theLockedTransactionNeverSeesThePin() {
        OrderRow order = active("hep", "bd");
        MedicineAdministration spied = org.mockito.Mockito.spy(administration);
        DoseService spiedService = new DoseService(spied, staffPin);

        spiedService.record(TENANT, NURSE, "NURSE", order.id(), givenHighAlert(EIGHT_AM));

        ArgumentCaptor<DoseInput> locked = ArgumentCaptor.forClass(DoseInput.class);
        verify(spied).recordLocked(eq(TENANT), eq(NURSE), eq("NURSE"), eq(order.id()), locked.capture(),
                eq(SECOND_NURSE_USER));
        assertThat(locked.getValue().secondNursePin()).isNull();
    }

    @Test
    void aHighAlertGivenStoresTheSecondNurseSUserIdAndTheEventNamesThem() {
        OrderRow order = active("hep", "bd");

        DoseResult result = nurseGives(order, givenHighAlert(EIGHT_AM));

        var stored = doses.inserted.get(0);
        assertThat(stored.highAlert()).isTrue();
        assertThat(stored.secondCheckBy()).isEqualTo(SECOND_NURSE_USER).isNotEqualTo(stored.recordedBy());
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Gave Inj. Heparin 5,000 IU (8 AM dose) · identity checked against UHID · second check by Sr. Rupali More", NURSE);
        Map<String, Object> after = auditAfter("IPD_DOSE_GIVEN");
        assertThat(after).containsEntry("highAlert", true).containsEntry("secondCheckUserId", SECOND_NURSE_USER);
        assertThat(after.toString()).doesNotContain(PIN).doesNotContain("Rupali");
        assertThat(result.answer().dose().secondCheckByName()).isEqualTo("Sr. Rupali More");
    }

    @Test
    void thePinIsCheckedAfterThePrepareStepAndBeforeTheLock() {
        OrderRow order = active("hep", "bd");

        nurseGives(order, givenHighAlert(EIGHT_AM));

        List<String> steps = trace.stream().filter(s -> !"clock".equals(s)).toList();
        assertThat(steps).containsExactly("pin", "lock", "insert");
        assertThat(trace.indexOf("clock")).isLessThan(trace.indexOf("pin"));
    }

    @Test
    void aMedicineThatIsNotHighAlertIgnoresTheSecondNurseAndThePin() {
        OrderRow order = active("pcm", "od");
        DoseInput body = with(given(EIGHT_AM), UHID, Boolean.TRUE, "garbage", "12", null);

        assertThat(nurseGives(order, body).created()).isTrue();

        verifyNoInteractions(staffPin);
        assertThat(doses.inserted.get(0).secondCheckBy()).isNull();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID", NURSE);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"WRONG", "LOCKED", "NOT_SET", "RATE_LIMITED", "UNAVAILABLE"})
    void everyPinRefusalStopsTheRequestBeforeTheLockAndWritesNothing(Outcome outcome) {
        OrderRow order = active("hep", "bd");
        when(staffPin.checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN)).thenReturn(
                ColleaguePinCheck.failed(new PinCheck(outcome, outcome == Outcome.WRONG ? 4 : 0,
                        outcome == Outcome.LOCKED ? Instant.parse("2026-11-11T03:00:00Z") : null,
                        outcome == Outcome.LOCKED || outcome == Outcome.RATE_LIMITED ? 600 : 0)));

        assertThatThrownBy(() -> nurseGives(order, givenHighAlert(EIGHT_AM))).isInstanceOf(StaffPinException.class);

        assertThat(trace).doesNotContain("lock", "insert");
        assertNothingWritten();
        verifyNoInteractions(patientAccess);
    }

    @Test
    void aWrongPinAnswersTheColleagueTextWithNoName() {
        OrderRow order = active("hep", "bd");
        when(staffPin.checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN)).thenReturn(
                ColleaguePinCheck.failed(new PinCheck(Outcome.WRONG, 2, null, 0)));

        assertThatThrownBy(() -> nurseGives(order, givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(
                StaffPinException.class, e -> {
                    assertThat(e.slug()).isEqualTo("wrong-pin");
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.getMessage()).isEqualTo("That PIN is not right.");
                });
    }

    @Test
    void aNotEligibleAnswerOfThePortIs400OnTheNurseField() {
        OrderRow order = active("hep", "bd");
        when(staffPin.checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN))
                .thenReturn(ColleaguePinCheck.notEligible());

        assertThatThrownBy(() -> nurseGives(order, givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(new FieldMessage(
                        "secondNurseId", "Choose a nurse from the list.")));
        assertNothingWritten();
    }

    @Test
    void thePortIsNeverCalledForAStateErrorOfTheOrderOrTheSlot() {
        OrderRow stopped = fake.seedAt(ADMISSION, "hep", "bd", "STOPPED", DOCTOR, CHECKED_LONG_AGO);
        OrderRow pending = fake.seedAt(ADMISSION, "hep", "bd", "PENDING", DOCTOR, null);
        OrderRow ok = active("hep", "bd");
        doses.seed(ADMISSION, ok.id(), EIGHT_AM, "GIVEN", null, OWNER, SECOND_NURSE, true);

        assertThatThrownBy(() -> nurseGives(stopped, givenHighAlert(EIGHT_AM))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> nurseGives(pending, givenHighAlert(EIGHT_AM))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> nurseGives(ok, givenHighAlert(EIGHT_AM))).isInstanceOf(ConflictException.class);

        verifyNoInteractions(staffPin);
    }

    // ---- not given ----

    @ParameterizedTest
    @ValueSource(strings = {"HELD_DOCTOR", "NIL_BY_MOUTH", "NOT_AVAILABLE", "AWAY_FROM_WARD"})
    void anyReasonOtherThanRefusalIsStoredAsHeld(String reason) {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, notGiven(EIGHT_AM, reason));

        assertThat(result.created()).isTrue();
        var stored = doses.inserted.get(0);
        assertThat(stored.outcome()).isEqualTo("HELD");
        assertThat(stored.reasonKey()).isEqualTo(reason);
        assertThat(stored.secondCheckBy()).isNull();
        assertThat(stored.identityScan()).isNull();
        assertThat(stored.identityConfirmed()).isNull();
        assertThat(result.answer().dose().outcome()).isEqualTo("HELD");
        assertThat(result.answer().dose().reason().key()).isEqualTo(reason);
    }

    @Test
    void thePatientRefusingIsStoredAsRefused() {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, notGiven(EIGHT_AM, "PATIENT_REFUSED"));

        assertThat(doses.inserted.get(0).outcome()).isEqualTo("REFUSED");
        assertThat(result.answer().mar().orders().get(0).slots().get(0).state()).isEqualTo("REFUSED");
    }

    @Test
    void theEventAndTheAuditRowOfANotGivenDoseCarryTheReasonLabelAndKey() {
        OrderRow order = active("pcm", "od");

        DoseResult result = nurseGives(order, notGiven(EIGHT_AM, "NIL_BY_MOUTH"));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Did not give Tab. Paracetamol 650 mg (8 AM dose) · Nil by mouth", NURSE);
        Map<String, Object> after = auditAfter("IPD_DOSE_NOT_GIVEN");
        assertThat(after).containsEntry("doseId", result.answer().dose().id()).containsEntry("orderId", order.id())
                .containsEntry("admissionId", ADMISSION).containsEntry("drugKey", "pcm")
                .containsEntry("slotAt", EIGHT_AM.toString()).containsEntry("outcome", "HELD")
                .containsEntry("reasonKey", "NIL_BY_MOUTH");
        verify(auditLog, never()).record(eq("IPD_DOSE_GIVEN"), anyString(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER", "patient_refused", " "})
    void aMissingOrUnknownReasonIs400OnTheReasonField(String reason) {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, notGiven(EIGHT_AM, reason))).satisfies(e -> assertField(e,
                "reasonKey", "Choose why the dose is not given."));
        assertNothingWritten();
    }

    @Test
    void aNullReasonIs400OnTheReasonField() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, notGiven(EIGHT_AM, null))).satisfies(e -> assertField(e,
                "reasonKey", "Choose why the dose is not given."));
    }

    @Test
    void notGivenNeedsNeitherTheWristbandNorTheTickNorTheSecondNurse() {
        OrderRow order = active("hep", "bd");
        DoseInput body = new DoseInput(UUID.randomUUID().toString(), EIGHT_AM.toString(), "NOT_GIVEN", "wrong band",
                Boolean.FALSE, "garbage", "1", "AWAY_FROM_WARD");

        DoseResult result = nurseGives(order, body);

        assertThat(result.created()).isTrue();
        assertThat(doses.inserted.get(0).secondCheckBy()).isNull();
        assertThat(doses.inserted.get(0).highAlert()).isTrue();
        verifyNoInteractions(staffPin);
        verify(events, never()).insert(any(), any(), eq(ClinicalEventRepository.KIND_DENY), anyString(), any());
    }

    @Test
    void notGivenOfAHighAlertMedicineNeedsNoPin() {
        OrderRow order = active("ins", "tds");

        assertThat(nurseGives(order, notGiven(EIGHT_AM, "PATIENT_REFUSED")).created()).isTrue();

        verify(staffPin, never()).checkColleague(any(), any(), any(), anyString(), any());
    }

    @Test
    void notGivenIsRefusedForTheSameStateErrorsAsGiven() {
        OrderRow stopped = ofStatus("STOPPED", CHECKED_LONG_AGO);
        OrderRow ahead = active("pcm", "od");
        fake.now = EIGHT_AM.minusSeconds(3 * 3600);

        assertThatThrownBy(() -> nurseGives(stopped, notGiven(EIGHT_AM, "NIL_BY_MOUTH")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> nurseGives(ahead, notGiven(EIGHT_AM, "NIL_BY_MOUTH")))
                .satisfies(e -> assertConflict(e, "dose-not-due",
                        "This dose is not due yet. It can be given from 1 hour before its time."));
    }

    // ---- a repeat of the client key ----

    @Test
    void aRepeatOfTheSameRequestAnswers200WithTheStoredDoseAndWritesOnlyTheAccessRow() {
        OrderRow order = active("pcm", "od");
        UUID key = UUID.randomUUID();
        DoseResult first = nurseGives(order, givenWithKey(key, EIGHT_AM));
        org.mockito.Mockito.clearInvocations(events, auditLog, patientAccess);
        int writes = doses.writes;

        DoseResult again = nurseGives(order, givenWithKey(key, EIGHT_AM));

        assertThat(again.created()).isFalse();
        assertThat(again.answer().replayed()).isTrue();
        assertThat(again.answer().dose().id()).isEqualTo(first.answer().dose().id());
        assertThat(again.answer().mar().orders().get(0).slots().get(0).state()).isEqualTo("GIVEN");
        assertThat(doses.writes).isEqualTo(writes);
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, NURSE);
    }

    @Test
    void aFirstRecordWritesNoAccessRowBecauseItsAuditRecordsIt() {
        OrderRow order = active("pcm", "od");

        nurseGives(order, given(EIGHT_AM));

        verifyNoInteractions(patientAccess);
    }

    @Test
    void aRepeatOfAHighAlertDoseWithTheSameSecondNurseChecksNoPin() {
        OrderRow order = active("hep", "bd");
        UUID key = UUID.randomUUID();
        DoseInput body = new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null);
        nurseGives(order, body);
        org.mockito.Mockito.clearInvocations(staffPin);

        DoseResult again = nurseGives(order, body);

        assertThat(again.answer().replayed()).isTrue();
        verifyNoInteractions(staffPin);
    }

    @Test
    void aRepeatEvenWithABadPinOrNoWristbandIsStillAReplay() {
        OrderRow order = active("hep", "bd");
        UUID key = UUID.randomUUID();
        nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null));

        DoseResult again = nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", "", null,
                secondMembership.toString(), "x", null));

        assertThat(again.answer().replayed()).isTrue();
    }

    @Test
    void aRepeatWithAnotherSecondNurseIsAReusedKey() {
        OrderRow order = active("hep", "bd");
        UUID other = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        roster.put(other, new com.sahaj.platform.api.TeamMemberPort.TeamMember(other, "Br. Nikhil Jadhav", "NURSE", true,
                0L));
        UUID key = UUID.randomUUID();
        nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null));

        assertThatThrownBy(() -> nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID,
                Boolean.TRUE, other.toString(), PIN, null))).satisfies(e -> assertConflict(e, "client-key-reused",
                "This dose was already recorded with different values. Reload the page."));
        assertThat(otherUser).isNotNull();
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void aRepeatWithNoSecondNurseOfAHighAlertDoseIsAReusedKey() {
        OrderRow order = active("hep", "bd");
        UUID key = UUID.randomUUID();
        nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null));

        assertThatThrownBy(() -> nurseGives(order, new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID,
                Boolean.TRUE, null, null, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void aRepeatWithAnotherSlotIsAReusedKey() {
        OrderRow order = active("pcm", "bd");
        UUID key = UUID.randomUUID();
        fake.now = Instant.parse("2026-11-11T08:00:00Z");
        nurseGives(order, givenWithKey(key, EIGHT_AM));

        assertThatThrownBy(() -> nurseGives(order, givenWithKey(key, EIGHT_PM))).satisfies(e -> assertConflict(e,
                "client-key-reused", "This dose was already recorded with different values. Reload the page."));
    }

    @Test
    void aRepeatThatSwitchesBetweenGivenAndNotGivenIsAReusedKey() {
        OrderRow order = active("pcm", "od");
        UUID key = UUID.randomUUID();
        nurseGives(order, givenWithKey(key, EIGHT_AM));
        DoseInput notGiven = new DoseInput(key.toString(), EIGHT_AM.toString(), "NOT_GIVEN", null, null, null, null,
                "PATIENT_REFUSED");

        assertThatThrownBy(() -> nurseGives(order, notGiven)).satisfies(e -> assertConflict(e, "client-key-reused",
                "This dose was already recorded with different values. Reload the page."));
    }

    @Test
    void aRepeatOfNotGivenWithTheSameReasonIsAReplayAndWithAnotherReasonIsAReusedKey() {
        OrderRow order = active("pcm", "od");
        UUID key = UUID.randomUUID();
        DoseInput first = new DoseInput(key.toString(), EIGHT_AM.toString(), "NOT_GIVEN", null, null, null, null,
                "NIL_BY_MOUTH");
        nurseGives(order, first);

        assertThat(nurseGives(order, first).answer().replayed()).isTrue();
        DoseInput other = new DoseInput(key.toString(), EIGHT_AM.toString(), "NOT_GIVEN", null, null, null, null,
                "NOT_AVAILABLE");
        assertThatThrownBy(() -> nurseGives(order, other)).isInstanceOf(ConflictException.class);
        DoseInput none = new DoseInput(key.toString(), EIGHT_AM.toString(), "NOT_GIVEN", null, null, null, null, null);
        assertThatThrownBy(() -> nurseGives(order, none)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aKeyOfAnotherOrderIs400OnTheClientKeyField() {
        OrderRow order = active("pcm", "od");
        OrderRow other = active("panto", "od");
        UUID key = UUID.randomUUID();
        nurseGives(order, givenWithKey(key, EIGHT_AM));

        assertThatThrownBy(() -> nurseGives(other, givenWithKey(key, EIGHT_AM))).satisfies(e -> assertField(e,
                "clientKey", "Reload the page and try again."));
    }

    @Test
    void aRepeatIsStillAReplayAfterTheOrderWasStoppedOrThePatientDischarged() {
        OrderRow order = active("pcm", "od");
        UUID key = UUID.randomUUID();
        nurseGives(order, givenWithKey(key, EIGHT_AM));
        fake.markStopped(TENANT, order.id(), DOCTOR);
        admission = admissionRow("DISCHARGED");

        DoseResult again = nurseGives(order, givenWithKey(key, EIGHT_AM));

        assertThat(again.answer().replayed()).isTrue();
        assertThat(again.created()).isFalse();
    }

    @Test
    void aRepeatInReadOnlyStateIsRefusedByTheStateCheckBeforeTheReplay() {
        OrderRow order = active("pcm", "od");
        UUID key = UUID.randomUUID();
        nurseGives(order, givenWithKey(key, EIGHT_AM));
        ipdState("readonly");

        assertThatThrownBy(() -> nurseGives(order, givenWithKey(key, EIGHT_AM))).satisfies(e -> assertConflict(e,
                "ipd-readonly", "IPD is read-only. Nothing can be changed."));
    }

    // ---- the database as the last defence ----

    @Test
    void aDatabaseRefusalOnTheInsertIsACodeFaultWithNoEventAndNoAuditRow() {
        OrderRow order = active("pcm", "od");
        doses.failWith = new DuplicateKeyException("uq_dose_administration_order_slot");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(IllegalStateException.class)
                .hasMessage("The dose could not be stored");

        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aCheckOrGuardRefusalOfTheDatabaseIsNotSwallowedEither() {
        OrderRow order = active("pcm", "od");
        doses.failWith = new DataIntegrityViolationException("ck_dose_administration_reason_by_outcome");

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- nothing personal in the toString of what travels ----

    @Test
    void theRequestAndTheResultsHideTheWristbandThePinAndTheNames() {
        OrderRow order = active("hep", "bd");
        DoseInput body = givenHighAlert(EIGHT_AM);

        DoseResult result = nurseGives(order, body);

        assertThat(body.toString()).doesNotContain(PIN).doesNotContain(UHID);
        assertThat(result.toString()).doesNotContain("Rupali").doesNotContain("Anjali").doesNotContain("Test Patient")
                .doesNotContain(UHID);
        assertThat(result.answer().toString()).doesNotContain("Rupali").doesNotContain("Test Patient");
        assertThat(result.answer().mar().toString()).doesNotContain("Test Patient").doesNotContain(UHID);
        assertThat(result.answer().dose().toString()).doesNotContain("Rupali").doesNotContain("Anjali");
        assertThat(doses.inserted.get(0).toString()).doesNotContain(UHID).doesNotContain("scan");
    }
}
