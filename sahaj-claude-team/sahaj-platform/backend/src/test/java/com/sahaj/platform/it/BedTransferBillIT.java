package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-26 segment maths and the split bill on the real {@code sahaj_test} database: the examples of AC 4 for both
 * bed-day cuts and both "New bed rate starts" rules with admissions moved back in time ({@link #backdate}), the exact
 * bill lines and their order of AC 5, rounding of the old category's visit charge, rates read at billing time, and the
 * one bill behind the sheet, the list, the board tile and the Deposit low pill. Test plan: docs/test-plans/SAH-26.md.
 *
 * <p>Time is moved by backdating {@code admitted_at}, never forwards: a "later" transfer is simulated by moving the
 * admission further into the past before the next move. The stored segment days stay what they were at the first move.
 */
@OnTestDatabase
class BedTransferBillIT extends BedTransferSupport {

    // ---------------- AC 4: the examples ----------------

    @Test
    void movedGeneralToPrivateOnDayThreeGivesGeneralTwoDaysSameDayAndThreeDaysNextDay() throws Exception {
        Clinic clinic = hospital();
        for (String rate : new String[] {"same", "next"}) {
            setRules(clinic, true, rate);
            LocalDate day = istToday();
            String id = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
            JsonNode answer = moveOk(clinic, id, newBed(clinic, "pvt"));
            JsonNode reread = detail(clinic, id);
            assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

            int segment = "same".equals(rate) ? 2 : 3;
            int onPrivate = 3 - segment;
            for (JsonNode d : new JsonNode[] {answer, reread}) {
                JsonNode bill = d.get("bill");
                assertBill(bill, before("gen", segment), current("pvt", onPrivate), nursing(3));
                assertThat(bill.get("billDays").asInt()).as(rate).isEqualTo(3);
                assertThat(bill.get("categoryName").asText()).isEqualTo("Private");
                assertMultiplier(bill, "1.50");
                assertThat(d.get("dayNumber").asInt()).isEqualTo(3);
            }
            assertThat(segmentDays(clinic, id)).as(rate).containsExactly(String.valueOf(segment));
            if ("same".equals(rate)) {
                // General 2 days, Private from day 3
                assertThat(lineNames(answer.get("bill"))).containsExactly("Bed · General (before transfer)",
                        "Doctor visits · General rate", "Bed · Private", DOCTOR_LINE, "Nursing care");
            } else {
                // General 3 days, Private from day 4: no Private line yet
                assertThat(lineNames(answer.get("bill"))).containsExactly("Bed · General (before transfer)",
                        "Doctor visits · General rate", "Nursing care");
            }
        }
    }

    @Test
    void movedOnDayOneSameDayBillsTheWholeFirstDayAtTheNewCategoryAndNextDayKeepsItOnTheOldOne() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day0 = istToday();
        String same = quickAdmit(clinic).get("id").asText();
        JsonNode sameAnswer = moveOk(clinic, same, newBed(clinic, "pvt"));
        assumeTrue(day0.equals(istToday()), "IST midnight passed while the test ran");
        assertBill(sameAnswer.get("bill"), current("pvt", 1), nursing(1));
        assertThat(lineNames(sameAnswer.get("bill"))).noneMatch(n -> n.contains("before transfer"));
        assertThat(segmentDays(clinic, same)).containsExactly("0");
        assertThat(sameAnswer.get("runningBillPaise").asLong()).isEqualTo(250_000 + 75_000 + 30_000);

        setRules(clinic, true, "next");
        String next = quickAdmit(clinic).get("id").asText();
        JsonNode nextAnswer = moveOk(clinic, next, newBed(clinic, "pvt"));
        assumeTrue(day0.equals(istToday()), "IST midnight passed while the test ran");
        // the first day stays General, the Private lines have no day yet and are not shown
        assertBill(nextAnswer.get("bill"), before("gen", 1), nursing(1));
        assertThat(segmentDays(clinic, next)).containsExactly("1");

        // one day later (admission moved back a day) the new rate has started
        LocalDate day = istToday();
        backdateToBillDays(clinic, next, 2, "midnight");
        JsonNode tomorrow = detail(clinic, next);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertBill(tomorrow.get("bill"), before("gen", 1), current("pvt", 1), nursing(2));
    }

    // ---------------- AC 4: both cuts, both rules, many days, upgrade and downgrade ----------------

    @Test
    void segmentDaysFollowTheFormulaForBothCutsBothRulesAndAllCategoryDirections() throws Exception {
        for (String cut : new String[] {"midnight", "24h"}) {
            for (String rate : new String[] {"same", "next"}) {
                Clinic clinic = hospital();
                setCharges(clinic, cut, NURSING, VISIT);
                setRules(clinic, true, rate);
                for (int n : new int[] {1, 2, 3, 5}) {
                    for (String[] pair : new String[][] {{"gen", "pvt"}, {"pvt", "gen"}}) {
                        LocalDate day = istToday();
                        String id = admitAtDay(clinic, pair[0], n, cut).get("id").asText();
                        JsonNode answer = moveOk(clinic, id, newBed(clinic, pair[1]));
                        JsonNode reread = detail(clinic, id);
                        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
                        String label = cut + "/" + rate + "/day " + n + "/" + pair[0] + " to " + pair[1];

                        int segment = "same".equals(rate) ? n - 1 : n;
                        assertThat(answer.get("bill").get("billDays").asInt()).as(label).isEqualTo(n);
                        assertThat(answer.get("bill").get("bedDayCut").asText()).isEqualTo(cut);
                        assertBill(answer.get("bill"), before(pair[0], segment), current(pair[1], n - segment),
                                nursing(n));
                        assertBill(reread.get("bill"), before(pair[0], segment), current(pair[1], n - segment),
                                nursing(n));
                        assertThat(segmentDays(clinic, id)).as(label).containsExactly(String.valueOf(segment));
                    }
                }
            }
        }
    }

    @Test
    void aSecondMoveOnTheSameDayFixesOnlyTheDaysNotYetMovedToEarlierSegments() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();

        // "Same day" both times: n = 3, p = 2, c = 1, segment = 0 (the second bed was only there for the same day)
        setRules(clinic, true, "same");
        String a = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        moveOk(clinic, a, newBed(clinic, "semi"));
        JsonNode sameDay = moveOk(clinic, a, newBed(clinic, "pvt"));
        assertBill(sameDay.get("bill"), before("gen", 2), current("pvt", 1), nursing(3));
        assertThat(segmentDays(clinic, a)).containsExactly("2", "0");

        // "Next day" both times: the first segment takes all 3 days, the second has none left (c = max(0, 3 - 3) = 0)
        setRules(clinic, true, "next");
        String b = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        moveOk(clinic, b, newBed(clinic, "semi"));
        JsonNode nextBoth = moveOk(clinic, b, newBed(clinic, "pvt"));
        assertBill(nextBoth.get("bill"), before("gen", 3), nursing(3));
        assertThat(segmentDays(clinic, b)).containsExactly("3", "0");

        // The rule in force at each move is the one used: Same day first (segment 2), Next day second (c = 1 -> 1)
        setRules(clinic, true, "same");
        String c = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        moveOk(clinic, c, newBed(clinic, "semi"));
        setRules(clinic, true, "next");
        JsonNode mixed = moveOk(clinic, c, newBed(clinic, "pvt"));
        assertBill(mixed.get("bill"), before("gen", 2), before("semi", 1), nursing(3));
        assertThat(segmentDays(clinic, c)).containsExactly("2", "1");
        assertThat(values(clinic, "select string_agg(new_rate_from, ',' order by transferred_at, id) "
                + "from ipd.bed_transfer where admission_id = ?::uuid", c)).containsExactly("same,next");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
    }

    @Test
    void aLaterMoveWithEarlierSegmentsTakesOnlyTheDaysSpentOnTheCurrentBed() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        setRules(clinic, true, "same");
        String id = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();

        // day 3: General -> Semi-private, General keeps 2 days
        JsonNode first = moveOk(clinic, id, newBed(clinic, "semi"));
        assertBill(first.get("bill"), before("gen", 2), current("semi", 1), nursing(3));

        // day 6: n = 6, p = 2, c = 4, Semi-private keeps 3 days, Private gets day 6
        backdateToBillDays(clinic, id, 6, "midnight");
        JsonNode second = moveOk(clinic, id, newBed(clinic, "pvt"));
        assertBill(second.get("bill"), before("gen", 2), before("semi", 3), current("pvt", 1), nursing(6));
        assertThat(segmentDays(clinic, id)).containsExactly("2", "3");

        // day 8 with "Next day": n = 8, p = 5, c = 3, Private keeps all 3, ICU starts on day 9
        setRules(clinic, true, "next");
        backdateToBillDays(clinic, id, 8, "midnight");
        JsonNode third = moveOk(clinic, id, newBed(clinic, "icu"));
        assertBill(third.get("bill"), before("gen", 2), before("semi", 3), before("pvt", 3), nursing(8));
        assertThat(segmentDays(clinic, id)).containsExactly("2", "3", "3");

        // day 9: the ICU bed bills its first day
        backdateToBillDays(clinic, id, 9, "midnight");
        JsonNode reread = detail(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertBill(reread.get("bill"), before("gen", 2), before("semi", 3), before("pvt", 3), current("icu", 1),
                nursing(9));
        assertThat(reread.get("bill").get("billDays").asInt()).isEqualTo(9);
        assertThat(reread.get("dayNumber").asInt()).isEqualTo(9);
        // 2 x 130000 + 3 x 212500 + 3 x 325000 + 600000 + 9 x 30000
        assertThat(reread.get("bill").get("totalPaise").asLong())
                .isEqualTo(2 * 130_000 + 3 * 212_500 + 3 * 325_000 + 600_000 + 9 * 30_000);
    }

    @Test
    void movingBackToTheFirstBedAfterItIsCleanedIsAnotherTransferWithItsOwnSegment() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        String id = admitAtDay(clinic, "gen", 2, "midnight").get("id").asText();
        UUID first = bedOf(clinic, id);
        UUID other = newBed(clinic, "pvt");
        moveOk(clinic, id, other);
        ok(HttpMethod.POST, BEDS + "/" + first + "/ready", clinic.owner(), null, 200);
        backdateToBillDays(clinic, id, 4, "midnight");
        JsonNode back = moveOk(clinic, id, first);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        // first move at day 2: General 1 day; back at day 4: n = 4, p = 1, c = 3, Private keeps 2 days; General again day 4
        assertBill(back.get("bill"), before("gen", 1), before("pvt", 2), current("gen", 1), nursing(4));
        assertThat(segmentDays(clinic, id)).containsExactly("1", "2");
        assertThat(transferRows(clinic)).isEqualTo(2);
    }

    // ---------------- AC 5: rounding, extra charges, rates read at billing time ----------------

    @Test
    void theOldCategorysVisitChargeIsRoundedHalfUpToWholePaiseAfterTheMultiplier() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day = istToday();
        // 50003 x 1.5 = 75004.5 -> 75005 (half-even would give 75004); 50003 x 1.25 = 62503.75 -> 62504;
        // 50002 x 1.25 = 62502.5 -> 62503 (half-even 62502)
        String pvt = admitAtDay(clinic, "pvt", 3, "midnight").get("id").asText();
        String semi = admitAtDay(clinic, "semi", 3, "midnight").get("id").asText();
        setCharges(clinic, "midnight", NURSING, 50_003);
        JsonNode fromPvt = moveOk(clinic, pvt, newBed(clinic, "gen"));
        JsonNode fromSemi = moveOk(clinic, semi, newBed(clinic, "gen"));
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        assertBill(fromPvt.get("bill"),
                new L("Bed · Private (before transfer)", 250_000, 2, true),
                new L("Doctor visits · Private rate", 75_005, 2, true),
                new L("Bed · General", 80_000, 1, false),
                new L(DOCTOR_LINE, 50_003, 1, false),
                nursing(3));
        assertBill(fromSemi.get("bill"),
                new L("Bed · Semi-private (before transfer)", 150_000, 2, true),
                new L("Doctor visits · Semi-private rate", 62_504, 2, true),
                new L("Bed · General", 80_000, 1, false),
                new L(DOCTOR_LINE, 50_003, 1, false),
                nursing(3));
        assertThat(billLine(fromPvt.get("bill"), "Doctor visits · Private rate").get("amountPaise").asLong())
                .isEqualTo(150_010);

        setCharges(clinic, "midnight", NURSING, 50_002);
        assertLine(detail(clinic, semi).get("bill"), "Doctor visits · Semi-private rate", 62_503, 2);
    }

    @Test
    void extraChargesAreRepricedAtTheCurrentCategoryAndComeAfterNursing() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day = istToday();
        String id = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        charge(clinic, id, "xray");
        charge(clinic, id, "xray");
        charge(clinic, id, "ecg");
        JsonNode before = detail(clinic, id);
        assertLine(before.get("bill"), "Chest X-ray", 45_000, 2);

        JsonNode after = moveOk(clinic, id, newBed(clinic, "pvt"));
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        // OQ-26-1 (as the mockup): extra charges use the multiplier of the bed the patient is in now (Private 1.5)
        assertBill(after.get("bill"), before("gen", 2), current("pvt", 1), nursing(3),
                new L("Chest X-ray", 67_500, 2, false), new L("ECG", 45_000, 1, false));
        // the charge rows keep the base price
        assertThat(values(clinic, "select unit_paise::text from ipd.admission_charge where service_key = 'xray' limit 1"))
                .containsExactly("45000");
    }

    @Test
    void nursingCareIsOneLineForAllBillDaysNotSplitByCategory() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day = istToday();
        String id = admitAtDay(clinic, "gen", 5, "midnight").get("id").asText();
        moveOk(clinic, id, newBed(clinic, "semi"));
        JsonNode bill = detail(clinic, id).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        long nursingLines = 0;
        for (JsonNode line : bill.get("lines")) {
            if (line.get("name").asText().toLowerCase().contains("nursing")) {
                nursingLines++;
                assertThat(line.get("qty").asInt()).isEqualTo(5);
                assertThat(line.path("segment").asBoolean(false)).isFalse();
            }
        }
        assertThat(nursingLines).isOne();
    }

    @Test
    void ratesCurrentlySetInClinicSetupPriceEverySegmentLineOnTheNextRead() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day = istToday();
        String id = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        moveOk(clinic, id, newBed(clinic, "pvt"));

        setCategory(clinic, "gen", 90_000, "1.5");
        setCharges(clinic, "midnight", 40_000, 60_000);
        JsonNode bill = detail(clinic, id).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertBill(bill,
                new L("Bed · General (before transfer)", 90_000, 2, true),
                new L("Doctor visits · General rate", 90_000, 2, true),
                new L("Bed · Private", 250_000, 1, false),
                new L(DOCTOR_LINE, 90_000, 1, false),
                new L("Nursing care", 40_000, 3, false));
        // a change of the new bed's category rate changes the current lines only
        setCategory(clinic, "pvt", 300_000, "2");
        assertBill(detail(clinic, id).get("bill"),
                new L("Bed · General (before transfer)", 90_000, 2, true),
                new L("Doctor visits · General rate", 90_000, 2, true),
                new L("Bed · Private", 300_000, 1, false),
                new L(DOCTOR_LINE, 120_000, 1, false),
                new L("Nursing care", 40_000, 3, false));
    }

    // ---------------- list, board tile, Deposit low and the Deposit alerts tile ----------------

    @Test
    void theSheetTheListTheBoardTileAndDepositLowAllFollowTheTransfer() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, true, "same");
        LocalDate day = istToday();
        // X: General, ₹10,000 deposit, day 3 = ₹4,800 (not low); moved to ICU: 2 x 1,300 + 6,000 + 900 = ₹9,500 (low)
        String x = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        // Y: ICU, ₹20,000 deposit, day 3 = ₹18,900 (low); moved to General: 2 x 6,000 + 1,300 + 900 = ₹14,200 (not low)
        String y = admitAtDay(clinic, "icu", 3, "midnight").get("id").asText();
        JsonNode listBefore = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(row(listBefore, x).get("runningBillPaise").asLong()).isEqualTo(3 * DAY_ONE_GENERAL);
        assertThat(row(listBefore, x).get("depositLow").asBoolean()).isFalse();
        assertThat(row(listBefore, y).get("runningBillPaise").asLong()).isEqualTo(3 * 630_000);
        assertThat(row(listBefore, y).get("depositLow").asBoolean()).isTrue();
        assertThat(listBefore.get("depositAlerts").asInt()).isOne();

        UUID xOld = bedOf(clinic, x);
        UUID yOld = bedOf(clinic, y);
        UUID xNew = newBed(clinic, "icu");
        UUID yNew = newBed(clinic, "gen");
        JsonNode xAnswer = moveOk(clinic, x, xNew);
        JsonNode yAnswer = moveOk(clinic, y, yNew);

        JsonNode list = list(clinic);
        JsonNode board = board(clinic);
        JsonNode xDetail = detail(clinic, x);
        JsonNode yDetail = detail(clinic, y);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        long xTotal = total(before("gen", 2), current("icu", 1), nursing(3));
        long yTotal = total(before("icu", 2), current("gen", 1), nursing(3));
        assertThat(xTotal).isEqualTo(950_000);
        assertThat(yTotal).isEqualTo(1_420_000);
        for (JsonNode d : new JsonNode[] {xAnswer, xDetail}) {
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(xTotal);
            assertThat(d.get("bill").get("totalPaise").asLong()).isEqualTo(xTotal);
            assertThat(d.get("depositLow").asBoolean()).as("X low after the move").isTrue();
        }
        for (JsonNode d : new JsonNode[] {yAnswer, yDetail}) {
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(yTotal);
            assertThat(d.get("depositLow").asBoolean()).as("Y not low after the move").isFalse();
        }
        assertThat(row(list, x).get("runningBillPaise").asLong()).isEqualTo(xTotal);
        assertThat(row(list, x).get("depositLow").asBoolean()).isTrue();
        assertThat(row(list, y).get("runningBillPaise").asLong()).isEqualTo(yTotal);
        assertThat(row(list, y).get("depositLow").asBoolean()).isFalse();
        assertThat(list.get("depositAlerts").asInt()).as("the Deposit alerts tile follows the new bills").isOne();
        // one day number everywhere, unchanged by the move
        assertThat(row(list, x).get("dayNumber").asInt()).isEqualTo(3);
        assertThat(xDetail.get("dayNumber").asInt()).isEqualTo(3);
        assertThat(tile(board, xNew).get("dayNumber").asInt()).isEqualTo(3);
        assertThat(xDetail.get("bill").get("billDays").asInt()).isEqualTo(3);
        // the board: new beds carry the flag of the new bill; the old beds are Cleaning with no patient
        assertThat(tile(board, xNew).get("status").asText()).isEqualTo("occ");
        assertThat(tile(board, xNew).get("depositLow").asBoolean()).isTrue();
        assertThat(tile(board, yNew).get("depositLow").asBoolean()).isFalse();
        for (UUID old : new UUID[] {xOld, yOld}) {
            assertThat(tile(board, old).get("status").asText()).isEqualTo("clean");
            assertThat(absent(tile(board, old), "depositLow")).isTrue();
            assertThat(absent(tile(board, old), "patientName")).isTrue();
        }
        // every reader role sees the same rows
        for (String role : READERS) {
            JsonNode l = ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200);
            assertThat(row(l, x).get("runningBillPaise").asLong()).as(role).isEqualTo(xTotal);
            assertThat(l.get("depositAlerts").asInt()).as(role).isOne();
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + x, clinic.as(role), null, 200);
            assertBill(d.get("bill"), before("gen", 2), current("icu", 1), nursing(3));
        }
    }

    @Test
    void anInsurancePatientStaysNeverDepositLowAfterAMoveToAnExpensiveBed() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        JsonNode a = admit(clinic, "gen", "Insurance", 0);
        String id = a.get("id").asText();
        backdateToBillDays(clinic, id, 10, "midnight");
        JsonNode moved = moveOk(clinic, id, newBed(clinic, "icu"));
        JsonNode list = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(moved.get("depositLow").asBoolean()).isFalse();
        assertThat(row(list, id).get("depositLow").asBoolean()).isFalse();
        assertThat(list.get("depositAlerts").asInt()).isZero();
        assertBill(moved.get("bill"), before("gen", 9), current("icu", 1), nursing(10));
    }
}
