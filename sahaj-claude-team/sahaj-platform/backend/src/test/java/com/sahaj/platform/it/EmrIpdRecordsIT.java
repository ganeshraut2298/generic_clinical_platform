package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-46-4 and SAH-52-1 on the real database: the EMR of an admitted patient (one record per admission per Asia/Kolkata
 * day, "Daily progress note") and of an Emergency case (one per case, "Emergency assessment"). The encounter is read
 * through {@code EmrEncounterSource}, which {@code ipd} implements with its own ward-scope rules: only an owner and a doctor
 * reach it, a visiting doctor and a nurse answer 403 forbidden, a nurse's break-glass grant opens nothing in the EMR and
 * is not touched, and an IPD that is off answers 403 module-off. Saving a draft for these encounters is on since SAH-51
 * (OQ-43-27 answered, docs/api/opd.yaml 0.6.0: the answer encounter-drafts-not-enabled is gone); signing them is in
 * EmrIpdRoundFinishIT and EmrEmergencyFinishIT. Test plan: docs/test-plans/SAH-53.md and SAH-51-68.md.
 */
@OnTestDatabase
class EmrIpdRecordsIT extends ScopeSupport {

    private static final String EMR = "/api/v1/opd/emr";
    private static final String OPEN = EMR + "/records/open";
    private static final String RECORDS = EMR + "/records";
    private static final String ER_CASES_URL = IPD + "/emergency/cases";

    private static String openBody(String type, Object id) {
        return "{\"encounterType\":\"" + type + "\",\"encounterId\":\"" + id + "\"}";
    }

    private JsonNode open(RequestPostProcessor who, String type, Object id, int expected) throws Exception {
        return ok(HttpMethod.POST, OPEN, who, openBody(type, id), expected);
    }

    private JsonNode refused(ResultActions actions, int status, String slug) throws Exception {
        JsonNode p = problem(actions, status);
        assertThat(p.get("type").asText()).isEqualTo(PROBLEM_BASE + slug);
        return p;
    }

    private JsonNode refusedOpen(RequestPostProcessor who, String type, Object id, int status, String slug) throws Exception {
        return refused(call(HttpMethod.POST, OPEN, who, openBody(type, id)), status, slug);
    }

    private String caseOf(Clinic clinic, String name, int level) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("identityUnknown", false);
        m.put("name", name);
        m.put("age", 40);
        m.put("sex", "F");
        m.put("mobile", null);
        m.put("complaint", "Chest pain for 1 hour");
        m.put("level", level);
        return ok(HttpMethod.POST, ER_CASES_URL, clinic.owner(), js(m), 201).get("actedCase").get("id").asText();
    }

    private static RequestPostProcessor anonymous() {
        return request -> request;
    }

    /** Every row of a query as text columns, read as sahaj_app with the clinic bound (rolled back). */
    private List<List<String>> table(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<List<String>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        List<String> line = new ArrayList<>();
                        for (int i = 1; i <= columns; i++) {
                            line.add(rs.getString(i));
                        }
                        out.add(line);
                    }
                }
            }
            return out;
        });
    }

    /** One statement as sahaj_app with the clinic bound, committed. */
    private void commitSql(Clinic clinic, String sql, Object... params) throws SQLException {
        try (java.sql.Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            DbAccess.execute(c, sql, params);
            c.commit();
        }
    }

    private long records(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from opd.emr_record");
    }

    // ---------------- an admitted patient ----------------

    @Test
    void aDoctorOpensTheDailyProgressNoteOfAnAdmissionWithTheEncounterFactsFromIpdAndNoOpdStageChanges() throws Exception {
        Clinic clinic = hospital();
        Adm admitted = admitOne(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        long stages = count(clinic, "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED'");
        JsonNode result = open(doctor.auth(), "IPD_ROUND", admitted.id(), 201);
        assertThat(result.get("created").asBoolean()).isTrue();
        assertThat(result.get("stageOutcome").asText()).isEqualTo("NOT_APPLICABLE");
        JsonNode record = result.get("record");
        assertThat(record.get("encounterType").asText()).isEqualTo("IPD_ROUND");
        assertThat(record.get("encounterId").asText()).isEqualTo(admitted.id());
        assertThat(record.get("roundDate").asText()).isEqualTo(LocalDate.now(IST).toString());
        assertThat(record.get("status").asText()).isEqualTo("DRAFT");
        assertThat(record.get("readOnly").asBoolean()).isFalse();
        JsonNode header = record.get("header");
        assertThat(header.get("patientName").asText()).isEqualTo(admitted.name());
        assertThat(header.get("patientId").asText()).isEqualTo(admitted.patientId().toString());
        assertThat(header.get("visitType").isNull()).as("the visit type control is OPD only").isTrue();
        assertThat(header.get("visitOrdinal").isNull()).isTrue();
        assertThat(header.get("emergency").isNull()).isTrue();
        JsonNode admission = header.get("admission");
        assertThat(admission.get("admissionId").asText()).isEqualTo(admitted.id());
        assertThat(admission.get("ipNumber").asText()).isNotBlank();
        assertThat(admission.get("dayNumber").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(admission.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(record.get("layout").get("departmentKey").asText()).isEqualTo("gm");
        assertThat(record.get("ipd").get("state").asText()).isEqualTo("TRIAL");
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED'")).as("no OPD stage changes").isEqualTo(stages);
        assertThat(values(clinic, "select encounter_type, ipd_admission_id::text, round_date::text, (opd_visit_id is null)::text, (er_case_id is null)::text, "
                + "patient_id::text from opd.emr_record where id = ?::uuid", record.get("id").asText()))
                .containsExactly("IPD_ROUND", admitted.id(), LocalDate.now(IST).toString(), "true", "true", admitted.patientId().toString());
        // the owner reads the same record; the audit entry holds ids only
        assertThat(ok(HttpMethod.GET, RECORDS + "/" + record.get("id").asText(), doctor.auth(), null, 200)).isEqualTo(record);
        List<List<String>> audits = table(clinic, "select after_json::text from platform.audit_event where action = 'EMR_RECORD_OPENED'");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(0)).contains(admitted.id()).doesNotContain(admitted.name());
    }

    @Test
    void openingAgainTheSameDayFindsTheSameRecordAndANewDayStartsANewOneWhileTheEarlierOneStays() throws Exception {
        Clinic clinic = hospital();
        Adm admitted = admitOne(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        // an earlier day's note, as it would exist after yesterday's round
        commitSql(clinic, "insert into opd.emr_record (encounter_type, ipd_admission_id, round_date, patient_id, opened_by_membership_id, "
                + "department_key, department_name, starter_key, layout_kind, starter_layout_version, choice_source, created_by, created_at) "
                + "values ('IPD_ROUND', ?::uuid, ?::date, ?::uuid, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 1, 'CLINIC_DEFAULT', ?, "
                + "now() - interval '1 day')", admitted.id(), LocalDate.now(IST).minusDays(1).toString(), admitted.patientId().toString(),
                doctor.membershipId(), doctor.userId());
        JsonNode today = open(doctor.auth(), "IPD_ROUND", admitted.id(), 201);
        assertThat(today.get("record").get("roundDate").asText()).isEqualTo(LocalDate.now(IST).toString());
        JsonNode again = open(doctor.auth(), "IPD_ROUND", admitted.id(), 200);
        assertThat(again.get("created").asBoolean()).isFalse();
        assertThat(again.get("record").get("id").asText()).isEqualTo(today.get("record").get("id").asText());
        assertThat(records(clinic)).as("yesterday's note and today's").isEqualTo(2);
        assertThat(count(clinic, "select count(*) from opd.emr_record where ipd_admission_id = ?::uuid and round_date = current_date - 1", admitted.id()))
                .isEqualTo(1);
    }

    @Test
    void fiveOpensAtTheSameMomentMakeOneRecordForTheAdmissionAndTheDay() throws Exception {
        Clinic clinic = hospital();
        Adm admitted = admitOne(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        int created = 0;
        List<String> ids = new ArrayList<>();
        try {
            List<Future<org.springframework.test.web.servlet.MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> call(HttpMethod.POST, OPEN, doctor.auth(), openBody("IPD_ROUND", admitted.id())).andReturn()));
            }
            for (Future<org.springframework.test.web.servlet.MvcResult> f : futures) {
                int status = f.get().getResponse().getStatus();
                assertThat(status).isIn(200, 201);
                created += status == 201 ? 1 : 0;
                ids.add(objectMapper.readTree(f.get().getResponse().getContentAsString()).get("record").get("id").asText());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(created).isEqualTo(1);
        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        assertThat(records(clinic)).isEqualTo(1);
    }

    @Test
    void savingADraftWorksForAnAdmissionAndAdmissionAdviceDoesNotApplyToIt() throws Exception {
        Clinic clinic = hospital();
        Adm admitted = admitOne(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        JsonNode record = open(doctor.auth(), "IPD_ROUND", admitted.id(), 201).get("record");
        String id = record.get("id").asText();
        String draft = "{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[{\"key\":\"hpi\",\"values\":{\"narrative\":\"Better today\"}}]}";
        // OQ-43-27 answered (ADR-025 D5): an admission's note is saved like an OPD visit's
        JsonNode saved = ok(HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.auth(), draft, 200);
        assertThat(saved.get("revision").asLong()).isEqualTo(1);
        assertThat(saved.get("status").asText()).isEqualTo("DRAFT");
        refused(call(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", doctor.auth(), "{\"revision\":1}"), 400, "not-applicable");
        assertThat(values(clinic, "select revision::text, admission_advised::text, (sections::text like '%Better today%')::text from opd.emr_record where id = ?::uuid", id))
                .containsExactly("1", "false", "true");
        assertThat(ok(HttpMethod.GET, RECORDS + "/" + id, doctor.auth(), null, 200).get("header").get("patientName").asText()).isEqualTo(admitted.name());
    }

    // ---------------- who may open ----------------

    @Test
    void anOwnerWithDoctorDetailsAndADoctorOpenAnyAdmissionAndNurseVisitingDoctorAndTheOtherRolesAre403Forbidden() throws Exception {
        Clinic clinic = hospital(false);
        Adm admitted = admitOne(clinic);
        String erCase = caseOf(clinic, "Dev Patil", 3); // a real case: a made-up id is a 404 before the role rule
        for (String role : new String[] {"NURSE", "VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            Pn person = newPn(clinic, role);
            JsonNode p = refusedOpen(person.auth(), "IPD_ROUND", admitted.id(), 403, "forbidden");
            assertThat(p.get("type").asText()).doesNotEndWith("outside-scope");
            refusedOpen(person.auth(), "ER_ASSESSMENT", erCase, 403, "forbidden");
        }
        refused(call(HttpMethod.POST, OPEN, anonymous(), openBody("IPD_ROUND", admitted.id())), 401, "unauthorized");
        assertThat(records(clinic)).isZero();
        // the owner without doctor details is told to add them; with details the owner opens
        refusedOpen(clinic.owner(), "IPD_ROUND", admitted.id(), 403, "doctor-details-missing");
        commitSql(clinic, "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, registration_no) "
                + "select id, 'General physician', 0, 'MBBS', 'REG-OWN-1' from platform.membership where role = 'OWNER' "
                + "on conflict (membership_id) do update set degree = 'MBBS', registration_no = 'REG-OWN-1'");
        assertThat(open(clinic.owner(), "IPD_ROUND", admitted.id(), 201).get("record").get("ipd").get("canSwitchOn").asBoolean()).isTrue();
        // a doctor who is not the admitting doctor opens too (the IPD scope of a doctor is every patient)
        Pn other = newPn(clinic, "DOCTOR");
        JsonNode second = open(other.auth(), "IPD_ROUND", admitted.id(), 200);
        assertThat(second.get("created").asBoolean()).isFalse();
        assertThat(second.get("record").get("ipd").get("canSwitchOn").asBoolean()).isFalse();
        // the record cannot be read by the roles that cannot open
        String id = second.get("record").get("id").asText();
        for (String role : new String[] {"NURSE", "VISITING_DOCTOR", "BILLING"}) {
            refused(call(HttpMethod.GET, RECORDS + "/" + id, newPn(clinic, role).auth(), null), 403, "forbidden");
        }
    }

    @Test
    void withWardScopeOnADoctorStillOpensAnyAdmissionAndANursesBreakGlassGrantOpensNothingInTheEmrAndIsLeftAsItWas() throws Exception {
        Hosp h = hosp();
        Pn doctor = newPn(h.clinic(), "DOCTOR");
        // ward scope is on: the nurse of ward A has no access to the patient of ward B in IPD, the doctor has
        refusedOpen(h.nurseA().auth(), "IPD_ROUND", h.inB().id(), 403, "forbidden");
        assertThat(open(doctor.auth(), "IPD_ROUND", h.inB().id(), 201).get("record").get("header").get("patientName").asText()).isEqualTo(h.inB().name());
        assertThat(open(doctor.auth(), "IPD_ROUND", h.inA().id(), 201).get("record").get("header").get("patientName").asText()).isEqualTo(h.inA().name());
        // the nurse opens a break-glass grant for the patient of ward B (the IPD path audits it); the grant gives the EMR nothing
        long grantsBefore = grantRows(h.clinic());
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        assertThat(grantRows(h.clinic())).isEqualTo(grantsBefore + 1);
        String grant = grantOf(h.clinic(), h.nurseA(), h.inB().id());
        List<String> state = values(h.clinic(), "select reviewed_by is null, expires_at::text, opened_at::text from ipd.emergency_access_grant where id = ?::uuid", grant);
        refusedOpen(h.nurseA().auth(), "IPD_ROUND", h.inB().id(), 403, "forbidden");
        refusedOpen(h.nurseA().auth(), "IPD_ROUND", h.inA().id(), 403, "forbidden");
        assertThat(grantRows(h.clinic())).as("the EMR neither opens, uses nor closes a grant").isEqualTo(grantsBefore + 1);
        assertThat(values(h.clinic(), "select reviewed_by is null, expires_at::text, opened_at::text from ipd.emergency_access_grant where id = ?::uuid", grant))
                .isEqualTo(state);
        // the doctor's EMR record of ward B is not readable by the nurse with the grant either
        String id = values(h.clinic(), "select id::text from opd.emr_record where ipd_admission_id = ?::uuid", h.inB().id()).get(0);
        refused(call(HttpMethod.GET, RECORDS + "/" + id, h.nurseA().auth(), null), 403, "forbidden");
    }

    @Test
    void anotherClinicsAdmissionOrCaseIs404AndAnIpdThatIsOffIs403ModuleOff() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Adm inA = admitOne(a);
        String caseA = caseOf(a, "Asha Patil", 3);
        Pn doctorB = newPn(b, "DOCTOR");
        refusedOpen(doctorB.auth(), "IPD_ROUND", inA.id(), 404, "encounter-not-found");
        refusedOpen(doctorB.auth(), "ER_ASSESSMENT", caseA, 404, "encounter-not-found");
        refusedOpen(doctorB.auth(), "IPD_ROUND", UUID.randomUUID(), 404, "encounter-not-found");
        // an OPD visit id is not an admission id
        refusedOpen(newPn(a, "DOCTOR").auth(), "IPD_ROUND", UUID.randomUUID(), 404, "encounter-not-found");
        assertThat(records(a) + records(b)).isZero();
        // a clinic with IPD switched off
        Clinic off = newClinic();
        Pn doctorOff = newPn(off, "DOCTOR");
        JsonNode problem = refusedOpen(doctorOff.auth(), "IPD_ROUND", UUID.randomUUID(), 403, "module-off");
        assertThat(problem.get("detail").asText()).isEqualTo("In-patients (IPD) is not switched on for your clinic.");
        refusedOpen(doctorOff.auth(), "ER_ASSESSMENT", UUID.randomUUID(), 403, "module-off");
        assertThat(records(off)).isZero();
    }

    // ---------------- a discharged admission ----------------

    @Test
    void aDischargedAdmissionOpensReadOnlyWhenARecordExistsAndIs409EncounterClosedWhenNoneDoes() throws Exception {
        Clinic clinic = hospital();
        Adm withNote = admitOne(clinic);
        Adm without = admitOne(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        JsonNode record = open(doctor.auth(), "IPD_ROUND", withNote.id(), 201).get("record");
        signAndDischarge(clinic, withNote.id());
        signAndDischarge(clinic, without.id());
        JsonNode reopened = open(doctor.auth(), "IPD_ROUND", withNote.id(), 200);
        assertThat(reopened.get("created").asBoolean()).isFalse();
        assertThat(reopened.get("record").get("id").asText()).isEqualTo(record.get("id").asText());
        assertThat(reopened.get("record").get("readOnly").asBoolean()).isTrue();
        assertThat(reopened.get("record").get("readOnlyReason").asText()).isEqualTo("ENCOUNTER_CLOSED");
        assertThat(reopened.get("record").get("departmentOptions").size()).isZero();
        assertThat(reopened.get("record").get("header").get("admission").get("status").asText()).isEqualTo("DISCHARGED");
        JsonNode p = refusedOpen(doctor.auth(), "IPD_ROUND", without.id(), 409, "encounter-closed");
        assertThat(p.get("detail").asText()).isEqualTo("This admission or emergency case is closed and has no EMR to open.");
        assertThat(records(clinic)).isEqualTo(1);
        refused(call(HttpMethod.PUT, RECORDS + "/" + record.get("id").asText() + "/department", doctor.auth(), "{\"departmentKey\":\"dental\",\"revision\":0}"),
                409, "encounter-closed");
    }

    // ---------------- an Emergency case ----------------

    @Test
    void aDoctorOpensTheEmergencyAssessmentOfACaseWithTheTriageLevelFromIpdAndNoPatientRecordYet() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Asha Patil", 3);
        Pn doctor = newPn(clinic, "DOCTOR");
        JsonNode result = open(doctor.auth(), "ER_ASSESSMENT", caseId, 201);
        assertThat(result.get("stageOutcome").asText()).isEqualTo("NOT_APPLICABLE");
        JsonNode record = result.get("record");
        assertThat(record.get("encounterType").asText()).isEqualTo("ER_ASSESSMENT");
        assertThat(record.get("encounterId").asText()).isEqualTo(caseId);
        assertThat(record.get("roundDate").isNull()).isTrue();
        JsonNode header = record.get("header");
        assertThat(header.get("patientId").isNull()).as("an Emergency case has no patient record until it is admitted").isTrue();
        assertThat(header.get("patientName").asText()).isEqualTo("Asha Patil");
        assertThat(header.get("age").asInt()).isEqualTo(40);
        assertThat(header.get("sex").asText()).isEqualTo("F");
        assertThat(header.get("admission").isNull()).isTrue();
        JsonNode emergency = header.get("emergency");
        assertThat(emergency.get("caseId").asText()).isEqualTo(caseId);
        assertThat(emergency.get("triageLevel").asInt()).isEqualTo(3);
        assertThat(emergency.get("triageLevelName").asText()).isNotBlank();
        assertThat(emergency.get("status").asText()).isEqualTo("WAITING");
        assertThat(values(clinic, "select encounter_type, er_case_id::text, (patient_id is null)::text, (opd_visit_id is null)::text, "
                + "(ipd_admission_id is null)::text, (round_date is null)::text from opd.emr_record where id = ?::uuid", record.get("id").asText()))
                .containsExactly("ER_ASSESSMENT", caseId, "true", "true", "true", "true");
        // one record per case, also when opened again
        JsonNode again = open(doctor.auth(), "ER_ASSESSMENT", caseId, 200);
        assertThat(again.get("record").get("id").asText()).isEqualTo(record.get("id").asText());
        assertThat(records(clinic)).isEqualTo(1);
        // a draft can be saved (OQ-43-27 answered); the patient name is not in any audit entry
        JsonNode savedCase = ok(HttpMethod.PUT, RECORDS + "/" + record.get("id").asText() + "/draft", doctor.auth(),
                "{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[{\"key\":\"hpi\",\"values\":{\"narrative\":\"Chest pain since morning\"}}]}", 200);
        assertThat(savedCase.get("revision").asLong()).isEqualTo(1);
        for (List<String> audit : table(clinic, "select coalesce(before_json::text, '') || coalesce(after_json::text, '') from platform.audit_event where action like 'EMR\\_%'")) {
            assertThat(audit.get(0)).doesNotContain("Asha").doesNotContain("Patil");
        }
        // Admit to IPD advice works for an Emergency case and not for an admission
        JsonNode advised = ok(HttpMethod.POST, RECORDS + "/" + record.get("id").asText() + "/admission-advice", doctor.auth(),
                "{\"revision\":1,\"referralLine\":\"Needs a ventilator bed\"}", 200);
        assertThat(advised.get("admissionAdvised").asBoolean()).isTrue();
        assertThat(advised.get("referralLine").asText()).isEqualTo("Needs a ventilator bed");
    }

    @Test
    void fiveOpensOfTheSameCaseAtTheSameMomentMakeOneRecord() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Bhim Kale", 2);
        Pn doctor = newPn(clinic, "DOCTOR");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> call(HttpMethod.POST, OPEN, doctor.auth(), openBody("ER_ASSESSMENT", caseId)).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(records(clinic)).isEqualTo(1);
    }

    @Test
    void aClosedCaseOpensReadOnlyWhenItHasARecordAndIs409WhenItHasNone() throws Exception {
        Clinic clinic = hospital();
        String withNote = caseOf(clinic, "Chitra Rao", 3);
        String without = caseOf(clinic, "Dev Shah", 3);
        Pn doctor = newPn(clinic, "DOCTOR");
        String id = open(doctor.auth(), "ER_ASSESSMENT", withNote, 201).get("record").get("id").asText();
        ok(HttpMethod.POST, ER_CASES_URL + "/" + withNote + "/release", clinic.owner(), null, 200);
        ok(HttpMethod.POST, ER_CASES_URL + "/" + without + "/release", clinic.owner(), null, 200);
        JsonNode reopened = open(doctor.auth(), "ER_ASSESSMENT", withNote, 200);
        assertThat(reopened.get("record").get("id").asText()).isEqualTo(id);
        assertThat(reopened.get("record").get("readOnly").asBoolean()).isTrue();
        assertThat(reopened.get("record").get("readOnlyReason").asText()).isEqualTo("ENCOUNTER_CLOSED");
        refusedOpen(doctor.auth(), "ER_ASSESSMENT", without, 409, "encounter-closed");
        refused(call(HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", doctor.auth(), "{\"revision\":0}"), 409, "encounter-closed");
        assertThat(records(clinic)).isEqualTo(1);
    }

    @Test
    void noLogLineOrAuditEntryOfAnIpdOrEmergencyOpenHoldsThePatientsName() throws Exception {
        Clinic clinic = hospital();
        Adm admitted = admitOne(clinic);
        String caseId = caseOf(clinic, "Zxqpatient Emergencyname", 3);
        Pn doctor = newPn(clinic, "DOCTOR");
        try (com.sahaj.platform.support.LogCapture capture = new com.sahaj.platform.support.LogCapture()) {
            open(doctor.auth(), "IPD_ROUND", admitted.id(), 201);
            open(doctor.auth(), "ER_ASSESSMENT", caseId, 201);
            open(doctor.auth(), "ER_ASSESSMENT", caseId, 200);
            refusedOpen(newPn(clinic, "NURSE").auth(), "IPD_ROUND", admitted.id(), 403, "forbidden");
            StringBuilder logs = new StringBuilder();
            capture.events().stream().filter(e -> !e.getLoggerName().startsWith("org.springframework.test."))
                    .forEach(e -> logs.append(e.getFormattedMessage()).append(e.getMDCPropertyMap()).append('\n'));
            assertThat(logs.toString()).contains("/api/v1/opd/emr").doesNotContain("Zxqpatient").doesNotContain("Emergencyname")
                    .doesNotContain(admitted.name()).doesNotContain(admitted.mobile());
        }
        for (List<String> audit : table(clinic, "select coalesce(before_json::text, '') || coalesce(after_json::text, '') from platform.audit_event where action like 'EMR\\_%'")) {
            assertThat(audit.get(0)).doesNotContain("Zxqpatient").doesNotContain(admitted.name());
        }
    }
}
