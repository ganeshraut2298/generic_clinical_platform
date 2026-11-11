package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-29 AC 2 to AC 6 on the real {@code sahaj_test} database: the running bill of an admission with a package,
 * through the real endpoints (sheet, Admitted list, board, Deposit alerts tile, transfer, discharge). Every expected
 * bill is worked out here from the story and the default rates, not read from the application. Packages are set as the
 * table owner ({@link PackageSupport#attachPackage}); the admission time is moved back with {@link #backdate}.
 * Test plan: docs/test-plans/SAH-29.md.
 *
 * <p>Default rates (paise): General bed 80,000, Semi-private 150,000 (1.25), Private 250,000 (1.5), ICU 500,000 (2).
 * The bed line beyond the package uses the bed charge as it is, with no multiplier; extra charges use base x multiplier.
 */
@OnTestDatabase
class PackageBillIT extends PackageSupport {

    private static final String PKG = "Appendicectomy";

    private static void assertPackageBill(JsonNode bill, Object... parts) {
        assertBill(bill, parts);
        for (JsonNode line : bill.get("lines")) {
            String name = line.get("name").asText();
            assertThat(name).as("a package admission has no visit, nursing or transfer line")
                    .doesNotContain("Doctor visits").doesNotContain("Nursing care").doesNotContain("before transfer");
            assertThat(line.path("segment").asBoolean(false)).isFalse();
        }
    }

    // ---------------- AC 3: included days 0, 1 and 3 ----------------

    @Test
    void theBillIsThePackageThenOnlyTheBedDaysBeyondTheIncludedDaysForDayCareOneDayAndThreeDays() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        // included days, bill days, bed days beyond: max(0, bill days - max(1, included days)), written out by hand
        int[][] cases = {{0, 1, 0}, {0, 2, 1}, {0, 4, 3}, {1, 1, 0}, {1, 2, 1}, {1, 3, 2}, {3, 1, 0}, {3, 2, 0},
                {3, 3, 0}, {3, 4, 1}, {3, 6, 3}};
        Map<String, Long> expectedTotal = new HashMap<>();
        for (int[] c : cases) {
            String id = admitWithPackage(clinic, "gen", c[1], PKG, 45_000, c[0]).get("id").asText();
            JsonNode d = detail(clinic, id);
            String label = "included " + c[0] + ", bill day " + c[1];
            assertPackageBill(d.get("bill"), packageLine(PKG, 45_000, c[0]), beyond("gen", c[2]));
            assertThat(d.get("bill").get("billDays").asInt()).as(label).isEqualTo(c[1]);
            assertThat(d.get("dayNumber").asInt()).as(label).isEqualTo(c[1]);
            long total = 4_500_000L + 80_000L * c[2];
            assertThat(d.get("bill").get("totalPaise").asLong()).as(label).isEqualTo(total);
            assertThat(d.get("runningBillPaise").asLong()).as(label).isEqualTo(total);
            assertThat(d.get("package").get("includedDays").asInt()).isEqualTo(c[0]);
            expectedTotal.put(id, total);
        }
        JsonNode list = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        expectedTotal.forEach((id, total) -> assertThat(row(list, id).get("runningBillPaise").asLong()).isEqualTo(total));
    }

    @Test
    void theMockupWordingIsKeptLiterallyForZeroAndOneDay() throws Exception {
        Clinic clinic = hospital();
        String dayCare = admitWithPackage(clinic, "gen", 1, "Cataract surgery", 25_000, 0).get("id").asText();
        String oneDay = admitWithPackage(clinic, "gen", 1, "Dressing", 8_000, 1).get("id").asText();
        assertThat(lineNames(detail(clinic, dayCare).get("bill")))
                .containsExactly("Package · Cataract surgery (bed, nursing and surgeon for 0 days)");
        assertThat(lineNames(detail(clinic, oneDay).get("bill")))
                .containsExactly("Package · Dressing (bed, nursing and surgeon for 1 days)");
    }

    @Test
    void theBedBeyondThePackageIsBilledAtTheBedChargeOfTheCurrentCategoryWithNoMultiplier() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String semi = admitWithPackage(clinic, "semi", 5, PKG, 45_000, 3).get("id").asText();
        String pvt = admitWithPackage(clinic, "pvt", 5, PKG, 45_000, 3).get("id").asText();
        String icu = admitWithPackage(clinic, "icu", 4, PKG, 45_000, 1).get("id").asText();
        JsonNode semiBill = detail(clinic, semi).get("bill");
        JsonNode pvtBill = detail(clinic, pvt).get("bill");
        JsonNode icuBill = detail(clinic, icu).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(semiBill, packageLine(PKG, 45_000, 3), beyond("semi", 2));
        assertPackageBill(pvtBill, packageLine(PKG, 45_000, 3), beyond("pvt", 2));
        assertPackageBill(icuBill, packageLine(PKG, 45_000, 1), beyond("icu", 3));
        assertThat(pvtBill.get("totalPaise").asLong()).isEqualTo(4_500_000L + 2 * 250_000L);
        assertThat(icuBill.get("totalPaise").asLong()).isEqualTo(4_500_000L + 3 * 500_000L);
        assertMultiplier(pvtBill, "1.50");
        assertThat(pvtBill.get("categoryName").asText()).isEqualTo("Private");
    }

    @Test
    void ratesSetInClinicSetupAfterwardsMoveOnlyTheBedLineNeverThePackageOrTheOldNursingAndVisitRates() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitWithPackage(clinic, "gen", 4, PKG, 45_000, 3).get("id").asText();
        assertPackageBill(detail(clinic, id).get("bill"), packageLine(PKG, 45_000, 3), beyond("gen", 1));

        setCategory(clinic, "gen", 90_000, "1");
        setCharges(clinic, "midnight", 77_700, 66_600);
        JsonNode bill = detail(clinic, id).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(bill, packageLine(PKG, 45_000, 3), new L("Bed · General beyond the package", 90_000, 1, false));
        assertThat(storedCopy(clinic, id)).isEqualTo(PKG + "|4500000|3");
    }

    @Test
    void bothBedDayCutsCountTheBillDaysForThePackage() throws Exception {
        Clinic clinic = hospital();
        setCharges(clinic, "24h", NURSING, VISIT);
        String one = admitAtDay(clinic, "gen", 1, "24h").get("id").asText();
        String four = admitAtDay(clinic, "gen", 4, "24h").get("id").asText();
        String five = admitAtDay(clinic, "gen", 5, "24h").get("id").asText();
        attachPackage(clinic, one, PKG, 45_000, 3);
        attachPackage(clinic, four, PKG, 45_000, 3);
        attachPackage(clinic, five, "Cataract surgery", 25_000, 0);

        JsonNode b1 = detail(clinic, one).get("bill");
        JsonNode b4 = detail(clinic, four).get("bill");
        JsonNode b5 = detail(clinic, five).get("bill");
        assertThat(b4.get("bedDayCut").asText()).isEqualTo("24h");
        assertPackageBill(b1, packageLine(PKG, 45_000, 3));
        assertPackageBill(b4, packageLine(PKG, 45_000, 3), beyond("gen", 1));
        assertPackageBill(b5, packageLine("Cataract surgery", 25_000, 0), beyond("gen", 4));
        assertThat(b1.get("billDays").asInt()).isEqualTo(1);
        assertThat(b4.get("billDays").asInt()).isEqualTo(4);
        assertThat(b5.get("billDays").asInt()).isEqualTo(5);
    }

    // ---------------- AC 3: extra charges ----------------

    @Test
    void extraChargesComeAfterTheBedLineNotInPackageAtTheCurrentMultiplierInOrderOfFirstCharge() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitWithPackage(clinic, "pvt", 4, PKG, 45_000, 2).get("id").asText();
        charge(clinic, id, "ecg");
        charge(clinic, id, "xray");
        charge(clinic, id, "ecg");
        charge(clinic, id, "xray");
        charge(clinic, id, "injection");
        JsonNode d = detail(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        // Private 1.5: ECG 30,000 -> 45,000; X-ray 45,000 -> 67,500; Injection 15,000 -> 22,500; lines in order of first charge
        assertPackageBill(d.get("bill"), packageLine(PKG, 45_000, 2), beyond("pvt", 2),
                extra("ECG", 45_000, 2), extra("Chest X-ray", 67_500, 2), extra("Injection (IV/IM)", 22_500, 1));
        assertThat(d.get("bill").get("totalPaise").asLong())
                .isEqualTo(4_500_000L + 500_000L + 90_000L + 135_000L + 22_500L);
        // the charge rows keep the base price
        assertThat(values(clinic, "select string_agg(unit_paise::text, ',' order by added_at, id) from ipd.admission_charge "
                + "where admission_id = ?::uuid", id)).containsExactly("30000,45000,30000,45000,15000");
    }

    @Test
    void anExtraChargeOnDayOneIsTheOnlyLineAfterThePackageWhenNoBedDayIsBeyond() throws Exception {
        Clinic clinic = hospital();
        String id = admitWithPackage(clinic, "gen", 1, PKG, 45_000, 3).get("id").asText();
        charge(clinic, id, "xray");
        JsonNode bill = detail(clinic, id).get("bill");
        assertPackageBill(bill, packageLine(PKG, 45_000, 3), extra("Chest X-ray", 45_000, 1));
        assertThat(lineNames(bill)).noneMatch(n -> n.contains("beyond the package"));
    }

    @Test
    void theExtraChargeMultiplierIsRoundedHalfUpToWholePaisePerUnitBeforeTheQuantity() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String pvt = admitWithPackage(clinic, "pvt", 1, PKG, 45_000, 3).get("id").asText();
        String semi = admitWithPackage(clinic, "semi", 1, PKG, 45_000, 3).get("id").asText();
        String semiTwo = admitWithPackage(clinic, "semi", 1, PKG, 45_000, 3).get("id").asText();
        // 50003 x 1.5 = 75004.5 -> 75005 (half-even 75004); 50003 x 1.25 = 62503.75 -> 62504;
        // 50002 x 1.25 = 62502.5 -> 62503 (half-even 62502). The unit is rounded first: 2 x 62504, not round(125007.5)
        addChargeRow(clinic, pvt, "cbc", "CBC", 50_003, 1);
        addChargeRow(clinic, semi, "cbc", "CBC", 50_003, 2);
        addChargeRow(clinic, semiTwo, "cbc", "CBC", 50_002, 1);

        JsonNode pvtBill = detail(clinic, pvt).get("bill");
        JsonNode semiBill = detail(clinic, semi).get("bill");
        JsonNode twoBill = detail(clinic, semiTwo).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(pvtBill, packageLine(PKG, 45_000, 3), extra("CBC", 75_005, 1));
        assertPackageBill(semiBill, packageLine(PKG, 45_000, 3), extra("CBC", 62_504, 2));
        assertPackageBill(twoBill, packageLine(PKG, 45_000, 3), extra("CBC", 62_503, 1));
        assertThat(billLine(semiBill, "CBC · not in package").get("amountPaise").asLong()).isEqualTo(125_008L);
    }

    // ---------------- AC 4: one bill everywhere ----------------

    @Test
    void theSheetTheListTheBoardTheDepositLowPillAndTheAlertsTileAllUseOnePackageBill() throws Exception {
        Clinic clinic = hospital();
        setAlertPercent(clinic, 80);
        LocalDate day = istToday();
        // deposit ₹10,000, alert at 80 percent = ₹8,000. The package price counts from day 1.
        String high = admitWithPackage(clinic, "gen", 1, "Knee arthroscopy", 9_000, 3).get("id").asText();
        String low = admitWithPackage(clinic, "gen", 1, PKG, 5_000, 3).get("id").asText();
        String insured = admitInsured(clinic);
        String plain = quickAdmit(clinic).get("id").asText();

        JsonNode list = list(clinic);
        JsonNode board = board(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(row(list, high).get("runningBillPaise").asLong()).isEqualTo(900_000L);
        assertThat(row(list, high).get("depositLow").asBoolean()).as("₹9,000 of ₹10,000 on day 1").isTrue();
        assertThat(row(list, low).get("runningBillPaise").asLong()).isEqualTo(500_000L);
        assertThat(row(list, low).get("depositLow").asBoolean()).isFalse();
        assertThat(row(list, insured).get("depositLow").asBoolean()).as("insurance is never low").isFalse();
        assertThat(row(list, plain).get("runningBillPaise").asLong()).as("no package: the SAH-25 bill").isEqualTo(DAY);
        assertThat(list.get("depositAlerts").asInt()).isOne();
        assertThat(tile(board, bedOf(clinic, high)).get("depositLow").asBoolean()).isTrue();
        assertThat(tile(board, bedOf(clinic, low)).get("depositLow").asBoolean()).isFalse();
        assertThat(tile(board, bedOf(clinic, high)).get("status").asText()).isEqualTo("occ");

        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + high, clinic.as(role), null, 200);
            assertThat(d.get("runningBillPaise").asLong()).as(role).isEqualTo(900_000L);
            assertThat(d.get("bill").get("totalPaise").asLong()).as(role).isEqualTo(900_000L);
            assertThat(d.get("depositLow").asBoolean()).as(role).isTrue();
            assertThat(d.get("package").get("name").asText()).as(role).isEqualTo("Knee arthroscopy");
            assertThat(d.get("package").get("pricePaise").asLong()).as(role).isEqualTo(900_000L);
            assertThat(d.get("package").get("includedDays").asInt()).as(role).isEqualTo(3);
            JsonNode l = ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200);
            assertThat(row(l, high).get("runningBillPaise").asLong()).as(role).isEqualTo(900_000L);
            assertThat(l.get("depositAlerts").asInt()).as(role).isOne();
        }

        // extras cross the line: 6 x ₹450 = ₹7,700 is below ₹8,000, the 7th makes ₹8,150 and the pill appears everywhere
        for (int i = 1; i <= 6; i++) {
            charge(clinic, low, "xray");
        }
        assertThat(detail(clinic, low).get("depositLow").asBoolean()).isFalse();
        assertThat(detail(clinic, low).get("runningBillPaise").asLong()).isEqualTo(500_000L + 6 * 45_000L);
        charge(clinic, low, "xray");
        JsonNode crossed = detail(clinic, low);
        JsonNode list2 = list(clinic);
        JsonNode board2 = board(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(crossed.get("runningBillPaise").asLong()).isEqualTo(815_000L);
        assertThat(crossed.get("depositLow").asBoolean()).isTrue();
        assertThat(row(list2, low).get("runningBillPaise").asLong()).isEqualTo(815_000L);
        assertThat(row(list2, low).get("depositLow").asBoolean()).isTrue();
        assertThat(tile(board2, bedOf(clinic, low)).get("depositLow").asBoolean()).isTrue();
        assertThat(list2.get("depositAlerts").asInt()).as("the Deposit alerts tile counts both").isEqualTo(2);
        assertThat(billLine(crossed.get("bill"), "Chest X-ray · not in package").get("qty").asInt()).isEqualTo(7);
    }

    @Test
    void thePackageAddsNoFieldToTheListRowTheBoardTileOrTheSummaryOfThePatient() throws Exception {
        Clinic clinic = hospital();
        String withPackage = admitWithPackage(clinic, "gen", 1, PKG, 45_000, 3).get("id").asText();
        String plain = quickAdmit(clinic).get("id").asText();
        JsonNode list = list(clinic);
        assertThat(fieldNames(row(list, withPackage))).as("list row").isEqualTo(fieldNames(row(list, plain)));
        assertThat(absent(row(list, withPackage), "package")).isTrue();
        JsonNode board = board(clinic);
        assertThat(fieldNames(tile(board, bedOf(clinic, withPackage)))).isEqualTo(fieldNames(tile(board, bedOf(clinic, plain))));
        JsonNode a = detail(clinic, withPackage);
        JsonNode b = detail(clinic, plain);
        Set<String> expected = fieldNames(b);
        assertThat(expected).contains("package");
        assertThat(b.get("package").isNull()).as("no package: null").isTrue();
        assertThat(fieldNames(a)).as("the same fields with or without a package").isEqualTo(expected);
    }

    // ---------------- AC 2: the admission keeps its own copy ----------------

    @Test
    void theAdmissionKeepsItsOwnCopyWhateverHappensToThePackageListAfterwards() throws Exception {
        Clinic clinic = hospital();
        UUID id = insertPackage(clinic, PKG, 45_000, 3);
        String admission = admitAtDay(clinic, "gen", 2, "midnight").get("id").asText();
        // the copy is taken when attached: name, price and days of the package row at that time
        attachPackage(clinic, admission, PKG, 45_000, 3);
        JsonNode before = detail(clinic, admission);
        assertThat(before.get("package").get("name").asText()).isEqualTo(PKG);
        assertThat(before.get("package").get("pricePaise").asLong()).isEqualTo(4_500_000L);
        assertThat(before.get("package").get("includedDays").asInt()).isEqualTo(3);

        editPackage(clinic, id, 99_000, false);
        assertThat(packageNames(packages(clinic))).as("switched off: gone from the list").isEmpty();
        JsonNode after = detail(clinic, admission);
        assertThat(after.get("package")).isEqualTo(before.get("package"));
        assertThat(after.get("bill")).as("an attached bill never moves with the list").isEqualTo(before.get("bill"));
        assertThat(after.get("bill").get("totalPaise").asLong()).isEqualTo(4_500_000L);

        // a package the clinic never had, or a new row with the same name, does not touch it either
        insertPackage(clinic, "Appendicectomy plus", 10_000, 9);
        assertThat(detail(clinic, admission).get("package")).isEqualTo(before.get("package"));
    }

    // ---------------- AC 5: transfer ----------------

    @Test
    void aTransferAddsNoSegmentLineBillsDaysBeyondAtTheNewBedAndRepricesTheExtras() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        String id = admitWithPackage(clinic, "gen", 4, PKG, 45_000, 3).get("id").asText();
        charge(clinic, id, "xray");
        assertPackageBill(detail(clinic, id).get("bill"), packageLine(PKG, 45_000, 3), beyond("gen", 1),
                extra("Chest X-ray", 45_000, 1));

        JsonNode moved = moveOk(clinic, id, newBed(clinic, "pvt"));
        JsonNode reread = detail(clinic, id);
        JsonNode list = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        for (JsonNode d : new JsonNode[] {moved, reread}) {
            // the bed beyond the package is the Private bed the patient is in now; the X-ray is re-priced at 1.5
            assertPackageBill(d.get("bill"), packageLine(PKG, 45_000, 3), beyond("pvt", 1),
                    extra("Chest X-ray", 67_500, 1));
            assertThat(d.get("bill").get("totalPaise").asLong()).isEqualTo(4_500_000L + 250_000L + 67_500L);
            assertThat(d.get("package").get("name").asText()).isEqualTo(PKG);
        }
        assertThat(row(list, id).get("runningBillPaise").asLong()).isEqualTo(4_817_500L);
        // the transfer itself works as in SAH-26 and its stored fact is still recorded; the package bill ignores it
        assertThat(transferRows(clinic)).isOne();
        assertThat(segmentDays(clinic, id)).containsExactly("3");
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isOne();
        assertThat(storedCopy(clinic, id)).isEqualTo(PKG + "|4500000|3");
    }

    @Test
    void aDearerBedAddsNoBedDifferenceAndACheaperBedBillsTheDaysBeyondAtTheCheaperRate() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        // ICU while the package covers all days: nothing but the package (and extras at 2.0)
        String up = admitWithPackage(clinic, "gen", 3, PKG, 45_000, 5).get("id").asText();
        charge(clinic, up, "xray");
        JsonNode upBill = moveOk(clinic, up, newBed(clinic, "icu")).get("bill");
        assertPackageBill(upBill, packageLine(PKG, 45_000, 5), extra("Chest X-ray", 90_000, 1));
        assertThat(upBill.get("totalPaise").asLong()).isEqualTo(4_500_000L + 90_000L);

        // Private to General with days beyond: General rate
        String down = admitWithPackage(clinic, "pvt", 5, PKG, 45_000, 2).get("id").asText();
        assertPackageBill(detail(clinic, down).get("bill"), packageLine(PKG, 45_000, 2), beyond("pvt", 3));
        JsonNode downBill = moveOk(clinic, down, newBed(clinic, "gen")).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(downBill, packageLine(PKG, 45_000, 2), beyond("gen", 3));
        assertThat(downBill.get("totalPaise").asLong()).isEqualTo(4_500_000L + 3 * 80_000L);
    }

    @Test
    void severalMovesStillGiveOneBedLineAtTheBedThePatientIsInNow() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        String id = admitWithPackage(clinic, "gen", 4, PKG, 45_000, 1).get("id").asText();
        moveOk(clinic, id, newBed(clinic, "semi"));
        JsonNode bill = moveOk(clinic, id, newBed(clinic, "pvt")).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(bill, packageLine(PKG, 45_000, 1), beyond("pvt", 3));
        assertThat(transferRows(clinic)).isEqualTo(2);
        assertThat(segmentDays(clinic, id)).hasSize(2);
    }

    @Test
    void transfersWrittenBeforeThePackageWasSetAreNeverReadByThePackageBill() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        String id = admitAtDay(clinic, "gen", 3, "midnight").get("id").asText();
        JsonNode plain = moveOk(clinic, id, newBed(clinic, "pvt"));
        assertThat(lineNames(plain.get("bill"))).contains("Bed · General (before transfer)");

        attachPackage(clinic, id, "Hernia repair", 30_000, 2);
        JsonNode bill = detail(clinic, id).get("bill");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertPackageBill(bill, packageLine("Hernia repair", 30_000, 2), beyond("pvt", 1));
        assertThat(transferRows(clinic)).as("the transfer fact stays").isOne();
        assertThat(segmentDays(clinic, id)).containsExactly("2");
    }

    // ---------------- AC 5 and AC 2: discharge ----------------

    @Test
    void theDischargeSheetDefaultsToSurgicalForAPackageAdmissionUnlessMaternityOrDayCareOrTheSetupTemplateSaysOtherwise()
            throws Exception {
        Clinic clinic = hospital();
        String planned = quickAdmit(clinic).get("id").asText();
        String plannedPackage = quickAdmit(clinic).get("id").asText();
        String maternity = admitOfType(clinic, "maternity", 1_500_000L).get("id").asText();
        String daycare = admitOfType(clinic, "daycare", 300_000L).get("id").asText();
        String emergency = admitOfType(clinic, "emergency", 500_000L).get("id").asText();
        attachPackage(clinic, plannedPackage, PKG, 45_000, 3);
        attachPackage(clinic, maternity, "Normal delivery", 35_000, 2);
        attachPackage(clinic, daycare, "Cataract surgery", 25_000, 0);
        attachPackage(clinic, emergency, "Hernia repair", 30_000, 2);

        assertThat(sheet(clinic, planned).get("defaultTemplateKey").asText()).as("no package").isEqualTo("general");
        assertThat(sheet(clinic, plannedPackage).get("defaultTemplateKey").asText()).isEqualTo("surgical");
        assertThat(sheet(clinic, maternity).get("defaultTemplateKey").asText()).isEqualTo("maternity");
        assertThat(sheet(clinic, daycare).get("defaultTemplateKey").asText()).isEqualTo("daycare");
        assertThat(sheet(clinic, emergency).get("defaultTemplateKey").asText()).as("a package, not maternity or day-care")
                .isEqualTo("surgical");

        putSettings(clinic, "general", true, 7);
        assertThat(sheet(clinic, plannedPackage).get("defaultTemplateKey").asText()).as("the setup template wins")
                .isEqualTo("general");
        putSettings(clinic, null, true, 7);
        assertThat(sheet(clinic, plannedPackage).get("defaultTemplateKey").asText()).isEqualTo("surgical");
        // the discharge sheet totals the same package bill (day 1: the package price only)
        assertThat(sheet(clinic, plannedPackage).get("bill").get("totalPaise").asLong()).isEqualTo(4_500_000L);
    }

    @Test
    void aDischargedAdmissionKeepsItsPackageAndTheFinalBillFreezesThePackageLines() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        UUID packageId = insertPackage(clinic, PKG, 45_000, 3);
        String id = admitAtDay(clinic, "gen", 5, "midnight").get("id").asText();
        attachPackage(clinic, id, PKG, 45_000, 3);
        charge(clinic, id, "xray");
        charge(clinic, id, "ecg");
        JsonNode running = detail(clinic, id);
        assertPackageBill(running.get("bill"), packageLine(PKG, 45_000, 3), beyond("gen", 2),
                extra("Chest X-ray", 45_000, 1), extra("ECG", 30_000, 1));
        long total = 4_500_000L + 160_000L + 45_000L + 30_000L;
        assertThat(running.get("bill").get("totalPaise").asLong()).isEqualTo(total);

        JsonNode done = signAndDischarge(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(lineKeys(done.get("finalBill"))).as("the final bill freezes the running bill lines")
                .isEqualTo(lineKeys(running.get("bill")));
        assertThat(done.get("finalBill").get("totalPaise").asLong()).isEqualTo(total);
        assertThat(storedLines(clinic, id)).containsExactly(
                "Package · " + PKG + " (bed, nursing and surgeon for 3 days)|4500000|1|4500000|false",
                "Bed · General beyond the package|80000|2|160000|false",
                "Chest X-ray · not in package|45000|1|45000|false",
                "ECG · not in package|30000|1|30000|false");

        editPackage(clinic, packageId, 1_000, false);
        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200);
            assertThat(d.get("status").asText()).isEqualTo("DISCHARGED");
            assertThat(d.get("package").get("name").asText()).as(role).isEqualTo(PKG);
            assertThat(d.get("package").get("pricePaise").asLong()).as(role).isEqualTo(4_500_000L);
            assertThat(d.get("package").get("includedDays").asInt()).as(role).isEqualTo(3);
            assertThat(lineKeys(d.get("bill"))).as(role).isEqualTo(lineKeys(running.get("bill")));
            assertThat(d.get("bill").get("totalPaise").asLong()).as(role).isEqualTo(total);
        }
        assertThat(storedCopy(clinic, id)).isEqualTo(PKG + "|4500000|3");
    }

    // ---------------- AC 6: tenants and roles ----------------

    @Test
    void anotherClinicsPackageAdmissionAnswers404AndItsPackageIsNeverSeen() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        insertPackage(a, PKG, 45_000, 3);
        String id = admitWithPackage(a, "gen", 2, PKG, 45_000, 3).get("id").asText();
        String own = admitWithPackage(b, "gen", 2, "Hernia repair", 30_000, 2).get("id").asText();

        for (String url : new String[] {ADMISSIONS + "/" + id, dischargeUrl(id)}) {
            JsonNode p = problem(call(HttpMethod.GET, url, b.owner(), null), 404);
            assertThat(p.toString()).doesNotContain(PKG);
        }
        assertThat(packageNames(packages(b))).isEmpty();
        assertThat(detail(b, own).get("package").get("name").asText()).isEqualTo("Hernia repair");
        List<String> seen = new ArrayList<>();
        list(b).get("items").forEach(i -> seen.add(i.get("id").asText()));
        assertThat(seen).containsExactly(own);
        assertThat(count(b, "select count(*) from ipd.admission where package_name = ?", PKG)).isZero();
    }

    @Test
    void aRoleWithoutPermissionGets403OnTheSheetThatCarriesThePackageAndNoSessionGets401() throws Exception {
        Clinic clinic = hospital();
        String id = admitWithPackage(clinic, "gen", 2, PKG, 45_000, 3).get("id").asText();
        for (String role : NON_READERS) {
            JsonNode p = problem(call(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null), 403);
            assertType(p, "forbidden");
            assertThat(p.toString()).doesNotContain(PKG);
        }
    }

    // ---------------- helpers ----------------

    private String admitInsured(Clinic clinic) throws Exception {
        String id = admit(clinic, "gen", "Insurance", 0).get("id").asText();
        attachPackage(clinic, id, "Insured knee", 50_000, 3);
        return id;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
