package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-58 (ADR-022 D12, DEC-042 H1 to H4) the money of a treatment course in billing: the running account worked out on read
 * (estimate, paid, credit, due from earlier, due today, balance, the ledger and the suggestions, to the rupee), collecting a
 * payment (owner and front desk only), the Idempotency-Key and its request hash, amounts over the balance, the clinic's RC-n
 * receipt numbers, the printable receipt, and the day closing and the revenue that now include course payments. Real
 * database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class CoursePaymentsIT extends EmrPhase3Support {

    private static long sum(JsonNode ledger, String field) {
        long total = 0;
        for (JsonNode line : ledger) {
            total += line.get(field).asLong();
        }
        return total;
    }

    // ================= the account =================

    @Test
    void aFreshAccountHasTheEstimateAsBalanceNothingPaidAndTheMinimumPartPaymentSuggested() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        JsonNode account = account(rec.clinic(), course);
        assertThat(account.get("courseId").asText()).isEqualTo(course.get("id").asText());
        assertThat(account.get("courseNo").asText()).isEqualTo("TC-0001");
        assertThat(account.get("unit").asText()).isEqualTo("SITTING");
        assertThat(account.get("estimatePaise").asLong()).isEqualTo(300000L);
        assertThat(account.get("paidPaise").asLong()).isZero();
        assertThat(account.get("creditPaise").asLong()).isZero();
        assertThat(account.get("dueFromEarlierPaise").asLong()).isZero();
        assertThat(account.get("todayChargePaise").asLong()).isZero();
        assertThat(account.get("dueTodayPaise").asLong()).isZero();
        assertThat(account.get("balancePaise").asLong()).isEqualTo(300000L);
        assertThat(account.get("sittingCount").asInt()).isEqualTo(3);
        assertThat(account.get("doneCount").asInt()).isZero();
        assertThat(account.get("receiptCount").asInt()).isZero();
        assertThat(account.get("nextPlannedAdmission").isNull()).isTrue();
        assertThat(account.get("suggestions").get("partPaymentPaise").asLong()).as("at least Rs 500").isEqualTo(50000L);
        assertThat(account.get("suggestions").get("ipdDepositPaise").isNull()).isTrue();
        assertThat(account.get("canCollect").asBoolean()).isTrue();
        assertThat(account.get("ledger")).isEmpty();
        // a course with no sittings has an empty account and no part payment to suggest
        JsonNode empty = newCourse(rec, "ANC", null);
        JsonNode none = account(rec.clinic(), empty);
        assertThat(none.get("estimatePaise").asLong()).isZero();
        assertThat(none.get("balancePaise").asLong()).isZero();
        assertThat(none.get("suggestions").get("partPaymentPaise").isNull()).isTrue();
    }

    @Test
    void thePaidCreditDueAndBalanceFollowThePaymentsAndTheDoneSittingsAndTheLedgerAddsUpToTheRupee() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        String s1 = course.get("sittings").get(0).get("id").asText();
        String s2 = course.get("sittings").get(1).get("id").asText();
        String s3 = course.get("sittings").get(2).get("id").asText();
        // Rs 1,000 part payment before any sitting is done: all of it is advance
        JsonNode first = collect(rec.clinic(), course, "PART_PAYMENT", 100000, "CASH");
        assertThat(first.get("receiptNo").asText()).isEqualTo("RC-1");
        JsonNode a = first.get("account");
        assertThat(a.get("paidPaise").asLong()).isEqualTo(100000L);
        assertThat(a.get("creditPaise").asLong()).isEqualTo(100000L);
        assertThat(a.get("balancePaise").asLong()).isEqualTo(200000L);
        assertThat(a.get("receiptCount").asInt()).isEqualTo(1);
        assertThat(a.get("ledger")).hasSize(1);
        JsonNode line = a.get("ledger").get(0);
        assertThat(line.get("kind").asText()).isEqualTo("PAYMENT");
        assertThat(line.get("type").asText()).isEqualTo("PART_PAYMENT");
        assertThat(line.get("mode").asText()).isEqualTo("CASH");
        assertThat(line.get("against").asText()).isEqualTo("Course balance");
        assertThat(line.get("amountPaise").asLong()).isEqualTo(100000L);
        assertThat(line.get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(line.get("date").asText()).matches("^\\d{4}-\\d{2}-\\d{2}$");
        // the first sitting is done and uses the advance: no credit left, nothing due, an "advance used" line
        markSittingDone(rec.clinic(), s1);
        JsonNode b = account(rec.clinic(), course);
        assertThat(b.get("creditPaise").asLong()).isZero();
        assertThat(b.get("dueFromEarlierPaise").asLong()).isZero();
        assertThat(b.get("doneCount").asInt()).isEqualTo(1);
        assertThat(b.get("ledger")).hasSize(2);
        JsonNode used = null;
        for (JsonNode l : b.get("ledger")) {
            if ("CREDIT_USED".equals(l.get("kind").asText())) {
                used = l;
            }
        }
        assertThat(used.get("amountPaise").asLong()).isEqualTo(-100000L);
        assertThat(used.get("against").asText()).isEqualTo("Sitting 1");
        assertThat(used.get("receiptNo").isNull()).isTrue();
        assertThat(sum(b.get("ledger"), "amountPaise")).as("the ledger adds up to the credit").isEqualTo(b.get("creditPaise").asLong());
        // the second sitting is done too: Rs 1,500 charged against Rs 1,000 paid is Rs 1,500 due from earlier less nothing: 1,500 - 0
        markSittingDone(rec.clinic(), s2);
        JsonNode c = account(rec.clinic(), course);
        assertThat(c.get("dueFromEarlierPaise").asLong()).isEqualTo(150000L);
        assertThat(c.get("creditPaise").asLong()).isZero();
        assertThat(c.get("balancePaise").asLong()).isEqualTo(200000L);
        // today's sitting is the third: linked to today's visit
        JsonNode visit = visitFor(rec.clinic(), rec.patientId(), rec.doctor());
        post(COURSES + "/" + course.get("id").asText() + "/sittings/" + s3 + "/link-visit", rec.token(),
                "{\"revision\":" + get(COURSES + "/" + course.get("id").asText(), rec.token()).get("revision").asLong() + ",\"visitId\":\"" + visit.get("id").asText() + "\"}", 200);
        JsonNode d = account(rec.clinic(), course);
        assertThat(d.get("todayChargePaise").asLong()).isEqualTo(50000L);
        assertThat(d.get("dueTodayPaise").asLong()).as("charges done 2,500 + today 500 - paid 1,000").isEqualTo(200000L);
        // the invariant of ADR-022: paid - charges done = credit - due from earlier, to the rupee
        assertThat(d.get("paidPaise").asLong() - 250000L).isEqualTo(d.get("creditPaise").asLong() - d.get("dueFromEarlierPaise").asLong());
        // paying the whole balance clears the estimate and leaves credit for what is still to be done
        JsonNode full = collect(rec.clinic(), course, "FULL_BALANCE", 200000, "UPI");
        JsonNode e = full.get("account");
        assertThat(full.get("receiptNo").asText()).isEqualTo("RC-2");
        assertThat(e.get("balancePaise").asLong()).isZero();
        assertThat(e.get("paidPaise").asLong()).isEqualTo(300000L);
        assertThat(e.get("dueFromEarlierPaise").asLong()).isZero();
        assertThat(e.get("creditPaise").asLong()).isEqualTo(50000L);
        assertThat(e.get("suggestions").get("partPaymentPaise").isNull()).as("nothing left to suggest").isTrue();
        assertThat(sum(e.get("ledger"), "amountPaise")).isEqualTo(e.get("creditPaise").asLong());
        assertThat(e.get("receiptCount").asInt()).isEqualTo(2);
        // the ledger is in time order and carries the receipts
        List<String> receipts = new ArrayList<>();
        for (JsonNode l : e.get("ledger")) {
            if (!l.get("receiptNo").isNull()) {
                receipts.add(l.get("receiptNo").asText());
            }
        }
        assertThat(receipts).containsExactly("RC-1", "RC-2");
    }

    @Test
    void thePartPaymentSuggestionIsHalfOfWhatIsDueRoundedToHundredsAtLeastFiveHundredAtMostTheBalanceAndTheIpdDepositIsFortyPercentToFiveHundreds() throws Exception {
        Rec rec = newRec();
        ok(HttpMethod.POST, TRIAL_URL, rec.clinic().owner(), null, 200);
        JsonNode course = newCourse(rec, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "," + sittingJson("opd_sitting", 200000L, null) + ","
                + sittingJson("day_care_admission", 400000L, "Admission deposit is due by 36 weeks.") + "]");
        // nothing done: due today 0, so the least (Rs 500); the estimate counts the planned admission too
        JsonNode start = account(rec.clinic(), course);
        assertThat(start.get("estimatePaise").asLong()).isEqualTo(700000L);
        assertThat(start.get("suggestions").get("partPaymentPaise").asLong()).isEqualTo(50000L);
        // 40 percent of Rs 4,000 is Rs 1,600, to the nearest Rs 500: Rs 1,500
        assertThat(start.get("suggestions").get("ipdDepositPaise").asLong()).isEqualTo(150000L);
        JsonNode next = start.get("nextPlannedAdmission");
        assertThat(next.get("sittingId").asText()).isEqualTo(course.get("sittings").get(2).get("id").asText());
        assertThat(next.get("sittingNo").asInt()).isEqualTo(3);
        assertThat(next.get("chargePaise").asLong()).isEqualTo(400000L);
        assertThat(next.get("note").asText()).isEqualTo("Admission deposit is due by 36 weeks.");
        // both OPD sittings done: due today Rs 3,000, half is Rs 1,500
        markSittingDone(rec.clinic(), course.get("sittings").get(0).get("id").asText());
        markSittingDone(rec.clinic(), course.get("sittings").get(1).get("id").asText());
        JsonNode done = account(rec.clinic(), course);
        assertThat(done.get("dueTodayPaise").asLong()).isEqualTo(300000L);
        assertThat(done.get("suggestions").get("partPaymentPaise").asLong()).isEqualTo(150000L);
        // the suggestion never exceeds the balance
        collect(rec.clinic(), course, "PART_PAYMENT", 650000, "CASH");
        JsonNode small = account(rec.clinic(), course);
        assertThat(small.get("balancePaise").asLong()).isEqualTo(50000L);
        assertThat(small.get("suggestions").get("partPaymentPaise").asLong()).isEqualTo(50000L);
        collect(rec.clinic(), course, "PART_PAYMENT", 50000, "CASH");
        assertThat(account(rec.clinic(), course).get("suggestions").get("partPaymentPaise").isNull()).isTrue();
        // an admission sitting with no charge: 40 percent of nothing is nothing
        JsonNode free = newCourse(rec, "DENTAL", "[" + sittingJson("ipd_admission", null, null) + "]");
        assertThat(account(rec.clinic(), free).get("suggestions").get("ipdDepositPaise").asLong()).isZero();
    }

    // ================= collecting a payment =================

    @Test
    void thePaymentTypesAreCheckedAndAVisitPaymentNamesTodaysSittingAndAnIpdDepositNamesAPlannedAdmission() throws Exception {
        Rec rec = newRec();
        ok(HttpMethod.POST, TRIAL_URL, rec.clinic().owner(), null, 200);
        JsonNode course = newCourse(rec, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "," + sittingJson("day_care_admission", 400000L, null) + "]");
        String opd = course.get("sittings").get(0).get("id").asText();
        String admission = course.get("sittings").get(1).get("id").asText();
        JsonNode visit = okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("VISIT_PAYMENT", 50000, "CASH", false, opd), key(), 201);
        assertThat(visit.get("account").get("ledger").get(0).get("against").asText()).isEqualTo("Sitting 1");
        JsonNode today = collect(rec.clinic(), course, "VISIT_PAYMENT", 50000, "CASH");
        assertThat(today.get("account").get("ledger").get(1).get("against").asText()).isEqualTo("Today's sitting");
        JsonNode deposit = okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("IPD_DEPOSIT", 150000, "UPI", true, admission), key(), 201);
        assertThat(deposit.get("account").get("ledger").get(2).get("type").asText()).isEqualTo("IPD_DEPOSIT");
        assertThat(deposit.get("account").get("ledger").get(2).get("against").asText()).isEqualTo("Admission deposit · Sitting 2");
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment where against_sitting_id = ?::uuid and type = 'IPD_DEPOSIT'", admission)).isEqualTo(1);
        assertThat(collect(rec.clinic(), course, "ADVANCE", 20000, "CARD").get("account").get("ledger").get(3).get("against").asText()).isEqualTo("Course advance");
        assertThat(collect(rec.clinic(), course, "PART_PAYMENT", 10000, "CASH").get("account").get("ledger").get(4).get("against").asText()).isEqualTo("Course balance");
        // refused: an IPD deposit with no sitting or an OPD one, a kind of payment with a sitting it does not take, an unknown sitting
        String[] bad = {paymentBody("IPD_DEPOSIT", 100000, "CASH", false, null), paymentBody("IPD_DEPOSIT", 100000, "CASH", false, opd),
                paymentBody("PART_PAYMENT", 100000, "CASH", false, opd), paymentBody("ADVANCE", 100000, "CASH", false, admission),
                paymentBody("VISIT_PAYMENT", 100000, "CASH", false, UUID.randomUUID().toString()),
                paymentBody("REFUND", 100000, "CASH", false, null), paymentBody("PART_PAYMENT", 100000, "CHEQUE", false, null)};
        for (String body : bad) {
            assertThat(callKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, key()).andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(5);
    }

    @Test
    void anAmountMustBeWholeRupeesAboveZeroAndUpToTenCroreAndAVisitFullOrPartPaymentMayNotPassTheBalanceButAnAdvanceMay() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        for (long amount : new long[] {0, -100, 50, 150, 100000000001L, 10_000_000_100L}) {
            JsonNode problem = problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("ADVANCE", amount, "CASH", false, null), key(), 400, "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).as("amount " + amount).isEqualTo("amountPaise");
        }
        for (String body : new String[] {"{\"type\":\"ADVANCE\",\"mode\":\"CASH\",\"useCredit\":false}",
                "{\"type\":\"ADVANCE\",\"amountPaise\":100,\"useCredit\":false}", "{\"amountPaise\":100,\"mode\":\"CASH\",\"useCredit\":false}",
                "{\"type\":\"ADVANCE\",\"amountPaise\":100,\"mode\":\"CASH\"}", "{}"}) {
            assertThat(callKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, key()).andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        // over the balance (Rs 3,000): refused for these three types with nothing recorded
        for (String type : new String[] {"VISIT_PAYMENT", "FULL_BALANCE", "PART_PAYMENT"}) {
            JsonNode problem = problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody(type, 300100, "CASH", false, null), key(), 400, "amount-over-balance");
            assertThat(problem.toString()).doesNotContain("300100");
        }
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isZero();
        // exactly the balance is allowed; the minimum, one rupee, is allowed
        assertThat(collect(rec.clinic(), course, "PART_PAYMENT", 100, "CASH").get("account").get("balancePaise").asLong()).isEqualTo(299900L);
        assertThat(collect(rec.clinic(), course, "FULL_BALANCE", 299900, "CASH").get("account").get("balancePaise").asLong()).isZero();
        // with nothing left to pay an advance or an IPD deposit is still accepted and stays as credit, up to Rs 10 crore
        JsonNode advance = collect(rec.clinic(), course, "ADVANCE", 500000, "UPI");
        assertThat(advance.get("account").get("balancePaise").asLong()).isZero();
        assertThat(advance.get("account").get("creditPaise").asLong()).isEqualTo(800000L);
        assertThat(collect(rec.clinic(), course, "ADVANCE", 10_000_000_000L, "CARD").get("account").get("creditPaise").asLong()).isEqualTo(10_000_800_000L);
        problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 100, "CASH", false, null), key(), 400, "amount-over-balance");
    }

    @Test
    void aPaymentBodyWithAnUnknownFieldIs400ValidationAsTheContractClosesItAndNothingIsRecorded() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        JsonNode problem = problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(),
                paymentBody("ADVANCE", 100000, "CASH", false, null).replace("}", ",\"zxqextra\":1}"), key(), 400, "validation");
        assertThat(problem.toString()).doesNotContain("zxqextra");
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isZero();
        assertThat(count(rec.clinic(), "select count(*) from billing.receipt_counter")).as("no receipt number was taken").isZero();
    }

    @Test
    void aBodyWithAClinicIdIsIgnoredAndThePaymentIsAlwaysTheSignedInClinics() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        String body = paymentBody("ADVANCE", 100000, "CASH", false, null).replace("}", ",\"tenantId\":\"" + UUID.randomUUID() + "\",\"clinicId\":\"" + UUID.randomUUID() + "\"}");
        JsonNode result = okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, key(), 201);
        assertThat(result.get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(1);
    }

    // ================= idempotency and concurrency =================

    @Test
    void theSameKeyAndBodyReturnsTheFirstPaymentWith200AndAnotherBodyOrAnotherCourseWithItIs409AndABadKeyIs400() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        JsonNode other = threeSittingCourse(rec);
        String key = key();
        String body = paymentBody("PART_PAYMENT", 100000, "CASH", false, null);
        JsonNode first = okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, key, 201);
        JsonNode replay = okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, key, 200);
        assertThat(replay.get("receiptNo").asText()).isEqualTo(first.get("receiptNo").asText());
        assertThat(replay.get("account").get("paidPaise").asLong()).as("not paid twice").isEqualTo(100000L);
        assertThat(replay.get("account").get("receiptCount").asInt()).isEqualTo(1);
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(1);
        problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 110000, "CASH", false, null), key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 100000, "UPI", false, null), key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 100000, "CASH", true, null), key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, paymentsPath(other), rec.clinic().owner(), body, key, 409, "idempotency-conflict");
        for (String bad : new String[] {null, "short", "has space in it 12345", "a".repeat(65), "bad!key!bad!key"}) {
            problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), body, bad, 400, "validation");
        }
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(1);
        assertThat(auditCount(rec.clinic(), "COURSE_PAYMENT_RECORDED")).isEqualTo(1);
        // the same key in another clinic is its own
        Rec b = newRec();
        okKeyed(HttpMethod.POST, paymentsPath(threeSittingCourse(b)), b.clinic().owner(), body, key, 201);
    }

    @Test
    void aTapRepeatedAtTheSameMomentRecordsOnceAndTwoPaymentsThatTogetherPassTheBalanceAreNotBothAccepted() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // the same key twice at once: one is created, the other is the replay
            String key = key();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> same = () -> {
                go.await();
                return callKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 100000, "CASH", false, null), key)
                        .andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Future<Integer> one = pool.submit(same);
            java.util.concurrent.Future<Integer> two = pool.submit(same);
            go.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(one.get(), two.get()));
            assertThat(statuses).containsExactlyInAnyOrder(200, 201);
            assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(1);
            // two different payments of Rs 2,000 at once against a balance of Rs 2,000: one wins, the other is over the balance
            java.util.concurrent.CountDownLatch go2 = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> part = () -> {
                go2.await();
                return callKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().owner(), paymentBody("PART_PAYMENT", 200000, "CASH", false, null), key())
                        .andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Future<Integer> three = pool.submit(part);
            java.util.concurrent.Future<Integer> four = pool.submit(part);
            go2.countDown();
            assertThat(new ArrayList<>(List.of(three.get(), four.get()))).containsExactlyInAnyOrder(201, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(account(rec.clinic(), course).get("balancePaise").asLong()).isZero();
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(2);
        assertThat(rows(rec.clinic(), "select receipt_no::text from billing.course_payment order by receipt_no")).extracting(r -> r.get(0)).containsExactly("1", "2");
    }

    // ================= who may do what =================

    @Test
    void onlyTheOwnerAndTheFrontDeskCollectTheBillingRoleReadsAndEveryOtherRoleIsRefusedAndNothingIsRecorded() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        String body = paymentBody("ADVANCE", 100000, "CASH", false, null);
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problemKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().as(role), body, key(), 403, "forbidden");
        }
        problemKeyed(HttpMethod.POST, paymentsPath(course), anonymous(), body, key(), 401, "unauthorized");
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isZero();
        for (String role : new String[] {"OWNER", "FRONT_DESK"}) {
            assertThat(okKeyed(HttpMethod.POST, paymentsPath(course), rec.clinic().as(role), body, key(), 201).get("account").get("canCollect").asBoolean()).as(role).isTrue();
        }
        assertThat(count(rec.clinic(), "select count(*) from billing.course_payment")).isEqualTo(2);
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING"}) {
            JsonNode view = get(accountPath(course), rec.clinic().as(role));
            assertThat(view.get("canCollect").asBoolean()).as(role).isEqualTo("OWNER".equals(role) || "FRONT_DESK".equals(role));
            assertThat(view.get("paidPaise").asLong()).as(role).isEqualTo(200000L);
        }
        for (String role : new String[] {"NURSE", "PHARMACIST", "COUNSELLOR"}) {
            problemOf(HttpMethod.GET, accountPath(course), rec.clinic().as(role), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, accountPath(course), anonymous(), null, 401, "unauthorized");
    }

    @Test
    void aNurseMemberCannotCollectOrReadTheAccountAndAFrontDeskMemberCollectsAndIsRecordedAsTheCollector() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        Staff nurse = addNonDoctor(rec.clinic(), "NURSE");
        problemKeyed(HttpMethod.POST, paymentsPath(course), nurse.token(rec.clinic()), paymentBody("ADVANCE", 100000, "CASH", false, null), key(), 403, "forbidden");
        problemOf(HttpMethod.GET, accountPath(course), nurse.token(rec.clinic()), null, 403, "forbidden");
        Staff desk = addNonDoctor(rec.clinic(), "FRONT_DESK");
        assertThat(okKeyed(HttpMethod.POST, paymentsPath(course), desk.token(rec.clinic()), paymentBody("ADVANCE", 100000, "CASH", false, null), key(), 201).get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(rows(rec.clinic(), "select recorded_by::text from billing.course_payment").get(0).get(0)).isEqualTo(desk.userId().toString());
    }

    // ================= receipts =================

    @Test
    void receiptNumbersAreOnePerClinicAcrossItsCoursesAndEveryClinicStartsAtOne() throws Exception {
        Rec a = newRec();
        JsonNode first = threeSittingCourse(a);
        JsonNode second = threeSittingCourse(a);
        assertThat(collect(a.clinic(), first, "ADVANCE", 100000, "CASH").get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(collect(a.clinic(), second, "ADVANCE", 100000, "CASH").get("receiptNo").asText()).isEqualTo("RC-2");
        assertThat(collect(a.clinic(), first, "ADVANCE", 100000, "CASH").get("receiptNo").asText()).isEqualTo("RC-3");
        assertThat(rows(a.clinic(), "select receipt_no::text, course_id::text from billing.course_payment order by receipt_no")).hasSize(3);
        assertThat(count(a.clinic(), "select last_no from billing.receipt_counter")).isEqualTo(3);
        Rec b = newRec();
        assertThat(collect(b.clinic(), threeSittingCourse(b), "ADVANCE", 100000, "CASH").get("receiptNo").asText()).isEqualTo("RC-1");
        // a refused payment takes no number
        problemKeyed(HttpMethod.POST, paymentsPath(first), a.clinic().owner(), paymentBody("FULL_BALANCE", 99999900, "CASH", false, null), key(), 400, "amount-over-balance");
        assertThat(collect(a.clinic(), first, "ADVANCE", 100000, "CASH").get("receiptNo").asText()).isEqualTo("RC-4");
    }

    @Test
    void aReceiptHasTheLetterheadPatientCourseTypeModeAgainstAmountCollectorAndTheBalanceAfterIt() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        collect(rec.clinic(), course, "PART_PAYMENT", 100000, "CASH");
        collect(rec.clinic(), course, "FULL_BALANCE", 200000, "UPI");
        String path = BILLING + "/courses/" + course.get("id").asText() + "/receipts/";
        JsonNode one = get(path + "1", rec.clinic().owner());
        assertThat(one.get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(one.get("clinicName").asText()).isEqualTo("Sahaj test clinic");
        assertThat(one.get("date").asText()).isNotBlank();
        assertThat(one.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(one.has("uhid")).isTrue();
        assertThat(one.get("courseNo").asText()).isEqualTo("TC-0001");
        assertThat(one.get("courseName").asText()).isEqualTo("Dental treatment course");
        assertThat(one.get("type").asText()).isEqualTo("PART_PAYMENT");
        assertThat(one.get("mode").asText()).isEqualTo("CASH");
        assertThat(one.get("against").asText()).isEqualTo("Course balance");
        assertThat(one.get("amountPaise").asLong()).isEqualTo(100000L);
        assertThat(one.get("collectedByName").asText()).isNotBlank();
        assertThat(one.get("balanceAfterPaise").asLong()).as("estimate 3,000 less paid so far 1,000").isEqualTo(200000L);
        JsonNode two = get(path + "2", rec.clinic().owner());
        assertThat(two.get("receiptNo").asText()).isEqualTo("RC-2");
        assertThat(two.get("type").asText()).isEqualTo("FULL_BALANCE");
        assertThat(two.get("against").asText()).isEqualTo("Whole course");
        assertThat(two.get("balanceAfterPaise").asLong()).isZero();
        // who may read a receipt
        for (String role : new String[] {"OWNER", "FRONT_DESK", "BILLING"}) {
            assertThat(get(path + "1", rec.clinic().as(role)).get("receiptNo").asText()).as(role).isEqualTo("RC-1");
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "PHARMACIST", "COUNSELLOR"}) {
            problemOf(HttpMethod.GET, path + "1", rec.clinic().as(role), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, path + "1", anonymous(), null, 401, "unauthorized");
        // a receipt that is not there, the receipt of another course, a bad number
        problemOf(HttpMethod.GET, path + "99", rec.clinic().owner(), null, 404, "receipt-not-found");
        JsonNode other = threeSittingCourse(rec);
        problemOf(HttpMethod.GET, BILLING + "/courses/" + other.get("id").asText() + "/receipts/1", rec.clinic().owner(), null, 404, "receipt-not-found");
        problemOf(HttpMethod.GET, BILLING + "/courses/" + UUID.randomUUID() + "/receipts/1", rec.clinic().owner(), null, 404, "course-not-found");
        assertThat(call(HttpMethod.GET, path + "abc", rec.clinic().owner(), null).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, path + "0", rec.clinic().owner(), null).andReturn().getResponse().getStatus()).isIn(400, 404);
    }

    // ================= day closing and revenue =================

    @Test
    void courseCashIsInTheDayClosingsExpectedCashAndCourseMoneyIsInTheRevenueByModeAndTheCashInTheDrawer() throws Exception {
        Rec rec = newRec();
        JsonNode course = threeSittingCourse(rec);
        collect(rec.clinic(), course, "ADVANCE", 100000, "CASH");
        collect(rec.clinic(), course, "ADVANCE", 50000, "UPI");
        collect(rec.clinic(), course, "ADVANCE", 30000, "CARD");
        JsonNode revenue = ok(HttpMethod.GET, BILLING + "/revenue?range=TODAY", rec.clinic().owner(), null, 200);
        assertThat(revenue.get("collectedPaise").asLong()).isEqualTo(180000L);
        assertThat(revenue.get("cashInDrawerPaise").asLong()).isEqualTo(100000L);
        long cash = 0;
        long upi = 0;
        long card = 0;
        for (JsonNode mode : revenue.get("modes")) {
            switch (mode.get("mode").asText()) {
                case "CASH" -> cash = mode.get("amountPaise").asLong();
                case "UPI" -> upi = mode.get("amountPaise").asLong();
                default -> card = mode.get("amountPaise").asLong();
            }
        }
        assertThat(new long[] {cash, upi, card}).containsExactly(100000L, 50000L, 30000L);
        long today = 0;
        for (JsonNode day : revenue.get("days")) {
            today += day.get("collectedPaise").asLong();
        }
        assertThat(today).isEqualTo(180000L);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=WEEK", rec.clinic().owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(180000L);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=MONTH", rec.clinic().owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(180000L);
        // the closing: only the cash is expected in the drawer
        JsonNode closed = ok(HttpMethod.POST, BILLING + "/day-closing", rec.clinic().owner(), "{\"countedCashPaise\":100000,\"note\":null}", 201);
        assertThat(closed.get("expectedCashPaise").asLong()).isEqualTo(100000L);
        assertThat(closed.get("diffPaise").asLong()).isZero();
        // a visit payment in cash adds to the same drawer
        Clinic other = newClinic();
        Staff doctor = addDoctor(other, "General physician");
        JsonNode visit = issueVisit(other, doctor.membershipId());
        ok(HttpMethod.POST, BILLING + "/bills/" + visit.get("id").asText() + "/payments", other.owner(), "{\"mode\":\"CASH\",\"amountPaise\":10000}", 200);
        Rec twin = startRecord(other, doctor);
        collect(other, threeSittingCourse(twin), "ADVANCE", 70000, "CASH");
        assertThat(ok(HttpMethod.POST, BILLING + "/day-closing", other.owner(), "{\"countedCashPaise\":80000,\"note\":null}", 201).get("expectedCashPaise").asLong()).isEqualTo(80000L);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=TODAY", other.owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(80000L);
    }

    @Test
    void theTodayScreensCollectedFigureIncludesCoursePaymentsSoItMatchesRevenueTodayAndTheDayClosing() throws Exception {
        // DEC-044 point 3 (human, 12 Nov 2026): GET /billing/today-summary "collected" now includes course payments, so
        // the Today screen, Revenue TODAY and the day closing show the same figure. It replaces the hand-over behaviour
        // of SAH-58 (course payments left out), which this test pinned before.
        Rec rec = newRec();
        collect(rec.clinic(), threeSittingCourse(rec), "ADVANCE", 100000, "CASH");
        long today = ok(HttpMethod.GET, BILLING + "/today-summary", rec.clinic().owner(), null, 200).get("collectedPaise").asLong();
        assertThat(today).isEqualTo(100000L);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=TODAY", rec.clinic().owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(today);
        // a visit payment adds to the same figure
        JsonNode visit = issueVisit(rec.clinic(), rec.clinic().doctorMembershipId());
        ok(HttpMethod.POST, BILLING + "/bills/" + visit.get("id").asText() + "/payments", rec.clinic().owner(), "{\"mode\":\"UPI\",\"amountPaise\":25000}", 200);
        assertThat(ok(HttpMethod.GET, BILLING + "/today-summary", rec.clinic().owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(125000L);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=TODAY", rec.clinic().owner(), null, 200).get("collectedPaise").asLong()).isEqualTo(125000L);
    }

    @Test
    void anotherClinicsCoursePaymentsNeverAddToTheTodayFigure() throws Exception {
        Rec a = newRec();
        collect(a.clinic(), threeSittingCourse(a), "ADVANCE", 100000, "CASH");
        Clinic b = newClinic();
        assertThat(ok(HttpMethod.GET, BILLING + "/today-summary", b.owner(), null, 200).get("collectedPaise").asLong()).isZero();
    }

    // ================= tenant isolation, audit and privacy =================

    @Test
    void anotherClinicNeverSeesAnAccountAReceiptOrALedgerAndCannotPayAgainstAForeignCourse() throws Exception {
        Rec a = newRec();
        JsonNode course = threeSittingCourse(a);
        collect(a.clinic(), course, "ADVANCE", 100000, "CASH");
        Rec b = newRec();
        problemOf(HttpMethod.GET, accountPath(course), b.clinic().owner(), null, 404, "course-not-found");
        problemKeyed(HttpMethod.POST, paymentsPath(course), b.clinic().owner(), paymentBody("ADVANCE", 100000, "CASH", false, null), key(), 404, "course-not-found");
        problemOf(HttpMethod.GET, BILLING + "/courses/" + course.get("id").asText() + "/receipts/1", b.clinic().owner(), null, 404, "course-not-found");
        assertThat(count(b.clinic(), "select count(*) from billing.course_payment")).isZero();
        assertThat(count(b.clinic(), "select count(*) from billing.receipt_counter")).isZero();
        assertThat(count(a.clinic(), "select count(*) from billing.course_payment")).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, BILLING + "/revenue?range=TODAY", b.clinic().owner(), null, 200).get("collectedPaise").asLong()).isZero();
    }

    @Test
    void thePaymentAuditCarriesTheCourseTheReceiptTypeModeAndAmountOnlyAndNoPatientNameOrItem() throws Exception {
        Rec rec = newRec();
        JsonNode course = newCourse(rec, "DENTAL", "[{\"addOptionKey\":\"opd_sitting\",\"chargePaise\":100000,\"items\":[\"Secret item ZXQ\"]}]");
        collect(rec.clinic(), course, "PART_PAYMENT", 50000, "UPI");
        List<List<String>> audits = auditRows(rec.clinic(), "COURSE_PAYMENT_RECORDED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(0)).isEqualTo("TREATMENT_COURSE");
        JsonNode after = objectMapper.readTree(audits.get(0).get(2));
        assertThat(after.get("receiptNo").asText()).isEqualTo("RC-1");
        assertThat(after.get("type").asText()).isEqualTo("PART_PAYMENT");
        assertThat(after.get("mode").asText()).isEqualTo("UPI");
        assertThat(after.get("amountPaise").asLong()).isEqualTo(50000L);
        assertThat(after.size()).isEqualTo(4);
        assertThat(audits.get(0).get(3)).isEqualTo(rec.clinic().ownerUserId().toString());
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain("Ramesh").doesNotContain("ZXQ").doesNotContain("Kulkarni");
    }
}
