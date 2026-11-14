package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-46, SAH-47 and SAH-52 (OPD part) through the real endpoints: open the EMR from a Queue card (idempotent, one record
 * per visit also under concurrency, the stage, the department choice and the layout reference), the frame
 * ({@code GET /emr/records/{id}}), Save draft with its value rules and revision, the Done rule and "Still needed", the
 * layout a record keeps when the owner publishes or resets later, switch department, Admit to IPD advice, Copy from last
 * visit and a signed record. IPD and Emergency are in {@link EmrIpdRecordsIT}. Patient names are made up. Test plan:
 * docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrRecordsIT extends EmrSupport {

    // ADR-022 D2 (phase 3): the complaint, the provisional diagnosis and the drug are component fields; their sibling fields
    // (duration, severity, dose and frequency, timing, instructions) are covered by the component and are never sent.
    private static final String COMPLAINTS = "{\"complaint\":{\"component\":\"EMR_LIST\",\"kind\":\"COMPLAINTS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000001\",\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true,"
            + "\"attr1\":\"D3\",\"attr2\":\"MODERATE\",\"active\":true}]}}";
    private static final String DIAGNOSIS = "{\"provisional_diagnosis\":{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000002\",\"itemKey\":null,\"text\":\"Viral fever\",\"newToList\":true,"
            + "\"attr1\":\"PROVISIONAL\",\"attr2\":\"ACTIVE\",\"active\":true}]}}";
    private static final String RX = "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":["
            + "{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000003\",\"medicineId\":null,\"name\":\"Paracetamol 500 mg\",\"form\":null,"
            + "\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\",\"source\":\"TYPED\"}]}}";
    private static final String RXFORMAT = "{\"header\":{\"text\":\"Header with registration number\"}}";

    private record Ctx(Clinic clinic, Staff doctor, JsonNode visit) {

        RequestPostProcessor token() {
            return doctor.token(clinic);
        }

        String visitId() {
            return visit.get("id").asText();
        }
    }

    private Ctx ctx() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        return new Ctx(clinic, doctor, issueVisit(clinic, doctor.membershipId()));
    }

    private Ctx ctxFor(Clinic clinic, Staff doctor) throws Exception {
        return new Ctx(clinic, doctor, issueVisit(clinic, doctor.membershipId()));
    }

    private JsonNode open(Ctx c) throws Exception {
        return openVisit(c.token(), c.visitId(), 201).get("record");
    }

    private long audits(Clinic clinic, String action) throws Exception {
        return count(clinic, "select count(*) from platform.audit_event where action = '" + action + "'");
    }

    private JsonNode save(Ctx c, JsonNode record, ObjectNode... sections) throws Exception {
        return saveRecordDraft(c.token(), record.get("id").asText(), draftBody(record.get("revision").asLong(), "ASSESSMENT", null, sections), 200);
    }

    /** Fills the four required sections of General Medicine and returns the record after the save. */
    private JsonNode fillRequired(Ctx c, JsonNode record) throws Exception {
        return save(c, record, sectionValues("complaints", COMPLAINTS), sectionValues("diagnosis", DIAGNOSIS),
                sectionValues("rx", RX), sectionValues("rxformat", RXFORMAT));
    }

    private void sign(Clinic clinic, Object recordId) throws Exception {
        commit(clinic.tenantId(), "update opd.emr_record set status = 'SIGNED', signed_at = now(), signed_by = ?::uuid, "
                + "signed_by_membership_id = (select m.id from platform.membership m where m.user_id = ?::uuid limit 1), sign_idempotency_key = ?, "
                + "edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null where id = ?::uuid",
                clinic.ownerUserId(), clinic.ownerUserId(), "fixture-" + shortId(), recordId.toString());
    }

    // ---------------- open ----------------

    @Test
    void openingAnOpdVisitCreatesTheRecordWithTheFrameHeaderLayoutPartsAndTheDepartmentOfTheDoctorsSpecialty() throws Exception {
        Ctx c = ctx();
        JsonNode result = openVisit(c.token(), c.visitId(), 201);
        assertThat(result.get("created").asBoolean()).isTrue();
        assertThat(result.get("stageOutcome").asText()).isEqualTo("MOVED");
        JsonNode record = result.get("record");
        assertThat(record.get("id").asText()).isNotBlank();
        assertThat(record.get("encounterType").asText()).isEqualTo("OPD_VISIT");
        assertThat(record.get("encounterId").asText()).isEqualTo(c.visitId());
        assertThat(record.get("roundDate").isNull()).isTrue();
        assertThat(record.get("status").asText()).isEqualTo("DRAFT");
        assertThat(record.get("readOnly").asBoolean()).isFalse();
        assertThat(record.get("readOnlyReason").isNull()).isTrue();
        assertThat(record.get("revision").asLong()).isZero();
        assertThat(record.get("admissionAdvised").asBoolean()).isFalse();
        assertThat(record.get("referralLine").isNull()).isTrue();
        // the layout reference: General Medicine on its starter layout, chosen by the doctor's specialty
        JsonNode layout = record.get("layout");
        assertThat(layout.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(layout.get("departmentName").asText()).isEqualTo("General Medicine");
        assertThat(layout.get("starterKey").asText()).isEqualTo("gm");
        assertThat(layout.get("layoutSource").asText()).isEqualTo("STARTER");
        assertThat(layout.get("versionNo").asInt()).isEqualTo(1);
        assertThat(layout.get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(layout.get("takenAt").asText()).isNotBlank();
        assertThat(layout.get("sections").size()).as("only the sections that are on").isEqualTo(16);
        assertThat(layoutKeys(record)).doesNotContain("procedures", "referral", "consent");
        assertThat(record.get("sections").size()).as("one entry per section of the layout").isEqualTo(16);
        for (JsonNode s : record.get("sections")) {
            assertThat(s.get("values").size()).isZero();
            assertThat(s.get("copiedFrom").isNull()).isTrue();
            assertThat(s.get("copyPendingConfirmation").asBoolean()).isFalse();
            assertThat(s.get("status").asText()).isIn("REQUIRED", "OPTIONAL");
        }
        // the parts, the badges and "Still needed" are computed from the layout
        JsonNode assessment = record.get("parts").get(0);
        JsonNode treatment = record.get("parts").get(1);
        assertThat(assessment.get("part").asText()).isEqualTo("ASSESSMENT");
        assertThat(treatment.get("part").asText()).isEqualTo("TREATMENT");
        assertThat(assessment.get("requiredTotal").asInt()).isEqualTo(1);
        assertThat(treatment.get("requiredTotal").asInt()).isEqualTo(3);
        assertThat(assessment.get("sectionsTotal").asInt()).isEqualTo(9);
        assertThat(treatment.get("sectionsTotal").asInt()).isEqualTo(7);
        assertThat(assessment.get("complete").asBoolean()).isFalse();
        assertThat(texts(record.get("stillNeeded"), null)).containsExactly("complaints", "diagnosis", "rx", "rxformat");
        assertThat(record.get("readyToComplete").asBoolean()).isFalse();
        assertThat(record.get("uiState").get("part").asText()).isEqualTo("ASSESSMENT");
        assertThat(record.get("uiState").get("sectionKey").isNull()).isTrue();
        // the header
        JsonNode header = record.get("header");
        assertThat(header.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(header.get("initials").asText()).isEqualTo("RK");
        assertThat(header.get("age").asInt()).isEqualTo(45);
        assertThat(header.get("sex").asText()).isEqualTo("M");
        assertThat(header.get("visitType").asText()).isEqualTo("NEW");
        assertThat(header.get("visitOrdinal").asInt()).isEqualTo(1);
        assertThat(header.get("lastVisitDaysAgo").isNull()).isTrue();
        assertThat(header.get("allergyText").isNull()).as("no allergy recorded: no tag, never 'No known allergy'").isTrue();
        assertThat(header.get("doctor").get("membershipId").asText()).isEqualTo(c.doctor().membershipId().toString());
        assertThat(header.get("doctor").get("name").asText()).isEqualTo("Dr Test Doctor");
        assertThat(header.get("admission").isNull()).isTrue();
        assertThat(header.get("emergency").isNull()).isTrue();
        assertThat(record.has("abha")).isFalse();
        assertThat(record.toString()).as("the ABHA linked tag waits for DEC-040").doesNotContain("ABHA linked");
        assertThat(texts(record.get("departmentOptions"), "departmentKey")).containsExactly(STARTER_KEYS);
        assertThat(record.get("ipd").get("canSwitchOn").asBoolean()).isFalse();
    }

    @Test
    void theRecordRowPointsAtTheStarterLayoutOrTheDepartmentVersionAndHoldsNoCopyOfIt() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        List<String> row = row(c.clinic(), "select layout_kind, (department_version_id is null)::text, starter_layout_version::text, starter_key, "
                + "department_key, choice_source, status, revision::text, sections::text, encounter_type, opd_visit_id::text, "
                + "(ipd_admission_id is null)::text, (er_case_id is null)::text, (round_date is null)::text, (patient_id is not null)::text "
                + "from opd.emr_record where id = ?::uuid",
                record.get("id").asText());
        assertThat(row).containsExactly("STARTER_LAYOUT", "true", "1", "gm", "gm", "DOCTOR_SPECIALTY", "DRAFT", "0", "{}", "OPD_VISIT",
                c.visitId(), "true", "true", "true", "true");
        publishOneChange(c.clinic(), "gm", "chronic", false);
        Ctx next = ctxFor(c.clinic(), c.doctor());
        JsonNode second = open(next);
        List<String> published = row(c.clinic(), "select r.layout_kind, (r.starter_layout_version is null)::text, "
                + "(r.department_version_id = v.id)::text, v.version_no::text from opd.emr_record r, opd.emr_department_version v "
                + "where r.id = ?::uuid", second.get("id").asText());
        assertThat(published).containsExactly("DEPARTMENT_VERSION", "true", "true", "1");
        assertThat(second.get("layout").get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(second.get("layout").get("versionNo").asInt()).isEqualTo(1);
        assertThat(layoutKeys(second)).doesNotContain("chronic");
    }

    /** The record without {@code editHold.expiresAt}: the hold is renewed at every open, so its expiry moves. */
    private static JsonNode withoutHoldExpiry(JsonNode record) {
        ObjectNode copy = record.deepCopy();
        if (copy.get("editHold") != null && copy.get("editHold").isObject()) {
            ((ObjectNode) copy.get("editHold")).remove("expiresAt");
        }
        return copy;
    }

    @Test
    void openingAgainIsSafeToRepeatItFindsTheSameRecordChangesNothingAndEveryOpenWritesOneAuditEntryWithIdsOnly() throws Exception {
        Ctx c = ctx();
        JsonNode first = openVisit(c.token(), c.visitId(), 201);
        JsonNode second = openVisit(c.token(), c.visitId(), 200);
        JsonNode third = openVisit(c.token(), c.visitId(), 200);
        assertThat(second.get("created").asBoolean()).isFalse();
        assertThat(second.get("stageOutcome").asText()).isEqualTo("ALREADY_WITH_DOCTOR");
        assertThat(third.get("record").get("id").asText()).isEqualTo(first.get("record").get("id").asText());
        assertThat(withoutHoldExpiry(second.get("record"))).as("only the edit hold expiry moves at every open").isEqualTo(withoutHoldExpiry(first.get("record")));
        assertThat(recordRows(c.clinic())).isEqualTo(1);
        assertThat(count(c.clinic(), "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED' and entity_id = '" + c.visitId() + "'"))
                .as("no duplicate stage is written").isEqualTo(1);
        List<List<String>> opened = auditRows(c.clinic(), "EMR_RECORD_OPENED");
        assertThat(opened).hasSize(3);
        JsonNode created = objectMapper.readTree(opened.get(0).get(2));
        assertThat(created.get("created").asBoolean()).isTrue();
        assertThat(objectMapper.readTree(opened.get(1).get(2)).get("created").asBoolean()).isFalse();
        assertThat(created.get("encounterType").asText()).isEqualTo("OPD_VISIT");
        assertThat(created.get("encounterId").asText()).isEqualTo(c.visitId());
        assertThat(created.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(opened.get(0).get(3)).isEqualTo(c.doctor().userId().toString());
        assertThat(opened.get(0).get(1) + opened.get(0).get(2)).doesNotContain("Ramesh").doesNotContain("Kulkarni");
        assertThat(withoutHoldExpiry(get(RECORDS + "/" + first.get("record").get("id").asText(), c.token()))).isEqualTo(withoutHoldExpiry(first.get("record")));
    }

    @Test
    void sixOpensAtTheSameMomentMakeOneRecordForTheVisit() throws Exception {
        Ctx c = ctx();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<MvcResult> results = new ArrayList<>();
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> call(HttpMethod.POST, OPEN, c.token(), openBody("OPD_VISIT", c.visitId())).andReturn()));
            }
            for (Future<MvcResult> f : futures) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Set<String> ids = new HashSet<>();
        int created = 0;
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isIn(200, 201);
            created += r.getResponse().getStatus() == 201 ? 1 : 0;
            ids.add(objectMapper.readTree(r.getResponse().getContentAsString()).get("record").get("id").asText());
        }
        assertThat(created).as("exactly one open created the record").isEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(recordRows(c.clinic())).isEqualTo(1);
        assertThat(audits(c.clinic(), "EMR_RECORD_OPENED")).isEqualTo(6);
    }

    @Test
    void openingMovesAWaitingVisitToWithDoctorThroughTheWorkflowAndTheQueueCardCarriesTheRecordIdAndStatus() throws Exception {
        Ctx c = ctx();
        JsonNode before = visitOnBoard(c.clinic(), c.visitId());
        assertThat(before.get("stage").asText()).isEqualTo("VITALS");
        assertThat(before.get("emrRecordId").isNull()).isTrue();
        assertThat(before.get("emrStatus").asText()).isEqualTo("NONE");
        JsonNode record = open(c);
        JsonNode after = visitOnBoard(c.clinic(), c.visitId());
        assertThat(after.get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(after.get("emrRecordId").asText()).isEqualTo(record.get("id").asText());
        assertThat(after.get("emrStatus").asText()).isEqualTo("DRAFT");
        sign(c.clinic(), record.get("id").asText());
        assertThat(visitOnBoard(c.clinic(), c.visitId()).get("emrStatus").asText()).isEqualTo("SIGNED");
        // a clinic with no vitals step starts the visit With doctor: nothing to move
        Clinic noVitals = newClinic(false);
        Staff doctor = addDoctor(noVitals, "General physician");
        Ctx plain = ctxFor(noVitals, doctor);
        assertThat(openVisit(plain.token(), plain.visitId(), 201).get("stageOutcome").asText()).isEqualTo("ALREADY_WITH_DOCTOR");
        // an owner with doctor details opens a visit that is already Done: the stage is left alone
        Ctx done = ctxFor(noVitals, doctor);
        ok(HttpMethod.PATCH, VISITS + "/" + done.visitId() + "/stage", noVitals.owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.PATCH, VISITS + "/" + done.visitId() + "/stage", noVitals.owner(), "{\"stage\":\"DONE\"}", 200);
        JsonNode doneOpen = openVisit(done.token(), done.visitId(), 201);
        assertThat(doneOpen.get("stageOutcome").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(visitOnBoard(noVitals, done.visitId()).get("stage").asText()).isEqualTo("DONE");
    }

    private JsonNode visitOnBoard(Clinic clinic, String visitId) throws Exception {
        for (JsonNode v : get(VISITS + "/today", clinic.owner())) {
            if (visitId.equals(v.get("id").asText())) {
                return v;
            }
        }
        throw new AssertionError("visit " + visitId + " is not on the board");
    }

    // ---------------- who may open ----------------

    @Test
    void onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsOpensAndEveryOtherRoleIs403WithNothingWritten() throws Exception {
        Clinic bare = newClinic(true, false);
        Ctx c = ctxFor(bare, addDoctor(bare, "General physician"));
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(c.clinic(), role);
            problemOf(HttpMethod.POST, OPEN, staff.token(c.clinic()), openBody("OPD_VISIT", c.visitId()), 403, "forbidden");
            problemOf(HttpMethod.POST, OPEN, staff.tokenClaiming(c.clinic(), "DOCTOR"), openBody("OPD_VISIT", c.visitId()), 403, "forbidden");
        }
        problemOf(HttpMethod.POST, OPEN, anonymous(), openBody("OPD_VISIT", c.visitId()), 401, "unauthorized");
        // an owner without doctor details is told to add them
        JsonNode problem = problemOf(HttpMethod.POST, OPEN, c.clinic().owner(), openBody("OPD_VISIT", c.visitId()), 403, "doctor-details-missing");
        assertThat(problem.get("detail").asText()).isEqualTo("Add your doctor details in Team and access to write consultations.");
        assertThat(recordRows(c.clinic())).isZero();
        assertThat(audits(c.clinic(), "EMR_RECORD_OPENED")).isZero();
        assertThat(visitOnBoard(c.clinic(), c.visitId()).get("stage").asText()).as("the queue is untouched").isEqualTo("VITALS");
        // the owner with doctor details opens any doctor's patient
        ownerWithDoctorDetails(c.clinic(), "General physician", "MBBS", "REG-" + shortId());
        JsonNode opened = openVisit(c.clinic().owner(), c.visitId(), 201);
        assertThat(opened.get("record").get("ipd").get("canSwitchOn").asBoolean()).isTrue();
        assertThat(opened.get("record").get("header").get("doctor").get("membershipId").asText()).isEqualTo(c.doctor().membershipId().toString());
        // the record is read and written the same way by the doctor of the visit
        assertThat(record(c.token(), opened.get("record").get("id").asText()).get("id").asText()).isEqualTo(opened.get("record").get("id").asText());
    }

    @Test
    void aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatientsAndAnotherDoctorsPatientIs403NotOwnPatient() throws Exception {
        Ctx c = ctx();
        Staff other = addDoctor(c.clinic(), "Dentist");
        Staff visiting = addStaff(c.clinic(), "VISITING_DOCTOR", "Dr Visiting Person", "Dentist", "BDS", "REG-" + shortId());
        JsonNode denied = problemOf(HttpMethod.POST, OPEN, other.token(c.clinic()), openBody("OPD_VISIT", c.visitId()), 403, "not-own-patient");
        assertThat(denied.get("detail").asText()).isEqualTo("You can open only your own patients.");
        problemOf(HttpMethod.POST, OPEN, visiting.token(c.clinic()), openBody("OPD_VISIT", c.visitId()), 403, "not-own-patient");
        assertThat(recordRows(c.clinic())).isZero();
        // the visiting doctor's own patient works
        Ctx theirs = ctxFor(c.clinic(), visiting);
        JsonNode opened = openVisit(visiting.token(c.clinic()), theirs.visitId(), 201);
        assertThat(opened.get("record").get("layout").get("departmentKey").asText()).isEqualTo("dental");
        // and the record of one doctor is not readable, writable, switchable or advisable by another
        JsonNode mine = open(c);
        String id = mine.get("id").asText();
        problemOf(HttpMethod.GET, RECORDS + "/" + id, other.token(c.clinic()), null, 403, "not-own-patient");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", other.token(c.clinic()), draftBody(0, "ASSESSMENT", null).toString(), 403, "not-own-patient");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", other.token(c.clinic()), "{\"departmentKey\":\"dental\",\"revision\":0}", 403, "not-own-patient");
        problemOf(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", other.token(c.clinic()), "{\"revision\":0}", 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(c.clinic(), role);
            problemOf(HttpMethod.GET, RECORDS + "/" + id, staff.token(c.clinic()), null, 403, "forbidden");
            problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", staff.token(c.clinic()), draftBody(0, "ASSESSMENT", null).toString(), 403, "forbidden");
            problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", staff.token(c.clinic()), "{\"departmentKey\":\"dental\",\"revision\":0}", 403, "forbidden");
            problemOf(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", staff.token(c.clinic()), "{\"revision\":0}", 403, "forbidden");
        }
        assertThat(record(c.token(), id).get("revision").asLong()).isZero();
    }

    @Test
    void anotherClinicsVisitAndRecordAreNotFoundAndNothingOfThemIsReturned() throws Exception {
        Ctx a = ctx();
        Ctx b = ctx();
        JsonNode record = open(a);
        problemOf(HttpMethod.POST, OPEN, b.token(), openBody("OPD_VISIT", a.visitId()), 404, "encounter-not-found");
        problemOf(HttpMethod.GET, RECORDS + "/" + record.get("id").asText(), b.token(), null, 404, "record-not-found");
        problemOf(HttpMethod.PUT, RECORDS + "/" + record.get("id").asText() + "/draft", b.token(), draftBody(0, "ASSESSMENT", null).toString(), 404, "record-not-found");
        problemOf(HttpMethod.PUT, RECORDS + "/" + record.get("id").asText() + "/department", b.token(), "{\"departmentKey\":\"dental\",\"revision\":0}", 404, "record-not-found");
        problemOf(HttpMethod.POST, RECORDS + "/" + record.get("id").asText() + "/admission-advice", b.token(), "{\"revision\":0}", 404, "record-not-found");
        problemOf(HttpMethod.GET, RECORDS + "/" + UUID.randomUUID(), a.token(), null, 404, "record-not-found");
        assertThat(recordRows(b.clinic())).isZero();
        assertThat(count(b.clinic(), "select count(*) from opd.emr_record")).isZero();
        assertThat(globalCount("select count(*) from opd.emr_record")).as("no clinic bound").isZero();
    }

    @Test
    void theOpenBodyIsClosedAndEachBadValueIs400OrAnUnknownVisitIs404() throws Exception {
        Ctx c = ctx();
        for (String bad : new String[] {"{\"encounterType\":\"WALK_IN\",\"encounterId\":\"" + c.visitId() + "\"}",
                "{\"encounterType\":\"opd_visit\",\"encounterId\":\"" + c.visitId() + "\"}", "{\"encounterId\":\"" + c.visitId() + "\"}",
                "{\"encounterType\":\"OPD_VISIT\"}", "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"not-a-uuid\"}",
                "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + c.visitId() + "\",\"clinicId\":\"" + UUID.randomUUID() + "\"}",
                "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + c.visitId() + "\",\"departmentKey\":\"dental\"}"}) {
            JsonNode problem = problem(call(HttpMethod.POST, OPEN, c.token(), bad), 400);
            assertThat(problem.get("type").asText()).as(bad).isIn(PROBLEM_BASE + "validation", PROBLEM_BASE + "malformed-request");
        }
        problemOf(HttpMethod.POST, OPEN, c.token(), "", 400, "malformed-request");
        problemOf(HttpMethod.POST, OPEN, c.token(), openBody("OPD_VISIT", UUID.randomUUID()), 404, "encounter-not-found");
        assertThat(recordRows(c.clinic())).isZero();
    }

    // ---------------- the department a record gets ----------------

    @Test
    void theDepartmentIsChosenOnceWhenTheRecordIsOpenedAndLaterChangesOfTheDoctorsSpecialtyDoNotMoveIt() throws Exception {
        Clinic clinic = newClinic();
        Staff dentist = addDoctor(clinic, "Dentist");
        Ctx c = ctxFor(clinic, dentist);
        JsonNode record = open(c);
        assertThat(record.get("layout").get("departmentKey").asText()).isEqualTo("dental");
        assertThat(record.get("layout").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(layoutKeys(record)).contains("dental_chart", "intraoral");
        setDoctorSpecialty(clinic, dentist, "Paediatrician");
        assertThat(record(c.token(), record.get("id").asText()).get("layout").get("departmentKey").asText()).isEqualTo("dental");
        assertThat(openVisit(c.token(), c.visitId(), 200).get("record").get("layout").get("departmentKey").asText()).isEqualTo("dental");
        // a doctor whose specialty is not in the list gets the clinic default and the record says so
        Staff odd = addDoctor(clinic, "Zxq specialty");
        JsonNode viaDefault = open(ctxFor(clinic, odd));
        assertThat(viaDefault.get("layout").get("departmentKey").asText()).isEqualTo("gm");
        assertThat(viaDefault.get("layout").get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
    }

    // ---------------- the layout a record keeps ----------------

    @Test
    void aRecordKeepsTheLayoutItOpenedWithWhenTheOwnerPublishesAgainOrResetsAndDecidesRequiredByIt() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        ObjectNode v1 = layoutInput(department(clinic, "gm"));
        setShown(v1, "chronic", false, false);
        sec(v1, "hpi").put("required", true);
        publish(clinic, "gm", v1, 200);
        Ctx old = ctxFor(clinic, doctor);
        JsonNode opened = open(old);
        assertThat(opened.get("layout").get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(opened.get("layout").get("versionNo").asInt()).isEqualTo(1);
        assertThat(layoutKeys(opened)).doesNotContain("chronic");
        assertThat(texts(opened.get("stillNeeded"), null)).containsExactly("complaints", "hpi", "diagnosis", "rx", "rxformat");
        // v2: chronic back, hpi optional, prescription hidden and not required; v3: a reset to the starter values
        ObjectNode v2 = layoutInput(department(clinic, "gm"));
        setShown(v2, "chronic", true, false);
        sec(v2, "hpi").put("required", false);
        setShown(v2, "rx", false, false);
        publish(clinic, "gm", v2, 200);
        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("revision", department(clinic, "gm").get("revision").asLong());
        reset.put("origin", "RESET");
        reset.set("sections", get(DEPARTMENTS + "/gm/reset-target", clinic.owner()).get("sections").deepCopy());
        publish(clinic, "gm", reset, 200);
        assertThat(department(clinic, "gm").get("publishedVersion").asInt()).isEqualTo(3);
        JsonNode again = record(old.token(), opened.get("id").asText());
        assertThat(again.get("layout")).as("the layout of the open is untouched").isEqualTo(opened.get("layout"));
        assertThat(again.get("stillNeeded")).isEqualTo(opened.get("stillNeeded"));
        assertThat(again.get("parts")).isEqualTo(opened.get("parts"));
        assertThat(openVisit(old.token(), old.visitId(), 200).get("record").get("layout")).isEqualTo(opened.get("layout"));
        // the saved values are judged by the snapshot: hpi is still needed
        JsonNode saved = save(old, again, sectionValues("complaints", COMPLAINTS));
        assertThat(texts(saved.get("stillNeeded"), null)).contains("hpi").doesNotContain("complaints");
        // a visit opened now reads v3
        JsonNode fresh = open(ctxFor(clinic, doctor));
        assertThat(fresh.get("layout").get("versionNo").asInt()).isEqualTo(3);
        assertThat(layoutKeys(fresh)).contains("chronic", "rx").doesNotContain("procedures");
        assertThat(texts(fresh.get("stillNeeded"), null)).containsExactly("complaints", "diagnosis", "rx", "rxformat");
        // the version the old record points at is still there and unchanged
        JsonNode version = get(DEPARTMENTS + "/gm/versions/1", clinic.owner());
        List<String> shownInVersion = new ArrayList<>();
        version.get("sections").forEach(s -> {
            if (s.get("shown").asBoolean()) {
                shownInVersion.add(s.get("key").asText());
            }
        });
        assertThat(layoutKeys(again)).isEqualTo(shownInVersion);
        assertThat(count(clinic, "select count(*) from opd.emr_department_version")).isEqualTo(3);
    }

    @Test
    void aRecordOpenedOnTheStarterLayoutKeepsItWhenTheOwnerPublishesTheDepartmentLater() throws Exception {
        Ctx c = ctx();
        JsonNode opened = open(c);
        assertThat(opened.get("layout").get("layoutSource").asText()).isEqualTo("STARTER");
        publishOneChange(c.clinic(), "gm", "adult_vax", false);
        JsonNode again = record(c.token(), opened.get("id").asText());
        assertThat(again.get("layout")).isEqualTo(opened.get("layout"));
        assertThat(layoutKeys(again)).contains("adult_vax");
        assertThat(row(c.clinic(), "select layout_kind from opd.emr_record where id = ?::uuid", opened.get("id").asText())).containsExactly("STARTER_LAYOUT");
        JsonNode next = open(ctxFor(c.clinic(), c.doctor()));
        assertThat(next.get("layout").get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(layoutKeys(next)).doesNotContain("adult_vax");
    }

    // ---------------- Save draft ----------------

    @Test
    void saveDraftStoresTheValuesThePositionAndANewRevisionWithoutCheckingTheRequiredSections() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        String id = record.get("id").asText();
        // an empty save stores the position only and never validates completeness
        JsonNode first = saveRecordDraft(c.token(), id, draftBody(0, "TREATMENT", "rx"), 200);
        assertThat(first.get("revision").asLong()).isEqualTo(1);
        assertThat(first.get("uiState").get("part").asText()).isEqualTo("TREATMENT");
        assertThat(first.get("uiState").get("sectionKey").asText()).isEqualTo("rx");
        assertThat(texts(first.get("stillNeeded"), null)).hasSize(4);
        // values of one section, then of another: the first stays as it was
        JsonNode second = saveRecordDraft(c.token(), id, draftBody(1, "ASSESSMENT", "complaints", sectionValues("complaints", COMPLAINTS)), 200);
        assertThat(second.get("revision").asLong()).isEqualTo(2);
        JsonNode third = saveRecordDraft(c.token(), id, draftBody(2, "ASSESSMENT", "history",
                sectionValues("history", "{\"past_illness\":[\"dm\",\"htn\"],\"diet\":\"veg\"}")), 200);
        assertThat(third.get("revision").asLong()).isEqualTo(3);
        JsonNode again = record(c.token(), id);
        assertThat(recordSection(again, "complaints").get("values").get("complaint").get("items").get(0).get("text").asText()).isEqualTo("Fever");
        assertThat(recordSection(again, "complaints").get("values").get("complaint").get("items").get(0).get("attr1").asText()).isEqualTo("D3");
        assertThat(recordSection(again, "history").get("values").get("past_illness").toString()).isEqualTo("[\"dm\",\"htn\"]");
        assertThat(recordSection(again, "history").get("updatedAt").asText()).isNotBlank();
        assertThat(again.get("uiState").get("sectionKey").asText()).as("the doctor comes back exactly where they were").isEqualTo("history");
        assertThat(again.get("revision").asLong()).isEqualTo(3);
        // the values of a section are replaced as a whole, and an empty value clears the field
        JsonNode cleared = saveRecordDraft(c.token(), id, draftBody(3, "ASSESSMENT", "history",
                sectionValues("history", "{\"past_illness\":[],\"diet\":\"\"}")), 200);
        assertThat(recordSection(cleared, "history").get("values").size()).isZero();
        assertThat(recordSection(cleared, "complaints").get("values").size()).isEqualTo(1);
        // what is stored is one document per section, with the shape version
        assertThat(row(c.clinic(), "select schema_version::text, jsonb_typeof(sections), jsonb_exists(sections, 'complaints')::text, revision::text, ui_part "
                + "from opd.emr_record where id = ?::uuid", id)).containsExactly("1", "object", "true", "4", "ASSESSMENT");
        // one audit entry per save: who and the section keys, never a value
        List<List<String>> saves = auditRows(c.clinic(), "EMR_RECORD_DRAFT_SAVED");
        assertThat(saves).hasSize(5);
        JsonNode keys = objectMapper.readTree(saves.get(1).get(2));
        assertThat(texts(keys.get("sectionKeys"), null)).containsExactly("complaints");
        assertThat(saves.get(1).get(2)).doesNotContain("Fever");
        assertThat(saves.get(1).get(3)).isEqualTo(c.doctor().userId().toString());
        for (List<String> row : saves) {
            assertThat(row.get(1) + row.get(2)).doesNotContain("Fever").doesNotContain("past_illness").doesNotContain("Ramesh");
        }
    }

    @Test
    void aSectionIsDoneWhenEveryRequiredFieldIsFilledOrWhenOneFieldIsFilledIfNothingIsRequiredAndNeverWhileCopiedValuesWait() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        // phase 3 (ADR-022 D2): the duration is covered by the list, so complaints is Done with one active complaint; an
        // inactive one does not count
        JsonNode inactive = save(c, record, sectionValues("complaints", COMPLAINTS.replace("\"active\":true", "\"active\":false")));
        assertThat(recordSection(inactive, "complaints").get("status").asText()).isEqualTo("REQUIRED");
        assertThat(texts(inactive.get("stillNeeded"), null)).contains("complaints");
        JsonNode half = inactive;
        JsonNode whole = save(c, half, sectionValues("complaints", COMPLAINTS));
        assertThat(recordSection(whole, "complaints").get("status").asText()).isEqualTo("DONE");
        assertThat(texts(whole.get("stillNeeded"), null)).doesNotContain("complaints");
        assertThat(whole.get("parts").get(0).get("requiredDone").asInt()).isEqualTo(1);
        assertThat(whole.get("parts").get(0).get("complete").asBoolean()).as("a tick replaces the number").isTrue();
        assertThat(whole.get("parts").get(1).get("complete").asBoolean()).isFalse();
        // hpi has no required field: one field is enough; an empty text is not a value; it never blocks
        assertThat(recordSection(whole, "hpi").get("status").asText()).isEqualTo("OPTIONAL");
        JsonNode blank = save(c, whole, sectionValues("hpi", "{\"narrative\":\"   \"}"));
        assertThat(recordSection(blank, "hpi").get("status").asText()).isEqualTo("OPTIONAL");
        JsonNode filled = save(c, blank, sectionValues("hpi", "{\"narrative\":\"Fever for three days\"}"));
        assertThat(recordSection(filled, "hpi").get("status").asText()).isEqualTo("DONE");
        assertThat(filled.get("parts").get(0).get("sectionsDone").asInt()).isEqualTo(2);
        // vitals is filled with the vitals log editor since SAH-60 (specialty only; see EmrSpecialtyScreensIT), so it is not tried with plain values here
    }

    @Test
    void whenEveryRequiredSectionIsDoneTheFooterIsReadyToCompleteAndMovingBetweenPartsNeverNeededThem() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        assertThat(record.get("readyToComplete").asBoolean()).isFalse();
        JsonNode filled = fillRequired(c, record);
        assertThat(filled.get("stillNeeded").size()).isZero();
        assertThat(filled.get("readyToComplete").asBoolean()).isTrue();
        assertThat(filled.get("parts").get(0).get("complete").asBoolean()).isTrue();
        assertThat(filled.get("parts").get(1).get("complete").asBoolean()).isTrue();
        assertThat(filled.get("parts").get(1).get("requiredDone").asInt()).isEqualTo(3);
        for (String key : new String[] {"complaints", "diagnosis", "rx", "rxformat"}) {
            assertThat(recordSection(filled, key).get("status").asText()).isEqualTo("DONE");
        }
        assertThat(filled.get("status").asText()).as("in phase 2 Complete does not sign").isEqualTo("DRAFT");
        // clearing one required field takes it back
        JsonNode cleared = save(c, filled, sectionValues("rx", "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":[]}}"));
        assertThat(texts(cleared.get("stillNeeded"), null)).containsExactly("rx");
        assertThat(cleared.get("readyToComplete").asBoolean()).isFalse();
        // Save draft moves between the parts with required sections missing: it never refuses
        assertThat(saveRecordDraft(c.token(), cleared.get("id").asText(), draftBody(cleared.get("revision").asLong(), "TREATMENT", "diagnosis"), 200)
                .get("uiState").get("part").asText()).isEqualTo("TREATMENT");
    }

    @Test
    void aHiddenSectionNeverAppearsInStillNeededAndADepartmentWithNothingRequiredShowsZeroOfZeroWithATick() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "rx", false, false);
        setShown(body, "rxformat", false, false);
        publish(clinic, "gm", body, 200);
        JsonNode record = open(ctxFor(clinic, doctor));
        assertThat(texts(record.get("stillNeeded"), null)).containsExactly("complaints", "diagnosis");
        assertThat(layoutKeys(record)).doesNotContain("rx", "rxformat");
        ObjectNode none = layoutInput(department(clinic, "gm"));
        for (JsonNode s : none.get("sections")) {
            ((ObjectNode) s).put("required", false);
        }
        publish(clinic, "gm", none, 200);
        JsonNode open = open(ctxFor(clinic, doctor));
        assertThat(open.get("stillNeeded").size()).isZero();
        assertThat(open.get("readyToComplete").asBoolean()).isTrue();
        for (JsonNode part : open.get("parts")) {
            assertThat(part.get("requiredTotal").asInt()).isZero();
            assertThat(part.get("requiredDone").asInt()).isZero();
            assertThat(part.get("complete").asBoolean()).as("a tick shows with 0 of 0 required").isTrue();
        }
        // a part with every section moved out is empty and still ticks
        ObjectNode moved = layoutInput(department(clinic, "gm"));
        for (JsonNode s : moved.get("sections")) {
            ((ObjectNode) s).put("part", "ASSESSMENT");
        }
        publish(clinic, "gm", moved, 200);
        JsonNode empty = open(ctxFor(clinic, doctor));
        assertThat(empty.get("parts").get(1).get("sectionsTotal").asInt()).isZero();
        assertThat(empty.get("parts").get(1).get("complete").asBoolean()).isTrue();
    }

    // ---------------- the value rules ----------------

    @Test
    void eachValueMustMatchItsFieldAndABadOneIs400ValidationNamingTheFieldWithoutEchoingTheValueAndStoresNothing() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        String id = record.get("id").asText();
        String[][] bad = {
                {"complaints", "{\"duration\":\"three days\"}", "duration"},
                {"complaints", "{\"duration\":{\"value\":\"3\",\"unit\":\"days\"}}", "duration"},
                {"complaints", "{\"duration\":{\"value\":3}}", "duration"},
                {"complaints", "{\"duration\":{\"value\":3,\"unit\":\"days\",\"extra\":1}}", "duration"},
                {"complaints", "{\"duration\":{\"value\":3,\"unit\":\"" + "u".repeat(21) + "\"}}", "duration"},
                {"complaints", "{\"duration\":{\"value\":1000001,\"unit\":\"days\"}}", "duration"},
                {"complaints", "{\"duration\":{\"value\":1.23456,\"unit\":\"days\"}}", "duration"},
                {"complaints", "{\"severity\":\"extreme\"}", "severity"},
                {"complaints", "{\"severity\":3}", "severity"},
                {"complaints", "{\"notes\":\"<script>alert(1)</script>\"}", "notes"},
                {"complaints", "{\"notes\":\"" + "n".repeat(2001) + "\"}", "notes"},
                {"complaints", "{\"notes\":42}", "notes"},
                {"complaints", "{\"complaint\":\"Fever\"}", "complaint"},
                {"complaints", "{\"complaint\":[{\"itemKey\":\"abc\",\"text\":\"Fever\",\"newToList\":true}]}", "complaint"},
                {"complaints", "{\"complaint\":[{\"itemKey\":null,\"text\":\"Fever\",\"newToList\":false}]}", "complaint"},
                {"complaints", "{\"complaint\":[{\"itemKey\":null,\"text\":\"" + "t".repeat(201) + "\",\"newToList\":true}]}", "complaint"},
                {"complaints", "{\"complaint\":[{\"itemKey\":null,\"text\":\"\",\"newToList\":true}]}", "complaint"},
                {"complaints", "{\"complaint\":[{\"itemKey\":null,\"newToList\":true}]}", "complaint"},
                {"vitals", "{\"bp_systolic\":\"120\"}", "values"}, // the vitals section takes specialty only (SAH-60)
                {"vitals", "{\"bp_systolic\":2000000}", "values"},
                {"vitals", "{\"bmi\":22}", "values"},
                {"history", "{\"past_illness\":[\"covid\"]}", "past_illness"},
                {"history", "{\"past_illness\":\"dm\"}", "past_illness"},
                {"history", "{\"diet\":\"vegan\"}", "diet"},
                {"history", "{\"surgical_history\":[{\"procedure\":\"Appendectomy\",\"month\":\"May\"}]}", "surgical_history"},
                {"followup", "{\"next_visit\":\"tomorrow\"}", "next_visit"},
                {"followup", "{\"next_visit\":\"2026-02-30\"}", "next_visit"},
                {"attachments", "{\"files\":\"report.pdf\"}", "files"},
                {"complaints", "{\"zxqunknownfield\":\"x\"}", "values"}};
        for (String[] b : bad) {
            JsonNode problem = problem(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(),
                    draftBody(0, "ASSESSMENT", null, sectionValues(b[0], b[1])).toString()), 400);
            assertThat(problem.get("type").asText()).as(b[0] + " " + b[1]).isEqualTo(PROBLEM_BASE + "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).as(b[1] + " -> " + problem.get("fieldErrors"))
                    .isEqualTo("values".equals(b[2]) ? "sections[0].values" : "sections[0].values." + b[2]);
            assertThat(problem.toString()).as("no value is echoed").doesNotContain("script").doesNotContain("extreme").doesNotContain("Appendectomy")
                    .doesNotContain("zxqunknownfield");
        }
        assertThat(record(c.token(), id).get("revision").asLong()).as("nothing was stored").isZero();
        assertThat(row(c.clinic(), "select sections::text from opd.emr_record where id = ?::uuid", id)).containsExactly("{}");
        assertThat(audits(c.clinic(), "EMR_RECORD_DRAFT_SAVED")).isZero();
        // the same limits accept their edge values
        JsonNode edge = saveRecordDraft(c.token(), id, draftBody(0, "ASSESSMENT", null,
                sectionValues("complaints", "{\"notes\":\"" + "n".repeat(2000) + "\"}"),
                sectionValues("history", "{\"surgical_history\":[{\"procedure\":\"Appendectomy\",\"year\":\"2019\"}],\"habits\":[\"smoking\",\"alcohol\"]}")), 200);
        assertThat(edge.get("revision").asLong()).isEqualTo(1);
    }

    @Test
    void aSectionThatIsNotInTheRecordsLayoutOrADuplicateOrAWrongShapeIs400() throws Exception {
        Ctx c = ctx();
        String id = open(c).get("id").asText();
        // procedures, referral and consent are off in General Medicine: they are not in this record's layout
        for (String key : new String[] {"procedures", "referral", "consent", "dental_chart", "no_such_section", "GM"}) {
            JsonNode problem = problem(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(),
                    draftBody(0, "ASSESSMENT", null, sectionValues(key, "{}")).toString()), 400);
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).as(key).isEqualTo("sections[0].key");
        }
        problem(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), draftBody(0, "ASSESSMENT", null,
                sectionValues("hpi", "{}"), sectionValues("hpi", "{}")).toString()), 400);
        for (String values : new String[] {"[]", "\"text\"", "5", "null"}) {
            ObjectNode section = objectMapper.createObjectNode();
            section.put("key", "hpi");
            section.set("values", objectMapper.readTree(values));
            problem(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), draftBody(0, "ASSESSMENT", null, section).toString()), 400);
        }
        // the position: a part that does not exist, a section that is not in the layout, a missing position
        for (String body : new String[] {
                "{\"revision\":0,\"uiState\":{\"part\":\"MIDDLE\",\"sectionKey\":null},\"sections\":[]}",
                "{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":\"consent\"},\"sections\":[]}",
                "{\"revision\":0,\"sections\":[]}", "{\"revision\":0,\"uiState\":{\"sectionKey\":null},\"sections\":[]}",
                "{\"revision\":-1,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[]}",
                "{\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[]}",
                "{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[],\"clinicId\":\"" + UUID.randomUUID() + "\"}",
                "{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null,\"extra\":1},\"sections\":[]}"}) {
            assertThat(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), body).andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), "", 400, "malformed-request");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), "{not json", 400, "malformed-request");
        assertThat(record(c.token(), id).get("revision").asLong()).isZero();
        // too many sections
        ObjectNode many = draftBody(0, "ASSESSMENT", null);
        for (int i = 0; i < 121; i++) {
            many.withArray("sections").add(sectionValues("hpi", "{}"));
        }
        assertThat(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), many.toString()).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aBodyOverSixtyFourKilobytesIs413AndNothingIsStored() throws Exception {
        Ctx c = ctx();
        String id = open(c).get("id").asText();
        ObjectNode big = draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"" + "x".repeat(70_000) + "\"}"));
        assertThat(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), big.toString()).andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(record(c.token(), id).get("revision").asLong()).isZero();
        // one text over the field limit but under the body limit is a normal 400
        ObjectNode long10001 = draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"" + "x".repeat(10_001) + "\"}"));
        problem(call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), long10001.toString()), 400);
        ObjectNode ok10000 = draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"" + "x".repeat(10_000) + "\"}"));
        saveRecordDraft(c.token(), id, ok10000, 200);
    }

    @Test
    void aStaleRevisionIs409EmrRecordChangedAndNothingIsStoredAndTwoSavesAtTheSameMomentOneWins() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        String id = record.get("id").asText();
        saveRecordDraft(c.token(), id, draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"first\"}")), 200);
        JsonNode stale = problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(),
                draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"second\"}")).toString(), 409, "emr-record-changed");
        assertThat(stale.get("detail").asText()).isEqualTo("This EMR was changed elsewhere. Reload to see the latest.");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(),
                draftBody(9, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"future\"}")).toString(), 409, "emr-record-changed");
        assertThat(recordSection(record(c.token(), id), "hpi").get("values").get("narrative").asText()).isEqualTo("first");
        // a second tab with the same revision
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String text : new String[] {"tab one", "tab two"}) {
                String body = draftBody(1, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"" + text + "\"}")).toString();
                futures.add(pool.submit(() -> call(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), body).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(record(c.token(), id).get("revision").asLong()).isEqualTo(2);
    }

    @Test
    void draftsAreForOpdVisitsOnlyAndASignedRecordIsRefusedWith409AndStaysReadOnly() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        String id = record.get("id").asText();
        JsonNode saved = saveRecordDraft(c.token(), id, draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"before signing\"}")), 200);
        sign(c.clinic(), id);
        JsonNode signed = record(c.token(), id);
        assertThat(signed.get("status").asText()).isEqualTo("SIGNED");
        assertThat(signed.get("readOnly").asBoolean()).isTrue();
        assertThat(signed.get("readOnlyReason").asText()).isEqualTo("SIGNED");
        assertThat(signed.get("departmentOptions").size()).as("no switch on a signed record").isZero();
        assertThat(signed.get("layout")).isEqualTo(saved.get("layout"));
        String body = draftBody(1, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"after signing\"}")).toString();
        JsonNode problem = problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/draft", c.token(), body, 409, "emr-record-signed");
        assertThat(problem.get("detail").asText()).isEqualTo("This EMR is signed and can no longer be changed.");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"dental\",\"revision\":1}", 409, "emr-record-signed");
        problemOf(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":1}", 409, "emr-record-signed");
        assertThat(recordSection(record(c.token(), id), "hpi").get("values").get("narrative").asText()).isEqualTo("before signing");
        // opening a signed record shows it read-only and changes nothing
        JsonNode reopened = openVisit(c.token(), c.visitId(), 200);
        assertThat(reopened.get("record").get("readOnly").asBoolean()).isTrue();
        assertThat(reopened.get("record").get("readOnlyReason").asText()).isEqualTo("SIGNED");
        assertThat(recordRows(c.clinic())).isEqualTo(1);
    }

    // ---------------- switch department ----------------

    @Test
    void theDoctorSwitchesTheDepartmentOfADraftRecordNoEnteredValueIsDeletedAndTheSwitchIsNotSavedAsTheirDefault() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        String id = record.get("id").asText();
        JsonNode saved = saveRecordDraft(c.token(), id, draftBody(0, "ASSESSMENT", null,
                sectionValues("complaints", COMPLAINTS), sectionValues("chronic", "{\"condition\":\"htn\"}")), 200);
        assertThat(recordSection(saved, "chronic").get("values").size()).isPositive();
        JsonNode switched = put(RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"dental\",\"revision\":1}", 200);
        assertThat(switched.get("layout").get("departmentKey").asText()).isEqualTo("dental");
        assertThat(switched.get("layout").get("departmentName").asText()).isEqualTo("Dental");
        assertThat(switched.get("layout").get("choiceSource").asText()).isEqualTo("SWITCHED");
        assertThat(switched.get("revision").asLong()).isEqualTo(2);
        assertThat(layoutKeys(switched)).contains("dental_chart", "complaints").doesNotContain("chronic");
        assertThat(recordSection(switched, "complaints").get("values").size()).as("a section in both layouts keeps its values (the complaint list; duration and severity are covered by the list)").isEqualTo(1);
        // the value of the section that is not in the new layout is kept in the record but not shown
        assertThat(switched.toString()).doesNotContain("\"condition\"");
        assertThat(row(c.clinic(), "select jsonb_exists(sections, 'chronic')::text, department_key, choice_source, layout_kind from opd.emr_record where id = ?::uuid", id))
                .containsExactly("true", "dental", "SWITCHED", "STARTER_LAYOUT");
        // back to General Medicine: the value is there again
        JsonNode back = put(RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"gm\",\"revision\":2}", 200);
        assertThat(recordSection(back, "chronic").get("values").toString()).contains("htn");
        // the doctor's own choice is not changed
        JsonNode me = get(ME, c.token());
        assertThat(me.get("department").get("departmentKey").asText()).isEqualTo("gm");
        assertThat(me.get("department").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        // a new visit of the same doctor opens with the doctor's own department again
        assertThat(open(ctxFor(c.clinic(), c.doctor())).get("layout").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        // two audit entries: who, the old and the new department
        List<List<String>> audits = auditRows(c.clinic(), "EMR_RECORD_DEPARTMENT_SWITCHED");
        assertThat(audits).hasSize(2);
        assertThat(objectMapper.readTree(audits.get(0).get(1)).get("departmentKey").asText()).isEqualTo("gm");
        assertThat(objectMapper.readTree(audits.get(0).get(2)).get("departmentKey").asText()).isEqualTo("dental");
        assertThat(audits.get(0).get(3)).isEqualTo(c.doctor().userId().toString());
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain("htn").doesNotContain("Fever");
    }

    @Test
    void switchingToTheSameDepartmentChangesNothingAndTheRulesOfTheTargetAreChecked() throws Exception {
        Ctx c = ctx();
        String id = open(c).get("id").asText();
        JsonNode same = put(RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"gm\",\"revision\":0}", 200);
        assertThat(same.get("revision").asLong()).isZero();
        assertThat(same.get("layout").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(audits(c.clinic(), "EMR_RECORD_DEPARTMENT_SWITCHED")).isZero();
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"dental\",\"revision\":5}", 409, "emr-record-changed");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"zz_nothing\",\"revision\":0}", 404, "department-not-found");
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"DENTAL\",\"revision\":0}", 400, "bad-request");
        for (String bad : new String[] {"{\"revision\":0}", "{\"departmentKey\":\"dental\"}", "{\"departmentKey\":\"dental\",\"revision\":-1}",
                "{\"departmentKey\":\"dental\",\"revision\":0,\"choiceSource\":\"DOCTOR_SPECIALTY\"}"}) {
            assertThat(call(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), bad).andReturn().getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        // an added department that was never published is not offered, a published one is
        JsonNode added = addDepartment(c.clinic(), "Walk-in clinic", BLANK_SOURCE);
        String key = added.get("departmentKey").asText();
        assertThat(texts(record(c.token(), id).get("departmentOptions"), "departmentKey")).doesNotContain(key);
        problemOf(HttpMethod.PUT, RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"" + key + "\",\"revision\":0}", 404, "department-not-published");
        publishAsIs(c.clinic(), key);
        assertThat(texts(record(c.token(), id).get("departmentOptions"), "departmentKey")).contains(key);
        JsonNode switched = put(RECORDS + "/" + id + "/department", c.token(), "{\"departmentKey\":\"" + key + "\",\"revision\":0}", 200);
        assertThat(switched.get("layout").get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(switched.get("layout").get("versionNo").asInt()).isEqualTo(1);
        assertThat(switched.get("layout").get("starterKey").isNull()).isTrue();
        assertThat(row(c.clinic(), "select layout_kind, (department_version_id is not null)::text from opd.emr_record where id = ?::uuid", id))
                .containsExactly("DEPARTMENT_VERSION", "true");
    }

    // ---------------- Admit to IPD ----------------

    @Test
    void adviseAdmissionMarksTheRecordStoresTheReferralLineOnlyOnceAndAuditsWithoutTheText() throws Exception {
        Ctx c = ctx();
        String id = open(c).get("id").asText();
        JsonNode advised = post(RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":0,\"referralLine\":\"Refer to district hospital for observation\"}", 200);
        assertThat(advised.get("admissionAdvised").asBoolean()).isTrue();
        assertThat(advised.get("referralLine").asText()).isEqualTo("Refer to district hospital for observation");
        assertThat(advised.get("revision").asLong()).isEqualTo(1);
        // repeating changes nothing: the same line, or no line at all
        JsonNode same = post(RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":1,\"referralLine\":\"Refer to district hospital for observation\"}", 200);
        assertThat(same.get("revision").asLong()).isEqualTo(1);
        JsonNode noLine = post(RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":1}", 200);
        assertThat(noLine.get("revision").asLong()).isEqualTo(1);
        assertThat(noLine.get("referralLine").asText()).isEqualTo("Refer to district hospital for observation");
        List<List<String>> audits = auditRows(c.clinic(), "EMR_ADMISSION_ADVISED");
        assertThat(audits).hasSize(1);
        assertThat(objectMapper.readTree(audits.get(0).get(2)).get("withReferral").asBoolean()).isTrue();
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain("district hospital");
        // a new line replaces the old one
        JsonNode changed = post(RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":1,\"referralLine\":\"Admit under medicine\"}", 200);
        assertThat(changed.get("referralLine").asText()).isEqualTo("Admit under medicine");
        assertThat(changed.get("revision").asLong()).isEqualTo(2);
        // without a line the record is marked and has no referral
        String other = open(ctxFor(c.clinic(), c.doctor())).get("id").asText();
        JsonNode plain = post(RECORDS + "/" + other + "/admission-advice", c.token(), "{\"revision\":0}", 200);
        assertThat(plain.get("admissionAdvised").asBoolean()).isTrue();
        assertThat(plain.get("referralLine").isNull()).isTrue();
        assertThat(objectMapper.readTree(auditRows(c.clinic(), "EMR_ADMISSION_ADVISED").get(2).get(2)).get("withReferral").asBoolean()).isFalse();
    }

    @Test
    void aReferralLineIsOneToThreeHundredCharactersAndAStaleRevisionIs409() throws Exception {
        Ctx c = ctx();
        String id = open(c).get("id").asText();
        for (String bad : new String[] {"\"\"", "\"   \"", "\"" + "r".repeat(301) + "\""}) {
            JsonNode problem = problem(call(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", c.token(),
                    "{\"revision\":0,\"referralLine\":" + bad + "}"), 400);
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("referralLine");
        }
        for (String bad : new String[] {"{}", "{\"revision\":-1}", "{\"revision\":0,\"label\":\"x\"}"}) {
            assertThat(call(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", c.token(), bad).andReturn().getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        problemOf(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":4,\"referralLine\":\"x\"}", 409, "emr-record-changed");
        assertThat(record(c.token(), id).get("admissionAdvised").asBoolean()).isFalse();
        JsonNode edge = post(RECORDS + "/" + id + "/admission-advice", c.token(), "{\"revision\":0,\"referralLine\":\"" + "r".repeat(300) + "\"}", 200);
        assertThat(edge.get("referralLine").asText()).hasSize(300);
    }

    // ---------------- header ----------------

    @Test
    void theHeaderShowsARecordedAllergyAndKnownConditionsAndNeverNoKnownAllergyAndTheVisitCountAndLastVisit() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String mobile = uniqueMobile();
        JsonNode patient = ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":\"Meera Joshi\",\"age\":31,\"sex\":\"F\",\"mobile\":\"" + mobile + "\",\"area\":\"Kothrud\",\"language\":\"mr\","
                        + "\"allergy\":\"Penicillin\"}", 201);
        String patientId = patient.get("id").asText();
        JsonNode visit = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        JsonNode first = openVisit(doctor.token(clinic), visit.get("id").asText(), 201).get("record");
        assertThat(first.get("header").get("allergyText").asText()).isEqualTo("Penicillin");
        assertThat(first.get("header").get("patientName").asText()).isEqualTo("Meera Joshi");
        assertThat(first.get("header").get("initials").asText()).isEqualTo("MJ");
        assertThat(first.get("header").get("sex").asText()).isEqualTo("F");
        assertThat(first.get("header").get("visitOrdinal").asInt()).isEqualTo(1);
        // a second visit inside the follow-up window is a follow-up, the third visit of the patient, and the last visit was today
        JsonNode again = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        JsonNode second = openVisit(doctor.token(clinic), again.get("id").asText(), 201).get("record");
        assertThat(second.get("header").get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(second.get("header").get("visitOrdinal").asInt()).isEqualTo(2);
        assertThat(second.get("header").get("lastVisitDaysAgo").asInt()).isZero();
        // "None known" and similar placeholders are not an allergy tag
        UUID noneKnown = UUID.fromString(ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":\"Kiran Desai\",\"age\":50,\"sex\":\"M\",\"mobile\":\"" + uniqueMobile() + "\",\"area\":\"Kothrud\",\"language\":\"mr\","
                        + "\"allergy\":\"None known\"}", 201).get("id").asText());
        JsonNode v3 = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + noneKnown + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        assertThat(openVisit(doctor.token(clinic), v3.get("id").asText(), 201).get("record").get("header").get("allergyText").isNull()).isTrue();
    }

    // ---------------- Copy from last visit ----------------

    @Test
    void copyFromLastVisitPrefillsFromThePatientsLastSignedRecordOfTheSameDepartmentAndWaitsForTheDoctorsConfirmation() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String patientId = newPatient(clinic).toString();
        JsonNode v1 = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        JsonNode first = openVisit(doctor.token(clinic), v1.get("id").asText(), 201).get("record");
        saveRecordDraft(doctor.token(clinic), first.get("id").asText(), draftBody(0, "ASSESSMENT", null,
                sectionValues("history", "{\"past_illness\":[\"dm\",\"htn\"]}"),
                sectionValues("allergy", "{\"drug_allergy\":[{\"itemKey\":null,\"text\":\"Penicillin\",\"newToList\":true}]}"),
                sectionValues("complaints", COMPLAINTS)), 200);
        // an unsigned record is not copied from
        JsonNode v2 = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        JsonNode beforeSigning = openVisit(doctor.token(clinic), v2.get("id").asText(), 201).get("record");
        for (JsonNode s : beforeSigning.get("sections")) {
            assertThat(s.get("copiedFrom").isNull()).as(s.get("key").asText()).isTrue();
            assertThat(s.get("values").size()).isZero();
        }
        sign(clinic, first.get("id").asText());
        JsonNode v3 = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        JsonNode copied = openVisit(doctor.token(clinic), v3.get("id").asText(), 201).get("record");
        JsonNode history = recordSection(copied, "history");
        assertThat(history.get("copiedFrom").get("recordId").asText()).isEqualTo(first.get("id").asText());
        assertThat(history.get("copiedFrom").get("date").asText()).matches("^\\d{4}-\\d{2}-\\d{2}$");
        assertThat(history.get("copyPendingConfirmation").asBoolean()).isTrue();
        assertThat(history.get("values").get("past_illness").toString()).isEqualTo("[\"dm\",\"htn\"]");
        assertThat(history.get("status").asText()).as("not Done until the doctor confirms or edits").isEqualTo("OPTIONAL");
        assertThat(recordSection(copied, "allergy").get("copyPendingConfirmation").asBoolean()).isTrue();
        // sections without the Copy flag are not copied
        assertThat(recordSection(copied, "complaints").get("values").size()).isZero();
        assertThat(recordSection(copied, "complaints").get("copiedFrom").isNull()).isTrue();
        // the doctor confirms one of them
        String id = copied.get("id").asText();
        JsonNode confirmed = saveRecordDraft(doctor.token(clinic), id, draftBody(0, "ASSESSMENT", null,
                confirm(recordSection(copied, "history"))), 200);
        assertThat(recordSection(confirmed, "history").get("copyPendingConfirmation").asBoolean()).isFalse();
        assertThat(recordSection(confirmed, "history").get("status").asText()).isEqualTo("DONE");
        assertThat(recordSection(confirmed, "allergy").get("copyPendingConfirmation").asBoolean()).isTrue();
        // another department never copies from this patient's General Medicine record
        Staff dentist = addDoctor(clinic, "Dentist");
        JsonNode v4 = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + dentist.membershipId() + "\"}", 201);
        JsonNode dental = openVisit(dentist.token(clinic), v4.get("id").asText(), 201).get("record");
        for (JsonNode s : dental.get("sections")) {
            assertThat(s.get("copiedFrom").isNull()).as(s.get("key").asText()).isTrue();
        }
        // another clinic's patient history is never read
        Clinic other = newClinic();
        Staff otherDoctor = addDoctor(other, "General physician");
        JsonNode theirs = openVisit(otherDoctor.token(other), issueVisit(other, otherDoctor.membershipId()).get("id").asText(), 201).get("record");
        assertThat(theirs.toString()).doesNotContain("Penicillin");
    }

    private ObjectNode confirm(JsonNode section) throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("key", section.get("key").asText());
        input.set("values", section.get("values").deepCopy());
        input.put("confirmCopy", true);
        return input;
    }

    // ---------------- no patient data in the stored shape ----------------

    @Test
    void theRecordRowHoldsReferencesAndOneDocumentAndNoCopyOfThePatientsNameOrTheLayout() throws Exception {
        Ctx c = ctx();
        JsonNode record = open(c);
        fillRequired(c, record);
        String dump = row(c.clinic(), "select row_to_json(r)::text from opd.emr_record r where id = ?::uuid", record.get("id").asText()).get(0);
        assertThat(dump).doesNotContain("Ramesh").doesNotContain("Kulkarni").doesNotContain("Dr Test Doctor");
        assertThat(dump).doesNotContain("\"fields\"").doesNotContain("\"hideLocked\"");
        assertThat(globalRows("select count(*) from opd.emr_record").get(0).get(0)).as("no clinic bound sees nothing").isEqualTo("0");
    }
}
