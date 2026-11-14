package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrMaster;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-48-1 and SAH-48-2 reads (ADR-022 D4, D5): the allergy classes, the doctor's medicine search with the allergy class
 * and the row defaults of each medicine, ranked by the doctor's own use, then the clinic's favourites, then the name; and
 * the clinic's favourites with their saved frequency, days and when. Doctors only; one list of favourites per clinic;
 * global reference data. Real database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrMedicinesIT extends EmrPhase3Support {

    private static JsonNode find(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("no medicine " + name);
    }

    private JsonNode search(Clinic clinic, Staff doctor, String query) throws Exception {
        return get(EMR_MEDICINES + (query == null ? "" : "?" + query), doctor.token(clinic));
    }

    @Test
    void theAllergyClassesAreSulfaAndPenicillinInOrderAndAreTheSameForEveryClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Staff doctorA = addDoctor(a, "General physician");
        Staff doctorB = addDoctor(b, "General physician");
        JsonNode classes = get(ALLERGY_CLASSES, doctorA.token(a));
        assertThat(classes).hasSize(2);
        assertThat(classes.get(0).get("classKey").asText()).isEqualTo("SULFA");
        assertThat(classes.get(0).get("label").asText()).isEqualTo("Sulfa drugs");
        assertThat(classes.get(1).get("classKey").asText()).isEqualTo("PENICILLIN");
        assertThat(classes.get(1).get("label").asText()).isEqualTo("Penicillin");
        assertThat(get(ALLERGY_CLASSES, doctorB.token(b))).isEqualTo(classes);
        assertThat(get(ALLERGY_CLASSES, a.owner()).size()).as("an owner with doctor details reads it").isEqualTo(2);
    }

    @Test
    void onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsReadsTheAllergyClassesTheMedicinesAndTheFavourites() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting Test", "General physician", "MBBS", "REG-" + shortId());
        for (String path : new String[] {ALLERGY_CLASSES, EMR_MEDICINES, FAVOURITES}) {
            get(path, doctor.token(clinic));
            get(path, visiting.token(clinic));
            for (String role : NOT_DOCTOR_ROLES) {
                Staff staff = addNonDoctor(clinic, role);
                problemOf(HttpMethod.GET, path, staff.token(clinic), null, 403, "forbidden");
                problemOf(HttpMethod.GET, path, staff.tokenClaiming(clinic, "DOCTOR"), null, 403, "forbidden");
            }
            problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
        }
        // an owner who has no doctor details of their own is refused, as everywhere in the EMR
        Clinic bare = bareOwnerClinic();
        assertThat(call(HttpMethod.GET, EMR_MEDICINES, bare.owner(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
        // a doctor whose membership was removed is refused at once
        Staff gone = addDoctor(clinic, "General physician");
        setMembershipStatusOf(clinic, gone, "INACTIVE");
        problemOf(HttpMethod.GET, EMR_MEDICINES, gone.token(clinic), null, 403, "forbidden");
    }

    @Test
    void theMedicineSearchIsAPrefixMatchPagedByTwentyAndEachResultCarriesItsClassAndDefaults() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode first = search(clinic, doctor, null);
        assertThat(first.get("pageSize").asInt()).isEqualTo(20);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("total").asLong()).isEqualTo(63);
        assertThat(first.get("items")).hasSize(20);
        assertThat(search(clinic, doctor, "page=2").get("items")).hasSize(20);
        assertThat(search(clinic, doctor, "page=3").get("items")).hasSize(3);
        assertThat(search(clinic, doctor, "page=4").get("items")).isEmpty();
        // prefix, ignoring case, trimmed; the total counts all pages
        JsonNode amox = search(clinic, doctor, "q=" + enc("  AMOX "));
        assertThat(amox.get("total").asLong()).isEqualTo(3);
        for (JsonNode m : amox.get("items")) {
            assertThat(m.get("name").asText().toLowerCase()).startsWith("amox");
            assertThat(m.get("allergyClass").asText()).isEqualTo("PENICILLIN");
        }
        JsonNode cotri = find(search(clinic, doctor, "q=co-trim").get("items"), SULFA_MEDICINE);
        assertThat(cotri.get("allergyClass").asText()).isEqualTo("SULFA");
        assertThat(cotri.get("form").asText()).isEqualTo("Tablet");
        assertThat(cotri.get("defaultFrequency").asText()).isEqualTo("1-0-1");
        assertThat(cotri.get("defaultDays").asInt()).isEqualTo(5);
        assertThat(cotri.get("defaultWhen").asText()).isEqualTo("AFTER_FOOD");
        assertThat(cotri.get("favourite").asBoolean()).isFalse();
        assertThat(cotri.get("useCount").asInt()).isZero();
        // seeded defaults of the mockup's favourites
        JsonNode panto = find(search(clinic, doctor, "q=panto").get("items"), "Pantoprazole 40 mg");
        assertThat(panto.get("defaultFrequency").asText()).isEqualTo("1-0-0");
        assertThat(panto.get("defaultDays").asInt()).isEqualTo(14);
        assertThat(panto.get("defaultWhen").asText()).isEqualTo("MORNING_EMPTY_STOMACH");
        assertThat(panto.get("allergyClass").isNull()).isTrue();
        JsonNode cetirizine = find(search(clinic, doctor, "q=" + enc("cetirizine 10")).get("items"), "Cetirizine 10 mg");
        assertThat(cetirizine.get("defaultFrequency").asText()).isEqualTo("0-0-1");
        assertThat(cetirizine.get("defaultWhen").asText()).isEqualTo("BEDTIME");
        // no seeded defaults: the SAH-7 schedule when it is one of the four, else 1-0-1; 5 days; after food (H6)
        JsonNode amoxicillin = find(search(clinic, doctor, "q=" + enc("amoxicillin 500 mg")).get("items"), PENICILLIN_MEDICINE);
        assertThat(amoxicillin.get("defaultFrequency").asText()).as("the schedule 1-1-1 of SAH-7").isEqualTo("1-1-1");
        assertThat(amoxicillin.get("defaultDays").asInt()).isEqualTo(5);
        assertThat(amoxicillin.get("defaultWhen").asText()).isEqualTo("AFTER_FOOD");
        JsonNode salbutamol = find(search(clinic, doctor, "q=salbutamol").get("items"), "Salbutamol 100 mcg per dose");
        assertThat(salbutamol.get("defaultFrequency").asText()).as("SOS is not one of the four").isEqualTo("1-0-1");
        assertThat(salbutamol.get("defaultDays").asInt()).isEqualTo(5);
        // exactly one medicine of the master has the sulfa class and exactly three the penicillin class
        assertThat(globalCount("select count(*) from opd.medicine where allergy_class = 'SULFA'")).isEqualTo(1);
        assertThat(globalCount("select count(*) from opd.medicine where allergy_class = 'PENICILLIN'")).isEqualTo(3);
        assertThat(globalCount("select count(*) from opd.medicine where default_frequency is not null")).isEqualTo(7);
    }

    @Test
    void thePercentUnderscoreAndBackslashAreLiteralAndATextOverOneHundredCharactersOrAPageOutsideTheRangeIs400() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        for (String text : new String[] {"%", "_", "\\", "Amox%", "A_ox", "'; drop table opd.medicine; --"}) {
            assertThat(search(clinic, doctor, "q=" + enc(text)).get("total").asLong()).as(text).isZero();
        }
        String tooLong = "q".repeat(101);
        JsonNode problem = problemOf(HttpMethod.GET, EMR_MEDICINES + "?q=" + tooLong, doctor.token(clinic), null, 400, "validation");
        assertThat(problem.toString()).doesNotContain(tooLong);
        for (String page : new String[] {"-1", "501", "abc"}) {
            assertThat(call(HttpMethod.GET, EMR_MEDICINES + "?page=" + page, doctor.token(clinic), null).andReturn().getResponse().getStatus())
                    .as(page).isEqualTo(400);
        }
        assertThat(search(clinic, doctor, "page=500").get("items")).isEmpty();
        assertThat(globalCount("select count(*) from opd.medicine")).as("the master is intact").isEqualTo(63);
    }

    @Test
    void theOrderIsTheDoctorsOwnUseThenTheClinicsFavouritesThenTheNameAndAnotherDoctorsUseDoesNotCount() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff other = addDoctor(clinic, "General physician");
        List<String> ids = itemKeys(EmrMaster.MEDICINES, 63);
        String used = ids.get(20);
        String favourite = ids.get(40);
        String othersUse = ids.get(45);
        ok(HttpMethod.PUT, "/api/v1/opd/medicine-favourites/" + favourite, clinic.owner(), null, 204);
        recordVisit(clinic, doctor.membershipId(), java.time.Instant.now(), ref(EmrMaster.MEDICINES, used));
        recordVisit(clinic, other.membershipId(), java.time.Instant.now(), ref(EmrMaster.MEDICINES, othersUse),
                ref(EmrMaster.MEDICINES, othersUse));
        JsonNode page = search(clinic, doctor, null);
        assertThat(page.get("items").get(0).get("medicineId").asText()).isEqualTo(used);
        assertThat(page.get("items").get(0).get("useCount").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(1).get("medicineId").asText()).isEqualTo(favourite);
        assertThat(page.get("items").get(1).get("favourite").asBoolean()).isTrue();
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < page.get("items").size(); i++) {
            rest.add(page.get("items").get(i).get("name").asText().toLowerCase());
        }
        assertThat(rest).isSorted();
        assertThat(page.get("items").get(2).get("medicineId").asText()).as("the other doctor's use does not rank here").isNotEqualTo(othersUse);
        // the other doctor's own use ranks for that doctor only
        JsonNode mine = search(clinic, other, null);
        assertThat(mine.get("items").get(0).get("medicineId").asText()).isEqualTo(othersUse);
        assertThat(mine.get("items").get(1).get("medicineId").asText()).as("then the favourite").isEqualTo(favourite);
        assertThat(search(clinic, doctor, null).get("items").get(0).get("medicineId").asText()).isEqualTo(used);
    }

    @Test
    void theFavouritesAreOneListPerClinicByNameWithTheirDefaultsAndAnotherClinicNeverSeesThem() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Staff doctorA = addDoctor(a, "General physician");
        Staff doctorB = addDoctor(b, "General physician");
        assertThat(get(FAVOURITES, doctorA.token(a))).isEmpty();
        String pantoprazole = medicineId("Pantoprazole 40 mg");
        String cotrimoxazole = medicineId(SULFA_MEDICINE);
        String amlodipine = medicineId("Amlodipine 5 mg");
        for (String id : new String[] {pantoprazole, cotrimoxazole, amlodipine}) {
            ok(HttpMethod.PUT, "/api/v1/opd/medicine-favourites/" + id, a.owner(), null, 204);
        }
        JsonNode favourites = get(FAVOURITES, doctorA.token(a));
        assertThat(texts(favourites, "name")).containsExactly("Amlodipine 5 mg", SULFA_MEDICINE, "Pantoprazole 40 mg");
        JsonNode amlo = favourites.get(0);
        assertThat(amlo.get("favourite").asBoolean()).isTrue();
        assertThat(amlo.get("defaultFrequency").asText()).isEqualTo("1-0-0");
        assertThat(amlo.get("defaultDays").asInt()).isEqualTo(30);
        assertThat(amlo.get("defaultWhen").asText()).isEqualTo("AFTER_FOOD");
        assertThat(favourites.get(1).get("allergyClass").asText()).isEqualTo("SULFA");
        // one list for the whole clinic: another doctor of the clinic reads the same list; another clinic has none
        Staff colleague = addDoctor(a, "Dentist");
        assertThat(get(FAVOURITES, colleague.token(a))).isEqualTo(favourites);
        assertThat(get(FAVOURITES, doctorB.token(b))).isEmpty();
        // removing a favourite takes it off the list
        ok(HttpMethod.DELETE, "/api/v1/opd/medicine-favourites/" + cotrimoxazole, a.owner(), null, 204);
        assertThat(texts(get(FAVOURITES, doctorA.token(a)), "name")).containsExactly("Amlodipine 5 mg", "Pantoprazole 40 mg");
    }

    @Test
    void theTwoNewGlobalTablesAreReadOnlyAndTheAllergyClassWritesAre403ForEveryRole() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
                problemOf(method, ALLERGY_CLASSES, clinic.as(role), "{\"classKey\":\"LATEX\",\"label\":\"Latex\"}", 403, "forbidden");
                problemOf(method, ALLERGY_CLASSES + "/SULFA", clinic.as(role), "{\"label\":\"Changed\"}", 403, "forbidden");
            }
        }
        assertThat(globalCount("select count(*) from opd.emr_allergy_class")).isEqualTo(2);
        assertThat(globalRows("select label from opd.emr_allergy_class order by position").get(0).get(0)).isEqualTo("Sulfa drugs");
    }
}
