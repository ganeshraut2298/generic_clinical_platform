package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.application.MedicineRules.Change;
import com.sahaj.ipd.application.MedicineRules.Keyed;
import com.sahaj.ipd.application.MedicineRules.Order;
import com.sahaj.ipd.application.MedicineViews.ChangeOrderInput;
import com.sahaj.ipd.application.MedicineViews.HoldOrderInput;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.QueryOrderInput;
import com.sahaj.ipd.application.MedicineViews.QueryReplyInput;
import com.sahaj.ipd.application.MedicineViews.ResumeOrderInput;
import com.sahaj.ipd.application.MedicineViews.StopOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76 the checked bodies of the new and widened order actions, with the texts of docs/api/ipd.yaml 0.4.0. */
class MedicineRulesBodiesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
    private static final String KEY = "3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11";
    private static final String TOKEN = "Qm9yZGVyLWNoZWNrLXRva2";

    private static List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        throw new AssertionError("no field error");
    }

    private static MedicineOrderInput order(String drug, String frequency, String priority, String indication) {
        return new MedicineOrderInput(KEY, drug, frequency, null, null, BigDecimal.ONE, "MG", "ORAL", null, null,
                indication, priority, null, null);
    }

    // ---- the order ----

    @Test
    void aStrictOrderCarriesTheCheckedValues() {
        Order order = MedicineRules.strictOrder(SeedCatalogue.catalogue(), order("pcm", "tds", "URGENT", null), NOW);

        assertThat(order.frequency()).isEqualTo(Frequency.TDS);
        assertThat(order.values().priority().name()).isEqualTo("URGENT");
        assertThat(order.values().kind()).isEqualTo(Kind.SCHEDULED);
    }

    @Test
    void aPrnOrderHasThePrnFrequencyAndAStatOrderHasNone() {
        MedicineOrderInput prn = new MedicineOrderInput(KEY, "pcm", "prn", null, null, BigDecimal.ONE, "MG", "ORAL",
                null, null, "Fever", "ROUTINE", 360, 4);
        Order prnOrder = MedicineRules.strictOrder(SeedCatalogue.catalogue(), prn, NOW);
        Order stat = MedicineRules.strictOrder(SeedCatalogue.catalogue(), order("ondan", null, "STAT", null), NOW);

        assertThat(prnOrder.frequency()).isEqualTo(Frequency.PRN);
        assertThat(prnOrder.values().kind()).isEqualTo(Kind.PRN);
        assertThat(stat.frequency()).isNull();
        assertThat(stat.values().kind()).isEqualTo(Kind.ONCE);
    }

    @Test
    void theOrderErrorsKeepTheSah31OrderAndThenTheNewFields() {
        MedicineOrderInput bad = new MedicineOrderInput("x", "zzz", "qd", "OTHER", "short", null, null, null, null, 0,
                null, "ROUTINE", null, null);

        List<FieldMessage> errors = errorsOf(() -> MedicineRules.strictOrder(SeedCatalogue.catalogue(), bad, NOW));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("clientKey", "drugKey", "frequencyKey",
                "overrideReasonKey", "checkToken", "doseAmount", "doseUnit", "routeKey", "durationDays");
    }

    @Test
    void theLenientPortOrderNeverAcceptsPrn() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.order(SeedCatalogue.catalogue(),
                new MedicineOrderInput(KEY, "pcm", "prn", null, null)));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("frequencyKey");
    }

    // ---- verify ----

    @Test
    void verifyNeedsTheVersionTheSheetShowed() {
        assertThat(MedicineRules.verify(new VerifyOrderInput("4821", TOKEN, 2)).orderVersion()).isEqualTo(2);
        for (Integer bad : new Integer[] {null, 0, -3}) {
            List<FieldMessage> errors = errorsOf(() -> MedicineRules.verify(new VerifyOrderInput("4821", TOKEN, bad)));
            assertThat(errors).containsExactly(new FieldMessage("orderVersion", "Reload the page and try again."));
        }
    }

    // ---- stop, hold, resume ----

    @Test
    void stopNeedsTheKeyTheVersionAndAReasonOfFourToTwoHundredCharacters() {
        Keyed ok = MedicineRules.stop(new StopOrderInput(KEY, 3, "  No longer needed  "));
        assertThat(ok.clientKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(ok.orderVersion()).isEqualTo(3);
        assertThat(ok.text()).isEqualTo("No longer needed");

        for (String bad : new String[] {null, "", "   ", "abc", "x".repeat(201)}) {
            assertThat(errorsOf(() -> MedicineRules.stop(new StopOrderInput(KEY, 1, bad)))).as("reason %s", bad)
                    .containsExactly(new FieldMessage("reason", "Write why you are stopping this medicine."));
        }
        assertThat(MedicineRules.stop(new StopOrderInput(KEY, 1, "abcd")).text()).isEqualTo("abcd");
        assertThat(MedicineRules.stop(new StopOrderInput(KEY, 1, "x".repeat(200))).text()).hasSize(200);
    }

    @Test
    void theKeyAndTheVersionAreReloadErrorsInOrder() {
        List<FieldMessage> errors = errorsOf(() -> MedicineRules.stop(new StopOrderInput("nope", 0, "No longer needed")));

        assertThat(errors).containsExactly(new FieldMessage("clientKey", "Reload the page and try again."),
                new FieldMessage("orderVersion", "Reload the page and try again."));
        assertThat(errorsOf(() -> MedicineRules.stop(null))).extracting(FieldMessage::field)
                .containsExactly("clientKey", "orderVersion", "reason");
    }

    @Test
    void holdUsesItsOwnReasonText() {
        assertThat(MedicineRules.hold(new HoldOrderInput(KEY, 1, "Procedure at noon")).text())
                .isEqualTo("Procedure at noon");
        assertThat(errorsOf(() -> MedicineRules.hold(new HoldOrderInput(KEY, 1, "no"))))
                .containsExactly(new FieldMessage("reason", "Write why you are holding this medicine."));
    }

    @Test
    void resumeNeedsOnlyTheKeyAndTheVersion() {
        Keyed ok = MedicineRules.resume(new ResumeOrderInput(KEY, 2));

        assertThat(ok.orderVersion()).isEqualTo(2);
        assertThat(ok.text()).isNull();
        assertThat(errorsOf(() -> MedicineRules.resume(new ResumeOrderInput(null, null)))).extracting(FieldMessage::field)
                .containsExactly("clientKey", "orderVersion");
    }

    // ---- query and reply ----

    @Test
    void aQuestionIsOneToThreeHundredCharacters() {
        assertThat(MedicineRules.query(new QueryOrderInput(KEY, 1, "Is the dose right?")).text())
                .isEqualTo("Is the dose right?");
        assertThat(MedicineRules.query(new QueryOrderInput(KEY, 1, "q".repeat(300))).text()).hasSize(300);
        for (String empty : new String[] {null, "", "  "}) {
            assertThat(errorsOf(() -> MedicineRules.query(new QueryOrderInput(KEY, 1, empty))))
                    .containsExactly(new FieldMessage("question", "Write your question for the doctor."));
        }
        assertThat(errorsOf(() -> MedicineRules.query(new QueryOrderInput(KEY, 1, "q".repeat(301)))))
                .containsExactly(new FieldMessage("question", "Keep the question to 300 characters."));
    }

    @Test
    void aReplyIsOneToThreeHundredCharacters() {
        assertThat(MedicineRules.reply(new QueryReplyInput(KEY, 1, "Yes, as written")).text())
                .isEqualTo("Yes, as written");
        assertThat(errorsOf(() -> MedicineRules.reply(new QueryReplyInput(KEY, 1, " "))))
                .containsExactly(new FieldMessage("reply", "Write your reply."));
        assertThat(errorsOf(() -> MedicineRules.reply(new QueryReplyInput(KEY, 1, "r".repeat(301)))))
                .containsExactly(new FieldMessage("reply", "Keep the reply to 300 characters."));
    }

    @Test
    void theClinicalTextOfABodyNeverReachesToString() {
        assertThat(new StopOrderInput(KEY, 1, "Secret reason").toString()).doesNotContain("Secret");
        assertThat(new HoldOrderInput(KEY, 1, "Secret reason").toString()).doesNotContain("Secret");
        assertThat(new QueryOrderInput(KEY, 1, "Secret question").toString()).doesNotContain("Secret");
        assertThat(new QueryReplyInput(KEY, 1, "Secret reply").toString()).doesNotContain("Secret");
        assertThat(MedicineRules.stop(new StopOrderInput(KEY, 1, "Secret reason")).toString())
                .doesNotContain("Secret");
    }

    // ---- change ----

    private static ChangeOrderInput change(String reason, String frequency, String priority) {
        return new ChangeOrderInput(KEY, 1, reason, BigDecimal.TEN, "MG", "ORAL", frequency, null, null, null, priority,
                null, null);
    }

    @Test
    void aChangeCarriesTheReasonAndTheNewValues() {
        Change change = MedicineRules.change(SeedCatalogue.drug("pcm"), change("Dose too low", "bd", "ROUTINE"), NOW);

        assertThat(change.reason()).isEqualTo("Dose too low");
        assertThat(change.orderVersion()).isEqualTo(1);
        assertThat(change.values().frequency()).isEqualTo(Frequency.BD);
    }

    @Test
    void aChangeNeedsAReasonOfFourOrMoreCharactersBeforeTheValues() {
        ChangeOrderInput bad = new ChangeOrderInput(KEY, 1, "no", null, null, null, "qd", null, null, null, "ROUTINE",
                null, null);

        assertThat(errorsOf(() -> MedicineRules.change(SeedCatalogue.drug("pcm"), bad, NOW)))
                .extracting(FieldMessage::field).containsExactly("reason", "frequencyKey", "doseAmount", "doseUnit",
                        "routeKey");
        assertThat(errorsOf(() -> MedicineRules.change(SeedCatalogue.drug("pcm"), change("no", "bd", "ROUTINE"), NOW))
                .get(0).message()).isEqualTo("Write why you are changing this order.");
    }

    @Test
    void aChangeToStatNeedsNoFrequency() {
        Change change = MedicineRules.change(SeedCatalogue.drug("pcm"), change("Needed now", null, "STAT"), NOW);

        assertThat(change.values().kind()).isEqualTo(Kind.ONCE);
        assertThatThrownBy(() -> MedicineRules.change(SeedCatalogue.drug("pcm"), change("Needed now", "bd", "STAT"), NOW))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theClinicalEventTemplatesCarryTheMedicineNameAndNumbersOnly() {
        var drug = SeedCatalogue.drug("pcm");

        assertThat(MedicineRules.changedText(drug, 3)).isEqualTo("Changed Tab. Paracetamol 650 mg (v3)");
        assertThat(MedicineRules.heldText(drug)).isEqualTo("Held Tab. Paracetamol 650 mg");
        assertThat(MedicineRules.resumedText(drug)).isEqualTo("Resumed Tab. Paracetamol 650 mg");
        assertThat(MedicineRules.queriedText(drug)).isEqualTo("Queried Tab. Paracetamol 650 mg");
        assertThat(MedicineRules.queryRepliedText(drug)).isEqualTo("Replied to query on Tab. Paracetamol 650 mg");
        assertThat(MedicineRules.reviewedText(drug))
                .isEqualTo("Reviewed the dose of Tab. Paracetamol 650 mg given before the pharmacist check");
    }

    @Test
    void theOrderedTextNamesStatAndAsNeeded() {
        var drug = SeedCatalogue.drug("pcm");
        Order stat = MedicineRules.strictOrder(SeedCatalogue.catalogue(), order("pcm", null, "STAT", null), NOW);
        MedicineOrderInput prnInput = new MedicineOrderInput(KEY, "pcm", "prn", null, null, BigDecimal.ONE, "MG", "ORAL",
                null, null, "Fever", "ROUTINE", 60, 3);
        Order prn = MedicineRules.strictOrder(SeedCatalogue.catalogue(), prnInput, NOW);
        Order plain = MedicineRules.strictOrder(SeedCatalogue.catalogue(), order("pcm", "bd", "ROUTINE", null), NOW);

        assertThat(MedicineRules.orderedText(drug, stat.values(), null)).isEqualTo("Ordered Tab. Paracetamol 650 mg · STAT");
        assertThat(MedicineRules.orderedText(drug, prn.values(), null))
                .isEqualTo("Ordered Tab. Paracetamol 650 mg · as needed");
        assertThat(MedicineRules.orderedText(drug, plain.values(), null))
                .isEqualTo("Ordered Tab. Paracetamol 650 mg twice daily");
    }
}
