package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-31 personal staff PIN over HTTP on the real {@code sahaj_test} database with a random pepper (ADR-010 D3 to D8,
 * D10, D12, D13; docs/api/platform.yaml {@code /me/pin}, {@code /me/pin/code}, {@code /team/{id}/pin/reset},
 * {@code GET /team}, {@code GET /setup/overview}). Lockout counting through the port is in {@link StaffPinCheckIT}; the
 * table in {@link StaffPinTableIT}; the missing and invalid pepper in {@link StaffPinPepperIT}. Test plan:
 * docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinIT extends PinTestSupport {

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}$");

    private static String bare(String pin) {
        return "(?<![0-9A-Za-z.:\\-])" + Pattern.quote(pin) + "(?![0-9A-Za-z.:\\-])";
    }

    // ---------------- status and code ----------------

    @Test
    void aPersonWithoutPinSeesNotSetWithTheMaskedMobileOnlyAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        JsonNode s = pinStatus(p);
        assertThat(s.get("available").asBoolean()).isTrue();
        assertThat(s.get("state").asText()).isEqualTo("NOT_SET");
        assertThat(s.get("setAt").isNull()).isTrue();
        assertThat(s.get("lockedUntil").isNull()).isTrue();
        assertThat(s.get("triesLeft").isNull()).isTrue();
        assertThat(s.get("notSetReason").isNull()).isTrue();
        assertThat(s.get("mobileMasked").asText()).isEqualTo("••••••" + p.mobile().substring(6));
        assertThat(s.toString()).doesNotContain(p.mobile()).doesNotContain("hash");
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).isEmpty();
    }

    @Test
    void theCodeGoesToTheOwnMobileAndIsNeverInTheAnswerAndTheSecondRequestInHalfAMinuteIsRefused() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        allowResend(p.mobile());
        String body = call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null).andReturn().getResponse().getContentAsString();
        JsonNode a = objectMapper.readTree(body);
        assertThat(a.get("resendAfterSeconds").asInt()).isEqualTo(30);
        assertThat(a.get("mobileMasked").asText()).isEqualTo("••••••" + p.mobile().substring(6));
        assertThat(a.size()).isEqualTo(2);
        assertThat(body).doesNotContain(p.mobile());
        JsonNode soon = problem(call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null), 429);
        assertType(soon, "rate-limit");
        assertThat(soon.get("retryAfterSeconds").asInt()).isPositive();
        assertThat(soon.toString()).doesNotContain(p.mobile());
    }

    @Test
    void thePersonSendsTheCodeOnlyToTheirOwnNumberEvenWhenTheBodyNamesAnotherMobile() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        Pn other = newPn(clinic, "DOCTOR");
        allowResend(p.mobile());
        long before = count(clinic, "select count(*) from platform.otp_challenge where mobile = ?", other.mobile());
        JsonNode a = objectMapper.readTree(call(HttpMethod.POST, ME_PIN_CODE, p.auth(),
                "{\"mobile\":\"" + other.mobile() + "\"}").andReturn().getResponse().getContentAsString());
        assertThat(a.get("mobileMasked").asText()).endsWith(p.mobile().substring(6));
        assertThat(count(clinic, "select count(*) from platform.otp_challenge where mobile = ?", other.mobile()))
                .isEqualTo(before);
    }

    @Test
    void fiveCodeRequestsAnHourAreAllowedAndTheSixthIsRefusedWithRetryAfter() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        for (int i = 0; i < 5; i++) {
            allowResend(p.mobile());
            call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null).andExpect(org.springframework.test.web.servlet.result
                    .MockMvcResultMatchers.status().isAccepted());
        }
        allowResend(p.mobile());
        JsonNode limited = problem(call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null), 429);
        assertType(limited, "rate-limit");
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        // another person of the clinic is not touched by this person's allowance
        Pn q = newPn(clinic, "DOCTOR");
        sendCode(q);
    }

    // ---------------- set and change ----------------

    @Test
    void aFirstPinIsStoredAsAHashOnlyAuditedOnceAndTheCodeCannotBeUsedTwice() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        String pin = goodPin(p);
        String code = sendCode(p);
        JsonNode s;
        try (LogCapture logs = new LogCapture()) {
            s = ok(HttpMethod.PUT, ME_PIN, p.auth(), pinBody(code, pin, pin), 200);
            String text = logs.allText();
            assertThat(text).doesNotContain(PEPPER).doesNotContain(p.mobile()).doesNotContain(pinRow(clinic,
                    p.membershipId()).hash());
            assertThat(Pattern.compile(bare(pin)).matcher(text).find()).as("the PIN in a log line").isFalse();
            assertThat(Pattern.compile(bare(code)).matcher(text).find()).as("the code in a log line").isFalse();
        }
        assertThat(s.get("state").asText()).isEqualTo("SET");
        assertThat(s.get("setAt").isNull()).isFalse();
        assertThat(s.get("triesLeft").asInt()).isEqualTo(5);
        assertThat(s.get("lockedUntil").isNull()).isTrue();
        assertThat(s.get("notSetReason").isNull()).isTrue();
        assertThat(s.toString()).doesNotContain("hash").doesNotContain(p.mobile());
        assertThat(hasBarePin(s.toString(), pin)).isFalse();

        PinRow row = pinRow(clinic, p.membershipId());
        assertThat(row.hasPin()).isTrue();
        assertThat(row.hash()).matches(BCRYPT.pattern());
        assertThat(row.failed()).isZero();
        assertThat(row.lockCount()).isZero();
        assertThat(row.version()).isZero();
        assertThat(row.hasSetAt()).isTrue();
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).hasSize(1);
        assertThat(audit(clinic, "PLATFORM_PIN_CHANGED")).isEmpty();
        assertThat(pinStatus(p).get("state").asText()).isEqualTo("SET");

        // the used code answers expired
        String next = otherPin(p, pin);
        JsonNode again = problem(putPin(p, code, next, next), 400);
        assertThat(fieldNames(again)).containsExactly("code");
        assertThat(fieldMessage(again, "code")).isEqualTo("That code has expired. Request a new one.");
        assertThat(pinRow(clinic, p.membershipId()).version()).isZero();
    }

    @Test
    void changingAPinNeedsANewCodeNotTheCurrentPinGivesChangedAuditAndAnotherHash() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        String first = goodPin(p);
        setPin(p, first);
        PinRow before = pinRow(clinic, p.membershipId());
        String second = otherPin(p, first);
        JsonNode s = setPin(p, second);
        assertThat(s.get("state").asText()).isEqualTo("SET");
        PinRow after = pinRow(clinic, p.membershipId());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.hash()).isNotEqualTo(before.hash());
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).hasSize(1);
        assertThat(audit(clinic, "PLATFORM_PIN_CHANGED")).hasSize(1);
        String[] changed = audit(clinic, "PLATFORM_PIN_CHANGED").get(0);
        assertThat(changed[0]).isEqualTo("STAFF_PIN");
        assertThat(changed[1]).isEqualTo(p.membershipId().toString());
        assertThat(hasBarePin(changed[3], first) || hasBarePin(changed[3], second)).isFalse();
        assertThat(changed[3]).contains("\"pinState\"").contains("SET")
                .doesNotContain(after.hash()).doesNotContain(p.mobile());
    }

    @Test
    void samePinForTwoPeopleGivesTwoDifferentHashesBecauseTheMembershipIsInTheInput() throws Exception {
        Clinic clinic = hospital();
        Pn a = newPn(clinic, "PHARMACIST");
        Pn b = newPn(clinic, "DOCTOR");
        String pin = "4827";
        if (a.mobile().contains(pin) || b.mobile().contains(pin)) {
            pin = "7391";
        }
        setPin(a, pin);
        setPin(b, pin);
        assertThat(pinRow(clinic, a.membershipId()).hash()).isNotEqualTo(pinRow(clinic, b.membershipId()).hash());
    }

    @Test
    void weakPinsAreRefusedBeforeTheCodeIsUsedSoTheSameCodeStillWorksForAGoodPin() throws Exception {
        Clinic clinic = hospital();
        List<String> weak = List.of("0000", "7777", "0123", "6789", "9876", "3210", "1212", "1122", "1900", "1987",
                "2026", "2099", "2580", "0852");
        Pn last = null;
        String lastCode = null;
        // ten PIN operations are allowed per person in five minutes, so seven refused PINs per person
        for (int from = 0; from < weak.size(); from += 7) {
            Pn p = newPn(clinic, "NURSE");
            String code = sendCode(p);
            for (String pin : weak.subList(from, from + 7)) {
                JsonNode p400 = problem(putPin(p, code, pin, pin), 400);
                assertType(p400, "validation");
                assertThat(fieldNames(p400)).as(pin).containsExactly("newPin");
                assertThat(fieldMessage(p400, "newPin")).as(pin).isEqualTo(PIN_WEAK);
                assertThat(p400.toString()).doesNotContain("\"" + pin + "\"");
            }
            assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
            last = p;
            lastCode = code;
        }
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).isEmpty();
        String good = goodPin(last);
        ok(HttpMethod.PUT, ME_PIN, last.auth(), pinBody(lastCode, good, good), 200); // the same, unused code
        assertThat(pinRow(clinic, last.membershipId()).hasPin()).isTrue();
    }

    @Test
    void everyWindowOfTheOwnMobileIsRefusedAndNotAllWindowsOfAnotherMobile() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        String code = sendCode(p);
        for (int i = 0; i + 4 <= p.mobile().length(); i++) {
            String window = p.mobile().substring(i, i + 4);
            JsonNode body = problem(putPin(p, code, window, window), 400);
            assertThat(fieldMessage(body, "newPin")).as("window " + i).isEqualTo(PIN_WEAK);
        }
    }

    @Test
    void formatErrorsComeTogetherInTheOrderCodeNewPinConfirmPinAndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        JsonNode all = problem(putPin(p, null, null, null), 400);
        assertType(all, "validation");
        assertThat(fieldNames(all)).as("two missing PINs are equal, so no mismatch").containsExactly("code", "newPin");
        assertThat(fieldMessage(all, "code")).isEqualTo(CODE_FORMAT);
        assertThat(fieldMessage(all, "newPin")).isEqualTo(PIN_FORMAT);
        // "12a4", a short PIN, an Arabic-Indic number and five digits
        for (String bad : new String[] {"12a4", "123", "١٢٣٤", "48271", "", " 482"}) {
            JsonNode b = problem(putPin(p, "12345", bad, bad), 400);
            assertThat(fieldNames(b)).as(bad).containsExactly("code", "newPin");
            assertThat(fieldMessage(b, "newPin")).as(bad).isEqualTo(PIN_FORMAT);
        }
        JsonNode mismatch = problem(putPin(p, "123456", "4827", "4828"), 400);
        assertThat(fieldNames(mismatch)).containsExactly("confirmPin");
        assertThat(fieldMessage(mismatch, "confirmPin")).isEqualTo(PIN_MISMATCH);
        JsonNode sixDigitsOnly = problem(putPin(p, "12ab56", "4827", "4827"), 400);
        assertThat(fieldMessage(sixDigitsOnly, "code")).isEqualTo(CODE_FORMAT);
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
    }

    // ---------------- the code check ----------------

    @Test
    void noCodeSentGivesSendACodeFirstOn400AndNeverA401() throws Exception {
        Clinic clinic = hospital();
        // a person added through the Team endpoint has never been sent a code (a person who signed up has a used one)
        Member added = addMember(clinic, "NURSE");
        UUID addedUser = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                added.membershipId().toString()).get(0));
        Pn p = new Pn(clinic, addedUser, added.membershipId(), added.mobile(), "NURSE", tokenOf(clinic, addedUser, "NURSE"));
        String pin = goodPin(p);
        JsonNode body = problem(putPin(p, "123456", pin, pin), 400);
        assertType(body, "validation");
        assertThat(fieldNames(body)).containsExactly("code");
        assertThat(fieldMessage(body, "code")).isEqualTo("Send a code first.");
    }

    @Test
    void aWrongCodeIsA400OnCodeAndFiveWrongCodesLockTheNumberWith423OtpLockedNeverA401() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        String real = sendCode(p);
        String wrong = "000000".equals(real) ? "000001" : "000000";
        String pin = goodPin(p);
        for (int i = 0; i < 4; i++) {
            JsonNode body = problem(putPin(p, wrong, pin, pin), 400);
            assertThat(fieldNames(body)).containsExactly("code");
            assertThat(fieldMessage(body, "code")).isEqualTo("That code is not right.");
        }
        JsonNode locked = problem(putPin(p, wrong, pin, pin), 423);
        assertType(locked, "otp-locked");
        assertThat(locked.get("detail").asText()).isEqualTo("Too many wrong codes. Try again in 15 minutes.");
        // the right code is refused too while the number is locked, and a new code cannot be requested
        assertType(problem(putPin(p, real, pin, pin), 423), "otp-locked");
        allowResend(p.mobile());
        assertType(problem(call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null), 423), "otp-locked");
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
    }

    @Test
    void anExpiredCodeGivesThatCodeHasExpired() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        String code = sendCode(p);
        ageOutLatestChallenge(p.mobile());
        String pin = goodPin(p);
        JsonNode body = problem(putPin(p, code, pin, pin), 400);
        assertThat(fieldMessage(body, "code")).isEqualTo("That code has expired. Request a new one.");
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
    }

    // ---------------- two first saves at once ----------------

    private record Resp(int status, String body) {
    }

    @Test
    void twoFirstSavesAtTheSameMomentGiveOneOkAndOneConcurrentChange409AndOneRowAndOneAudit() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        String pin = goodPin(p);
        String code = sendCode(p);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Resp>> futures = new ArrayList<>();
        // The latest challenge row is held, so both requests read the code as unused and then wait to mark it used:
        // both pass the code check, the later insert meets the primary key (ADR-010 D7).
        try (Connection holder = db().app()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from platform.otp_challenge where mobile = ? "
                    + "order by created_at desc limit 1 for update")) {
                lock.setString(1, p.mobile());
                assertThat(lock.executeQuery().next()).isTrue();
            }
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    var res = mvc.perform(request(HttpMethod.PUT, URI.create(ME_PIN)).with(p.auth())
                                    .contentType(MediaType.APPLICATION_JSON).content(pinBody(code, pin, pin)))
                            .andReturn().getResponse();
                    return new Resp(res.getStatus(), res.getContentAsString());
                }));
            }
            assertThat(awaitLockWaiters("%update platform.otp_challenge set consumed_at%", 2, 30_000))
                    .as("both requests wait to use the code").isTrue();
            holder.rollback();
        }
        List<Integer> statuses = new ArrayList<>();
        Resp conflict = null;
        for (Future<Resp> f : futures) {
            Resp r = f.get(60, TimeUnit.SECONDS);
            statuses.add(r.status());
            if (r.status() == 409) {
                conflict = r;
            }
        }
        pool.shutdownNow();
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        JsonNode p409 = objectMapper.readTree(conflict.body());
        assertType(p409, "concurrent-change");
        assertThat(p409.get("detail").asText()).isEqualTo("Your PIN was saved at the same moment from another screen. Try again.");
        assertThat(count(clinic, "select count(*) from platform.staff_pin where membership_id = ?::uuid",
                p.membershipId().toString())).isOne();
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).hasSize(1);
        assertThat(pinRow(clinic, p.membershipId()).hasPin()).isTrue();
    }

    // ---------------- rate limits ----------------

    @Test
    void tenPinOperationsInFiveMinutesAreAllowedAndTheEleventhIs429RateLimitWithRetryAfterBeforeAnyWork() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        for (int i = 0; i < 10; i++) {
            assertType(problem(putPin(p, null, null, null), 400), "validation");
        }
        JsonNode limited = problem(putPin(p, "123456", "4827", "4827"), 429);
        assertType(limited, "rate-limit");
        assertThat(limited.get("detail").asText()).isEqualTo(PIN_RATE);
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        // nothing was checked or stored
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
        // the allowance is per person
        assertType(problem(putPin(newPn(clinic, "DOCTOR"), null, null, null), 400), "validation");
    }

    // ---------------- owner reset ----------------

    @Test
    void theOwnerResetClearsThePinKeepsTheRowWritesOneAuditAndTheMemberSetsANewOneWithACode() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        String pin = goodPin(p);
        setPin(p, pin);
        PinRow before = pinRow(clinic, p.membershipId());
        JsonNode member = resetPin(clinic, p.membershipId());
        assertThat(member.get("id").asText()).isEqualTo(p.membershipId().toString());
        assertThat(member.get("pin").get("state").asText()).isEqualTo("NOT_SET");
        assertThat(member.get("pin").get("setAt").isNull()).isTrue();
        assertThat(member.get("pin").get("lockedUntil").isNull()).isTrue();
        assertThat(member.get("mobileMasked").asText()).doesNotContain(p.mobile());
        PinRow row = pinRow(clinic, p.membershipId());
        assertThat(row.exists()).isTrue();
        assertThat(row.hasPin()).isFalse();
        assertThat(row.hasSetAt()).isFalse();
        assertThat(row.reason()).isEqualTo("RESET_BY_OWNER");
        assertThat(row.resetBy()).isEqualTo(clinic.ownerUserId().toString());
        assertThat(row.hasResetAt()).isTrue();
        assertThat(row.version()).isEqualTo(before.version() + 1);
        assertThat(audit(clinic, "PLATFORM_PIN_RESET")).hasSize(1);
        String[] a = audit(clinic, "PLATFORM_PIN_RESET").get(0);
        assertThat(a[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(a[2]).contains("SET");
        assertThat(a[3]).contains("NOT_SET").contains("RESET_BY_OWNER").doesNotContain(before.hash());
        assertThat(hasBarePin(a[3], pin)).isFalse();

        JsonNode status = pinStatus(p);
        assertThat(status.get("state").asText()).isEqualTo("NOT_SET");
        assertThat(status.get("notSetReason").asText()).isEqualTo("RESET_BY_OWNER");
        // the owner never sets one: the member does, with a code, and the reset stamp stays
        String next = otherPin(p, pin);
        setPin(p, next);
        PinRow again = pinRow(clinic, p.membershipId());
        assertThat(again.hasPin()).isTrue();
        assertThat(again.reason()).isEmpty();
        assertThat(again.resetBy()).isEqualTo(clinic.ownerUserId().toString());
        assertThat(again.hasResetAt()).isTrue();
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).hasSize(2);
    }

    @Test
    void resetOfALockedPinAndOfTheOwnersOwnRowWorksAndAResetRepeatedOrOfAMemberWithoutPinWritesNothing() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        setPin(p, goodPin(p));
        forceCounters(p, 3, 1);
        forcePinRow(p, "locked_until = clock_timestamp() + interval '10 minutes'");
        assertThat(teamRow(clinic, clinic.owner(), p.membershipId()).get("pin").get("state").asText()).isEqualTo("LOCKED");
        resetPin(clinic, p.membershipId());
        PinRow cleared = pinRow(clinic, p.membershipId());
        assertThat(cleared.hasPin()).isFalse();
        assertThat(cleared.failed()).isZero();
        assertThat(cleared.lockCount()).isZero();
        assertThat(cleared.lockedNow()).isFalse();
        assertThat(cleared.hasWindow()).isFalse();
        // repeat: 200, no new write, no new audit
        long auditsBefore = audits(clinic, "PLATFORM_PIN_RESET");
        JsonNode second = resetPin(clinic, p.membershipId());
        assertThat(second.get("pin").get("state").asText()).isEqualTo("NOT_SET");
        assertThat(pinRow(clinic, p.membershipId()).version()).isEqualTo(cleared.version());
        assertThat(audits(clinic, "PLATFORM_PIN_RESET")).isEqualTo(auditsBefore);
        // a member who never had a PIN: 200 and still no row
        Pn never = newPn(clinic, "DOCTOR");
        assertThat(resetPin(clinic, never.membershipId()).get("pin").get("state").asText()).isEqualTo("NOT_SET");
        assertThat(pinRow(clinic, never.membershipId()).exists()).isFalse();
        // the owner's own row
        Pn owner = ownerPn(clinic);
        setPin(owner, goodPin(owner));
        resetPin(clinic, owner.membershipId());
        assertThat(pinRow(clinic, owner.membershipId()).hasPin()).isFalse();
    }

    @Test
    void everyRoleButTheOwnerGets403OnlyTheClinicOwnerCanDoThisAndTheMembersPinStays() throws Exception {
        Clinic clinic = hospital();
        Pn target = newPn(clinic, "PHARMACIST");
        setPin(target, goodPin(target));
        long audits = audits(clinic, "PLATFORM_PIN_RESET");
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
                "COUNSELLOR"}) {
            Pn actor = newPn(clinic, role);
            JsonNode p = problem(call(HttpMethod.POST, TEAM + "/" + target.membershipId() + "/pin/reset", actor.auth(),
                    null), 403);
            assertType(p, "forbidden");
            assertThat(p.get("detail").asText()).as(role).isEqualTo(ONLY_OWNER);
        }
        assertThat(pinRow(clinic, target.membershipId()).hasPin()).isTrue();
        assertThat(audits(clinic, "PLATFORM_PIN_RESET")).isEqualTo(audits);
        assertType(problem(call(HttpMethod.POST, TEAM + "/" + target.membershipId() + "/pin/reset", noSession(), null),
                401), "unauthorized");
    }

    @Test
    void theTokenSaysOwnerButTheMembershipIsADoctorSoTheResetIsRefusedAtOnce() throws Exception {
        Clinic clinic = hospital();
        Pn target = newPn(clinic, "PHARMACIST");
        setPin(target, goodPin(target));
        Staff doctor = staff(clinic, "DOCTOR");
        var forgedOwner = tokenOf(clinic, doctor.userId(), "OWNER");
        JsonNode p = problem(call(HttpMethod.POST, TEAM + "/" + target.membershipId() + "/pin/reset", forgedOwner, null),
                403);
        assertThat(p.get("detail").asText()).isEqualTo(ONLY_OWNER);
        assertThat(pinRow(clinic, target.membershipId()).hasPin()).isTrue();
        // and a real owner whose membership was changed to DOCTOR is refused with the old OWNER token
        Staff second = staff(clinic, "DOCTOR");
        setMembershipRole(clinic, second.userId(), "OWNER");
        resetPin2(clinic, second, target, 200);
        setMembershipRole(clinic, second.userId(), "NURSE");
        problem(call(HttpMethod.POST, TEAM + "/" + target.membershipId() + "/pin/reset",
                tokenOf(clinic, second.userId(), "OWNER"), null), 403);
    }

    private void resetPin2(Clinic clinic, Staff by, Pn target, int expected) throws Exception {
        ok(HttpMethod.POST, TEAM + "/" + target.membershipId() + "/pin/reset", tokenOf(clinic, by.userId(), "OWNER"), null,
                expected);
    }

    @Test
    void aMemberOfAnotherClinicAndAnUnknownIdAre404AndTheOtherClinicsPinIsUntouched() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn inA = newPn(a, "PHARMACIST");
        setPin(inA, goodPin(inA));
        PinRow before = pinRow(a, inA.membershipId());
        JsonNode p = problem(call(HttpMethod.POST, TEAM + "/" + inA.membershipId() + "/pin/reset", b.owner(), null), 404);
        assertType(p, "not-found");
        assertType(problem(call(HttpMethod.POST, TEAM + "/" + UUID.randomUUID() + "/pin/reset", b.owner(), null), 404),
                "not-found");
        assertThat(pinRow(a, inA.membershipId())).isEqualTo(before);
        assertThat(audits(a, "PLATFORM_PIN_RESET")).isZero();
        // clinic B's team list never shows clinic A's members
        for (JsonNode row : ok(HttpMethod.GET, TEAM, b.owner(), null, 200)) {
            assertThat(row.get("id").asText()).isNotEqualTo(inA.membershipId().toString());
        }
    }

    @Test
    void thirtyResetsAnHourAreAllowedAndTheThirtyFirstIs429() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        for (int i = 0; i < 30; i++) {
            resetPin(clinic, p.membershipId());
        }
        JsonNode limited = problem(call(HttpMethod.POST, TEAM + "/" + p.membershipId() + "/pin/reset", clinic.owner(),
                null), 429);
        assertType(limited, "rate-limit");
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        // another clinic's owner has an allowance of their own
        Clinic other = hospital();
        resetPin(other, newPn(other, "NURSE").membershipId());
    }

    @Test
    void anUnknownMembershipIdOnResetIs404AndDoesNotUseTheOwnersAllowanceOf30() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "NURSE");
        // 40 refused calls with ids that do not exist (and one of another clinic): none uses the allowance
        Clinic other = hospital();
        Pn foreign = newPn(other, "NURSE");
        for (int i = 0; i < 40; i++) {
            UUID id = i == 0 ? foreign.membershipId() : UUID.randomUUID();
            assertType(problem(call(HttpMethod.POST, TEAM + "/" + id + "/pin/reset", clinic.owner(), null), 404), "not-found");
        }
        // all 30 real resets still go through, the 31st is limited
        for (int i = 0; i < 30; i++) {
            resetPin(clinic, p.membershipId());
        }
        assertType(problem(call(HttpMethod.POST, TEAM + "/" + p.membershipId() + "/pin/reset", clinic.owner(), null), 429),
                "rate-limit");
    }

    // ---------------- access removal ----------------

    @Test
    void removingAccessClearsThePinWithTheResetAuditAndRemovingSomeoneWithoutAPinWritesNothing() throws Exception {
        Clinic clinic = hospital();
        Pn withPin = newPn(clinic, "NURSE");
        setPin(withPin, goodPin(withPin));
        Pn without = newPn(clinic, "FRONT_DESK");
        call(HttpMethod.DELETE, TEAM + "/" + withPin.membershipId(), clinic.owner(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        call(HttpMethod.DELETE, TEAM + "/" + without.membershipId(), clinic.owner(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        PinRow row = pinRow(clinic, withPin.membershipId());
        assertThat(row.hasPin()).isFalse();
        assertThat(row.reason()).isEqualTo("RESET_BY_OWNER");
        assertThat(row.resetBy()).isEqualTo(clinic.ownerUserId().toString());
        assertThat(auditCount(clinic, "PLATFORM_PIN_RESET", withPin.membershipId())).isOne();
        assertThat(pinRow(clinic, without.membershipId()).exists()).isFalse();
        assertThat(auditCount(clinic, "PLATFORM_PIN_RESET", without.membershipId())).isZero();
        // a person without an active membership has no PIN screen
        JsonNode denied = problem(call(HttpMethod.GET, ME_PIN, withPin.auth(), null), 403);
        assertType(denied, "forbidden");
    }

    // ---------------- who sees which PIN state ----------------

    @Test
    void ownerSeesThePinStateOfEveryRowAndOthersSeeOnlyTheirOwnAndThePinKeyIsAbsentOnTheOthersRows() throws Exception {
        Clinic clinic = hospital();
        Pn set = newPn(clinic, "PHARMACIST");
        setPin(set, goodPin(set));
        Pn locked = newPn(clinic, "NURSE");
        setPin(locked, goodPin(locked));
        forceCounters(locked, 0, 1);
        forcePinRow(locked, "locked_until = clock_timestamp() + interval '10 minutes'");
        Pn none = newPn(clinic, "DOCTOR");

        JsonNode asOwner = ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200);
        int seen = 0;
        for (JsonNode row : asOwner) {
            String id = row.get("id").asText();
            assertThat(row.has("pin")).as("owner sees pin on " + id).isTrue();
            assertThat(row.get("pin").has("setAt")).isTrue();
            assertThat(row.get("pin").has("lockedUntil")).isTrue();
            if (id.equals(set.membershipId().toString())) {
                assertThat(row.get("pin").get("state").asText()).isEqualTo("SET");
                assertThat(row.get("pin").get("setAt").isNull()).isFalse();
                assertThat(row.get("pin").get("lockedUntil").isNull()).isTrue();
                seen++;
            } else if (id.equals(locked.membershipId().toString())) {
                assertThat(row.get("pin").get("state").asText()).isEqualTo("LOCKED");
                assertThat(row.get("pin").get("lockedUntil").isNull()).isFalse();
                seen++;
            } else if (id.equals(none.membershipId().toString())) {
                assertThat(row.get("pin").get("state").asText()).isEqualTo("NOT_SET");
                assertThat(row.get("pin").get("setAt").isNull()).isTrue();
                seen++;
            }
        }
        assertThat(seen).isEqualTo(3);
        assertThat(asOwner.toString()).doesNotContain("hash").doesNotContain("pin_hash");

        for (Pn viewer : List.of(set, locked, none)) {
            for (JsonNode row : ok(HttpMethod.GET, TEAM, viewer.auth(), null, 200)) {
                boolean own = row.get("id").asText().equals(viewer.membershipId().toString());
                assertThat(row.has("pin")).as(viewer.role() + " looking at " + row.get("id").asText()).isEqualTo(own);
            }
        }
        // a token that says OWNER for a person whose membership is not an owner gets the own row only
        Staff doctor = staff(clinic, "DOCTOR");
        Pn doc = pn(clinic, doctor);
        for (JsonNode row : ok(HttpMethod.GET, TEAM, tokenOf(clinic, doc.userId(), "OWNER"), null, 200)) {
            assertThat(row.has("pin")).isEqualTo(row.get("id").asText().equals(doc.membershipId().toString()));
        }
    }

    // ---------------- tenant isolation ----------------

    @Test
    void aPersonOfOneClinicNeverSeesOrChangesAnotherClinicsPinRowsAndOnlyOwnClinicsCountsOnTheChecklist() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn inA = newPn(a, "PHARMACIST");
        setPin(inA, goodPin(inA));
        assertThat(count(b, "select count(*) from platform.staff_pin where membership_id = ?::uuid",
                inA.membershipId().toString())).as("clinic B reads no row of clinic A").isZero();
        assertThat(count(a, "select count(*) from platform.staff_pin where membership_id = ?::uuid",
                inA.membershipId().toString())).isOne();
        // the token of clinic A's pharmacist used in clinic B (forged tenant): no membership there
        var crossed = tokenOf(b, inA.userId(), "PHARMACIST");
        assertType(problem(call(HttpMethod.GET, ME_PIN, crossed, null), 403), "forbidden");
        assertType(problem(call(HttpMethod.POST, ME_PIN_CODE, crossed, null), 403), "forbidden");
        JsonNode body = problem(putPin(new Pn(b, inA.userId(), inA.membershipId(), inA.mobile(), "PHARMACIST", crossed),
                "123456", "4827", "4827"), 403);
        assertType(body, "forbidden");
    }

    // ---------------- audit and problem bodies ----------------

    @Test
    void noPinCodeHashPepperOrMobileIsInAnyAuditRowOrProblemBodyOfTheWholeLifecycle() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        String first = goodPin(p);
        String second = otherPin(p, first);
        List<String> bodies = new ArrayList<>();
        String code = sendCode(p);
        bodies.add(call(HttpMethod.PUT, ME_PIN, p.auth(), pinBody(code, "0000", "0000")).andReturn().getResponse()
                .getContentAsString());
        bodies.add(call(HttpMethod.PUT, ME_PIN, p.auth(), pinBody("000000".equals(code) ? "000001" : "000000", first,
                first)).andReturn().getResponse().getContentAsString());
        bodies.add(ok(HttpMethod.PUT, ME_PIN, p.auth(), pinBody(code, first, first), 200).toString());
        setPin(p, second);
        bodies.add(pinStatus(p).toString());
        bodies.add(resetPin(clinic, p.membershipId()).toString());
        setPin(p, first);
        String hash = pinRow(clinic, p.membershipId()).hash();
        String audit = allAuditText(clinic);
        bodies.add(audit);
        for (String text : bodies) {
            assertThat(text).doesNotContain(PEPPER).doesNotContain(p.mobile()).doesNotContain(hash)
                    .doesNotContain(hash.substring(7, 27)).doesNotContain("pin_hash");
            assertThat(Pattern.compile(bare(first)).matcher(text).find()).isFalse();
            assertThat(Pattern.compile(bare(second)).matcher(text).find()).isFalse();
            assertThat(Pattern.compile(bare(code)).matcher(text).find()).isFalse();
        }
        for (String action : new String[] {"PLATFORM_PIN_SET", "PLATFORM_PIN_CHANGED", "PLATFORM_PIN_RESET"}) {
            for (String[] row : audit(clinic, action)) {
                assertThat(row[0]).isEqualTo("STAFF_PIN");
                assertThat(row[1]).isEqualTo(p.membershipId().toString());
            }
        }
    }

    // ---------------- checklist and menu ----------------

    @Test
    void theSetupOverviewHasASeventhOptionalPinLineThatIsDoneWhenEveryActiveMemberHasAPinAndNeverChangesThePercent()
            throws Exception {
        Clinic clinic = hospital();
        JsonNode first = ok(HttpMethod.GET, OVERVIEW, clinic.owner(), null, 200);
        assertThat(first.get("checks")).hasSize(7);
        JsonNode line = first.get("checks").get(6);
        assertThat(line.get("key").asText()).isEqualTo("pin");
        assertThat(line.get("label").asText()).isEqualTo("Own PIN for each staff member");
        assertThat(line.get("why").asText()).isEqualTo("So medicine checks show who did them");
        assertThat(line.get("optional").asBoolean()).isTrue();
        assertThat(line.get("done").asBoolean()).isFalse();
        for (int i = 0; i < 6; i++) {
            assertThat(first.get("checks").get(i).get("optional").asBoolean()).isFalse();
        }
        int percent = first.get("percent").asInt();
        // every active member (the owner and the doctor added by the set-up) sets a PIN
        Pn owner = ownerPn(clinic);
        setPin(owner, goodPin(owner));
        assertThat(ok(HttpMethod.GET, OVERVIEW, clinic.owner(), null, 200).get("checks").get(6).get("done").asBoolean())
                .as("the doctor still has none").isFalse();
        UUID doctorUser = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                clinic.doctorMembershipId().toString()).get(0));
        Pn doctor = pn(clinic, new Staff(doctorUser, "Dr", "DOCTOR", tokenOf(clinic, doctorUser, "DOCTOR")));
        setPin(doctor, goodPin(doctor));
        JsonNode after = ok(HttpMethod.GET, OVERVIEW, clinic.owner(), null, 200);
        assertThat(after.get("checks").get(6).get("done").asBoolean()).isTrue();
        assertThat(after.get("percent").asInt()).isEqualTo(percent);
    }

    @Test
    void theSetupOverviewIsStillOwnerOnlyWithThePinLine() throws Exception {
        Clinic clinic = hospital();
        for (String role : new String[] {"DOCTOR", "NURSE", "PHARMACIST", "FRONT_DESK"}) {
            assertType(problem(call(HttpMethod.GET, OVERVIEW, staff(clinic, role).auth(), null), 403), "forbidden");
        }
    }

    @Test
    void thePharmacistMenuHasIpdRightAfterTodayWhenIpdIsOnAndNotWhenItIsOff() throws Exception {
        Clinic on = hospital();
        Staff pharmacist = staff(on, "PHARMACIST");
        List<String> menu = new ArrayList<>();
        ok(HttpMethod.GET, "/api/v1/platform/me", pharmacist.auth(), null, 200).get("menu").forEach(n -> menu.add(n.asText()));
        assertThat(menu).contains("today", "ipd");
        assertThat(menu.indexOf("ipd")).isEqualTo(menu.indexOf("today") + 1);
        assertThat(menu).doesNotContain("queue", "billing", "config");
        Clinic off = newClinic();
        List<String> offMenu = new ArrayList<>();
        ok(HttpMethod.GET, "/api/v1/platform/me", staff(off, "PHARMACIST").auth(), null, 200).get("menu")
                .forEach(n -> offMenu.add(n.asText()));
        assertThat(offMenu).containsExactly("today");
    }

    @Test
    void anUnauthenticatedCallToEveryPinEndpointIs401() throws Exception {
        assertType(problem(call(HttpMethod.GET, ME_PIN, noSession(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.POST, ME_PIN_CODE, noSession(), null), 401), "unauthorized");
        assertType(problem(putPin(new Pn(null, null, null, null, null, noSession()), "123456", "4827", "4827"), 401),
                "unauthorized");
    }

    @SuppressWarnings("unused")
    private static CountDownLatch unused() {
        return new CountDownLatch(0);
    }
}
