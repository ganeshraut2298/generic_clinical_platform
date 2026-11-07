package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseService.DoseResult;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-32 the locked transaction repeats every check (ADR-011 D6): the second nurse's PIN check takes about a quarter of
 * a second, and anything may change meanwhile. Each test changes the world inside the PIN check and expects the answer
 * of the rule, with nothing stored and the PIN already counted.
 */
class DoseServiceLockedTest extends DoseTestBase {

    private OrderRow order;

    /** Runs {@code change} while the PIN is being checked, then accepts the PIN. */
    private void duringPin(Runnable change) {
        order = active("hep", "bd");
        when(staffPin.checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN)).thenAnswer(call -> {
            trace.add("pin");
            change.run();
            return ColleaguePinCheck.ok(SECOND_NURSE_USER);
        });
    }

    private DoseResult highAlert(DoseInput input) {
        return service.record(TENANT, NURSE, "NURSE", order.id(), input);
    }

    private void assertNothingStored() {
        assertThat(doses.writes).isZero();
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    private void assertPinCheckedOnce() {
        verify(staffPin, times(1)).checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN);
    }

    @Test
    void theOrderStoppedWhileThePinWasCheckedIsRefusedAndThePinStaysCounted() {
        duringPin(() -> fake.markStopped(TENANT, order.id(), DOCTOR));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                e -> {
                    assertThat(e.problemType()).isEqualTo("order-state");
                    assertThat(e.getMessage()).isEqualTo("This medicine was stopped. Its doses can no longer be recorded.");
                });

        assertPinCheckedOnce();
        assertNothingStored();
        assertThat(trace).contains("lock");
    }

    @Test
    void thePatientDischargedWhileThePinWasCheckedIsNotAdmitted() {
        duringPin(() -> admission = admissionRow("DISCHARGED"));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.problemType()).isEqualTo("not-admitted"));

        assertPinCheckedOnce();
        assertNothingStored();
    }

    @Test
    void theSameSlotRecordedByAnotherNurseWhileThePinWasCheckedIsDoseRecorded() {
        duringPin(() -> doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, OWNER, SECOND_NURSE, true));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                e -> {
                    assertThat(e.problemType()).isEqualTo("dose-recorded");
                    assertThat(e.getMessage()).isEqualTo("This dose is already recorded.");
                });

        assertPinCheckedOnce();
        assertNothingStored();
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void theSameKeyRecordedByARetryWhileThePinWasCheckedIsAReplayNotASecondRecord() {
        UUID key = UUID.randomUUID();
        duringPin(() -> doses.rows.add(new DoseRepository.DoseRow(UUID.randomUUID(), ADMISSION, order.id(), key,
                EIGHT_AM, "GIVEN", null, NURSE, SECOND_NURSE, true, CLOCK)));
        DoseInput body = new DoseInput(key.toString(), EIGHT_AM.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null);

        DoseResult result = highAlert(body);

        assertThat(result.created()).isFalse();
        assertThat(result.answer().replayed()).isTrue();
        assertThat(doses.writes).isZero();
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, NURSE);
        assertPinCheckedOnce();
    }

    @Test
    void theCallersRoleChangedWhileThePinWasCheckedIsRefused() {
        duringPin(() -> memberRoles.put(NURSE, "FRONT_DESK"));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Only nurses and the clinic owner can give medicines."));

        assertNothingStored();
    }

    @Test
    void theCallerDeactivatedWhileThePinWasCheckedIsRefused() {
        duringPin(() -> memberActive = false);

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOf(ForbiddenException.class);
        assertNothingStored();
    }

    @Test
    void ipdSwitchedToReadOnlyWhileThePinWasCheckedIsRefused() {
        duringPin(() -> ipdState("readonly"));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        assertNothingStored();
    }

    @Test
    void ipdSwitchedOffWhileThePinWasCheckedIsRefused() {
        duringPin(() -> ipdState("off"));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOf(ModuleOffException.class);
        assertNothingStored();
    }

    @Test
    void theSecondNurseDeactivatedWhileThePinWasCheckedIsNotOnTheListAnyMore() {
        duringPin(() -> roster.put(secondMembership, new TeamMember(secondMembership, "Sr. Rupali More", "NURSE", false,
                0L)));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(
                InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("secondNurseId");
                    assertThat(e.getMessage()).isEqualTo("Choose a nurse from the list.");
                });
        assertNothingStored();
    }

    @Test
    void theSecondNurseChangedToAnotherRoleWhileThePinWasCheckedIsNotOnTheListAnyMore() {
        duringPin(() -> roster.put(secondMembership, new TeamMember(secondMembership, "Sr. Rupali More", "PHARMACIST",
                true, 0L)));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOf(InvalidInputException.class);
        assertNothingStored();
    }

    @Test
    void aPinCheckThatNamesTheCallerAsTheSecondNurseIsRefusedByTheLockedStep() {
        duringPin(() -> { });
        when(staffPin.checkColleague(TENANT, NURSE, secondMembership, "NURSE", PIN))
                .thenReturn(ColleaguePinCheck.ok(NURSE));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(
                InvalidInputException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("The second check must be a different nurse."));
        assertNothingStored();
    }

    @Test
    void theWristbandChangedByAMergeWhileThePinWasCheckedWritesTheDenyEntryInsteadOfTheDose() {
        duringPin(() -> when(patients.find(eq(TENANT), eq(PATIENT))).thenReturn(Optional.of(
                new PatientClinical(PATIENT, "UH-000777", "Test Patient", 54, "F", "None known"))));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                e -> {
                    assertThat(e.problemType()).isEqualTo("wristband-mismatch");
                    assertThat(e.getMessage()).doesNotContain("UH-000");
                });

        assertThat(doses.writes).isZero();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Stopped at the identity check: entered UH-000123, expected UH-000777 (Inj. Heparin 5,000 IU)",
                NURSE);
        verify(auditLog).record(eq("IPD_DOSE_WRISTBAND_MISMATCH"), eq("IPD_MEDICINE_ORDER"), eq(order.id()), any(),
                any());
        assertPinCheckedOnce();
    }

    @Test
    void theClockPastMidnightWhileThePinWasCheckedMakesTheSlotNotOfTodayAnyMore() {
        duringPin(() -> fake.now = Instant.parse("2026-11-11T18:31:00Z"));
        fake.now = Instant.parse("2026-11-11T18:20:00Z"); // 11:50 PM IST: the 8 PM slot is LATE, not yet over

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_PM))).isInstanceOfSatisfying(
                InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("slotAt");
                    assertThat(e.getMessage()).isEqualTo(
                            "This is not a dose time of this medicine today. Reload the page and try again.");
                });
        assertNothingStored();
    }

    @Test
    void theAdmissionRowThatVanishedBeforeTheLockIs404() {
        duringPin(() -> when(admissions.findForNoKeyUpdate(eq(TENANT), eq(ADMISSION))).thenReturn(Optional.empty()));

        assertThatThrownBy(() -> highAlert(givenHighAlert(EIGHT_AM))).isInstanceOf(NotFoundException.class);
        assertNothingStored();
    }

    @Test
    void whenNothingChangesTheDoseIsRecordedUnderTheLockAndTheClockOfTheLock() {
        duringPin(() -> fake.now = EIGHT_AM.plusSeconds(60 * 60 + 5));

        DoseResult result = highAlert(givenHighAlert(EIGHT_AM));

        // the clock moved to 61 minutes late while the PIN was checked: still recordable (late), and the answer's MAR
        // is built from the clock after the write
        assertThat(result.created()).isTrue();
        assertThat(result.answer().mar().asOf()).isEqualTo(fake.now);
    }
}
