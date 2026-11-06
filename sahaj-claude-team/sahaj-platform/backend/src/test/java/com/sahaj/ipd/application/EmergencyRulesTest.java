package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.AdmissionViews.BedOption;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-28 pure rules: levels, queue order, waits, over target, bays, scale mapping, suggested bed. */
class EmergencyRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");

    private static CaseRow row(String id, int level, String status, long minutesAgo, String bay) {
        return new CaseRow(UUID.fromString("00000000-0000-0000-0000-00000000000" + id), null, "Name", 40, "M",
                null, "Complaint", false, level, bay, status, NOW.minusSeconds(minutesAgo * 60), UUID.randomUUID(),
                null, null, null, null, null, UUID.randomUUID(), 0);
    }

    private static BedOption bed(String category) {
        return new BedOption(UUID.randomUUID(), "B-" + category, "Ward", category, category, 1000L);
    }

    @Test
    void threeColourLevelsAreRedYellowGreenWithTheirTargets() {
        List<Level> levels = EmergencyRules.levels(EmergencySetup.DEFAULTS);

        assertThat(levels).extracting(Level::name).containsExactly("Red", "Yellow", "Green");
        assertThat(levels).extracting(Level::description).containsExactly("Immediate", "Urgent", "Can wait");
        assertThat(levels).extracting(Level::tone).containsExactly("crit", "warn", "good");
        assertThat(levels).extracting(Level::targetMinutes).containsExactly(5, 30, 120);
        assertThat(levels).extracting(Level::level).containsExactly(1, 2, 3);
    }

    @Test
    void fiveLevelsHaveTheirOwnNamesTonesAndTargets() {
        EmergencySetup five = new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true);

        List<Level> levels = EmergencyRules.levels(five);

        assertThat(levels).extracting(Level::name).containsExactly("Level 1", "Level 2", "Level 3", "Level 4", "Level 5");
        assertThat(levels).extracting(Level::description)
                .containsExactly("Resuscitation", "Emergent", "Urgent", "Less urgent", "Non-urgent");
        assertThat(levels).extracting(Level::tone).containsExactly("crit", "crit", "warn", "good", "good");
        assertThat(levels).extracting(Level::targetMinutes).containsExactly(0, 10, 30, 60, 120);
    }

    @Test
    void queueIsOrderedByLevelThenLongestWaitThenId() {
        List<CaseRow> rows = new ArrayList<>(List.of(
                row("5", 3, "WAITING", 90, null), row("4", 1, "WAITING", 5, "ER-1"),
                row("3", 2, "SEEN", 20, null), row("2", 2, "WAITING", 40, null),
                row("1", 2, "WAITING", 40, null)));

        rows.sort(EmergencyRules.queueOrder());

        assertThat(rows).extracting(r -> r.id().toString().substring(35)).containsExactly("4", "1", "2", "3", "5");
    }

    @Test
    void waitingMinutesAreWholeMinutesRoundedDownAndNeverNegative() {
        assertThat(EmergencyRules.waitingMinutes(NOW.minusSeconds(38 * 60 + 59), NOW)).isEqualTo(38);
        assertThat(EmergencyRules.waitingMinutes(NOW.minusSeconds(59), NOW)).isZero();
        assertThat(EmergencyRules.waitingMinutes(NOW.plusSeconds(600), NOW)).isZero();
    }

    @Test
    void overTargetIsWaitingOnlyAndStrictlyPastTheTarget() {
        assertThat(EmergencyRules.overTarget("WAITING", 30, 30)).isFalse();
        assertThat(EmergencyRules.overTarget("WAITING", 31, 30)).isTrue();
        assertThat(EmergencyRules.overTarget("SEEN", 500, 30)).isFalse();
        assertThat(EmergencyRules.overTarget("RELEASED", 500, 30)).isFalse();
    }

    @Test
    void targetNowMeansAnyWaitOfOneMinuteIsOver() {
        assertThat(EmergencyRules.overTarget("WAITING", 0, 0)).isFalse();
        assertThat(EmergencyRules.overTarget("WAITING", 1, 0)).isTrue();
    }

    @Test
    void lowestFreeBayFillsGapsAndStopsAtTheConfiguredBays() {
        List<CaseRow> open = List.of(row("1", 1, "WAITING", 1, "ER-2"));
        assertThat(EmergencyRules.lowestFreeBay(2, open)).isEqualTo("ER-1");
        assertThat(EmergencyRules.lowestFreeBay(2, List.of(row("1", 1, "WAITING", 1, "ER-1"),
                row("2", 1, "SEEN", 1, "ER-2")))).isNull();
        assertThat(EmergencyRules.lowestFreeBay(0, List.of())).isNull();
        assertThat(EmergencyRules.lowestFreeBay(10, List.of())).isEqualTo("ER-1");
    }

    @Test
    void aHeldBayAboveTheConfiguredBaysIsIgnoredNotReassigned() {
        List<CaseRow> open = List.of(row("1", 1, "WAITING", 1, "ER-5"));

        assertThat(EmergencyRules.lowestFreeBay(2, open)).isEqualTo("ER-1");
        assertThat(EmergencyRules.baysFree(2, open)).isEqualTo(2);
        assertThat(EmergencyRules.baysFree(5, open)).isEqualTo(4);
    }

    @Test
    void aClosedCaseNeverHoldsABay() {
        assertThat(EmergencyRules.baysFree(2, List.of(row("1", 1, "RELEASED", 1, "ER-1")))).isEqualTo(2);
        assertThat(EmergencyRules.lowestFreeBay(2, List.of(row("1", 1, "ADMITTED", 1, "ER-1")))).isEqualTo("ER-1");
    }

    @Test
    void threeToFiveMapsRedToLevel2YellowToLevel3GreenToLevel5() {
        assertThat(EmergencyRules.mapLevel(1, 3, 5)).isEqualTo(2);
        assertThat(EmergencyRules.mapLevel(2, 3, 5)).isEqualTo(3);
        assertThat(EmergencyRules.mapLevel(3, 3, 5)).isEqualTo(5);
    }

    @Test
    void fiveToThreeMapsLevels1And2ToRed3ToYellow4And5ToGreen() {
        assertThat(EmergencyRules.mapLevel(1, 5, 3)).isEqualTo(1);
        assertThat(EmergencyRules.mapLevel(2, 5, 3)).isEqualTo(1);
        assertThat(EmergencyRules.mapLevel(3, 5, 3)).isEqualTo(2);
        assertThat(EmergencyRules.mapLevel(4, 5, 3)).isEqualTo(3);
        assertThat(EmergencyRules.mapLevel(5, 5, 3)).isEqualTo(3);
        assertThat(EmergencyRules.mapLevel(2, 3, 3)).isEqualTo(2);
    }

    @Test
    void unknownPatientsAreNamedBySexAndAge() {
        assertThat(EmergencyRules.unknownName("M", 40)).isEqualTo("Unknown male, about 40");
        assertThat(EmergencyRules.unknownName("F", 8)).isEqualTo("Unknown female, about 8");
        assertThat(EmergencyRules.unknownName("O", 0)).isEqualTo("Unknown person, about 0");
        assertThat(EmergencyRules.unknownName("M", null)).isEqualTo("Unknown male, about ?");
    }

    @Test
    void wholeAcceptsOnlyWholeNumbersInRange() {
        assertThat(EmergencyRules.whole(new BigDecimal("40"), 0, 120)).isEqualTo(40);
        assertThat(EmergencyRules.whole(new BigDecimal("40.0"), 0, 120)).isEqualTo(40);
        assertThat(EmergencyRules.whole(new BigDecimal("40.5"), 0, 120)).isNull();
        assertThat(EmergencyRules.whole(new BigDecimal("121"), 0, 120)).isNull();
        assertThat(EmergencyRules.whole(new BigDecimal("-1"), 0, 120)).isNull();
        assertThat(EmergencyRules.whole(new BigDecimal("0"), 0, 120)).isZero();
        assertThat(EmergencyRules.whole(null, 0, 120)).isNull();
    }

    @Test
    void suggestedBedForTheMostUrgentLevelIsTheFirstFreeIcuBed() {
        BedOption gen = bed("gen");
        BedOption icu1 = bed("icu");
        BedOption icu2 = bed("icu");

        assertThat(EmergencyRules.suggestedBed(1, List.of(gen, icu1, icu2))).isEqualTo(icu1.id());
    }

    @Test
    void suggestedBedForOtherLevelsIsTheFirstFreeGeneralBed() {
        BedOption gen1 = bed("gen");
        BedOption gen2 = bed("gen");
        BedOption icu = bed("icu");

        assertThat(EmergencyRules.suggestedBed(2, List.of(gen1, gen2, icu))).isEqualTo(gen1.id());
        assertThat(EmergencyRules.suggestedBed(5, List.of(icu, gen2))).isEqualTo(gen2.id());
    }

    @Test
    void suggestedBedFallsBackToTheFirstFreeBedOfAnyCategoryThenNull() {
        BedOption semi = bed("semi");
        BedOption pvt = bed("pvt");

        assertThat(EmergencyRules.suggestedBed(1, List.of(semi, pvt))).isEqualTo(semi.id());
        assertThat(EmergencyRules.suggestedBed(3, List.of(pvt, semi))).isEqualTo(pvt.id());
        assertThat(EmergencyRules.suggestedBed(1, List.of())).isNull();
    }


    // ---- review fixes ----

    @Test
    void theThirdSortKeyIsTheJavaUuidCompareNotThePostgreSqlOrder() {
        // Same level and arrival: java.util.UUID compares the most significant half as a signed number, so
        // 8000... sorts before 7000... (PostgreSQL would put 7000... first). The contract says "as a UUID compare".
        CaseRow high = withId("80000000-0000-0000-0000-000000000000");
        CaseRow low = withId("70000000-0000-0000-0000-000000000000");
        List<CaseRow> rows = new ArrayList<>(List.of(low, high));

        rows.sort(EmergencyRules.queueOrder());

        assertThat(rows).containsExactly(high, low);
    }

    private static CaseRow withId(String id) {
        return new CaseRow(UUID.fromString(id), null, "Name", 40, "M", null, "Complaint", false, 2, null, "WAITING",
                NOW, UUID.randomUUID(), null, null, null, null, null, UUID.randomUUID(), 0);
    }

    @Test
    void aLevelIsClampedIntoTheScaleSoALevelFiveNeverMeetsAThreeColourSetup() {
        assertThat(EmergencyRules.clampLevel(5, 3)).isEqualTo(3);
        assertThat(EmergencyRules.clampLevel(4, 3)).isEqualTo(3);
        assertThat(EmergencyRules.clampLevel(2, 3)).isEqualTo(2);
        assertThat(EmergencyRules.clampLevel(5, 5)).isEqualTo(5);
        assertThat(EmergencyRules.clampLevel(0, 5)).isEqualTo(1);
        assertThat(EmergencyRules.targetOf(EmergencySetup.DEFAULTS, 5)).isEqualTo(120);
        assertThat(EmergencyRules.targetOf(EmergencySetup.DEFAULTS, 1)).isEqualTo(5);
    }

    @Test
    void wholeNodeAcceptsOnlyJsonNumbersThatAreWholeAndInRange() {
        com.fasterxml.jackson.databind.node.JsonNodeFactory json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;

        assertThat(EmergencyRules.wholeNode(json.numberNode(40), 0, 120)).isEqualTo(40);
        assertThat(EmergencyRules.wholeNode(json.numberNode(40.0), 0, 120)).isEqualTo(40);
        assertThat(EmergencyRules.wholeNode(json.numberNode(new BigDecimal("0")), 0, 120)).isZero();
        assertThat(EmergencyRules.wholeNode(json.numberNode(40.5), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.numberNode(121), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.numberNode(-1), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.textNode("40"), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.booleanNode(true), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.nullNode(), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.arrayNode(), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(json.objectNode(), 0, 120)).isNull();
        assertThat(EmergencyRules.wholeNode(null, 0, 120)).isNull();
    }
}
