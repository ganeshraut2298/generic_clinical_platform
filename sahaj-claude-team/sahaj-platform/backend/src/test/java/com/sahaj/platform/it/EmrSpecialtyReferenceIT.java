package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The read-only specialty reference (docs/api/opd.yaml 0.5.1, ADR-023 D4, D13.1) with its closed set of list names per profile,
 * the items the 0.5.1 follow-up added (General Medicine "General" line, oncology "Spine and bones", Loperamide, eight referrals,
 * outcome scales), the tag on every answer, the clinic tariff match, writes refused with 403 for every role, the "Second checks"
 * menu key of GET /me by role (platform.yaml 0.1.1), the visit templates (SAH-60-4) and the global tables' writes. Writes on
 * the allergy classes and the form starters answer 403 too: see {@link EmrMedicinesIT} and {@link EmrFormsIT}. Every rule
 * value is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed". Test plan:
 * docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrSpecialtyReferenceIT extends EmrSpecialtySupport {

    private static final List<String> NAMES_GM = List.of("gmSigns", "gmSystems", "gmFollowUp");
    private static final List<String> NAMES_DENTAL = List.of("dentalConditions", "dentalSurfaces", "dentalMobility", "dentalShades", "dentalGumTones",
            "dentalSuggestions", "dentalNextIntervals", "dentalLabItems");
    private static final List<String> NAMES_OG = List.of("ancSymptoms", "ancDueOrders", "ancScans", "ancSupplements", "gynaeQuickOrders");
    private static final List<String> NAMES_PAEDS = List.of("iapGroups", "iapVaccines", "milestones", "paedsDoseRules");
    private static final List<String> NAMES_PHYSIO = List.of("physioGaits", "physioModalities", "jointRom", "jointMmt", "jointSpecialTests", "outcomeScales");
    private static final List<String> NAMES_SURGERY = List.of("surgeryProcedures", "surgeryPayers", "surgeryConsentItems", "surgeryTests", "surgeryPreopOrders", "surgeryBlood");
    private static final List<String> NAMES_ONCO = List.of("oncSymptoms", "oncEvents", "oncSystems", "oncSystemChips", "oncLabs", "oncEcog", "oncDecisions",
            "oncDeviationReasons", "oncDoseLevels", "oncReductionReasons", "oncTakeHome", "oncReferrals");

    private static List<String> namesOf(JsonNode reference) {
        List<String> names = new ArrayList<>();
        reference.get("lists").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode item(JsonNode reference, String list, String key) {
        for (JsonNode n : reference.get("lists").get(list)) {
            if (key.equals(n.get("key").asText())) {
                return n;
            }
        }
        throw new AssertionError("no item " + key + " in " + list);
    }

    // ================= the reference of each profile =================

    @Test
    void eachOfTheSevenProfilesAnswersExactlyItsOwnListNamesWithTheTagAndTheListsOfAllSevenAreTheFortyFourClosedNames() throws Exception {
        Rec rec = newRec();
        String[][] expected = {{"gm"}, {"dental"}, {"og"}, {"paeds"}, {"physio"}, {"surgery"}, {"onco"}};
        List<List<String>> all = List.of(NAMES_GM, NAMES_DENTAL, NAMES_OG, NAMES_PAEDS, NAMES_PHYSIO, NAMES_SURGERY, NAMES_ONCO);
        int total = 0;
        for (int i = 0; i < expected.length; i++) {
            JsonNode reference = reference(rec, expected[i][0]);
            assertThat(reference.get("profile").asText()).isEqualTo(expected[i][0]);
            assertThat(reference.get("tagNote").asText()).as(expected[i][0]).isEqualTo(TAG);
            assertThat(namesOf(reference)).as(expected[i][0]).containsExactlyInAnyOrderElementsOf(all.get(i));
            total += all.get(i).size();
            for (String name : all.get(i)) {
                assertThat(reference.get("lists").get(name).isArray()).as(name).isTrue();
                assertThat(reference.get("lists").get(name).size()).as(name).isPositive();
                for (JsonNode entry : reference.get("lists").get(name)) {
                    assertThat(entry.get("key").asText()).as(name).isNotBlank();
                    assertThat(entry.get("key").asText().length()).as(name).isLessThanOrEqualTo(40);
                    assertThat(entry.get("label").asText()).as(name).isNotBlank();
                }
            }
            assertThat(reference.get("thresholds").isObject()).isTrue();
        }
        assertThat(total).as("the 44 names of the closed set").isEqualTo(44);
    }

    @Test
    void aProfileOutsideTheSevenIs400BadRequestAndAnUnknownOneToo() throws Exception {
        Rec rec = newRec();
        for (String profile : new String[] {"generic", "ortho", "GM", "nope"}) {
            problemOf(HttpMethod.GET, REFERENCE + profile, rec.token(), null, 400, "bad-request");
        }
    }

    @Test
    void theZeroPointFiveOneItemsTheFollowUpAddedAreThereAsDrawn() throws Exception {
        Rec rec = newRec();
        JsonNode gm = reference(rec, "gm");
        assertThat(keysOfList(gm, "gmSystems")).as("General first, as drawn").containsExactly("general", "cvs", "rs", "pa", "cns");
        assertThat(item(gm, "gmSystems", "general").get("label").asText()).isEqualTo("General");
        assertThat(item(gm, "gmSystems", "general").get("sub").asText()).isEqualTo("No pallor, icterus, cyanosis, oedema");
        assertThat(keysOfList(gm, "gmSigns")).containsExactly("pallor", "icterus", "cyanosis", "clubbing", "lymphadenopathy", "oedema", "dehydration");
        assertThat(keysOfList(gm, "gmFollowUp")).containsExactly("3d", "7d", "15d", "1m", "3m");
        JsonNode onco = reference(rec, "onco");
        assertThat(keysOfList(onco, "oncSystems")).as("five systems with Spine and bones").containsExactly("cvs", "rs", "pa", "cns", "bone");
        assertThat(item(onco, "oncSystems", "bone").get("label").asText()).isEqualTo("Spine and bones");
        assertThat(item(onco, "oncSystems", "bone").get("sub").asText()).isEqualTo("Bone spread");
        assertThat(item(onco, "oncSystems", "pa").get("label").asText()).as("pa keeps its key and shows as Abdomen").isEqualTo("Abdomen");
        List<String> chipsOfBone = new ArrayList<>();
        for (JsonNode chip : onco.get("lists").get("oncSystemChips")) {
            if ("bone".equals(chip.get("group").asText())) {
                chipsOfBone.add(chip.get("key").asText());
            }
        }
        assertThat(chipsOfBone).containsExactly("Spine tenderness", "Rib tenderness", "Restricted movement");
        assertThat(keysOfList(onco, "oncTakeHome")).containsExactly("ONDANSETRON_8", "DEXAMETHASONE_4", "PANTOPRAZOLE_40", "PEGFILGRASTIM_6", "BENZYDAMINE_MOUTHWASH", "LOPERAMIDE_2");
        JsonNode loperamide = item(onco, "oncTakeHome", "LOPERAMIDE_2");
        assertThat(loperamide.get("label").asText()).isEqualTo("Loperamide 2 mg");
        assertThat(loperamide.get("note").asText()).isEqualTo("Loose motions");
        assertThat(loperamide.get("defaultOn").asBoolean()).as("not ticked at start").isFalse();
        assertThat(loperamide.get("defaults").get("frequencyText").asText()).isEqualTo("After each loose motion (max 8)");
        assertThat(loperamide.get("defaults").path("days").isNull() || loperamide.get("defaults").path("days").isMissingNode()).as("no days drawn (H22)").isTrue();
        assertThat(loperamide.get("medicine").get("name").asText()).as("the seeded master medicine, so the allergy check applies").isEqualTo("Loperamide 2 mg");
        for (String key : new String[] {"ONDANSETRON_8", "DEXAMETHASONE_4", "PANTOPRAZOLE_40", "BENZYDAMINE_MOUTHWASH"}) {
            assertThat(item(onco, "oncTakeHome", key).get("defaultOn").asBoolean()).as(key).isTrue();
            assertThat(item(onco, "oncTakeHome", key).get("medicine").isNull()).as(key + " has a master row").isFalse();
        }
        assertThat(item(onco, "oncTakeHome", "PEGFILGRASTIM_6").get("defaultOn").asBoolean()).as("follows pegfilgrastimSuggested").isFalse();
        assertThat(keysOfList(onco, "oncReferrals")).containsExactly("RADIOTHERAPY", "SURGICAL_ONCOLOGY", "PALLIATIVE_CARE", "DIETITIAN", "PSYCHO_ONCOLOGY_COUNSELLING",
                "FERTILITY_COUNSELLING", "SOCIAL_WORKER_SCHEME_PAPERS", "CARDIOLOGY_ECHO");
        for (JsonNode referral : onco.get("lists").get("oncReferrals")) {
            assertThat(referral.get("defaultOn").asBoolean()).as(referral.get("key").asText()).isFalse();
        }
        assertThat(item(onco, "oncReferrals", "SOCIAL_WORKER_SCHEME_PAPERS").get("label").asText()).isEqualTo("Social worker (scheme papers)");
        assertThat(item(onco, "oncReferrals", "CARDIOLOGY_ECHO").get("label").asText()).isEqualTo("Cardiology (echo)");
        assertThat(keysOfList(onco, "oncDoseLevels")).containsExactly("100", "90", "80", "75");
        assertThat(keysOfList(onco, "oncReductionReasons")).containsExactly("LOW_NEUTROPHILS_LAST_CYCLE", "FEVER_LOW_COUNTS", "GRADE_3_SIDE_EFFECT", "NEUROPATHY", "LIVER_FUNCTION", "FRAILTY");
        assertThat(keysOfList(onco, "oncDecisions")).containsExactly("GIVE_AS_PLANNED", "GIVE_REDUCED", "DELAY_1_WEEK", "CHANGE_REGIMEN", "STOP");
        assertThat(keysOfList(onco, "oncEcog")).containsExactly("0", "1", "2", "3", "4");
        JsonNode physio = reference(rec, "physio");
        assertThat(keysOfList(physio, "outcomeScales")).containsExactly("ODI", "NDI", "WOMAC", "LEFS");
        assertThat(keysOfList(physio, "jointRom")).containsExactly("flexion", "extension", "flexion_passive");
        assertThat(keysOfList(physio, "jointMmt")).containsExactly("quadriceps", "hamstrings");
        assertThat(keysOfList(physio, "jointSpecialTests")).containsExactly("mcmurray_medial", "lachman", "patellar_grind", "anterior_drawer", "valgus_stress", "apley_compression");
        assertThat(keysOfList(physio, "physioModalities")).containsExactly("IFT", "HOT_PACK", "ULTRASOUND", "TENS", "EXERCISE_THERAPY", "TAPING");
        assertThat(keysOfList(physio, "physioGaits")).containsExactly("NORMAL", "ANTALGIC", "WITH_STICK", "WITH_WALKER");
    }

    @Test
    void theOncologyReferenceCarriesTheFourProtocolsWithTheirDrugsAndOnlyPaclitaxelThreeWeeklyIsSuggestedOnAChange() throws Exception {
        Rec rec = newRec();
        JsonNode onco = reference(rec, "onco");
        assertThat(texts(onco.get("protocols"), "key")).containsExactly("AC", "PACLI_3W", "PACLI_WEEKLY", "TC");
        for (JsonNode protocol : onco.get("protocols")) {
            assertThat(protocol.get("suggestedOnChange").asBoolean()).as(protocol.get("key").asText()).isEqualTo("PACLI_3W".equals(protocol.get("key").asText()));
            assertThat(protocol.get("drugs").size()).isPositive();
            assertThat(protocol.get("premeds").size()).isPositive();
        }
        JsonNode ac = onco.get("protocols").get(0);
        assertThat(ac.get("intervalDays").asInt()).isEqualTo(21);
        assertThat(ac.get("emeticRisk").asText()).isEqualTo("HIGH");
        assertThat(ac.get("drugs").get(0).get("drugKey").asText()).isEqualTo("doxorubicin");
        assertThat(ac.get("drugs").get(0).get("dosePerM2").asDouble()).isEqualTo(60.0);
        assertThat(ac.get("drugs").get(0).get("vialStepMg").asInt()).isEqualTo(5);
        assertThat(ac.get("drugs").get(0).get("vesicant").asBoolean()).isTrue();
        assertThat(ac.get("drugs").get(0).get("anthracycline").asBoolean()).isTrue();
        assertThat(onco.get("thresholds").get("oncDoxorubicinLifetime").asDouble()).isEqualTo(450.0);
        assertThat(onco.get("thresholds").get("oncBsaCap").asDouble()).isEqualTo(2.0);
        assertThat(onco.get("thresholds").get("oncLabHbMin").asDouble()).isEqualTo(8.0);
        assertThat(onco.has("growthBands")).as("growth bands are for paeds only").isFalse();
    }

    @Test
    void theGrowthBandsOfPaediatricsAreTheFiveWeightRowsOnlyAndNeverALengthOrHeadCircumferenceBandBecauseOfH8() throws Exception {
        Rec rec = newRec();
        JsonNode paeds = reference(rec, "paeds");
        JsonNode bands = paeds.get("growthBands");
        assertThat(bands).hasSize(5);
        for (JsonNode band : bands) {
            assertThat(band.get("measure").asText()).as("DEC-044 H8: no LENGTH and no HEAD_CIRC row").isEqualTo("WEIGHT");
        }
        assertThat(keysOfList(paeds, "iapGroups")).containsExactly("birth", "week_6", "week_10", "week_14", "month_6_9", "month_9", "month_12", "month_15", "month_16_18");
        assertThat(keysOfList(paeds, "iapVaccines")).hasSize(30).contains("bcg", "opv_0", "hep_b_1", "dtwp_1", "typhoid_conj", "dtwp_booster_1", "varicella_1");
        assertThat(keysOfList(paeds, "milestones")).containsExactly("walks_alone", "climbs_stairs_with_help", "scribbles_with_crayon", "feeds_self_with_spoon",
                "points_to_show_interest", "says_6_or_more_words");
        assertThat(keysOfList(paeds, "paedsDoseRules")).containsExactly("PARACETAMOL_120_5", "PARACETAMOL_250_5", "IBUPROFEN_100_5");
        assertThat(paeds.get("thresholds").get("paedsParacetamolMgPerKg").asDouble()).isEqualTo(15.0);
        for (String rule : keysOfList(paeds, "paedsDoseRules")) {
            assertThat(item(paeds, "paedsDoseRules", rule).get("medicine").isNull()).as(rule + " points at a seeded master medicine").isFalse();
        }
    }

    @Test
    void theDentalSuggestionsCarryTheClinicsTariffMatchIgnoringCaseAndSpacesOrNullAndNeverTheMockupsSampleRates() throws Exception {
        Rec rec = specialtyRec("dental");
        JsonNode before = reference(rec, "dental");
        for (JsonNode suggestion : before.get("lists").get("dentalSuggestions")) {
            assertThat(suggestion.get("tariff").isNull()).as(suggestion.get("key").asText() + " is not in the tariff").isTrue();
        }
        JsonNode service = post("/api/v1/opd/services", rec.clinic().owner(), "{\"name\":\"composite  FILLING\",\"category\":\"Lab\",\"priceRupees\":800}", 201);
        JsonNode after = reference(rec, "dental");
        boolean matched = false;
        for (JsonNode suggestion : after.get("lists").get("dentalSuggestions")) {
            if ("COMPOSITE_FILLING".equals(suggestion.get("key").asText())) {
                assertThat(suggestion.get("tariff").get("serviceId").asText()).isEqualTo(service.get("id").asText());
                assertThat(suggestion.get("tariff").get("pricePaise").asLong()).isEqualTo(80000L);
                matched = true;
            } else if (!"COMPOSITE_FILLING".equals(suggestion.get("key").asText())) {
                assertThat(suggestion.get("tariff").isNull()).isTrue();
            }
        }
        assertThat(matched).isTrue();
        assertThat(item(after, "dentalSuggestions", "ROOT_CANAL").get("defaults").get("sittings").asInt()).as("root canal: 3 sittings at the start (C-3)").isEqualTo(3);
        Rec another = specialtyRec("dental");
        for (JsonNode suggestion : reference(another, "dental").get("lists").get("dentalSuggestions")) {
            assertThat(suggestion.get("tariff").isNull()).as("another clinic's tariff is never matched").isTrue();
        }
    }

    // ================= who may read, and the writes =================

    @Test
    void onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsReadsTheReferenceAndAnyWriteOnItIs403ForEveryRoleOwnerIncluded() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting Test", "General physician", "MBBS", "REG-" + shortId());
        get(REFERENCE + "gm", doctor.token(clinic));
        get(REFERENCE + "onco", visiting.token(clinic));
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            problemOf(HttpMethod.GET, REFERENCE + "gm", staff.token(clinic), null, 403, "forbidden");
            problemOf(HttpMethod.GET, REFERENCE + "gm", staff.tokenClaiming(clinic, "DOCTOR"), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, REFERENCE + "gm", anonymous(), null, 401, "unauthorized");
        assertThat(call(HttpMethod.GET, REFERENCE + "gm", clinic.owner(), null).andReturn().getResponse().getStatus()).as("an owner with no doctor details").isEqualTo(403);
        ownerWithDoctorDetails(clinic, "General physician", "MBBS", "REG-" + shortId());
        get(REFERENCE + "gm", clinic.owner());
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            for (String role : ALL_ROLES) {
                problemOf(method, REFERENCE + "gm", clinic.as(role), "{\"tagNote\":\"Changed\"}", 403, "forbidden");
                problemOf(method, REFERENCE + "onco", clinic.as(role), null, 403, "forbidden");
            }
        }
        assertThat(globalCount("select count(*) from opd.emr_chemo_protocol")).as("nothing was written").isEqualTo(4);
    }

    // ================= the Second checks menu key =================

    private List<String> menu(Clinic clinic, org.springframework.test.web.servlet.request.RequestPostProcessor who) throws Exception {
        List<String> items = new ArrayList<>();
        get(ME_URL, who).get("menu").forEach(n -> items.add(n.asText()));
        return items;
    }

    @Test
    void theSecondChecksMenuKeyIsForNursesPharmacistsAndDoctorsAndTheOwnerOnlyWithDoctorDetailsAndNeverForFrontDeskBillingOrCounsellor() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR"}) {
            Staff staff = role.contains("DOCTOR") ? addStaff(clinic, role, "Dr Menu", "General physician", "MBBS", "REG-" + shortId()) : addNonDoctor(clinic, role);
            assertThat(menu(clinic, staff.token(clinic))).as(role).contains("secondchecks");
        }
        for (String role : NOT_CHECKER_ROLES) {
            assertThat(menu(clinic, addNonDoctor(clinic, role).token(clinic))).as(role).doesNotContain("secondchecks");
        }
        Clinic bare = bareOwnerClinic();
        assertThat(menu(bare, bare.owner())).as("an owner without doctor details").doesNotContain("secondchecks");
        ownerWithDoctorDetails(bare, "General physician", "MBBS", "REG-" + shortId());
        assertThat(menu(bare, bare.owner())).as("an owner with doctor details").contains("secondchecks");
    }

    @Test
    void theSecondChecksItemComesRightAfterQueueWhenTheRoleHasItElseAtTheEndBeforeStaffAndIsInBothMenuModesWithNoModuleCondition() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff nurse = addNonDoctor(clinic, "NURSE");
        Staff pharmacist = addNonDoctor(clinic, "PHARMACIST");
        for (Staff staff : new Staff[] {doctor, nurse}) {
            List<String> menu = menu(clinic, staff.token(clinic));
            assertThat(menu.indexOf("secondchecks")).as(staff.role() + ": right after queue").isEqualTo(menu.indexOf("queue") + 1);
        }
        List<String> pharmacy = menu(clinic, pharmacist.token(clinic));
        assertThat(pharmacy).as("no queue: at the end").endsWith("secondchecks");
        assertThat(pharmacy.indexOf("secondchecks")).isEqualTo(pharmacy.size() - 1);
        // the Simple menu of the owner who has doctor details, and IPD off, HRA off
        Clinic owner = newClinic();
        ownerWithDoctorDetails(owner, "General physician", "MBBS", "REG-" + shortId());
        ok(HttpMethod.PATCH, ME_URL + "/preferences", owner.owner(), "{\"menuMode\":\"simple\"}", 200);
        assertThat(menu(owner, owner.owner())).contains("secondchecks");
        ok(HttpMethod.PATCH, ME_URL + "/preferences", owner.owner(), "{\"menuMode\":\"advanced\"}", 200);
        List<String> advanced = menu(owner, owner.owner());
        assertThat(advanced).contains("secondchecks");
        assertThat(advanced.indexOf("secondchecks")).isEqualTo(advanced.indexOf("queue") + 1);
        assertThat(get(ME_URL, doctor.token(clinic)).get("role").asText()).isEqualTo("DOCTOR");
    }

    // ================= visit templates (SAH-60-4) =================

    private static String templateBody(String name, String subLine, String itemsJson) {
        return "{\"name\":\"" + name + "\",\"subLine\":" + (subLine == null ? "null" : "\"" + subLine + "\"") + ",\"items\":" + itemsJson + "}";
    }

    private static final String SOME_ITEMS = "{\"complaints\":[{\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true}],\"advice\":[{\"itemKey\":null,\"text\":\"Rest\",\"newToList\":true}],"
            + "\"medicines\":[{\"medicineId\":null,\"name\":\"Paracetamol 650 mg\",\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\"}],\"repeatLastRx\":false}";

    @Test
    void aDoctorSavesAVisitTemplateTheClinicsDoctorsShareItAndAnotherClinicNeverSeesIt() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff second = addDoctor(clinic, "General physician");
        assertThat(get(VISIT_TEMPLATES, doctor.token(clinic))).isEmpty();
        JsonNode created = post(VISIT_TEMPLATES, doctor.token(clinic), templateBody("Fever visit", "Fever with body ache", SOME_ITEMS), 201);
        assertThat(created.get("name").asText()).isEqualTo("Fever visit");
        assertThat(created.get("subLine").asText()).isEqualTo("Fever with body ache");
        assertThat(created.get("items").has("schemaVersion")).as("the stored schema version is dropped on read").isFalse();
        assertThat(created.get("items").get("complaints").get(0).get("text").asText()).isEqualTo("Fever");
        assertThat(created.get("id").asText()).isNotBlank();
        JsonNode shared = get(VISIT_TEMPLATES, second.token(clinic));
        assertThat(shared).hasSize(1);
        assertThat(shared.get(0).get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(row(clinic, "select items ->> 'schemaVersion', created_by_membership_id::text from opd.emr_visit_template")).containsExactly("1", doctor.membershipId().toString());
        Clinic other = newClinic();
        Staff foreign = addDoctor(other, "General physician");
        assertThat(get(VISIT_TEMPLATES, foreign.token(other))).as("another clinic sees none").isEmpty();
        post(VISIT_TEMPLATES, foreign.token(other), templateBody("Fever visit", null, SOME_ITEMS), 201);
        assertThat(count(clinic, "select count(*) from opd.emr_visit_template")).isEqualTo(1);
        List<List<String>> audits = auditRows(clinic, "EMR_VISIT_TEMPLATE_CREATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(2)).doesNotContain("Fever").doesNotContain("Paracetamol").doesNotContain("Rest");
    }

    @Test
    void aTemplateNameIsUniqueIgnoringCaseAndTheLimitsAndShapeAreChecked() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        post(VISIT_TEMPLATES, doctor.token(clinic), templateBody("Review visit", null, SOME_ITEMS), 201);
        JsonNode dup = problemOf(HttpMethod.POST, VISIT_TEMPLATES, doctor.token(clinic), templateBody("REVIEW VISIT", null, SOME_ITEMS), 400, "validation");
        assertThat(dup.toString()).contains("A template with this name exists");
        String[] bad = {templateBody("", null, SOME_ITEMS), templateBody("n".repeat(61), null, SOME_ITEMS), templateBody("Sub", "s".repeat(121), SOME_ITEMS),
                templateBody("Unknown key", null, "{\"patientName\":\"Asha\"}"), templateBody("Too many", null,
                "{\"complaints\":[" + String.join(",", java.util.Collections.nCopies(21, "{\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true}")) + "]}"),
                "{\"name\":\"No items\"}", "{\"items\":{}}"};
        for (String body : bad) {
            problemOf(HttpMethod.POST, VISIT_TEMPLATES, doctor.token(clinic), body, 400, "validation");
        }
        post(VISIT_TEMPLATES, doctor.token(clinic), templateBody("n".repeat(60), null, "{}"), 201);
        assertThat(count(clinic, "select count(*) from opd.emr_visit_template")).isEqualTo(2);
    }

    @Test
    void aClinicHoldsAtMostOneHundredTemplatesAndTheHundredAndFirstIs400VisitTemplateLimit() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        commit(clinic.tenantId(), "insert into opd.emr_visit_template (name, items, created_by_membership_id, created_by) "
                + "select 'Template ' || g, '{\"schemaVersion\":1}'::jsonb, ?::uuid, ?::uuid from generate_series(1, 100) g", doctor.membershipId().toString(), clinic.ownerUserId().toString());
        assertThat(get(VISIT_TEMPLATES, doctor.token(clinic)).size()).isEqualTo(100);
        JsonNode problem = problemOf(HttpMethod.POST, VISIT_TEMPLATES, doctor.token(clinic), templateBody("One too many", null, SOME_ITEMS), 400, "visit-template-limit");
        assertThat(problem.get("detail").asText()).isEqualTo("A clinic can have up to 100 visit templates.");
        assertThat(count(clinic, "select count(*) from opd.emr_visit_template")).isEqualTo(100);
        Clinic other = newClinic();
        post(VISIT_TEMPLATES, addDoctor(other, "General physician").token(other), templateBody("One too many", null, SOME_ITEMS), 201);
    }

    @Test
    void onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsListsOrSavesATemplate() throws Exception {
        Clinic clinic = newClinic();
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting Test", "General physician", "MBBS", "REG-" + shortId());
        post(VISIT_TEMPLATES, visiting.token(clinic), templateBody("From visiting", null, SOME_ITEMS), 201);
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            problemOf(HttpMethod.GET, VISIT_TEMPLATES, staff.token(clinic), null, 403, "forbidden");
            problemOf(HttpMethod.POST, VISIT_TEMPLATES, staff.token(clinic), templateBody("Nope", null, SOME_ITEMS), 403, "forbidden");
            problemOf(HttpMethod.POST, VISIT_TEMPLATES, staff.tokenClaiming(clinic, "DOCTOR"), templateBody("Nope", null, SOME_ITEMS), 403, "forbidden");
        }
        problemOf(HttpMethod.GET, VISIT_TEMPLATES, anonymous(), null, 401, "unauthorized");
        assertThat(call(HttpMethod.GET, VISIT_TEMPLATES, clinic.owner(), null).andReturn().getResponse().getStatus()).as("an owner without doctor details").isEqualTo(403);
        ownerWithDoctorDetails(clinic, "General physician", "MBBS", "REG-" + shortId());
        post(VISIT_TEMPLATES, clinic.owner(), templateBody("From owner", null, SOME_ITEMS), 201);
        assertThat(get(VISIT_TEMPLATES, clinic.owner())).hasSize(2);
        assertThat(count(clinic, "select count(*) from opd.emr_visit_template")).isEqualTo(2);
        assertThat(UUID.fromString(row(clinic, "select created_by_membership_id::text from opd.emr_visit_template where name = 'From owner'").get(0))).isEqualTo(owner(clinic).membershipId());
    }
}
