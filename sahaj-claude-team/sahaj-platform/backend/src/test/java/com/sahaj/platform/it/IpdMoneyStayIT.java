package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * GO-LIVE QA-1 row "IpdMoneyStayIT" (docs/reviews/GO-LIVE-ipd-test-gaps.md F7, F10) on the real {@code sahaj_test} database: the
 * money of one stay tested across the whole stay and not step by step. The running bill is the sum of its lines at every step
 * (admission, extra charges, deposits, a bed move, the discharge sheet) and the detail, the list row and the discharge sheet say the
 * same total; the final bill is the running bill frozen, and deposits plus balance equal the total; a refund is the owner's and is
 * stored negative; an Insurance or Corporate patient is never "deposit low"; deposits that arrive at the same moment are all kept.
 *
 * <p>The bed day is cut after 24 hours and admissions are moved back by whole 24-hour periods plus 30 minutes, so the days of a stay
 * never depend on the hour or the IST day the test runs (no {@code assumeTrue} on the time of day, F10). Test plan:
 * docs/test-plans/IPD-go-live.md.
 */
@OnTestDatabase
class IpdMoneyStayIT extends IpdJourneySupport {

    private Clinic clinic24h() throws Exception {
        Clinic clinic = hospital();
        setCharges(clinic, "24h", NURSING, VISIT);
        setRules(clinic, true, "same");
        return clinic;
    }

    /** The same one bill in the detail, the list row and the discharge sheet, and the lines add up to it. */
    private long oneBill(Clinic clinic, String id, String when) throws Exception {
        JsonNode d = detail(clinic, id);
        long total = d.get("bill").get("totalPaise").asLong();
        assertBillAddsUp(d.get("bill"));
        assertThat(d.get("runningBillPaise").asLong()).as(when + ": detail runningBillPaise").isEqualTo(total);
        assertThat(row(list(clinic), id).get("runningBillPaise").asLong()).as(when + ": list row").isEqualTo(total);
        assertThat(totalOf(sheet(clinic, id))).as(when + ": discharge sheet").isEqualTo(total);
        long lines = 0;
        for (JsonNode l : d.get("bill").get("lines")) {
            lines += l.get("unitPaise").asLong() * l.get("qty").asInt();
        }
        assertThat(lines).as(when + ": sum of unit x quantity").isEqualTo(total);
        return total;
    }

    @Test
    void theRunningBillIsTheSumOfItsLinesAndTheSameEverywhereAtEveryStepOfAStay() throws Exception {
        Clinic clinic = clinic24h();
        String id = admitAtDay(clinic, "gen", 3, "24h").get("id").asText();
        long total = oneBill(clinic, id, "admitted");
        assertThat(total).isEqualTo(total(current("gen", 3), nursing(3)));

        charge(clinic, id, "xray");
        total = oneBill(clinic, id, "after a chest X-ray");
        assertThat(total).isEqualTo(total(current("gen", 3), nursing(3)) + 45_000);

        charge(clinic, id, "cbc");
        charge(clinic, id, "cbc");
        total = oneBill(clinic, id, "after two CBC");
        assertThat(total).isEqualTo(total(current("gen", 3), nursing(3)) + 45_000 + 2 * 30_000);
        assertThat(billLine(detail(clinic, id).get("bill"), "CBC").get("qty").asInt()).isEqualTo(2);

        deposit(clinic, id, 500_000);
        assertThat(oneBill(clinic, id, "after a deposit")).as("a deposit never changes the bill").isEqualTo(total);

        moveOk(clinic, id, newBed(clinic, "semi"));
        long afterMove = oneBill(clinic, id, "after the move to a semi-private bed");
        // the extra services are priced at base times the multiplier of the bed category at billing time (Semi-private 1.25)
        assertThat(afterMove).isEqualTo(total(before("gen", 2), current("semi", 1), nursing(3)) + 56_250 + 2 * 37_500);
        assertBill(withoutExtras(detail(clinic, id).get("bill")), before("gen", 2), current("semi", 1), nursing(3));

        charge(clinic, id, "dressing");
        assertThat(oneBill(clinic, id, "after a dressing")).isEqualTo(afterMove + 50_000);
    }

    /** The bill lines of the stay itself: the extra services (X-ray, CBC) taken out, to compare with the expected stay lines. */
    private JsonNode withoutExtras(JsonNode bill) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = bill.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode lines = copy.putArray("lines");
        long total = 0;
        for (JsonNode l : bill.get("lines")) {
            String name = l.get("name").asText();
            if (!name.equals("Chest X-ray") && !name.equals("CBC") && !name.equals("Major dressing")) {
                lines.add(l);
                total += l.get("amountPaise").asLong();
            }
        }
        copy.put("totalPaise", total);
        return copy;
    }

    @Test
    void theFinalBillIsTheRunningBillFrozenAndDepositsPlusBalanceAreTheTotalForACollectionARefundAndASettlement() throws Exception {
        Clinic clinic = clinic24h();
        String[] outcomes = {"collected", "refunded", "settled"};
        long[] days = {10, 1, 6};
        for (int i = 0; i < 3; i++) {
            String id = admitAtDay(clinic, "gen", (int) days[i], "24h").get("id").asText();
            if (i == 0) {
                deposit(clinic, id, 200_000);
                charge(clinic, id, "xray");
            }
            if (i == 2) {
                charge(clinic, id, "dressing"); // 6 x 1,600 + 400 = 10,000 = the deposit
            }
            signNormal(clinic, id);
            JsonNode s = sheet(clinic, id);
            long total = totalOf(s);
            long deposits = depositsOf(s);
            long balance = s.get("bill").get("balancePaise").asLong();
            assertThat(balance).as(outcomes[i] + ": balance is total minus deposits").isEqualTo(total - deposits);
            List<String> running = lineKeys(detail(clinic, id).get("bill"));
            assertThat(totalOf(s)).as("the sheet shows the running bill total").isEqualTo(detail(clinic, id).get("bill").get("totalPaise").asLong());

            JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(),
                    dischargeBody("normal", "general", total, deposits, balance == 0 ? null : "Cash"), 200);
            JsonNode fb = d.get("finalBill");
            assertThat(fb.get("outcome").asText()).isEqualTo(outcomes[i]);
            assertThat(fb.get("totalPaise").asLong()).isEqualTo(total);
            assertThat(fb.get("depositsPaise").asLong()).isEqualTo(deposits);
            assertThat(fb.get("balancePaise").asLong()).isEqualTo(balance);
            assertThat(fb.get("totalPaise").asLong()).as("total = deposits + balance (a refund is a negative balance)")
                    .isEqualTo(fb.get("depositsPaise").asLong() + fb.get("balancePaise").asLong());
            assertThat(lineKeys(fb)).as("the final bill is the running bill").isEqualTo(running);
            assertThat(storedLines(clinic, id)).as("and is stored as shown").isEqualTo(running);
            assertBillAddsUp(fb);
            assertThat(values(clinic, "select (total_paise = deposits_paise + balance_paise)::text from ipd.final_bill where admission_id = ?::uuid", id))
                    .containsExactly("true");
            long sumOfStoredLines = count(clinic, "select coalesce(sum(l.amount_paise), 0) from ipd.final_bill_line l join ipd.final_bill b "
                    + "on b.tenant_id = l.tenant_id and b.id = l.final_bill_id where b.admission_id = ?::uuid", id);
            assertThat(sumOfStoredLines).as("the stored lines add up to the stored total").isEqualTo(total);
        }
    }

    @Test
    void aRefundIsTheOwnersAndIsStoredNegative() throws Exception {
        Clinic clinic = clinic24h();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        JsonNode s = sheet(clinic, id);
        long total = totalOf(s);
        long deposits = depositsOf(s);
        assertThat(total - deposits).isNegative();
        assertThat(s.get("canRefund").asBoolean()).isTrue();
        Written untouched = written(clinic, id);
        String body = dischargeBody("normal", "general", total, deposits, "Cash");
        for (String role : new String[] {"FRONT_DESK"}) {
            JsonNode p = problem(postDischarge(staff(clinic, role).auth(), id, body), 403);
            assertType(p, "refund-needs-owner");
            assertThat(p.get("detail").asText()).isEqualTo(ONLY_OWNER_REFUNDS);
        }
        for (String role : NON_DISCHARGERS) {
            problem(postDischarge(clinic.as(role), id, body), 403);
        }
        assertThat(written(clinic, id)).as("a refused refund writes nothing").isEqualTo(untouched);
        // the owner needs a mode to refund
        JsonNode noMode = problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", total, deposits, null)), 400);
        assertFieldError(noMode, "mode");
        assertThat(written(clinic, id)).isEqualTo(untouched);

        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), body, 200);
        assertThat(d.get("finalBill").get("balancePaise").asLong()).isEqualTo(total - deposits);
        assertThat(d.get("finalBill").get("outcome").asText()).isEqualTo("refunded");
        assertThat(values(clinic, "select balance_paise::text, settle_mode, created_by::text from ipd.final_bill where admission_id = ?::uuid", id))
                .containsExactly(String.valueOf(total - deposits), "Cash", clinic.ownerUserId().toString());
    }

    @Test
    void anInsuranceOrCorporatePatientIsNeverDepositLowHoweverBigTheBill() throws Exception {
        Clinic clinic = clinic24h();
        List<String> ids = new ArrayList<>();
        for (String payer : new String[] {"Insurance", "Corporate"}) {
            JsonNode a = admit(clinic, "icu", payer, 0);
            String id = a.get("id").asText();
            ids.add(id);
            assertThat(a.get("depositState").asText()).isEqualTo("NOT_NEEDED");
            backdateToBillDays(clinic, id, 20, "24h");
            charge(clinic, id, "xray");
            JsonNode d = detail(clinic, id);
            assertThat(d.get("bill").get("totalPaise").asLong()).as("a large bill").isGreaterThan(10_000_000L);
            assertThat(d.get("depositLow").asBoolean()).as(payer + " detail").isFalse();
            assertThat(d.get("depositState").asText()).isEqualTo("NOT_NEEDED");
            assertThat(row(list(clinic), id).get("depositLow").asBoolean()).as(payer + " list").isFalse();
            assertThat(list(clinic).get("depositAlerts").asInt()).as("no deposit alert counted for " + payer).isZero();
        }
        // a Self patient with the same bill is low
        String self = admit(clinic, "icu", "Self", 2_000_000).get("id").asText();
        backdateToBillDays(clinic, self, 20, "24h");
        assertThat(detail(clinic, self).get("depositLow").asBoolean()).as("the control: a Self patient with a big bill is low").isTrue();
        assertThat(ids).hasSize(2);
    }

    @Test
    void depositsThatArriveAtTheSameMomentAreAllKeptAndAddUp() throws Exception {
        Clinic clinic = clinic24h();
        String id = admitAtDay(clinic, "gen", 2, "24h").get("id").asText();
        long before = oneBill(clinic, id, "before the deposits");
        long[] amounts = {100_000, 200_000, 300_000, 400_000, 500_000};
        List<Req> requests = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            requests.add(new Req(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", i % 2 == 0 ? clinic.owner() : clinic.as("FRONT_DESK"),
                    "{\"amountPaise\":" + amounts[i] + ",\"mode\":\"" + (i % 2 == 0 ? "UPI" : "Cash") + "\"}"));
        }
        for (Resp r : parallel(requests.toArray(new Req[0]))) {
            assertThat(r.status()).as(r.body()).isEqualTo(200);
        }
        long sum = 0;
        for (long a : amounts) {
            sum += a;
        }
        assertThat(count(clinic, "select coalesce(sum(amount_paise), 0) from ipd.deposit where admission_id = ?::uuid", id))
                .isEqualTo(DEPOSIT + sum);
        assertThat(rows(clinic, "deposit")).isEqualTo(1 + amounts.length);
        assertThat(audits(clinic, "IPD_DEPOSIT_COLLECTED")).isEqualTo(amounts.length);
        JsonNode d = detail(clinic, id);
        assertThat(d.get("depositPaise").asLong()).isEqualTo(DEPOSIT + sum);
        assertThat(d.get("deposits")).hasSize(1 + amounts.length);
        assertThat(oneBill(clinic, id, "after the deposits")).as("deposits do not move the bill").isEqualTo(before);
        assertThat(depositsOf(sheet(clinic, id))).isEqualTo(DEPOSIT + sum);
    }
}
