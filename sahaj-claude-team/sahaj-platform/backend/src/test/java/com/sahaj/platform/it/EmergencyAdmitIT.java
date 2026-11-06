package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-28 the Admit hand-off on the real {@code sahaj_test} database: {@code GET /admission-options?emergencyCaseId=}
 * with the suggested bed, {@code POST /admissions} with {@code emergencyCaseId} (source EMERGENCY, the case ADMITTED
 * with the admission and patient linked in one transaction, the bay freed, the two audit rows), a case closed
 * meanwhile (409 case-closed), a failed admit that leaves the case in the queue, the tile numbers of
 * {@code GET /admissions}, and the menu key with the badge data. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyAdmitIT extends EmergencySupport {

    private static final String ME = "/api/v1/platform/me";

    private List<JsonNode> offered(Clinic clinic) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds").forEach(out::add);
        return out;
    }

    private String firstOffered(Clinic clinic, String category) throws Exception {
        for (JsonNode b : offered(clinic)) {
            if (category == null || category.equals(b.get("categoryKey").asText())) {
                return b.get("id").asText();
            }
        }
        return null;
    }

    /** Occupies a free bed with a new patient (an emergency admission without a deposit, so any category works). */
    private void fill(Clinic clinic, String bedId) throws Exception {
        Map<String, Object> m = admitBody(clinic, newPatient(clinic), UUID.fromString(bedId), null);
        m.put("admissionType", "emergency");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        admitOk(clinic, m);
    }

    private JsonNode suggestion(Clinic clinic, Object caseId) throws Exception {
        return optionsFor(clinic, "OWNER", caseId).get("emergencyCase").get("suggestedBedId");
    }

    private List<String> menu(Clinic clinic, String role) throws Exception {
        List<String> items = new ArrayList<>();
        ok(HttpMethod.GET, ME, clinic.as(role), null, 200).get("menu").forEach(n -> items.add(n.asText()));
        return items;
    }

    // ---------------- the hand-off data ----------------

    @Test
    void theOptionsWithACaseCarryTheHandOffDataAndWithoutOneTheyDoNotAndReadingChangesNothing() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        Map<String, Object> body = caseBody("Asha Patil", 2);
        body.put("mobile", "9876543210");
        body.put("complaint", "Chest pain for 1 hour");
        String id = register(clinic, body).get("actedCase").get("id").asText();
        String unknown = register(clinic, unknownBody("M", 40, 3)).get("actedCase").get("id").asText();
        Written before = written(clinic);

        JsonNode options = optionsFor(clinic, "OWNER", id);
        JsonNode h = options.get("emergencyCase");
        assertThat(h.get("caseId").asText()).isEqualTo(id);
        assertThat(h.get("displayName").asText()).isEqualTo("Asha Patil");
        assertThat(h.get("age").asInt()).isEqualTo(40);
        assertThat(h.get("sex").asText()).isEqualTo("F");
        assertThat(h.get("mobile").asText()).isEqualTo("9876543210");
        assertThat(h.get("complaint").asText()).isEqualTo("Chest pain for 1 hour");
        assertThat(h.get("identityPending").asBoolean()).isFalse();
        assertThat(h.get("level").asInt()).isEqualTo(2);
        assertThat(h.get("levelName").asText()).isEqualTo("Yellow");
        assertThat(h.get("suggestedBedId").asText()).isEqualTo(bed.toString());
        assertThat(options.get("beds")).hasSize(1);
        assertThat(options.get("doctors")).isNotEmpty();
        assertThat(options.has("rules")).isTrue();

        // an unknown patient hands over the placeholder name, the age and the sex and no mobile
        JsonNode u = optionsFor(clinic, "NURSE", unknown).get("emergencyCase");
        assertThat(u.get("identityPending").asBoolean()).isTrue();
        assertThat(u.get("displayName").asText()).isEqualTo("Unknown male, about 40");
        assertThat(u.get("mobile").isNull()).isTrue();

        // without the id the answer is the one of SAH-23
        JsonNode plain = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        assertThat(plain.path("emergencyCase").isMissingNode() || plain.get("emergencyCase").isNull()).isTrue();
        // security review M3: each 200 answer of the hand-off writes exactly one audit row IPD_ER_HANDOFF_OPENED (two cases
        // were opened above, the plain read opened none); no case, event, admission, bay or record-access row changes
        Written after = written(clinic);
        assertThat(after.cases()).as("cases").isEqualTo(before.cases());
        assertThat(after.events()).as("events").isEqualTo(before.events());
        assertThat(after.admissions()).as("admissions").isEqualTo(before.admissions());
        assertThat(after.dump()).as("every case column (status, level, bay, version, times, links)").isEqualTo(before.dump());
        assertThat(after.access()).as("no patient record-access row").isEqualTo(before.access());
        assertThat(after.erAudits()).as("one audit row per 200 hand-off").isEqualTo(before.erAudits() + 2);
        assertThat(after.audits()).as("no other audit row").isEqualTo(before.audits() + 2);
        List<String[]> opened = audit(clinic, "IPD_ER_HANDOFF_OPENED");
        assertThat(opened).hasSize(2);
        assertThat(opened.stream().map(r -> r[1]).toList()).containsExactlyInAnyOrder(id, unknown);
        for (String[] row : opened) {
            assertThat(row[0]).isEqualTo("IPD_EMERGENCY_CASE");
            assertThat(row[2]).as("no before data").isNull();
            JsonNode data = objectMapper.readTree(row[3]);
            assertThat(data.size()).as("after data holds the case id only").isOne();
            assertThat(data.get("caseId").asText()).isEqualTo(row[1]);
            assertThat(row[4]).as("the actor is the signed-in user").isEqualTo(clinic.ownerUserId().toString());
        }
    }

    @Test
    void theHandOffIsForTheAdmitRolesOnlyAndADoctorIsRefusedWithAndWithoutACase() throws Exception {
        Clinic clinic = hospital();
        newBed(clinic, "gen");
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        for (String role : ALL_ROLES) {
            if (in(role, ADMITTERS)) {
                assertThat(optionsFor(clinic, role, id).get("emergencyCase").get("caseId").asText()).isEqualTo(id);
            } else {
                assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, clinic.as(role), null), 403), "forbidden");
            }
        }
        assertType(problem(call(HttpMethod.GET, OPTIONS, clinic.as("DOCTOR"), null), 403), "forbidden");   // the sheet data is for the admit roles (SAH-23)
        // unknown case: 404; closed case: 409 case-closed
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + UUID.randomUUID(), clinic.owner(), null), 404), "not-found");
        release(clinic, id);
        assertCaseClosed(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, clinic.owner(), null), 409));
    }

    private static boolean in(String role, String[] roles) {
        return java.util.Arrays.asList(roles).contains(role);
    }

    @Test
    void theOptionsWithACaseAnswerModuleOffBeforeTheCaseAndAreRefusedFor400OnABadId() throws Exception {
        Clinic off = newClinic();
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + UUID.randomUUID(), off.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + UUID.randomUUID(), off.as("DOCTOR"), null), 403), "forbidden");
        Clinic clinic = hospital();
        int status = call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=not-a-uuid", clinic.owner(), null).andReturn().getResponse().getStatus();
        assertThat(status).isBetween(400, 404);
    }

    // ---------------- the suggested bed ----------------

    @Test
    void theSuggestedBedIsTheFirstFreeIcuBedForLevel1OtherwiseTheFirstFreeGeneralBedAndFallsBackToAnyBed() throws Exception {
        Clinic clinic = hospital();
        newBed(clinic, "semi");
        newBed(clinic, "icu");
        newBed(clinic, "gen");
        newBed(clinic, "icu");
        newBed(clinic, "gen");
        newBed(clinic, "pvt");
        String red = reg(clinic, "Red", 1).get("id").asText();
        String yellow = reg(clinic, "Yellow", 2).get("id").asText();
        String green = reg(clinic, "Green", 3).get("id").asText();

        String firstIcu = firstOffered(clinic, "icu");
        String firstGeneral = firstOffered(clinic, "gen");
        assertThat(firstIcu).isNotNull();
        assertThat(firstGeneral).isNotNull();
        assertThat(suggestion(clinic, red).asText()).as("level 1: first ICU bed").isEqualTo(firstIcu);
        assertThat(suggestion(clinic, yellow).asText()).as("level 2: first General bed").isEqualTo(firstGeneral);
        assertThat(suggestion(clinic, green).asText()).as("level 3: first General bed").isEqualTo(firstGeneral);

        // the five-level scale: only Level 1 is the most urgent, Level 2 gets a General bed
        Clinic five = hospital();
        newBed(five, "icu");
        newBed(five, "gen");
        setScale(five, 5);
        String l1 = reg(five, "Level 1", 1).get("id").asText();
        String l2 = reg(five, "Level 2", 2).get("id").asText();
        assertThat(suggestion(five, l1).asText()).isEqualTo(firstOffered(five, "icu"));
        assertThat(suggestion(five, l2).asText()).isEqualTo(firstOffered(five, "gen"));

        // an occupied bed is never suggested: fill both ICU beds, the next most urgent patient is offered the first bed of any kind
        fill(clinic, firstIcu);
        assertThat(suggestion(clinic, red).asText()).as("the other ICU bed").isEqualTo(firstOffered(clinic, "icu"));
        fill(clinic, firstOffered(clinic, "icu"));
        assertThat(firstOffered(clinic, "icu")).isNull();
        String anyBed = firstOffered(clinic, null);
        assertThat(suggestion(clinic, red).asText()).as("no ICU bed free: the first free bed of any category").isEqualTo(anyBed);

        // no General bed free either way: fill the General beds, the first free bed of any category is offered to level 2
        while (firstOffered(clinic, "gen") != null) {
            fill(clinic, firstOffered(clinic, "gen"));
        }
        String remaining = firstOffered(clinic, null);
        assertThat(remaining).isNotNull();
        assertThat(suggestion(clinic, yellow).asText()).as("no General bed free").isEqualTo(remaining);

        // no free bed at all: null
        while (firstOffered(clinic, null) != null) {
            fill(clinic, firstOffered(clinic, null));
        }
        assertThat(suggestion(clinic, red).isNull()).isTrue();
        assertThat(suggestion(clinic, green).isNull()).isTrue();
        assertThat(optionsFor(clinic, "OWNER", yellow).get("emergencyCase").get("suggestedBedId").isNull()).isTrue();

        // a clinic with no beds at all
        Clinic bare = hospital();
        assertThat(suggestion(bare, reg(bare, "No beds", 1).get("id").asText()).isNull()).isTrue();
    }

    @Test
    void aBedThatIsNotAvailableIsNeverSuggested() throws Exception {
        Clinic clinic = hospital();
        UUID cleaning = newBed(clinic, "icu");
        UUID blocked = newBed(clinic, "gen");
        UUID free = newBed(clinic, "gen");
        setBedStatus(clinic, cleaning, "clean");
        setBedStatus(clinic, blocked, "blk");
        String red = reg(clinic, "Red", 1).get("id").asText();
        assertThat(suggestion(clinic, red).asText()).as("neither the bed being cleaned nor the blocked one").isEqualTo(free.toString());
        setBedStatus(clinic, free, "res");
        assertThat(suggestion(clinic, red).isNull()).isTrue();
    }

    // ---------------- the admit link ----------------

    @Test
    void anAdmissionFromACaseHasSourceEmergencyAndTheCaseBecomesAdmittedWithBothLinksAndTheBayIsFreed() throws Exception {
        Clinic clinic = hospital();
        setBays(clinic, 1);
        UUID bed = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        String id = reg(clinic, "Asha Patil", 1).get("id").asText();
        assertThat(col(clinic, id, "bay")).isEqualTo("ER-1");
        assertThat(queue(clinic).get("baysFree").asInt()).isZero();
        seen(clinic, id);

        Map<String, Object> m = admitBody(clinic, patient, bed, id);
        JsonNode admission = ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(m), 201);
        String admissionId = admission.get("id").asText();
        assertThat(admission.get("admissionType").asText()).as("the sheet only preselects Emergency").isEqualTo("planned");
        assertThat(sourceOf(clinic, admissionId)).isEqualTo("EMERGENCY");

        assertThat(col(clinic, id, "status")).isEqualTo("ADMITTED");
        assertThat(col(clinic, id, "admission_id")).isEqualTo(admissionId);
        assertThat(col(clinic, id, "patient_id")).isEqualTo(patient.toString());
        assertThat(col(clinic, id, "closed_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(col(clinic, id, "closed_at")).isNotNull();
        assertThat(col(clinic, id, "seen_at")).as("a seen case keeps its seen time").isNotNull();
        assertThat(col(clinic, id, "version")).isEqualTo("2");
        assertThat(bedStatus(clinic, bed)).isEqualTo("occ");
        assertThat(col(clinic, id, "bay")).as("kept on the closed row as history").isEqualTo("ER-1");

        JsonNode q = queue(clinic);
        assertThat(q.get("cases")).isEmpty();
        assertThat(q.get("baysFree").asInt()).as("the bay is free again").isOne();
        assertThat(reg(clinic, "Next red", 1).get("bay").asText()).isEqualTo("ER-1");

        // both audit rows: ids, level, bay and time only
        List<String[]> admitted = audit(clinic, "IPD_ADMITTED");
        assertThat(admitted).hasSize(1);
        assertThat(objectMapper.readTree(admitted.get(0)[3]).get("source").asText()).isEqualTo("EMERGENCY");
        List<String[]> link = audit(clinic, "IPD_ER_ADMITTED");
        assertThat(link).hasSize(1);
        assertThat(link.get(0)[0]).isEqualTo("IPD_EMERGENCY_CASE");
        assertThat(link.get(0)[1]).isEqualTo(id);
        JsonNode after = objectMapper.readTree(link.get(0)[3]);
        assertThat(after.get("caseId").asText()).isEqualTo(id);
        assertThat(after.get("admissionId").asText()).isEqualTo(admissionId);
        assertThat(after.get("level").asInt()).isOne();
        assertThat(after.get("bay").asText()).isEqualTo("ER-1");
        assertThat(after.get("closedAt").asText()).isNotBlank();
        assertThat(after.fieldNames()).toIterable().containsExactlyInAnyOrder("caseId", "admissionId", "level", "bay", "closedAt");

        // the admitted patient is on the list; an ordinary admission has source DIRECT
        assertThat(row(list(clinic), admissionId).get("id").asText()).isEqualTo(admissionId);
        String direct = admitOk(clinic, admitBody(clinic, newPatient(clinic), newBed(clinic, "gen"), null)).get("id").asText();
        assertThat(sourceOf(clinic, direct)).isEqualTo("DIRECT");
        Map<String, Object> explicitNull = admitBody(clinic, newPatient(clinic), newBed(clinic, "gen"), null);
        explicitNull.put("emergencyCaseId", null);
        assertThat(sourceOf(clinic, ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(explicitNull), 201).get("id").asText()))
                .isEqualTo("DIRECT");
    }

    @Test
    void anUnknownPatientCaseIsAdmittedWithTheChosenPatientAndKeepsTheTypedIdentityOnTheCase() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        String id = register(clinic, unknownBody("F", 30, 2)).get("actedCase").get("id").asText();
        JsonNode admission = ok(HttpMethod.POST, ADMISSIONS, clinic.as("NURSE"), js(admitBody(clinic, patient, bed, id)), 201);
        assertThat(sourceOf(clinic, admission.get("id").asText())).isEqualTo("EMERGENCY");
        assertThat(col(clinic, id, "status")).isEqualTo("ADMITTED");
        assertThat(col(clinic, id, "patient_id")).isEqualTo(patient.toString());
        assertThat(col(clinic, id, "display_name")).isEqualTo("Unknown female, about 30");
        assertThat(col(clinic, id, "closed_by")).isEqualTo(clinic.ownerUserId().toString());
    }

    @Test
    void theAdmitRolesAdmitFromACaseAndTheOthersAreRefusedWithTheCaseStillInTheQueue() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        for (String role : ALL_ROLES) {
            if (in(role, ADMITTERS)) {
                continue;
            }
            Written before = written(clinic);
            assertType(problem(admitCall(clinic.as(role), admitBody(clinic, newPatient(clinic), newBed(clinic, "gen"), id)), 403), "forbidden");
            assertThat(col(clinic, id, "status")).as(role).isEqualTo("WAITING");
            assertThat(written(clinic).dump()).isEqualTo(before.dump());
        }
        assertThat(rows(clinic, "admission")).isZero();
        for (String role : ADMITTERS) {
            String own = reg(clinic, "Case of " + role, 2).get("id").asText();
            JsonNode a = ok(HttpMethod.POST, ADMISSIONS, clinic.as(role), js(admitBody(clinic, newPatient(clinic), newBed(clinic, "gen"), own)), 201);
            assertThat(sourceOf(clinic, a.get("id").asText())).as(role).isEqualTo("EMERGENCY");
            assertThat(col(clinic, own, "status")).isEqualTo("ADMITTED");
        }
    }

    // ---------------- a case closed meanwhile, a failed admit ----------------

    @Test
    void aCaseThatWasClosedMeanwhileAnswers409CaseClosedBeforeThePatientIsCheckedAndNothingIsAdmitted() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        String released = reg(clinic, "Released", 2).get("id").asText();
        String referred = reg(clinic, "Referred", 2).get("id").asText();
        String admitted = reg(clinic, "Admitted", 2).get("id").asText();
        release(clinic, released);
        refer(clinic, referred);
        ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, patient, bed, admitted)), 201);
        UUID otherBed = newBed(clinic, "gen");
        UUID otherPatient = newPatient(clinic);
        Written before = written(clinic);

        for (String id : new String[] {released, referred, admitted}) {
            assertCaseClosed(problem(admitCall(clinic.owner(), admitBody(clinic, otherPatient, otherBed, id)), 409));
            // the case is checked before the patient: a patient that does not exist does not change the answer
            assertCaseClosed(problem(admitCall(clinic.owner(), admitBody(clinic, UUID.randomUUID(), otherBed, id)), 409));
            // and before "already admitted": the patient of the first admission
            assertCaseClosed(problem(admitCall(clinic.owner(), admitBody(clinic, patient, otherBed, id)), 409));
        }
        assertThat(written(clinic)).isEqualTo(before);
        assertThat(bedStatus(clinic, otherBed)).isEqualTo("free");
        assertThat(rows(clinic, "admission")).isOne();
        // an unknown case is a 404, also before the patient and the bed
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, patient, otherBed, UUID.randomUUID())), 404), "not-found");
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())), 404), "not-found");
    }

    @Test
    void aFailedAdmitLeavesTheCaseInTheQueueWithItsBayAndNothingElseIsWritten() throws Exception {
        Clinic clinic = hospital();
        setBays(clinic, 1);
        UUID bed = newBed(clinic, "gen");
        UUID taken = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        UUID admittedPatient = newPatient(clinic);
        ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, admittedPatient, taken, null)), 201);
        String id = reg(clinic, "Asha Patil", 1).get("id").asText();
        Written before = written(clinic);
        long ipNumbers = count(clinic, "select coalesce(max(last_no), 0) from ipd.ip_sequence");

        // the bed is occupied
        JsonNode full = problem(admitCall(clinic.owner(), admitBody(clinic, patient, taken, id)), 409);
        assertType(full, "bed-not-free");
        assertThat(full.get("detail").asText()).isEqualTo(BED_TAKEN);
        // the patient is in a bed already
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, admittedPatient, bed, id)), 409), "already-admitted");
        // the patient does not exist
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, UUID.randomUUID(), bed, id)), 404), "not-found");
        // the consent is missing, the deposit is too low, the doctor is wrong
        Map<String, Object> noConsent = admitBody(clinic, patient, bed, id);
        noConsent.put("consentSigned", false);
        assertFieldError(problem(admitCall(clinic.owner(), noConsent), 400), "consentSigned");
        Map<String, Object> lowDeposit = admitBody(clinic, patient, bed, id);
        lowDeposit.put("depositPaise", 100_000);
        assertFieldError(problem(admitCall(clinic.owner(), lowDeposit), 400), "depositPaise");
        Map<String, Object> noDoctor = admitBody(clinic, patient, bed, id);
        noDoctor.put("doctorMembershipId", UUID.randomUUID().toString());
        assertFieldError(problem(admitCall(clinic.owner(), noDoctor), 400), "doctorMembershipId");

        assertThat(written(clinic)).as("every refusal left the case, the bed and the audit trail as they were").isEqualTo(before);
        assertThat(count(clinic, "select coalesce(max(last_no), 0) from ipd.ip_sequence")).isEqualTo(ipNumbers);
        assertThat(col(clinic, id, "status")).isEqualTo("WAITING");
        assertThat(col(clinic, id, "bay")).isEqualTo("ER-1");
        assertThat(col(clinic, id, "admission_id")).isNull();
        assertThat(col(clinic, id, "patient_id")).isNull();
        assertThat(ids(queue(clinic))).containsExactly(id);
        assertThat(queue(clinic).get("baysFree").asInt()).isZero();
        assertThat(bedStatus(clinic, bed)).isEqualTo("free");

        // and the admission can still be saved afterwards
        JsonNode ok = ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, patient, bed, id)), 201);
        assertThat(sourceOf(clinic, ok.get("id").asText())).isEqualTo("EMERGENCY");
        assertThat(col(clinic, id, "status")).isEqualTo("ADMITTED");
    }

    @Test
    void readOnlyRefusesTheAdmitFromACaseAndTheCaseStays() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        expireTrial(clinic);
        Written before = written(clinic);
        assertType(problem(admitCall(clinic.owner(), admitBody(clinic, patient, bed, id)), 409), "ipd-readonly");
        assertThat(written(clinic)).isEqualTo(before);
        assertThat(col(clinic, id, "status")).isEqualTo("WAITING");
    }

    // ---------------- tile numbers and the menu ----------------

    @Test
    void theAdmissionListAlwaysHasAnEmergencyObjectForTheTileAndTheTabAndItCountsTheQueueAndTheOverTargetCases() throws Exception {
        Clinic clinic = hospital();
        JsonNode empty = list(clinic).get("emergency");
        assertThat(empty.get("writable").asBoolean()).isTrue();
        assertThat(empty.get("queue").asInt()).isZero();
        assertThat(empty.get("overTarget").asInt()).isZero();
        for (String role : ER_ROLES) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200).get("emergency").get("queue").asInt()).as(role).isZero();
        }
        regWaited(clinic, "Late red", 1, 30);
        regWaited(clinic, "Late green", 3, 130);
        String seenLate = regWaited(clinic, "Seen late", 1, 90).get("id").asText();
        seen(clinic, seenLate);
        reg(clinic, "Fresh yellow", 2);
        JsonNode e = list(clinic).get("emergency");
        assertThat(e.get("queue").asInt()).isEqualTo(4);
        assertThat(e.get("overTarget").asInt()).as("a seen case is never over target").isEqualTo(2);
        assertThat(e).isEqualTo(queue(clinic).get("counts"));
        assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.as("DOCTOR"), null, 200).get("emergency")).isEqualTo(e);

        // the tile of Beds occupied and the other numbers are not disturbed
        assertThat(list(clinic).get("bedsTotal").asInt()).isZero();

        // a clinic without cases of its own sees none of these
        Clinic other = hospital();
        assertThat(list(other).get("emergency").get("queue").asInt()).isZero();

        // Read-only: the number stays, the tile shows a dash because writable is false
        expireTrial(clinic);
        JsonNode ro = list(clinic).get("emergency");
        assertThat(ro.get("writable").asBoolean()).isFalse();
        assertThat(ro.get("queue").asInt()).isEqualTo(4);
    }

    @Test
    void theMenuHasEmergencyBeforeBedsAndAdmissionsForOwnerFrontDeskAndNurseOnlyAndTheBadgeCountComesFromTheSummary() throws Exception {
        Clinic clinic = hospital();                                  // Trial
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            List<String> menu = menu(clinic, role);
            assertThat(menu).as(role).contains("emergency", "ipd");
            assertThat(menu.indexOf("ipd")).as(role + ": emergency right before ipd").isEqualTo(menu.indexOf("emergency") + 1);
        }
        List<String> doctor = menu(clinic, "DOCTOR");
        assertThat(doctor).contains("ipd").doesNotContain("emergency");
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "COUNSELLOR"}) {
            assertThat(menu(clinic, role)).as(role).doesNotContain("emergency", "ipd");
        }
        // SAH-31 (DEC-017 Q2): the PHARMACIST has ipd (the Safety-only page) right after today, and no Emergency item
        List<String> pharmacist = menu(clinic, "PHARMACIST");
        assertThat(pharmacist).as("PHARMACIST").contains("ipd").doesNotContain("emergency");
        assertThat(pharmacist.indexOf("ipd")).as("PHARMACIST: ipd right after today").isEqualTo(pharmacist.indexOf("today") + 1);
        // the badge: the number of cases in the queue, no names
        reg(clinic, "One", 2);
        reg(clinic, "Two", 3);
        String gone = reg(clinic, "Three", 3).get("id").asText();
        release(clinic, gone);
        for (String role : ER_ROLES) {
            JsonNode summary = ok(HttpMethod.GET, ER_SUMMARY, clinic.as(role), null, 200);
            assertThat(summary.get("queue").asInt()).as(role).isEqualTo(2);
            assertThat(summary.toString()).doesNotContain("One").doesNotContain("Two");
        }
        // the Simple menu has no separate Emergency item (the tab of Beds and admissions carries it)
        ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"menuMode\":\"simple\"}", 200);
        List<String> simple = menu(clinic, "OWNER");
        assertThat(simple).contains("ipd").doesNotContain("emergency");
        ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"menuMode\":\"advanced\"}", 200);
        assertThat(menu(clinic, "OWNER")).contains("emergency");
        // Read-only: no item
        expireTrial(clinic);
        for (String role : ALL_ROLES) {
            assertThat(menu(clinic, role)).as(role + " read-only").doesNotContain("emergency");
        }
        assertThat(menu(clinic, "OWNER")).contains("ipd");
        // Off: no item
        assertThat(menu(newClinic(), "OWNER")).doesNotContain("emergency", "ipd");
    }
}
