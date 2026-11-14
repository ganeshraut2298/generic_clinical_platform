package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * SAH-73 (ADR-024 D7): what the specialty view takes from the patient's date of birth and effective age. The Child tab
 * of the dental chart follows the effective years (SAH-73-5); the Paediatrics view carries the months and days, whether
 * a date is recorded, and the date itself only for a role that may see it.
 */
class SpecialtyAgeViewTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-13T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final SpecialtyReferenceData DATA = new SpecialtyReferenceData(
            List.of(new Group("birth", "Birth", 1, 0, "DAYS", 6, "WEEKS")),
            List.of(new Vaccine("bcg", "birth", "BCG", 1)),
            List.of(new GrowthBand("WEIGHT", null, 18, 18, 8.6, "Below 3rd centile", true, 1)),
            List.of(new SpecialtyReferenceData.Milestone(18, "walks_alone", "Walks alone", "GROSS_MOTOR", 1)),
            List.of(), List.of());

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SpecialtyCtx ctx(String profile, LocalDate dob, Integer ageYears, boolean dobVisible) {
        Function<String, JsonNode> sibling = c -> "GROWTH".equals(c) ? json("{'weightKg':8.5}") : null;
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, UUID.randomUUID(), dob, "F", DATA, Tariff.none(), Set.of(),
                null, sibling, Hooks.none(), ageYears, dobVisible);
    }

    private static JsonNode dental(LocalDate dob, Integer ageYears) {
        return SpecialtyViewBuilder.build("dental", ctx("dental", dob, ageYears, true), null, null).path("dental");
    }

    private static JsonNode paeds(LocalDate dob, Integer ageYears, boolean dobVisible) {
        return SpecialtyViewBuilder.build("paeds", ctx("paeds", dob, ageYears, dobVisible), null, null).path("paeds");
    }

    // ---- dental Child tab (SAH-73-5) ----

    @Test
    void theChildTabOpensForTwelveYearsElevenMonthsAndNotForExactlyThirteenYears() {
        // 13 Nov 2026: born 14 Dec 2013 is 12 years 11 months; born 13 Nov 2013 is exactly 13 years
        assertThat(dental(LocalDate.of(2013, 12, 14), 12).path("childTabDefault").asBoolean()).isTrue();
        assertThat(dental(LocalDate.of(2013, 11, 13), 13).path("childTabDefault").asBoolean()).isFalse();
    }

    @Test
    void withoutADateOfBirthTheChildTabFollowsTheWholeNumberAge() {
        assertThat(dental(null, 12).path("childTabDefault").asBoolean()).isTrue();
        assertThat(dental(null, 13).path("childTabDefault").asBoolean()).isFalse();
        assertThat(dental(null, 5).path("childTabDefault").asBoolean()).isTrue();
        assertThat(dental(null, null).path("childTabDefault").asBoolean()).as("no patient").isFalse();
    }

    @Test
    void theOldConstructorWorksTheYearsOutFromTheDateOfBirthOnly() {
        SpecialtyCtx old = new SpecialtyCtx("dental", NOW, TODAY, TODAY, UUID.randomUUID(), LocalDate.of(2018, 3, 1),
                "M", DATA, Tariff.none(), Set.of(), null, c -> null, Hooks.none());
        assertThat(old.ageYears()).isEqualTo(8);
        assertThat(old.dateOfBirthVisible()).as("default deny").isFalse();
        assertThat(new SpecialtyCtx("dental", NOW, TODAY, TODAY, UUID.randomUUID(), null, "M", DATA, Tariff.none(),
                Set.of(), null, c -> null, Hooks.none()).ageYears()).isNull();
    }

    // ---- Paediatrics ----

    @Test
    void paediatricsCarriesMonthsDaysAndWhetherADateIsRecordedFromTheSameFunction() {
        // born 10 May 2025: on 13 Nov 2026 that is 18 months and 3 days
        JsonNode paeds = paeds(LocalDate.of(2025, 5, 10), 1, true);
        assertThat(paeds.path("dateOfBirthRecorded").asBoolean()).isTrue();
        assertThat(paeds.path("ageMonths").asInt()).isEqualTo(18);
        assertThat(paeds.path("ageDays").asInt()).isEqualTo(3);
        assertThat(paeds.path("growthNotes").get(0).path("label").asText()).isEqualTo("Below 3rd centile");
        assertThat(paeds.path("milestonesSetUp").asBoolean()).isTrue();
    }

    @Test
    void paediatricsWithoutADateOfBirthSaysNotRecordedAndNeverEstimatesFromTheStoredAge() {
        JsonNode paeds = paeds(null, 1, true);
        assertThat(paeds.path("dateOfBirthRecorded").asBoolean()).isFalse();
        assertThat(paeds.path("dateOfBirth").isNull()).as("visible role, not recorded: null").isTrue();
        assertThat(paeds.path("ageMonths").isNull()).isTrue();
        assertThat(paeds.path("ageDays").isNull()).isTrue();
        assertThat(paeds.path("growthNotes").get(0).path("label").asText())
                .isEqualTo("Centile not available for this age");
        assertThat(paeds.path("milestonesSetUp").asBoolean()).isFalse();
        assertThat(paeds.path("vaccines").get(0).path("state").asText()).isEqualTo("LATER");
        assertThat(paeds.path("dueTodayCount").asInt()).isZero();
        assertThat(paeds.path("overdueCount").asInt()).isZero();
    }

    @Test
    void theDateOfBirthIsInThePaediatricsViewOnlyForARoleThatMaySeeIt() {
        JsonNode visible = paeds(LocalDate.of(2025, 5, 10), 1, true);
        assertThat(visible.path("dateOfBirth").asText()).isEqualTo("2025-05-10");
        JsonNode hidden = paeds(LocalDate.of(2025, 5, 10), 1, false);
        assertThat(hidden.has("dateOfBirth")).as("absent, not null").isFalse();
        assertThat(hidden.path("dateOfBirthRecorded").asBoolean()).isTrue();
        assertThat(hidden.path("ageMonths").asInt()).isEqualTo(18);
    }

    @Test
    void theDateOfBirthIsInNoOtherProfilesView() {
        for (String profile : List.of("gm", "dental", "og", "physio", "surgery", "onco")) {
            JsonNode view = SpecialtyViewBuilder.build(profile, ctx(profile, LocalDate.of(2025, 5, 10), 1, true), null,
                    null);
            assertThat(view.toString()).as(profile).doesNotContain("2025-05-10");
        }
    }

    @Test
    void aDateOfBirthInTheFutureGivesNoAgeAndTheContextNeverPrintsIt() {
        JsonNode paeds = paeds(TODAY.plusDays(1), 0, true);
        assertThat(paeds.path("ageMonths").isNull()).isTrue();
        assertThat(ctx("paeds", LocalDate.of(2025, 5, 10), 1, true).toString()).doesNotContain("2025");
    }
}
