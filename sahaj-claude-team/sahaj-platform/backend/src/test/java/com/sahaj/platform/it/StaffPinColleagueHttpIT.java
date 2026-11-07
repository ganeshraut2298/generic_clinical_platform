package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.StaffPinPort;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * SAH-38 the three new answers of the second-nurse PIN check as the Give call shows them over HTTP (docs/api/ipd.yaml,
 * docs/api/platform.yaml): 429 {@code colleague-pin-limit} without a retry hint, 503 {@code pin-check-failed} that is the same
 * for a right and a wrong PIN with no dose recorded, and a plain 500 for a constraint or permission fault. The port-level
 * behaviour is in {@link StaffPinColleagueHardeningIT}. Test plan: docs/test-plans/SAH-38.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinColleagueHttpIT extends MarSupport {

    private static final String EVENTS = "platform.staff_pin_colleague_event";

    @Autowired
    private StaffPinPort port;

    private record Fix(Ward w, String hep) {
    }

    private Fix fix() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        return new Fix(w, hep);
    }

    private static String wrong(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    @Test
    void thePairCapOver429HasItsOwnTypeTextAndNoRetryHintAndTheDoseIsNotRecordedAndTheOtherNurseStillWorks() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        NurseWithPin other = nurseWithPin(w.clinic());
        for (int i = 0; i < 4; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 403,
                    "wrong-pin", "That PIN is not right.");
        }
        assertThat(port.check(w.clinic().tenantId(), w.nurse2().userId(), w.nurse2Pin()).outcome())
                .isEqualTo(com.sahaj.platform.api.PinCheck.Outcome.OK);
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 403,
                "wrong-pin", "That PIN is not right.");
        freshPinAllowance(w.nurse());
        DSnap before = dsnap(w);
        for (String pin : new String[] {w.nurse2Pin(), wrong(w.nurse2Pin())}) {
            var actions = doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), pin));
            var response = actions.andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).as("no retry hint: the wait is days").isNull();
            JsonNode p = objectMapper.readTree(response.getContentAsString());
            assertThat(p.get("type").asText()).isEqualTo(PROBLEM_BASE + "colleague-pin-limit");
            assertThat(p.get("title").asText()).isEqualTo("Too many wrong PIN tries");
            assertThat(p.get("detail").asText()).isEqualTo("Too many wrong PIN tries for this nurse. Choose another nurse.");
            assertThat(p.has("retryAfterSeconds")).isFalse();
            assertThat(p.toString()).doesNotContain(w.nurse2().membershipId().toString()).doesNotContain(displayName(w.nurse2()));
            assertThat(hasBarePin(p.toString(), pin)).isFalse();
        }
        assertThat(dsnap(w)).as("nothing recorded, counted or audited").isEqualTo(before);
        // the other nurse still works for the same caller
        doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), other.nurse(), other.pin()));
    }

    @Test
    void aTimeoutIs503PinCheckFailedTheSameForARightAndAWrongPinAndNothingIsRecordedOrCounted() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        // one real wrong try first, so that a right PIN has something to clear and writes to the event table too
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 403, "wrong-pin",
                "That PIN is not right.");
        DSnap before = dsnap(w);
        String logged;
        try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
            runForcedTimeout(w, f);
            logged = logs.allText();
        }
        // S38 hardening: the fail-closed log line names the signed-in giver (callerUserId) next to the tenant and the colleague
        assertThat(logged).contains("staff-pin-colleague-check").contains("outcome=CHECK_FAILED")
                .contains("callerUserId=" + w.nurse().userId()).contains("tenantId=" + w.clinic().tenantId())
                .contains("membershipId=" + w.nurse2().membershipId());
        assertThat(logged).as("no PIN and no name in the log").doesNotContain(displayName(w.nurse2()))
                .doesNotContain(displayName(w.nurse()));
        assertThat(hasBarePin(logged, w.nurse2Pin())).isFalse();
        assertThat(dsnap(w)).isEqualTo(before);
        assertThat(doseRowsOfOrder(w.clinic(), f.hep())).isZero();
        // and the dose can be given right away once the store answers again
        doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
    }

    private void runForcedTimeout(Ward w, Fix f) throws Exception {
        forcedEventFailure(w, "55P03", () -> {
            for (String pin : new String[] {w.nurse2Pin(), wrong(w.nurse2Pin())}) {
                var response = doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), pin)).andReturn()
                        .getResponse();
                assertThat(response.getStatus()).isEqualTo(503);
                JsonNode p = objectMapper.readTree(response.getContentAsString());
                assertThat(p.get("type").asText()).isEqualTo(PROBLEM_BASE + "pin-check-failed");
                assertThat(p.get("title").asText()).isEqualTo("PIN check failed");
                assertThat(p.get("detail").asText()).isEqualTo("We could not check the PIN. Try again.");
                assertThat(hasBarePin(p.toString(), pin)).isFalse();
                assertThat(p.toString()).doesNotContain("55P03").doesNotContain("staff_pin")
                        .doesNotContain("Exception");
            }
        });
    }

    @Test
    void aConstraintOrPermissionFaultIsAPlain500WithNoDetailAndEverythingIsRolledBack() throws Exception {
        for (String errcode : new String[] {"23514", "42501"}) {
            Fix f = fix();
            Ward w = f.w();
            DSnap before = dsnap(w);
            forcedEventFailure(w, errcode, () -> {
                var response = doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin())))
                        .andReturn().getResponse();
                assertThat(response.getStatus()).as(errcode).isEqualTo(500);
                JsonNode p = objectMapper.readTree(response.getContentAsString());
                assertThat(p.get("type").asText()).endsWith("/internal-error");
                assertThat(p.toString()).doesNotContain("forced failure").doesNotContain("staff_pin").doesNotContain(errcode)
                        .doesNotContain("Exception").doesNotContain("pin-check-failed");
            });
            assertThat(dsnap(w)).as(errcode).isEqualTo(before);
        }
    }

    private void forcedEventFailure(Ward w, String errcode, Action action) throws Exception {
        String suffix = w.nurse2().membershipId().toString().replace('-', '_');
        String function = "platform.it_fail_http_" + suffix;
        String trigger = "trg_it_fail_http_" + suffix;
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the fail-closed test' using errcode = '" + errcode + "'; end; $$");
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

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    @Test
    void theTextsAreExactlyTheContractWordingAndTheOldWristbandTextsAreGone() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
        assertThat(eventLines(w.clinic(), w.adm().id()))
                .contains("give|Gave Inj. Heparin 5,000 IU (8 AM dose) · identity checked against UHID · second check by "
                        + displayName(w.nurse2()))
                .noneMatch(l -> l.contains("wristband scanned"));
    }
}
