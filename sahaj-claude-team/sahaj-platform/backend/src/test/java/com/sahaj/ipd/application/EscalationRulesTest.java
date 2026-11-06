package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.EscalationRules.Acknowledge;
import com.sahaj.ipd.application.EscalationRules.CallSenior;
import com.sahaj.ipd.application.EscalationRules.Respond;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-33 request-body checks: the version numbers, the action and the plan text (OQ-33-14). */
class EscalationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUIRED = "Write the plan and when vitals are next due.";
    private static final String LENGTH = "Keep the plan to 1,000 characters.";
    private static final String CHARACTERS = "Plan cannot contain control or text-direction characters";

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseInput respond(String action, String note) {
        return new ResponseInput(json("3"), action, note);
    }

    private static List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            assertThat(e.firstMessageAsDetail()).isTrue();
            return e.errors();
        }
        throw new AssertionError("expected a validation error");
    }

    // ---- the version numbers ----

    @Test
    void aWholeJsonNumberFromZeroIsAVersion() {
        assertThat(EscalationRules.acknowledge(new VersionInput(json("0")))).isEqualTo(new Acknowledge(0));
        assertThat(EscalationRules.acknowledge(new VersionInput(json("41")))).isEqualTo(new Acknowledge(41));
        assertThat(EscalationRules.acknowledge(new VersionInput(json("3000000000"))).version())
                .isEqualTo(3_000_000_000L);
    }

    @Test
    void aMissingNullNegativeStringFractionOrHugeVersionIsRefusedOnTheFieldVersion() {
        for (JsonNode bad : new JsonNode[] {null, json("null"), json("-1"), json("\"3\""), json("3.5"), json("3.0"),
            json("true"), json("[]"), json("{}"), json("99999999999999999999999")}) {
            List<FieldMessage> errors = errorsOf(() -> EscalationRules.acknowledge(new VersionInput(bad)));

            assertThat(errors).containsExactly(new FieldMessage("version", "Reload the page and try again."));
        }
        assertThat(errorsOf(() -> EscalationRules.acknowledge(null))).hasSize(1);
    }

    @Test
    void theSeniorCallCountIsAWholeNumberInTheIntegerRangeAndBothFieldsAreReportedTogether() {
        CallSenior ok = EscalationRules.callSenior(new SeniorCallInput(json("4"), json("2")));
        assertThat(ok).isEqualTo(new CallSenior(4, 2));

        List<FieldMessage> both = errorsOf(() -> EscalationRules.callSenior(new SeniorCallInput(null, json("-1"))));
        assertThat(both).extracting(FieldMessage::field).containsExactly("version", "seniorCalls");
        assertThat(both).extracting(FieldMessage::message).containsOnly("Reload the page and try again.");

        assertThat(errorsOf(() -> EscalationRules.callSenior(new SeniorCallInput(json("1"), json("\"1\""))))
                .get(0).field()).isEqualTo("seniorCalls");
        assertThat(errorsOf(() -> EscalationRules.callSenior(new SeniorCallInput(json("1"), json("2147483648"))))
                .get(0).field()).isEqualTo("seniorCalls");
        assertThat(errorsOf(() -> EscalationRules.callSenior(null))).hasSize(2);
    }

    // ---- the action ----

    @Test
    void theFiveDoctorActionsAreAccepted() {
        for (ResponseAction action : ResponseAction.doctorChoices()) {
            Respond body = EscalationRules.respond(respond(action.name(), "Plan"));

            assertThat(body.action()).isEqualTo(action);
            assertThat(body.version()).isEqualTo(3);
            assertThat(body.note()).isEqualTo("Plan");
        }
    }

    @Test
    void closedAtDischargeAnUnknownOrAMissingActionIsRefusedOnTheFieldAction() {
        for (String bad : new String[] {"CLOSED_AT_DISCHARGE", "OTHER", "", " BEDSIDE_REVIEW", "bedside_review",
            null}) {
            List<FieldMessage> errors = errorsOf(() -> EscalationRules.respond(respond(bad, "Plan")));

            assertThat(errors).containsExactly(new FieldMessage("action", "Choose what was done"));
        }
    }

    @Test
    void everyFieldErrorIsReportedTogetherInTheOrderVersionActionNote() {
        List<FieldMessage> errors = errorsOf(() -> EscalationRules.respond(new ResponseInput(null, "X", "   ")));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("version", "action", "note");
        assertThat(errors).extracting(FieldMessage::message).containsExactly("Reload the page and try again.",
                "Choose what was done", REQUIRED);
        assertThat(errorsOf(() -> EscalationRules.respond(null))).extracting(FieldMessage::field)
                .containsExactly("version", "action", "note");
    }

    // ---- the plan ----

    @Test
    void anEmptyOrBlankOrMissingPlanIsTheContractsMessage() {
        for (String bad : new String[] {null, "", "   ", "\n\n", "\t \r\n"}) {
            assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", bad))))
                    .containsExactly(new FieldMessage("note", REQUIRED));
        }
    }

    @Test
    void aPlanOfNoBreakAndOtherSpaceSeparatorsIsEmptyLikeABlankPlan() {
        for (String bad : new String[] {"\u00a0\u2007\u202f\u3000", "\u00a0\u00a0\u00a0", "\u2007", "\u202f \n\u3000", "\u2003\u2009",
            "\u00a0\n\u00a0\r\n \t"}) {
            assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", bad))))
                    .as(bad.codePoints().mapToObj(c -> String.format("U+%04X", c)).toList().toString())
                    .containsExactly(new FieldMessage("note", REQUIRED));
        }
    }

    @Test
    void aPlanWithSpaceSeparatorsAtTheEndsIsStrippedAndStored() {
        assertThat(EscalationRules.respond(respond("NO_CHANGE", "\u00a0\u3000O2 up\u202f\u2007")).note())
                .isEqualTo("O2 up");
    }

    @Test
    void thePlanIsStrippedAndLineBreaksAreKeptAsLineFeeds() {
        Respond body = EscalationRules.respond(respond("NO_CHANGE", "  O2 up to 6 L\r\nrepeat vitals 30 min\rthen 1 h \n"));

        assertThat(body.note()).isEqualTo("O2 up to 6 L\nrepeat vitals 30 min\nthen 1 h");
    }

    @Test
    void aTabBecomesOneSpace() {
        assertThat(EscalationRules.respond(respond("NO_CHANGE", "a\tb")).note()).isEqualTo("a b");
    }

    @Test
    void thePlanIsComposedToUnicodeNfc() {
        // "e" followed by a combining acute accent becomes the single letter e with acute
        assertThat(EscalationRules.respond(respond("NO_CHANGE", "café")).note()).isEqualTo("café");
    }

    @Test
    void controlAndTextDirectionCharactersAreRefusedWithTheContractsMessage() {
        String[] bad = {"plan\u0000", "plan\u0007", "plan‮", "plan​", "plan⁦", "﻿plan",
            "plan ", "plan\u009f", "plan‍", "a­b", "plan\u0085"};
        for (String text : bad) {
            assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", text))))
                    .as(text.codePoints().boxed().toList().toString())
                    .containsExactly(new FieldMessage("note", CHARACTERS));
        }
    }

    @Test
    void aJoinerAfterAViramaInAnIndianScriptIsAllowed() {
        // Devanagari: KA + virama + ZWJ... joiner must be directly after a virama and before a letter
        String text = "क्‍ष plan";

        assertThat(EscalationRules.respond(respond("NO_CHANGE", text)).note()).isEqualTo(text);
    }

    @Test
    void marathiAndOtherScriptsAreFine() {
        String text = "ऑक्सिजन वाढवले; ३० मिनिटांनी पुन्हा तपासा";

        assertThat(EscalationRules.respond(respond("NO_CHANGE", text)).note()).isEqualTo(text);
    }

    @Test
    void thePlanMayBeExactly1000CodePointsAndNotOneMore() {
        String ok = "a".repeat(1000);
        String tooLong = "a".repeat(1001);

        assertThat(EscalationRules.respond(respond("NO_CHANGE", ok)).note()).hasSize(1000);
        assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", tooLong))))
                .containsExactly(new FieldMessage("note", LENGTH));
    }

    @Test
    void theLengthCountsUnicodeCodePointsAndALineBreakCountsAsOne() {
        // 500 emoji are 1000 UTF-16 units but 500 code points
        String emoji = "😀".repeat(500);
        String emojiLong = "😀".repeat(1001);
        String breaks = "a\n".repeat(499) + "a";

        assertThat(EscalationRules.respond(respond("NO_CHANGE", emoji)).note().codePointCount(0, 1000))
                .isEqualTo(500);
        assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", emojiLong))))
                .containsExactly(new FieldMessage("note", LENGTH));
        assertThat(EscalationRules.respond(respond("NO_CHANGE", breaks)).note()).hasSize(999);
    }

    @Test
    void aRawPlanOver4000CodeUnitsIsRefusedBeforeAnyNormalisation() {
        String raw = "a".repeat(4001);

        assertThat(errorsOf(() -> EscalationRules.respond(respond("NO_CHANGE", raw))))
                .containsExactly(new FieldMessage("note", LENGTH));
        // 4,000 code units that shrink under stripping are still judged on the stripped length
        String padded = " ".repeat(3000) + "a".repeat(1000);
        assertThat(EscalationRules.respond(respond("NO_CHANGE", padded)).note()).hasSize(1000);
    }

    @Test
    void theCheckedBodyAndItsMessagesNeverEchoThePlan() {
        String secret = "Chest pain, Ramesh Kulkarni, 9876543210";
        Respond body = EscalationRules.respond(respond("NO_CHANGE", secret));

        assertThat(body.toString()).doesNotContain("Ramesh").doesNotContain("9876543210");
        assertThat(respond("NO_CHANGE", secret).toString()).doesNotContain("Ramesh").doesNotContain("9876543210");
        assertThatThrownBy(() -> EscalationRules.respond(respond("NO_CHANGE", secret + "\u0000")))
                .isInstanceOf(InvalidFieldsException.class)
                .satisfies(e -> assertThat(((InvalidFieldsException) e).errors().toString()).doesNotContain("Ramesh"));
    }

    // ---- who may do what ----

    @Test
    void theRoleSetsFollowDec015() {
        assertThat(EscalationRules.READ_ROLES).containsExactlyInAnyOrder("OWNER", "DOCTOR", "NURSE");
        assertThat(EscalationRules.DOCTOR_ROLES).containsExactlyInAnyOrder("OWNER", "DOCTOR");
        assertThat(EscalationRules.NURSE_ROLES).containsExactly("NURSE");
    }
}
