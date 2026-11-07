package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.application.DoseRules.Body;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SAH-32 the checked body of a dose request, the roles, and the fixed texts and event templates of ADR-011. */
class DoseRulesTest {

    private static final String KEY = "2b4f6a8c-1d3e-4f50-9a7b-3c5d7e9f1a2b";
    private static final Drug HEPARIN = SeedCatalogue.drug("hep");
    private static final Drug PARACETAMOL = SeedCatalogue.drug("pcm");
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");

    private static DoseInput input(String key, String slot, String outcome) {
        return new DoseInput(key, slot, outcome, null, null, null, null, null);
    }

    // ---- roles ----

    @Test
    void ownerAndNurseGiveOwnerNurseAndDoctorRead() {
        assertThat(DoseRules.GIVE_ROLES).containsExactlyInAnyOrder("OWNER", "NURSE");
        assertThat(DoseRules.MAR_ROLES).containsExactlyInAnyOrder("OWNER", "NURSE", "DOCTOR");
    }

    // ---- the body ----

    @Test
    void aGoodBodyIsParsed() {
        Body body = DoseRules.body(input(KEY, "2026-11-11T02:30:00Z", "GIVEN"));

        assertThat(body.clientKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(body.slotAt()).isEqualTo(EIGHT_AM);
        assertThat(body.given()).isTrue();
    }

    @Test
    void notGivenIsParsedAsNotGiven() {
        assertThat(DoseRules.body(input(KEY, "2026-11-11T02:30:00Z", "NOT_GIVEN")).given()).isFalse();
    }

    @Test
    void theKeyAndTheSlotAreStripped() {
        Body body = DoseRules.body(input("  " + KEY + " ", " 2026-11-11T02:30:00Z ", "GIVEN"));

        assertThat(body.clientKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(body.slotAt()).isEqualTo(EIGHT_AM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-11T08:00:00+05:30", "2026-11-11T02:30:00.000Z", "2026-11-11T03:30:00+01:00"})
    void aSlotWithAnOffsetIsConvertedToTheInstant(String slot) {
        assertThat(DoseRules.body(input(KEY, slot, "GIVEN")).slotAt()).isEqualTo(EIGHT_AM);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1-1-1-1-1", "not a key", "2b4f6a8c1d3e4f509a7b3c5d7e9f1a2b", "{2b4f6a8c-1d3e-4f50-9a7b-3c5d7e9f1a2b}"})
    void aBadKeyIsAFormatErrorOnTheKey(String key) {
        assertThatThrownBy(() -> DoseRules.body(input(key, "2026-11-11T02:30:00Z", "GIVEN")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).containsExactly(new FieldMessage("clientKey", "Reload the page and try again."));
                    assertThat(e.firstMessageAsDetail()).isTrue();
                });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yesterday", "2026-11-11", "2026-11-11T02:30:00", "02:30", "1762828200"})
    void aBadSlotIsAFormatErrorOnTheSlot(String slot) {
        assertThatThrownBy(() -> DoseRules.body(input(KEY, slot, "GIVEN"))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(new FieldMessage("slotAt",
                        "Reload the page and try again.")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"given", "HELD", "REFUSED", "Not_Given"})
    void aBadOutcomeIsAFormatErrorOnTheOutcome(String outcome) {
        assertThatThrownBy(() -> DoseRules.body(input(KEY, "2026-11-11T02:30:00Z", outcome))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(new FieldMessage("outcome",
                        "Reload the page and try again.")));
    }

    // ---- the pieces of the bedside checks ----

    @ParameterizedTest
    @ValueSource(strings = {"0000", "4821", "9999", "0007"})
    void aPinOfFourAsciiDigitsIsAccepted(String pin) {
        assertThat(DoseRules.pinFormat(pin)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "123", "12345", "12 4", "12a4", " 123", "1234 ", "1234\n", "-123", "1.23"})
    void anythingElseIsNotAPin(String pin) {
        assertThat(DoseRules.pinFormat(pin)).isFalse();
    }

    @Test
    void onlyTheJsonValueTrueTicksTheBox() {
        DoseInput base = input(KEY, "2026-11-11T02:30:00Z", "GIVEN");
        assertThat(DoseRules.confirmed(withBox(base, Boolean.TRUE))).isTrue();
        assertThat(DoseRules.confirmed(withBox(base, Boolean.FALSE))).isFalse();
        assertThat(DoseRules.confirmed(withBox(base, null))).isFalse();
        assertThat(DoseRules.confirmed(withBox(base, "true"))).isFalse();
        assertThat(DoseRules.confirmed(withBox(base, 1))).isFalse();
        assertThat(DoseRules.confirmed(null)).isFalse();
    }

    private static DoseInput withBox(DoseInput base, Object box) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.outcome(), null, box, null, null, null);
    }

    @Test
    void theSecondNurseIdMustBeACanonicalUuid() {
        DoseInput base = input(KEY, "2026-11-11T02:30:00Z", "GIVEN");
        assertThat(DoseRules.secondNurse(new DoseInput(KEY, base.slotAt(), "GIVEN", null, null, KEY, null, null)))
                .isEqualTo(UUID.fromString(KEY));
        assertThat(DoseRules.secondNurse(new DoseInput(KEY, base.slotAt(), "GIVEN", null, null, "1-1-1-1-1", null,
                null))).isNull();
        assertThat(DoseRules.secondNurse(base)).isNull();
        assertThat(DoseRules.secondNurse(null)).isNull();
    }

    @Test
    void theReasonIsOneOfTheFiveKeys() {
        DoseInput base = input(KEY, "2026-11-11T02:30:00Z", "NOT_GIVEN");
        for (NotGivenReason reason : NotGivenReason.values()) {
            assertThat(DoseRules.reason(new DoseInput(KEY, base.slotAt(), "NOT_GIVEN", null, null, null, null,
                    reason.key()))).isEqualTo(reason);
        }
        assertThat(DoseRules.reason(new DoseInput(KEY, base.slotAt(), "NOT_GIVEN", null, null, null, null,
                "OTHER"))).isNull();
        assertThat(DoseRules.reason(base)).isNull();
        assertThat(DoseRules.reason(null)).isNull();
    }

    // ---- the texts of the contract ----

    @Test
    void theTextsAreTheContractTexts() {
        assertThat(DoseRules.ONLY_GIVERS).isEqualTo("Only nurses and the clinic owner can give medicines.");
        assertThat(DoseRules.SCAN_FIRST).isEqualTo("Scan the wristband first.");
        assertThat(DoseRules.MISMATCH).isEqualTo(
                "Wristband does not match this patient. Stop. Check the patient before giving anything.");
        assertThat(DoseRules.CONFIRM).isEqualTo("Confirm the second identifier: name and date of birth.");
        assertThat(DoseRules.HIGH_ALERT).isEqualTo(
                "High-alert medicine: a second nurse must check it and enter a 4-digit PIN.");
        assertThat(DoseRules.DIFFERENT_NURSE).isEqualTo("The second check must be a different nurse.");
        assertThat(DoseRules.CHOOSE_NURSE).isEqualTo("Choose a nurse from the list.");
        assertThat(DoseRules.CHOOSE_REASON).isEqualTo("Choose why the dose is not given.");
        assertThat(DoseRules.NOT_ADMITTED).isEqualTo("This patient is not admitted any more. Doses cannot be recorded.");
        assertThat(DoseRules.NOT_VERIFIED).isEqualTo("This order is not checked by the pharmacist yet.");
        assertThat(DoseRules.STOPPED).isEqualTo("This medicine was stopped. Its doses can no longer be recorded.");
        assertThat(DoseRules.REJECTED).isEqualTo("This order was rejected by the pharmacist. It cannot be given.");
        assertThat(DoseRules.NOT_A_DOSE_TIME).isEqualTo(
                "This is not a dose time of this medicine today. Reload the page and try again.");
        assertThat(DoseRules.BEFORE_START).isEqualTo("This dose time is before the pharmacist checked the order.");
        assertThat(DoseRules.NOT_DUE).isEqualTo(
                "This dose is not due yet. It can be given from 1 hour before its time.");
        assertThat(DoseRules.RECORDED).isEqualTo("This dose is already recorded.");
        assertThat(DoseRules.KEY_REUSED).isEqualTo(
                "This dose was already recorded with different values. Reload the page.");
    }

    // ---- the event templates ----

    @Test
    void theGaveEventForAMedicineThatIsNotHighAlert() {
        assertThat(DoseRules.gaveText(PARACETAMOL, EIGHT_AM, null))
                .isEqualTo("Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID");
    }

    @Test
    void theGaveEventForAHighAlertMedicineNamesTheSecondNurse() {
        assertThat(DoseRules.gaveText(HEPARIN, Instant.parse("2026-11-11T14:30:00Z"), "Sr. Rupali More"))
                .isEqualTo("Gave Inj. Heparin 5,000 IU (8 PM dose) · identity checked against UHID · second check by Sr. Rupali More");
    }

    @Test
    void theNotGivenEventHasTheReasonLabel() {
        assertThat(DoseRules.notGivenText(PARACETAMOL, Instant.parse("2026-11-11T03:00:00Z"),
                NotGivenReason.HELD_DOCTOR))
                .isEqualTo("Did not give Tab. Paracetamol 650 mg (8:30 AM dose) · Held on doctor's advice");
    }

    @Test
    void theDeniedEvent() {
        assertThat(DoseRules.deniedText("SJ-00043", "SJ-00042", HEPARIN)).isEqualTo(
                "Stopped at the identity check: entered SJ-00043, expected SJ-00042 (Inj. Heparin 5,000 IU)");
    }

    private static Drug longestDrug() {
        return SeedCatalogue.catalogue().drugs().stream()
                .max(java.util.Comparator.comparingInt(d -> d.name().length())).orElseThrow();
    }

    @Test
    void theGiveTextWithA200CharacterSecondNurseNameAndTheLongestDrugStaysWithinThreeHundred() {
        Drug longest = longestDrug();
        String second = "A".repeat(TeamNameLimit.MAX);

        String text = DoseRules.gaveText(longest, EIGHT_AM, second);

        assertThat(text.length()).isLessThanOrEqualTo(300);
        assertThat(text).startsWith("Gave " + longest.name() + " (")
                .contains(" · identity checked against UHID · second check by A")
                .endsWith("…");
    }

    @Test
    void aShortSecondNurseNameIsNotShortened() {
        Drug longest = longestDrug();
        String name = "N".repeat(80);

        String text = DoseRules.gaveText(longest, EIGHT_AM, name);

        assertThat(text).endsWith(" · second check by " + name).doesNotContain("…");
    }

    @Test
    void theGiveTextIsCutOnlyWhenTheNameDoesNotFitAndThenFillsTheLimit() {
        Drug longest = longestDrug();
        String fixedPart = DoseRules.gaveText(longest, EIGHT_AM, null) + " · second check by ";
        int room = 300 - fixedPart.length();

        assertThat(DoseRules.gaveText(longest, EIGHT_AM, "B".repeat(room))).hasSize(300).doesNotContain("…");
        String cut = DoseRules.gaveText(longest, EIGHT_AM, "B".repeat(room + 1));
        assertThat(cut).hasSize(300).endsWith("B…");
    }

    @Test
    void aNameCutThroughASurrogatePairKeepsNoHalfCharacter() {
        Drug longest = longestDrug();
        String fixedPart = DoseRules.gaveText(longest, EIGHT_AM, null) + " · second check by ";
        int room = 300 - fixedPart.length();
        // Place a supplementary character so the cut falls between its two UTF-16 units.
        String name = "C".repeat(room - 2) + "😀" + "D".repeat(20);

        String text = DoseRules.gaveText(longest, EIGHT_AM, name);

        assertThat(text.length()).isLessThanOrEqualTo(300);
        assertThat(Character.isHighSurrogate(text.charAt(text.length() - 2))).isFalse();
    }

    @Test
    void everyCatalogueDrugKeepsEveryEventTextWithinThreeHundred() {
        String second = "A".repeat(TeamNameLimit.MAX);
        String scan = "ABCDEF-" + "1".repeat(34);
        for (Drug drug : SeedCatalogue.catalogue().drugs()) {
            assertThat(DoseRules.gaveText(drug, EIGHT_AM, second).length()).as(drug.key()).isLessThanOrEqualTo(300);
            assertThat(DoseRules.gaveText(drug, EIGHT_AM, null).length()).as(drug.key()).isLessThanOrEqualTo(300);
            assertThat(DoseRules.deniedText(scan, scan, drug).length()).as(drug.key()).isLessThanOrEqualTo(300);
            for (NotGivenReason reason : NotGivenReason.values()) {
                assertThat(DoseRules.notGivenText(drug, EIGHT_AM, reason).length()).isLessThanOrEqualTo(300);
            }
        }
    }

    @Test
    void anOverlongMismatchTextIsCutToTheLimitWithAnEllipsis() {
        String text = DoseRules.deniedText("X".repeat(400), "Y".repeat(10), longestDrug());

        assertThat(text).hasSize(300).endsWith("…");
    }

    /** The longest name a team member can have ({@code TeamService.MAX_NAME_LENGTH}, 200). */
    private static final class TeamNameLimit {
        static final int MAX = 200;
    }

    @Test
    void theReasonsKeepTheMockupOrderAndStoreRefusedOnlyForARefusal() {
        assertThat(NotGivenReason.values()).extracting(NotGivenReason::key).containsExactly("PATIENT_REFUSED",
                "HELD_DOCTOR", "NIL_BY_MOUTH", "NOT_AVAILABLE", "AWAY_FROM_WARD");
        assertThat(NotGivenReason.PATIENT_REFUSED.outcome().name()).isEqualTo("REFUSED");
        for (NotGivenReason reason : NotGivenReason.values()) {
            if (reason != NotGivenReason.PATIENT_REFUSED) {
                assertThat(reason.outcome().name()).isEqualTo("HELD");
            }
        }
        assertThat(NotGivenReason.ofKey("HELD_DOCTOR")).contains(NotGivenReason.HELD_DOCTOR);
        assertThat(NotGivenReason.ofKey("held_doctor")).isEmpty();
        assertThat(NotGivenReason.ofKey(null)).isEmpty();
    }
}
