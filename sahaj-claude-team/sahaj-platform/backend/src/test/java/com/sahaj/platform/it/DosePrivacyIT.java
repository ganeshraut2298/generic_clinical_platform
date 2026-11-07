package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SAH-32 AC12 and the privacy rules of CLAUDE.md on the real {@code sahaj_test} database: through one long flow (opening the
 * MAR, doses given and not given, a high-alert dose, wristband stops with a typed name, a typed note and a UHID-shaped band,
 * a wrong PIN, validation errors, a repeated dose, a refused role) no PIN, no scanned or typed text, no patient name, no
 * mobile number and no UHID reaches the application log, the audit rows, the problem details or the clinical events - except
 * the two things the design puts there on purpose: the deny entry's UHID-shaped scan with the expected UHID, and the second
 * nurse's display name in the give entry. Test plan: docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class DosePrivacyIT extends MarSupport {

    private static final String NOTE = "ZqNote allergic to penicillin since childhood";

    private static String wrong(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    private String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    @Test
    void noPinScanTextNameMobileOrUhidReachesTheLogTheAuditRowsTheProblemDetailsOrTheEventsExceptWhatTheDesignPutsThere()
            throws Exception {
        Ward w = ward();
        String pcm = activeOrder(w, "pcm", "od");
        String hep = activeOrder(w, "hep", "bd");
        String panto = activeOrder(w, "panto", "od");
        String ins = activeOrder(w, "ins", "od");
        NurseWithPin n3 = nurseWithPin(w.clinic());
        clockAt(8, 5);
        String bad = wrong(w.nurse2Pin());
        String other = w.uhid().substring(0, w.uhid().length() - 1) + (w.uhid().endsWith("9") ? "8" : "9");
        List<String> problems = new ArrayList<>();
        String logText;
        try (LogCapture logs = new LogCapture()) {
            // reading
            mar(w.nurse().auth(), w.adm().id());
            problems.add(body(call(org.springframework.http.HttpMethod.GET, marUrl(w.adm().id()), staff(w.clinic(), "BILLING").auth(), null)));
            // wristband stops with a name, a mobile, a note and a UHID-shaped band of another patient
            for (String typed : new String[] {w.adm().name(), w.adm().mobile(), NOTE, other}) {
                problems.add(body(doseCall(w.nurse().auth(), pcm, given(ist(8, 0), typed))));
            }
            // bedside errors
            problems.add(body(doseCall(w.nurse().auth(), pcm, set(given(w, ist(8, 0)), "identityConfirmed", false))));
            problems.add(body(doseCall(w.nurse().auth(), pcm, without(given(w, ist(8, 0)), "wristband"))));
            problems.add(body(doseCall(w.nurse().auth(), hep, given(w, ist(8, 0)))));
            problems.add(body(doseCall(w.nurse().auth(), hep, givenHighAlert(w, ist(8, 0), w.nurse(), "1234"))));
            problems.add(body(doseCall(w.nurse().auth(), hep, givenHighAlert(w, ist(8, 0), w.pharmacist(), w.pharmacistPin()))));
            problems.add(body(doseCall(w.nurse().auth(), pcm, notGiven(ist(8, 0), NOTE))));
            problems.add(body(doseRaw(w.nurse().auth(), pcm, "{\"wristband\":\"" + w.adm().name() + "\"}")));
            problems.add(body(doseCall(w.doctor().auth(), pcm, given(w, ist(8, 0)))));
            // a wrong PIN, then the right one
            problems.add(body(doseCall(w.nurse().auth(), hep, givenHighAlert(w, ist(8, 0), w.nurse2(), bad))));
            doseOk(w.nurse().auth(), hep, givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
            // doses, a repeat and a record of the same slot
            Map<String, Object> g = given(w, ist(8, 0));
            doseOk(w.nurse().auth(), pcm, g);
            ok(org.springframework.http.HttpMethod.POST, dosesUrl(pcm), w.nurse().auth(), js(g), 200);
            problems.add(body(doseCall(w.nurse2().auth(), pcm, given(w, ist(8, 0)))));
            doseOk(w.nurse().auth(), panto, notGiven(ist(8, 0), "HELD_DOCTOR"));
            problems.add(body(doseCall(w.nurse().auth(), panto, set(notGiven(ist(8, 0), "NIL_BY_MOUTH"), "wristband", w.adm().name()))));
            // a high-alert wrong PIN of a different nurse and the limit
            problems.add(body(doseCall(w.nurse().auth(), ins, givenHighAlert(w, ist(8, 0), n3.nurse(), wrong(n3.pin())))));
            logText = logs.allText();
        }

        String firstName = w.adm().name().split(" ")[0];
        String all = String.join("\n", problems);
        for (String never : new String[] {w.adm().name(), w.adm().mobile(), w.uhid(), other, "ZqNote", "penicillin", "childhood",
                displayName(w.nurse2()), w.nurse2().mobile(), displayName(n3.nurse())}) {
            assertThat(all).as("problem details never hold: " + never).doesNotContain(never);
        }
        assertThat(all).doesNotContain(firstName + " ");
        for (String pin : new String[] {bad, w.nurse2Pin(), wrong(n3.pin()), n3.pin(), "1234"}) {
            assertThat(hasBarePin(all, pin)).as("problem details never hold the PIN " + pin).isFalse();
        }

        for (String never : new String[] {w.adm().name(), w.adm().mobile(), w.uhid(), other, "ZqNote", "penicillin", "childhood",
                w.nurse2().mobile(), w.nurse().mobile()}) {
            assertThat(logText).as("the log never holds: " + never).doesNotContain(never);
        }
        for (String pin : new String[] {bad, w.nurse2Pin(), wrong(n3.pin()), n3.pin()}) {
            assertThat(hasBarePin(logText, pin)).as("the log never holds the PIN " + pin).isFalse();
        }

        String audits = allAuditText(w.clinic());
        for (String never : new String[] {w.adm().name(), w.adm().mobile(), w.uhid(), other, "ZqNote", "penicillin", "childhood"}) {
            assertThat(audits).as("the audit rows never hold: " + never).doesNotContain(never);
        }
        for (String pin : new String[] {bad, w.nurse2Pin(), wrong(n3.pin()), n3.pin()}) {
            assertThat(hasBarePin(audits, pin)).as("the audit rows never hold the PIN " + pin).isFalse();
        }

        List<String> events = eventLines(w.clinic(), w.adm().id());
        int deny = 0;
        boolean secondNurseName = false;
        for (String line : events) {
            assertThat(line).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain("ZqNote")
                    .doesNotContain("penicillin");
            if (line.startsWith("deny|")) {
                deny++;
                assertThat(line).startsWith("deny|Stopped at the identity check: entered ").contains(", expected " + w.uhid() + " (");
                assertThat(line).matches("deny\\|Stopped at the identity check: entered (text that is not a UHID|" + other
                        + "), expected " + w.uhid() + " \\(.+\\)");
            } else {
                assertThat(line).as("the UHID is only in the deny entries").doesNotContain(w.uhid());
            }
            secondNurseName |= line.contains("second check by " + displayName(w.nurse2()));
        }
        assertThat(deny).isEqualTo(4);
        assertThat(secondNurseName).as("the second nurse's display name is in the give entry by design").isTrue();
        assertThat(allDoseText(w.clinic())).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain("ZqNote");
    }

    @Test
    void theDoseAnswerAndTheMarCarryNoPinAndNoScanTextBack() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        Map<String, Object> body = givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin());
        JsonNode a = doseOk(w.nurse().auth(), hep, body);
        String text = a.toString();
        assertThat(hasBarePin(text, w.nurse2Pin())).isFalse();
        assertThat(text).doesNotContain("secondNursePin").doesNotContain("wristband").doesNotContain("identityScan")
                .doesNotContain("identity_scan").doesNotContain(w.nurse2().mobile());
        assertThat(mar(w.nurse().auth(), w.adm().id()).toString()).doesNotContain("identityScan").doesNotContain("secondNursePin");
        assertThat(emr(w.doctor().auth(), w.adm().id()).toString()).doesNotContain("identityScan").doesNotContain("secondNursePin")
                .doesNotContain("identity_scan");
    }
}
