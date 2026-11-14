package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.support.LogCapture;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-6 reprint of a signed prescription (docs/api/opd.yaml 0.6.1, ADR-025 D15, H12, H20, H21): who may reprint, what the
 * answer carries (print-on sections, layout snapshot, specialty as of the signing day, reference labels, addenda), that it is
 * read only and audited, and that no date of birth reaches a role that may not see it. Test plan:
 * docs/test-plans/SAH-51-68.md (T-51-6.x).
 */
@OnTestDatabase
class EmrReprintIT extends EmrSignSupport {

    private String path(Object visitId) {
        return VISITS + "/" + visitId + "/prescription-print";
    }

    private Rec signedRec() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        signOk(rec);
        return rec;
    }

    // ---------------- the answer ----------------

    @Test
    void theDoctorReprintsTheirSignedVisitWithTheReprintFlagThePrintOnSectionsTheLayoutAndTheFollowUpDate() throws Exception {
        Rec rec = signedRec();
        JsonNode reprint = get(path(rec.visitId()), rec.token());
        assertThat(reprint.get("visitId").asText()).isEqualTo(rec.visitId());
        assertThat(reprint.get("recordId").asText()).isEqualTo(rec.id());
        assertThat(reprint.get("reprint").asBoolean()).isTrue();
        assertThat(reprint.get("signedAt").asText()).isNotBlank();
        assertThat(reprint.get("context").get("signature").get("signed").asBoolean()).isTrue();
        assertThat(reprint.get("followUpDate").asText()).isEqualTo(today().plusDays(7).toString());
        List<String> printed = texts(reprint.get("sections"), "key");
        assertThat(printed).contains("complaints", "diagnosis", "rx");
        List<String> layoutKeys = texts(reprint.get("layout").get("sections"), "key");
        assertThat(layoutKeys).containsAll(printed);
        for (JsonNode s : reprint.get("layout").get("sections")) {
            assertThat(s.get("print").asBoolean()).as(s.get("key").asText() + " is a print-on section").isTrue();
        }
        assertThat(reprint.get("addenda")).isEmpty();
    }

    @Test
    void theReprintReadsOnlyItChangesNeitherTheRecordNorTheHoldNorTheQueueAndWritesOneAuditEntryEachTime() throws Exception {
        Rec rec = signedRec();
        List<String> columns = signColumns(rec.clinic(), rec.id());
        String stage = boardVisit(rec.clinic(), rec.visitId()).get("stage").asText();
        get(path(rec.visitId()), rec.token());
        get(path(rec.visitId()), rec.token());
        assertThat(signColumns(rec.clinic(), rec.id())).isEqualTo(columns);
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo(stage);
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_PRESCRIPTION_REPRINTED");
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).get(0)).isEqualTo("OPD_VISIT");
        assertThat(audits.get(0).get(2)).contains(rec.id()).doesNotContain("Fever").doesNotContain("Paracetamol");
        assertThat(audits.get(0).get(3)).isEqualTo(rec.doctor().userId().toString());
    }

    @Test
    void theAddendaComeWithTheReprintOldestFirst() throws Exception {
        Rec rec = signedRec();
        for (String reason : new String[] {"First correction", "Second correction"}) {
            okKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/addenda", rec.token(),
                    objectMapper.createObjectNode().put("reason", reason).put("text", "Text of " + reason).toString(), key(), 201);
        }
        JsonNode addenda = get(path(rec.visitId()), rec.token()).get("addenda");
        assertThat(texts(addenda, "reason")).containsExactly("First correction", "Second correction");
    }

    @Test
    void aVisitThatIsNotSignedIs409EmrRecordNotSignedAndAVisitWithNoRecordIs404() throws Exception {
        Rec rec = readyRec();
        problemOf(HttpMethod.GET, path(rec.visitId()), rec.token(), null, 409, "emr-record-not-signed");
        JsonNode other = issueVisit(rec.clinic(), rec.doctor().membershipId());
        problemOf(HttpMethod.GET, path(other.get("id").asText()), rec.token(), null, 404, "record-not-found");
        assertThat(auditCount(rec.clinic(), "EMR_PRESCRIPTION_REPRINTED")).isZero();
    }

    // ---------------- who may reprint ----------------

    @Test
    void ownerFrontDeskAndPharmacistReprintAnyVisitOfTheClinic() throws Exception {
        Rec rec = signedRec();
        assertThat(get(path(rec.visitId()), rec.clinic().owner()).get("reprint").asBoolean()).isTrue();
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            JsonNode reprint = get(path(rec.visitId()), staff.token(rec.clinic()));
            assertThat(reprint.get("reprint").asBoolean()).as(role).isTrue();
            assertThat(reprint.get("layout").isNull()).as(role + " gets the layout to print labels (H21)").isFalse();
        }
        assertThat(auditCount(rec.clinic(), "EMR_PRESCRIPTION_REPRINTED")).isEqualTo(3);
    }

    @Test
    void aDoctorAndAVisitingDoctorReprintOnlyTheirOwnVisitsAndTheOthersAre403NotOwnPatient() throws Exception {
        Rec rec = signedRec();
        Staff other = addDoctor(rec.clinic(), "Dentist");
        Staff visiting = addStaff(rec.clinic(), "VISITING_DOCTOR", "Dr Visiting Person", "Dentist", "BDS", "REG-" + shortId());
        problemOf(HttpMethod.GET, path(rec.visitId()), other.token(rec.clinic()), null, 403, "not-own-patient");
        problemOf(HttpMethod.GET, path(rec.visitId()), visiting.token(rec.clinic()), null, 403, "not-own-patient");
        // the visiting doctor reprints a visit of their own
        JsonNode theirs = issueVisit(rec.clinic(), visiting.membershipId());
        JsonNode theirRecord = openVisit(visiting.token(rec.clinic()), theirs.get("id").asText(), 201).get("record");
        signRecord(rec.clinic(), theirRecord.get("id").asText());
        assertThat(get(path(theirs.get("id").asText()), visiting.token(rec.clinic())).get("reprint").asBoolean()).isTrue();
    }

    @Test
    void nurseBillingAndCounsellorAreRefusedWith403EvenForAnUnknownVisit() throws Exception {
        Rec rec = signedRec();
        for (String role : new String[] {"NURSE", "BILLING", "COUNSELLOR"}) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.GET, path(rec.visitId()), staff.token(rec.clinic()), null, 403, "forbidden");
            problemOf(HttpMethod.GET, path(UUID.randomUUID()), staff.token(rec.clinic()), null, 403, "forbidden");
        }
        assertThat(auditCount(rec.clinic(), "EMR_PRESCRIPTION_REPRINTED")).isZero();
    }

    @Test
    void anotherClinicsVisitIs404AndNoSessionIs401() throws Exception {
        Rec rec = signedRec();
        Rec foreign = newRec();
        call(HttpMethod.GET, path(rec.visitId()), foreign.clinic().owner(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        call(HttpMethod.GET, path(rec.visitId()), foreign.token(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        problemOf(HttpMethod.GET, path(rec.visitId()), anonymous(), null, 401, "unauthorized");
    }

    // ---------------- specialty as of the signing day, labels and the date of birth ----------------

    @Test
    void aDepartmentWithoutAProfileAnswersNoSpecialtyViewAndNoReference() throws Exception {
        Rec rec = switchDepartment(newRec(), "ortho");
        signRecord(rec.clinic(), rec.id());
        JsonNode reprint = get(path(rec.visitId()), rec.token());
        assertThat(reprint.get("specialty").isNull()).isTrue();
        assertThat(reprint.get("specialtyReference").isNull()).isTrue();
        assertThat(reprint.get("layout").get("departmentKey").asText()).isEqualTo("ortho");
    }

    private Rec childRec(Clinic clinic, Staff doctor, LocalDate dob) throws Exception {
        ObjectNode patient = objectMapper.createObjectNode();
        patient.put("name", "Test Child One");
        patient.put("sex", "M");
        patient.put("mobile", uniqueMobile());
        patient.put("dateOfBirth", dob.toString());
        UUID id = UUID.fromString(ok(HttpMethod.POST, PATIENTS_URL, clinic.owner(), patient.toString(), 201).get("id").asText());
        return specialtyRec(clinic, doctor, id, "paeds");
    }

    @Test
    void theSpecialtyViewIsWorkedOutAsOfTheSigningDayNotAsOfTheReprint() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(10).minusDays(15);
        Rec rec = childRec(clinic, doctor, dob);
        int liveMonths = rec.record().get("specialty").get("paeds").get("ageMonths").asInt();
        assertThat(liveMonths).isEqualTo((int) Period.between(dob, today()).toTotalMonths());
        signRecord(clinic, rec.id(), 30);
        JsonNode reprint = get(path(rec.visitId()), rec.token());
        int asOfMonths = reprint.get("specialty").get("paeds").get("ageMonths").asInt();
        assertThat(asOfMonths).as("as of the signing day, 30 days before the reprint")
                .isEqualTo((int) Period.between(dob, today().minusDays(30)).toTotalMonths());
        assertThat(asOfMonths).isLessThan(liveMonths);
        assertThat(reprint.get("specialtyReference").isNull()).as("the labels to print choices as words").isFalse();
    }

    @Test
    void theDateOfBirthReachesTheFrontDeskThatMaySeeItAndNeverThePharmacist() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(8).minusDays(5);
        Rec rec = childRec(clinic, doctor, dob);
        signRecord(clinic, rec.id(), 1);
        Staff pharmacist = addNonDoctor(clinic, "PHARMACIST");
        Staff frontDesk = addNonDoctor(clinic, "FRONT_DESK");
        JsonNode forPharmacist = get(path(rec.visitId()), pharmacist.token(clinic));
        assertThat(forPharmacist.toString()).as("no date of birth for the pharmacist").doesNotContain(dob.toString())
                .doesNotContain("dateOfBirth\":\"");
        assertThat(forPharmacist.get("specialty").isNull()).as("the profile block is still there (H21)").isFalse();
        JsonNode forFrontDesk = get(path(rec.visitId()), frontDesk.token(clinic));
        assertThat(forFrontDesk.get("specialty").get("paeds").get("dateOfBirth").asText()).isEqualTo(dob.toString());
    }

    // ---------------- what the front desk and the pharmacist get of the specialty block (DEC-048 H32, security M2) ----------------

    private static final String ANC_WITH_FORM_F = "{\"datingScanEdd\":\"%s\",\"today\":{\"weightKg\":60.5},\"symptoms\":[],"
            + "\"orders\":[{\"key\":\"GROWTH_SCAN_FORM_F\"}],\"scans\":[]}";

    /** A signed Obstetrics visit with a dating-scan EDD and a growth scan ordered (Form F); {@code ancPrints} says whether ANC prints. */
    private Rec signedOgVisit(boolean ancPrints, String edd) throws Exception {
        return signedOgVisit(ancPrints, false, edd);
    }

    /**
     * Same, and {@code pcpndtPrints} turns "Print on prescription" on for the PCPNDT Form F section. The Form F section always
     * holds a filled and signed Form F (a section with no values never prints), so only the print flag decides.
     */
    private Rec signedOgVisit(boolean ancPrints, boolean pcpndtPrints, String edd) throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        if (!ancPrints || pcpndtPrints) {
            ObjectNode layout = layoutInput(department(clinic, "og"));
            sec(layout, "anc").put("print", ancPrints);
            sec(layout, "pcpndt").put("print", pcpndtPrints);
            publish(clinic, "og", layout, 200);
        }
        Rec rec = specialtyRec(clinic, doctor, newPatient(clinic), "og");
        rec = save(rec, spSection("anc", "ANC_CHECK", String.format(ANC_WITH_FORM_F, edd)),
                spSection("pcpndt", "FORM_F", "{\"filledAndSigned\":true}"));
        signRecord(clinic, rec.id());
        return rec;
    }

    private static List<String> fieldNamesOf(JsonNode object) {
        List<String> keys = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static boolean hasText(JsonNode json, String text) {
        return json.toString().contains(text);
    }

    @Test
    void anObstetricsReprintForTheFrontDeskAndThePharmacistHasNoDatingNoGestationalAgeNoFormFAndNoAncWhenAncAndFormFAreNotPrinted() throws Exception {
        String edd = today().plusDays(140).toString();
        Rec rec = signedOgVisit(false, edd);
        // the record's doctor and the owner keep the full block
        for (JsonNode full : new JsonNode[] {get(path(rec.visitId()), rec.token()), get(path(rec.visitId()), rec.clinic().owner())}) {
            JsonNode og = full.get("specialty").get("og");
            assertThat(og.get("edd").asText()).isEqualTo(edd);
            assertThat(og.has("gestationalAge")).isTrue();
            assertThat(og.get("formFRequired").asBoolean()).as("a growth scan was ordered and Form F is not signed").isTrue();
            assertThat(texts(full.get("layout").get("sections"), "key")).as("ANC is not a print-on section here").doesNotContain("anc", "pcpndt");
        }
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            JsonNode reprint = get(path(rec.visitId()), addNonDoctor(rec.clinic(), role).token(rec.clinic()));
            JsonNode specialty = reprint.get("specialty");
            assertThat(specialty.isNull()).as(role + " still gets the frame").isFalse();
            assertThat(specialty.get("profile").asText()).isEqualTo("og");
            assertThat(specialty.has("og")).as(role + ": no Obstetrics block").isFalse();
            assertThat(hasText(specialty, edd)).as(role + ": no EDD").isFalse();
            assertThat(hasText(specialty, "formF")).as(role + ": no Form F facts (PCPNDT)").isFalse();
            assertThat(hasText(specialty, "gestationalAge")).isFalse();
            assertThat(reprint.get("specialtyReference").isNull()).as("the labels stay, tariff prices included (H24)").isFalse();
            assertThat(reprint.get("layout").isNull()).as("and the layout to print labels (H21)").isFalse();
        }
    }

    @Test
    void whenAncPrintsTheFrontDeskGetsTheAncBlockItsSectionNeedsButNotTheFormFFactsOfASectionThatDoesNotPrint() throws Exception {
        String edd = today().plusDays(140).toString();
        Rec rec = signedOgVisit(true, edd);
        JsonNode forFrontDesk = get(path(rec.visitId()), addNonDoctor(rec.clinic(), "FRONT_DESK").token(rec.clinic()));
        JsonNode printed = forFrontDesk.get("layout").get("sections");
        assertThat(texts(printed, "key")).as("ANC prints by default in Obstetrics").contains("anc").doesNotContain("pcpndt");
        JsonNode og = forFrontDesk.get("specialty").get("og");
        assertThat(og).as("the ANC section prints, so its block is sent").isNotNull();
        assertThat(og.get("edd").asText()).isEqualTo(edd);
        // The PCPNDT Form F section is not print-on: its facts are not for the front desk or the pharmacist even though the ANC
        // block that is sent carries them in the same object (finding F-8 if this fails)
        assertThat(og.has("formFRequired")).as("Form F facts without a printed Form F section").isFalse();
        assertThat(og.has("formFCount")).isFalse();
    }

    /** DEC-048 H32 key level (F-8): the PCPNDT Form F facts follow the PCPNDT section alone, the dating and ANC keys the ANC section alone. */
    @Test
    void whenOnlyPcpndtPrintsTheFrontDeskAndThePharmacistGetOnlyTheFormFFactsInTheObstetricsBlock() throws Exception {
        String edd = today().plusDays(140).toString();
        Rec rec = signedOgVisit(false, true, edd);
        JsonNode full = get(path(rec.visitId()), rec.token());
        assertThat(texts(full.get("layout").get("sections"), "key")).contains("pcpndt").doesNotContain("anc");
        assertThat(full.get("specialty").get("og").get("edd").asText()).as("the record's doctor keeps the whole block").isEqualTo(edd);
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            JsonNode specialty = get(path(rec.visitId()), addNonDoctor(rec.clinic(), role).token(rec.clinic())).get("specialty");
            assertThat(specialty.get("profile").asText()).isEqualTo("og");
            assertThat(fieldNamesOf(specialty.get("og"))).as(role + ": only the Form F facts")
                    .containsExactlyInAnyOrder("formFRequired", "formFCount");
            assertThat(hasText(specialty, edd)).as(role + ": no EDD").isFalse();
            assertThat(hasText(specialty, "gestationalAge")).isFalse();
        }
    }

    @Test
    void whenNeitherAncNorPcpndtPrintsTheFrontDeskAndThePharmacistGetNoObstetricsBlockEvenThoughBothSectionsHoldValues() throws Exception {
        String edd = today().plusDays(140).toString();
        Rec rec = signedOgVisit(false, false, edd);
        assertThat(texts(get(path(rec.visitId()), rec.token()).get("layout").get("sections"), "key")).doesNotContain("anc", "pcpndt");
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            JsonNode specialty = get(path(rec.visitId()), addNonDoctor(rec.clinic(), role).token(rec.clinic())).get("specialty");
            assertThat(specialty.has("og")).as(role + ": no og block at all").isFalse();
            assertThat(hasText(specialty, "formF")).isFalse();
            assertThat(hasText(specialty, edd)).isFalse();
        }
    }

    /** DEC-048 H32, H38: the lab flags follow the Labs section alone; the decision, cycle and dose keys follow the regimen section. */
    @Test
    void whenOnlyTheOncologyLabsSectionPrintsTheFrontDeskAndThePharmacistGetOnlyTheLabKeys() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        ObjectNode layout = layoutInput(department(clinic, "onco"));
        sec(layout, "onc_labs").put("print", true);
        publish(clinic, "onco", layout, 200);
        Rec rec = specialtyRec(clinic, doctor, newPatient(clinic), "onco");
        rec = save(rec, spSection("onc_labs", oncLabs(1, NORMAL_LABS)), spSection("onc_ecog", "ONC_ECOG", "{\"grade\":1}"));
        signRecord(clinic, rec.id());
        JsonNode full = get(path(rec.visitId()), rec.token());
        assertThat(texts(full.get("layout").get("sections"), "key")).contains("onc_labs")
                .doesNotContain("onc_regimen", "onc_tox", "onc_bsa", "onc_ecog");
        assertThat(full.get("specialty").get("onco").has("decision")).as("the record's doctor keeps the whole block").isTrue();
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            JsonNode onco = get(path(rec.visitId()), addNonDoctor(clinic, role).token(clinic)).get("specialty").get("onco");
            assertThat(fieldNamesOf(onco)).as(role + ": only the lab keys").containsExactlyInAnyOrder("labFails", "labsBadge", "labsFresh");
        }
    }

    @Test
    void anOncologyReprintForTheFrontDeskAndThePharmacistHasNoOncologyBlockWhileTheDoctorAndTheOwnerKeepIt() throws Exception {
        Rec rec = specialtyRec("onco");
        signRecord(rec.clinic(), rec.id());
        JsonNode full = get(path(rec.visitId()), rec.token());
        assertThat(full.get("specialty").has("onco")).as("the record's doctor keeps the Oncology block (decision, lab flags)").isTrue();
        assertThat(get(path(rec.visitId()), rec.clinic().owner()).get("specialty").has("onco")).as("and so does the owner").isTrue();
        assertThat(texts(full.get("layout").get("sections"), "key")).as("no oncology section prints by default")
                .doesNotContain("onc_tox", "onc_bsa", "onc_labs", "onc_ecog", "onc_regimen");
        for (String role : new String[] {"FRONT_DESK", "PHARMACIST"}) {
            JsonNode specialty = get(path(rec.visitId()), addNonDoctor(rec.clinic(), role).token(rec.clinic())).get("specialty");
            assertThat(specialty.get("profile").asText()).isEqualTo("onco");
            assertThat(specialty.has("onco")).as(role + ": no oncology decision or lab flags").isFalse();
            assertThat(hasText(specialty, "decision")).as(role).isFalse();
            assertThat(specialty.has("hiddenSectionKeys")).as(role + ": the frame (hidden sections) stays").isTrue();        }
    }

    @Test
    void aPaediatricsReprintForTheFrontDeskStillCarriesTheVaccinationDataTheCardPrintsFrom() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(7).minusDays(3);
        Rec rec = childRec(clinic, doctor, dob);
        signRecord(clinic, rec.id(), 1);
        JsonNode paeds = get(path(rec.visitId()), addNonDoctor(clinic, "FRONT_DESK").token(clinic)).get("specialty").get("paeds");
        for (String field : new String[] {"ageMonths", "dateOfBirth", "vaccines", "nextDue", "dueTodayCount", "overdueCount"}) {
            assertThat(paeds.has(field)).as("the vaccination card needs " + field).isTrue();
        }
    }

    // ---------------- privacy ----------------

    @Test
    void noLogLineOrAuditEntryOfAReprintHoldsAClinicalValueOrThePatientsName() throws Exception {
        Rec rec = signedRec();
        try (LogCapture logs = new LogCapture()) {
            get(path(rec.visitId()), rec.token());
            Staff frontDesk = addNonDoctor(rec.clinic(), "FRONT_DESK");
            get(path(rec.visitId()), frontDesk.token(rec.clinic()));
            assertThat(logs.allText()).doesNotContain("Viral fever").doesNotContain("Paracetamol").doesNotContain("Ramesh");
        }
        assertThat(allAuditText(rec.clinic())).doesNotContain("Viral fever").doesNotContain("Paracetamol").doesNotContain("Ramesh");
    }
}
