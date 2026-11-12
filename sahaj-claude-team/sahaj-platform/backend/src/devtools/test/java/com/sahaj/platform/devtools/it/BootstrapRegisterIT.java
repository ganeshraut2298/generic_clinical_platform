package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.NAME;
import static com.sahaj.platform.devtools.it.BootstrapSupport.PASSWORD;
import static com.sahaj.platform.devtools.it.BootstrapSupport.PIN;
import static com.sahaj.platform.devtools.it.BootstrapSupport.json;
import static com.sahaj.platform.devtools.it.BootstrapSupport.newTicket;
import static com.sahaj.platform.devtools.it.BootstrapSupport.register;
import static com.sahaj.platform.devtools.it.BootstrapSupport.unlock;
import static com.sahaj.platform.devtools.it.BootstrapSupport.wrongPin;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.application.admin.Base32;
import com.sahaj.platform.application.admin.Totp;
import com.sahaj.platform.devtools.it.BootstrapSupport.Ticket;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * SAH-42 stories 4 and 5, the register call, on the whole application with the real bootstrap functions in {@code sahaj_test}
 * (ADR-018 D5 and D6, docs/database/SAH-42.md S14 to S21). Same preconditions and skip behaviour as {@link BootstrapUnlockIT}.
 *
 * <p>The ticket slot is single: there is never "two valid tickets", so the race of the story (two registers at once) is run
 * with ten calls that share the one valid ticket and carry different e-mail addresses. Times are moved by data, never waited.
 */
class BootstrapRegisterIT extends BootstrapHttpBase {

    private static final String PROBLEM = "https://sahaj.in/problems/";
    private static final String PASSWORD_TEXT = "The password must be 12 to 64 characters and must not contain the first part of your email.";
    private static final String TICKET_TEXT = "Your unlock took too long. Enter the PIN again.";

    // ---- helpers -----------------------------------------------------------------------------------------

    private void assertTicketInvalid(RawHttp.Response response, String label) throws IOException {
        assertThat(response.status()).as(label).isEqualTo(401);
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).as(label).isEqualTo(PROBLEM + "bootstrap-ticket-invalid");
        assertThat(body.get("title").asText()).isEqualTo(TICKET_TEXT);
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        String cookie = BootstrapSupport.bindingCookieLine(response);
        assertThat(cookie).as(label + ": the cookie is cleared").isNotNull().contains("Max-Age=0").contains("Path=/api/v1/admin/bootstrap");
    }

    private void assertValidation(RawHttp.Response response, String... fieldAndMessage) throws IOException {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "validation");
        JsonNode errors = body.get("fieldErrors");
        assertThat(errors.size()).as("field errors " + errors).isEqualTo(fieldAndMessage.length / 2);
        for (int i = 0; i < errors.size(); i++) {
            assertThat(errors.get(i).get("field").asText()).isEqualTo(fieldAndMessage[2 * i]);
            assertThat(errors.get(i).get("message").asText()).isEqualTo(fieldAndMessage[2 * i + 1]);
        }
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        assertThat(BootstrapSupport.setCookies(response)).as("a rejected form does not touch the cookie").isEmpty();
    }

    private static long adminRows() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarLong(c, "select count(*) from platform.platform_admin");
        }
    }

    private static long preAuthRowsOf(UUID adminId) throws Exception {
        try (Connection c = SampleAdminDb.migrator();
             PreparedStatement s = c.prepareStatement("select count(*) from platform.platform_admin_preauth where admin_id = ?")) {
            s.setObject(1, adminId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long tenantRows() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarLong(c, "select count(*) from platform.tenant");
        }
    }

    /** One admin row as the owner reads it: id, email, name, hash, active, not-enrolled flags. */
    private record AdminRow(UUID id, String email, String fullName, String hash, boolean active, boolean totpEmpty) {
    }

    private static AdminRow theOnlyAdmin() throws Exception {
        try (Connection c = SampleAdminDb.migrator();
             PreparedStatement s = c.prepareStatement("select id, email, full_name, password_hash, active, "
                     + "(totp_secret_enc is null and totp_key_version is null and totp_enrolled_at is null) from platform.platform_admin");
             ResultSet rs = s.executeQuery()) {
            assertThat(rs.next()).as("one admin").isTrue();
            AdminRow row = new AdminRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getBoolean(6));
            assertThat(rs.next()).as("only one admin").isFalse();
            return row;
        }
    }

    // ---- S14 the happy path ------------------------------------------------------------------------------

    @Test
    void validDetailsCreateTheFirstAdminNotEnrolledAnswer201WithNoBodyNoPreAuthTokenAndClearTheCookie() throws Exception {
        // ADR-018 Revision 5 (security L-4): the 201 has no body and register issues no pre-auth token.
        long tenantsBefore = tenantRows();
        Ticket ticket = newTicket(app);
        String email = uniqueEmail();

        RawHttp.Response response = register(app, ticket, NAME, email, PASSWORD);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).as("the 201 has an empty body").isEmpty();
        assertThat(response.hasHeader("Content-Type")).as("no Content-Type").isFalse();
        assertThat(response.hasHeader("Location")).as("no Location").isFalse();
        assertThat(response.header("Content-Length")).as("Content-Length is absent or 0 (no body on the wire)").isIn(null, "0");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(response.header("Pragma")).isEqualTo("no-cache");
        assertThat(response.header("X-Content-Type-Options")).as("nosniff on the 201 (BootstrapController, contract 0.5.0)").isEqualTo("nosniff");
        String cookie = BootstrapSupport.bindingCookieLine(response);
        assertThat(cookie).as("binding cookie cleared").isNotNull().contains("Max-Age=0").contains("Path=/api/v1/admin/bootstrap");
        assertThat(BootstrapSupport.setCookies(response)).as("no other cookie").hasSize(1);

        AdminRow admin = theOnlyAdmin();
        assertThat(admin.email()).isEqualTo(email);
        assertThat(admin.fullName()).isEqualTo(NAME);
        assertThat(admin.active()).isTrue();
        assertThat(admin.totpEmpty()).as("created NOT enrolled: every authenticator column is null").isTrue();
        assertThat(admin.hash()).as("BCrypt strength 12").startsWith("$2a$12$").hasSize(60);
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, admin.hash())).as("the stored hash matches the password").isTrue();
        assertThat(tenantRows()).as("no tenant row, no membership: the admin belongs to no clinic (BR11, BR14)").isEqualTo(tenantsBefore);
        assertThat(BootstrapDb.state().hasTicket()).as("the ticket is spent").isFalse();
        assertThat(BootstrapDb.state().failures()).isZero();
        assertThat(preAuthRowsOf(admin.id())).as("register wrote no pre-auth row for the new admin (Revision 5)").isZero();
    }

    @Test
    void theCreationIsAuditedOnceWithTheIdAndNameAndNoEmailPasswordHashTicketOrPin() throws Exception {
        Ticket ticket = newTicket(app);
        String email = uniqueEmail();
        RawHttp.Response response = register(app, ticket, NAME, email, PASSWORD);
        assertThat(response.status()).isEqualTo(201);

        AdminRow admin = theOnlyAdmin();
        List<String> created = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED");
        assertThat(created).hasSize(1);
        JsonNode row = json(created.get(0));
        assertThat(row.get("kind").asText()).isEqualTo("AUTH");
        assertThat(row.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(row.get("summary").asText()).isEqualTo("First admin created through bootstrap registration");
        assertThat(row.get("admin_id").asText()).isEqualTo(admin.id().toString());
        assertThat(row.get("admin_name").asText()).isEqualTo(NAME);
        assertThat(row.get("ip").asText()).isEqualTo("127.0.0.1");
        assertThat(row.get("request_id").asText()).isNotBlank();
        assertThat(row.get("target_tenant_id").isNull()).isTrue();

        List<String> forbidden = List.of(email, PASSWORD, admin.hash(), ticket.value(), ticket.binding(), PIN,
                BootstrapSupport.pinHash(), BootstrapSupport.atRestHash(ticket.value()), BootstrapSupport.atRestHash(ticket.binding()));
        for (String text : SampleAdminDb.auditRowsSince(snapshot)) {
            for (String value : forbidden) {
                assertThat(text).as("no audit column holds a secret value").doesNotContain(value);
            }
        }
        try (Connection c = SampleAdminDb.migrator()) {
            for (String table : List.of("platform_admin_audit", "platform_admin_preauth", "platform_admin_bootstrap_state")) {
                try (PreparedStatement dump = c.prepareStatement(
                        "select coalesce(string_agg(row_to_json(t)::text, ' '), '') from platform." + table + " t");
                     ResultSet rs = dump.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).as("table " + table + " holds no plain password, PIN, ticket or binding")
                            .doesNotContain(PASSWORD).doesNotContain(PIN).doesNotContain(ticket.value())
                            .doesNotContain(ticket.binding());
                }
            }
        }
    }

    @Test
    void theEmailIsStoredTrimmedAndLowerCaseAndTheNameTrimmed() throws Exception {
        Ticket ticket = newTicket(app);
        String local = "mixed" + UUID.randomUUID().toString().substring(0, 6);

        RawHttp.Response response = register(app, ticket, "  " + NAME + "  ", "  " + local.toUpperCase() + "@Example.Test  ", PASSWORD);

        assertThat(response.status()).isEqualTo(201);
        AdminRow admin = theOnlyAdmin();
        assertThat(admin.email()).isEqualTo(local + "@example.test");
        assertThat(admin.fullName()).isEqualTo(NAME);
    }

    @Test
    void aNonAsciiEmailAndANameWithANoBreakSpaceAreJudgedTheSameByTheDatabaseAndByJava() throws Exception {
        // Code review N4: the database register re-check uses lower/btrim/\s in the database locale, Java uses Locale.ROOT, its own
        // \s and trim (which does not strip U+00A0). A disagreement would be a 22023 from the database, a bodyless 500 with the
        // ticket unspent. Expected: both agree, 201, stored values equal the Java result.
        Ticket ticket = newTicket(app);
        String local = "JOSÉ-" + UUID.randomUUID().toString().substring(0, 6);
        String email = "  " + local + "@Example.Test  ";
        String name = " Asha Verma ";

        RawHttp.Response response = register(app, ticket, name, email, PASSWORD);

        assertThat(response.status()).as("a 5xx here is a database or Java disagreement (22023), not a validation answer").isEqualTo(201);
        AdminRow admin = theOnlyAdmin();
        assertThat(admin.email()).isEqualTo(email.trim().toLowerCase(java.util.Locale.ROOT));
        assertThat(admin.fullName()).isEqualTo(name.trim());
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 16, 64, -24})
    void passwordsAtTheEdgesOfTheRuleAreAcceptedTwelveCharactersACommonOneSixtyFourAndExactly72Bytes(int shape) throws Exception {
        // 12 and 64 characters are the edges, 16 is a common password (no breached-password check, OQ-42-2), and -24 means
        // 24 characters of 3 bytes each: exactly 72 bytes, the edge of the BCrypt limit.
        String password = switch (shape) {
            case 16 -> "passwordpassword";
            case -24 -> "€".repeat(24);
            default -> "Aa1-".repeat(20).substring(0, shape);
        };
        assertThat(password.length() == Math.abs(shape)).as("test data").isTrue();
        Ticket ticket = newTicket(app);

        RawHttp.Response response = register(app, ticket, NAME, uniqueEmail(), password);

        assertThat(response.status()).as("password of " + password.length() + " characters").isEqualTo(201);
        assertThat(new BCryptPasswordEncoder().matches(password, theOnlyAdmin().hash())).isTrue();
    }

    @Test
    void aNameOfExactly120CharactersIsAcceptedAfterTrimming() throws Exception {
        Ticket ticket = newTicket(app);
        assertThat(register(app, ticket, " " + "N".repeat(120) + " ", uniqueEmail(), PASSWORD).status()).isEqualTo(201);
        assertThat(theOnlyAdmin().fullName()).hasSize(120);
    }

    @Test
    void theNewAdminSignsInFirstThenFinishesEnrolmentWithTheTokenOfThatSignInAndThenSignsInWithEmailAndPassword() throws Exception {
        // ADR-018 Revision 5: register returns no token. The owner's first sign-in (the page's fallback, /admin/sign-in)
        // issues its own ENROL token through admin_preauth_create, which is why that function stays a prerequisite.
        Ticket ticket = newTicket(app);
        String email = uniqueEmail();
        assertThat(register(app, ticket, NAME, email, PASSWORD).status()).isEqualTo(201);

        RawHttp.Response first = app.post("/api/v1/admin/auth/login")
                .json("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").send();
        assertThat(first.status()).isEqualTo(200);
        assertThat(json(first).get("next").asText()).as("first sign-in of a not-enrolled admin").isEqualTo("ENROL");
        String preAuth = json(first).get("preAuthToken").asText();
        assertThat(preAuth).as("a token of the sign-in's own").matches(BootstrapSupport.TICKET_SHAPE);

        // The existing enrolment step, unchanged.
        RawHttp.Response enrol = app.post("/api/v1/admin/auth/totp/enrol").json("{\"preAuthToken\":\"" + preAuth + "\"}").send();
        assertThat(enrol.status()).as("enrol with the token that the first sign-in returned").isEqualTo(200);
        String secretKey = json(enrol).get("secretKey").asText();
        byte[] secret = Base32.decode(secretKey.replace(" ", ""));
        String code = Totp.generate(secret, Totp.stepAt(Instant.now()), Totp.DIGITS);
        RawHttp.Response verify = app.post("/api/v1/admin/auth/totp/verify")
                .json("{\"preAuthToken\":\"" + preAuth + "\",\"code\":\"" + code + "\"}").send();
        assertThat(verify.status()).as("confirm the authenticator").isEqualTo(200);
        assertThat(json(verify).get("accessToken").asText()).isNotBlank();

        // Sign in again the normal way: the admin is now enrolled, so the next step is the code.
        RawHttp.Response login = app.post("/api/v1/admin/auth/login")
                .json("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").send();
        assertThat(login.status()).isEqualTo(200);
        assertThat(json(login).get("next").asText()).isEqualTo("TOTP");
    }

    @Test
    void theSignInOfAnAdminWhoClosedTheBrowserBeforeTheCodeOffersEnrolmentAgain() throws Exception {
        Ticket ticket = newTicket(app);
        String email = uniqueEmail();
        assertThat(register(app, ticket, NAME, email, PASSWORD).status()).isEqualTo(201);

        RawHttp.Response login = app.post("/api/v1/admin/auth/login")
                .json("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").send();
        assertThat(login.status()).isEqualTo(200);
        assertThat(json(login).get("next").asText()).as("first sign-in of a not-enrolled admin").isEqualTo("ENROL");
    }

    @Test
    void aBearerTokenAndOtherCookiesSentWithTheRegisterCallAreIgnored() throws Exception {
        Ticket ticket = newTicket(app);
        RawHttp.Response response = BootstrapSupport.registerRequest(app,
                BootstrapSupport.registerBody(ticket.value(), NAME, uniqueEmail(), PASSWORD), null)
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")
                .header("Cookie", "sahaj_admin_rt=junk; " + BootstrapSupport.COOKIE + "=" + ticket.binding()).send();
        assertThat(response.status()).isEqualTo(201);
    }

    // ---- closing ---------------------------------------------------------------------------------------------

    @Test
    void afterTheFirstAdminExistsBothCallsAreTheEmpty404EvenWithTheSpentTicketOrTheCorrectPin() throws Exception {
        Ticket ticket = newTicket(app);
        assertThat(register(app, ticket, NAME, uniqueEmail(), PASSWORD).status()).isEqualTo(201);
        String reference = BootstrapSupport.referenceNotFound(app);

        assertThat(register(app, ticket, NAME, uniqueEmail(), PASSWORD).assertDevNotFound().fingerprint())
                .as("replay of the spent ticket").isEqualTo(reference);
        assertThat(unlock(app, PIN).assertDevNotFound().fingerprint()).as("unlock with the correct PIN").isEqualTo(reference);
        assertThat(unlock(app, wrongPin(1)).assertDevNotFound().fingerprint()).isEqualTo(reference);
        assertThat(BootstrapSupport.registerRequest(app, "not json", null).send().assertDevNotFound().fingerprint())
                .as("closed beats an unreadable body").isEqualTo(reference);
        assertThat(adminRows()).as("still one admin").isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isEqualTo(1);
    }

    @Test
    void anAdminCreatedSomeOtherWayBeforeTheRegisterCallClosesItAndNothingIsCreated() throws Exception {
        Ticket ticket = newTicket(app);
        SampleAdminDb.insertAdmin(uniqueEmail(), true);

        RawHttp.Response response = register(app, ticket, NAME, uniqueEmail(), PASSWORD);

        response.assertDevNotFound();
        assertThat(adminRows()).as("only the admin made by hand").isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isZero();
    }

    @Test
    void tenRegisterCallsAtTheSameMomentWithTheOneValidTicketCreateExactlyOneAdmin() throws Exception {
        Ticket ticket = newTicket(app);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return register(app, ticket, NAME, uniqueEmail(), PASSWORD).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(180, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 201).count()).as("created: " + statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 404).count()).as("the other nine are the empty 404: " + statuses).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(adminRows()).isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isEqualTo(1);
    }

    // ---- ticket rules (story 4) -------------------------------------------------------------------------------

    @Test
    void everyMissingMalformedUnknownOrMismatchedTicketOrCookieIsThe401AndLeavesTheOpenTicketAndTheDatabaseAlone() throws Exception {
        Ticket good = newTicket(app);
        BootstrapDb.State before = BootstrapDb.state();
        String other = "A".repeat(43);
        String form = "\"fullName\":\"" + NAME + "\",\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + PASSWORD + "\"";
        record Case(String label, String body, String cookie) {
        }
        List<Case> cases = List.of(
                new Case("no ticket field", "{" + form + "}", good.binding()),
                new Case("empty ticket", "{\"ticket\":\"\"," + form + "}", good.binding()),
                new Case("null ticket", "{\"ticket\":null," + form + "}", good.binding()),
                new Case("ticket too short", "{\"ticket\":\"short\"," + form + "}", good.binding()),
                new Case("ticket one character too long", "{\"ticket\":\"" + good.value() + "A\"," + form + "}", good.binding()),
                new Case("ticket with a space", "{\"ticket\":\"" + good.value().substring(0, 42) + " \"," + form + "}", good.binding()),
                new Case("ticket with a plus sign", "{\"ticket\":\"" + good.value().substring(0, 42) + "+\"," + form + "}", good.binding()),
                new Case("a random well-shaped ticket", "{\"ticket\":\"" + other + "\"," + form + "}", good.binding()),
                new Case("the right ticket without the cookie (another browser)", "{\"ticket\":\"" + good.value() + "\"," + form + "}", null),
                new Case("the right ticket with a random cookie", "{\"ticket\":\"" + good.value() + "\"," + form + "}", other),
                new Case("the right ticket with an empty cookie", "{\"ticket\":\"" + good.value() + "\"," + form + "}", ""),
                new Case("the two halves swapped", "{\"ticket\":\"" + good.binding() + "\"," + form + "}", good.value()),
                new Case("the cookie as the ticket", "{\"ticket\":\"" + good.binding() + "\"," + form + "}", good.binding()),
                new Case("the ticket as the cookie", "{\"ticket\":\"" + good.value() + "\"," + form + "}", good.value()),
                new Case("a random ticket with the right cookie", "{\"ticket\":\"" + other + "\"," + form + "}", good.binding()));
        for (Case c : cases) {
            RawHttp.Response response = BootstrapSupport.registerRequest(app, c.body(), c.cookie()).send();
            assertTicketInvalid(response, c.label());
            assertThat(BootstrapDb.state()).as(c.label() + ": the open ticket is untouched").isEqualTo(before);
        }
        // Two cookies of the same name are "no binding".
        RawHttp.Response twoCookies = BootstrapSupport.registerRequest(app, "{\"ticket\":\"" + good.value() + "\"," + form + "}", null)
                .header("Cookie", BootstrapSupport.COOKIE + "=" + good.binding() + "; " + BootstrapSupport.COOKIE + "=" + other).send();
        assertTicketInvalid(twoCookies, "two cookies of the binding name");
        assertThat(adminRows()).as("no admin was created by any refusal").isZero();
        assertThat(BootstrapDb.auditActions(snapshot)).as("an invalid ticket is not audited").isEmpty();

        // The genuine ticket still works after all of that.
        assertThat(register(app, good, NAME, uniqueEmail(), PASSWORD).status()).isEqualTo(201);
    }

    @Test
    void aSecondUnlockReplacesTheFirstTicketOnlyTheNewOneWorksOnce() throws Exception {
        Ticket first = newTicket(app);
        Ticket second = newTicket(app);

        assertTicketInvalid(register(app, first, NAME, uniqueEmail(), PASSWORD), "the replaced ticket");
        assertThat(adminRows()).isZero();
        assertThat(register(app, second, NAME, uniqueEmail(), PASSWORD).status()).as("the newer ticket").isEqualTo(201);
    }

    @Test
    void aTicketFiveMinutesAndOneSecondOldIsRefusedAndOneWithSecondsLeftStillWorks() throws Exception {
        Ticket expired = newTicket(app);
        BootstrapDb.ticketEndsIn(Duration.ofSeconds(-1));
        assertTicketInvalid(register(app, expired, NAME, uniqueEmail(), PASSWORD), "5 minutes 1 second");
        assertThat(adminRows()).isZero();

        Ticket fresh = newTicket(app);
        BootstrapDb.ticketEndsIn(Duration.ofSeconds(20));
        assertThat(register(app, fresh, NAME, uniqueEmail(), PASSWORD).status()).as("4 minutes 40 seconds old").isEqualTo(201);
    }

    @Test
    void aTicketThatWasOpenWhenFiveWrongPinsLockedTheFeatureIsRefusedWith423AndIsDeadWhenTheLockEnds() throws Exception {
        Ticket ticket = newTicket(app);
        for (int n = 1; n <= 5; n++) {
            unlock(app, wrongPin(n));
        }

        RawHttp.Response locked = register(app, ticket, NAME, uniqueEmail(), PASSWORD);

        assertThat(locked.status()).isEqualTo(423);
        JsonNode body = json(locked);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "bootstrap-locked");
        assertThat(body.get("retryAfterSeconds").asLong()).isBetween(3500L, 3600L);
        assertThat(locked.header("Retry-After")).isEqualTo(Long.toString(body.get("retryAfterSeconds").asLong()));
        assertThat(BootstrapSupport.setCookies(locked)).isEmpty();
        assertThat(adminRows()).isZero();

        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));
        assertTicketInvalid(register(app, ticket, NAME, uniqueEmail(), PASSWORD), "after the lock ended the cancelled ticket is dead");
        assertThat(adminRows()).isZero();
    }

    // ---- form (story 5), the ticket is not spent -----------------------------------------------------------------

    @Test
    void aRejectedFormDoesNotSpendTheTicketAndTheOwnerCanCorrectAndSendAgain() throws Exception {
        Ticket ticket = newTicket(app);
        BootstrapDb.State before = BootstrapDb.state();

        assertValidation(register(app, ticket, NAME, uniqueEmail(), "short-pw-11"), "password", PASSWORD_TEXT);
        assertThat(BootstrapDb.state()).as("the ticket is still the same open ticket").isEqualTo(before);
        assertThat(adminRows()).isZero();
        assertThat(BootstrapDb.auditActions(snapshot)).as("a validation error is not audited").isEmpty();

        assertThat(register(app, ticket, NAME, uniqueEmail(), PASSWORD).status()).as("same ticket, corrected form").isEqualTo(201);
    }

    @Test
    void theEveryPasswordRuleIsEnforcedWithTheOneFixedText() throws Exception {
        Ticket ticket = newTicket(app);
        String email = "first.admin@example.test";
        List<String> bad = List.of("short-pw-11", "x".repeat(65), "€".repeat(40) /* 120 bytes */,
                "My-FIRST.ADMIN-pass1" /* contains the e-mail's first part, any case */, "            " /* 12 spaces */.substring(0, 5));
        for (String password : bad) {
            assertValidation(register(app, ticket, NAME, email, password), "password", PASSWORD_TEXT);
        }
        assertThat(adminRows()).isZero();
        assertThat(BootstrapDb.state().hasTicket()).as("none of them spent the ticket").isTrue();
    }

    @Test
    void anEmptyOrOverlongNameIsAFullNameErrorAndABadEmailIsAnEmailError() throws Exception {
        Ticket ticket = newTicket(app);
        for (String name : new String[] {"", "   ", "N".repeat(121)}) {
            assertValidation(register(app, ticket, name, uniqueEmail(), PASSWORD), "fullName", "Enter the full name.");
        }
        for (String email : new String[] {"", "no-at-sign", "a@b", "two@@example.test", "with space@example.test",
                "a".repeat(250) + "@example.test"}) {
            assertValidation(register(app, ticket, NAME, email, PASSWORD), "email", "Enter a valid email.");
        }
        assertThat(adminRows()).isZero();
    }

    @Test
    void registerNeverReturnsMoreThanTwoFieldErrorsTheNameAndTheFirstOfEmailThenPassword() throws Exception {
        Ticket ticket = newTicket(app);
        assertValidation(register(app, ticket, "", "bad-email", "x"), "fullName", "Enter the full name.", "email", "Enter a valid email.");
        assertValidation(register(app, ticket, "", uniqueEmail(), "x"), "fullName", "Enter the full name.", "password", PASSWORD_TEXT);
        assertValidation(register(app, ticket, NAME, "bad-email", "x"), "email", "Enter a valid email.");
        assertValidation(register(app, ticket, NAME, uniqueEmail(), "x"), "password", PASSWORD_TEXT);
    }

    @Test
    void anUnknownFieldIsRejectedAndNeverStored() throws Exception {
        Ticket ticket = newTicket(app);
        String email = uniqueEmail();
        String body = "{\"ticket\":\"" + ticket.value() + "\",\"fullName\":\"" + NAME + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"PLATFORM_OWNER\",\"active\":false}";

        RawHttp.Response response = BootstrapSupport.registerRequest(app, body, ticket.binding()).send();

        assertThat(response.status()).isEqualTo(400);
        assertThat(json(response).get("type").asText()).isEqualTo(PROBLEM + "bad-request");
        assertThat(response.bodyText()).doesNotContain("role").doesNotContain("PLATFORM_OWNER").doesNotContain("active");
        assertThat(adminRows()).isZero();
        assertThat(BootstrapDb.state().hasTicket()).as("the ticket is not spent").isTrue();
    }

    @Test
    void noRegisterAnswerEchoesATicketPasswordEmailOrNameThatWasSent() throws Exception {
        Ticket ticket = newTicket(app);
        String email = "echo-probe@example.test";
        List<RawHttp.Response> answers = List.of(
                register(app, ticket, "Echo Probe Name", email, "short"),
                register(app, ticket, "", "not-an-email", PASSWORD),
                BootstrapSupport.registerRequest(app, "{\"ticket\":\"" + ticket.value() + "\",\"zzUnknown\":\"zzValue\"}", ticket.binding()).send(),
                BootstrapSupport.registerRequest(app, "{\"ticket\":\"" + "B".repeat(43) + "\",\"password\":\"" + PASSWORD + "\"}", null).send());
        for (RawHttp.Response answer : answers) {
            assertThat(answer.bodyText()).doesNotContain(ticket.value()).doesNotContain(ticket.binding()).doesNotContain(PASSWORD)
                    .doesNotContain("Echo Probe Name").doesNotContain(email).doesNotContain("zzUnknown").doesNotContain("zzValue");
        }
    }

    // ---- ADR-018 Revision 3 -------------------------------------------------------------------------------------------

    private static final String REGISTER_PATH = "/api/v1/admin/bootstrap/register";

    /** The control characters of ADR-018 D6 as JSON escape text (a backslash, a u and four digits), built without Unicode escapes in source. */
    private static final List<String> CONTROL_ESCAPES = List.of("\\u0000", "\\u001F", "\\u007F", "\\u0085", "\\t", "\\n", "\\r", "\\uD800");

    private RawHttp.Response registerRaw(Ticket ticket, String fullNameJsonText, String emailJsonText, String password) throws IOException {
        String body = "{\"ticket\":\"" + ticket.value() + "\",\"fullName\":\"" + fullNameJsonText + "\",\"email\":\"" + emailJsonText
                + "\",\"password\":\"" + password + "\"}";
        return BootstrapSupport.registerRequest(app, body, ticket.binding()).send();
    }

    @Test
    void r3I9AControlCharacterInTheFullNameIs400ValidationWithTheFixedTextNotAFiveHundredAndTheTicketIsNotSpent() throws Exception {
        Ticket ticket = newTicket(app);
        BootstrapDb.State before = BootstrapDb.state();
        for (String escape : CONTROL_ESCAPES) {
            for (String name : List.of("Ab" + escape + "cd", escape + "Valid Name", "Valid Name" + escape)) {
                RawHttp.Response response = registerRaw(ticket, name, uniqueEmail(), PASSWORD);
                assertThat(response.status()).as("fullName with " + escape + " in " + name.length() + " chars: 400, never 500").isEqualTo(400);
                JsonNode problem = json(response);
                assertThat(problem.get("type").asText()).isEqualTo(PROBLEM + "validation");
                JsonNode errors = problem.get("fieldErrors");
                assertThat(errors.size()).isEqualTo(1);
                assertThat(errors.get(0).get("field").asText()).isEqualTo("fullName");
                assertThat(errors.get(0).get("message").asText()).isEqualTo("Enter the full name.");
                assertThat(response.bodyText()).as("nothing typed comes back").doesNotContain("Valid Name");
                assertThat(BootstrapDb.state()).as("the ticket is not spent").isEqualTo(before);
                assertThat(adminRows()).as("no admin").isZero();
            }
        }
        assertThat(register(app, ticket, NAME, uniqueEmail(), PASSWORD).status()).as("the same ticket still creates the admin").isEqualTo(201);
    }

    @Test
    void r3I9AControlCharacterInTheEmailIs400ValidationWithTheFixedTextAndThePasswordIsThenNotJudged() throws Exception {
        Ticket ticket = newTicket(app);
        BootstrapDb.State before = BootstrapDb.state();
        for (String escape : CONTROL_ESCAPES) {
            for (String email : List.of("qa" + escape + "x@example.test", escape + "qa-x@example.test", "qa-x@example.test" + escape)) {
                // An invalid password too: the rule "first wrong of email or password" must report only the email.
                RawHttp.Response response = registerRaw(ticket, NAME, email, "short");
                assertThat(response.status()).as("email with " + escape + ": 400, never 500").isEqualTo(400);
                JsonNode errors = json(response).get("fieldErrors");
                assertThat(errors.size()).isEqualTo(1);
                assertThat(errors.get(0).get("field").asText()).isEqualTo("email");
                assertThat(errors.get(0).get("message").asText()).isEqualTo("Enter a valid email.");
                assertThat(BootstrapDb.state()).as("the ticket is not spent").isEqualTo(before);
                assertThat(adminRows()).isZero();
            }
        }
        // Both fields broken at once: fullName plus email, at most two errors.
        RawHttp.Response both = registerRaw(ticket, "A" + CONTROL_ESCAPES.get(0) + "B", "a" + CONTROL_ESCAPES.get(0) + "b@example.test", PASSWORD);
        assertThat(both.status()).isEqualTo(400);
        assertThat(json(both).get("fieldErrors").size()).isEqualTo(2);
        assertThat(BootstrapDb.auditActions(snapshot)).as("a validation error is not audited").isEmpty();
    }

    @Test
    void r3I9ThePasswordIsNotCheckedForControlCharactersBecauseItIsNeverStoredAsText() throws Exception {
        Ticket ticket = newTicket(app);
        RawHttp.Response response = registerRaw(ticket, NAME, uniqueEmail(), "qa-Throwaway\\u0001-Pw-4821");
        assertThat(response.status()).as("only BCrypt ever sees the password (ADR-018 D6)").isEqualTo(201);
    }

    @Test
    void r3I10EveryRegisterProblemCarriesTheOperationsOwnFixedPathAsInstance() throws Exception {
        // 401: no ticket
        RawHttp.Response noTicket = BootstrapSupport.registerRequest(app, "{}", null).send();
        assertThat(noTicket.status()).isEqualTo(401);
        assertThat(json(noTicket).get("instance").asText()).isEqualTo(REGISTER_PATH);
        // 400 bad-request and 400 validation
        Ticket ticket = newTicket(app);
        RawHttp.Response badRequest = BootstrapSupport.registerRequest(app,
                "{\"ticket\":\"" + ticket.value() + "\",\"zz\":\"x\"}", ticket.binding()).send();
        assertThat(badRequest.status()).isEqualTo(400);
        assertThat(json(badRequest).get("instance").asText()).isEqualTo(REGISTER_PATH);
        RawHttp.Response validation = register(app, ticket, NAME, uniqueEmail(), "short");
        assertThat(validation.status()).isEqualTo(400);
        assertThat(json(validation).get("instance").asText()).isEqualTo(REGISTER_PATH);
        // 423
        for (int n = 1; n <= 5; n++) {
            unlock(app, wrongPin(n));
        }
        RawHttp.Response locked = register(app, ticket, NAME, uniqueEmail(), PASSWORD);
        assertThat(locked.status()).isEqualTo(423);
        assertThat(json(locked).get("instance").asText()).isEqualTo(REGISTER_PATH);
    }

    @Test
    void registerWhileLockedNeverCountsATryTakesNoLeaseAndReportsTheRemainingTimeFromTheLockItself() throws Exception {
        Ticket ticket = newTicket(app);
        for (int n = 1; n <= 5; n++) {
            unlock(app, wrongPin(n));
        }
        BootstrapDb.lockFor(Duration.ofSeconds(90));
        BootstrapDb.State before = BootstrapDb.state();

        for (int i = 0; i < 3; i++) {
            RawHttp.Response locked = register(app, ticket, NAME, uniqueEmail(), PASSWORD);
            assertThat(locked.status()).isEqualTo(423);
            assertThat(json(locked).get("retryAfterSeconds").asLong()).as("from the locked_until the register function returned")
                    .isBetween(80L, 90L);
        }

        assertThat(BootstrapDb.state()).as("register never calls begin: no count, no lease").isEqualTo(before);
        assertThat(BootstrapDb.state().failures()).isEqualTo(5);
        assertThat(BootstrapDb.state().hasLease()).isFalse();
    }

    @Test
    void aRegisterThatWaitsMoreThanTenSecondsForTheRowGets429WithRetryAfterFiveAndTheSameTicketStillWorksAfterwards() throws Exception {
        Ticket ticket = newTicket(app);
        BootstrapDb.State before = BootstrapDb.state();
        long waited;
        RawHttp.Response response;
        try (Connection holder = SampleAdminDb.migrator()) {
            holder.setAutoCommit(false);
            try (java.sql.Statement lock = holder.createStatement()) {
                lock.execute("select 1 from " + BootstrapDb.TABLE + " where id = 1 for update");
            }
            long started = System.nanoTime();
            response = register(app, ticket, NAME, uniqueEmail(), PASSWORD);
            waited = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            holder.rollback();
        }

        assertThat(response.status()).as("the lock wait (10 s) gives 429").isEqualTo(429);
        assertThat(waited).isBetween(8L, 25L);
        assertThat(response.header("Retry-After")).isEqualTo("5");
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "rate-limit");
        assertThat(body.get("retryAfterSeconds").asInt()).isEqualTo(5);
        assertThat(body.get("instance").asText()).as("R3-I10").isEqualTo(REGISTER_PATH);
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        assertThat(adminRows()).as("nothing was created").isZero();
        assertThat(BootstrapDb.state()).as("the ticket was not spent").isEqualTo(before);
        assertThat(register(app, ticket, NAME, uniqueEmail(), PASSWORD).status()).as("the same ticket, sent again").isEqualTo(201);
    }
}
