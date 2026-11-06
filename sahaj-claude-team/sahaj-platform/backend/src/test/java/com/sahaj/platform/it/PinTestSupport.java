package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-31 integration tests: the real application on {@code sahaj_test} with a random staff PIN
 * pepper generated once per run (ADR-010 D3: no fixed value is committed), people of a clinic as {@link Pn} (user id,
 * membership id, own mobile, token), the one-time code read the one permitted way (the dev/test log line), the PIN
 * endpoints, the {@code platform.staff_pin} row read back by SQL, and the states the application cannot reach (a lock
 * in the past, a window older than 24 hours) written by the table owner with the guard trigger switched off inside one
 * transaction. All classes that extend it share one Spring context. The contract texts used by assertions are copied
 * from {@code docs/api/platform.yaml}, {@code docs/api/ipd.yaml} and ADR-010 D11, not read from the application.
 */
abstract class PinTestSupport extends SafetyTestSupport {

    static final String ME_PIN = "/api/v1/platform/me/pin";
    static final String ME_PIN_CODE = ME_PIN + "/code";
    static final String TEAM = "/api/v1/platform/team";
    static final String OVERVIEW = "/api/v1/platform/setup/overview";

    /** Base64 of 32 random bytes, new for every JVM run (applied by {@link WithPinPepper}). */
    static final String PEPPER = PinPepper.VALUE;

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.context.ApplicationContext applicationContext;

    /**
     * Test seam for the in-memory limits of failed PIN checks and of PIN saves (10 each per 5 minutes, one bucket each since
     * the review fix pass): forgets what was counted for this person in both, so a test that needs many failed checks (a
     * lockout takes five, the third lock fifteen) is not stopped by the limit. The limits have their own tests that do not
     * call this.
     */
    protected void freshPinAllowance(Pn p) {
        try {
            Object limits = applicationContext.getBean(com.sahaj.platform.infrastructure.ratelimit.InMemoryStaffPinLimits.class);
            forgetHits(limits, "pinFailures", p);
            forgetHits(limits, "pinSets", p);
            // SAH-32: the two per-caller buckets of failed colleague checks (5 per 15 minutes, 10 per 24 hours) are keyed
            // "tenantId:callerUserId" as well, so a person who is the caller of many second-nurse tries starts clean too.
            forgetHits(limits, "colleagueFailuresShort", p);
            forgetHits(limits, "colleagueFailuresDay", p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the limiter changed shape: " + e, e);
        }
    }

    /**
     * SAH-32 test seam: forgets only the 15-minute bucket of this caller's failed colleague checks and keeps the 24-hour one,
     * the way 15 minutes passing would. The limits use the application clock, which cannot move, so this is the only way to
     * reach the 10-per-24-hours limit without waiting. The limits have their own unit tests.
     */
    protected void forgetColleagueShortWindow(Pn caller) {
        try {
            Object limits = applicationContext.getBean(com.sahaj.platform.infrastructure.ratelimit.InMemoryStaffPinLimits.class);
            forgetHits(limits, "colleagueFailuresShort", caller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the limiter changed shape: " + e, e);
        }
    }

    /** SAH-32 test seam: forgets a person's own failed-check bucket (10 per 5 minutes) only, so it can be told from the caller's. */
    protected void forgetOwnFailureWindow(Pn p) {
        try {
            Object limits = applicationContext.getBean(com.sahaj.platform.infrastructure.ratelimit.InMemoryStaffPinLimits.class);
            forgetHits(limits, "pinFailures", p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the limiter changed shape: " + e, e);
        }
    }

    /** Forgets one person's hits in one named limiter of the bean. */
    private void forgetHits(Object limits, String limiterField, Pn p) throws ReflectiveOperationException {
        java.lang.reflect.Field field = limits.getClass().getDeclaredField(limiterField);
        field.setAccessible(true);
        Object limiter = field.get(limits);
        java.lang.reflect.Field hits = limiter.getClass().getDeclaredField("hits");
        hits.setAccessible(true);
        ((java.util.Map<?, ?>) hits.get(limiter)).remove(p.clinic().tenantId() + ":" + p.userId());
    }

    // ---- texts (ADR-010 D11 and platform.yaml) ----
    static final String PIN_UNAVAILABLE = "PIN checks are not available right now. Try again later.";
    static final String PIN_RATE = "Too many PIN tries in a short time. Try again in a few minutes.";
    static final String PIN_NEVER = "You have not set your PIN yet. Set your PIN, then try again.";
    static final String PIN_RESET_TEXT = "The clinic owner reset your PIN. Set a new one, then try again.";
    static final String PIN_SWITCHED_OFF = "Your PIN was switched off after too many wrong tries. Set a new one, then try again.";
    static final String PIN_WEAK = "This PIN is too easy to guess. Choose another one.";
    static final String PIN_FORMAT = "Enter a 4-digit PIN.";
    static final String PIN_MISMATCH = "The two PINs do not match.";
    static final String CODE_FORMAT = "Enter the 6-digit code.";
    static final String ONLY_OWNER = "Only the clinic owner can do this.";

    private static final Pattern CODE_IN_LOG = Pattern.compile("One-time code for [^:]+:\\s*(\\d{6})");
    private static final String[] GOOD_PINS = {"4827", "7391", "5164", "8203", "3946", "6058", "9417", "1583", "7249",
            "3681", "5972", "4306"};
    private static int nextGood;

    /** A person of one clinic. */
    protected record Pn(Clinic clinic, UUID userId, UUID membershipId, String mobile, String role,
                        RequestPostProcessor auth) {

        @Override
        public String toString() {
            return "Pn[" + role + " " + membershipId + "]";
        }
    }

    /**
     * A clinic holds at most {@code PlanLimits.MAX_USERS} (5) people through the Team endpoint, and a hospital of these tests
     * already has the owner and a doctor, so a test with a doctor, a nurse, a pharmacist and one more would be refused with
     * 409 plan-limit. A person is therefore made the way a second clinic membership of an existing user is made elsewhere in
     * the tests ({@link #addMembership}): a real sign-up of a new mobile (own user id and mobile, and a clinic of their own that
     * the test never uses) and a committed membership in this clinic with the given display name. The token is signed for that
     * real user and the role.
     */
    @Override
    protected Staff staff(Clinic clinic, String role, String name) throws Exception {
        Session session = signUp(uniqueMobile());
        try (Connection connection = db().app()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement insert = connection.prepareStatement("insert into platform.membership (tenant_id, user_id, role, "
                    + "display_name) values (?::uuid, ?::uuid, ?, ?)")) {
                insert.setString(1, clinic.tenantId().toString());
                insert.setString(2, session.userId().toString());
                insert.setString(3, role);
                insert.setString(4, name);
                insert.executeUpdate();
            }
            connection.commit();
        }
        return new Staff(session.userId(), name, role, tokenOf(clinic, session.userId(), role));
    }

    protected Pn pn(Clinic clinic, Staff staff) throws SQLException {
        String membership = values(clinic, "select id::text from platform.membership where tenant_id = ?::uuid "
                + "and user_id = ?::uuid", clinic.tenantId().toString(), staff.userId().toString()).get(0);
        String mobile = values(clinic, "select mobile from platform.app_user where id = ?::uuid",
                staff.userId().toString()).get(0);
        return new Pn(clinic, staff.userId(), UUID.fromString(membership), mobile, staff.role(), staff.auth());
    }

    protected Pn newPn(Clinic clinic, String role) throws Exception {
        return pn(clinic, staff(clinic, role));
    }

    protected Pn ownerPn(Clinic clinic) throws SQLException {
        return pn(clinic, ownerStaff(clinic));
    }

    /** A PIN that passes the weak-PIN rules for this person (not part of their mobile). */
    protected static synchronized String goodPin(Pn person) {
        for (int i = 0; i < GOOD_PINS.length; i++) {
            String candidate = GOOD_PINS[nextGood++ % GOOD_PINS.length];
            if (!person.mobile().contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no good pin");
    }

    /** A different good PIN (never equal to the one given). */
    protected static String otherPin(Pn person, String not) {
        String pin;
        do {
            pin = goodPin(person);
        } while (pin.equals(not));
        return pin;
    }

    // ---- the code and the endpoints ----

    /** Lets the next code request through the 30-second resend wait (does not expire the latest code). */
    protected void allowResend(String mobile) throws SQLException {
        try (Connection c = db().app();
             PreparedStatement ps = c.prepareStatement("update platform.otp_challenge set last_sent_at = now() - interval "
                     + "'10 minutes' where mobile = ?")) {
            ps.setString(1, mobile);
            ps.executeUpdate();
        }
    }

    /** Requests the code through the real endpoint and reads it from the permitted dev log line. */
    protected String sendCode(Pn p) throws Exception {
        allowResend(p.mobile());
        try (LogCapture logs = new LogCapture()) {
            call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null).andExpect(status().isAccepted());
            Matcher m = CODE_IN_LOG.matcher(logs.allText());
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            assertThat(last).as("the permitted dev log line with the code").isNotNull();
            return last;
        }
    }

    protected static String pinBody(String code, String newPin, String confirmPin) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"code\":").append(code == null ? "null" : "\"" + code + "\"");
        b.append(",\"newPin\":").append(newPin == null ? "null" : "\"" + newPin + "\"");
        b.append(",\"confirmPin\":").append(confirmPin == null ? "null" : "\"" + confirmPin + "\"");
        return b.append('}').toString();
    }

    protected org.springframework.test.web.servlet.ResultActions putPin(Pn p, String code, String pin, String confirm)
            throws Exception {
        return call(HttpMethod.PUT, ME_PIN, p.auth(), pinBody(code, pin, confirm));
    }

    /** Code, then a successful save. Returns the status answer. */
    protected JsonNode setPin(Pn p, String pin) throws Exception {
        String code = sendCode(p);
        return ok(HttpMethod.PUT, ME_PIN, p.auth(), pinBody(code, pin, pin), 200);
    }

    protected JsonNode pinStatus(Pn p) throws Exception {
        return ok(HttpMethod.GET, ME_PIN, p.auth(), null, 200);
    }

    protected JsonNode teamRow(Clinic clinic, RequestPostProcessor viewer, UUID membershipId) throws Exception {
        for (JsonNode row : ok(HttpMethod.GET, TEAM, viewer, null, 200)) {
            if (membershipId.toString().equals(row.get("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("member not in the team list");
    }

    protected JsonNode resetPin(Clinic clinic, UUID membershipId) throws Exception {
        return ok(HttpMethod.POST, TEAM + "/" + membershipId + "/pin/reset", clinic.owner(), null, 200);
    }

    // ---- the staff_pin row ----

    /** The stored row as text columns: hasPin, failed, lockCount, locked(Future), window, reason, version, setAt, resetBy. */
    protected record PinRow(boolean exists, boolean hasPin, int failed, int lockCount, boolean lockedNow,
                            boolean hasWindow, String reason, long version, boolean hasSetAt, String resetBy,
                            boolean hasResetAt, String hash) {
    }

    protected PinRow pinRow(Clinic clinic, UUID membershipId) throws SQLException {
        List<String> v = values(clinic, "select (pin_hash is not null)::text, failed_count::text, lock_count::text, "
                + "(locked_until is not null and locked_until > clock_timestamp())::text, "
                + "(lock_window_started_at is not null)::text, coalesce(not_set_reason, ''), version::text, "
                + "(set_at is not null)::text, coalesce(reset_by::text, ''), (reset_at is not null)::text, "
                + "coalesce(pin_hash, '') from platform.staff_pin where membership_id = ?::uuid",
                membershipId.toString());
        if (v.isEmpty()) {
            return new PinRow(false, false, 0, 0, false, false, "", -1, false, "", false, "");
        }
        return new PinRow(true, Boolean.parseBoolean(v.get(0)), Integer.parseInt(v.get(1)), Integer.parseInt(v.get(2)),
                Boolean.parseBoolean(v.get(3)), Boolean.parseBoolean(v.get(4)), v.get(5), Long.parseLong(v.get(6)),
                Boolean.parseBoolean(v.get(7)), v.get(8), Boolean.parseBoolean(v.get(9)), v.get(10));
    }

    /** True when the 4 digits stand alone in the text (not inside a longer number, a time or a uuid). */
    protected static boolean hasBarePin(String text, String pin) {
        return Pattern.compile("(?<![0-9A-Za-z.:\\-])" + Pattern.quote(pin) + "(?![0-9A-Za-z.:\\-])").matcher(text).find();
    }

    protected static final String[] PIN_TABLE = {"platform.staff_pin"};

    /**
     * Runs a statement on the staff_pin row of one membership as the table owner with the update guard switched off in
     * the same transaction (committed): the only way to make a lock that is already over or a window older than 24 hours,
     * which the application cannot write. DDL is transactional, so no other session sees the trigger off.
     */
    protected void forcePinRow(Pn p, String setClause) throws SQLException {
        asOwner(true, PIN_TABLE, c -> {
            DbAccess.execute(c, "alter table platform.staff_pin disable trigger trg_staff_pin_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update platform.staff_pin set " + setClause
                    + " where membership_id = ?::uuid")) {
                ps.setString(1, p.membershipId().toString());
                assertThat(ps.executeUpdate()).as("forced rows").isOne();
            }
            DbAccess.execute(c, "alter table platform.staff_pin enable trigger trg_staff_pin_guard_update");
            return null;
        });
    }

    /** The lock of the person ended a minute ago. */
    protected void lockEnded(Pn p) throws SQLException {
        forcePinRow(p, "locked_until = clock_timestamp() - interval '1 minute'");
    }

    /** The first lock of the 24-hour window happened {@code hours} hours ago. */
    protected void windowStartedHoursAgo(Pn p, int hours) throws SQLException {
        forcePinRow(p, "lock_window_started_at = clock_timestamp() - interval '" + hours + " hours'");
    }

    /** Sets the failed count, lock count and window as the owner (guard off). The window is now when {@code locks} &gt; 0. */
    protected void forceCounters(Pn p, int failed, int locks) throws SQLException {
        forcePinRow(p, "failed_count = " + failed + ", lock_count = " + locks + ", lock_window_started_at = "
                + (locks > 0 ? "clock_timestamp()" : "null") + ", locked_until = null");
    }

    protected static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    protected long auditCount(Clinic clinic, String action, UUID entityId) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ? and entity_id = ?::uuid",
                action, entityId.toString());
    }

    /** All audit rows of the clinic as one text (before and after), for "never contains" checks. */
    protected String allAuditText(Clinic clinic) throws SQLException {
        return values(clinic, "select coalesce(string_agg(action || ' ' || coalesce(before_json::text, '') || ' ' "
                + "|| coalesce(after_json::text, ''), E'\\n'), '') from platform.audit_event").get(0);
    }
}
