package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-34 story 34E (break-glass emergency access) on the real {@code sahaj_test} database: the picker holds identity only and
 * writes nothing; opening writes one grant (30 minutes on the database clock), one {@code bg} entry linked to the grant, one
 * record-access row and one audit row in one transaction; two taps make one grant; a patient already in the caller's list is
 * 409; only a nurse who sees some wards may open; a grant opens only her own role's endpoints for that one patient, ends at
 * its time (real expiry, the stamping trigger switched off for the insert), and ends with the stay. Test plan:
 * docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class EmergencyAccessIT extends ScopeSupport {

    private static final String[] NON_NURSE = {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"};

    private static final String PICKER_AUDIT = "IPD_EMERGENCY_ACCESS_PICKER_VIEWED";

    /** The {@link #written} snapshot with the audit count (15th field) raised by {@code extra}: the picker's one audit row. */
    private static String withAuditRows(String snapshot, int extra) {
        String[] f = snapshot.split("/", -1);
        f[14] = String.valueOf(Long.parseLong(f[14]) + extra);
        return String.join("/", f);
    }

    private String pickerAuditRowText(Clinic clinic) throws Exception {
        return String.join("|", values(clinic, "select to_jsonb(a)::text from platform.audit_event a where action = ? "
                + "order by at desc limit 1", PICKER_AUDIT));
    }

    private JsonNode picker(Pn nurse) throws Exception {
        return ok(HttpMethod.GET, EA_PICKER, nurse.auth(), null, 200);
    }

    private static List<String> pickerIds(JsonNode picker) {
        List<String> ids = new ArrayList<>();
        picker.get("items").forEach(i -> ids.add(i.get("admissionId").asText()));
        return ids;
    }

    // ---------------- the picker ----------------

    @Test
    void thePickerListsTheAdmittedPatientsOutsideHerWardsWithIdentityOnlyThreeReasonsAndWritesNothing() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        raise(clinic, h.inB(), high7());
        String written = written(clinic);
        long access = recordAccess(clinic, h.inB().patientId());
        long pickerAudits = audits(clinic, PICKER_AUDIT);
        var response = call(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        String raw = response.getContentAsString();
        JsonNode picker = objectMapper.readTree(raw);
        assertThat(pickerIds(picker)).containsExactly(h.inB().id());
        JsonNode item = picker.get("items").get(0);
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("admissionId", "patientName", "bedCode", "uhid");
        assertThat(item.get("patientName").asText()).isEqualTo(h.inB().name());
        assertThat(item.get("bedCode").asText()).isEqualTo(h.inB().bedCode());
        assertThat(item.get("uhid").asText()).isEqualTo(values(clinic, "select uhid from patient.patient where id = ?::uuid",
                h.inB().patientId()).get(0));
        assertThat(raw).doesNotContain("diagnosis").doesNotContain("allerg").doesNotContain("vitals").doesNotContain("note")
                .doesNotContain("bill").doesNotContain("Paise").doesNotContain("mobile").doesNotContain(h.inB().mobile())
                .doesNotContain("Surgical").doesNotContain("ews");
        assertThat(picker.get("reasons")).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(picker.get("reasons").get(i).get("key").asText()).isEqualTo(EA_REASONS[i][0]);
            assertThat(picker.get("reasons").get(i).get("label").asText()).isEqualTo(EA_REASONS[i][1]);
        }
        // SAH-34 review fix (security finding 1, AC 78): exactly one audit row, and nothing else is written
        assertThat(written(clinic)).as("no clinical event, no record-access row, nothing but the one audit row")
                .isEqualTo(withAuditRows(written, 1));
        assertThat(recordAccess(clinic, h.inB().patientId())).isEqualTo(access);
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(pickerAudits + 1);
        List<String[]> rows = audit(clinic, PICKER_AUDIT);
        String[] a = rows.get(rows.size() - 1);
        assertThat(a[0]).isEqualTo("IPD_EMERGENCY_ACCESS");
        assertThat(a[1]).as("entity id").isNull();
        assertThat(a[2]).as("before").isNull();
        assertThat(objectMapper.readTree(a[3])).isEqualTo(objectMapper.readTree("{\"count\": 1}"));
        assertThat(a[4]).as("actor").isEqualTo(h.nurseA().userId().toString());
        assertThat(pickerAuditRowText(clinic)).doesNotContain(h.inB().id()).doesNotContain(h.inB().patientId().toString())
                .doesNotContain(h.inB().name()).doesNotContain(h.inB().mobile()).doesNotContain(h.inB().bedCode())
                .doesNotContain(item.get("uhid").asText());
    }

    @Test
    void anEmptyPickerStillWritesOneAuditRowWithCountZeroAndTwoReadsWriteTwoRows() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        assign(clinic, h.wardB(), h.nurseB(), h.nurseA()); // she now sees everyone: nothing to pick
        String written = written(clinic);
        long before = audits(clinic, PICKER_AUDIT);
        assertThat(picker(h.nurseA()).get("items")).isEmpty();
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before + 1);
        List<String[]> rows = audit(clinic, PICKER_AUDIT);
        String[] last = rows.get(rows.size() - 1);
        assertThat(objectMapper.readTree(last[3])).isEqualTo(objectMapper.readTree("{\"count\": 0}"));
        assertThat(last[1]).as("entity id").isNull();
        assertThat(last[4]).isEqualTo(h.nurseA().userId().toString());
        assertThat(written(clinic)).isEqualTo(withAuditRows(written, 1));
        // a second read is a second row; the other nurse's read counts her one patient
        picker(h.nurseA());
        pickerIds(picker(h.nurseB()));
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before + 3);
        rows = audit(clinic, PICKER_AUDIT);
        last = rows.get(rows.size() - 1);
        assertThat(objectMapper.readTree(last[3])).isEqualTo(objectMapper.readTree("{\"count\": 1}"));
        assertThat(last[4]).isEqualTo(h.nurseB().userId().toString());
        // opening a grant still writes only its own action, never the picker's
        long pickerRows = audits(clinic, PICKER_AUDIT);
        openGrant(h.nurseB(), h.inA().id(), "DETERIORATING");
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(pickerRows);
        assertThat(audits(clinic, "IPD_EMERGENCY_ACCESS_OPENED")).isOne();
    }

    @Test
    void aRefusedOrUnauthenticatedPickerReadWritesNoAuditRowAndNothingElse() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        long before = audits(clinic, PICKER_AUDIT);
        // set-up that writes its own rows (a team member, the switch) is done before each snapshot, never between it and the check
        Pn doctor = newPn(clinic, "DOCTOR");
        setMembershipStatus(clinic.tenantId(), h.nurseB().userId(), "INACTIVE");
        String written = written(clinic);
        for (String role : NON_NURSE) {
            forbidden(call(HttpMethod.GET, EA_PICKER, clinic.as(role), null));
        }
        call(HttpMethod.GET, EA_PICKER, noSession(), null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        // a nurse token whose membership is not a nurse, and an inactive membership
        forbidden(call(HttpMethod.GET, EA_PICKER, tokenOf(clinic, doctor.userId(), "NURSE"), null));
        forbidden(call(HttpMethod.GET, EA_PICKER, h.nurseB().auth(), null));
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before);
        assertThat(written(clinic)).isEqualTo(written);
        // a nurse of a clinic whose switch is off
        switchScope(clinic, false);
        written = written(clinic);
        forbidden(call(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null));
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before);
        assertThat(written(clinic)).isEqualTo(written);
    }

    @Test
    void aReadOnlyIpdStillAnswersThePickerAndWritesTheRowWhileIpdOffIsRoleFirstThenModuleOffAndWritesNothing() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        long before = audits(clinic, PICKER_AUDIT);
        expireTrial(clinic); // IPD read-only: reads work, writes are refused
        assertThat(pickerIds(picker(h.nurseA()))).containsExactly(h.inB().id());
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before + 1);
        // IPD Off in a clinic that never started the trial: the role answer comes first (AC 14 as reworded), then the module
        Clinic off = newClinic();
        Pn nurse = newPn(off, "NURSE");
        String id = UUID.randomUUID().toString();
        assertType(problem(call(HttpMethod.GET, EA_PICKER, off.owner(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.POST, EA, off.owner(), eaBody(id, "DETERIORATING", true)), 403), "forbidden");
        assertType(problem(call(HttpMethod.GET, EA_PICKER, nurse.auth(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, EA, nurse.auth(), eaBody(id, "DETERIORATING", true)), 403), "module-off");
        assertThat(audits(off, PICKER_AUDIT)).isZero();
        assertThat(grantRows(off)).isZero();
    }

    @Test
    void whenTheAuditWriteFailsThePickerAnswers500WithNoListAndNothingIsKept() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        long before = audits(clinic, PICKER_AUDIT);
        String written = written(clinic);
        String suffix = clinic.tenantId().toString().replace('-', '_');
        String function = "platform.it_fail_picker_audit_" + suffix;
        String trigger = "trg_it_fail_picker_audit_" + suffix;
        String raw;
        try (java.sql.Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the picker audit test'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before insert on platform.audit_event for each row "
                    + "when (new.tenant_id = '" + clinic.tenantId() + "'::uuid and new.action = '" + PICKER_AUDIT
                    + "') execute function " + function + "()");
            try {
                var response = call(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(500);
                raw = response.getContentAsString();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on platform.audit_event");
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
        assertThat(raw).doesNotContain("items").doesNotContain("patientName").doesNotContain("uhid").doesNotContain(h.inB().name())
                .doesNotContain(h.inB().id()).doesNotContain("forced failure").doesNotContain("audit_event");
        assertThat(audits(clinic, PICKER_AUDIT)).isEqualTo(before);
        assertThat(written(clinic)).isEqualTo(written);
        // the trigger is gone: the picker works again
        assertThat(pickerIds(picker(h.nurseA()))).containsExactly(h.inB().id());
    }

    @Test
    void thePickerIsOrderedByBedCodeAndIsEmptyWhenTheNurseAlreadySeesEveryone() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Adm second = admitInNamed(clinic, h.wardB(), "Eshan Second-B");
        Adm third = admitInNamed(clinic, h.wardB(), "Farid Third-B");
        List<String> expected = new ArrayList<>(List.of(h.inB().bedCode(), second.bedCode(), third.bedCode()));
        java.util.Collections.sort(expected);
        List<String> codes = new ArrayList<>();
        picker(h.nurseA()).get("items").forEach(i -> codes.add(i.get("bedCode").asText()));
        assertThat(codes).as("code-point order").isEqualTo(expected);
        // a nurse of both wards sees everyone: nothing to pick
        assign(clinic, h.wardB(), h.nurseB(), h.nurseA());
        assertThat(picker(h.nurseA()).get("items")).isEmpty();
        assertThat(picker(h.nurseA()).get("reasons")).hasSize(3);
    }

    @Test
    void everyRoleButANurseWhoSeesSomeWardsGets403WithTheFixedTextAndNothingIsRead() throws Exception {
        Hosp h = hosp();
        String written = written(h.clinic());
        for (String role : NON_NURSE) {
            for (JsonNode p : new JsonNode[] {
                problem(call(HttpMethod.GET, EA_PICKER, h.clinic().as(role), null), 403),
                problem(call(HttpMethod.POST, EA, h.clinic().as(role), eaBody(h.inB().id(), "DETERIORATING", true)), 403)}) {
                assertType(p, "forbidden");
                assertThat(p.get("title").asText()).isEqualTo("Not allowed");
                assertThat(p.get("detail").asText()).as(role).isEqualTo(EA_ONLY);
            }
        }
        assertThat(written(h.clinic())).isEqualTo(written);
        // no session at all
        call(HttpMethod.GET, EA_PICKER, noSession(), null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        // a nurse of a clinic whose switch is off
        switchScope(h.clinic(), false);
        assertThat(problem(call(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null), 403).get("detail").asText()).isEqualTo(EA_ONLY);
    }

    @Test
    void aNurseTokenWhoseMembershipIsNotANurseIsRefusedAndAMembershipThatIsNotActiveIsRefused() throws Exception {
        Hosp h = hosp();
        Pn doctor = newPn(h.clinic(), "DOCTOR");
        RequestPostProcessor claimsNurse = tokenOf(h.clinic(), doctor.userId(), "NURSE");
        assertThat(problem(call(HttpMethod.GET, EA_PICKER, claimsNurse, null), 403).get("detail").asText()).isEqualTo(EA_ONLY);
        problem(call(HttpMethod.POST, EA, claimsNurse, eaBody(h.inB().id(), "DETERIORATING", true)), 403);
        setMembershipStatus(h.clinic().tenantId(), h.nurseA().userId(), "INACTIVE");
        problem(call(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null), 403);
        problem(call(HttpMethod.POST, EA, h.nurseA().auth(), eaBody(h.inB().id(), "DETERIORATING", true)), 403);
        assertThat(grantRows(h.clinic())).isZero();
    }

    // ---------------- opening ----------------

    @Test
    void openingWritesOneGrantOneBgEntryOneRecordAccessRowAndOneAuditRowAndThirtyMinutesFromTheDatabaseClock() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        long access = recordAccess(clinic, h.inB().patientId());
        long events = eventCount(clinic, h.inB().id());
        long audits = audits(clinic, "IPD_EMERGENCY_ACCESS_OPENED");
        Instant before = Instant.now();
        JsonNode g = openGrant(h.nurseA(), h.inB().id(), "COVERING_BREAK");
        Instant after = Instant.now();
        assertThat(g.get("admissionId").asText()).isEqualTo(h.inB().id());
        assertThat(g.get("patientName").asText()).isEqualTo(h.inB().name());
        assertThat(g.get("bedCode").asText()).isEqualTo(h.inB().bedCode());
        assertThat(g.get("reasonKey").asText()).isEqualTo("COVERING_BREAK");
        assertThat(g.get("reasonLabel").asText()).isEqualTo("Covering for a colleague on break");
        Instant opened = instant(g.get("openedAt"));
        Instant expires = instant(g.get("expiresAt"));
        assertThat(Duration.between(opened, expires)).isEqualTo(Duration.ofMinutes(30));
        assertThat(opened).isBetween(before.minusSeconds(5), after.plusSeconds(5));
        String id = g.get("id").asText();

        assertThat(grantRows(clinic)).isOne();
        List<String> row = values(clinic, "select user_id::text, membership_id::text, role, reason_key, admission_id::text, bed_id::text, "
                + "(opened_at between clock_timestamp() - interval '2 minutes' and clock_timestamp())::text, "
                + "(expires_at - opened_at = interval '30 minutes')::text, (reviewed_at is null)::text, version::text "
                + "from ipd.emergency_access_grant where id = ?::uuid", id);
        assertThat(row).containsExactly(h.nurseA().userId().toString(), h.nurseA().membershipId().toString(), "NURSE", "COVERING_BREAK",
                h.inB().id(), bedOf(clinic, h.inB().id()).toString(), "true", "true", "true", "0");
        // the bg entry, linked to the grant
        List<String> bg = values(clinic, "select kind, text, grant_id::text, actor::text from ipd.clinical_event where grant_id = ?::uuid", id);
        assertThat(bg).containsExactly("bg", "Emergency access opened · Covering for a colleague on break", id, h.nurseA().userId().toString());
        assertThat(eventCount(clinic, h.inB().id())).isEqualTo(events + 1);
        assertThat(recordAccess(clinic, h.inB().patientId())).isEqualTo(access + 1);
        // the audit row: ids and the fixed key only
        List<String[]> audit = audit(clinic, "IPD_EMERGENCY_ACCESS_OPENED");
        assertThat(audit).hasSize((int) audits + 1);
        String[] a = audit.get(audit.size() - 1);
        assertThat(a[0]).isEqualTo("IPD_EMERGENCY_ACCESS");
        assertThat(a[1]).isEqualTo(id);
        assertThat(a[4]).isEqualTo(h.nurseA().userId().toString());
        assertThat(a[3]).contains(id).contains(h.inB().id()).contains("COVERING_BREAK").doesNotContain(h.inB().name())
                .doesNotContain(nameOf(clinic, h.nurseA().userId())).doesNotContain(h.inB().mobile());
    }

    @Test
    void afterOpeningThePatientIsInHerListWithTheGreyFlagAndOnlyHerOwnRoleEndpointsOpenForThatOnePatient() throws Exception {
        Full f = full();
        Hosp h = f.h();
        Clinic clinic = h.clinic();
        Adm third = admitInNamed(clinic, wardIdByName(clinic, "General ward"), "Gita Third-A");
        String wardC = newWard(clinic, "Orthopaedic ward");
        Adm inC = admitInNamed(clinic, wardC, "Hari Ward-C");
        Pn nurse = h.nurseA();
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), nurse.auth(), null));
        JsonNode g = openGrant(nurse, h.inB().id(), "DETERIORATING");

        JsonNode list = listOf(nurse.auth());
        assertThat(idsOf(list)).containsExactlyInAnyOrder(h.inA().id(), third.id(), h.inB().id());
        assertThat(list.get("scope").get("visible").asInt()).isEqualTo(3);
        assertThat(list.get("scope").get("admitted").asInt()).isEqualTo(4);
        for (JsonNode r : list.get("items")) {
            if (h.inB().id().equals(r.get("id").asText())) {
                assertThat(instant(r.get("emergencyAccess").get("expiresAt"))).isEqualTo(instant(g.get("expiresAt")));
                assertThat(r.get("mine").asBoolean()).as("a grant is not a ward").isFalse();
            } else {
                assertThat(r.has("emergencyAccess")).isFalse();
            }
        }
        // a grant of one nurse shows to nobody else
        JsonNode other = listOf(h.nurseB().auth());
        for (JsonNode r : other.get("items")) {
            assertThat(r.has("emergencyAccess")).isFalse();
        }
        for (JsonNode r : listOf(clinic.owner()).get("items")) {
            assertThat(r.has("emergencyAccess")).isFalse();
        }
        // her role's reads and writes now work for that one patient
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), nurse.auth(), null, 200);
        ok(HttpMethod.GET, nursingUrl(h.inB().id()), nurse.auth(), null, 200);
        ok(HttpMethod.GET, emrUrl(h.inB().id()), nurse.auth(), null, 200);
        ok(HttpMethod.GET, marUrl(h.inB().id()), nurse.auth(), null, 200);
        ok(HttpMethod.POST, vitalsUrl(h.inB().id()), nurse.auth(), js(low()), 201);
        ok(HttpMethod.POST, callUrl(f.escalationId()), nurse.auth(), "{\"version\":" + esc(clinic, f.escalationId()).version() + ",\"seniorCalls\":0}", 200);
        ok(HttpMethod.POST, dosesUrl(f.orderId()), nurse.auth(), js(notGiven(ist(8, 0), "NIL_BY_MOUTH")), 201);
        // the bed board shows the tile unmasked
        assertThat(ok(HttpMethod.GET, BOARD, nurse.auth(), null, 200).toString()).contains(h.inB().name());
        // but it gives no other role's action, and no other patient
        for (JsonNode p : new JsonNode[] {
            problem(call(HttpMethod.POST, ordersUrl(h.inB().id()), nurse.auth(), js(orderBody(newKey(), "Paracetamol 500 mg", "od", null, null))), 403),
            problem(call(HttpMethod.POST, stopUrl(f.orderId()), nurse.auth(), null), 403),
            problem(call(HttpMethod.POST, ackUrl(f.escalationId()), nurse.auth(), "{\"version\":0}"), 403)}) {
            assertThat(p.get("type").asText()).doesNotEndWith("outside-scope");
        }
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + inC.id(), nurse.auth(), null));
        // the escalation and the round of the granted patient are in her Safety board now
        assertThat(rowIds(boardOf(nurse.auth()))).contains(f.escalationId());
        // her own audit trail: the entries of the granted patient are in her events
        assertThat(eventsOf(nurse.auth(), "limit=50").toString()).contains("Emergency access opened");
        // the other nurse still cannot see him
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseB().auth(), null));
    }

    @Test
    void theValidationErrorsAreCollectedWithTheirFieldTextsAndNothingIsWritten() throws Exception {
        Hosp h = hosp();
        String written = written(h.clinic());
        Pn n = h.nurseA();
        String[][] cases = {
            {eaBody(null, "DETERIORATING", true), "admissionId", CHOOSE_PATIENT_TEXT},
            {eaBody("not-a-uuid", "DETERIORATING", true), "admissionId", CHOOSE_PATIENT_TEXT},
            {eaBody(h.inB().id(), null, true), "reasonKey", CHOOSE_REASON_TEXT},
            {eaBody(h.inB().id(), "BORED", true), "reasonKey", CHOOSE_REASON_TEXT},
            {eaBody(h.inB().id(), "deteriorating", true), "reasonKey", CHOOSE_REASON_TEXT},
            {eaBody(h.inB().id(), "DETERIORATING", false), "confirmed", TICK_TEXT},
            {eaBody(h.inB().id(), "DETERIORATING", "\"true\""), "confirmed", TICK_TEXT},
            {eaBody(h.inB().id(), "DETERIORATING", "null"), "confirmed", TICK_TEXT},
            {eaBody(h.inB().id(), "DETERIORATING", 1), "confirmed", TICK_TEXT},
            {"{\"admissionId\":\"" + h.inB().id() + "\",\"reasonKey\":\"DETERIORATING\"}", "confirmed", TICK_TEXT},
        };
        for (String[] c : cases) {
            JsonNode p = problem(call(HttpMethod.POST, EA, n.auth(), c[0]), 400);
            assertType(p, "validation");
            assertThat(fieldMessage(p, c[1])).as(c[0]).isEqualTo(c[2]);
        }
        JsonNode all = problem(call(HttpMethod.POST, EA, n.auth(), "{\"admissionId\":null,\"reasonKey\":null,\"confirmed\":false}"), 400);
        assertThat(fieldNames(all)).containsExactly("admissionId", "reasonKey", "confirmed");
        assertThat(written(h.clinic())).isEqualTo(written);
        // an empty body, a non-object and broken JSON are not validation errors
        for (String bad : new String[] {"", "[]", "{broken"}) {
            JsonNode p = problem(call(HttpMethod.POST, EA, n.auth(), bad.isEmpty() ? null : bad), 400);
            assertThat(p.get("type").asText()).endsWith("malformed-request");
        }
        assertThat(grantRows(h.clinic())).isZero();
    }

    @Test
    void aPatientAlreadyInHerWardsOrAlreadyGrantedIs409AlreadyInScopeAndNothingIsWritten() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String written = written(clinic);
        JsonNode p = problem(call(HttpMethod.POST, EA, h.nurseA().auth(), eaBody(h.inA().id(), "DETERIORATING", true)), 409);
        assertType(p, "already-in-scope");
        assertThat(p.get("detail").asText()).isEqualTo(ALREADY_IN_LIST);
        assertThat(written(clinic)).isEqualTo(written);
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        written = written(clinic);
        JsonNode again = problem(call(HttpMethod.POST, EA, h.nurseA().auth(), eaBody(h.inB().id(), "COVERING_BREAK", true)), 409);
        assertType(again, "already-in-scope");
        assertThat(written(clinic)).isEqualTo(written);
        assertThat(grantRows(clinic)).isOne();
        // the patient of her own grant is no longer in the picker
        assertThat(pickerIds(picker(h.nurseA()))).isEmpty();
        // the other nurse can still open the same patient? no: ward B is hers, so already in scope
        assertThat(problem(call(HttpMethod.POST, EA, h.nurseB().auth(), eaBody(h.inB().id(), "DETERIORATING", true)), 409)
                .get("type").asText()).endsWith("already-in-scope");
    }

    @Test
    void anUnknownAnotherClinicsAndADischargedAdmissionAreNotFound() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        Adm gone = admitInNamed(h.clinic(), h.wardB(), "Imran Gone");
        signAndDischarge(h.clinic(), gone.id());
        String written = written(h.clinic());
        for (String id : new String[] {UUID.randomUUID().toString(), other.inB().id(), gone.id()}) {
            JsonNode p = problem(call(HttpMethod.POST, EA, h.nurseA().auth(), eaBody(id, "DETERIORATING", true)), 404);
            assertType(p, "not-found");
        }
        assertThat(written(h.clinic())).isEqualTo(written);
        assertThat(grantRows(other.clinic())).isZero();
        assertThat(pickerIds(picker(h.nurseA()))).as("a discharged patient is not offered").containsExactly(h.inB().id());
    }

    @Test
    void twoTapsAtTheSameMomentMakeOneGrantOneEntryOneRecordAccessRowAndOneAuditRow() throws Exception {
        for (int round = 0; round < 3; round++) {
            Hosp h = hosp();
            Clinic clinic = h.clinic();
            long access = recordAccess(clinic, h.inB().patientId());
            String body = eaBody(h.inB().id(), "DUTY_DOCTOR_ASKED", true);
            List<Resp> answers = parallel(new Req(HttpMethod.POST, EA, h.nurseA().auth(), body),
                    new Req(HttpMethod.POST, EA, h.nurseA().auth(), body));
            List<Integer> statuses = new ArrayList<>();
            answers.forEach(a -> statuses.add(a.status()));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
            for (Resp a : answers) {
                if (a.status() == 409) {
                    assertThat(a.type(objectMapper)).isEqualTo("already-in-scope");
                }
            }
            assertThat(grantRows(clinic)).isOne();
            assertThat(count(clinic, "select count(*) from ipd.clinical_event where kind = 'bg'")).isOne();
            assertThat(recordAccess(clinic, h.inB().patientId())).isEqualTo(access + 1);
            assertThat(audits(clinic, "IPD_EMERGENCY_ACCESS_OPENED")).isOne();
        }
    }

    @Test
    void aFailureAfterTheGrantRollsBackTheGrantTheEntryTheRecordAccessAndTheAudit() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String written = written(clinic);
        failingInsert(clinic, "ipd.clinical_event", "new.kind = 'bg'", () -> {
            var response = call(HttpMethod.POST, EA, h.nurseA().auth(), eaBody(h.inB().id(), "DETERIORATING", true)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).doesNotContain(h.inB().name()).doesNotContain("forced failure");
        });
        assertThat(written(clinic)).as("all or nothing").isEqualTo(written);
        // and the next tap works
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        assertThat(grantRows(clinic)).isOne();
    }

    @Test
    void emergencyAccessIsAllowedWhenIpdIsReadOnly() throws Exception {
        Hosp h = hosp();
        expireTrial(h.clinic());
        assertThat(pickerIds(picker(h.nurseA()))).containsExactly(h.inB().id());
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        assertThat(grantRows(h.clinic())).isOne();
    }

    // ---------------- expiry and the end of the stay ----------------

    @Test
    void aGrantEndsAtItsTimeForRealThePatientLeavesHerListAndTheScreenAndAnewOneIsANewRow() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String grant = insertGrantEndingIn(clinic, h.nurseA(), h.inB().id(), 4);
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
        assertThat(idsOf(listOf(h.nurseA().auth()))).contains(h.inB().id());
        assertThat(pickerIds(picker(h.nurseA()))).isEmpty();
        Thread.sleep(5_000);
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null));
        outside(call(HttpMethod.POST, vitalsUrl(h.inB().id()), h.nurseA().auth(), js(low())));
        assertThat(idsOf(listOf(h.nurseA().auth()))).containsExactly(h.inA().id());
        assertThat(pickerIds(picker(h.nurseA()))).containsExactly(h.inB().id());
        JsonNode second = openGrant(h.nurseA(), h.inB().id(), "COVERING_BREAK");
        assertThat(second.get("id").asText()).isNotEqualTo(grant);
        assertThat(grantRows(clinic)).as("no extension: a new row with its own review line").isEqualTo(2);
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
    }

    @Test
    void anAlreadyExpiredGrantGivesNoAccessAndTheGrantOfOneNurseNeverWorksForAnother() throws Exception {
        Hosp h = hosp();
        insertExpiredGrant(h.clinic(), h.nurseA(), h.inB().id());
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null));
        insertGrantEndingIn(h.clinic(), h.nurseB(), h.inA().id(), 600);
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseB().auth(), null, 200);
        Pn third = newPn(h.clinic(), "NURSE");
        assign(h.clinic(), wardIdByName(h.clinic(), "General ward"), h.nurseA(), third);
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), third.auth(), null));
    }

    @Test
    void aGrantEndsWithTheStayAndAGrantOfADischargedPatientOpensNothing() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
        signAndDischarge(clinic, h.inB().id());
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null));
        assertThat(idsOf(listOf(h.nurseA().auth()))).containsExactly(h.inA().id());
        // the grant row is still there, unreviewed, for the owner
        assertThat(grantRows(clinic)).isOne();
        JsonNode board = boardOf(clinic.owner());
        assertThat(board.get("emergencyAccess").get("waiting").asInt()).isOne();
        assertThat(board.get("emergencyAccess").get("items").get(0).get("admissionStatus").asText()).isEqualTo("DISCHARGED");
    }

    @Test
    void theReviewRowKeepsTheBedOfTheGrantAfterThePatientIsMoved() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String oldBed = h.inB().bedCode();
        UUID newBed = bedIn(clinic, h.wardA());
        ok(HttpMethod.POST, transferUrl(h.inB().id()), clinic.owner(), moveBody(newBed, null), 200);
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
        JsonNode review = boardOf(clinic.owner()).get("emergencyAccess").get("items").get(0);
        assertThat(review.get("bedCode").asText()).as("the bed at the time of the grant").isEqualTo(oldBed);
    }

}
