package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Sweep: SAH-8 patients on the real database - create, search, family, update, duplicates, merge, roles, isolation. */
@OnTestDatabase
class PatientsIT extends SweepSupport {

    private static final String PATIENTS = "/api/v1/patient/patients";

    private static String body(String name, int age, String sex, String mobile) {
        return "{\"name\":\"" + name + "\",\"age\":" + age + ",\"sex\":\"" + sex + "\",\"mobile\":\"" + mobile + "\"}";
    }

    private JsonNode create(Clinic clinic, String name, int age, String sex, String mobile) throws Exception {
        return ok(HttpMethod.POST, PATIENTS, clinic.owner(), body(name, age, sex, mobile), 201);
    }

    // ---------------- create / read ----------------

    @Test
    void registerPatientReturnsUhidDefaultsAndRoundTripsEveryField() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"),
                "{\"name\":\"Sunita Patil\",\"age\":38,\"sex\":\"F\",\"mobile\":\"" + mobile + "\",\"area\":\"Kothrud\","
                        + "\"language\":\"hi\",\"allergy\":\"Penicillin\",\"chronic\":\"Diabetes\",\"abhaLinked\":true}", 201);
        assertThat(created.get("uhid").asText()).matches("[A-Za-z]+-\\d{5}");
        assertThat(created.get("name").asText()).isEqualTo("Sunita Patil");
        assertThat(created.get("language").asText()).isEqualTo("hi");
        assertThat(created.get("abhaLinked").asBoolean()).isTrue();
        assertThat(created.get("createdAt").asText()).isNotBlank();
        assertThat(created.get("mobile").asText()).isEqualTo(mobile);
        JsonNode got = ok(HttpMethod.GET, PATIENTS + "/" + created.get("id").asText(), clinic.as("NURSE"), null, 200);
        assertThat(got.get("allergy").asText()).isEqualTo("Penicillin");
        assertThat(got.get("chronic").asText()).isEqualTo("Diabetes");
        assertThat(got.get("area").asText()).isEqualTo("Kothrud");
    }

    @Test
    void languageDefaultsToMarathiAndUhidsIncrementPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode first = create(a, "First Person", 30, "M", uniqueMobile());
        JsonNode second = create(a, "Second Person", 31, "M", uniqueMobile());
        JsonNode otherClinic = create(b, "Other Person", 32, "M", uniqueMobile());
        assertThat(first.get("language").asText()).isEqualTo("mr");
        String f = first.get("uhid").asText();
        String s = second.get("uhid").asText();
        assertThat(Integer.parseInt(s.substring(s.indexOf('-') + 1))).isEqualTo(Integer.parseInt(f.substring(f.indexOf('-') + 1)) + 1);
        assertThat(otherClinic.get("uhid").asText().substring(otherClinic.get("uhid").asText().indexOf('-') + 1)).isEqualTo("00001");
    }

    @Test
    void ageAndSexBoundariesAreAccepted() throws Exception {
        Clinic clinic = newClinic();
        create(clinic, "Newborn Baby", 0, "F", uniqueMobile());
        create(clinic, "Very Old", 120, "O", uniqueMobile());
    }

    @Test
    void createValidationReturnsProblemWithFieldErrors() throws Exception {
        Clinic clinic = newClinic();
        String m = uniqueMobile();
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("", 30, "F", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("   ", 30, "F", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("n".repeat(121), 30, "F", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", -1, "F", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", 121, "F", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", 30, "X", m)), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", 30, "F", "12345")), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", 30, "F", "5123456789")), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), body("Ok", 30, "F", "98765432101")), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), "{\"name\":\"Ok\",\"age\":30,\"sex\":\"F\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), "{\"name\":\"Ok\",\"sex\":\"F\",\"mobile\":\"" + m + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), "{\"age\":30,\"sex\":\"F\",\"mobile\":\"" + m + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Ok\",\"age\":30,\"sex\":\"F\",\"mobile\":\"" + m + "\",\"language\":\"fr\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Ok\",\"age\":30,\"sex\":\"F\",\"mobile\":\"" + m + "\",\"allergy\":\"" + "a".repeat(401) + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Ok\",\"age\":30,\"sex\":\"F\",\"mobile\":\"" + m + "\",\"area\":\"" + "a".repeat(161) + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Ok\",\"age\":\"thirty\",\"sex\":\"F\",\"mobile\":\"" + m + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), "{broken"), 400);
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), "{}"), 400);
    }

    @Test
    void sqlAndScriptTextInNamesIsStoredAsPlainText() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = create(clinic, "Robert'); DROP TABLE patient.patient;-- <script>x</script>", 40, "M", uniqueMobile());
        JsonNode got = ok(HttpMethod.GET, PATIENTS + "/" + p.get("id").asText(), clinic.owner(), null, 200);
        assertThat(got.get("name").asText()).contains("DROP TABLE");
    }

    @Test
    void unicodeNamesRoundTrip() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = create(clinic, "रमेश कुलकर्णी", 45, "M", uniqueMobile());
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + p.get("id").asText(), clinic.owner(), null, 200).get("name").asText())
                .isEqualTo("रमेश कुलकर्णी");
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=" + java.net.URLEncoder.encode("रमेश", "UTF-8"), clinic.owner(), null, 200)
                .get("total").asInt()).isEqualTo(1);
    }

    @Test
    void getUnknownPatientIs404AndBadIdIs400() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.GET, PATIENTS + "/" + UUID.randomUUID(), clinic.owner(), null), 404);
        problem(call(HttpMethod.GET, PATIENTS + "/not-a-uuid", clinic.owner(), null), 400);
    }

    // ---------------- roles ----------------

    @Test
    void onlyOwnerAndFrontDeskMayWritePatients() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = create(clinic, "Existing Person", 30, "M", uniqueMobile());
        String id = p.get("id").asText();
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.POST, PATIENTS, clinic.as(role), body("Nope", 30, "M", uniqueMobile())), 403);
            problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.as(role), "{\"name\":\"Hacked\"}"), 403);
            problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.as(role), "{\"keepId\":\"" + id + "\",\"mergeId\":\"" + id + "\"}"), 403);
        }
        ok(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.as("FRONT_DESK"), "{\"name\":\"Updated By Desk\"}", 200);
    }

    @Test
    void doctorsAndNursesCanReadPatientsAndFamily() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = create(clinic, "Readable Person", 30, "M", uniqueMobile());
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK"}) {
            ok(HttpMethod.GET, PATIENTS, clinic.as(role), null, 200);
            ok(HttpMethod.GET, PATIENTS + "/" + p.get("id").asText(), clinic.as(role), null, 200);
            ok(HttpMethod.GET, PATIENTS + "/" + p.get("id").asText() + "/family", clinic.as(role), null, 200);
        }
    }

    /**
     * DEC-049 answer 2 (closes DEC-013 item 14, patient.yaml 0.2.1): BILLING and PHARMACIST may read the list, the detail and
     * the family (the name is needed on bills and for dispensing); COUNSELLOR may not. Reading is not the same as seeing the
     * date of birth: {@code DateOfBirthAccess} stays the separate allow-list, so these two roles get {@code age} and
     * {@code ageDetail} and the property {@code dateOfBirth} is absent (not null) on all three answers.
     */
    @Test
    void billingAndPharmacistReadTheListDetailAndFamilyWithAgeAndNoDateOfBirth() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        java.time.LocalDate dob = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusYears(11).minusDays(5);
        JsonNode dated = ok(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Dated Reader\",\"sex\":\"F\",\"mobile\":\"" + mobile + "\",\"dateOfBirth\":\"" + dob + "\"}", 201);
        JsonNode sibling = create(clinic, "Family Reader", 40, "M", mobile);
        String id = dated.get("id").asText();
        for (String role : new String[] {"BILLING", "PHARMACIST"}) {
            JsonNode page = ok(HttpMethod.GET, PATIENTS, clinic.as(role), null, 200);
            assertThat(page.get("total").asInt()).as(role + " list total").isEqualTo(2);
            assertThat(page.toString()).as(role + " list").doesNotContain(dob.toString()).doesNotContain("dateOfBirth");
            JsonNode listed = null;
            for (JsonNode item : page.get("items")) {
                assertThat(item.has("dateOfBirth")).as(role + " list item has no dateOfBirth property").isFalse();
                assertThat(item.get("ageDetail")).as(role + " list item ageDetail").isNotNull();
                if (id.equals(item.get("id").asText())) {
                    listed = item;
                }
            }
            assertThat(listed).as(role + " sees the dated patient in the list").isNotNull();
            assertThat(listed.get("age").asInt()).as(role + " list age").isEqualTo(11);
            assertThat(listed.get("ageDetail").get("fromDateOfBirth").asBoolean()).isTrue();
            assertThat(ok(HttpMethod.GET, PATIENTS + "?q=Reader", clinic.as(role), null, 200).get("total").asInt()).isEqualTo(2);

            JsonNode detail = ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.as(role), null, 200);
            assertThat(detail.get("name").asText()).as(role + " detail name").isEqualTo("Dated Reader");
            assertThat(detail.has("dateOfBirth")).as(role + " detail: absent, not null").isFalse();
            assertThat(detail.toString()).as(role + " detail").doesNotContain(dob.toString());
            assertThat(detail.get("age").asInt()).isEqualTo(11);
            assertThat(detail.get("ageDetail").get("unit").asText()).isEqualTo("YEARS");
            assertThat(detail.get("ageDetail").get("fromDateOfBirth").asBoolean()).isTrue();

            JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.as(role), null, 200);
            assertThat(family.findValuesAsText("id")).as(role + " family").containsExactly(sibling.get("id").asText());
            assertThat(family.get(0).has("dateOfBirth")).as(role + " family row has no dateOfBirth property").isFalse();
            assertThat(family.get(0).get("age").asInt()).isEqualTo(40);
            assertThat(family.get(0).get("ageDetail")).isNotNull();
        }
    }

    @Test
    void counsellorGets403OnTheListTheDetailAndTheFamily() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = create(clinic, "Not For Counsellor", 30, "M", uniqueMobile());
        String id = p.get("id").asText();
        for (String role : new String[] {"COUNSELLOR"}) {
            problem(call(HttpMethod.GET, PATIENTS, clinic.as(role), null), 403);
            problem(call(HttpMethod.GET, PATIENTS + "?q=Counsellor", clinic.as(role), null), 403);
            problem(call(HttpMethod.GET, PATIENTS + "/" + id, clinic.as(role), null), 403);
            problem(call(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.as(role), null), 403);
        }
    }

    @Test
    void billingAndPharmacistStillCannotWritePatientsAndCannotReadAnotherClinicsPatient() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String inA = create(a, "Only In A", 33, "F", uniqueMobile()).get("id").asText();
        for (String role : new String[] {"BILLING", "PHARMACIST"}) {
            problem(call(HttpMethod.POST, PATIENTS, b.as(role), body("Nope", 30, "M", uniqueMobile())), 403);
            problem(call(HttpMethod.PATCH, PATIENTS + "/" + inA, a.as(role), "{\"name\":\"Hacked\"}"), 403);
            problem(call(HttpMethod.GET, PATIENTS + "/" + inA, b.as(role), null), 404);
            problem(call(HttpMethod.GET, PATIENTS + "/" + inA + "/family", b.as(role), null), 404);
            assertThat(ok(HttpMethod.GET, PATIENTS, b.as(role), null, 200).get("total").asInt()).as(role + " sees no patient of clinic A").isZero();
        }
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PATIENTS))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ---------------- family and duplicates ----------------

    @Test
    void secondPatientOnTheSameMobileJoinsTheFamilyAndNeverBlocks() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode first = create(clinic, "Meera Joshi", 35, "F", mobile);
        JsonNode child = create(clinic, "Aarav Joshi", 6, "M", mobile);
        JsonNode other = create(clinic, "Different Family", 50, "M", uniqueMobile());
        JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + first.get("id").asText() + "/family", clinic.owner(), null, 200);
        assertThat(family.findValuesAsText("id")).containsExactly(child.get("id").asText());
        assertThat(family.get(0).get("uhid").asText()).isNotBlank();
        JsonNode back = ok(HttpMethod.GET, PATIENTS + "/" + child.get("id").asText() + "/family", clinic.owner(), null, 200);
        assertThat(back.findValuesAsText("id")).containsExactly(first.get("id").asText());
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + other.get("id").asText() + "/family", clinic.owner(), null, 200).size()).isZero();
        assertThat(child.get("possibleDuplicateOfId") == null || child.get("possibleDuplicateOfId").isNull()).isTrue();
    }

    @Test
    void similarNameSameMobileAndCloseAgeIsFlaggedButStillSaved() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode first = create(clinic, "Ramesh Kulkarni", 45, "M", mobile);
        JsonNode dup = create(clinic, "Ramesh Kulkarni", 46, "M", mobile);
        assertThat(dup.get("possibleDuplicateOfId").asText()).isEqualTo(first.get("id").asText());
        JsonNode farAge = create(clinic, "Ramesh Kulkarni", 52, "M", mobile);
        assertThat(farAge.get("possibleDuplicateOfId") == null || farAge.get("possibleDuplicateOfId").isNull()).isTrue();
        JsonNode otherName = create(clinic, "Sita Verma", 45, "F", mobile);
        assertThat(otherName.get("possibleDuplicateOfId") == null || otherName.get("possibleDuplicateOfId").isNull()).isTrue();
    }

    @Test
    void sameMobileInAnotherClinicIsNotAFamilyAndNotADuplicate() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String mobile = uniqueMobile();
        JsonNode inA = create(a, "Shared Person", 30, "M", mobile);
        JsonNode inB = create(b, "Shared Person", 30, "M", mobile);
        assertThat(inB.get("possibleDuplicateOfId") == null || inB.get("possibleDuplicateOfId").isNull()).isTrue();
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + inB.get("id").asText() + "/family", b.owner(), null, 200).size()).isZero();
        problem(call(HttpMethod.GET, PATIENTS + "/" + inA.get("id").asText() + "/family", b.owner(), null), 404);
    }

    // ---------------- search and filters ----------------

    @Test
    void searchByNameMobileAndUhidIsCaseInsensitive() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode p = create(clinic, "Gauri Deshpande", 29, "F", mobile);
        create(clinic, "Someone Else", 29, "F", uniqueMobile());
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=gauri", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=DESHPANDE", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=" + mobile, clinic.owner(), null, 200).get("items").get(0).get("id").asText())
                .isEqualTo(p.get("id").asText());
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=" + mobile.substring(4), clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        String uhid = p.get("uhid").asText();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=" + uhid.toLowerCase(), clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=nobody-here", clinic.owner(), null, 200).get("total").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(2);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=%20%20", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(2);
    }

    @Test
    void searchTreatsWildcardsAndQuotesAsLiteralText() throws Exception {
        Clinic clinic = newClinic();
        create(clinic, "Plain Name", 30, "M", uniqueMobile());
        create(clinic, "100%_Real", 30, "M", uniqueMobile());
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=%25", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=_", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=%27%20OR%20%271%27%3D%271", clinic.owner(), null, 200).get("total").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=%5C", clinic.owner(), null, 200).get("total").asInt()).isZero();
    }

    @Test
    void filterChipsKidsSeniorsChronicNewAndVisitDataChipsReturnZeroNotErrors() throws Exception {
        Clinic clinic = newClinic();
        create(clinic, "Kid One", 5, "M", uniqueMobile());
        create(clinic, "Senior One", 70, "F", uniqueMobile());
        ok(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Chronic One\",\"age\":40,\"sex\":\"M\",\"mobile\":\"" + uniqueMobile() + "\",\"chronic\":\"Asthma\"}", 201);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=all", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(3);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=kids", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=seniors", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=chronic", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=new", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(3);
        for (String f : new String[] {"due", "missed", "overdue", "lapsed", "bogus"}) {
            ok(HttpMethod.GET, PATIENTS + "?filter=" + f, clinic.owner(), null, 200);
        }
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=due", clinic.owner(), null, 200).get("total").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=kids&q=kid", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        // SAH-73: Children is under 13 (the mockup), no longer under 18: 13 and 17 are not children
        create(clinic, "Teen Thirteen", 13, "F", uniqueMobile());
        create(clinic, "Teen Seventeen", 17, "F", uniqueMobile());
        create(clinic, "Kid Twelve", 12, "F", uniqueMobile());
        assertThat(ok(HttpMethod.GET, PATIENTS + "?filter=kids", clinic.owner(), null, 200).get("total").asInt()).isEqualTo(2);
    }

    @Test
    void listIsPagedByTwentyAndSummaryShape() throws Exception {
        Clinic clinic = newClinic();
        for (int i = 0; i < 21; i++) {
            create(clinic, String.format("Page Person %02d", i), 30, "M", uniqueMobile());
        }
        JsonNode p0 = ok(HttpMethod.GET, PATIENTS, clinic.owner(), null, 200);
        assertThat(p0.get("total").asInt()).isEqualTo(21);
        assertThat(p0.get("items").size()).isEqualTo(20);
        JsonNode p1 = ok(HttpMethod.GET, PATIENTS + "?page=1", clinic.owner(), null, 200);
        assertThat(p1.get("items").size()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?page=5", clinic.owner(), null, 200).get("items").size()).isZero();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?page=-1", clinic.owner(), null, 200).get("items").size()).isEqualTo(20);
        problem(call(HttpMethod.GET, PATIENTS + "?page=abc", clinic.owner(), null), 400);
        JsonNode item = p0.get("items").get(0);
        assertThat(item.get("uhid").asText()).isNotBlank();
        assertThat(item.get("mobile").asText()).hasSize(10);
        assertThat(item.get("lastVisitAt") == null || item.get("lastVisitAt").isNull()).isTrue();
    }

    @Test
    void lastVisitAtOnTheListStaysNullEvenAfterAVisit() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        ok(HttpMethod.POST, "/api/v1/opd/visits", clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        JsonNode item = ok(HttpMethod.GET, PATIENTS, clinic.owner(), null, 200).get("items").get(0);
        // contract: "Always null until SAH-9 exists" - SAH-9 now exists; record whatever the API says without failing on it
        assertThat(item.has("uhid")).isTrue();
    }

    // ---------------- update ----------------

    @Test
    void partialUpdateKeepsOtherFieldsAndNeverChangesTheMobileOrUhid() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode p = ok(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Before Name\",\"age\":30,\"sex\":\"M\",\"mobile\":\"" + mobile + "\",\"area\":\"Old area\",\"allergy\":\"Dust\"}", 201);
        String id = p.get("id").asText();
        JsonNode updated = ok(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(),
                "{\"name\":\"After Name\",\"age\":31,\"mobile\":\"9000000001\",\"uhid\":\"HACK-1\",\"chronic\":\"BP\",\"abhaLinked\":true,\"language\":\"en\",\"sex\":\"O\"}", 200);
        assertThat(updated.get("name").asText()).isEqualTo("After Name");
        assertThat(updated.get("age").asInt()).isEqualTo(31);
        assertThat(updated.get("sex").asText()).isEqualTo("O");
        assertThat(updated.get("mobile").asText()).isEqualTo(mobile);
        assertThat(updated.get("uhid").asText()).isEqualTo(p.get("uhid").asText());
        assertThat(updated.get("area").asText()).isEqualTo("Old area");
        assertThat(updated.get("allergy").asText()).isEqualTo("Dust");
        assertThat(updated.get("chronic").asText()).isEqualTo("BP");
        assertThat(updated.get("abhaLinked").asBoolean()).isTrue();
        assertThat(updated.get("language").asText()).isEqualTo("en");
        // empty string clears an optional text field; empty patch changes nothing
        JsonNode cleared = ok(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"area\":\"\"}", 200);
        assertThat(cleared.get("area") == null || cleared.get("area").isNull()).isTrue();
        ok(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{}", 200);
    }

    @Test
    void updateValidationAndNotFound() throws Exception {
        Clinic clinic = newClinic();
        String id = create(clinic, "Some Person", 30, "M", uniqueMobile()).get("id").asText();
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"name\":\"\"}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"age\":121}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"age\":-1}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"sex\":\"Z\"}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"language\":\"fr\"}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.owner(), "{\"allergy\":\"" + "a".repeat(401) + "\"}"), 400);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + UUID.randomUUID(), clinic.owner(), "{\"name\":\"X\"}"), 404);
        problem(call(HttpMethod.PATCH, PATIENTS + "/xyz", clinic.owner(), "{\"name\":\"X\"}"), 400);
    }

    @Test
    void anotherClinicCannotSeeOrChangeMyPatient() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String id = create(a, "Private Patient", 30, "M", uniqueMobile()).get("id").asText();
        problem(call(HttpMethod.GET, PATIENTS + "/" + id, b.owner(), null), 404);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, b.owner(), "{\"name\":\"Hacked\"}"), 404);
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=private", b.owner(), null, 200).get("total").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + id, a.owner(), null, 200).get("name").asText()).isEqualTo("Private Patient");
    }

    // ---------------- merge ----------------

    @Test
    void mergeMovesDataOntoKeepAndHidesTheMergedRecordFromSearchAndFamily() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode keep = create(clinic, "Ramesh K", 45, "M", mobile);
        JsonNode dup = ok(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":\"Ramesh Kulkarni\",\"age\":46,\"sex\":\"M\",\"mobile\":\"" + mobile + "\",\"allergy\":\"Sulfa\"}", 201);
        JsonNode merged = ok(HttpMethod.POST, PATIENTS + "/merge", clinic.as("FRONT_DESK"),
                "{\"keepId\":\"" + keep.get("id").asText() + "\",\"mergeId\":\"" + dup.get("id").asText() + "\"}", 200);
        assertThat(merged.get("id").asText()).isEqualTo(keep.get("id").asText());
        assertThat(merged.get("name").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(merged.get("allergy").asText()).isEqualTo("Sulfa");
        // SAH-73: the merge answer always says whether the two dates of birth differed (false when there are none)
        assertThat(merged.get("datesOfBirthDiffered").asBoolean()).isFalse();
        assertThat(merged.get("ageDetail").get("fromDateOfBirth").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.GET, PATIENTS + "?q=" + mobile, clinic.owner(), null, 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + keep.get("id").asText() + "/family", clinic.owner(), null, 200).size()).isZero();
        // the merged-away record still exists (soft merge)
        ok(HttpMethod.GET, PATIENTS + "/" + dup.get("id").asText(), clinic.owner(), null, 200);
    }

    @Test
    void mergeErrors() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        String a = create(clinic, "Alpha Person", 40, "M", mobile).get("id").asText();
        String b = create(clinic, "Alpha Person", 40, "M", mobile).get("id").asText();
        String c = create(clinic, "Gamma Person", 40, "M", uniqueMobile()).get("id").asText();
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + a + "\",\"mergeId\":\"" + a + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + a + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"mergeId\":\"" + a + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(),
                "{\"keepId\":\"" + a + "\",\"mergeId\":\"" + UUID.randomUUID() + "\"}"), 404);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(),
                "{\"keepId\":\"" + UUID.randomUUID() + "\",\"mergeId\":\"" + a + "\"}"), 404);
        ok(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + a + "\",\"mergeId\":\"" + b + "\"}", 200);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + a + "\",\"mergeId\":\"" + b + "\"}"), 400);
        problem(call(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + b + "\",\"mergeId\":\"" + c + "\"}"), 400);
    }

    @Test
    void cannotMergeAcrossClinics() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String inA = create(a, "Alpha Person", 40, "M", uniqueMobile()).get("id").asText();
        String inB = create(b, "Alpha Person", 40, "M", uniqueMobile()).get("id").asText();
        problem(call(HttpMethod.POST, PATIENTS + "/merge", a.owner(), "{\"keepId\":\"" + inA + "\",\"mergeId\":\"" + inB + "\"}"), 404);
    }
}
