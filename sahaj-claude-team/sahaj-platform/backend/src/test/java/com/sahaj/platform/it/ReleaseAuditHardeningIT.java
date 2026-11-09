package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Release audit G-12, G-13 and G-16 (DEC-025, SAH-35/36/38) as the API answers them.
 *
 * <ul>
 * <li>G-12: an unreadable JSON value (an object, a number or a list where text is expected) of {@code seenThrough},
 * {@code clientKey}, {@code identityUnknown}, {@code version}, {@code seniorCalls}, {@code slotAt}, {@code checkToken} or
 * {@code outcome} answers 400 {@code validation} with the contract text "Reload the page and try again." on that field; every
 * other field keeps "Choose a valid option" (deferred D-1).</li>
 * <li>G-13: the "Unhandled error" log line names the kind of database fault and never a quoted value (reached with a
 * real database fault forced by a temporary trigger, the way {@link StaffPinColleagueHttpIT} does).</li>
 * <li>G-16: a malformed {@code {id}} on the four operations of the contracts answers 400 {@code bad-request} with the fixed
 * texts, and a well formed but unknown id still answers 404.</li>
 * </ul>
 * Test plan: docs/test-plans/SAH-35-36-38-audit.md.
 */
@OnTestDatabase
@WithPinPepper
class ReleaseAuditHardeningIT extends RoundSupport {

    private static final String EVENTS = "platform.staff_pin_colleague_event";
    private static final String SEEN_PATH = ME_PIN + "/colleague-tries/seen";
    private static final String BAD_REQUEST_DETAIL = "The request could not be understood. Check it and try again.";
    private static final String CHOOSE_VALID_OPTION = "Choose a valid option";
    private static final String[] LEAK_WORDS = {"Cannot deserialize", "JSON parse error", "com.sahaj", "com.fasterxml",
        "MismatchedInput", "HttpMessageNotReadable", "at [Source"};

    private record Fix(Ward w, String hep) {
    }

    private Fix fix() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        return new Fix(w, hep);
    }

    private void assertNoLeak(JsonNode p) {
        assertThat(p.toString()).doesNotContain(LEAK_WORDS);
    }

    // ---------------- G-12: the Give call ----------------

    private String giveJson(String clientKey, String slotAt, String outcome, String reasonKey, Ward w) {
        return "{\"clientKey\":" + clientKey + ",\"slotAt\":" + slotAt + ",\"outcome\":" + outcome
                + (reasonKey == null ? "" : ",\"reasonKey\":" + reasonKey)
                + ",\"wristband\":\"" + w.uhid() + "\",\"identityConfirmed\":true}";
    }

    private void unreadable(Fix f, String json, String field, String message, String label) throws Exception {
        DSnap before = dsnap(f.w());
        JsonNode p = refused(doseRaw(f.w().nurse().auth(), f.hep(), json), 400, "validation", null);
        assertThat(fieldNames(p)).as(label + " " + p).containsExactly(field);
        assertThat(fieldMessage(p, field)).as(label).isEqualTo(message);
        assertNoLeak(p);
        assertThat(dsnap(f.w())).as(label + ": nothing written").isEqualTo(before);
    }

    @Test
    void anUnreadableClientKeyOfTheGiveCallAnswersTheReloadTextWhateverTheWrongJsonTypeIs() throws Exception {
        Fix f = fix();
        String slot = "\"" + ist(8, 0) + "\"";
        for (String bad : new String[] {"123", "{}", "[]", "[\"x\"]", "true", "{\"a\":1}", "1.5"}) {
            unreadable(f, giveJson(bad, slot, "\"GIVEN\"", null, f.w()), "clientKey", RELOAD_TEXT, "clientKey " + bad);
        }
    }

    @Test
    void anUnreadableSlotAtOrOutcomeOfTheGiveCallAnswersTheReloadText() throws Exception {
        Fix f = fix();
        String key = "\"" + newKey() + "\"";
        for (String bad : new String[] {"{}", "[]"}) {
            unreadable(f, giveJson(key, bad, "\"GIVEN\"", null, f.w()), "slotAt", RELOAD_TEXT, "slotAt " + bad);
            unreadable(f, giveJson(key, "\"" + ist(8, 0) + "\"", bad, null, f.w()), "outcome", RELOAD_TEXT, "outcome " + bad);
        }
    }

    @Test
    void anUnreadableValueOfAnyOtherFieldKeepsChooseAValidOption() throws Exception {
        // deferred D-1: the fallback text is not contract text for these fields; this pins what the build answers today
        Fix f = fix();
        String key = "\"" + newKey() + "\"";
        String slot = "\"" + ist(8, 0) + "\"";
        for (String bad : new String[] {"{}", "[]"}) {
            unreadable(f, giveJson(key, slot, "\"NOT_GIVEN\"", bad, f.w()), "reasonKey", CHOOSE_VALID_OPTION, "reasonKey " + bad);
        }
    }

    @Test
    void theReloadTextOfTheGiveCallIsTheSameForEveryRoleThatCanGiveAndNoSessionIsStill401() throws Exception {
        Fix f = fix();
        String json = giveJson("{}", "\"" + ist(8, 0) + "\"", "\"GIVEN\"", null, f.w());
        for (var auth : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {f.w().nurse().auth(),
            f.w().clinic().owner()}) {
            JsonNode p = refused(doseRaw(auth, f.hep(), json), 400, "validation", null);
            assertThat(fieldMessage(p, "clientKey")).isEqualTo(RELOAD_TEXT);
        }
        // no session is 401 before anything is read
        refused(doseRaw(noSession(), f.hep(), json), 401, "unauthorized", null);
    }

    // ---------------- G-12: "Got it" of the colleague-tries notice ----------------

    @Test
    void anUnreadableOrUnacceptableSeenThroughAnswers400WithTheReloadTextAndAGoodOneAnswers204() throws Exception {
        Ward w = ward();
        Instant now = Instant.now();
        String[] bad = {"{}", "123", "[]", "true", "1.5", "null", "\"\"", "\"not a date\"", "\"2019-12-31T23:59:59Z\"",
            "\"" + now.plus(3, ChronoUnit.DAYS) + "\"", "\"" + "2".repeat(41) + "\""};
        for (String value : bad) {
            JsonNode p = refused(call(HttpMethod.POST, SEEN_PATH, w.nurse().auth(), "{\"seenThrough\":" + value + "}"), 400,
                    "validation", null);
            assertThat(fieldNames(p)).as(value).containsExactly("seenThrough");
            assertThat(fieldMessage(p, "seenThrough")).as(value).isEqualTo(RELOAD_TEXT);
            assertNoLeak(p);
        }
        JsonNode missing = refused(call(HttpMethod.POST, SEEN_PATH, w.nurse().auth(), "{}"), 400, "validation", null);
        assertThat(fieldNames(missing)).containsExactly("seenThrough");
        assertThat(fieldMessage(missing, "seenThrough")).isEqualTo(RELOAD_TEXT);
        call(HttpMethod.POST, SEEN_PATH, w.nurse().auth(), "{\"seenThrough\":\"" + now + "\"}")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    @Test
    void aBrokenOrEmptyBodyOfTheSeenCallIs400NotA500AndNoSessionIs401() throws Exception {
        Ward w = ward();
        for (String body : new String[] {"not json", "{", "[]", "\"x\"", ""}) {
            JsonNode p = problem(call(HttpMethod.POST, SEEN_PATH, w.nurse().auth(), body), 400);
            assertNoLeak(p);
        }
        refused(call(HttpMethod.POST, SEEN_PATH, noSession(), "{\"seenThrough\":\"" + Instant.now() + "\"}"), 401, "unauthorized",
                null);
    }

    // ---------------- G-13: the unhandled-error log line cuts before quoted values ----------------

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private void withForcedEventFailure(Ward w, String raiseStatement, Action action) throws Exception {
        String suffix = w.nurse2().membershipId().toString().replace('-', '_');
        String function = "platform.it_audit_fail_" + suffix;
        String trigger = "trg_it_audit_fail_" + suffix;
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + raiseStatement + " end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before insert on " + EVENTS + " for each row when "
                    + "(new.membership_id = '" + w.nurse2().membershipId() + "'::uuid) execute function " + function + "()");
            try {
                action.run();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on " + EVENTS);
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
    }

    @Test
    void aDatabaseFaultThatQuotesAValueIsLoggedWithTheKindOfFaultAndNeverTheValue() throws Exception {
        String[][] cases = {
            // {statement raised by the database, text that must be in the dbError part, texts that must not be anywhere in the log}
            {"raise exception 'invalid input syntax for type uuid: \"Asha-Rane-9000000001\"' using errcode = '23514';",
                "invalid input syntax for type uuid", "Asha-Rane"},
            {"raise exception 'duplicate key value violates unique constraint ''uq_x'' for ''Asha-Rane-9000000001''' "
                    + "using errcode = '23514';", "duplicate key value violates unique constraint", "Asha-Rane"},
            {"raise exception 'check violated' using errcode = '23514', detail = 'Key (mobile)=(9000000001) is Asha-Rane';",
                "check violated", "Asha-Rane"},
            // code review #1 / security #4: an identifier after relation, column, constraint, table, schema or index is KEPT
            // (the 4th element) so the line can still be used to find the fault; the value is cut
            {"raise exception 'duplicate key value violates unique constraint \"uq_it_audit_x\"' using errcode = '23514', "
                    + "detail = 'Key (mobile)=(9000000001) already exists. Asha-Rane';",
                "duplicate key value violates unique constraint \"uq_it_audit_x\"", "Asha-Rane", "constraint \"uq_it_audit_x\""},
            {"raise exception 'null value in column \"mobile_x\" of relation \"staff_x\" violates not-null constraint' "
                    + "using errcode = '23514', detail = 'Failing row contains (Asha-Rane, 9000000001).';",
                "null value in column \"mobile_x\" of relation \"staff_x\" violates not-null constraint", "Asha-Rane",
                "column \"mobile_x\" of relation \"staff_x\""},
            // identifiers kept up to the colon, the unquoted value after the colon is cut
            {"raise exception 'insert on table \"t_x\" violates foreign key constraint \"fk_x\": Asha-Rane-9000000001 not present' "
                    + "using errcode = '23514';",
                "insert on table \"t_x\" violates foreign key constraint \"fk_x\"", "Asha-Rane",
                "table \"t_x\" violates foreign key constraint \"fk_x\""},
            // a quoted value after a word that is not an identifier word is cut even when it looks like an identifier
            {"raise exception 'bad value \"Asha-Rane\" for column \"c_x\"' using errcode = '23514';",
                "bad value", "Asha-Rane"},
        };
        for (String[] c : cases) {
            Fix f = fix();
            Ward w = f.w();
            String wrongPin = "9306".equals(w.nurse2Pin()) ? "9307" : "9306";
            DSnap before = dsnap(w);
            String unhandled = null;
            try (LogCapture logs = new LogCapture()) {
                withForcedEventFailure(w, c[0], () -> {
                    var response = doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrongPin))
                            .andReturn().getResponse();
                    assertThat(response.getStatus()).isEqualTo(500);
                    assertThat(response.getContentAsString()).doesNotContain("Asha").doesNotContain("9000000001");
                });
                List<ILoggingEvent> events = logs.events().stream()
                        .filter(e -> e.getFormattedMessage().startsWith("Unhandled error")).toList();
                assertThat(events).as("one unhandled-error line for " + c[1]).hasSize(1);
                unhandled = events.get(0).getFormattedMessage();
                String all = logs.allText();
                assertThat(all).as("no quoted value anywhere in the log").doesNotContain(c[2]).doesNotContain("9000000001");
            }
            assertThat(unhandled).contains("sqlState=23514").contains("dbError=").contains(c[1]);
            String dbError = unhandled.substring(unhandled.indexOf("dbError="), unhandled.indexOf(" at=")).trim();
            assertThat(dbError).as("the part after dbError=").doesNotContain("'").doesNotContain("Asha")
                    .doesNotContain("9000000001");
            if (c.length > 3) {
                assertThat(dbError).as("the identifier is kept").contains(c[3]);
                assertThat(dbError.replace(c[3], "")).as("nothing else is quoted").doesNotContain("\"");
            } else {
                assertThat(dbError).as("no quoted text at all").doesNotContain("\"");
            }
            assertThat(dsnap(w)).as("everything rolled back").isEqualTo(before);
        }
    }

    // ---------------- G-16: a malformed {id} on the four operations ----------------

    private static final String[] BAD_IDS = {"not-a-uuid", "12345", "00000000-0000-0000-0000-00000000000g",
        "00000000-0000-0000-0000-0000000000001"};

    private JsonNode badRequest(org.springframework.test.web.servlet.ResultActions actions, String badId, String label)
            throws Exception {
        JsonNode p = refused(actions, 400, "bad-request", BAD_REQUEST_DETAIL);
        assertThat(p.get("title").asText()).as(label).isEqualTo("Bad Request");
        assertThat(p.get("type").asText()).as(label).isEqualTo(PROBLEM_BASE + "bad-request");
        assertThat(p.toString()).as(label + ": the bad id is not echoed").doesNotContain(badId);
        assertNoLeak(p);
        return p;
    }

    @Test
    void aMalformedIdOnTheFourOperationsAnswers400BadRequestAndAWellFormedUnknownIdStill404() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        DSnap before = dsnap(w);
        for (String bad : BAD_IDS) {
            badRequest(call(HttpMethod.GET, ADMISSIONS + "/" + bad + "/mar", w.nurse().auth(), null), bad, "MAR " + bad);
            badRequest(call(HttpMethod.POST, IPD + "/medicine-orders/" + bad + "/doses", w.nurse().auth(),
                    js(given(w, ist(8, 0)))), bad, "doses " + bad);
            badRequest(call(HttpMethod.POST, TEAM + "/" + bad + "/pin/reset", w.clinic().owner(), null), bad, "pin reset " + bad);
            badRequest(call(HttpMethod.GET, TEAM + "/" + bad + "/pin/colleague-tries", w.clinic().owner(), null), bad,
                    "colleague tries " + bad);
        }
        assertThat(dsnap(w)).as("nothing written").isEqualTo(before);
        // the control: a well formed id of nothing is 404 on all four, as before
        String unknown = UUID.randomUUID().toString();
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + unknown + "/mar", w.nurse().auth(), null), 404);
        problem(call(HttpMethod.POST, IPD + "/medicine-orders/" + unknown + "/doses", w.nurse().auth(), js(given(w, ist(8, 0)))),
                404);
        problem(call(HttpMethod.POST, TEAM + "/" + unknown + "/pin/reset", w.clinic().owner(), null), 404);
        problem(call(HttpMethod.GET, TEAM + "/" + unknown + "/pin/colleague-tries", w.clinic().owner(), null), 404);
    }

    @Test
    void anIdOfAnotherClinicIs404OnTheFourOperationsNotABadRequestAndNotADifferentAnswer() throws Exception {
        Fix mine = fix();
        Ward theirs = ward();
        String theirOrder = activeOrder(theirs, "pcm", "od");
        clockAt(8, 5);
        problem(call(HttpMethod.GET, marUrl(theirs.adm().id()), mine.w().nurse().auth(), null), 404);
        problem(call(HttpMethod.POST, dosesUrl(theirOrder), mine.w().nurse().auth(), js(given(mine.w(), ist(8, 0)))), 404);
        problem(call(HttpMethod.POST, TEAM + "/" + theirs.nurse().membershipId() + "/pin/reset", mine.w().clinic().owner(), null),
                404);
        problem(call(HttpMethod.GET, TEAM + "/" + theirs.nurse().membershipId() + "/pin/colleague-tries",
                mine.w().clinic().owner(), null), 404);
    }

    @Test
    void theNonOwnerStillGets403OnTheTwoOwnerOnlyOperationsForAWellFormedId() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String id = w.nurse2().membershipId().toString();
        refused(call(HttpMethod.POST, TEAM + "/" + id + "/pin/reset", w.nurse().auth(), null), 403, "forbidden", ONLY_OWNER);
        refused(call(HttpMethod.GET, TEAM + "/" + id + "/pin/colleague-tries", w.nurse().auth(), null), 403, "forbidden",
                ONLY_OWNER);
    }
}
