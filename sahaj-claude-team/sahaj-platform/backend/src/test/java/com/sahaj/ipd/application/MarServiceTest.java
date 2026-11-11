package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.MarOrderView;
import com.sahaj.ipd.application.MarViews.SecondNurseOption;
import com.sahaj.ipd.application.MarViews.SlotView;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SAH-32 the Medicines (MAR) tab: who may read it, what it lists, every slot state against the database clock, the
 * "Checked by" list, the tab count, the lock reasons, and the record-access row. No database.
 */
class MarServiceTest extends DoseTestBase {

    private Mar mar(UUID actor, String role) {
        return marService.mar(TENANT, actor, role, ADMISSION);
    }

    private Mar nurseMar() {
        return mar(NURSE, "NURSE");
    }

    private static SlotView slot(Mar mar, int order, int slot) {
        return mar.orders().get(order).slots().get(slot);
    }

    // ---- who may read ----

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "NURSE", "DOCTOR"})
    void ownerNurseAndDoctorMayRead(String role) {
        UUID user = "OWNER".equals(role) ? OWNER : "NURSE".equals(role) ? NURSE : DOCTOR;
        active("pcm", "od");

        assertThat(mar(user, role).orders()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "ADMIN"})
    void everyOtherRoleIsRefusedAndNothingIsWritten(String role) {
        active("pcm", "od");

        assertThatThrownBy(() -> mar(NURSE, role)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(patientAccess);
    }

    @Test
    void aMissingRoleIsRefused() {
        assertThatThrownBy(() -> mar(NURSE, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theRoleOfTheMembershipIsReadAgain() {
        memberRoles.put(NURSE, "FRONT_DESK");
        assertThatThrownBy(() -> nurseMar()).isInstanceOf(ForbiddenException.class);

        memberRoles.put(NURSE, "NURSE");
        memberActive = false;
        assertThatThrownBy(() -> nurseMar()).isInstanceOf(ForbiddenException.class);

        memberActive = true;
        memberRoles.remove(NURSE);
        assertThatThrownBy(() -> nurseMar()).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(patientAccess);
    }

    @Test
    void ipdOffIsRefusedWithModuleOff() {
        ipdState("off");

        assertThatThrownBy(() -> nurseMar()).isInstanceOf(ModuleOffException.class);
        verifyNoInteractions(patientAccess);
    }

    @Test
    void anAdmissionOfAnotherClinicOrAMissingPatientIs404AndWritesNothing() {
        assertThatThrownBy(() -> marService.mar(TENANT, NURSE, "NURSE", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        patientFound = false;
        assertThatThrownBy(() -> nurseMar()).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(patientAccess);
    }

    // ---- the record-access row ----

    @Test
    void openingTheMarWritesOneRecordAccessRowAndNoEventAndNoAudit() {
        active("pcm", "od");

        nurseMar();

        verify(patientAccess).recordOpened(TENANT, PATIENT, NURSE);
        verify(events, never()).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
        assertThat(doses.writes).isZero();
    }

    @Test
    void everyOpeningWritesItsOwnRow() {
        nurseMar();
        nurseMar();

        verify(patientAccess, org.mockito.Mockito.times(2)).recordOpened(TENANT, PATIENT, NURSE);
    }

    // ---- the identity bar and the header ----

    @Test
    void theMarHoldsTheIdentityBarTheStateAndTheDatabaseClock() {
        Mar mar = nurseMar();

        assertThat(mar.admissionId()).isEqualTo(ADMISSION);
        assertThat(mar.status()).isEqualTo("ADMITTED");
        assertThat(mar.state()).isEqualTo("on");
        assertThat(mar.asOf()).isEqualTo(CLOCK);
        assertThat(mar.patient().patientId()).isEqualTo(PATIENT);
        assertThat(mar.patient().uhid()).isEqualTo(UHID);
        assertThat(mar.patient().bedCode()).isEqualTo("202");
        assertThat(mar.patient().age()).isEqualTo(54);
        assertThat(mar.patient().sex()).isEqualTo("F");
        assertThat(mar.patient().allergyText()).isEqualTo("None known");
        assertThat(mar.patient().allergyStatus()).isEqualTo("NONE_KNOWN");
        assertThat(mar.notGivenReasons()).extracting(k -> k.key()).containsExactly("PATIENT_REFUSED", "HELD_DOCTOR",
                "NIL_BY_MOUTH", "NOT_AVAILABLE", "AWAY_FROM_WARD");
        assertThat(mar.notGivenReasons()).extracting(k -> k.label()).containsExactly("Patient refused",
                "Held on doctor's advice", "Nil by mouth", "Medicine not available", "Patient away from the ward");
    }

    @Test
    void asOfIsTheDatabaseClockAndNotTheApplicationClock() {
        fake.now = Instant.parse("2026-11-11T09:15:00Z");

        assertThat(nurseMar().asOf()).isEqualTo(Instant.parse("2026-11-11T09:15:00Z"));
    }

    @Test
    void withNoOrdersTheListIsEmptyAndTheCountIsZero() {
        Mar mar = nurseMar();

        assertThat(mar.orders()).isEmpty();
        assertThat(mar.doseCount()).isZero();
    }

    // ---- which orders are listed ----

    @Test
    void onlyPendingAndActiveOrdersAreListedInChartOrder() {
        OrderRow first = active("pcm", "od");
        OrderRow pending = ofStatus("PENDING", null);
        ofStatus("REJECTED", null);
        ofStatus("STOPPED", CHECKED_LONG_AGO);
        OrderRow last = active("hep", "bd");

        Mar mar = nurseMar();

        assertThat(mar.orders()).extracting(MarOrderView::orderId).containsExactly(first.id(), pending.id(),
                last.id());
        assertThat(mar.orders()).extracting(MarOrderView::status).containsExactly("ACTIVE", "PENDING", "ACTIVE");
    }

    @Test
    void anOrderBlockHoldsTheDrugTheRouteTheFrequencyAndTheDoctor() {
        OrderRow order = active("hep", "bd");

        MarOrderView block = nurseMar().orders().get(0);

        assertThat(block.orderId()).isEqualTo(order.id());
        assertThat(block.drugKey()).isEqualTo("hep");
        assertThat(block.drugName()).isEqualTo("Inj. Heparin 5,000 IU");
        assertThat(block.route()).isEqualTo("SC");
        assertThat(block.highAlert()).isEqualTo("Anticoagulant");
        assertThat(block.frequencyKey()).isEqualTo("bd");
        assertThat(block.frequencyLabel()).isEqualTo("Twice daily");
        assertThat(block.orderedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(block.slots()).hasSize(2);
    }

    @Test
    void aMedicineThatIsNotHighAlertHasNoHighAlertClass() {
        active("pcm", "od");

        assertThat(nurseMar().orders().get(0).highAlert()).isNull();
    }

    @Test
    void theSlotsFollowTheFrequency() {
        active("pcm", "od");
        active("panto", "bd");
        active("ondan", "tds");
        active("ns", "q8");

        Mar mar = nurseMar();

        assertThat(mar.orders()).extracting(o -> o.slots().size()).containsExactly(1, 2, 3, 3);
        assertThat(mar.orders().get(3).slots()).extracting(SlotView::slotAt).containsExactly(
                Instant.parse("2026-11-11T00:30:00Z"), Instant.parse("2026-11-11T08:30:00Z"),
                Instant.parse("2026-11-11T16:30:00Z"));
    }

    // ---- the slot states ----

    @Test
    void aPendingOrderWaitsForThePharmacistAndItsSlotsCannotBeGiven() {
        ofStatus("PENDING", null);

        Mar mar = nurseMar();

        assertThat(slot(mar, 0, 0).state()).isEqualTo("WAITING");
        assertThat(slot(mar, 0, 0).canGive()).isFalse();
        assertThat(mar.doseCount()).isZero();
    }

    @Test
    void aSlotWithinSixtyMinutesIsDueAndOneMoreThanSixtyAfterIsLate() {
        active("pcm", "tds");
        fake.now = Instant.parse("2026-11-11T03:30:00Z"); // 9:00 AM IST: 8 AM is exactly 60 minutes late

        assertThat(slot(nurseMar(), 0, 0).state()).isEqualTo("DUE");

        fake.now = Instant.parse("2026-11-11T03:31:00Z"); // 9:01 AM IST
        SlotView late = slot(nurseMar(), 0, 0);
        assertThat(late.state()).isEqualTo("LATE");
        assertThat(late.lateHours()).isEqualTo(1);
        assertThat(late.canGive()).isTrue();
    }

    @Test
    void theLateHoursAreRoundedDown() {
        active("pcm", "od");
        fake.now = Instant.parse("2026-11-11T05:29:00Z"); // 10:59 AM IST: 2 h 59 min late

        SlotView late = slot(nurseMar(), 0, 0);

        assertThat(late.state()).isEqualTo("LATE");
        assertThat(late.lateHours()).isEqualTo(2);
    }

    @Test
    void aSlotMoreThanSixtyMinutesAheadIsLaterToday() {
        active("pcm", "tds");

        Mar mar = nurseMar(); // 8:10 AM IST: 2 PM is far ahead

        assertThat(slot(mar, 0, 0).state()).isEqualTo("DUE");
        assertThat(slot(mar, 0, 0).canGive()).isTrue();
        assertThat(slot(mar, 0, 1).state()).isEqualTo("LATER");
        assertThat(slot(mar, 0, 1).canGive()).isFalse();
        assertThat(slot(mar, 0, 2).state()).isEqualTo("LATER");
    }

    @Test
    void theCountIsTheDueAndLateSlotsOfActiveOrdersOnly() {
        active("pcm", "od"); // 8 AM due
        active("ns", "q8"); // 6 AM late, 2 PM and 10 PM later
        ofStatus("PENDING", null); // waiting, not counted

        Mar mar = nurseMar();

        assertThat(mar.doseCount()).isEqualTo(2);
    }

    @Test
    void aRecordedSlotShowsItsOutcomeTheNamesAndIsNotCountedOrClickable() {
        OrderRow order = active("hep", "bd");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, SECOND_NURSE, true);

        Mar mar = nurseMar();

        SlotView given = slot(mar, 0, 0);
        assertThat(given.state()).isEqualTo("GIVEN");
        assertThat(given.canGive()).isFalse();
        assertThat(given.lateHours()).isNull();
        assertThat(given.dose().outcome()).isEqualTo("GIVEN");
        assertThat(given.dose().recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(given.dose().secondCheckByName()).isEqualTo("Sr. Rupali More");
        assertThat(given.dose().reason()).isNull();
        assertThat(slot(mar, 0, 1).dose()).isNull();
        assertThat(mar.doseCount()).isZero();
    }

    @Test
    void heldAndRefusedSlotsShowTheirReasonLabel() {
        OrderRow held = active("pcm", "od");
        OrderRow refused = active("panto", "od");
        doses.seed(ADMISSION, held.id(), EIGHT_AM, "HELD", "NIL_BY_MOUTH", NURSE, null, false);
        doses.seed(ADMISSION, refused.id(), EIGHT_AM, "REFUSED", "PATIENT_REFUSED", NURSE, null, false);

        Mar mar = nurseMar();

        assertThat(slot(mar, 0, 0).state()).isEqualTo("HELD");
        assertThat(slot(mar, 0, 0).dose().reason().label()).isEqualTo("Nil by mouth");
        assertThat(slot(mar, 1, 0).state()).isEqualTo("REFUSED");
        assertThat(slot(mar, 1, 0).dose().reason().label()).isEqualTo("Patient refused");
    }

    @Test
    void aStaffMemberWhoCannotBeFoundIsNamedTeamMember() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, UUID.randomUUID(), null, false);

        assertThat(slot(nurseMar(), 0, 0).dose().recordedByName()).isEqualTo("Team member");
    }

    @Test
    void yesterdaysDoseIsNotOnTodaysMar() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), Instant.parse("2026-11-10T02:30:00Z"), "GIVEN", null, NURSE, null, false);

        Mar mar = nurseMar();

        assertThat(slot(mar, 0, 0).state()).isEqualTo("DUE");
        assertThat(slot(mar, 0, 0).dose()).isNull();
    }

    @Test
    void anOrderCheckedAtHalfPastTenHasNoEightAmDose() {
        fake.seedAt(ADMISSION, "pcm", "bd", "ACTIVE", DOCTOR, Instant.parse("2026-11-11T05:00:00Z"));
        fake.now = Instant.parse("2026-11-11T05:05:00Z");

        Mar mar = nurseMar();

        assertThat(slot(mar, 0, 0).state()).isEqualTo("BEFORE_START");
        assertThat(slot(mar, 0, 0).canGive()).isFalse();
        assertThat(slot(mar, 0, 1).state()).isEqualTo("LATER");
        assertThat(mar.doseCount()).isZero();
    }

    @Test
    void justBeforeMidnightIstTheSlotsAreOfTodayAndJustAfterTheyAreOfTheNewDay() {
        OrderRow order = active("pcm", "tds");
        doses.seed(ADMISSION, order.id(), EIGHT_PM, "GIVEN", null, NURSE, null, false);
        fake.now = Instant.parse("2026-11-11T18:29:59Z"); // 11:59:59 PM IST

        Mar before = nurseMar();
        assertThat(slot(before, 0, 2).state()).isEqualTo("GIVEN");
        assertThat(slot(before, 0, 0).state()).isEqualTo("LATE");

        fake.now = Instant.parse("2026-11-11T18:30:00Z"); // 12:00 AM IST on 12 Nov
        Mar after = nurseMar();
        assertThat(slot(after, 0, 0).slotAt()).isEqualTo(Instant.parse("2026-11-12T02:30:00Z"));
        assertThat(slot(after, 0, 0).state()).isEqualTo("LATER");
        assertThat(slot(after, 0, 2).state()).isEqualTo("LATER");
        assertThat(after.doseCount()).isZero();
    }

    // ---- can give, lock reasons, the Checked by list ----

    @Test
    void aNurseAndAnOwnerCanGiveADoctorCannotAndIsToldWhyByRole() {
        active("pcm", "od");

        Mar nurse = nurseMar();
        Mar owner = mar(OWNER, "OWNER");
        Mar doctor = mar(DOCTOR, "DOCTOR");

        assertThat(nurse.canGive()).isTrue();
        assertThat(nurse.giveLockReason()).isNull();
        assertThat(owner.canGive()).isTrue();
        assertThat(doctor.canGive()).isFalse();
        assertThat(doctor.giveLockReason()).isEqualTo("ROLE");
        assertThat(slot(doctor, 0, 0).state()).isEqualTo("DUE");
        assertThat(slot(doctor, 0, 0).canGive()).isFalse();
        assertThat(doctor.doseCount()).isEqualTo(1);
    }

    @Test
    void aTokenNurseWhoIsADoctorInTheMembershipCannotGiveEither() {
        active("pcm", "od");
        memberRoles.put(NURSE, "DOCTOR");

        // the membership role is DOCTOR, which may read: the token says NURSE, so the read is allowed but not the give
        Mar mar = nurseMar();

        assertThat(mar.canGive()).isFalse();
        assertThat(mar.giveLockReason()).isEqualTo("ROLE");
    }

    @Test
    void inReadOnlyStateTheMarIsReadableButCannotBeGiven() {
        active("pcm", "od");
        ipdState("readonly");

        Mar mar = nurseMar();

        assertThat(mar.state()).isEqualTo("readonly");
        assertThat(mar.canGive()).isFalse();
        assertThat(mar.giveLockReason()).isEqualTo("IPD_READONLY");
        assertThat(slot(mar, 0, 0).canGive()).isFalse();
        assertThat(mar.secondNurses()).isEmpty();
        assertThat(mar.doseCount()).isEqualTo(1);
    }

    @Test
    void aDischargedAdmissionIsReadableWithNoDueSlotsAndNotRecordedSlots() {
        OrderRow order = active("pcm", "bd");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);
        admission = admissionRow("DISCHARGED");

        Mar mar = nurseMar();

        assertThat(mar.status()).isEqualTo("DISCHARGED");
        assertThat(mar.canGive()).isFalse();
        assertThat(mar.giveLockReason()).isEqualTo("NOT_ADMITTED");
        assertThat(mar.doseCount()).isZero();
        assertThat(slot(mar, 0, 0).state()).isEqualTo("GIVEN");
        assertThat(slot(mar, 0, 1).state()).isEqualTo("NOT_RECORDED");
        assertThat(slot(mar, 0, 1).canGive()).isFalse();
    }

    @Test
    void theRoleLockReasonComesBeforeTheReadOnlyAndNotAdmittedReasons() {
        ipdState("readonly");
        admission = admissionRow("DISCHARGED");

        assertThat(mar(DOCTOR, "DOCTOR").giveLockReason()).isEqualTo("ROLE");
        assertThat(nurseMar().giveLockReason()).isEqualTo("IPD_READONLY");
        ipdState("on");
        assertThat(nurseMar().giveLockReason()).isEqualTo("NOT_ADMITTED");
    }

    @Test
    void theCheckedByListIsTheOtherActiveNursesAndNeverTheSignedInPerson() {
        UUID thirdMembership = UUID.randomUUID();
        roster.put(thirdMembership, new TeamMember(thirdMembership, "Br. Nikhil Jadhav", "NURSE", true, 0L));
        UUID inactive = UUID.randomUUID();
        roster.put(inactive, new TeamMember(inactive, "Sr. Left Clinic", "NURSE", false, 0L));
        UUID doctor = UUID.randomUUID();
        roster.put(doctor, new TeamMember(doctor, "Dr Another", "DOCTOR", true, 0L));

        List<SecondNurseOption> list = nurseMar().secondNurses();

        assertThat(list).extracting(SecondNurseOption::membershipId).containsExactly(secondMembership,
                thirdMembership);
        assertThat(list).extracting(SecondNurseOption::name).containsExactly("Sr. Rupali More", "Br. Nikhil Jadhav");
        assertThat(list).extracting(SecondNurseOption::membershipId).doesNotContain(callerMembership,
                membership(OWNER), inactive, doctor);
    }

    @Test
    void theOwnerSeesTheNursesAndNotTheOwnerHimself() {
        List<SecondNurseOption> list = mar(OWNER, "OWNER").secondNurses();

        assertThat(list).extracting(SecondNurseOption::membershipId).containsExactlyInAnyOrder(callerMembership,
                secondMembership);
    }

    @Test
    void aDoctorGetsNoCheckedByList() {
        assertThat(mar(DOCTOR, "DOCTOR").secondNurses()).isEmpty();
    }

    // ---- nothing personal in toString ----

    @Test
    void theMarAndItsPartsHideNamesAndIdentity() {
        OrderRow order = active("hep", "bd");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, SECOND_NURSE, true);

        Mar mar = nurseMar();

        assertThat(mar.toString()).doesNotContain("Test Patient").doesNotContain(UHID);
        assertThat(mar.patient().toString()).doesNotContain("Test Patient").doesNotContain(UHID);
        assertThat(mar.orders().get(0).toString()).doesNotContain("Heparin").doesNotContain("Meera");
        assertThat(mar.secondNurses().get(0).toString()).doesNotContain("Rupali");
        assertThat(slot(mar, 0, 0).dose().toString()).doesNotContain("Anjali").doesNotContain("Rupali");
    }

    // ---- SAH-34 patient scope ----

    @Test
    void marOutsideScopeWritesNoRecordAccess() {
        active("pcm", "od");
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(NURSE), eq("NURSE"),
                eq("NURSE"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> nurseMar()).isInstanceOf(OutsideScopeException.class);

        verifyNoInteractions(patientAccess);
    }
}
