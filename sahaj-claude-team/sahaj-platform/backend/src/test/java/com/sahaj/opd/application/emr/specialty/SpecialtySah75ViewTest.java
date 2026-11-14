package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Cycle;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.JourneyRecord;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.OncoExtra;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.PlanInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * SAH-75-6, SAH-75-7 and SAH-75-11 (ADR-026 D6, D7): the server-computed parts of the specialty view: the Dental medical
 * alerts and the oncology journey strip, the watch chip and the day-care bed. Values from the EMR mockup, not reviewed.
 */
class SpecialtySah75ViewTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-14T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SpecialtyCtx ctx(String profile, Function<String, JsonNode> sibling, Hooks hooks) {
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, MEMBER, null, "F", SpecialtyReferenceData.empty(),
                Tariff.none(), Set.of(), null, sibling, hooks);
    }

    // ---- Dental medical alerts ----

    private static JsonNode dentalView(String medhx, String habits) {
        Function<String, JsonNode> sibling = component -> "DENTAL_MED_HISTORY".equals(component) && medhx != null
                ? json(medhx) : null;
        Hooks hooks = new Hooks(key -> "dental_hx".equals(key) && habits != null ? json(habits) : null, c -> List.of(),
                List::of, () -> false, () -> null, ChemoInfo::none);
        return SpecialtyViewBuilder.build("dental", ctx("dental", sibling, hooks), null, null).path("dental");
    }

    @Test
    void theBannersFollowTheStoredCardAndTheHabitsOfTheSameRecordInTheDrawnOrder() {
        JsonNode dental = dentalView("{'component':'DENTAL_MED_HISTORY','schemaVersion':1,'conditions':['DIABETES'],"
                + "'medicines':['ASPIRIN'],'allergies':['PENICILLIN'],'reviewedWithPatient':false}",
                "{'habits':['smoking','alcohol']}");

        JsonNode alerts = dental.path("medicalAlerts");

        assertThat(alerts).extracting(a -> a.path("key").asText()).containsExactly("PENICILLIN_ALLERGY",
                "BLEEDING_RISK", "DIABETES", "TOBACCO_USE");
        assertThat(alerts.get(0).path("title").asText()).isEqualTo("Penicillin allergy.");
        assertThat(alerts.get(3).path("text").asText()).isEqualTo(
                "Screen the mouth for pre-cancer patches and counsel to quit.");
    }

    @Test
    void withNoCardAndNoHabitsTheListIsEmptyNeverMissing() {
        JsonNode dental = dentalView(null, null);

        assertThat(dental.has("medicalAlerts")).isTrue();
        assertThat(dental.path("medicalAlerts")).isEmpty();
    }

    // ---- the oncology strip ----

    private static Hooks oncoHooks(OncoExtra extra) {
        return new Hooks(key -> null, c -> List.of(), List::of, () -> false, () -> null, ChemoInfo::none, () -> extra);
    }

    private static JsonNode onco(OncoExtra extra, String decision) {
        return SpecialtyViewBuilder.build("onco", ctx("onco", c -> null, oncoHooks(extra)), null,
                decision == null ? null : json(decision)).path("onco");
    }

    private static Cycle cycle(String regimen, String state) {
        return new Cycle(UUID.randomUUID(), regimen, state);
    }

    private static JourneyRecord journey() {
        return new JourneyRecord(3, "IIB", "cT2 cN2 M0", "ER+ PR+ HER2−", 28, "BRCA pending", "CURATIVE", "NEOADJUVANT",
                1, LocalDate.of(2026, 7, 26), Instant.parse("2026-11-10T05:00:00Z"), "Dr Rao");
    }

    @Test
    void withNothingRecordedTheStripIsWholeWithNullPartsAndTheWatchChipIsHidden() {
        JsonNode onco = onco(OncoExtra.none(), null);

        assertThat(onco.path("journey").has("record")).isTrue();
        assertThat(onco.path("journey").path("record").isNull()).isTrue();
        assertThat(onco.path("journey").path("diagnosisText").isNull()).isTrue();
        assertThat(onco.path("journey").path("plan").isNull()).isTrue();
        assertThat(onco.path("watch").path("doxorubicinGivenMgM2").isNull()).isTrue();
        assertThat(onco.path("watch").path("doxorubicinLimitMgM2").asInt()).isEqualTo(450);
        assertThat(onco.path("dayCareBed").isNull()).isTrue();
    }

    @Test
    void theJourneyRecordIsCarriedWithTheEditorsNameAndTheDiagnosisText() {
        JsonNode strip = onco(new OncoExtra(journey(), "Carcinoma left breast, invasive ductal", null, null, null), null)
                .path("journey");

        JsonNode record = strip.path("record");
        assertThat(record.path("version").asInt()).isEqualTo(3);
        assertThat(record.path("stageGroup").asText()).isEqualTo("IIB");
        assertThat(record.path("tnmText").asText()).isEqualTo("cT2 cN2 M0");
        assertThat(record.path("ki67Pct").asInt()).isEqualTo(28);
        assertThat(record.path("boardDate").asText()).isEqualTo("2026-07-26");
        assertThat(record.path("recordedByName").asText()).isEqualTo("Dr Rao");
        assertThat(strip.path("diagnosisText").asText()).isEqualTo("Carcinoma left breast, invasive ductal");
    }

    @Test
    void thePlanHasTheRegimenRunTitleTheChipsAndTheCycleNumberOfTodaysCycle() {
        List<Cycle> cycles = List.of(cycle("AC", "GIVEN"), cycle("AC", "GIVEN"), cycle("AC", "TODAY"), cycle("AC", "PLANNED"),
                cycle("PACLI_WEEKLY", "PLANNED"));

        JsonNode plan = onco(new OncoExtra(null, null, new PlanInfo(COURSE, "Chemotherapy course", cycles), null, null),
                null).path("journey").path("plan");

        assertThat(plan.path("courseId").asText()).isEqualTo(COURSE.toString());
        assertThat(plan.path("title").asText()).isEqualTo("AC ×4 → Paclitaxel ×1");
        assertThat(plan.path("cycleNo").asInt()).isEqualTo(3);
        assertThat(plan.path("cycleCount").asInt()).isEqualTo(5);
        assertThat(plan.path("cycles")).extracting(c -> c.path("label").asText()).containsExactly("AC1", "AC2", "AC3",
                "AC4", "P1");
        assertThat(plan.path("cycles")).extracting(c -> c.path("state").asText()).containsExactly("GIVEN", "GIVEN", "TODAY",
                "PLANNED", "PLANNED");
    }

    @Test
    void todaysCycleIsDelayedOnlyWhileTheRecordedDecisionIsDelayOneWeek() {
        PlanInfo plan = new PlanInfo(COURSE, "Chemotherapy course", List.of(cycle("TC", "GIVEN"), cycle("TC", "TODAY")));

        JsonNode delayed = onco(new OncoExtra(null, null, plan, null, null), "{'choice':'DELAY_1_WEEK'}")
                .path("journey").path("plan");
        JsonNode given = onco(new OncoExtra(null, null, plan, null, null), "{'choice':'GIVE_AS_PLANNED'}")
                .path("journey").path("plan");

        assertThat(delayed.path("cycles").get(1).path("state").asText()).isEqualTo("DELAYED");
        assertThat(delayed.path("cycles").get(0).path("state").asText()).isEqualTo("GIVEN");
        assertThat(given.path("cycles").get(1).path("state").asText()).isEqualTo("TODAY");
    }

    @Test
    void withoutARegimenTheTitleIsTheCourseNameAndTheChipsAreCAndTheNumber() {
        PlanInfo plan = new PlanInfo(COURSE, "Chemotherapy course", List.of(cycle(null, "GIVEN"), cycle(null, "PLANNED"),
                cycle(null, "MISSED")));

        JsonNode strip = onco(new OncoExtra(null, null, plan, null, null), null).path("journey").path("plan");

        assertThat(strip.path("title").asText()).isEqualTo("Chemotherapy course");
        assertThat(strip.path("cycles")).extracting(c -> c.path("label").asText()).containsExactly("C1", "C2", "C3");
        assertThat(strip.path("cycleNo").asInt()).as("the next planned cycle when none is today").isEqualTo(2);
        assertThat(strip.path("cycles").get(2).path("state").asText()).isEqualTo("MISSED");
    }

    @Test
    void whenEveryCycleIsGivenThereIsNoCycleNumber() {
        PlanInfo plan = new PlanInfo(COURSE, "Chemotherapy course", List.of(cycle("AC", "GIVEN")));

        JsonNode strip = onco(new OncoExtra(null, null, plan, null, null), null).path("journey").path("plan");

        assertThat(strip.path("cycleNo").isNull()).isTrue();
        assertThat(strip.path("cycleCount").asInt()).isEqualTo(1);
    }

    @Test
    void theWatchChipAndTheDayCareBedComeFromTheFactsAndTheSchemeChipHasNoField() {
        JsonNode onco = onco(new OncoExtra(null, null, null, 180.0, "B-12"), null);

        assertThat(onco.path("watch").path("doxorubicinGivenMgM2").asDouble()).isEqualTo(180.0);
        assertThat(onco.path("watch").path("doxorubicinLimitMgM2").asInt()).isEqualTo(450);
        assertThat(onco.path("dayCareBed").asText()).isEqualTo("B-12");
        assertThat(onco.path("watch").fieldNames()).toIterable().containsExactlyInAnyOrder("doxorubicinGivenMgM2",
                "doxorubicinLimitMgM2");
        assertThat(onco.toString()).doesNotContain("scheme");
    }
}
