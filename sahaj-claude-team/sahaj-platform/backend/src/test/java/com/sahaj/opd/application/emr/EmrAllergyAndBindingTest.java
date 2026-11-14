package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrAllergyCheck.Alert;
import com.sahaj.opd.application.emr.EmrMedicineRepository.MedicineRow;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrViews.MedicineOption;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmrAllergyAndBindingTest {

    private static final UUID SULFA_DRUG = UUID.randomUUID();
    private static final UUID ROW = UUID.randomUUID();

    private static EmrRecordDocument document(String allergyValues, String rxValues) {
        try {
            EmrRecordDocument doc = EmrRecordDocument.empty(EmrTestData.MAPPER);
            doc.put("allergy", (com.fasterxml.jackson.databind.node.ObjectNode) EmrTestData.MAPPER.readTree(allergyValues),
                    false, java.time.Instant.EPOCH, UUID.randomUUID());
            doc.put("rx", (com.fasterxml.jackson.databind.node.ObjectNode) EmrTestData.MAPPER.readTree(rxValues),
                    false, java.time.Instant.EPOCH, UUID.randomUUID());
            return doc;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String ALLERGY_SULFA = "{\"drug_allergy\":[{\"itemKey\":\"SULFA\",\"text\":\"Sulfa drugs\","
            + "\"newToList\":false}]}";

    private static String rx(String review) {
        return "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":[{\"id\":\"" + ROW
                + "\",\"medicineId\":\"" + SULFA_DRUG + "\",\"name\":\"Co-trimoxazole 960 mg\",\"frequency\":\"1-0-1\","
                + "\"days\":5,\"when\":\"AFTER_FOOD\",\"source\":\"SEARCH\",\"allergyReview\":" + review + "}]}}";
    }

    private static List<LayoutSection> layout() {
        FieldDefinition drug = new FieldDefinition("drug", "Drug", "SEARCH_LIST", EmrValueKind.COMPONENT, "", false,
                "MASTER", "medicines", true, List.of(), List.of(), null, null,
                new EmrModel.ComponentRef(EmrComponentBinding.PRESCRIPTION, null));
        return List.of(EmrTestData.section("rx", "COMMON", "TREATMENT", true, false, false, drug));
    }

    @Test
    void aSulfaDrugWithASulfaAllergyIsAnAlertUntilTheRowIsReviewed() {
        EmrRecordDocument doc = document(ALLERGY_SULFA, rx("null"));
        Set<String> classes = EmrAllergyCheck.visitClasses(doc, Set.of("SULFA", "PENICILLIN"));

        List<Alert> alerts = EmrAllergyCheck.alerts(layout(), doc, classes, id -> "SULFA");

        assertThat(classes).containsExactly("SULFA");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).reviewed()).isFalse();
        assertThat(EmrAllergyCheck.blocked(alerts)).isTrue();
        assertThat(alerts.get(0).toString()).doesNotContain("Co-trimoxazole");
    }

    @Test
    void aReviewForTheSameClassClearsTheBlockButAReviewForAnotherClassDoesNot() {
        String sulfaReview = "{\"allergyClass\":\"SULFA\",\"confirmed\":true,\"reviewedAt\":\"2026-11-12T06:00:00Z\"}";
        String otherReview = "{\"allergyClass\":\"PENICILLIN\",\"confirmed\":true}";
        Set<String> classes = Set.of("SULFA");

        List<Alert> reviewed = EmrAllergyCheck.alerts(layout(), document(ALLERGY_SULFA, rx(sulfaReview)), classes,
                id -> "SULFA");
        List<Alert> wrong = EmrAllergyCheck.alerts(layout(), document(ALLERGY_SULFA, rx(otherReview)), classes,
                id -> "SULFA");

        assertThat(reviewed.get(0).reviewed()).isTrue();
        assertThat(EmrAllergyCheck.blocked(reviewed)).isFalse();
        assertThat(EmrAllergyCheck.blocked(wrong)).isTrue();
    }

    @Test
    void noAllergyClassTypedAllergiesAndTypedMedicinesNeverTriggerTheCheck() {
        EmrRecordDocument typed = document("{\"drug_allergy\":[{\"itemKey\":null,\"text\":\"sulfa\",\"newToList\":true}]}",
                rx("null"));
        EmrRecordDocument none = document("{}", rx("null"));

        assertThat(EmrAllergyCheck.visitClasses(typed, Set.of("SULFA"))).isEmpty();
        assertThat(EmrAllergyCheck.alerts(layout(), none, Set.of(), id -> "SULFA")).isEmpty();
        assertThat(EmrAllergyCheck.alerts(layout(), typed, Set.of("SULFA"), id -> null)).isEmpty();
    }

    @Test
    void whenTheClassesChangeEveryReviewIsCleared() throws Exception {
        JsonNode values = EmrTestData.MAPPER.readTree(rx("{\"allergyClass\":\"SULFA\",\"confirmed\":true}"));

        assertThat(EmrAllergyCheck.clearReviews(values)).isTrue();
        assertThat(values.path("drug").path("rows").get(0).path("allergyReview").isNull()).isTrue();
        assertThat(EmrAllergyCheck.clearReviews(values)).isFalse();
    }

    // ---- binding ----

    private static FieldDefinition f(String key, String type, String source, String master) {
        return new FieldDefinition(key, key, type, EmrFieldType.valueKind(type), "", true, source, master, true,
                List.of(), List.of(), null, null);
    }

    @Test
    void complaintsBecomeAListAndTheirSiblingsAreCoveredAndNeverRequired() {
        List<FieldDefinition> bound = EmrComponentBinding.bind("complaints", List.of(
                f("complaint", "SEARCH_LIST", "MASTER", "complaints"), f("duration", "NUMBER_UNIT", "NONE", null),
                f("severity", "DROPDOWN", "FIELD", null), f("notes", "TEXT", "NONE", null)));

        assertThat(bound.get(0).valueKind()).isEqualTo(EmrValueKind.COMPONENT);
        assertThat(bound.get(0).component().listKind()).isEqualTo("COMPLAINTS");
        assertThat(bound.get(1).valueKind()).isEqualTo(EmrValueKind.COVERED);
        assertThat(bound.get(1).required()).isFalse();
        assertThat(bound.get(1).fillable()).isFalse();
        assertThat(bound.get(2).valueKind()).isEqualTo(EmrValueKind.COVERED);
        assertThat(bound.get(3).valueKind()).isEqualTo(EmrValueKind.TEXT);
    }

    @Test
    void diagnosisKeepsOneListAndCoversTheFinalDiagnosisAndTheType() {
        List<FieldDefinition> bound = EmrComponentBinding.bind("diagnosis", List.of(
                f("provisional_diagnosis", "SEARCH_LIST", "MASTER", "diagnoses"),
                f("final_diagnosis", "SEARCH_LIST", "MASTER", "diagnoses"), f("type", "DROPDOWN", "FIELD", null)));

        assertThat(bound.get(0).component().listKind()).isEqualTo("DIAGNOSIS");
        assertThat(bound.get(1).valueKind()).isEqualTo(EmrValueKind.COVERED);
        assertThat(bound.get(2).valueKind()).isEqualTo(EmrValueKind.COVERED);
    }

    @Test
    void medicinesBecomeThePrescriptionServicesAListAndBodyMapsAMap() {
        assertThat(EmrComponentBinding.bind("rx", List.of(f("drug", "SEARCH_LIST", "MASTER", "medicines"),
                f("dose_frequency", "PATTERN", "NONE", null), f("instructions", "TEXT", "NONE", null)))
                .stream().map(FieldDefinition::valueKind).toList())
                .containsExactly(EmrValueKind.COMPONENT, EmrValueKind.COVERED, EmrValueKind.COVERED);
        assertThat(EmrComponentBinding.bind("procedures", List.of(f("procedure", "SEARCH_LIST", "SERVICES", null)))
                .get(0).component().listKind()).isEqualTo("SERVICES");
        assertThat(EmrComponentBinding.bind("exam", List.of(f("map", "BODY_MAP", "NONE", null))).get(0).component()
                .type()).isEqualTo("BODY_MAP");
        assertThat(EmrComponentBinding.bind("allergy", List.of(f("drug_allergy", "SEARCH_LIST", "NONE", null)))
                .get(0).valueKind()).isEqualTo(EmrValueKind.ITEM_LIST);
    }

    // ---- medicine options ----

    private static MedicineRow medicine(String schedule, String frequency, Integer days, String when) {
        return new MedicineRow(UUID.randomUUID(), "Drug", "Tablet", schedule, null, frequency, days, when, false, 0);
    }

    @Test
    void aMedicineWithNoSavedValuesStartsAtItsScheduleOrOneZeroOneFiveDaysAfterFood() {
        MedicineOption bySchedule = EmrMedicineService.option(medicine("0-0-1", null, null, null));
        MedicineOption fallback = EmrMedicineService.option(medicine("SOS", null, null, null));
        MedicineOption seeded = EmrMedicineService.option(medicine("1-0-1", "1-0-0", 14, "MORNING_EMPTY_STOMACH"));

        assertThat(bySchedule.defaultFrequency()).isEqualTo("0-0-1");
        assertThat(fallback.defaultFrequency()).isEqualTo("1-0-1");
        assertThat(fallback.defaultDays()).isEqualTo(5);
        assertThat(fallback.defaultWhen()).isEqualTo("AFTER_FOOD");
        assertThat(seeded.defaultDays()).isEqualTo(14);
        assertThat(seeded.defaultWhen()).isEqualTo("MORNING_EMPTY_STOMACH");
    }

    @Test
    void theMedicineSearchPassesTheDoctorsMembershipAndRefusesLongText() {
        EmrAccess access = mock(EmrAccess.class);
        EmrMedicineRepository repo = mock(EmrMedicineRepository.class);
        UUID tenant = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        ClinicUser user = new ClinicUser(UUID.randomUUID(), tenant, "DOCTOR");
        when(access.requireConsultationWrite(user)).thenReturn(new TeamMember(member, "Dr", "DOCTOR", true, 0));
        when(repo.search(eq(tenant), eq(member), eq("tel"), eq(20), eq(20)))
                .thenReturn(new EmrMedicineRepository.MedicinePage(List.of(medicine("1-0-1", null, null, null)), 41));
        EmrMedicineService service = new EmrMedicineService(access, repo);

        EmrViews.MedicinePage page = service.search(user, " tel ", 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(41);
        assertThat(page.pageSize()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.search(user, "x".repeat(101), 0))
                .isInstanceOf(com.sahaj.platform.api.InvalidInputException.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).search(any(), any(), eq("x".repeat(101)),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
