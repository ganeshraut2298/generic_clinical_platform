package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahaj.ipd.application.MarViews.CorrectionInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-5 undo and "recorded in error" (ADR-027 D3): the recorder and the window, the IST day, the roles, the lock, the
 * replay, the append-only correction row, the review item that closes, the event and the audit row. In-memory fakes.
 */
class DoseCorrectionServiceTest extends DoseTestBase {

    private OrderRow order;
    private UUID doseId;

    @BeforeEach
    void givenOneDose() {
        order = active("pcm", "od");
        doses.recordedAt = CLOCK.minusSeconds(1);
        DoseRecorded given = service.record(TENANT, NURSE, "NURSE", order.id(), given(EIGHT_AM)).answer();
        doseId = given.dose().id();
        trace.clear();
        fake.now = CLOCK.plusSeconds(5);
    }

    private static CorrectionInput undoKey(UUID key) {
        return new CorrectionInput(key.toString(), null);
    }

    private static CorrectionInput errorKey(UUID key, String reason) {
        return new CorrectionInput(key.toString(), reason);
    }

    private DoseRecorded undo(UUID actor, String role, CorrectionInput input) {
        return corrections.undo(TENANT, actor, role, doseId, input);
    }

    private DoseRecorded error(UUID actor, String role, CorrectionInput input) {
        return corrections.recordedInError(TENANT, actor, role, doseId, input);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), isNull(), after.capture());
        return after.getValue();
    }

    private static void assertConflict(Throwable e, String type, String message) {
        assertThat(e).isInstanceOfSatisfying(ConflictException.class, c -> {
            assertThat(c.problemType()).isEqualTo(type);
            assertThat(c.getMessage()).isEqualTo(message);
        });
    }

    private void assertNothingCorrected() {
        assertThat(doses.corrections).isEmpty();
        verify(reviews, never()).closeForDose(any(), any(), any(), any());
    }

    // ---- undo ----

    @Test
    void theRecorderUndoesWithinTheWindowUnderTheAdmissionLockAndTheSlotIsDueAgain() {
        DoseRecorded answer = undo(NURSE, "NURSE", undoKey(UUID.randomUUID()));

        assertThat(answer.replayed()).isFalse();
        assertThat(answer.dose().correction().kind()).isEqualTo("UNDO");
        assertThat(answer.dose().correction().reason()).isNull();
        assertThat(answer.dose().correction().correctedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(answer.dose().canUndo()).isFalse();
        assertThat(answer.dose().undoSecondsLeft()).isZero();
        assertThat(trace).containsSubsequence("lock", "correction");
        assertThat(doses.corrections).hasSize(1);
        assertThat(doses.corrections.get(0).kind()).isEqualTo("UNDO");
        var slot = answer.mar().orders().get(0).slots().get(0);
        assertThat(slot.state()).isEqualTo("DUE");
        assertThat(slot.dose()).as("an undone record is not shown on the MAR").isNull();
        assertThat(slot.history()).isEmpty();
        assertThat(answer.mar().counters().given()).isZero();
        verify(reviews).closeForDose(eq(TENANT), eq(doseId), eq(ReviewOutcome.DOSE_UNDONE), eq(NURSE));
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Undid the dose of Tab. Paracetamol 650 mg", NURSE);
        assertThat(auditAfter("IPD_DOSE_UNDONE")).containsEntry("doseId", doseId).containsEntry("orderId", order.id())
                .containsEntry("drugKey", "pcm").containsEntry("orderVersion", 1).containsKey("device")
                .doesNotContainKey("reasonKey");
    }

    @Test
    void theSameKeyAgainIsAReplayThatWritesNothing() {
        UUID key = UUID.randomUUID();
        undo(NURSE, "NURSE", undoKey(key));
        int writes = doses.writes;

        DoseRecorded again = undo(NURSE, "NURSE", undoKey(key));

        assertThat(again.replayed()).isTrue();
        assertThat(doses.writes).isEqualTo(writes);
        assertThat(doses.corrections).hasSize(1);
    }

    @Test
    void anotherNurseCannotUndo() {
        assertThatThrownBy(() -> undo(SECOND_NURSE, "NURSE", undoKey(UUID.randomUUID()))).isInstanceOfSatisfying(
                ForbiddenException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Only the nurse who recorded this dose can undo it."));
        assertNothingCorrected();
    }

    @Test
    void afterTheWindowUndoIsExpired() {
        fake.now = CLOCK.plusSeconds(11);

        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).satisfies(e -> assertConflict(e,
                "undo-expired", "The time to undo has passed. Mark it as recorded in error instead."));
        assertNothingCorrected();
    }

    @Test
    void aDoseThatIsNotGivenOrIsAlreadyCorrectedCannotBeUndone() {
        OrderRow other = active("ns", "od");
        UUID notGivenId = service.record(TENANT, NURSE, "NURSE", other.id(), notGiven(EIGHT_AM,
                "NIL_BY_MOUTH")).answer().dose().id();

        assertThatThrownBy(() -> corrections.undo(TENANT, NURSE, "NURSE", notGivenId, undoKey(UUID.randomUUID())))
                .satisfies(e -> assertConflict(e, "dose-state", "This dose cannot be undone."));
        undo(NURSE, "NURSE", undoKey(UUID.randomUUID()));
        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).satisfies(e -> assertConflict(e,
                "dose-state", "This dose cannot be undone."));
    }

    @Test
    void aDoseFromBeforeSah76HasNoUndoWindow() {
        DoseRepository.DoseRow old = doses.seed(ADMISSION, order.id(), TWO_PM, "GIVEN", null, NURSE, null, false);

        assertThatThrownBy(() -> corrections.undo(TENANT, NURSE, "NURSE", old.id(), undoKey(UUID.randomUUID())))
                .satisfies(e -> assertConflict(e, "undo-expired",
                        "The time to undo has passed. Mark it as recorded in error instead."));
    }

    @Test
    void theDoctorAndOtherRolesCannotCorrect() {
        assertThatThrownBy(() -> undo(DOCTOR, "DOCTOR", undoKey(UUID.randomUUID()))).isInstanceOfSatisfying(
                ForbiddenException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Only nurses and the clinic owner can give medicines."));
        assertThatThrownBy(() -> error(DOCTOR, "DOCTOR", errorKey(UUID.randomUUID(), "DUPLICATE")))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Only nurses and the clinic owner can correct a dose."));
        assertThatThrownBy(() -> error(PHARMACIST, "PHARMACIST", errorKey(UUID.randomUUID(), "DUPLICATE")))
                .isInstanceOf(ForbiddenException.class);
        assertNothingCorrected();
    }

    @Test
    void aTokenRoleThatIsNoLongerTheMembershipRoleIsRefused() {
        memberRoles.put(NURSE, "BILLING");

        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).isInstanceOf(ForbiddenException.class);
        assertNothingCorrected();
    }

    @Test
    void readOnlyIpdRefusesBothCorrections() {
        ipdState("readonly");

        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).satisfies(e -> assertConflict(e,
                "ipd-readonly", "IPD is read-only. Nothing can be changed."));
        assertThatThrownBy(() -> error(NURSE, "NURSE", errorKey(UUID.randomUUID(), "DUPLICATE")))
                .isInstanceOf(ConflictException.class);
        ipdState("off");
        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void aBadKeyIs400AndAnUnknownDoseIs404() {
        assertThatThrownBy(() -> undo(NURSE, "NURSE", new CorrectionInput("1-1-1-1-1", null))).isInstanceOfSatisfying(
                InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("clientKey"));
        assertThatThrownBy(() -> corrections.undo(TENANT, NURSE, "NURSE", UUID.randomUUID(), undoKey(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDischargedAdmissionIsNotAdmitted() {
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).satisfies(e -> assertConflict(e,
                "not-admitted", "This patient is not admitted any more. Doses cannot be recorded."));
        assertNothingCorrected();
    }

    @Test
    void aKeyUsedForAnotherDoseOrKindIsReused() {
        UUID key = UUID.randomUUID();
        error(NURSE, "NURSE", errorKey(key, "DUPLICATE"));

        assertThatThrownBy(() -> corrections.undo(TENANT, NURSE, "NURSE", doseId, undoKey(key))).satisfies(
                e -> assertConflict(e, "client-key-reused",
                        "This dose was already recorded with different values. Reload the page."));
        assertThatThrownBy(() -> error(NURSE, "NURSE", errorKey(key, "WRONG_TIME"))).isInstanceOf(ConflictException.class);
    }

    // ---- recorded in error ----

    @Test
    void anotherNurseMarksTheDoseInErrorAndTheOriginalStaysStruckThrough() {
        DoseRecorded answer = error(SECOND_NURSE, "NURSE", errorKey(UUID.randomUUID(), "WRONG_PATIENT"));

        assertThat(answer.dose().correction().kind()).isEqualTo("IN_ERROR");
        assertThat(answer.dose().correction().reason().label()).isEqualTo("Documented on the wrong patient");
        assertThat(answer.dose().correction().correctedByName()).isEqualTo("Sr. Rupali More");
        assertThat(answer.dose().canMarkInError()).isFalse();
        var slot = answer.mar().orders().get(0).slots().get(0);
        assertThat(slot.state()).isEqualTo("DUE");
        assertThat(slot.dose()).isNull();
        assertThat(slot.history()).hasSize(1);
        assertThat(slot.history().get(0).correction().kind()).isEqualTo("IN_ERROR");
        verify(reviews).closeForDose(eq(TENANT), eq(doseId), eq(ReviewOutcome.DOSE_UNDONE), eq(SECOND_NURSE));
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Marked the dose of Tab. Paracetamol 650 mg recorded in error: Documented on the wrong patient",
                SECOND_NURSE);
        assertThat(auditAfter("IPD_DOSE_RECORDED_IN_ERROR")).containsEntry("reasonKey", "WRONG_PATIENT")
                .containsEntry("doseId", doseId).containsKey("device");

    }

    @Test
    void theOwnerCanMarkInErrorAfterTheUndoWindow() {
        fake.now = CLOCK.plusSeconds(600);

        DoseRecorded answer = error(OWNER, "OWNER", errorKey(UUID.randomUUID(), "WRONG_TIME"));

        assertThat(answer.dose().correction().correctedByName()).isEqualTo("Owner Kulkarni");
    }

    @Test
    void aMissingOrUnknownReasonIs400OnTheReasonField() {
        for (String reason : new String[] {null, "", "duplicate", "OTHER"}) {
            assertThatThrownBy(() -> error(NURSE, "NURSE", errorKey(UUID.randomUUID(), reason))).isInstanceOfSatisfying(
                    InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("reasonKey");
                        assertThat(e.getMessage()).isEqualTo("Choose why it is in error.");
                    });
        }
        assertNothingCorrected();
    }

    @Test
    void afterTheIstDayOfTheSlotTheMarkIsRefused() {
        fake.now = Instant.parse("2026-11-11T19:00:00Z"); // 12:30 AM IST on 12 Nov

        assertThatThrownBy(() -> error(NURSE, "NURSE", errorKey(UUID.randomUUID(), "DUPLICATE"))).satisfies(
                e -> assertConflict(e, "error-window-closed", "This dose is from an earlier day and cannot be changed."));
        assertNothingCorrected();
    }

    @Test
    void aNotGivenDoseCannotBeMarkedInError() {
        OrderRow other = active("ns", "od");
        UUID notGivenId = service.record(TENANT, NURSE, "NURSE", other.id(), notGiven(EIGHT_AM,
                "NIL_BY_MOUTH")).answer().dose().id();

        assertThatThrownBy(() -> corrections.recordedInError(TENANT, NURSE, "NURSE", notGivenId,
                errorKey(UUID.randomUUID(), "DUPLICATE"))).satisfies(e -> assertConflict(e, "dose-state",
                        "This dose cannot be marked in error."));
    }

    @Test
    void aSlotCorrectedByUndoCanBeGivenAgainAndTheNewRecordLinksToIt() {
        undo(NURSE, "NURSE", undoKey(UUID.randomUUID()));
        fake.now = CLOCK.plusSeconds(60);

        var again = service.record(TENANT, NURSE, "NURSE", order.id(), given(EIGHT_AM));

        assertThat(again.created()).isTrue();
        assertThat(doses.inserted.get(1).attempt()).isEqualTo(2);
        assertThat(doses.inserted.get(1).supersedesDoseId()).isEqualTo(doseId);
    }

    // ---- SAH-34 patient scope (refusal tests named by IpdScopeCoverageTest) ----

    private void leaveTheScope() {
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), any(),
                anyString(), anyString(), eq(ADMISSION), anyString());
    }

    @Test
    void undoOutsideScopeWritesNothing() {
        leaveTheScope();

        assertThatThrownBy(() -> undo(NURSE, "NURSE", undoKey(UUID.randomUUID()))).isInstanceOf(OutsideScopeException.class);

        assertNothingCorrected();
        verify(events, never()).insert(any(), any(), eq(ClinicalEventRepository.KIND_GIVE), eq("Undid the dose of Tab. "
                + "Paracetamol 650 mg"), any());
    }

    @Test
    void recordedInErrorOutsideScopeWritesNothing() {
        leaveTheScope();

        assertThatThrownBy(() -> error(SECOND_NURSE, "NURSE", errorKey(UUID.randomUUID(), "DUPLICATE")))
                .isInstanceOf(OutsideScopeException.class);

        assertNothingCorrected();
        verify(auditLog, never()).record(eq("IPD_DOSE_RECORDED_IN_ERROR"), anyString(), any(), any(), any());
    }
}
