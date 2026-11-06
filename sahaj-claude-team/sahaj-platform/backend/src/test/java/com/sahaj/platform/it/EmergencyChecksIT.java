package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-28 the checks of every emergency call on the real {@code sahaj_test} database: the order of the checks (role,
 * module Off, Read-only, 400, 404, 409), the exact validation messages, the roles of AC11 for every endpoint, 401
 * without a session, module Off, Read-only, another clinic's data, and the Indic text rules. A refused call writes
 * nothing. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyChecksIT extends EmergencySupport {

    private static final String READ_ONLY_TEXT = "IPD is read-only. Nothing can be changed.";
    private static final String ZWJ = "‍";
    private static final String ZWNJ = "‌";
    /** Ka + virama + ZWJ + Ssa, and Ka + virama + ZWNJ + Ssa. */
    private static final String WITH_ZWJ = "क्" + ZWJ + "ष राव";
    private static final String WITH_ZWNJ = "क्" + ZWNJ + "ष पाटील";

    // ---------------- validation: exact messages ----------------

    private JsonNode refused(Clinic clinic, Map<String, Object> body) throws Exception {
        Written before = written(clinic);
        JsonNode p = problem(registerCall(clinic.owner(), body), 400);
        assertType(p, "validation");
        assertThat(written(clinic)).as("a refused registration writes nothing").isEqualTo(before);
        return p;
    }

    private void assertMessage(Clinic clinic, Map<String, Object> body, String field, String message) throws Exception {
        JsonNode p = refused(clinic, body);
        assertThat(messageOf(p, field)).as(field + " of " + body).isEqualTo(message);
    }

    @Test
    void theNameIsRequiredUnlessUnknownAndHasAtMost80CharactersCountedAsUnicodeCodePoints() throws Exception {
        Clinic clinic = hospital();
        for (String name : new String[] {null, "", "   "}) {
            Map<String, Object> body = caseBody(name, 2);
            assertMessage(clinic, body, "name", NAME_REQUIRED);
        }
        assertMessage(clinic, caseBody("x".repeat(81), 2), "name", "Enter a name of up to 80 characters");
        assertMessage(clinic, caseBody("  " + "x".repeat(81) + "  ", 2), "name", "Enter a name of up to 80 characters");
        // 80 is the limit; spaces around it do not count
        assertThat(register(clinic, caseBody("  " + "x".repeat(80) + "  ", 2)).get("actedCase").get("displayName").asText())
                .hasSize(80);
        // characters outside the basic plane count once (a name needs at least one letter: "a" + 79 emoji is 80 code points)
        String emoji = "😀";
        String atLimit = register(clinic, caseBody("a" + emoji.repeat(79), 2)).get("actedCase").get("displayName").asText();
        assertThat(atLimit.codePointCount(0, atLimit.length())).isEqualTo(80);
        assertThat(atLimit.length()).as("UTF-16 units: more than the 80 code points").isEqualTo(159);
        assertMessage(clinic, caseBody("a" + emoji.repeat(80), 2), "name", "Enter a name of up to 80 characters");
        // an emoji-only name has no letter: it is refused as a blank name, before the length is looked at
        assertMessage(clinic, caseBody(emoji.repeat(80), 2), "name", NAME_REQUIRED);
        // a name is not needed for an unknown patient, whatever is typed in the hidden field
        Map<String, Object> unknown = unknownBody("M", 40, 2);
        unknown.put("name", "");
        assertThat(register(clinic, unknown).get("actedCase").get("displayName").asText()).isEqualTo("Unknown male, about 40");
        Map<String, Object> missing = unknownBody("F", 30, 2);
        missing.remove("name");
        assertThat(register(clinic, missing).get("actedCase").get("displayName").asText()).isEqualTo("Unknown female, about 30");
        Map<String, Object> longHidden = unknownBody("O", 10, 2);
        longHidden.put("name", "x".repeat(500));
        register(clinic, longHidden);
    }

    @Test
    void aNameOrComplaintOfOnlyNoBreakSpacesAndOtherSpaceSeparatorsIsBlankLikeAnEmptyOne() throws Exception {
        // ADR-007 R6: the blank check removes every white space and every space separator (U+00A0, U+2007, U+202F, U+3000...)
        Clinic clinic = hospital();
        String[] blanks = {"\u00A0", "\u2007", "\u202F", "\u3000", "\u00A0\u2007\u202F\u3000", "\u00A0".repeat(40),
                "\u00A0 \u202F \u3000", "\u2003\u2009\u200A\u205F\u1680"};
        for (String blank : blanks) {
            assertMessage(clinic, caseBody(blank, 2), "name", NAME_REQUIRED);
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("complaint", blank);
            assertMessage(clinic, body, "complaint", COMPLAINT_REQUIRED);
        }
        // with a real word between the spaces both are accepted and stored without the outer spaces
        Map<String, Object> ok = caseBody("\u00A0Asha Patil\u202F", 2);
        ok.put("complaint", "\u00A0\u00A0Fever\u3000");
        JsonNode acted = register(clinic, ok).get("actedCase");
        assertThat(acted.get("displayName").asText()).isEqualTo("Asha Patil");
        assertThat(acted.get("complaint").asText()).isEqualTo("Fever");
    }

    @Test
    void theAgeIsAWholeNumberFrom0To120OrEmpty() throws Exception {
        Clinic clinic = hospital();
        String message = "Enter the age as a whole number from 0 to 120, or leave it empty";
        for (Object bad : new Object[] {-1, 121, 1000, 40.5, "forty"}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("age", bad);
            JsonNode p = refused(clinic, body);
            assertThat(fieldsOf(p)).as("age " + bad).contains("age");
            if (bad instanceof Number) {
                assertThat(messageOf(p, "age")).as("age " + bad).isEqualTo(message);
            }
        }
        for (Object good : new Object[] {0, 1, 120}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("age", good);
            assertThat(register(clinic, body).get("actedCase").get("age").asInt()).isEqualTo((Integer) good);
        }
        Map<String, Object> empty = caseBody("Asha Patil", 2);
        empty.put("age", null);
        assertThat(register(clinic, empty).get("actedCase").get("age").isNull()).isTrue();
    }

    @Test
    void theSexMustBeMaleFemaleOrOther() throws Exception {
        Clinic clinic = hospital();
        for (Object bad : new Object[] {null, "", "X", "m", "Male", 1}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("sex", bad);
            JsonNode p = refused(clinic, body);
            assertThat(fieldsOf(p)).as("sex " + bad).contains("sex");
            if (!(bad instanceof Integer)) {
                assertThat(messageOf(p, "sex")).as("sex " + bad).isEqualTo("Choose male, female or other");
            }
        }
        for (String good : new String[] {"M", "F", "O"}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("sex", good);
            assertThat(register(clinic, body).get("actedCase").get("sex").asText()).isEqualTo(good);
        }
        Map<String, Object> missing = caseBody("Asha Patil", 2);
        missing.remove("sex");
        assertThat(messageOf(refused(clinic, missing), "sex")).isEqualTo("Choose male, female or other");
    }

    @Test
    void theMobileIsEmptyOrATenDigitNumberStartingWith6To9() throws Exception {
        Clinic clinic = hospital();
        String message = "Enter a 10-digit mobile number, or leave it empty";
        for (String bad : new String[] {"12345", "0123456789", "5987654321", "98765432101", "987654321", "98765 43210",
                "+919876543210", "98765abcde", "९८७६५४३२१०"}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("mobile", bad);
            assertMessage(clinic, body, "mobile", message);
        }
        for (String good : new String[] {"6000000000", "7999999999", "8123456789", "9876543210", " 9876543210 "}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("mobile", good);
            String id = register(clinic, body).get("actedCase").get("id").asText();
            assertThat(col(clinic, id, "mobile")).isEqualTo(good.strip());
        }
    }

    @Test
    void theComplaintIsRequiredAndHasAtMost200Characters() throws Exception {
        Clinic clinic = hospital();
        for (String bad : new String[] {null, "", "    "}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("complaint", bad);
            assertMessage(clinic, body, "complaint", COMPLAINT_REQUIRED);
        }
        Map<String, Object> tooLong = caseBody("Asha Patil", 2);
        tooLong.put("complaint", "c".repeat(201));
        assertMessage(clinic, tooLong, "complaint", "Enter the main complaint in up to 200 characters");
        Map<String, Object> exact = caseBody("Asha Patil", 2);
        exact.put("complaint", "c".repeat(200));
        assertThat(register(clinic, exact).get("actedCase").get("complaint").asText()).hasSize(200);
        Map<String, Object> padded = caseBody("Asha Patil", 2);
        padded.put("complaint", " " + "c".repeat(200) + " ");
        assertThat(register(clinic, padded).get("actedCase").get("complaint").asText()).hasSize(200);
    }

    @Test
    void theLevelMustBeBetweenOneAndTheScaleOfTheClinic() throws Exception {
        Clinic clinic = hospital();
        for (Object bad : new Object[] {null, 0, -1, 4, 5, 6, 2.5}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("level", bad);
            assertMessage(clinic, body, "level", LEVEL_MESSAGE);
        }
        setScale(clinic, 5);
        for (int good = 1; good <= 5; good++) {
            assertThat(register(clinic, caseBody("Level " + good, good)).get("actedCase").get("level").asInt()).isEqualTo(good);
        }
        assertMessage(clinic, caseBody("Asha Patil", 6), "level", LEVEL_MESSAGE);
    }

    @Test
    void allFieldErrorsComeTogetherInTheOrderOfTheContract() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> body = caseBody("", 9);
        body.put("age", 200);
        body.put("sex", "X");
        body.put("mobile", "1");
        body.put("complaint", "");
        JsonNode p = refused(clinic, body);
        assertThat(fieldsOf(p)).containsExactly("name", "age", "sex", "mobile", "complaint", "level");
        assertThat(messageOf(p, "name")).isEqualTo(NAME_REQUIRED);
        assertThat(messageOf(p, "complaint")).isEqualTo(COMPLAINT_REQUIRED);
        assertThat(messageOf(p, "level")).isEqualTo(LEVEL_MESSAGE);
        assertThat(p.get("type").asText()).isEqualTo(PROBLEM_BASE + "validation");
    }

    @Test
    void theClientKeyMustBeAUuidAndIsCheckedBeforeTheBody() throws Exception {
        Clinic clinic = hospital();
        for (Object bad : new Object[] {null, "", "not-a-uuid", "1234"}) {
            Map<String, Object> body = caseBody("", 9);          // the body is wrong as well
            body.put("clientKey", bad);
            JsonNode p = refused(clinic, body);
            assertThat(fieldsOf(p)).as("key " + bad).containsExactly("clientKey");
            assertThat(messageOf(p, "clientKey")).isEqualTo("Reload the page and try again.");
        }
        Map<String, Object> missing = caseBody("Asha Patil", 2);
        missing.remove("clientKey");
        assertThat(fieldsOf(refused(clinic, missing))).containsExactly("clientKey");
        // an empty JSON object has no key
        Written before = written(clinic);
        JsonNode p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), "{}"), 400);
        assertThat(fieldsOf(p)).containsExactly("clientKey");
        assertThat(written(clinic)).isEqualTo(before);
    }

    @Test
    void unknownPatientRegistrationOffRefusesTheTickedBoxAndKeepsTheNamedWay() throws Exception {
        Clinic clinic = hospital();
        putSetup(clinic, 3, T3, T5, 2, false);
        assertThat(queue(clinic).get("unknownPatientRegistration").asBoolean()).isFalse();
        JsonNode p = refused(clinic, unknownBody("M", 40, 2));
        assertThat(fieldsOf(p)).contains("identityUnknown");
        assertThat(messageOf(p, "identityUnknown")).isEqualTo("Unknown patient registration is off. Enter a name.");
        // a named patient is still registered; the box is the only thing that is refused
        assertThat(register(clinic, caseBody("Asha Patil", 2)).get("actedCase").get("displayName").asText()).isEqualTo("Asha Patil");
        // turning it off does not change a case that was registered as unknown before
        putSetup(clinic, 3, T3, T5, 2, true);
        String id = register(clinic, unknownBody("M", 40, 2)).get("actedCase").get("id").asText();
        putSetup(clinic, 3, T3, T5, 2, false);
        JsonNode q = queue(clinic);
        assertThat(caseIn(q, id).get("displayName").asText()).isEqualTo("Unknown male, about 40");
        assertThat(caseIn(q, id).get("identityPending").asBoolean()).isTrue();
        // the repeat of an old unknown registration still returns the first case (the key is checked first)
    }

    @Test
    void theLevelOfARetriageMustBeInTheScaleAndAMalformedBodyIsA400() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        Written before = written(clinic);
        for (String json : new String[] {"{\"level\":0}", "{\"level\":4}", "{\"level\":-1}", "{\"level\":2.5}", "{}",
                "{\"level\":null}", "{\"level\":\"high\"}"}) {
            JsonNode p = problem(retriageCall(clinic.owner(), id, json), 400);
            assertType(p, "validation");
            assertThat(fieldsOf(p)).as(json).contains("level");
        }
        JsonNode p = problem(retriageCall(clinic.owner(), id, "{\"level\":9}"), 400);
        assertThat(messageOf(p, "level")).isEqualTo(LEVEL_MESSAGE);
        assertThat(written(clinic)).isEqualTo(before);
    }

    // ---------------- Indic text ----------------

    @Test
    void devanagariNameAndComplaintWithJoinersAfterAViramaAreAcceptedAndStoredCharacterForCharacter() throws Exception {
        Clinic clinic = hospital();
        for (String text : new String[] {WITH_ZWJ, WITH_ZWNJ, "अशोक पाटील"}) {
            Map<String, Object> body = caseBody(text, 2);
            body.put("complaint", text + " सीने में दर्द");
            JsonNode acted = register(clinic, body).get("actedCase");
            String id = acted.get("id").asText();
            assertThat(acted.get("displayName").asText()).isEqualTo(text);
            assertThat(acted.get("complaint").asText()).isEqualTo(body.get("complaint"));
            assertThat(col(clinic, id, "display_name")).isEqualTo(text);
            assertThat(col(clinic, id, "complaint")).isEqualTo(body.get("complaint"));
            assertThat(caseIn(queue(clinic), id).get("displayName").asText()).isEqualTo(text);
        }
        assertThat(values(clinic, "select (position(chr(8205) in display_name) > 0 or position(chr(8204) in display_name) > 0)::text "
                + "from ipd.emergency_case where display_name = ?", WITH_ZWJ)).containsExactly("true");
    }

    @Test
    void zeroWidthSpacesByteOrderMarksJoinersInTheWrongPlaceBidiAndControlCharactersAreRefused() throws Exception {
        Clinic clinic = hospital();
        String[] bad = {"Ra​vi", "﻿Ravi", "Ravi﻿", "Ra⁠vi", "Ra‮vi", "Ra‏vi", "Ra\u0007vi",
                "Ra\tvi", "Ra\nvi", "Ra\u0085vi",
                ZWJ + "ष", "क्" + ZWJ, "क्" + ZWJ + ZWJ + "ष", "Ka" + ZWJ + "Sa", "क् " + ZWJ + "ष"};
        for (String text : bad) {
            Map<String, Object> name = caseBody(text, 2);
            assertThat(fieldsOf(refused(clinic, name))).as("name " + escape(text)).contains("name");
            Map<String, Object> complaint = caseBody("Asha Patil", 2);
            complaint.put("complaint", text);
            assertThat(fieldsOf(refused(clinic, complaint))).as("complaint " + escape(text)).contains("complaint");
        }
        assertThat(caseRows(clinic)).isZero();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        s.chars().forEach(c -> b.append(c < 0x20 || c > 0x7e ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return b.toString();
    }

    // ---------------- order of the checks ----------------

    @Test
    void theRoleIsCheckedBeforeTheModuleStateAndTheModuleStateBeforeEverythingElse() throws Exception {
        Clinic off = newClinic();                              // IPD is Off
        UUID id = UUID.randomUUID();
        // a role without permission: 403 forbidden, not module-off
        assertType(problem(registerCall(off.as("BILLING"), caseBody("Asha Patil", 2)), 403), "forbidden");
        assertType(problem(act(off.as("FRONT_DESK"), id, "seen"), 403), "forbidden");
        assertType(problem(act(off.as("FRONT_DESK"), id, "release"), 403), "forbidden");
        assertType(problem(call(HttpMethod.GET, ER, off.as("PHARMACIST"), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, off.as("FRONT_DESK"), "{}"), 403), "forbidden");
        // a role with permission: module-off, with the exact message, even with a bad body or an unknown case
        JsonNode p = problem(registerCall(off.owner(), caseBody("", 9)), 403);
        assertType(p, "module-off");
        assertThat(p.get("detail").asText()).isEqualTo(MODE_OFF);
        assertType(problem(act(off.owner(), id, "seen"), 403), "module-off");
        assertType(problem(retriageCall(off.owner(), id, "{\"level\":99}"), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, off.owner(), "{}"), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, ER_SETTINGS, off.owner(), null), 403), "module-off");
        assertThat(rows(off, "emergency_case")).isZero();
    }

    @Test
    void readOnlyBeatsABadBodyAndAnUnknownCaseAndTheRoleBeatsReadOnly() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        expireTrial(clinic);
        Written before = written(clinic);
        UUID unknown = UUID.randomUUID();

        JsonNode ro = problem(registerCall(clinic.owner(), caseBody("", 9)), 409);
        assertType(ro, "ipd-readonly");
        assertThat(ro.get("detail").asText()).isEqualTo(READ_ONLY_TEXT);
        Map<String, Object> noKey = caseBody("Asha Patil", 2);
        noKey.remove("clientKey");
        assertType(problem(registerCall(clinic.owner(), noKey), 409), "ipd-readonly");
        for (String action : new String[] {"seen", "release", "refer"}) {
            assertType(problem(act(clinic.owner(), id, action), 409), "ipd-readonly");
            assertType(problem(act(clinic.owner(), unknown, action), 409), "ipd-readonly");
        }
        assertType(problem(retriageCall(clinic.owner(), id, "{\"level\":1}"), 409), "ipd-readonly");
        assertType(problem(retriageCall(clinic.owner(), unknown, "{\"level\":99}"), 409), "ipd-readonly");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), settingsJson(5, T3, T5, 3, true)), 409), "ipd-readonly");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), "{}"), 409), "ipd-readonly");
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, UUID.randomUUID(), UUID.randomUUID(), id)), 409), "ipd-readonly");
        // a role without permission is told so, not that IPD is read-only
        assertType(problem(act(clinic.as("FRONT_DESK"), id, "seen"), 403), "forbidden");
        assertType(problem(registerCall(clinic.as("BILLING"), caseBody("Asha Patil", 2)), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.as("NURSE"), "{}"), 403), "forbidden");
        assertThat(written(clinic)).isEqualTo(before);
    }

    @Test
    void readOnlyStillReadsTheQueueTheSummaryTheSettingsAndTheAdmitHandOffButNothingMayBeDone() throws Exception {
        Clinic clinic = hospital();
        String id = regWaited(clinic, "Asha Patil", 2, 40).get("id").asText();
        newBed(clinic, "gen");
        expireTrial(clinic);
        for (String role : ER_ROLES) {
            JsonNode q = queueAs(clinic, role);
            assertThat(q.get("state").asText()).as(role).isEqualTo("readonly");
            for (String flag : new String[] {"canRegister", "canRetriage", "canAct", "canAdmit"}) {
                assertThat(q.get(flag).asBoolean()).as(role + " " + flag).isFalse();
            }
            assertThat(q.get("counts").get("writable").asBoolean()).isFalse();
            assertThat(q.get("counts").get("queue").asInt()).isOne();
            assertThat(q.get("counts").get("overTarget").asInt()).isOne();
            assertThat(ids(q)).containsExactly(id);
            assertThat(ok(HttpMethod.GET, ER_SUMMARY, clinic.as(role), null, 200).get("writable").asBoolean()).isFalse();
            assertThat(ok(HttpMethod.GET, ER_SETTINGS, clinic.as(role), null, 200).get("canEdit").asBoolean()).as(role).isFalse();
        }
        assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).get("emergency").get("writable").asBoolean()).isFalse();
        assertThat(optionsFor(clinic, "OWNER", id).get("emergencyCase").get("caseId").asText()).isEqualTo(id);
    }

    @Test
    void aBadLevelIs400BeforeTheCaseIsLookedUpAnUnknownCaseIs404AndAClosedCaseIs409() throws Exception {
        Clinic clinic = hospital();
        String closed = reg(clinic, "Closed", 2).get("id").asText();
        release(clinic, closed);
        UUID unknown = UUID.randomUUID();
        Written before = written(clinic);
        assertType(problem(retriageCall(clinic.owner(), unknown, "{\"level\":9}"), 400), "validation");
        assertType(problem(retriageCall(clinic.owner(), closed, "{\"level\":9}"), 400), "validation");
        assertType(problem(retriageCall(clinic.owner(), unknown, "{\"level\":1}"), 404), "not-found");
        assertCaseClosed(problem(retriageCall(clinic.owner(), closed, "{\"level\":1}"), 409));
        for (String action : new String[] {"seen", "release", "refer"}) {
            assertType(problem(act(clinic.owner(), unknown, action), 404), "not-found");
            assertCaseClosed(problem(act(clinic.owner(), closed, action), 409));
        }
        // an id that is not a UUID is not a case
        for (String action : new String[] {"seen", "release", "refer"}) {
            assertThat(call(HttpMethod.POST, ER_CASES + "/not-a-uuid/" + action, clinic.owner(), null).andReturn().getResponse()
                    .getStatus()).as(action).isBetween(400, 404);
        }
        assertThat(written(clinic)).isEqualTo(before);
    }

    // ---------------- roles (AC11) ----------------

    private static boolean in(String role, String[] roles) {
        return Arrays.asList(roles).contains(role);
    }

    @Test
    void readingTheQueueTheSummaryAndTheSettingsIsForOwnerFrontDeskNurseAndDoctorOnly() throws Exception {
        Clinic clinic = hospital();
        reg(clinic, "Asha Patil", 2);
        for (String role : ALL_ROLES) {
            for (String url : new String[] {ER, ER_SUMMARY, ER_SETTINGS}) {
                if (in(role, ER_ROLES)) {
                    ok(HttpMethod.GET, url, clinic.as(role), null, 200);
                } else {
                    assertType(problem(call(HttpMethod.GET, url, clinic.as(role), null), 403), "forbidden");
                }
            }
        }
        // the flags follow the roles
        String[][] flags = {{"OWNER", "true", "true", "true", "true"}, {"FRONT_DESK", "true", "true", "false", "true"},
                {"NURSE", "true", "true", "true", "true"}, {"DOCTOR", "true", "true", "true", "false"}};
        for (String[] f : flags) {
            JsonNode q = queueAs(clinic, f[0]);
            assertThat(q.get("canRegister").asBoolean()).as(f[0] + " canRegister").isEqualTo(Boolean.parseBoolean(f[1]));
            assertThat(q.get("canRetriage").asBoolean()).as(f[0] + " canRetriage").isEqualTo(Boolean.parseBoolean(f[2]));
            assertThat(q.get("canAct").asBoolean()).as(f[0] + " canAct").isEqualTo(Boolean.parseBoolean(f[3]));
            assertThat(q.get("canAdmit").asBoolean()).as(f[0] + " canAdmit").isEqualTo(Boolean.parseBoolean(f[4]));
        }
    }

    @Test
    void registerAndRetriageAreForOwnerFrontDeskNurseAndDoctorOnlyAndARefusalWritesNothing() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 3).get("id").asText();
        for (String role : ALL_ROLES) {
            Written before = written(clinic);
            if (in(role, ER_ROLES)) {
                JsonNode created = ok(HttpMethod.POST, ER_CASES, clinic.as(role), js(caseBody("By " + role, 3)), 201);
                assertThat(created.get("actedCase").get("displayName").asText()).isEqualTo("By " + role);
                JsonNode moved = ok(HttpMethod.PUT, caseUrl(id, "level"), clinic.as(role), "{\"level\":" + (role.equals("OWNER") ? 2 : 3) + "}", 200);
                assertThat(moved.get("actedCase").get("id").asText()).isEqualTo(id);
            } else {
                assertType(problem(registerCall(clinic.as(role), caseBody("By " + role, 3)), 403), "forbidden");
                assertType(problem(retriageCall(clinic.as(role), id, "{\"level\":1}"), 403), "forbidden");
                assertThat(written(clinic)).as(role).isEqualTo(before);
            }
        }
    }

    @Test
    void seenReleaseAndReferAreForOwnerNurseAndDoctorOnlyFrontDeskIsRefused() throws Exception {
        Clinic clinic = hospital();
        for (String action : new String[] {"seen", "release", "refer"}) {
            for (String role : ALL_ROLES) {
                String id = reg(clinic, action + " by " + role, 3).get("id").asText();
                Written before = written(clinic);
                if (in(role, ACTORS)) {
                    JsonNode a = ok(HttpMethod.POST, caseUrl(id, action), clinic.as(role), null, 200);
                    assertThat(a.get("actedCase").get("id").asText()).isEqualTo(id);
                } else {
                    JsonNode p = problem(act(clinic.as(role), id, action), 403);
                    assertType(p, "forbidden");
                    assertThat(written(clinic)).as(action + " by " + role).isEqualTo(before);
                    assertThat(col(clinic, id, "status")).isEqualTo("WAITING");
                }
            }
        }
    }

    @Test
    void settingsAreChangedByTheOwnerOnlyAndOtherRolesAreRefusedWithoutAChange() throws Exception {
        Clinic clinic = hospital();
        for (String role : ALL_ROLES) {
            String body = settingsJson(3, T3, T5, 4, true);
            if ("OWNER".equals(role)) {
                assertThat(ok(HttpMethod.PUT, ER_SETTINGS, clinic.as(role), body, 200).get("bays").asInt()).isEqualTo(4);
            } else {
                assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.as(role), settingsJson(5, T3, T5, 9, false)), 403),
                        "forbidden");
            }
        }
        JsonNode s = settings(clinic);
        assertThat(s.get("scale").asInt()).isEqualTo(3);
        assertThat(s.get("bays").asInt()).isEqualTo(4);
        assertThat(s.get("unknownPatientRegistration").asBoolean()).isTrue();
        assertThat(audits(clinic, "IPD_ER_SETTINGS_UPDATED")).isOne();
    }

    @Test
    void everyEndpointAnswers401WithoutASession() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        Written before = written(clinic);
        assertType(problem(call(HttpMethod.GET, ER, anonymous(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.GET, ER_SUMMARY, anonymous(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.GET, ER_SETTINGS, anonymous(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, anonymous(), settingsJson(3, T3, T5, 2, true)), 401), "unauthorized");
        assertType(problem(registerCall(anonymous(), caseBody("Asha Patil", 2)), 401), "unauthorized");
        for (String action : new String[] {"seen", "level", "release", "refer"}) {
            assertType(problem(act(anonymous(), id, action), 401), "unauthorized");
        }
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, anonymous(), null), 401), "unauthorized");
        assertType(problem(admitCall(anonymous(), admitBody(clinic, UUID.randomUUID(), UUID.randomUUID(), id)), 401), "unauthorized");
        assertThat(written(clinic)).isEqualTo(before);
    }

    // ---------------- another clinic ----------------

    @Test
    void anotherClinicsCaseIsInvisibleAndEveryActionOnItAnswers404AndChangesNothing() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        String id = regWaited(a, "Asha Patil", 1, 20).get("id").asText();
        reg(b, "Own case of B", 3);
        Written beforeA = written(a);

        JsonNode bq = queue(b);
        assertThat(ids(bq)).doesNotContain(id);
        assertThat(names(bq)).containsExactly("Own case of B");
        assertThat(bq.get("counts").get("queue").asInt()).isOne();
        assertThat(bq.get("counts").get("overTarget").asInt()).isZero();
        assertThat(bq.get("baysFree").asInt()).as("A's bay is not B's").isEqualTo(2);
        assertThat(ok(HttpMethod.GET, ER_SUMMARY, b.owner(), null, 200).get("queue").asInt()).isOne();
        for (String action : new String[] {"seen", "level", "release", "refer"}) {
            assertType(problem(act(b.owner(), id, action), 404), "not-found");
        }
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, b.owner(), null), 404), "not-found");
        UUID patient = newPatient(b);
        UUID bed = newBed(b, "gen");
        Written beforeB = written(b);
        assertType(problem(admitCall(b.owner(), admitBody(b, patient, bed, id)), 404), "not-found");
        assertThat(rows(b, "admission")).isZero();
        assertThat(bedStatus(b, bed)).isEqualTo("free");

        assertThat(written(a)).as("A's case is untouched").isEqualTo(beforeA);
        assertThat(written(b)).isEqualTo(beforeB);
        // by SQL: B sees none of A's rows, and with no clinic bound nothing is visible
        assertThat(count(b, "select count(*) from ipd.emergency_case where id = ?::uuid", id)).isZero();
        assertThat(count(b, "select count(*) from ipd.emergency_triage_event where case_id = ?::uuid", id)).isZero();
        long unboundCases = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.emergency_case"));
        assertThat(unboundCases).isZero();
        long unboundEvents = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.emergency_triage_event"));
        assertThat(unboundEvents).isZero();
        // the settings of B are its own
        putSetup(a, 5, T3, T5, 7, false);
        JsonNode sb = settings(b);
        assertThat(sb.get("scale").asInt()).isEqualTo(3);
        assertThat(sb.get("bays").asInt()).isEqualTo(2);
        assertThat(sb.get("unknownPatientRegistration").asBoolean()).isTrue();
    }

    @Test
    void aRegistrationNeverTakesTheClinicFromTheBody() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Map<String, Object> body = caseBody("Asha Patil", 2);
        body.put("tenantId", b.tenantId().toString());
        body.put("tenant_id", b.tenantId().toString());
        body.put("registeredBy", UUID.randomUUID().toString());
        body.put("status", "SEEN");
        body.put("arrivedAt", "2020-01-01T00:00:00Z");
        body.put("bay", "ER-9");
        JsonNode acted = ok(HttpMethod.POST, ER_CASES, a.owner(), js(body), 201).get("actedCase");
        String id = acted.get("id").asText();
        assertThat(caseRows(a)).isOne();
        assertThat(caseRows(b)).isZero();
        assertThat(acted.get("status").asText()).isEqualTo("WAITING");
        assertThat(acted.get("bay").isNull()).isTrue();
        assertThat(acted.get("waitingMinutes").asInt()).isZero();
        assertThat(col(a, id, "registered_by")).isEqualTo(a.ownerUserId().toString());
        assertThat(List.of(acted.get("level").asInt())).containsExactly(2);
    }

    // RequestPostProcessor is used by the helpers above
    @SuppressWarnings("unused")
    private static RequestPostProcessor unused() {
        return anonymous();
    }
}
