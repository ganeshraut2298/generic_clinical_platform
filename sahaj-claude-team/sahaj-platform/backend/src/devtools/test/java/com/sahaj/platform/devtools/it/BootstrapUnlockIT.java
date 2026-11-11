package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.PIN;
import static com.sahaj.platform.devtools.it.BootstrapSupport.json;
import static com.sahaj.platform.devtools.it.BootstrapSupport.unlock;
import static com.sahaj.platform.devtools.it.BootstrapSupport.wrongPin;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SAH-42 stories 1 to 4, the unlock call, on the whole application with the real bootstrap functions in {@code sahaj_test}
 * (ADR-018 Revision 3: count first, judge after; R3-I1 to R3-I6, R3-I10, R3-I13 below),
 * (ADR-018 D2 to D5, docs/database/SAH-42.md S6 to S13, S20 to S23, T-numbers of docs/test-plans/SAH-42.md).
 *
 * <p>PRECONDITIONS: the human prerequisites of DEC-016 and an empty {@code platform_admin} (see {@link BootstrapHttpBase}). Not
 * run today: they are missing, so the class is skipped with that message.
 *
 * <p>Time is never read or waited for: a lock or ticket "30 minutes later" is made by moving the stored time as the table owner
 * ({@link BootstrapDb#lockFor}, {@link BootstrapDb#lockEndedAgo}, {@link BootstrapDb#ticketEndsIn}), which is what the database
 * design prescribes (the functions read only {@code clock_timestamp()}). Only the 10-second lock wait of S23 really waits.
 * Throwaway PINs only; the PIN of the tests is {@link BootstrapSupport#PIN}.
 */
class BootstrapUnlockIT extends BootstrapHttpBase {

    private static final String PROBLEM = "https://sahaj.in/problems/";
    private static final Pattern ISO_UTC = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

    // ---- helpers -----------------------------------------------------------------------------------------

    private void assertWrongPin(RawHttp.Response response, int triesLeft) throws IOException {
        assertThat(response.status()).as("wrong PIN answer").isEqualTo(401);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "bootstrap-pin-invalid");
        assertThat(body.get("title").asText()).isEqualTo("That PIN is not right.");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("triesLeft").asInt()).as("tries left").isEqualTo(triesLeft);
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        assertThat(BootstrapSupport.setCookies(response)).as("no cookie on a wrong PIN").isEmpty();
        assertThat(response.bodyText()).as("no ticket in a wrong-PIN answer").doesNotContain("\"ticket\"");
    }

    private void assertLocked(RawHttp.Response response, long minSeconds, long maxSeconds) throws IOException {
        assertThat(response.status()).as("locked answer").isEqualTo(423);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "bootstrap-locked");
        assertThat(body.get("title").asText()).isEqualTo("Registration is locked.");
        assertThat(body.get("status").asInt()).isEqualTo(423);
        long seconds = body.get("retryAfterSeconds").asLong();
        assertThat(seconds).as("retryAfterSeconds").isBetween(minSeconds, maxSeconds);
        assertThat(response.header("Retry-After")).as("Retry-After equals the body").isEqualTo(Long.toString(seconds));
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        assertThat(BootstrapSupport.setCookies(response)).as("no cookie while locked").isEmpty();
        assertThat(response.bodyText()).as("no ticket while locked").doesNotContain("\"ticket\"");
    }

    private void fiveWrongPins() throws IOException {
        for (int n = 1; n <= 5; n++) {
            unlock(app, wrongPin(n));
        }
    }

    // ---- S6, story 2: the correct PIN ---------------------------------------------------------------------

    @Test
    void aCorrectPinAnswers200WithATicketAndTheBindingCookieAndAuditsNothing() throws Exception {
        RawHttp.Response response = unlock(app, PIN);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        JsonNode body = json(response);
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("ticket", "expiresInSeconds");
        assertThat(body.get("ticket").asText()).matches(BootstrapSupport.TICKET_SHAPE);
        assertThat(body.get("expiresInSeconds").asInt()).isEqualTo(300);
        assertThat(BootstrapSupport.bindingOf(response)).as("binding value").matches(BootstrapSupport.TICKET_SHAPE);
        assertThat(BootstrapSupport.bindingOf(response)).as("the two halves differ").isNotEqualTo(body.get("ticket").asText());
        assertThat(BootstrapDb.auditActions(snapshot)).as("a correct unlock is not audited (OQ-42-9)").isEmpty();
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).isZero();
        assertThat(state.lockedUntil()).isNull();
    }

    @Test
    void theCookieHasTheFlagsOfAdr018D5AndIsTheOnlyCookie() throws Exception {
        RawHttp.Response response = unlock(app, PIN);
        List<String> cookies = BootstrapSupport.setCookies(response);
        assertThat(cookies).as("exactly one Set-Cookie").hasSize(1);
        String cookie = cookies.get(0);
        assertThat(cookie).startsWith(BootstrapSupport.COOKIE + "=");
        assertThat(cookie).contains("; HttpOnly").contains("; SameSite=Strict").contains("; Path=/api/v1/admin/bootstrap")
                .contains("; Max-Age=300");
        // The dev profile sets refresh-cookie-secure=false (plain http://localhost), so no Secure here (checked the other way below).
        assertThat(cookie).doesNotContain("Secure").doesNotContain("Domain=");
    }

    @Test
    void theCookieIsSecureWhenTheRefreshCookieSettingSaysSo() throws Exception {
        try (AppRun secure = appBuilder().option("sahaj.auth.refresh-cookie-secure", "true").start()) {
            String cookie = BootstrapSupport.bindingCookieLine(unlock(secure, PIN));
            assertThat(cookie).contains("; Secure").contains("; HttpOnly").contains("; SameSite=Strict");
        }
    }

    @Test
    void bothHalvesOfTheTicketAreStoredOnlyAsKeyedHashesAndTheClockStartsAtTheUnlock() throws Exception {
        Instant before = BootstrapDb.dbNow();
        RawHttp.Response response = unlock(app, PIN);
        Instant after = BootstrapDb.dbNow();
        String ticket = json(response).get("ticket").asText();
        String binding = BootstrapSupport.bindingOf(response);

        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.ticketHash()).as("HMAC-SHA256 hex of the ticket").matches("[0-9a-f]{64}")
                .isEqualTo(BootstrapSupport.atRestHash(ticket));
        assertThat(state.bindingHash()).matches("[0-9a-f]{64}").isEqualTo(BootstrapSupport.atRestHash(binding));
        assertThat(state.ticketHash()).isNotEqualTo(state.bindingHash());
        assertThat(state.toString()).as("the plain values are nowhere in the row").doesNotContain(ticket).doesNotContain(binding);
        assertThat(state.ticketExpiresAt()).as("five minutes from the unlock, on the database clock")
                .isBetween(before.plusSeconds(300).minusSeconds(1), after.plusSeconds(300).plusSeconds(1));
    }

    @Test
    void aCorrectPinWithAStrangeButAllowedTypeOfPinNeedsNoCodeChangeBecauseThereIsNoSixDigitRule() throws Exception {
        // BR5: the PIN of these tests is 11 characters with letters and hyphens; the hash was made from exactly that text.
        assertThat(PIN).doesNotMatch("[0-9]{6}");
        assertThat(unlock(app, PIN).status()).isEqualTo(200);
    }

    @Test
    void aHashMadeByTheRealToolUnlocksWithItsPinAndOnlyWithThatPinAndNeedsNoChangeToTheServerForALongerPin() throws Exception {
        // Story 2: "the human changes the hash in .env to the hash of a longer PIN and restarts: it unlocks with no code change".
        String longerPin = "a-much-longer-throwaway-pin-for-the-tool-test";
        String hash = BootstrapSupport.hashFromTool(longerPin, 12);
        try (AppRun other = BootstrapSupport.builder().environmentVariable(BootstrapSupport.HASH_ENV, hash).start()) {
            assertThat(unlock(other, PIN).status()).as("the old test PIN is now wrong").isEqualTo(401);
            assertThat(unlock(other, longerPin).status()).as("the PIN the tool hashed").isEqualTo(200);
        }
    }

    @Test
    void aBearerTokenAndOtherCookiesAreIgnored() throws Exception {
        RawHttp.Response response = BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(PIN))
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")
                .header("Cookie", "sahaj_admin_rt=junk; sahaj_rt=junk; sahaj_admin_bootstrap=junk").send();
        assertThat(response.status()).as("same answer as without any credential").isEqualTo(200);
    }

    // ---- S7, S8, story 2 and 3: wrong PINs and the lock ----------------------------------------------------

    @Test
    void wrongPinsOneToFourAnswer401WithTheTriesLeftAndAreCountedAndAudited() throws Exception {
        for (int n = 1; n <= 4; n++) {
            assertWrongPin(unlock(app, wrongPin(n)), 5 - n);
            assertThat(BootstrapDb.state().failures()).as("count after wrong PIN " + n).isEqualTo(n);
        }
        List<String> rows = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED");
        assertThat(rows).as("one audit row per wrong PIN").hasSize(4);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).isZero();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = json(rows.get(i));
            assertThat(row.get("kind").asText()).isEqualTo("AUTH");
            assertThat(row.get("outcome").asText()).isEqualTo("REFUSED");
            assertThat(row.get("refusal_code").asText()).isEqualTo("pin-invalid");
            assertThat(row.get("summary").asText()).isEqualTo("Bootstrap registration: wrong PIN (" + (i + 1) + " of 5)");
            assertThat(row.get("ip").asText()).as("caller address").isEqualTo("127.0.0.1");
            assertThat(row.get("request_id").asText()).as("request id").isNotBlank();
            assertThat(row.get("admin_id").isNull()).isTrue();
            assertThat(row.get("auth_step").isNull()).isTrue();
            assertThat(row.get("target_tenant_id").isNull()).isTrue();
        }
    }

    @Test
    void theAnswerToAWrongPinIsTheSameWhateverWasTypedAndOverlongInputIsJustWrong() throws Exception {
        List<String> typed = List.of("0", "12345", "1234567", "abcdef", "  ", "a b c", PIN.toUpperCase(), " " + PIN, PIN + " ",
                "x".repeat(64), "x".repeat(65), "x".repeat(2000), "€".repeat(25) /* 75 bytes */,
                "pässwörd", "'; drop table x; --");
        String shape = null;
        int expectedCount = 0;
        for (String pin : typed) {
            if (pin.isBlank()) {
                continue; // a blank PIN is a form error and is not counted (see BootstrapBodiesIT)
            }
            if (expectedCount == 4) {
                BootstrapDb.setFailures(0);
                expectedCount = 0;
            }
            RawHttp.Response response = unlock(app, pin);
            expectedCount++;
            assertThat(response.status()).as("typed text of length " + pin.length()).isEqualTo(401);
            JsonNode body = json(response);
            String thisShape = body.get("type").asText() + "|" + body.get("title").asText() + "|" + body.get("status").asInt()
                    + "|" + response.header("Content-Type") + "|" + response.header("Cache-Control");
            if (shape == null) {
                shape = thisShape;
            }
            assertThat(thisShape).as("same type, title and shape for every wrong PIN").isEqualTo(shape);
            if (pin.length() >= 6) {
                assertThat(response.bodyText()).as("the typed text is never echoed").doesNotContain(pin);
            }
            assertThat(BootstrapDb.state().failures()).as("each wrong PIN is counted once").isEqualTo(expectedCount);
        }
    }

    @Test
    void theFifthWrongPinLocksForSixtyMinutesAnswers423With3600AndAuditsTwoRows() throws Exception {
        for (int n = 1; n <= 4; n++) {
            assertWrongPin(unlock(app, wrongPin(n)), 5 - n);
        }
        Instant before = BootstrapDb.dbNow();

        RawHttp.Response fifth = unlock(app, wrongPin(5));

        Instant after = BootstrapDb.dbNow();
        assertLocked(fifth, 3600, 3600);
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).isEqualTo(5);
        assertThat(state.lockedUntil()).as("sixty minutes on the database clock")
                .isBetween(before.plusSeconds(3600).minusSeconds(1), after.plusSeconds(3600).plusSeconds(1));
        assertThat(state.hasTicket()).isFalse();
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).as("the 5th also writes its failed row").isEqualTo(5);
        List<String> locked = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCKED");
        assertThat(locked).hasSize(1);
        JsonNode row = json(locked.get(0));
        assertThat(row.get("outcome").asText()).isEqualTo("REFUSED");
        assertThat(row.get("refusal_code").asText()).isEqualTo("locked");
        assertThat(row.get("summary").asText()).isEqualTo("Bootstrap registration locked for 60 minutes");
        String lockedUntil = row.get("after_json").get("lockedUntil").asText();
        assertThat(lockedUntil).as("UTC, ends in Z").matches(ISO_UTC);
        assertThat(Duration.between(Instant.parse(lockedUntil), state.lockedUntil()).abs().toSeconds())
                .as("the audit end time is the stored end time").isLessThanOrEqualTo(1);
    }

    @Test
    void whileLockedEvenTheCorrectPinIsRefusedWithoutACompareACountOrAnAuditRow() throws Exception {
        fiveWrongPins();
        int auditBefore = BootstrapDb.auditActions(snapshot).size();
        assertThat(auditBefore).isEqualTo(6);

        assertLocked(unlock(app, PIN), 3500, 3600);
        assertLocked(unlock(app, wrongPin(9)), 3500, 3600);
        assertLocked(unlock(app, "x".repeat(70)), 3500, 3600);

        assertThat(BootstrapDb.state().failures()).as("count unchanged").isEqualTo(5);
        assertThat(BootstrapDb.state().hasTicket()).isFalse();
        assertThat(BootstrapDb.auditActions(snapshot)).as("refusals during a lock are not audited").hasSize(auditBefore);
    }

    @Test
    void theRemainingTimeCountsDownOnTheDatabaseClockAndTheLockEndsByItself() throws Exception {
        fiveWrongPins();

        BootstrapDb.lockFor(Duration.ofMinutes(30));
        assertLocked(unlock(app, PIN), 29 * 60 + 55, 30 * 60);

        BootstrapDb.lockFor(Duration.ofSeconds(90));
        assertLocked(unlock(app, PIN), 85, 90);

        BootstrapDb.lockFor(Duration.ofSeconds(1));
        RawHttp.Response lastSecond = unlock(app, PIN);
        assertThat(lastSecond.status()).as("one second left is still locked or just ended, never a 5xx").isIn(200, 423);
    }

    @Test
    void whenTheLockHasEndedTheNextWrongPinCountsAsNumberOneOfFive() throws Exception {
        fiveWrongPins();
        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));

        assertWrongPin(unlock(app, wrongPin(7)), 4);

        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).as("the count restarted at zero, then 1").isEqualTo(1);
        assertThat(state.lockedUntil()).isNull();
    }

    @Test
    void whenTheLockHasEndedTheCorrectPinUnlocksAndTheCountIsZero() throws Exception {
        fiveWrongPins();
        BootstrapDb.lockEndedAgo(Duration.ofMinutes(5));

        assertThat(unlock(app, PIN).status()).isEqualTo(200);

        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).isZero();
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.hasTicket()).isTrue();
    }

    @Test
    void fourWrongPinsThenTheCorrectOneUnlocksAndFourMoreWrongOnesDoNotLock() throws Exception {
        for (int n = 1; n <= 4; n++) {
            assertWrongPin(unlock(app, wrongPin(n)), 5 - n);
        }
        assertThat(unlock(app, PIN).status()).as("the 5th try is the correct PIN and is judged before any lock").isEqualTo(200);
        assertThat(BootstrapDb.state().failures()).as("a correct PIN resets the count (BR7c)").isZero();
        assertThat(BootstrapDb.state().lockedUntil()).as("R3-I6: no lock").isNull();
        assertThat(BootstrapDb.state().hasLease()).as("R3-I6: no lease").isFalse();

        for (int n = 1; n <= 4; n++) {
            assertWrongPin(unlock(app, wrongPin(20 + n)), 5 - n);
        }
        assertThat(BootstrapDb.state().lockedUntil()).as("four wrong PINs after a reset do not lock").isNull();
    }

    @Test
    void theLockIsGlobalWrongPinsFromTwoBrowsersOrAddressesAddUp() throws Exception {
        for (int n = 1; n <= 3; n++) {
            BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(wrongPin(n)))
                    .header("User-Agent", "browser-one").header("X-Forwarded-For", "127.0.0.1").send();
        }
        RawHttp.Response fourth = BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(wrongPin(4)))
                .header("User-Agent", "browser-two").header("X-Forwarded-For", "::1").send();
        assertWrongPin(fourth, 1);
        RawHttp.Response fifth = BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(wrongPin(5)))
                .header("User-Agent", "browser-two").header("X-Forwarded-For", "::1").send();
        assertLocked(fifth, 3600, 3600);
        assertLocked(BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(PIN)).header("User-Agent", "browser-one").send(),
                3500, 3600);
    }

    @Test
    void aCountOfThreeAndARestartThenTwoMoreWrongPinsLockBecauseTheCountSurvivesTheRestart() throws Exception {
        for (int n = 1; n <= 3; n++) {
            unlock(app, wrongPin(n));
        }
        try (AppRun restarted = appBuilder().start()) {
            assertThat(BootstrapDb.state().failures()).isEqualTo(3);
            assertWrongPin(unlock(restarted, wrongPin(4)), 1);
            assertLocked(unlock(restarted, wrongPin(5)), 3600, 3600);
        }
    }

    @Test
    void aLockSurvivesARestartAndTheCorrectPinIsStillRefusedUntilItEnds() throws Exception {
        fiveWrongPins();
        BootstrapDb.State before = BootstrapDb.state();
        try (AppRun restarted = appBuilder().start()) {
            assertLocked(unlock(restarted, PIN), 3500, 3600);
            assertThat(BootstrapDb.state()).as("the start did not change the lock").isEqualTo(before);
            BootstrapDb.lockEndedAgo(Duration.ofSeconds(2));
            assertThat(unlock(restarted, PIN).status()).as("after the lock ends the PIN is checked normally").isEqualTo(200);
        }
    }

    // ---- S22 / R3-I5: serialisation by the lease ---------------------------------------------------------------

    @Test
    void r3I5TenParallelWrongPinsAreSerialisedFourAre401TheFifthLocksAndTheRestAreRefusedWithoutACount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return unlock(app, wrongPin(100 + n)).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(120, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("every answer is a normal refusal, never a 200 or a 5xx").allMatch(s -> s == 401 || s == 423 || s == 429);
            assertThat(statuses.stream().filter(s -> s == 401).count()).as("statuses " + statuses).isEqualTo(4);
            assertThat(statuses.stream().filter(s -> s == 423 || s == 429).count()).as("statuses " + statuses).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
        assertThat(BootstrapDb.state().failures()).as("the count never passes 5, and exactly 5 tries were counted").isEqualTo(5);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).isEqualTo(5);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).isEqualTo(1);
        assertThat(BootstrapDb.state().hasLease()).as("every judge gave its lease back").isFalse();
    }

    // ---- ADR-018 D4: the count is committed BEFORE the compare; no transaction is held during it -----------------------

    @Test
    void theClaimIsCommittedAndVisibleWhileTheSlowCompareStillRunsAndNoRowLockOrTransactionIsHeldDuringIt() throws Exception {
        // A BCrypt cost-16 hash (the slowest the server accepts, B4) makes the compare last seconds, so the state can be read
        // from a second connection while the request is still in flight. First-run watch items 3 and "no connection leaks
        // between T1, the compare and T2".
        String slowPin = "a-slow-throwaway-pin-for-the-lease-test";
        String slowHash = BootstrapSupport.hashFromTool(slowPin, 16);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (AppRun slow = BootstrapSupport.builder().environmentVariable(BootstrapSupport.HASH_ENV, slowHash).start()) {
            Instant before = BootstrapDb.dbNow();
            Future<RawHttp.Response> inFlight = pool.submit(() -> unlock(slow, wrongPin(1)));
            BootstrapDb.State claimed = null;
            long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < give && !inFlight.isDone()) {
                BootstrapDb.State seen = BootstrapDb.state();
                if (seen.failures() == 1 && seen.hasLease()) {
                    claimed = seen;
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(claimed).as("the claim (count 1 and a lease) was visible to a second connection while the compare ran").isNotNull();
            assertThat(inFlight.isDone()).as("the answer is not there yet: the compare is running outside any transaction").isFalse();
            assertThat(claimed.tryExpiresAt()).as("the lease is 30 seconds from the claim")
                    .isBetween(before.plusSeconds(28), BootstrapDb.dbNow().plusSeconds(31));
            // No row lock is held: a second connection may lock the row at once (NOWAIT fails with 55P03 if T1 were still open).
            try (java.sql.Connection probe = SampleAdminDb.migrator(); java.sql.Statement s = probe.createStatement()) {
                probe.setAutoCommit(false);
                s.execute("select 1 from " + BootstrapDb.TABLE + " where id = 1 for update nowait");
                probe.rollback();
            }
            // A second try meanwhile is BUSY, not counted (it polls up to 10 s, so only look at the state, not at the answer).
            Future<RawHttp.Response> second = pool.submit(() -> unlock(slow, wrongPin(2)));
            Thread.sleep(1500);
            assertThat(BootstrapDb.state().failures()).as("the second try waits for the lease and is not counted").isEqualTo(1);

            RawHttp.Response first = inFlight.get(60, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(401);
            assertThat(json(first).get("triesLeft").asInt()).isEqualTo(4);
            RawHttp.Response answered = second.get(60, TimeUnit.SECONDS);
            assertThat(answered.status()).as("the waiting try got its turn or gave up with a 429, never a 5xx").isIn(401, 429);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fifteenWrongAndCorrectPairsAndFifteenBlankPinsLeakNoConnectionAndNeverStall() throws Exception {
        // 15 pairs (wrong then correct) = 30 requests, 15 blank PINs, 1 final correct unlock: 46 requests in all.
        // Each wrong or correct unlock is claim (T1), compare, judge (T2) = two pooled transactions. A connection kept
        // across the compare, or left open by a path, would exhaust the pool (default 10) within a dozen cycles and the
        // call would stall for the pool timeout and fail.
        for (int cycle = 1; cycle <= 15; cycle++) {
            long started = System.nanoTime();
            assertThat(unlock(app, wrongPin(cycle)).status()).as("wrong PIN, cycle " + cycle).isEqualTo(401);
            assertThat(unlock(app, PIN).status()).as("correct PIN, cycle " + cycle).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toSeconds()).as("cycle " + cycle + " did not stall").isLessThan(10);
            assertThat(BootstrapDb.state().failures()).as("a correct PIN resets the count").isZero();
            assertThat(BootstrapDb.state().hasLease()).as("no lease is left behind").isFalse();
        }
        // Blank PINs are the read-only peek (one more transaction, never counted).
        for (int i = 0; i < 15; i++) {
            assertThat(unlock(app, "").status()).isEqualTo(400);
        }
        assertThat(unlock(app, PIN).status()).as("the pool still has connections").isEqualTo(200);
    }

    // ---- R3-I1 .. R3-I3: forced failures, the limit fails closed -------------------------------------------------------------

    @Test
    void r3I1ASetOfWrongPinsWhoseJudgeFailsIsStillCountedEachAnswersAnEmpty500AndTheFifthLocksSoTheCorrectPinIsThenRefused() throws Exception {
        BootstrapDb.failAuditInsertsOf("ADMIN_BOOTSTRAP_UNLOCK_FAILED");
        try {
            for (int n = 1; n <= 5; n++) {
                BootstrapDb.expireLease(); // 30 s of lease are moved into the past (no-op before the first try)
                RawHttp.Response response = unlock(app, wrongPin(n));
                assertThat(response.status()).as("wrong PIN " + n + " with a failing judge").isEqualTo(500);
                assertThat(response.bodyText()).as("a bodyless 500: nothing about the PIN, no triesLeft").isEmpty();
                assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
                assertThat(BootstrapDb.state().failures()).as("the try was counted although the judge failed").isEqualTo(n);
            }
            BootstrapDb.State state = BootstrapDb.state();
            assertThat(state.lockedUntil()).as("the lock stands after the 5th counted try").isNotNull();
            assertThat(state.hasTicket()).isFalse();

            RawHttp.Response correct = unlock(app, PIN);

            assertThat(correct.status()).as("the correct PIN is refused by the lock, not compared, never a 200").isEqualTo(423);
            assertThat(BootstrapSupport.setCookies(correct)).isEmpty();
            assertThat(BootstrapDb.state().failures()).as("and not counted").isEqualTo(5);
        } finally {
            BootstrapDb.dropForcedFailures();
        }
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).as("the four lost tries whose lease ran out").isEqualTo(4);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).as("no judge committed").isZero();
    }

    @Test
    void r3I2ThreeFailedJudgesThenTheTriggerIsDroppedAndTheCorrectPinUnlocksWithUnjudgedRowsForTheLostTries() throws Exception {
        BootstrapDb.failAuditInsertsOf("ADMIN_BOOTSTRAP_UNLOCK_FAILED");
        try {
            for (int n = 1; n <= 3; n++) {
                BootstrapDb.expireLease();
                assertThat(unlock(app, wrongPin(n)).status()).as("wrong PIN " + n).isEqualTo(500);
            }
        } finally {
            BootstrapDb.dropForcedFailures();
        }
        BootstrapDb.expireLease();

        RawHttp.Response response = unlock(app, PIN);

        assertThat(response.status()).as("the 4th counted try is the correct PIN and is judged normally").isEqualTo(200);
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).as("a correct PIN resets the count").isZero();
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.hasLease()).isFalse();
        List<String> unjudged = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED");
        assertThat(unjudged).as("one row per lost try, written by the begin that found its lease run out").hasSize(3);
        for (String row : unjudged) {
            assertThat(row).contains("\"refusal_code\":\"try-unjudged\"").contains("\"ip\":null").contains("\"request_id\":null");
        }
    }

    @Test
    void r3I3TheCorrectPinWhoseJudgeFailsIsCountedFiveTimesAnEmpty500EachAndThenTheLockHoldsNoTicketIsEverIssued() throws Exception {
        BootstrapDb.failTicketWrites();
        try {
            for (int n = 1; n <= 5; n++) {
                BootstrapDb.expireLease();
                RawHttp.Response response = unlock(app, PIN);
                assertThat(response.status()).as("correct PIN " + n + " with a failing judge").isEqualTo(500);
                assertThat(response.bodyText()).as("a bodyless 500: no ticket").isEmpty();
                assertThat(BootstrapSupport.setCookies(response)).as("no binding cookie").isEmpty();
                assertThat(BootstrapDb.state().failures()).as("counted although the judge failed").isEqualTo(n);
                assertThat(BootstrapDb.state().hasTicket()).isFalse();
            }
            assertThat(BootstrapDb.state().lockedUntil()).as("the 5th counted try set the lock (the fail-closed price)").isNotNull();
            assertThat(unlock(app, PIN).status()).as("6th try").isEqualTo(423);
        } finally {
            BootstrapDb.dropForcedFailures();
        }
    }

    // ---- R3-I4: an unjudged try ---------------------------------------------------------------------------------------------

    @Test
    void r3I4AClaimWithoutAJudgeBlocksTheNextUnlockForTenSecondsThen429AndAfterTheLeaseRunsOutTheNextTryCountsAndOneUnjudgedRowIsWritten() throws Exception {
        BootstrapSql.claimAndCommit(UUID.randomUUID()); // as the app does, then the request "dies": nothing judges it
        assertThat(BootstrapDb.state().failures()).isEqualTo(1);

        long started = System.nanoTime();
        RawHttp.Response waiting = unlock(app, wrongPin(1));
        long waited = Duration.ofNanos(System.nanoTime() - started).toSeconds();

        assertThat(waiting.status()).as("BUSY for 10 seconds gives the 429").isEqualTo(429);
        assertThat(waited).as("it waited about ten seconds, polling").isBetween(9L, 25L);
        assertThat(waiting.header("Retry-After")).isEqualTo("5");
        assertThat(json(waiting).get("instance").asText()).as("R3-I10").isEqualTo(UNLOCK_PATH);
        assertThat(BootstrapDb.state().failures()).as("the waiting try was not counted").isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).isZero();

        BootstrapDb.expireLease();
        RawHttp.Response next = unlock(app, wrongPin(2));

        assertWrongPin(next, 3);
        assertThat(BootstrapDb.state().failures()).as("the lost try stayed counted, this one is the second").isEqualTo(2);
        List<String> unjudged = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED");
        assertThat(unjudged).hasSize(1);
        assertThat(unjudged.get(0)).contains("\"ip\":null").contains("\"request_id\":null");
    }

    // ---- R3-I13 (HTTP): a lost 5th try ---------------------------------------------------------------------------------------

    @Test
    void r3I13ALostFifthTryWithItsLeaseRunOutAnswers423OnTheFirstUnlockClearsTheLeaseWritesOneUnjudgedRowAndNeverAgain() throws Exception {
        for (int n = 1; n <= 4; n++) {
            assertWrongPin(unlock(app, wrongPin(n)), 5 - n);
        }
        BootstrapSql.claimAndCommit(UUID.randomUUID()); // the 5th, counted and locked, never judged
        BootstrapDb.expireLease();
        BootstrapDb.State before = BootstrapDb.state();
        assertThat(before.failures()).isEqualTo(5);
        assertThat(before.lockedUntil()).isNotNull();

        assertLocked(unlock(app, PIN), 3500, 3600);

        BootstrapDb.State after = BootstrapDb.state();
        assertThat(after.hasLease()).as("the first begin cleared the run-out lease").isFalse();
        assertThat(after.failures()).isEqualTo(5);
        assertThat(after.lockedUntil()).as("lock unchanged").isEqualTo(before.lockedUntil());
        List<String> unjudged = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED");
        assertThat(unjudged).hasSize(1);
        assertThat(unjudged.get(0)).contains("\"ip\":null").contains("\"request_id\":null");
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).as("no LOCKED row for a lost 5th try").isZero();

        assertLocked(unlock(app, PIN), 3500, 3600);

        assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).as("no second row").hasSize(1);
        assertThat(BootstrapDb.state()).isEqualTo(after);
    }

    // ---- slow compare: five parallel tries (count first, so the others are BUSY, not queued on a row lock) ---------------

    @Test
    void fiveParallelWrongPinsWithASlowHashGiveOneCountedWhileTheRestWaitAndAnyThatWaitTooLongGet429WithoutACount() throws Exception {
        String slowPin = "a-slow-throwaway-pin-for-the-parallel-test";
        String slowHash = BootstrapSupport.hashFromTool(slowPin, 16);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try (AppRun slow = BootstrapSupport.builder().environmentVariable(BootstrapSupport.HASH_ENV, slowHash).start()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<RawHttp.Response>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return unlock(slow, wrongPin(200 + n));
                }));
            }
            go.countDown();
            // While the first compare runs (seconds), only that one try is counted: the other four are BUSY.
            long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < give && BootstrapDb.state().failures() == 0) {
                Thread.sleep(25);
            }
            Thread.sleep(700);
            BootstrapDb.State midway = BootstrapDb.state();
            if (results.stream().noneMatch(Future::isDone)) {
                assertThat(midway.failures()).as("one COUNTED while its compare runs, the other four BUSY").isEqualTo(1);
            }
            List<RawHttp.Response> answers = new ArrayList<>();
            for (Future<RawHttp.Response> result : results) {
                answers.add(result.get(120, TimeUnit.SECONDS));
            }
            long refused429 = answers.stream().filter(a -> a.status() == 429).count();
            long counted = answers.stream().filter(a -> a.status() == 401 || a.status() == 423).count();
            assertThat(answers).as("statuses").allMatch(a -> a.status() == 401 || a.status() == 423 || a.status() == 429);
            for (RawHttp.Response answer : answers) {
                if (answer.status() == 429) {
                    assertThat(answer.header("Retry-After")).isEqualTo("5");
                }
            }
            assertThat(BootstrapDb.state().failures()).as("exactly the answered tries were counted, a 429 never").isEqualTo((int) counted);
            assertThat(counted + refused429).isEqualTo(5);
            assertThat(BootstrapDb.state().hasLease()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- R3-I10: the problem instance is a fixed path -------------------------------------------------------------------

    private static final String UNLOCK_PATH = "/api/v1/admin/bootstrap/unlock";

    @Test
    void r3I10EveryProblemOfTheUnlockCallCarriesTheOperationsOwnFixedPathAsInstance() throws Exception {
        // 400 (blank PIN), 401 (wrong PIN), 423 (locked); 429 is checked in r3I4 and in the lock-wait test below.
        RawHttp.Response blank = unlock(app, "");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(json(blank).get("instance").asText()).isEqualTo(UNLOCK_PATH);
        RawHttp.Response unreadable = BootstrapSupport.unlockRequest(app, "[").send();
        assertThat(unreadable.status()).isEqualTo(400);
        assertThat(json(unreadable).get("instance").asText()).isEqualTo(UNLOCK_PATH);
        RawHttp.Response wrong = unlock(app, wrongPin(1));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(json(wrong).get("instance").asText()).isEqualTo(UNLOCK_PATH);
        for (int n = 2; n <= 5; n++) {
            unlock(app, wrongPin(n));
        }
        RawHttp.Response locked = unlock(app, PIN);
        assertThat(locked.status()).isEqualTo(423);
        assertThat(json(locked).get("instance").asText()).isEqualTo(UNLOCK_PATH);
    }

    @Test
    void r3I10APathThatTheRequestFirewallOrTheGateRefusesNeverEchoesWhatWasTypedAndAnyProblemItGetsHasTheFixedInstance() throws Exception {
        // ADR-018 D2 "instance": a path parameter or another percent-encoding must never come back to the caller.
        for (String odd : List.of(UNLOCK_PATH + ";x=y", UNLOCK_PATH + ";jsessionid=abc", "/api/v1/admin/bootstrap/unl%6Fck", UNLOCK_PATH + "%3Bx=y")) {
            RawHttp.Response response = app.post(odd).json(BootstrapSupport.pinBody(wrongPin(1))).send();
            assertThat(response.status()).as(odd + " is refused or answered normally").isBetween(400, 404);
            assertThat(response.bodyText()).as("nothing typed in the path comes back").doesNotContain("x=y").doesNotContain("jsessionid")
                    .doesNotContain("%6F").doesNotContain("%3B");
            if (!response.bodyText().isEmpty() && response.header("Content-Type") != null
                    && response.header("Content-Type").startsWith("application/problem+json")) {
                assertThat(json(response).get("instance").asText()).isEqualTo(UNLOCK_PATH);
            }
        }
        assertThat(BootstrapDb.state().failures()).as("at most the requests that reached the service were counted").isLessThanOrEqualTo(4);
    }

    // ---- S23: lock wait --------------------------------------------------------------------------------------

    @Test
    void aCallThatWaitsMoreThanTenSecondsForItsTurnGets429WithRetryAfterFiveAndNothingIsCounted() throws Exception {
        try (java.sql.Connection holder = SampleAdminDb.migrator()) {
            holder.setAutoCommit(false);
            try (java.sql.Statement lock = holder.createStatement()) {
                lock.execute("select 1 from " + BootstrapDb.TABLE + " where id = 1 for update");
            }
            long started = System.nanoTime();
            RawHttp.Response response = unlock(app, wrongPin(1));
            long waitedSeconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            holder.rollback();

            assertThat(response.status()).isEqualTo(429);
            assertThat(waitedSeconds).as("it waited for the lock timeout of 10 seconds").isBetween(8L, 25L);
            assertThat(response.header("Retry-After")).isEqualTo("5");
            JsonNode body = json(response);
            assertThat(body.get("type").asText()).isEqualTo(PROBLEM + "rate-limit");
            assertThat(body.get("instance").asText()).as("R3-I10: the fixed path").isEqualTo(UNLOCK_PATH);
            assertThat(body.get("retryAfterSeconds").asInt()).isEqualTo(5);
            assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        }
        assertThat(BootstrapDb.state().failures()).as("nothing was counted").isZero();
        assertThat(BootstrapDb.auditActions(snapshot)).as("nothing was audited").isEmpty();
    }

    // ---- K7 / story 1: closed once an admin exists --------------------------------------------------------

    @Test
    void onceAnAdminExistsTheUnlockIsTheEmpty404WithTheCorrectPinAWrongPinOrALock() throws Exception {
        String reference = BootstrapSupport.referenceNotFound(app);
        for (boolean active : new boolean[] {true, false}) {
            SampleAdminDb.Snapshot own = SampleAdminDb.snapshot();
            try {
                SampleAdminDb.insertAdmin(uniqueEmail(), active);

                assertThat(unlock(app, PIN).assertDevNotFound().fingerprint()).as("correct PIN, active=" + active).isEqualTo(reference);
                assertThat(unlock(app, wrongPin(1)).assertDevNotFound().fingerprint()).as("wrong PIN").isEqualTo(reference);
                assertThat(BootstrapSupport.unlockRequest(app, "not json").send().assertDevNotFound().fingerprint())
                        .as("unreadable body is still the 404").isEqualTo(reference);
                assertThat(BootstrapDb.state().failures()).as("nothing counted").isZero();
                assertThat(BootstrapDb.state().hasTicket()).as("no ticket issued").isFalse();
                assertThat(BootstrapDb.auditActions(own)).as("nothing audited (BR12)").isEmpty();

                BootstrapDb.lockFor(Duration.ofMinutes(10));
                assertThat(unlock(app, PIN).assertDevNotFound().fingerprint()).as("closed beats locked").isEqualTo(reference);
                BootstrapDb.resetState();
            } finally {
                SampleAdminDb.cleanup(own);
            }
        }
    }

    @Test
    void theUnlockAnswersNeverAreAFiveHundredOrCarryDatabaseOrStackText() throws Exception {
        List<RawHttp.Response> answers = new ArrayList<>();
        answers.add(unlock(app, wrongPin(1)));
        answers.add(unlock(app, PIN));
        answers.add(unlock(app, ""));
        answers.add(BootstrapSupport.unlockRequest(app, "[").send());
        fiveWrongPins();
        answers.add(unlock(app, PIN));
        for (RawHttp.Response answer : answers) {
            assertThat(answer.status()).isLessThan(500);
            assertThat(answer.bodyText()).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.postgresql")
                    .doesNotContain("SQLSTATE").doesNotContain("platform.").doesNotContain("Tomcat").doesNotContain("Spring");
        }
    }
}
