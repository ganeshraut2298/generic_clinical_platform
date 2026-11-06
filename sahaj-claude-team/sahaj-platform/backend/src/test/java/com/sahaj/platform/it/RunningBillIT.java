package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-25 running bill on the real {@code sahaj_test} database: exact paise math per category, rounding, bill days
 * for both bed-day cuts (admission times moved with {@link #backdate}), the Deposit low rule at its exact boundary,
 * the list, the tiles, the bed board and the detail bill block. Test plan: docs/test-plans/SAH-25.md.
 */
@OnTestDatabase
class RunningBillIT extends IpdBillingSupport {

    // ---------------- AC1 the lines and the money ----------------

    @Test
    void freshGeneralAdmissionBillsOneDayOfBedVisitAndNursingForExactly1600Rupees() throws Exception {
        Clinic clinic = hospital();
        JsonNode admitted = quickAdmit(clinic);
        String id = admitted.get("id").asText();

        for (JsonNode d : new JsonNode[] {admitted, detail(clinic, id)}) {
            JsonNode bill = d.get("bill");
            assertThat(lineNames(bill)).containsExactly("Bed · General", "Doctor visits · Dr Asha", "Nursing care");
            assertLine(bill, "Bed · General", 80_000, 1);
            assertLine(bill, "Doctor visits · Dr Asha", 50_000, 1);
            assertLine(bill, "Nursing care", 30_000, 1);
            assertThat(bill.get("totalPaise").asLong()).isEqualTo(160_000);
            assertThat(bill.get("billDays").asInt()).isOne();
            assertThat(bill.get("bedDayCut").asText()).isEqualTo("midnight");
            assertThat(bill.get("categoryName").asText()).isEqualTo("General");
            assertMultiplier(bill, "1.00");
            assertThat(bill.get("paidPaise").asLong()).isEqualTo(1_000_000);
            assertThat(bill.get("depositPercent").asInt()).isEqualTo(16);
            assertThat(bill.get("alertPercent").asInt()).isEqualTo(80);
            assertBillAddsUp(bill);
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(160_000);
            assertThat(d.get("depositLow").asBoolean()).isFalse();
        }
        JsonNode row = row(list(clinic), id);
        assertThat(row.get("runningBillPaise").asLong()).isEqualTo(160_000);
        assertThat(row.get("depositLow").asBoolean()).isFalse();
        assertThat(row.get("dayNumber").asInt()).isOne();
    }

    @Test
    void everyCategoryBillsItsOwnBedRateAndTheVisitChargeTimesTheMultiplier() throws Exception {
        Clinic clinic = hospital();
        // category, deposit that meets the minimum, bed, visit (50000 x multiplier), total, multiplier, name
        Object[][] cases = {
                {"gen", 1_000_000L, 80_000L, 50_000L, 160_000L, "1.00", "General"},
                {"semi", 1_200_000L, 150_000L, 62_500L, 242_500L, "1.25", "Semi-private"},
                {"pvt", 1_500_000L, 250_000L, 75_000L, 355_000L, "1.50", "Private"},
                {"icu", 2_000_000L, 500_000L, 100_000L, 630_000L, "2.00", "ICU"}};
        for (Object[] c : cases) {
            String category = (String) c[0];
            JsonNode a = admit(clinic, category, "Self", (Long) c[1]);
            JsonNode bill = detail(clinic, a.get("id").asText()).get("bill");
            String name = (String) c[6];
            assertThat(bill.get("categoryName").asText()).as(category).isEqualTo(name);
            assertMultiplier(bill, (String) c[5]);
            assertLine(bill, "Bed · " + name, (Long) c[2], 1);
            assertLine(bill, "Doctor visits · Dr Asha", (Long) c[3], 1);
            assertLine(bill, "Nursing care", 30_000, 1);
            assertThat(bill.get("totalPaise").asLong()).as(category + " total").isEqualTo((Long) c[4]);
            assertBillAddsUp(bill);
            assertThat(row(list(clinic), a.get("id").asText()).get("runningBillPaise").asLong()).isEqualTo((Long) c[4]);
        }
    }

    @Test
    void theVisitChargeIsRoundedHalfUpToWholePaiseAfterTheMultiplier() throws Exception {
        Clinic clinic = hospital();
        JsonNode semi = admit(clinic, "semi", "Self", 1_200_000);
        JsonNode pvt = admit(clinic, "pvt", "Self", 1_500_000);
        // 50002 x 1.25 = 62502.5 -> 62503 (half-even would give 62502); 50003 x 1.5 = 75004.5 -> 75005 (half-even 75004)
        setCharges(clinic, "midnight", 30_000, 50_002);
        assertLine(detail(clinic, semi.get("id").asText()).get("bill"), "Doctor visits · Dr Asha", 62_503, 1);
        setCharges(clinic, "midnight", 30_000, 50_003);
        assertLine(detail(clinic, pvt.get("id").asText()).get("bill"), "Doctor visits · Dr Asha", 75_005, 1);
        // 50003 x 1.25 = 62503.75 -> 62504
        JsonNode bill = detail(clinic, semi.get("id").asText()).get("bill");
        assertLine(bill, "Doctor visits · Dr Asha", 62_504, 1);
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(150_000 + 62_504 + 30_000);
        assertBillAddsUp(bill);
    }

    @Test
    void ratesSetInClinicSetupDriveTheBillOnTheNextReadWithNothingStored() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        assertThat(detail(clinic, id).get("bill").get("totalPaise").asLong()).isEqualTo(160_000);

        setCharges(clinic, "midnight", 45_000, 70_000);
        JsonNode bill = detail(clinic, id).get("bill");
        assertLine(bill, "Doctor visits · Dr Asha", 70_000, 1);
        assertLine(bill, "Nursing care", 45_000, 1);
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(80_000 + 70_000 + 45_000);

        // the bed category rate and multiplier of the bed the patient is in
        setCategory(clinic, "gen", 90_000, "1.5");
        bill = detail(clinic, id).get("bill");
        assertLine(bill, "Bed · General", 90_000, 1);
        assertLine(bill, "Doctor visits · Dr Asha", 105_000, 1);
        assertMultiplier(bill, "1.5");
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(90_000 + 105_000 + 45_000);
        assertThat(row(list(clinic), id).get("runningBillPaise").asLong()).isEqualTo(240_000);
        assertThat(count(clinic, "select count(*) from ipd.admission_charge")).as("nothing is posted by a read").isZero();
    }

    // ---------------- AC2 bill days: Bed day counted at midnight ----------------

    @Test
    void midnightCutCountsIstCalendarDaysInclusiveAroundMidnightAndOverSeveralDays() throws Exception {
        Clinic clinic = hospital();
        LocalDate today = istToday();
        Object[][] cases = {
                // label, admitted_at, expected bill days
                {"IST midnight exactly", istMidnight(today), 1},
                {"one second after IST midnight", istMidnight(today).plusSeconds(1), 1},
                {"one second before IST midnight", istMidnight(today).minusSeconds(1), 2},
                {"one second after yesterday's IST midnight", istMidnight(today.minusDays(1)).plusSeconds(1), 2},
                {"one millisecond before IST midnight", istMidnight(today).minusMillis(1), 2},
                {"yesterday at IST midnight", istMidnight(today.minusDays(1)), 2},
                {"one second before yesterday's midnight", istMidnight(today.minusDays(1)).minusSeconds(1), 3},
                {"three days ago at noon IST", istMidnight(today.minusDays(3)).plus(12, ChronoUnit.HOURS), 4},
                {"ten days ago", istMidnight(today.minusDays(10)).plus(1, ChronoUnit.HOURS), 11}};
        for (Object[] c : cases) {
            JsonNode a = quickAdmit(clinic);
            String id = a.get("id").asText();
            backdate(clinic, id, (Instant) c[1]);
            int days = (Integer) c[2];
            LocalDate before = istToday();
            JsonNode d = detail(clinic, id);
            JsonNode listRow = row(list(clinic), id);
            assumeTrue(before.equals(istToday()) && before.equals(today), "IST midnight passed while the test ran");
            JsonNode bill = d.get("bill");
            String label = (String) c[0];
            assertThat(bill.get("billDays").asInt()).as(label).isEqualTo(days);
            assertThat(bill.get("bedDayCut").asText()).isEqualTo("midnight");
            assertLine(bill, "Bed · General", 80_000, days);
            assertLine(bill, "Doctor visits · Dr Asha", 50_000, days);
            assertLine(bill, "Nursing care", 30_000, days);
            assertThat(bill.get("totalPaise").asLong()).as(label).isEqualTo(DAY_ONE_GENERAL * days);
            assertThat(listRow.get("runningBillPaise").asLong()).as(label + " list").isEqualTo(DAY_ONE_GENERAL * days);
            assertThat(d.get("dayNumber").asInt()).as(label + " day number").isEqualTo(days);
            assertThat(listRow.get("dayNumber").asInt()).isEqualTo(days);
        }
    }

    @Test
    void theBoardTileDayNumberAndTheBillAgreeAfterBackdating() throws Exception {
        Clinic clinic = hospital();
        JsonNode a = quickAdmit(clinic);
        String id = a.get("id").asText();
        backdate(clinic, id, istMidnight(istToday().minusDays(4)).plus(2, ChronoUnit.HOURS));
        LocalDate before = istToday();
        JsonNode bill = detail(clinic, id).get("bill");
        JsonNode board = board(clinic);
        assumeTrue(before.equals(istToday()), "IST midnight passed while the test ran");
        UUID bed = UUID.fromString(values(clinic, "select bed_id::text from ipd.admission where id = ?::uuid", id).get(0));
        assertThat(bill.get("billDays").asInt()).isEqualTo(5);
        assertThat(tile(board, bed).get("dayNumber").asInt()).isEqualTo(5);
    }

    @Test
    void oneDayNumberEverywhereTheSheetTheListAndTheBoardTileAllEqualTheBillDaysUnderBothBedDayCuts() throws Exception {
        Clinic clinic = hospital();
        // Chosen so the two cuts disagree: 23:59 IST yesterday is 2 calendar days but under 24 h; 50 h ago is
        // 3 calendar days (or 4 just after midnight) but exactly 2 completed 24-hour periods.
        Instant[] admittedAt = {istMidnight(istToday()).minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(50, ChronoUnit.HOURS), istMidnight(istToday().minusDays(5)).plusSeconds(60)};
        List<String> ids = new ArrayList<>();
        List<UUID> beds = new ArrayList<>();
        for (Instant at : admittedAt) {
            String id = quickAdmit(clinic).get("id").asText();
            backdate(clinic, id, at);
            ids.add(id);
            beds.add(UUID.fromString(values(clinic, "select bed_id::text from ipd.admission where id = ?::uuid", id)
                    .get(0)));
        }
        java.util.Map<String, Integer> firstBillDays = new java.util.HashMap<>();
        for (String cut : new String[] {"midnight", "24h"}) {
            setCharges(clinic, cut, NURSING, VISIT);
            LocalDate before = istToday();
            List<JsonNode> details = new ArrayList<>();
            for (String id : ids) {
                details.add(detail(clinic, id));
            }
            JsonNode list = list(clinic);
            JsonNode board = board(clinic);
            assumeTrue(before.equals(istToday()), "IST midnight passed while the test ran");
            firstBillDays.put(cut, details.get(0).get("bill").get("billDays").asInt());
            for (int i = 0; i < ids.size(); i++) {
                int billDays = details.get(i).get("bill").get("billDays").asInt();
                assertThat(billDays).as(cut + " admission " + i).isGreaterThanOrEqualTo(1);
                assertThat(details.get(i).get("dayNumber").asInt()).as(cut + " sheet " + i).isEqualTo(billDays);
                assertThat(row(list, ids.get(i)).get("dayNumber").asInt()).as(cut + " list " + i).isEqualTo(billDays);
                assertThat(tile(board, beds.get(i)).get("dayNumber").asInt()).as(cut + " tile " + i)
                        .isEqualTo(billDays);
            }
        }
        // The cuts really did differ for the first admission, so the equalities above are not trivial.
        assertThat(firstBillDays.get("midnight")).isEqualTo(2);
        assertThat(firstBillDays.get("24h")).isOne();
    }

    // ---------------- AC2 bill days: Bed day counted every 24 hours ----------------

    @Test
    void every24HoursCutCountsCompletedPeriodsAtLeastOneWithAMinuteOfMarginOnEachSide() throws Exception {
        Clinic clinic = hospital();
        setCharges(clinic, "24h", NURSING, VISIT);
        Object[][] cases = {
                {"1 hour", 60L, 1},
                {"23 h 59 min", 23 * 60L + 59, 1},
                {"24 h 1 min", 24 * 60L + 1, 1},
                {"47 h 59 min", 47 * 60L + 59, 1},
                {"48 h 1 min", 48 * 60L + 1, 2},
                {"71 h 59 min", 71 * 60L + 59, 2},
                {"72 h 1 min", 72 * 60L + 1, 3},
                {"10 days 1 min", 240 * 60L + 1, 10}};
        for (Object[] c : cases) {
            JsonNode a = quickAdmit(clinic);
            String id = a.get("id").asText();
            backdate(clinic, id, Instant.now().minus((Long) c[1], ChronoUnit.MINUTES));
            int days = (Integer) c[2];
            JsonNode d = detail(clinic, id);
            JsonNode bill = d.get("bill");
            String label = (String) c[0];
            assertThat(bill.get("billDays").asInt()).as(label).isEqualTo(days);
            assertThat(bill.get("bedDayCut").asText()).isEqualTo("24h");
            assertLine(bill, "Bed · General", 80_000, days);
            assertLine(bill, "Nursing care", 30_000, days);
            assertThat(bill.get("totalPaise").asLong()).as(label).isEqualTo(DAY_ONE_GENERAL * days);
            assertThat(row(list(clinic), id).get("runningBillPaise").asLong()).as(label + " list")
                    .isEqualTo(DAY_ONE_GENERAL * days);
            // One day number everywhere: the bill days of the 24-hour cut, in the sheet and in the list.
            assertThat(d.get("dayNumber").asInt()).as(label + " day number").isEqualTo(days);
            assertThat(row(list(clinic), id).get("dayNumber").asInt()).as(label + " list day number").isEqualTo(days);
        }
    }

    @Test
    void sameAdmissionGivesTwoDaysAtMidnightButOneDayEvery24HoursWhenAdmittedJustBeforeMidnight() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        // 23:59 IST yesterday: always under 24 h before the earliest moment the test can run after midnight, and
        // never 48 h
        backdate(clinic, id, istMidnight(istToday()).minus(1, ChronoUnit.MINUTES));
        LocalDate before = istToday();
        // Read everything first, then guard against IST midnight passing mid-test, then assert.
        JsonNode midnightDetail = detail(clinic, id);
        setCharges(clinic, "24h", NURSING, VISIT);
        JsonNode hoursDetail = detail(clinic, id);
        JsonNode hoursRow = row(list(clinic), id);
        setCharges(clinic, "midnight", NURSING, VISIT);
        JsonNode backDetail = detail(clinic, id);
        assumeTrue(before.equals(istToday()), "IST midnight passed while the test ran");

        assertThat(midnightDetail.get("bill").get("billDays").asInt()).isEqualTo(2);
        assertThat(midnightDetail.get("dayNumber").asInt()).isEqualTo(2);
        assertThat(hoursDetail.get("bill").get("billDays").asInt()).isOne();
        assertThat(hoursDetail.get("dayNumber").asInt()).isOne();
        assertThat(hoursRow.get("dayNumber").asInt()).isOne();
        // switching back changes the same admission's bill again
        assertThat(backDetail.get("bill").get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL * 2);
    }

    @Test
    void anAdmissionDatedInTheFutureStillBillsOneDay() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        backdate(clinic, id, Instant.now().plus(3, ChronoUnit.DAYS));
        assertThat(detail(clinic, id).get("bill").get("billDays").asInt()).isOne();
        setCharges(clinic, "24h", NURSING, VISIT);
        assertThat(detail(clinic, id).get("bill").get("billDays").asInt()).isOne();
    }

    // ---------------- AC3 Deposit low rule ----------------

    @Test
    void depositLowIsTrueAtExactlyTheAlertShareAndFalseOnePaiseRupeeAbove() throws Exception {
        Clinic clinic = hospital();
        // bill 160000 paise, alert 80 percent: low when 160000 x 100 >= deposit x 80, i.e. deposit <= 200000
        JsonNode below = admit(clinic, "gen", "Self", 0);      // no deposit yet
        JsonNode exact = admit(clinic, "gen", "Self", 0);
        JsonNode above = admit(clinic, "gen", "Self", 0);
        JsonNode over = admit(clinic, "gen", "Self", 0);
        String belowId = below.get("id").asText();
        String exactId = exact.get("id").asText();
        String aboveId = above.get("id").asText();
        String overId = over.get("id").asText();
        for (String id : new String[] {belowId, exactId, aboveId, overId}) {
            assertThat(detail(clinic, id).get("depositLow").asBoolean()).as("no deposit is always low").isTrue();
            assertThat(detail(clinic, id).get("bill").path("depositPercent").isNull()).isTrue();
        }
        deposit(clinic, belowId, 199_900);   // ₹1,999
        deposit(clinic, exactId, 200_000);   // ₹2,000
        deposit(clinic, aboveId, 200_100);   // ₹2,001
        deposit(clinic, overId, 5_000_000);  // ₹50,000

        Map<String, Boolean> expected = Map.of(belowId, true, exactId, true, aboveId, false, overId, false);
        JsonNode list = list(clinic);
        for (Map.Entry<String, Boolean> e : expected.entrySet()) {
            JsonNode d = detail(clinic, e.getKey());
            assertThat(d.get("depositLow").asBoolean()).as("detail " + e.getKey()).isEqualTo(e.getValue());
            assertThat(row(list, e.getKey()).get("depositLow").asBoolean()).as("list " + e.getKey())
                    .isEqualTo(e.getValue());
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(160_000);
        }
        assertThat(list.get("depositAlerts").asInt()).isEqualTo(2);
        assertThat(list.get("alertPercent").asInt()).isEqualTo(80);
        // the bill as a share of the deposit, rounded half-up
        assertThat(detail(clinic, belowId).get("bill").get("depositPercent").asInt()).isEqualTo(80); // 80.04
        assertThat(detail(clinic, exactId).get("bill").get("depositPercent").asInt()).isEqualTo(80);
        assertThat(detail(clinic, aboveId).get("bill").get("depositPercent").asInt()).isEqualTo(80); // 79.96
        assertThat(detail(clinic, overId).get("bill").get("depositPercent").asInt()).isEqualTo(3);   // 3.2
    }

    @Test
    void theAlertPercentOfAdmissionAndDepositsChangesTheFlagAndTheTile() throws Exception {
        Clinic clinic = hospital();
        String a = admit(clinic, "gen", "Self", 0).get("id").asText();  // deposit 200000
        String b = admit(clinic, "gen", "Self", 0).get("id").asText();  // deposit 320000
        deposit(clinic, a, 200_000);
        deposit(clinic, b, 320_000);
        // bill 160000: 16,000,000 against deposit x percent
        Object[][] steps = {
                // percent, A low (200000 x p <= 16M: p <= 80), B low (320000 x p <= 16M: p <= 50), alerts
                {80, true, false, 1},
                {85, false, false, 0},
                {50, true, true, 2},
                {55, true, false, 1},
                {100, false, false, 0},
                {75, true, false, 1}};
        for (Object[] s : steps) {
            int percent = (Integer) s[0];
            setAlertPercent(clinic, percent);
            JsonNode list = list(clinic);
            assertThat(list.get("alertPercent").asInt()).as("percent " + percent).isEqualTo(percent);
            assertThat(list.get("depositAlerts").asInt()).as("alerts at " + percent).isEqualTo((Integer) s[3]);
            assertThat(row(list, a).get("depositLow").asBoolean()).as("A at " + percent).isEqualTo(s[1]);
            assertThat(row(list, b).get("depositLow").asBoolean()).as("B at " + percent).isEqualTo(s[2]);
            JsonNode da = detail(clinic, a);
            assertThat(da.get("depositLow").asBoolean()).isEqualTo(s[1]);
            assertThat(da.get("bill").get("alertPercent").asInt()).isEqualTo(percent);
            assertThat(detail(clinic, b).get("depositLow").asBoolean()).isEqualTo(s[2]);
        }
    }

    @Test
    void addingAChargeOrADepositFlipsDepositLowBothWays() throws Exception {
        Clinic clinic = hospital();
        String id = admit(clinic, "gen", "Self", 0).get("id").asText();
        deposit(clinic, id, 200_100);                       // 160000 x 100 < 200100 x 80
        assertThat(detail(clinic, id).get("depositLow").asBoolean()).isFalse();
        JsonNode afterCharge = charge(clinic, id, "ecg");   // bill 190000: 19,000,000 >= 16,008,000
        assertThat(afterCharge.get("depositLow").asBoolean()).isTrue();
        assertThat(afterCharge.get("runningBillPaise").asLong()).isEqualTo(190_000);
        assertThat(row(list(clinic), id).get("depositLow").asBoolean()).isTrue();
        JsonNode afterDeposit = deposit(clinic, id, 100_000);   // deposit 300100 x 80 = 24,008,000 > 19,000,000
        assertThat(afterDeposit.get("depositLow").asBoolean()).isFalse();
        assertThat(afterDeposit.get("bill").get("paidPaise").asLong()).isEqualTo(300_100);
    }

    @Test
    void insuranceAndCorporatePatientsAreNeverDepositLowEvenWithNoDepositAndAHugeBill() throws Exception {
        Clinic clinic = hospital();
        List<String> ids = new ArrayList<>();
        for (String payer : new String[] {"Insurance", "Corporate"}) {
            JsonNode a = admit(clinic, "icu", payer, 0);
            String id = a.get("id").asText();
            ids.add(id);
            assertThat(a.get("depositLow").asBoolean()).as(payer).isFalse();
            charge(clinic, id, "oxygen");
            backdate(clinic, id, istMidnight(istToday().minusDays(30)));
        }
        JsonNode list = list(clinic);
        for (String id : ids) {
            JsonNode d = detail(clinic, id);
            assertThat(d.get("bill").get("billDays").asInt()).isGreaterThan(29);
            assertThat(d.get("runningBillPaise").asLong()).isGreaterThan(10_000_000L);
            assertThat(d.get("depositLow").asBoolean()).isFalse();
            assertThat(row(list, id).get("depositLow").asBoolean()).isFalse();
            assertThat(d.get("bill").path("depositPercent").isNull()).isTrue();
        }
        assertThat(list.get("depositAlerts").asInt()).isZero();
        UUID bed = UUID.fromString(values(clinic, "select bed_id::text from ipd.admission where id = ?::uuid",
                ids.get(0)).get(0));
        assertThat(tile(board(clinic), bed).get("depositLow").asBoolean()).isFalse();
    }

    // ---------------- AC3/AC4 list fields and tiles ----------------

    @Test
    void theListCarriesRunningBillDepositLowAndTheTileCountsOnlyDepositLowPatients() throws Exception {
        Clinic clinic = hospital();
        JsonNode ok1 = quickAdmit(clinic);                                   // ₹10,000 deposit, not low
        JsonNode low1 = admit(clinic, "gen", "Self", 0);                     // no deposit, low
        JsonNode low2 = admit(clinic, "semi", "Self", 0);
        deposit(clinic, low2.get("id").asText(), 200_000);                   // 242500 x 100 >= 200000 x 80, low
        JsonNode ins = admit(clinic, "gen", "Insurance", 0);                 // never low

        JsonNode list = list(clinic);
        assertThat(list.get("items")).hasSize(4);
        assertThat(list.get("depositAlerts").asInt()).isEqualTo(2);
        assertThat(list.get("alertPercent").asInt()).isEqualTo(80);
        assertThat(row(list, ok1.get("id").asText()).get("depositLow").asBoolean()).isFalse();
        assertThat(row(list, low1.get("id").asText()).get("depositLow").asBoolean()).isTrue();
        assertThat(row(list, low2.get("id").asText()).get("depositLow").asBoolean()).isTrue();
        assertThat(row(list, low2.get("id").asText()).get("runningBillPaise").asLong()).isEqualTo(242_500);
        assertThat(row(list, ins.get("id").asText()).get("depositLow").asBoolean()).isFalse();
        for (String role : READERS) {
            JsonNode l = ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200);
            assertThat(l.get("depositAlerts").asInt()).as(role).isEqualTo(2);
            assertThat(l.get("alertPercent").asInt()).isEqualTo(80);
        }
        // a discharged patient leaves the tile count
        discharge(clinic, low1.get("id").asText());
        assertThat(list(clinic).get("depositAlerts").asInt()).isOne();
    }

    @Test
    void anEmptyClinicHasZeroAlertsAndTheDefaultAlertPercent() throws Exception {
        Clinic clinic = hospital();
        JsonNode list = list(clinic);
        assertThat(list.get("items")).isEmpty();
        assertThat(list.get("depositAlerts").asInt()).isZero();
        assertThat(list.get("alertPercent").asInt()).isEqualTo(80);
        setAlertPercent(clinic, 65);
        assertThat(list(clinic).get("alertPercent").asInt()).isEqualTo(65);
    }

    // ---------------- AC5 bed board ----------------

    @Test
    void boardTilesShowDepositLowTrueFalseForOccupiedBedsAndNothingForOtherBeds() throws Exception {
        Clinic clinic = hospital();
        UUID lowBed = newBed(clinic, "gen");
        UUID okBed = newBed(clinic, "gen");
        UUID insBed = newBed(clinic, "gen");
        UUID freeBed = newBed(clinic, "gen");
        UUID cleanBed = newBed(clinic, "gen");
        Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), lowBed);
        m.put("admissionType", "emergency");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        JsonNode low = admitOk(clinic, m);
        admitOk(clinic, body(newPatient(clinic), clinic.doctorMembershipId(), okBed));
        m = body(newPatient(clinic), clinic.doctorMembershipId(), insBed);
        m.put("payer", "Insurance");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        admitOk(clinic, m);
        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = 'clean' where id = ?", cleanBed);

        for (String role : READERS) {
            JsonNode b = ok(HttpMethod.GET, BOARD, clinic.as(role), null, 200);
            assertThat(tile(b, lowBed).get("depositLow").asBoolean()).as(role).isTrue();
            assertThat(tile(b, okBed).get("depositLow").asBoolean()).as(role).isFalse();
            assertThat(tile(b, insBed).get("depositLow").asBoolean()).as(role).isFalse();
            assertThat(absent(tile(b, freeBed), "depositLow")).as(role + " free").isTrue();
            assertThat(absent(tile(b, cleanBed), "depositLow")).as(role + " cleaning").isTrue();
        }
        // paying a deposit clears the flag on the tile
        deposit(clinic, low.get("id").asText(), 5_000_000);
        assertThat(tile(board(clinic), lowBed).get("depositLow").asBoolean()).isFalse();
    }

    @Test
    void listAndBoardAgreeOnWhoIsDepositLowAfterBackdatingAndExtraCharges() throws Exception {
        Clinic clinic = hospital();
        List<UUID> beds = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID bed = newBed(clinic, "gen");
            beds.add(bed);
            ids.add(admitOk(clinic, body(newPatient(clinic), clinic.doctorMembershipId(), bed)).get("id").asText());
        }
        backdate(clinic, ids.get(1), istMidnight(istToday().minusDays(2)).plus(1, ChronoUnit.HOURS));
        backdate(clinic, ids.get(2), istMidnight(istToday().minusDays(20)));
        for (int i = 0; i < 6; i++) {
            charge(clinic, ids.get(3), "oxygen");
        }
        JsonNode list = list(clinic);
        JsonNode board = board(clinic);
        int lows = 0;
        for (int i = 0; i < 4; i++) {
            boolean low = row(list, ids.get(i)).get("depositLow").asBoolean();
            assertThat(tile(board, beds.get(i)).get("depositLow").asBoolean()).as("bed " + i).isEqualTo(low);
            assertThat(detail(clinic, ids.get(i)).get("depositLow").asBoolean()).isEqualTo(low);
            assertThat(detail(clinic, ids.get(i)).get("runningBillPaise").asLong())
                    .isEqualTo(row(list, ids.get(i)).get("runningBillPaise").asLong());
            lows += low ? 1 : 0;
        }
        // 10000 rupee deposit: day 3 (480000) is under 80 percent; 21 days (3360000) and 6 oxygen are over
        assertThat(row(list, ids.get(0)).get("depositLow").asBoolean()).isFalse();
        assertThat(row(list, ids.get(1)).get("depositLow").asBoolean()).isFalse();
        assertThat(row(list, ids.get(2)).get("depositLow").asBoolean()).isTrue();
        assertThat(row(list, ids.get(3)).get("depositLow").asBoolean()).as("160000 + 6 x 80000 = 640000").isFalse();
        assertThat(lows).isEqualTo(list.get("depositAlerts").asInt()).isOne();
    }

    // ---------------- AC6 detail bill block ----------------

    @Test
    void detailBillBlockHasEveryFieldAndCanAddChargeFollowsRoleAndState() throws Exception {
        Clinic clinic = hospital();
        String id = admit(clinic, "semi", "Self", 1_200_000).get("id").asText();
        deposit(clinic, id, 300_000);
        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200);
            JsonNode bill = d.get("bill");
            for (String f : new String[] {"billDays", "bedDayCut", "categoryName", "multiplier", "lines", "totalPaise",
                    "paidPaise", "depositPercent", "alertPercent"}) {
                assertThat(bill.has(f)).as(role + " bill." + f).isTrue();
            }
            assertThat(bill.get("billDays").asInt()).isOne();
            assertThat(bill.get("categoryName").asText()).isEqualTo("Semi-private");
            assertMultiplier(bill, "1.25");
            assertThat(bill.get("totalPaise").asLong()).isEqualTo(242_500);
            assertThat(bill.get("paidPaise").asLong()).isEqualTo(1_500_000);
            assertThat(bill.get("depositPercent").asInt()).isEqualTo(16);  // 242500 / 1500000 = 16.17
            assertThat(bill.get("alertPercent").asInt()).isEqualTo(80);
            assertThat(d.get("canAddCharge").asBoolean()).as(role).isEqualTo(!"DOCTOR".equals(role));
            // the deposit lines of the sheet come from the deposits list, not the bill
            assertThat(d.get("deposits")).hasSize(2);
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null), 403);
        }
        // read-only IPD: reads work, nobody can add a charge
        expireTrial(clinic);
        for (String role : READERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200).get("canAddCharge")
                    .asBoolean()).as(role + " readonly").isFalse();
        }
    }

    @Test
    void aDischargedAdmissionStillShowsItsBillButCannotTakeCharges() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "cbc");
        discharge(clinic, id);
        JsonNode d = detail(clinic, id);
        assertThat(d.get("canAddCharge").asBoolean()).isFalse();
        assertThat(d.get("canCollectDeposit").asBoolean()).isFalse();
        assertLine(d.get("bill"), "CBC", 30_000, 1);
        assertThat(d.get("runningBillPaise").asLong()).isEqualTo(DAY_ONE_GENERAL + 30_000);
    }

    // ---------------- AC1 charge services ----------------

    @Test
    void chargeServicesAreTheTenOfTheMockupWithTheirExactNamesAndBasePrices() throws Exception {
        Clinic clinic = hospital();
        for (String role : READERS) {
            JsonNode services = ok(HttpMethod.GET, CHARGE_SERVICES, clinic.as(role), null, 200);
            assertThat(services.isArray()).isTrue();
            assertThat(services).as(role).hasSize(10);
            for (int i = 0; i < 10; i++) {
                assertThat(services.get(i).get("key").asText()).as(role + " key " + i).isEqualTo(SERVICES[i][0]);
                assertThat(services.get(i).get("name").asText()).isEqualTo(SERVICES[i][1]);
                assertThat(services.get(i).get("basePaise").asLong()).isEqualTo(SERVICES[i][2]);
            }
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, CHARGE_SERVICES, clinic.as(role), null), 403);
        }
        mvc.perform(get(CHARGE_SERVICES)).andExpect(status().isUnauthorized());
    }

    @Test
    void chargeServicesAreTheSameWhateverTheCategoryMultiplierIs() throws Exception {
        Clinic clinic = hospital();
        setCategory(clinic, "gen", 80_000, "2");
        JsonNode services = ok(HttpMethod.GET, CHARGE_SERVICES, clinic.owner(), null, 200);
        assertThat(services.get(0).get("basePaise").asLong()).as("the list shows base prices; the sheet applies the multiplier")
                .isEqualTo(50_000);
    }
}
