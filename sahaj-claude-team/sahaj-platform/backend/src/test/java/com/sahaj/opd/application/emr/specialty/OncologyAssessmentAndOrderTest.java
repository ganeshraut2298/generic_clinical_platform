package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.Computed;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.OrderInputs;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Check;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Premed;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The oncology assessment of a record and the server computation of a chemotherapy order (SAH-65, SAH-66; rule C-8).
 * Every number is tagged (DEC-039, not clinically reviewed).
 */
class OncologyAssessmentAndOrderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final Protocol AC = new Protocol("AC", "AC", "Doxorubicin + cyclophosphamide · every 21 days", 21,
            "HIGH", false,
            List.of(new Drug("doxorubicin", "Doxorubicin", 60, 15, 5, true, true, "Vesicant: give by port"),
                    new Drug("cyclophosphamide", "Cyclophosphamide", 600, 30, 20, false, false, null)),
            List.of(new Premed("ondansetron_iv", "Ondansetron 8 mg IV", 15)));
    private static final Protocol PACLI = new Protocol("PACLI_3W", "Paclitaxel 3-weekly", "x", 21, "LOW", true,
            List.of(new Drug("paclitaxel", "Paclitaxel", 175, 180, 10, false, false, null)), List.of());

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String LESION = "{'id':'%s','name':'L','baselineCm':5.0,'lastCm':4.0,'todayCm':%s}";

    private static Function<String, JsonNode> record(String heightWeight, String lesionToday, String labs, String ecog,
                                                     String tox, String vitals, String line) {
        Map<String, JsonNode> values = Map.of(
                "ONC_EXAM", json("{'heightCm':" + heightWeight.split("/")[0] + ",'weightKg':" + heightWeight.split("/")[1]
                        + ",'capAt2':true,'lesions':[" + LESION.formatted(UUID.randomUUID(), lesionToday)
                        + "],'newLesionSeen':false,'systems':{'cvs':{'state':'NORMAL'}},'line':" + line + "}"),
                "ONC_LABS", json(labs),
                "ONC_ECOG", json("{'grade':" + ecog + "}"),
                "ONC_TOX", json(tox),
                "VITALS_LOG", json(vitals));
        return values::get;
    }

    private static final String GOOD_LABS = "{'drawnAt':'2026-11-12T04:00:00Z','values':{'hb':10.5,'anc':1800,"
            + "'plt':200,'cr':0.8,'bili':0.7,'alt':30,'lvef':62}}";
    private static final String TOX_NONE = "{'symptoms':[{'key':'NAUSEA','confirmedGrade':1},"
            + "{'key':'FEVER_LOW_COUNTS','confirmedGrade':0}],'events':[]}";
    private static final String VITALS_OK = "{'entries':[{'id':'a','temperature':{'value':36.8,'unit':'C'}}]}";
    private static final String PORT_OK = "{'device':'PORT','checks':['FLUSHES_FREELY','BLOOD_RETURN','NO_REDNESS_SWELLING']}";

    private static OncologyAssessment assess(Function<String, JsonNode> record, Double cycle1) {
        return OncologyAssessment.compute(record, cycle1, NOW, null);
    }

    @Test
    void aGoodRecordGivesBsaNoFailsAndTheSuggestionGiveAsPlannedTaggedC8() {
        OncologyAssessment a = assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE, VITALS_OK, PORT_OK), null);
        assertThat(a.bsa()).isEqualTo(1.63);
        assertThat(a.bsaUsed()).isEqualTo(1.63);
        assertThat(a.weightChangePct()).as("no earlier order: cycle 1 is today, the change is 0").isEqualTo(0.0);
        assertThat(a.recalculate()).isFalse();
        assertThat(a.fails()).isEmpty();
        assertThat(a.labsFresh()).isTrue();
        assertThat(a.fever()).isFalse();
        assertThat(a.worstGrade()).isEqualTo(1);
        assertThat(a.lineReady()).isTrue();
        assertThat(a.response().category()).isEqualTo("PARTIAL_RESPONSE");
        assertThat(a.suggestion()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(a.alerts()).isEmpty();
        assertThat(a.weightAndHeightRecorded()).isTrue();
    }

    @Test
    void aWeightChangeOfTenPercentFromCycleOneRecalculatesTaggedC8() {
        OncologyAssessment a = assess(record("160/54", "3.0", GOOD_LABS, "1", TOX_NONE, VITALS_OK, PORT_OK), 60.0);
        assertThat(a.cycle1WeightKg()).isEqualTo(60.0);
        assertThat(a.weightChangePct()).isEqualTo(-10.0);
        assertThat(a.recalculate()).isTrue();
        assertThat(a.alerts()).containsExactly("WEIGHT_CHANGE_10");
    }

    @Test
    void aFailedCountOrFeverSuggestsDelayAndFeverComesFromTheLatestVitalsEntryTaggedC8() {
        OncologyAssessment lowAnc = assess(record("160/60", "3.0", GOOD_LABS.replace("1800", "1000"), "1", TOX_NONE,
                VITALS_OK, PORT_OK), null);
        assertThat(lowAnc.suggestion()).isEqualTo("DELAY_1_WEEK");
        assertThat(lowAnc.alerts()).contains("LAB_OUT_OF_RANGE");
        OncologyAssessment fever = assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE,
                "{'entries':[{'id':'a','temperature':{'value':36.8,'unit':'C'}},{'id':'b','temperature':{'value':38.4,"
                        + "'unit':'C'}}]}", PORT_OK), null);
        assertThat(fever.fever()).isTrue();
        assertThat(fever.suggestion()).isEqualTo("DELAY_1_WEEK");
        OncologyAssessment fahrenheit = assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE,
                "{'entries':[{'id':'a','temperature':{'value':101.3,'unit':'F'}}]}", PORT_OK), null);
        assertThat(fahrenheit.fever()).isTrue();
    }

    @Test
    void stopForEcogThreeChangeForLowHeartReduceForGradeThreeTaggedC8() {
        assertThat(assess(record("160/60", "3.0", GOOD_LABS, "3", TOX_NONE, VITALS_OK, PORT_OK), null).suggestion())
                .isEqualTo("STOP");
        assertThat(assess(record("160/60", "3.0", GOOD_LABS.replace("'lvef':62", "'lvef':45"), "1", TOX_NONE, VITALS_OK,
                PORT_OK), null).suggestion()).isEqualTo("CHANGE_REGIMEN");
        assertThat(assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE.replace("'confirmedGrade':1",
                "'confirmedGrade':3"), VITALS_OK, PORT_OK), null)).satisfies(a -> {
            assertThat(a.suggestion()).isEqualTo("GIVE_REDUCED");
            assertThat(a.alerts()).contains("GRADE_3_PLUS");
        });
        assertThat(assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE.replace("'events':[]",
                "'events':['ADMITTED']"), VITALS_OK, PORT_OK), null).suggestion()).isEqualTo("GIVE_REDUCED");
        assertThat(assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE.replace("'events':[]",
                "'events':['ER_VISIT']"), VITALS_OK, PORT_OK), null).suggestion()).isEqualTo("GIVE_AS_PLANNED");
    }

    @Test
    void noEcogMeansNoSuggestionYetAndAPortWithoutBloodReturnRaisesTheAlertTaggedC8() {
        assertThat(assess(record("160/60", "3.0", GOOD_LABS, "null", TOX_NONE, VITALS_OK, PORT_OK), null).suggestion())
                .isNull();
        OncologyAssessment noReturn = assess(record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE, VITALS_OK,
                "{'device':'PORT','checks':['FLUSHES_FREELY']}"), null);
        assertThat(noReturn.lineReady()).isFalse();
        assertThat(noReturn.alerts()).containsExactly("PORT_NO_BLOOD_RETURN");
        assertThat(noReturn.reasons().get(5).text()).isEqualTo("Port: no blood return yet");
    }

    @Test
    void anEmptyRecordWorksOutNothingAndDoesNotFail() {
        OncologyAssessment a = OncologyAssessment.compute(c -> null, null, NOW, null);
        assertThat(a.bsa()).isNull();
        assertThat(a.labsFresh()).isNull();
        assertThat(a.suggestion()).isNull();
        assertThat(a.weightAndHeightRecorded()).isFalse();
    }

    // ---- the order ----

    private static OrderInputs inputs(String protocol, int level, String reasonKey, boolean allergy, boolean consent,
                                      String scheme, Map<String, Double> finals) {
        return new OrderInputs(protocol, level, reasonKey, null, finals, Set.of(), allergy, consent, scheme, null);
    }

    private static Computed compute(OrderInputs in, Protocol protocol, Function<String, JsonNode> record,
                                    ChemoInfo info, String decision, UUID checker) {
        return ChemoOrderCompute.compute(in, protocol, assess(record, info.cycle1WeightKg()), info, decision, checker);
    }

    private static Check check(Computed c, String key) {
        return c.checks().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
    }

    private static final Function<String, JsonNode> GOOD = record("160/60", "3.0", GOOD_LABS, "1", TOX_NONE, VITALS_OK,
            PORT_OK);

    @Test
    void theDosesAreCalculatedFromTheBsaUsedRoundedToTheVialStepAndEditsAreFlaggedTaggedC8() {
        Computed c = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, ChemoInfo.none(),
                "GIVE_AS_PLANNED", null);
        // BSA 1.63: doxorubicin 60 x 1.63 = 97.8 -> 100; cyclophosphamide 600 x 1.63 = 978 -> 980
        assertThat(c.drugs().get(0).line().calculatedMg()).isEqualTo(97.8);
        assertThat(c.drugs().get(0).line().roundedMg()).isEqualTo(100.0);
        assertThat(c.drugs().get(1).line().calculatedMg()).isEqualTo(978.0);
        assertThat(c.drugs().get(1).line().roundedMg()).isEqualTo(980.0);
        Computed edited = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of("doxorubicin", 90.0)), AC, GOOD,
                ChemoInfo.none(), "GIVE_AS_PLANNED", null);
        assertThat(edited.drugs().get(0).line().finalMg()).isEqualTo(90.0);
        assertThat(edited.drugs().get(0).line().edited()).isTrue();
        assertThat(edited.drugs().get(0).line().moreThan5Pct()).isTrue();
        assertThat(edited.fingerprint()).isNotEqualTo(c.fingerprint());
    }

    @Test
    void everyCheckPassesAndTheFingerprintIsStableTaggedC8() {
        Computed c = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, ChemoInfo.none(),
                "GIVE_AS_PLANNED", null);
        assertThat(c.checks()).hasSize(10);
        assertThat(c.allChecksPass()).isTrue();
        assertThat(c.fingerprint()).matches("^[0-9a-f]{64}$");
        Computed again = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, ChemoInfo.none(),
                "GIVE_AS_PLANNED", null);
        assertThat(again.fingerprint()).isEqualTo(c.fingerprint());
    }

    @Test
    void namingACheckerOrChangingTheDecisionOrLabsOrWeightChangesTheFingerprint() {
        OrderInputs in = inputs("AC", 100, null, true, true, "APPROVED", Map.of());
        String base = compute(in, AC, GOOD, ChemoInfo.none(), "GIVE_AS_PLANNED", null).fingerprint();
        assertThat(compute(in, AC, GOOD, ChemoInfo.none(), "GIVE_AS_PLANNED", UUID.randomUUID()).fingerprint())
                .isNotEqualTo(base);
        assertThat(compute(in, AC, GOOD, ChemoInfo.none(), "GIVE_REDUCED", null).fingerprint()).isNotEqualTo(base);
        Function<String, JsonNode> otherLabs = record("160/60", "3.0", GOOD_LABS.replace("1800", "1900"), "1", TOX_NONE,
                VITALS_OK, PORT_OK);
        assertThat(compute(in, AC, otherLabs, ChemoInfo.none(), "GIVE_AS_PLANNED", null).fingerprint())
                .isNotEqualTo(base);
        Function<String, JsonNode> heavier = record("160/61", "3.0", GOOD_LABS, "1", TOX_NONE, VITALS_OK, PORT_OK);
        assertThat(compute(in, AC, heavier, ChemoInfo.none(), "GIVE_AS_PLANNED", null).fingerprint())
                .isNotEqualTo(base);
        assertThat(compute(inputs("AC", 90, "FRAILTY", true, true, "APPROVED", Map.of()), AC, GOOD, ChemoInfo.none(),
                "GIVE_AS_PLANNED", null).fingerprint()).isNotEqualTo(base);
    }

    @Test
    void aReducedLevelNeedsAReasonAndALifetimeAbove450FailsTaggedC8() {
        Computed noReason = compute(inputs("AC", 90, null, true, true, "APPROVED", Map.of()), AC, GOOD,
                ChemoInfo.none(), "GIVE_REDUCED", null);
        assertThat(check(noReason, "REDUCTION_REASON").passed()).isFalse();
        Computed reasoned = compute(inputs("AC", 90, "NEUROPATHY", true, true, "APPROVED", Map.of()), AC, GOOD,
                ChemoInfo.none(), "GIVE_REDUCED", null);
        assertThat(check(reasoned, "REDUCTION_REASON").passed()).isTrue();
        ChemoInfo earlier = new ChemoInfo(60.0, 390.0, 0, 6, Set.of("AC"), "DRAFT", null, "AC", 100, null);
        Computed over = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, earlier,
                "GIVE_AS_PLANNED", null);
        assertThat(over.lifetime().totalMgM2()).isEqualTo(450.0);
        assertThat(check(over, "CUMULATIVE_DOSE").passed()).as("450 mg/m2 passes").isTrue();
        ChemoInfo more = new ChemoInfo(60.0, 391.0, 0, 6, Set.of("AC"), "DRAFT", null, "AC", 100, null);
        assertThat(check(compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, more,
                "GIVE_AS_PLANNED", null), "CUMULATIVE_DOSE").passed()).isFalse();
    }

    @Test
    void theOutsideDoseTypedOnTheOrderOverridesTheLatestOneAndSentDosesAreAdded() {
        ChemoInfo info = new ChemoInfo(60.0, 120.0, 100.0, 2, Set.of("AC"), "DRAFT", null, "AC", 100, null);
        Computed carried = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, info,
                "GIVE_AS_PLANNED", null);
        assertThat(carried.earlierMgM2()).isEqualTo(220.0);
        assertThat(carried.lifetime().totalMgM2()).isEqualTo(280.0);
        OrderInputs typed = new OrderInputs("AC", 100, null, null, Map.of(), Set.of(), true, true, "APPROVED", 10.0);
        assertThat(compute(typed, AC, GOOD, info, "GIVE_AS_PLANNED", null).earlierMgM2()).isEqualTo(130.0);
    }

    @Test
    void aNonAnthracyclineProtocolHasNoLifetimeLimitAndNoVesicantCheckTaggedH4() {
        Computed c = compute(inputs("PACLI_3W", 100, null, true, true, "APPROVED", Map.of()), PACLI, GOOD,
                ChemoInfo.none(), "GIVE_AS_PLANNED", null);
        assertThat(c.lifetime()).isNull();
        assertThat(check(c, "CUMULATIVE_DOSE").detail()).isEqualTo("No lifetime limit");
        assertThat(c.checks()).hasSize(9).noneMatch(x -> x.key().equals("LINE_VESICANT"));
        assertThat(c.thisCycleMgM2()).isZero();
    }

    @Test
    void consentComesFromAnEarlierSentOrderOfTheProtocolOrANewSignatureTaggedL3() {
        OrderInputs none = inputs("AC", 100, null, true, false, "APPROVED", Map.of());
        assertThat(check(compute(none, AC, GOOD, ChemoInfo.none(), "GIVE_AS_PLANNED", null), "CONSENT_REGIMEN")
                .passed()).isFalse();
        ChemoInfo sent = new ChemoInfo(60.0, 60, 0, 1, Set.of("AC"), "DRAFT", null, "AC", 100, null);
        assertThat(check(compute(none, AC, GOOD, sent, "GIVE_AS_PLANNED", null), "CONSENT_REGIMEN").passed()).isTrue();
        assertThat(check(compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD,
                ChemoInfo.none(), "GIVE_AS_PLANNED", null), "CONSENT_REGIMEN").passed()).isTrue();
    }

    @Test
    void theDecisionMustBeAGiveDecisionAndTheLabsMustBeFreshTaggedC8() {
        assertThat(check(compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, GOOD, ChemoInfo.none(),
                "DELAY_1_WEEK", null), "DECISION_RECORDED").passed()).isFalse();
        Function<String, JsonNode> old = record("160/60", "3.0", GOOD_LABS.replace("2026-11-12T04:00:00Z",
                "2026-11-08T04:00:00Z"), "1", TOX_NONE, VITALS_OK, PORT_OK);
        Computed c = compute(inputs("AC", 100, null, true, true, "APPROVED", Map.of()), AC, old, ChemoInfo.none(),
                "GIVE_AS_PLANNED", null);
        assertThat(check(c, "LABS_72H").passed()).isFalse();
        assertThat(c.allChecksPass()).isFalse();
    }
}
