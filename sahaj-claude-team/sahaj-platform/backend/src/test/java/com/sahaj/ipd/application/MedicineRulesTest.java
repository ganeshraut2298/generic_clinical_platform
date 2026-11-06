package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import com.sahaj.ipd.application.MedicineRules.Order;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.ipd.domain.RejectReason;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-31 the body checks, the page cursor and the clinical event templates. */
class MedicineRulesTest {

    private static final DrugCatalogue CATALOGUE = SeedCatalogue.catalogue();
    private static final String KEY = "3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11";
    private static final String TOKEN = "Qm9yZGVyLWNoZWNrLXRva2";

    private static List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            assertThat(e.firstMessageAsDetail()).isTrue();
            return e.errors();
        }
        throw new AssertionError("expected InvalidFieldsException");
    }

    // ---- the order body ----

    @Test
    void aValidOrderBodyIsChecked() {
        Order order = MedicineRules.order(CATALOGUE, new MedicineOrderInput(KEY, "asp", "od", null, null));

        assertThat(order.clientKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(order.drug().key()).isEqualTo("asp");
        assertThat(order.frequency()).isEqualTo(Frequency.OD);
        assertThat(order.reason()).isNull();
        assertThat(order.checkToken()).isNull();
    }

    @Test
    void aReasonAndATokenAreKept() {
        Order order = MedicineRules.order(CATALOGUE,
                new MedicineOrderInput(KEY, "asp", "od", "CARDIOLOGY_STENT", TOKEN));

        assertThat(order.reason()).isEqualTo(OverrideReason.CARDIOLOGY_STENT);
        assertThat(order.checkToken()).isEqualTo(TOKEN);
    }

    @Test
    void everyFieldErrorComesTogetherInTheContractOrder() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.order(CATALOGUE,
                new MedicineOrderInput("nope", "zzz", "qd", "OTHER", "short")));

        assertThat(errors).extracting(FieldMessage::field)
                .containsExactly("clientKey", "drugKey", "frequencyKey", "overrideReasonKey", "checkToken");
        assertThat(errors).extracting(FieldMessage::message).containsExactly("Reload the page and try again.",
                "Choose a medicine from the list", "Choose how often", "Choose a reason from the list",
                "Reload the page and try again.");
    }

    @Test
    void missingFieldsAreErrorsButAnAbsentReasonAndTokenAreNot() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.order(CATALOGUE,
                new MedicineOrderInput(null, null, null, null, null)));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("clientKey", "drugKey", "frequencyKey");
    }

    @Test
    void aNullBodyIsRefusedWithTheThreeRequiredFields() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.order(CATALOGUE, null));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("clientKey", "drugKey", "frequencyKey");
    }

    @Test
    void anEmptyReasonStringIsAnErrorAndTheKeysAreCaseSensitive() {
        assertThat(errorsOf(() -> MedicineRules.order(CATALOGUE,
                new MedicineOrderInput(KEY, "asp", "od", "", null)))).extracting(FieldMessage::field)
                .containsExactly("overrideReasonKey");
        assertThat(errorsOf(() -> MedicineRules.order(CATALOGUE,
                new MedicineOrderInput(KEY, "ASP", "OD", null, null)))).extracting(FieldMessage::field)
                .containsExactly("drugKey", "frequencyKey");
    }

    @Test
    void aShortGroupedKeyIsNotAClientKey() {
        assertThat(errorsOf(() -> MedicineRules.order(CATALOGUE,
                new MedicineOrderInput("1-1-1-1-1", "asp", "od", null, null)))).extracting(FieldMessage::field)
                .containsExactly("clientKey");
    }

    @Test
    void theTokenMustBeExactly22UrlSafeCharacters() {
        for (String bad : new String[] {"", "short", TOKEN + "x", "Qm9yZGVyLWNoZWNrLXR+a2", "Qm9yZGVyLWNoZWNrLXR a2"}) {
            assertThat(errorsOf(() -> MedicineRules.order(CATALOGUE,
                    new MedicineOrderInput(KEY, "asp", "od", null, bad)))).extracting(FieldMessage::field)
                    .containsExactly("checkToken");
        }
    }

    @Test
    void anOrderBodyNeverShowsInToString() {
        assertThat(new MedicineOrderInput(KEY, "asp", "od", null, TOKEN).toString()).doesNotContain(KEY)
                .doesNotContain(TOKEN);
    }

    // ---- verify and reject bodies ----

    @Test
    void aVerifyBodyNeedsAFourDigitPinAndATokenAndNeverShowsThem() {
        MedicineRules.Verify body = MedicineRules.verify(new VerifyOrderInput("0482", TOKEN));

        assertThat(body.pin()).isEqualTo("0482");
        assertThat(body.checkToken()).isEqualTo(TOKEN);
        assertThat(body.toString()).doesNotContain("0482").doesNotContain(TOKEN);
        assertThat(new VerifyOrderInput("0482", TOKEN).toString()).doesNotContain("0482");
    }

    @Test
    void thePinMustBeExactlyFourAsciiDigits() {
        for (String bad : new String[] {null, "", "123", "12345", "12a4", " 123", "1 23", "١٢٣٤",
            "1234\n"}) {
            List<FieldMessage> errors = errorsOf(() -> MedicineRules.verify(new VerifyOrderInput(bad, TOKEN)));
            assertThat(errors).extracting(FieldMessage::field).containsExactly("pin");
            assertThat(errors.get(0).message()).isEqualTo("Enter the 4-digit PIN.");
        }
    }

    @Test
    void verifyErrorsComeInTheOrderPinThenToken() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.verify(new VerifyOrderInput("x", null)));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("pin", "checkToken");
        assertThat(errors.get(1).message()).isEqualTo("Reload the page and try again.");
        assertThat(errorsOf(() -> MedicineRules.verify(null))).extracting(FieldMessage::field)
                .containsExactly("pin", "checkToken");
    }

    @Test
    void aRejectBodyNeedsOneOfTheFourReasons() {
        assertThat(MedicineRules.reject(new RejectOrderInput("INTERACTION_RISK"))).isEqualTo(RejectReason.INTERACTION_RISK);
        for (RejectOrderInput bad : new RejectOrderInput[] {null, new RejectOrderInput(null),
            new RejectOrderInput(""), new RejectOrderInput("OTHER")}) {
            List<FieldMessage> errors = errorsOf(() -> MedicineRules.reject(bad));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).field()).isEqualTo("reasonKey");
            assertThat(errors.get(0).message()).isEqualTo("Choose a reason");
        }
    }

    // ---- paging ----

    @Test
    void theLimitDefaultsTo50AndAcceptsOneToOneHundred() {
        assertThat(MedicineRules.limit(null)).isEqualTo(50);
        assertThat(MedicineRules.limit("1")).isEqualTo(1);
        assertThat(MedicineRules.limit("100")).isEqualTo(100);
        for (String bad : new String[] {"0", "101", "-1", "abc", "", "1.5", "1000", " 5"}) {
            assertThat(MedicineRules.limit(bad)).as(bad).isNull();
        }
    }

    @Test
    void theCursorRoundTripsInAtMost64CharactersWithMicrosecondPrecision() {
        Position position = new Position(Instant.parse("2026-11-11T06:00:00.123456Z"), UUID.randomUUID());

        String cursor = MedicineRules.encodeCursor(position);

        assertThat(cursor).hasSize(32).matches("^[A-Za-z0-9_-]+$");
        assertThat(MedicineRules.decodeCursor(cursor)).isEqualTo(position);
    }

    @Test
    void aCursorThatIsNotOursIsRefused() {
        for (String bad : new String[] {null, "", "abc", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "/////////////////////////////////"}) {
            assertThat(MedicineRules.decodeCursor(bad)).as(String.valueOf(bad)).isNull();
        }
        // A negative time (first bit set) is not a cursor either.
        assertThat(MedicineRules.decodeCursor("_____________________________AAA")).isNull();
    }

    // ---- clinical event templates (ADR-009 D7) ----

    @Test
    void theEventTemplatesAreTheFixedWordsOfTheContract() {
        var aspirin = SeedCatalogue.drug("asp");

        assertThat(MedicineRules.OPENED_TEXT).isEqualTo("Opened the clinical record");
        assertThat(MedicineRules.orderedText(aspirin, Frequency.BD, null))
                .isEqualTo("Ordered Tab. Aspirin 75 mg twice daily");
        assertThat(MedicineRules.orderedText(aspirin, Frequency.Q8, OverrideReason.CARDIOLOGY_STENT))
                .isEqualTo("Ordered Tab. Aspirin 75 mg 8-hourly · override: Recent coronary stent, cardiology advised");
        assertThat(MedicineRules.blockedText(aspirin, "Documented penicillin allergy. X is a penicillin."))
                .isEqualTo("Order blocked: Tab. Aspirin 75 mg · Documented penicillin allergy. X is a penicillin.");
        assertThat(MedicineRules.stoppedText(aspirin)).isEqualTo("Stopped Tab. Aspirin 75 mg");
        assertThat(MedicineRules.verifiedText(aspirin, false)).isEqualTo("Verified Tab. Aspirin 75 mg");
        assertThat(MedicineRules.verifiedText(aspirin, true))
                .isEqualTo("Verified Tab. Aspirin 75 mg · accepted doctor's reason");
        assertThat(MedicineRules.rejectedText(aspirin, RejectReason.WRONG_DOSE))
                .isEqualTo("Rejected Tab. Aspirin 75 mg · Wrong dose or frequency");
    }

    @Test
    void theLongestEventTextStaysWithinTheDatabaseLimitOf300() {
        var longest = SeedCatalogue.drugs().stream().max((a, b) -> a.name().length() - b.name().length()).orElseThrow();

        assertThat(MedicineRules.orderedText(longest, Frequency.TDS, OverrideReason.ALLERGY_HISTORY_CHECKED).length())
                .isLessThanOrEqualTo(300);
        assertThat(MedicineRules.blockedText(longest, longest.name() + " is already on the chart. Change the existing"
                + " order instead of adding a second one.").length()).isLessThanOrEqualTo(300);
    }

    @Test
    void theRolesAreTheOnesOfTheDecision() {
        assertThat(MedicineRules.ORDER_ROLES).containsExactlyInAnyOrder("OWNER", "DOCTOR");
        assertThat(MedicineRules.EMR_ROLES).containsExactlyInAnyOrder("OWNER", "DOCTOR", "NURSE");
        assertThat(MedicineRules.PHARMACIST_ROLES).containsExactlyInAnyOrder("OWNER", "PHARMACIST");
        assertThatThrownBy(() -> MedicineRules.ORDER_ROLES.add("NURSE")).isInstanceOf(UnsupportedOperationException.class);
    }
}
