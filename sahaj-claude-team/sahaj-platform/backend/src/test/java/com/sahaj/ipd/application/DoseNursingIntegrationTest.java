package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.DoseService.DoseResult;
import com.sahaj.ipd.application.MarViews.CorrectionInput;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-4 and 76-5 the nurse side of giving (ADR-027 D7): the medicine code and the manual override, the order state and
 * version checks that are audited, the slot rules on the order timeline, a STAT dose before the pharmacist check, PRN
 * limits, the new not-given reasons, and a new give after a correction. In-memory fakes, no database.
 */
class DoseNursingIntegrationTest extends DoseTestBase {

    private static final Instant STAT_CREATED = Instant.parse("2026-11-11T02:35:20Z");
    private static final Instant STAT_SLOT = Instant.parse("2026-11-11T02:35:00Z");

    private DoseResult nurseGives(OrderRow order, DoseInput input) {
        return service.record(TENANT, NURSE, "NURSE", order.id(), input);
    }

    private static void assertConflict(Throwable e, String type, String message) {
        assertThat(e).isInstanceOfSatisfying(ConflictException.class, c -> {
            assertThat(c.problemType()).isEqualTo(type);
            assertThat(c.getMessage()).isEqualTo(message);
        });
    }

    private static void assertField(Throwable e, String field, String message) {
        assertThat(e).isInstanceOfSatisfying(InvalidInputException.class, i -> {
            assertThat(i.field()).isEqualTo(field);
            assertThat(i.getMessage()).isEqualTo(message);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), isNull(), after.capture());
        return after.getValue();
    }

    private MedicineOrderVersion statVersion(OrderRow o, Verification verification) {
        return new MedicineOrderVersion(o.id(), 1, Kind.ONCE, null, null, null, "ORAL", STAT_CREATED, null, null,
                OrderPriority.STAT, null, null, null, DOCTOR, STAT_CREATED, null, verification, null, null);
    }

    /** A PENDING order whose only version is a STAT (ONCE) version created at 8:05 AM IST. */
    private OrderRow pendingStat() {
        OrderRow order = ofStatus("PENDING", null);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.PENDING, statVersion(order,
                Verification.REQUIRED)));
        return order;
    }

    private OrderRow prn() {
        OrderRow order = active("pcm", "od");
        MedicineOrderVersion v = new MedicineOrderVersion(order.id(), 1, Kind.PRN, null, null, null, "ORAL",
                CHECKED_LONG_AGO, null, "Pain", OrderPriority.ROUTINE, 240, 3, null, DOCTOR, CHECKED_LONG_AGO, null,
                Verification.NOT_REQUIRED, null, null);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, v));
        return order;
    }

    // ---- the medicine code ----

    @Test
    void aGivenWithoutAMedicineCodeAndWithoutAnOverrideIs400OnTheCodeField() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withCode(given(EIGHT_AM), null))).satisfies(
                e -> assertField(e, "medicineCode", "Scan or type the medicine code."));
        assertThat(doses.writes).isZero();
    }

    @Test
    void theMedicineCodeOfTheDrugIsNormalisedAndStored() {
        OrderRow order = active("pcm", "od");

        nurseGives(order, withCode(given(EIGHT_AM), " " + CODE.toLowerCase() + "\r\n"));

        assertThat(doses.inserted.get(0).medicineScan()).isEqualTo(CODE);
        assertThat(doses.inserted.get(0).scanMethod()).isEqualTo("SCAN");
        assertThat(doses.inserted.get(0).orderVersion()).isEqualTo(1);
        assertThat(doses.inserted.get(0).doseKind()).isEqualTo("SCHEDULED");
    }

    @Test
    void aCodeOfAnotherMedicineIsAStopWithTheNamesAndTheDenyEntryHoldsTheKnownCode() {
        OrderRow order = active("pcm", "od");
        when(drugCodes.codesOf("pcm")).thenReturn(Set.of(CODE));
        when(drugCodes.drugKeyOf("HEP-5000")).thenReturn(Optional.of("hep"));

        assertThatThrownBy(() -> nurseGives(order, withCode(given(EIGHT_AM), "hep-5000"))).satisfies(e -> assertConflict(
                e, "medicine-mismatch", "Wrong medicine. This is Inj. Heparin 5,000 IU, not Tab. Paracetamol 650 mg."));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Stopped at the medicine check: entered HEP-5000, expected Tab. Paracetamol 650 mg", NURSE);
        assertThat(auditAfter("IPD_DOSE_MEDICINE_MISMATCH")).containsOnlyKeys("orderId", "admissionId", "drugKey",
                "slotAt", "device").containsEntry("drugKey", "pcm").containsEntry("slotAt", EIGHT_AM.toString());
        assertThat(doses.writes).isZero();
    }

    @Test
    void aCodeNotInTheMedicineListNeverReachesTheLog() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withCode(given(EIGHT_AM), "9876543210"))).satisfies(e -> assertConflict(
                e, "medicine-mismatch", "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg."));

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_DENY,
                "Stopped at the medicine check: entered a code not in the medicine list, expected Tab. Paracetamol 650 mg",
                NURSE);
    }

    @Test
    void aMedicineMismatchIsStillAStopWithAnOverrideAndLeavesAScanStopOfKindMedicine() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withOverride(withCode(given(EIGHT_AM), "9876543210"), "NO_BARCODE",
                "Read the strip label"))).satisfies(e -> assertConflict(e, "medicine-mismatch",
                        "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg."));

        assertThat(doses.stops).singleElement().satisfies(s -> {
            assertThat(s.checkKind()).isEqualTo("MEDICINE");
            assertThat(s.orderId()).isEqualTo(order.id());
            assertThat(s.slotAt()).isEqualTo(EIGHT_AM);
            assertThat(s.actor()).isEqualTo(NURSE);
        });
        assertThat(doses.writes).isZero();
    }

    // ---- the manual override (contract 0.4.3, ADR-027 A9) ----

    @Test
    void anOverrideNeedsOnlyAnEmptyCodeAndKeepsTheWristbandScanAndIsStoredWithItsReasonAndNote() {
        OrderRow order = active("pcm", "od");
        DoseInput input = withOverride(withCode(given(EIGHT_AM), null), "NO_BARCODE", "Read the strip label");

        DoseResult result = nurseGives(order, input);

        assertThat(result.created()).isTrue();
        var stored = doses.inserted.get(0);
        assertThat(stored.scanMethod()).isEqualTo("OVERRIDE");
        assertThat(stored.overrideReasonKey()).isEqualTo("NO_BARCODE");
        assertThat(stored.overrideNote()).isEqualTo("Read the strip label");
        assertThat(stored.identityScan()).isEqualTo(UHID);
        assertThat(stored.medicineScan()).isNull();
        assertThat(result.answer().dose().method()).isEqualTo("OVERRIDE");
        assertThat(result.answer().dose().overrideReason().label()).isEqualTo("Medicine has no barcode");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID"
                        + " · scan override: Medicine has no barcode", NURSE);
        Map<String, Object> after = auditAfter("IPD_DOSE_GIVEN");
        assertThat(after).containsEntry("method", "OVERRIDE").containsEntry("overrideReasonKey", "NO_BARCODE")
                .containsEntry("notePresent", true).containsEntry("orderVersion", 1)
                .containsEntry("doseKind", "SCHEDULED").containsEntry("beforeVerification", false).containsKey("device");
        assertThat(after.toString()).doesNotContain("strip label");
        assertThat(doses.stops).isEmpty();
    }

    @Test
    void anOverrideWithoutTheWristbandIs400AndWritesNothingWhateverTheReason() {
        OrderRow order = active("pcm", "od");
        for (String reason : List.of("NO_BARCODE", "SCANNER_DOWN", "EMERGENCY")) {
            DoseInput input = withOverride(with(withCode(given(EIGHT_AM), null), null, Boolean.TRUE, null, null, null),
                    reason, "Read the strip label");

            assertThatThrownBy(() -> nurseGives(order, input)).satisfies(
                    e -> assertField(e, "wristband", "Scan the wristband first."));
        }
        assertThat(doses.writes).isZero();
        assertThat(doses.stops).isEmpty();
    }

    @Test
    void aWristbandOfOnlyWhiteSpaceWithAnOverrideIsTheSame400() {
        OrderRow order = active("pcm", "od");
        DoseInput input = withOverride(with(withCode(given(EIGHT_AM), null), " \r\n", Boolean.TRUE, null, null, null),
                "EMERGENCY", "Patient unconscious, case file checked");

        assertThatThrownBy(() -> nurseGives(order, input)).satisfies(
                e -> assertField(e, "wristband", "Scan the wristband first."));
    }

    @Test
    void anOverrideStillComparesTheWristbandAndAMismatchIsAStopWithAScanStopOfKindWristband() {
        OrderRow order = active("pcm", "od");
        DoseInput input = withOverride(with(withCode(given(EIGHT_AM), null), "UH-000999", Boolean.TRUE, null, null, null),
                "NO_BARCODE", "Strip label read against the chart");

        assertThatThrownBy(() -> nurseGives(order, input)).satisfies(e -> assertConflict(e, "wristband-mismatch",
                DoseRules.MISMATCH));

        assertThat(doses.writes).isZero();
        assertThat(doses.stops).singleElement().satisfies(s -> {
            assertThat(s.checkKind()).isEqualTo("WRISTBAND");
            assertThat(s.slotAt()).isEqualTo(EIGHT_AM);
            assertThat(s.actor()).isEqualTo(NURSE);
        });
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_DENY), anyString(), eq(NURSE));
    }

    @Test
    void anOverrideWithoutAGoodReasonOrNoteIs400OnItsField() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withOverride(withCode(given(EIGHT_AM), null), null, "Read the label")))
                .satisfies(e -> assertField(e, "override.reasonKey", "Choose why you cannot scan."));
        assertThatThrownBy(() -> nurseGives(order, withOverride(withCode(given(EIGHT_AM), null), "EMERGENCY", "abc")))
                .satisfies(e -> assertField(e, "override.note", "Write what you checked instead."));
        assertThat(doses.writes).isZero();
    }

    @Test
    void theWristbandDamagedReasonIsRefusedOnInputAndAnUnknownKeyToo() {
        OrderRow order = active("pcm", "od");

        for (String reason : List.of("WRISTBAND_DAMAGED", "BROKEN", "")) {
            assertThatThrownBy(() -> nurseGives(order, withOverride(withCode(given(EIGHT_AM), null), reason,
                    "Band cut off, name checked"))).satisfies(
                    e -> assertField(e, "override.reasonKey", "Choose why you cannot scan."));
        }
        assertThat(doses.writes).isZero();
    }

    @Test
    void anOverrideSentWithAMatchingCodeIsIgnoredAndTheDoseIsAScan() {
        OrderRow order = active("pcm", "od");
        // The override is not even checked: a reason that would be refused on input does not matter.
        DoseInput input = withOverride(given(EIGHT_AM), "WRISTBAND_DAMAGED", "x");

        DoseResult result = nurseGives(order, input);

        var stored = doses.inserted.get(0);
        assertThat(stored.scanMethod()).isEqualTo("SCAN");
        assertThat(stored.medicineScan()).isEqualTo(CODE);
        assertThat(stored.overrideReasonKey()).isNull();
        assertThat(stored.overrideNote()).isNull();
        assertThat(result.answer().dose().method()).isEqualTo("SCAN");
        assertThat(auditAfter("IPD_DOSE_GIVEN")).containsEntry("method", "SCAN")
                .containsEntry("overrideReasonKey", null).containsEntry("notePresent", false);
    }

    @Test
    void anOverrideNeverSkipsTheSecondIdentifier() {
        OrderRow order = active("pcm", "od");
        DoseInput input = withOverride(with(withCode(given(EIGHT_AM), null), UHID, null, null, null, null), "EMERGENCY",
                "Patient unconscious, case file checked");

        assertThatThrownBy(() -> nurseGives(order, input)).satisfies(e -> assertField(e, "identityConfirmed",
                DoseRules.CONFIRM));
    }

    // ---- an override after a mismatch (contract 0.4.3, ADR-027 A9) ----

    private DoseInput overrideOf(Instant slot) {
        return withOverride(withCode(given(slot), null), "NO_BARCODE", "Read the strip label");
    }

    @Test
    void anOverrideWithinFifteenMinutesOfTheCallersOwnMismatchIsRefusedAuditedAndWritesNothingElse() {
        OrderRow order = active("pcm", "od");
        assertThatThrownBy(() -> nurseGives(order, withCode(given(EIGHT_AM), "9876543210")))
                .satisfies(e -> assertConflict(e, "medicine-mismatch",
                        "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg."));
        fake.now = fake.now.plusSeconds(14 * 60);

        assertThatThrownBy(() -> nurseGives(order, overrideOf(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "override-after-mismatch", "A scan for this dose did not match in the last 15 minutes. Check the patient"
                        + " and the medicine, then scan the medicine code."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "override-after-mismatch")
                .containsEntry("orderId", order.id()).containsEntry("admissionId", ADMISSION).containsKey("device");
        assertThat(doses.writes).isZero();
        assertThat(doses.stops).hasSize(1);
        verify(events, org.mockito.Mockito.times(1)).insert(eq(TENANT), eq(ADMISSION), anyString(), anyString(),
                eq(NURSE));
    }

    @Test
    void theRefusalTitleIsOverrideNotAllowed() {
        OrderRow order = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "WRISTBAND", NURSE, fake.now));

        assertThatThrownBy(() -> nurseGives(order, overrideOf(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                c -> {
                    assertThat(c.problemType()).isEqualTo("override-after-mismatch");
                    assertThat(c.title()).isEqualTo("Override not allowed");
                });
    }

    @Test
    void aWristbandStopCountsToo() {
        OrderRow order = active("pcm", "od");
        assertThatThrownBy(() -> nurseGives(order, with(given(EIGHT_AM), "UH-000999", Boolean.TRUE, null, null, null)))
                .satisfies(e -> assertConflict(e, "wristband-mismatch", DoseRules.MISMATCH));

        assertThatThrownBy(() -> nurseGives(order, overrideOf(EIGHT_AM))).satisfies(
                e -> assertConflict(e, "override-after-mismatch", DoseRules.OVERRIDE_AFTER_MISMATCH));
    }

    @Test
    void anOverrideAfterFifteenMinutesOrMoreIsAllowed() {
        OrderRow order = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "MEDICINE", NURSE, fake.now));
        fake.now = fake.now.plusSeconds(15 * 60);

        assertThat(nurseGives(order, overrideOf(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void anotherNursesMismatchDoesNotCount() {
        OrderRow order = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "MEDICINE", OWNER, fake.now));

        assertThat(nurseGives(order, overrideOf(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void aMismatchOfAnotherOrderOrAnotherSlotDoesNotCount() {
        OrderRow order = active("pcm", "od");
        OrderRow other = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, other.id(), EIGHT_AM, "MEDICINE", NURSE, fake.now));
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_PM, "MEDICINE", NURSE, fake.now));

        assertThat(nurseGives(order, overrideOf(EIGHT_AM)).created()).isTrue();
    }

    @Test
    void aMatchingScanIsNeverRefusedByTheRule() {
        OrderRow order = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "MEDICINE", NURSE, fake.now));

        DoseResult result = nurseGives(order, given(EIGHT_AM));

        assertThat(result.created()).isTrue();
        assertThat(doses.inserted.get(0).scanMethod()).isEqualTo("SCAN");
    }

    @Test
    void aPrnOrderTakesAMismatchOfAnyTimeSlot() {
        OrderRow order = prn();
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), CLOCK.minusSeconds(600), "MEDICINE", NURSE, fake.now));

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(overrideOf(EIGHT_AM)))).satisfies(
                e -> assertConflict(e, "override-after-mismatch", DoseRules.OVERRIDE_AFTER_MISMATCH));
    }

    @Test
    void thePrnMismatchIsStoredWithTheDatabaseMinuteAsItsSlot() {
        OrderRow order = prn();

        assertThatThrownBy(() -> nurseGives(order, withCode(withoutSlot(given(EIGHT_AM)), "9876543210"))).satisfies(
                e -> assertConflict(e, "medicine-mismatch",
                        "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg."));

        assertThat(doses.stops).singleElement().satisfies(s -> assertThat(s.slotAt()).isEqualTo(CLOCK));
    }

    // ---- the check order of a GIVEN (contract 0.4.3 (E)) ----

    @Test
    void theWristbandIsCheckedBeforeTheCodeAndTheCodeBeforeTheOverrideFields() {
        OrderRow order = active("pcm", "od");
        // Nothing right: no wristband, a wrong code, a bad override, the box not ticked.
        DoseInput bad = withOverride(with(withCode(given(EIGHT_AM), "9876543210"), null, null, null, null, null),
                "BROKEN", "x");
        assertThatThrownBy(() -> nurseGives(order, bad)).satisfies(
                e -> assertField(e, "wristband", "Scan the wristband first."));

        // The wristband is right: the wrong code is the next stop, before the override fields.
        assertThatThrownBy(() -> nurseGives(order, with(bad, UHID, null, null, null, null))).satisfies(
                e -> assertConflict(e, "medicine-mismatch",
                        "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg."));

        // No code: the override fields come next.
        DoseInput noCode = withCode(with(bad, UHID, null, null, null, null), null);
        assertThatThrownBy(() -> nurseGives(order, noCode)).satisfies(
                e -> assertField(e, "override.reasonKey", "Choose why you cannot scan."));
    }

    @Test
    void theOverrideAfterMismatchRuleComesAfterTheOverrideFieldsAndBeforeIdentityConfirmed() {
        OrderRow order = active("pcm", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "MEDICINE", NURSE, fake.now));
        DoseInput unticked = with(overrideOf(EIGHT_AM), UHID, null, null, null, null);
        DoseInput badNote = withOverride(withCode(given(EIGHT_AM), null), "NO_BARCODE", "abc");

        assertThatThrownBy(() -> nurseGives(order, badNote)).satisfies(
                e -> assertField(e, "override.note", "Write what you checked instead."));
        assertThatThrownBy(() -> nurseGives(order, unticked)).satisfies(
                e -> assertConflict(e, "override-after-mismatch", DoseRules.OVERRIDE_AFTER_MISMATCH));
    }

    @Test
    void theOverrideAfterMismatchRuleComesBeforeTheSecondNurseOfAHighAlertMedicine() {
        OrderRow order = active("hep", "od");
        doses.stops.add(new FakeDoses.Stop(ADMISSION, order.id(), EIGHT_AM, "MEDICINE", NURSE, fake.now));
        when(drugCodes.codesOf("hep")).thenReturn(Set.of("HEP-5000"));
        DoseInput input = withOverride(with(withCode(given(EIGHT_AM), null), UHID, Boolean.TRUE, null, null, null),
                "NO_BARCODE", "Read the strip label");

        assertThatThrownBy(() -> nurseGives(order, input)).satisfies(
                e -> assertConflict(e, "override-after-mismatch", DoseRules.OVERRIDE_AFTER_MISMATCH));
    }

    // ---- a PRN dose outside its start and end (contract 0.4.3 (F)) ----

    private OrderRow prnBetween(Instant start, Instant end) {
        OrderRow order = active("pcm", "od");
        MedicineOrderVersion v = new MedicineOrderVersion(order.id(), 1, Kind.PRN, null, null, null, "ORAL", start, end,
                "Pain", OrderPriority.ROUTINE, 240, 3, null, DOCTOR, CHECKED_LONG_AGO, null, Verification.NOT_REQUIRED,
                null, null);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, v));
        return order;
    }

    @Test
    void aPrnDoseBeforeTheStartIsNotDueAndNotAudited() {
        OrderRow order = prnBetween(CLOCK.plusSeconds(60), null);

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).satisfies(e -> assertConflict(e,
                "dose-not-due", "This dose cannot be given now. Reload the page."));
        assertThatThrownBy(() -> nurseGives(order, withoutSlot(notGiven(EIGHT_AM, "NIL_BY_MOUTH")))).satisfies(e ->
                assertConflict(e, "dose-not-due", "This dose cannot be given now. Reload the page."));

        verify(auditLog, never()).record(eq("IPD_DOSE_REFUSED"), anyString(), any(), any(), any());
        assertThat(doses.writes).isZero();
    }

    @Test
    void aPrnDoseAtOrAfterTheEndIsNotDue() {
        assertThatThrownBy(() -> nurseGives(prnBetween(CHECKED_LONG_AGO, CLOCK), withoutSlot(given(EIGHT_AM))))
                .satisfies(e -> assertConflict(e, "dose-not-due", "This dose cannot be given now. Reload the page."));
        assertThatThrownBy(() -> nurseGives(prnBetween(CHECKED_LONG_AGO, CLOCK.minusSeconds(1)),
                withoutSlot(notGiven(EIGHT_AM, "NIL_BY_MOUTH")))).satisfies(e -> assertConflict(e, "dose-not-due",
                "This dose cannot be given now. Reload the page."));
        assertThat(doses.writes).isZero();
    }

    @Test
    void aPrnDoseAtTheStartOrBeforeTheEndIsGiven() {
        assertThat(nurseGives(prnBetween(CLOCK, null), withoutSlot(given(EIGHT_AM))).created()).isTrue();
        assertThat(nurseGives(prnBetween(CHECKED_LONG_AGO, CLOCK.plusSeconds(1)), withoutSlot(given(EIGHT_AM)))
                .created()).isTrue();
    }

    @Test
    void thePrnLineIsLockedNotStartedOrEndedAgainstTheMarClock() {
        OrderRow notStarted = prnBetween(CLOCK.plusSeconds(3600), null);
        OrderRow ended = prnBetween(CHECKED_LONG_AGO, CLOCK.minusSeconds(3600));

        var orders = marService.mar(TENANT, NURSE, "NURSE", ADMISSION).orders();

        assertThat(orders).filteredOn(o -> o.orderId().equals(notStarted.id())).singleElement().satisfies(o -> {
            assertThat(o.prn().lockReason()).isEqualTo("NOT_STARTED");
            assertThat(o.prn().canGive()).isFalse();
        });
        assertThat(orders).filteredOn(o -> o.orderId().equals(ended.id())).singleElement().satisfies(o -> {
            assertThat(o.prn().lockReason()).isEqualTo("ENDED");
            assertThat(o.prn().canGive()).isFalse();
        });
    }

    // ---- a replay by another nurse (security L1) ----

    @Test
    void theSameKeySentByAnotherUserIsClientKeyReusedAndWritesNothing() {
        OrderRow order = active("pcm", "od");
        DoseInput input = given(EIGHT_AM);
        nurseGives(order, input);
        org.mockito.Mockito.clearInvocations(patientAccess, auditLog);

        assertThatThrownBy(() -> service.record(TENANT, OWNER, "OWNER", order.id(), input)).satisfies(
                e -> assertConflict(e, "client-key-reused",
                        "This dose was already recorded with different values. Reload the page."));

        assertThat(doses.rows).hasSize(1);
        verify(patientAccess, never()).recordOpened(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theSameKeySentAgainByTheRecorderIsStillAReplay() {
        OrderRow order = active("pcm", "od");
        DoseInput input = given(EIGHT_AM);
        nurseGives(order, input);

        DoseResult again = nurseGives(order, input);

        assertThat(again.created()).isFalse();
        assertThat(doses.rows).hasSize(1);
    }

    // ---- order state and version (audited refusals) ----

    @Test
    void aDoseForAnOldVersionIsRefusedAndAuditedAsOrderChanged() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withVersion(given(EIGHT_AM), 2))).satisfies(e -> assertConflict(e,
                "order-changed", "This order was changed. Reload and check the new order."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "order-changed")
                .containsEntry("orderId", order.id()).containsKey("device");
        assertThat(doses.writes).isZero();
    }

    @Test
    void anOrderOnHoldIsRefusedAndAudited() {
        OrderRow order = active("pcm", "od");
        timelineOverrides.put(order.id(), new OrderTimeline(order.id(), ADMISSION, OrderStatus.ON_HOLD,
                List.of(version(order, 1, Kind.SCHEDULED, "od", OrderPriority.ROUTINE)),
                List.of(new OrderHoldInterval(Instant.parse("2026-11-11T02:00:00Z"), DOCTOR, "Reason", null, null)),
                null));

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "order-on-hold",
                "This order is on hold. Do not give it."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "order-on-hold");
    }

    @Test
    void aQueriedOrderIsNotCheckedYetAndAudited() {
        OrderRow order = ofStatus("QUERIED", null);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "order-not-verified", "This order is not checked by the pharmacist yet."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "order-not-verified");
    }

    @Test
    void aSlotInsideAHoldCannotBeGivenNow() {
        OrderRow order = active("pcm", "od");
        timelineOverrides.put(order.id(), new OrderTimeline(order.id(), ADMISSION, OrderStatus.ACTIVE,
                List.of(version(order, 1, Kind.SCHEDULED, "od", OrderPriority.ROUTINE)),
                List.of(new OrderHoldInterval(Instant.parse("2026-11-11T02:20:00Z"), DOCTOR, "Reason",
                        Instant.parse("2026-11-11T02:50:00Z"), DOCTOR)), null));

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "dose-not-due",
                "This dose cannot be given now. Reload the page."));
    }

    @Test
    void aSlotOfAReplacedVersionCannotBeGivenEvenWithTheCurrentVersionNumber() {
        OrderRow order = active("pcm", "od");
        Instant change = Instant.parse("2026-11-11T02:35:00Z");
        MedicineOrderVersion v1 = new MedicineOrderVersion(order.id(), 1, Kind.SCHEDULED, "bd", null, null, "ORAL",
                CHECKED_LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, DOCTOR, CHECKED_LONG_AGO, change,
                Verification.REQUIRED, DOCTOR, CHECKED_LONG_AGO);
        MedicineOrderVersion v2 = new MedicineOrderVersion(order.id(), 2, Kind.SCHEDULED, "od", null, null, "ORAL",
                change, null, null, OrderPriority.ROUTINE, null, null, "Changed", DOCTOR, change, null,
                Verification.REQUIRED, DOCTOR, change);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, v1, v2));

        assertThatThrownBy(() -> nurseGives(order, withVersion(given(EIGHT_AM), 2))).satisfies(e -> assertConflict(e,
                "dose-not-due", "This dose cannot be given now. Reload the page."));
        assertThatThrownBy(() -> nurseGives(order, withVersion(given(EIGHT_PM), 2))).satisfies(e -> assertConflict(e,
                "dose-not-due", "This dose cannot be given now. Reload the page."));
    }

    @Test
    void theWindowOfTheWardProfileDecidesWhetherASlotIsDueYet() {
        OrderRow order = active("pcm", "od");
        settings = new com.sahaj.ipd.domain.EffectiveNursingSettings(true, 30, 30, 15, 30, 5, 10, 240);
        fake.now = Instant.parse("2026-11-11T01:59:00Z"); // 31 minutes before the 8 AM slot

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "dose-not-due",
                "This dose is not due yet. It can be given from 30 min before its time."));
    }

    // ---- STAT before the pharmacist check ----

    @Test
    void aStatDoseCanBeGivenBeforeTheCheckAndOpensAReviewAndAcknowledgesTheNotices() {
        OrderRow order = pendingStat();

        DoseResult result = nurseGives(order, given(STAT_SLOT));

        assertThat(result.created()).isTrue();
        var stored = doses.inserted.get(0);
        assertThat(stored.beforeVerification()).isTrue();
        assertThat(stored.doseKind()).isEqualTo("ONCE");
        assertThat(stored.slotAt()).isEqualTo(STAT_SLOT);
        verify(reviews).openForDose(eq(TENANT), eq(ADMISSION), eq(OrderRef.medicine(order.id(), 1)),
                eq(doses.rows.get(0).id()));
        verify(notices).acknowledgeByDose(eq(TENANT), eq(order.id()), eq(NURSE));
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_GIVE),
                org.mockito.ArgumentMatchers.endsWith(" · before pharmacist check"), eq(NURSE));
        assertThat(auditAfter("IPD_DOSE_GIVEN")).containsEntry("beforeVerification", true)
                .containsEntry("doseKind", "ONCE");
        assertThat(result.answer().dose().beforeVerification()).isTrue();
    }

    @Test
    void aSecondStatDoseBeforeTheCheckIsRefused() {
        OrderRow order = pendingStat();
        nurseGives(order, given(STAT_SLOT));

        assertThatThrownBy(() -> nurseGives(order, given(STAT_SLOT))).satisfies(e -> assertConflict(e,
                "order-not-verified", "This order is not checked by the pharmacist yet."));
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void notGivenBeforeTheCheckIsAllowedAndOpensNoReview() {
        OrderRow order = pendingStat();

        nurseGives(order, notGiven(STAT_SLOT, "NIL_BY_MOUTH"));

        assertThat(doses.inserted.get(0).beforeVerification()).isTrue();
        verify(reviews, never()).openForDose(any(), any(), any(), any());
        verify(notices).acknowledgeByDose(eq(TENANT), eq(order.id()), eq(NURSE));
    }

    @Test
    void aWaitingNonStatOrderStillCannotBeGiven() {
        OrderRow order = ofStatus("PENDING", null);

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e,
                "order-not-verified", "This order is not checked by the pharmacist yet."));
    }

    // ---- PRN ----

    @Test
    void aPrnDoseHasNoSlotAndIsRecordedAtTheMinuteOfTheDatabaseClock() {
        OrderRow order = prn();
        doses.recordedAt = Instant.parse("2026-11-11T02:39:00Z");

        DoseResult result = nurseGives(order, withoutSlot(given(EIGHT_AM)));

        assertThat(result.created()).isTrue();
        var stored = doses.inserted.get(0);
        assertThat(stored.slotAt()).isEqualTo(Instant.parse("2026-11-11T02:40:00Z"));
        assertThat(stored.doseKind()).isEqualTo("PRN");
        assertThat(result.answer().mar().orders().get(0).prn().givenLast24h()).isEqualTo(1);
        assertThat(result.answer().mar().orders().get(0).slots()).isEmpty();
    }

    @Test
    void aPrnDoseMustNotSendASlot() {
        OrderRow order = prn();

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertField(e, "slotAt",
                "Reload the page and try again."));
    }

    @Test
    void aScheduledDoseNeedsASlot() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).satisfies(e -> assertField(e,
                "slotAt", "Reload the page and try again."));
    }

    @Test
    void aPrnDoseUnderTheMinimumIntervalIsTooSoonWithTheTimeAndIsAudited() {
        OrderRow order = prn();
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(100 * 60), "GIVEN", null, NURSE, null, false);

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).satisfies(e -> assertConflict(e,
                "prn-too-soon", "Too soon. Next dose allowed at 10:34 AM."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "prn-too-soon");
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void aSecondPrnDoseInTheSameMinuteIsTooSoonAndAuditedNotDoseRecorded() {
        OrderRow order = prn();
        doses.recordedAt = CLOCK.minusSeconds(30);
        assertThat(nurseGives(order, withoutSlot(given(EIGHT_AM))).created()).isTrue();

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).isInstanceOfSatisfying(
                ConflictException.class, c -> {
                    assertThat(c.problemType()).isEqualTo("prn-too-soon");
                    assertThat(c.getMessage()).startsWith("Too soon. Next dose allowed at ");
                });

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "prn-too-soon");
        assertThat(doses.rows).hasSize(1);
    }

    @Test
    void aSecondPrnDoseInTheSameMinuteAtTheDailyMaximumIsMaxReachedAndAudited() {
        OrderRow order = prn();
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(10 * 3600), "GIVEN", null, NURSE, null, false);
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(5 * 3600), "GIVEN", null, NURSE, null, false);
        doses.recordedAt = CLOCK.minusSeconds(30);
        nurseGives(order, withoutSlot(given(EIGHT_AM)));

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).isInstanceOfSatisfying(
                ConflictException.class, c -> assertThat(c.problemType()).isEqualTo("prn-max-reached"));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "prn-max-reached");
    }

    @Test
    void aScheduledDoseAlreadyRecordedStaysDoseRecorded() {
        OrderRow order = active("pcm", "od");
        nurseGives(order, given(EIGHT_AM));

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).isInstanceOfSatisfying(ConflictException.class,
                c -> assertThat(c.problemType()).isEqualTo("dose-recorded"));
    }

    @Test
    void aPrnDoseAtTheDailyMaximumIsRefusedUntilTheOldestDoseLeavesTheWindow() {
        OrderRow order = prn();
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(20 * 3600), "GIVEN", null, NURSE, null, false);
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(10 * 3600), "GIVEN", null, NURSE, null, false);
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(5 * 3600), "GIVEN", null, NURSE, null, false);

        assertThatThrownBy(() -> nurseGives(order, withoutSlot(given(EIGHT_AM)))).satisfies(e -> assertConflict(e,
                "prn-max-reached", "Daily maximum reached. Next dose allowed at 12:14 PM."));

        assertThat(auditAfter("IPD_DOSE_REFUSED")).containsEntry("refusal", "prn-max-reached");
    }

    @Test
    void anUndoneDoseNoLongerCountsForAPrnLimit() {
        OrderRow order = prn();
        DoseRow first = doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(100 * 60), "GIVEN", null, NURSE, null, false);
        doses.rows.replaceAll(d -> d.id().equals(first.id()) ? d.corrected(new DoseRepository.CorrectionRow(d.id(),
                "UNDO", null, NURSE, CLOCK, UUID.randomUUID())) : d);

        assertThat(nurseGives(order, withoutSlot(given(EIGHT_AM))).created()).isTrue();
    }

    // ---- not given ----

    @Test
    void vomitedIsAReasonStoredAsHeld() {
        OrderRow order = active("pcm", "od");

        nurseGives(order, notGiven(EIGHT_AM, "VOMITED"));

        assertThat(doses.inserted.get(0).outcome()).isEqualTo("HELD");
        assertThat(doses.inserted.get(0).reasonKey()).isEqualTo("VOMITED");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Did not give Tab. Paracetamol 650 mg (8 AM dose) · Vomited", NURSE);
    }

    @Test
    void otherNeedsANoteWhichIsStoredButNeverWrittenToTheEventOrTheAudit() {
        OrderRow order = active("pcm", "od");

        assertThatThrownBy(() -> nurseGives(order, withNote(notGiven(EIGHT_AM, "OTHER"), "no"))).satisfies(
                e -> assertField(e, "note", "Write what happened."));
        nurseGives(order, withNote(notGiven(EIGHT_AM, "OTHER"), "Spat it out at once"));

        assertThat(doses.inserted.get(0).notGivenNote()).isEqualTo("Spat it out at once");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_GIVE,
                "Did not give Tab. Paracetamol 650 mg (8 AM dose) · Other", NURSE);
        Map<String, Object> after = auditAfter("IPD_DOSE_NOT_GIVEN");
        assertThat(after).containsEntry("notePresent", true).containsEntry("reasonKey", "OTHER");
        assertThat(after.toString()).doesNotContain("Spat");
    }

    // ---- a new give after a correction ----

    @Test
    void aSlotCanBeGivenAgainAfterTheFirstRecordWasMarkedInErrorAsTheNextAttempt() {
        OrderRow order = active("pcm", "od");
        DoseResult first = nurseGives(order, given(EIGHT_AM));
        corrections.recordedInError(TENANT, NURSE, "NURSE", first.answer().dose().id(),
                new CorrectionInput(UUID.randomUUID().toString(), "WRONG_PATIENT"));

        DoseResult again = nurseGives(order, given(EIGHT_AM));

        assertThat(again.created()).isTrue();
        var second = doses.inserted.get(1);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.supersedesDoseId()).isEqualTo(first.answer().dose().id());
        SlotCheck.slotGivenOnce(again, EIGHT_AM);
    }

    @Test
    void aSlotWithALiveRecordCannotBeGivenAgain() {
        OrderRow order = active("pcm", "od");
        nurseGives(order, given(EIGHT_AM));

        assertThatThrownBy(() -> nurseGives(order, given(EIGHT_AM))).satisfies(e -> assertConflict(e, "dose-recorded",
                "This dose is already recorded."));
    }

    // ---- what the answer carries ----

    @Test
    void theAnswerCarriesTheUndoWindowOfTheCallerAndTheMarKnowsTheNewFields() {
        OrderRow order = active("pcm", "od");
        doses.recordedAt = CLOCK.minusSeconds(1);

        DoseResult result = nurseGives(order, given(EIGHT_AM));

        assertThat(result.answer().dose().undoSecondsLeft()).isEqualTo(10);
        assertThat(result.answer().dose().canUndo()).isTrue();
        assertThat(result.answer().dose().canMarkInError()).isTrue();
        assertThat(result.answer().dose().method()).isEqualTo("SCAN");
        assertThat(result.answer().mar().counters().given()).isEqualTo(1);
        assertThat(result.answer().mar().windows().undoSeconds()).isEqualTo(10);
    }

    /** Small assertions on the MAR of an answer. */
    private static final class SlotCheck {
        static void slotGivenOnce(DoseResult result, Instant slotAt) {
            assertThat(result.answer().mar().orders().get(0).slots()).filteredOn(s -> s.slotAt().equals(slotAt))
                    .singleElement().satisfies(s -> {
                        assertThat(s.state()).isEqualTo("GIVEN");
                        assertThat(s.history()).hasSize(1);
                        assertThat(s.history().get(0).correction().reason().key()).isEqualTo("WRONG_PATIENT");
                    });
        }
    }
}
