package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-17 Privacy and consent on the real {@code sahaj_test} database: the lazy privacy settings row, the
 * append-only consent history, the record-access log written by opening a patient file, row level security
 * and the grants/triggers that make both logs append-only.
 */
@OnTestDatabase
class PrivacyConsentIT extends SweepSupport {

    private static final String PRIVACY = "/api/v1/platform/privacy";
    private static final String PATIENTS = "/api/v1/patient/patients";
    private static final String ACCESS = "/api/v1/patient/record-access";
    private static final String AUDIT = "/api/v1/platform/audit-events?limit=100";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String PUT_OK = "{\"consentRequired\":false,\"promoConsent\":true,\"recordCopy\":false,"
            + "\"hfrId\":\"HFR-123\"}";

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long settingsRows(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.privacy_settings");
    }

    private long consentRows(Clinic clinic, UUID patient) throws SQLException {
        return count(clinic, "select count(*) from patient.patient_consent where patient_id = ?", patient);
    }

    private long accessRows(Clinic clinic, UUID patient) throws SQLException {
        return count(clinic, "select count(*) from patient.record_access where patient_id = ?", patient);
    }

    private String consents(UUID patient) {
        return PATIENTS + "/" + patient + "/consents";
    }

    private String body(String kind, boolean given) {
        return "{\"kind\":\"" + kind + "\",\"given\":" + given + "}";
    }

    // ---------------- Privacy settings (AC2, AC5, AC9, AC10) ----------------

    @Test
    void freshTenantGetsDefaultsAndGetInsertsNothing() throws Exception {
        Clinic clinic = newClinic();
        JsonNode s = ok(HttpMethod.GET, PRIVACY, clinic.owner(), null, 200);
        assertThat(s.get("consentRequired").asBoolean()).isTrue();
        assertThat(s.get("promoConsent").asBoolean()).isTrue();
        assertThat(s.get("recordCopy").asBoolean()).isTrue();
        assertThat(s.get("hfrId").isNull()).isTrue();
        ok(HttpMethod.GET, PRIVACY, clinic.owner(), null, 200);
        assertThat(settingsRows(clinic)).isZero();
    }

    @Test
    void ownerSavesAndSecondPutUpdatesTheSingleRow() throws Exception {
        Clinic clinic = newClinic();
        JsonNode saved = ok(HttpMethod.PUT, PRIVACY, clinic.owner(), PUT_OK, 200);
        assertThat(saved.get("consentRequired").asBoolean()).isFalse();
        assertThat(saved.get("hfrId").asText()).isEqualTo("HFR-123");
        assertThat(settingsRows(clinic)).isEqualTo(1);
        long v1 = count(clinic, "select version from platform.privacy_settings");

        ok(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":false,\"recordCopy\":true,\"hfrId\":null}", 200);
        assertThat(settingsRows(clinic)).isEqualTo(1);
        assertThat(count(clinic, "select version from platform.privacy_settings")).isGreaterThan(v1);

        JsonNode read = ok(HttpMethod.GET, PRIVACY, clinic.owner(), null, 200);
        assertThat(read.get("consentRequired").asBoolean()).isTrue();
        assertThat(read.get("promoConsent").asBoolean()).isFalse();
        assertThat(read.get("hfrId").isNull()).isTrue();
    }

    @Test
    void hfrIdIsTrimmedBlankBecomesNullAndThirtyThreeCharsIsRefused() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":\"  HFR-9  \"}", 200)
                .get("hfrId").asText()).isEqualTo("HFR-9");
        assertThat(ok(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":\"   \"}", 200)
                .get("hfrId").isNull()).isTrue();
        assertThat(count(clinic, "select count(*) from platform.privacy_settings where hfr_id is null")).isEqualTo(1);
        String ok32 = "A".repeat(32);
        assertThat(ok(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":\"" + ok32 + "\"}", 200)
                .get("hfrId").asText()).isEqualTo(ok32);
        problem(call(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":\"" + "A".repeat(33) + "\"}"), 400);
        assertThat(ok(HttpMethod.GET, PRIVACY, clinic.owner(), null, 200).get("hfrId").asText()).isEqualTo(ok32);
    }

    @Test
    void missingSwitchIsRefusedAndNothingIsSaved() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":null}"), 400);
        problem(call(HttpMethod.PUT, PRIVACY, clinic.owner(),
                "{\"consentRequired\":true,\"promoConsent\":true,\"hfrId\":null}"), 400);
        problem(call(HttpMethod.PUT, PRIVACY, clinic.owner(), "{}"), 400);
        assertThat(settingsRows(clinic)).isZero();
    }

    @Test
    void everyNonOwnerRoleIsRefusedOnPutAndEveryClinicRoleCanGet() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, PRIVACY, clinic.as(role), PUT_OK), 403);
        }
        assertThat(settingsRows(clinic)).isZero();
        for (String role : ALL_ROLES) {
            ok(HttpMethod.GET, PRIVACY, clinic.as(role), null, 200);
        }
    }

    @Test
    void savingIsAuditedWithBeforeAndAfterAndNoPatientData() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic); // a patient exists, its data must not leak into the audit
        ok(HttpMethod.PUT, PRIVACY, clinic.owner(), PUT_OK, 200);
        JsonNode events = ok(HttpMethod.GET, AUDIT, clinic.owner(), null, 200);
        JsonNode event = null;
        for (JsonNode e : events) {
            if ("PRIVACY_SETTINGS_CHANGED".equals(e.get("action").asText())) {
                event = e;
                break;
            }
        }
        assertThat(event).as("PRIVACY_SETTINGS_CHANGED audit event").isNotNull();
        assertThat(event.get("before").get("consentRequired").asBoolean()).isTrue();
        assertThat(event.get("after").get("consentRequired").asBoolean()).isFalse();
        assertThat(event.get("after").get("hfrId").asText()).isEqualTo("HFR-123");
        String text = event.toString();
        assertThat(text).doesNotContain("Ramesh", "Kulkarni", "Shivaji", patient.toString());
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'PRIVACY_SETTINGS_CHANGED'"))
                .isEqualTo(1);
    }

    @Test
    void anotherTenantHasItsOwnDefaultsAndCannotSeeTheRow() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ok(HttpMethod.PUT, PRIVACY, a.owner(), PUT_OK, 200);
        JsonNode sb = ok(HttpMethod.GET, PRIVACY, b.owner(), null, 200);
        assertThat(sb.get("consentRequired").asBoolean()).isTrue();
        assertThat(sb.get("hfrId").isNull()).isTrue();
        assertThat(settingsRows(b)).isZero();
        assertThat(settingsRows(a)).isEqualTo(1);
        // Tenant B's session asking for A's row by id sees nothing, even by an explicit tenant filter.
        assertThat((long) db().<Long>inTenant(b.tenantId(), c -> DbAccess.count(c,
                "select count(*) from platform.privacy_settings where tenant_id = ?", a.tenantId()))).isZero();
        // B's audit trail holds no privacy event of A.
        assertThat(ok(HttpMethod.GET, AUDIT, b.owner(), null, 200).toString()).doesNotContain("PRIVACY_SETTINGS_CHANGED");
    }

    // ---------------- Consents (AC3, AC4, AC9, AC10) ----------------

    @Test
    void createWithConsentsRecordsBothWithStaffName() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(), patientJson(
                ",\"consents\":[{\"kind\":\"RECORDS\",\"given\":true},{\"kind\":\"PROMOTIONAL\",\"given\":false}]"), 201);
        UUID id = UUID.fromString(created.get("id").asText());
        JsonNode state = ok(HttpMethod.GET, consents(id), clinic.as("DOCTOR"), null, 200);
        assertThat(state.get("records").get("given").asBoolean()).isTrue();
        assertThat(state.get("records").get("kind").asText()).isEqualTo("RECORDS");
        assertThat(state.get("records").get("recordedByName").asText()).isNotBlank();
        assertThat(state.get("promotional").get("given").asBoolean()).isFalse();
        assertThat(state.get("promotional").get("recordedByName").asText()).isNotBlank();
        assertThat(consentRows(clinic, id)).isEqualTo(2);
    }

    @Test
    void patientWithoutConsentsHasNoConsentState() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        JsonNode state = ok(HttpMethod.GET, consents(id), clinic.owner(), null, 200);
        assertThat(state.get("records") == null || state.get("records").isNull()).isTrue();
        assertThat(state.get("promotional") == null || state.get("promotional").isNull()).isTrue();
        assertThat(consentRows(clinic, id)).isZero();
    }

    @Test
    void withdrawalIsANewRowAndLatestPerKindWins() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(), patientJson(
                ",\"consents\":[{\"kind\":\"RECORDS\",\"given\":true},{\"kind\":\"PROMOTIONAL\",\"given\":false}]"), 201);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(consentRows(clinic, id)).isEqualTo(2);

        JsonNode after = ok(HttpMethod.POST, consents(id), clinic.as("FRONT_DESK"), body("RECORDS", false), 200);
        assertThat(after.get("records").get("given").asBoolean()).isFalse();
        assertThat(after.get("promotional").get("given").asBoolean()).isFalse();
        assertThat(consentRows(clinic, id)).isEqualTo(3);
        // The older "true" row is still there.
        assertThat(count(clinic,
                "select count(*) from patient.patient_consent where patient_id = ? and kind = 'RECORDS' and given",
                id)).isEqualTo(1);

        // Agree again: 4 rows, the latest per kind (distinct on) is true.
        ok(HttpMethod.POST, consents(id), clinic.owner(), body("RECORDS", true), 200);
        assertThat(consentRows(clinic, id)).isEqualTo(4);
        JsonNode state = ok(HttpMethod.GET, consents(id), clinic.owner(), null, 200);
        assertThat(state.get("records").get("given").asBoolean()).isTrue();
        assertThat(state.get("promotional").get("given").asBoolean()).isFalse();
        assertThat(count(clinic, "select count(*) from (select distinct on (kind) kind, given "
                + "from patient.patient_consent where patient_id = ? order by kind, recorded_at desc, id) t where given",
                id)).isEqualTo(1);
    }

    @Test
    void duplicateKindInOneCreateIsRefusedAndPatientIsNotCreated() throws Exception {
        Clinic clinic = newClinic();
        long before = count(clinic, "select count(*) from patient.patient");
        long consentsBefore = count(clinic, "select count(*) from patient.patient_consent");
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), patientJson(
                ",\"consents\":[{\"kind\":\"RECORDS\",\"given\":true},{\"kind\":\"RECORDS\",\"given\":false}]")), 400);
        assertThat(count(clinic, "select count(*) from patient.patient")).isEqualTo(before);
        assertThat(count(clinic, "select count(*) from patient.patient_consent")).isEqualTo(consentsBefore);
    }

    @Test
    void unknownKindAndMissingGivenAreRefused() throws Exception {
        Clinic clinic = newClinic();
        long before = count(clinic, "select count(*) from patient.patient");
        problem(call(HttpMethod.POST, PATIENTS, clinic.owner(), patientJson(
                ",\"consents\":[{\"kind\":\"MARKETING\",\"given\":true}]")), 400);
        assertThat(count(clinic, "select count(*) from patient.patient")).isEqualTo(before);
        UUID id = newPatient(clinic);
        problem(call(HttpMethod.POST, consents(id), clinic.owner(), body("MARKETING", true)), 400);
        problem(call(HttpMethod.POST, consents(id), clinic.owner(), "{\"kind\":\"RECORDS\"}"), 400);
        problem(call(HttpMethod.POST, consents(id), clinic.owner(), "{\"given\":true}"), 400);
        assertThat(consentRows(clinic, id)).isZero();
    }

    @Test
    void onlyOwnerFrontDeskAndNurseMayRecordConsent() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            ok(HttpMethod.POST, consents(id), clinic.as(role), body("PROMOTIONAL", true), 200);
        }
        assertThat(consentRows(clinic, id)).isEqualTo(3);
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.POST, consents(id), clinic.as(role), body("PROMOTIONAL", false)), 403);
        }
        assertThat(consentRows(clinic, id)).isEqualTo(3);
    }

    @Test
    void unknownAndOtherTenantPatientIsNotFound() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID inA = newPatient(a);
        problem(call(HttpMethod.GET, consents(UUID.randomUUID()), a.owner(), null), 404);
        problem(call(HttpMethod.POST, consents(UUID.randomUUID()), a.owner(), body("RECORDS", true)), 404);
        problem(call(HttpMethod.GET, consents(inA), b.owner(), null), 404);
        problem(call(HttpMethod.POST, consents(inA), b.owner(), body("RECORDS", true)), 404);
        assertThat(consentRows(a, inA)).isZero();
    }

    @Test
    void consentRowsCannotBeUpdatedDeletedOrTruncatedByTheApp() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        ok(HttpMethod.POST, consents(id), clinic.owner(), body("RECORDS", true), 200);
        assertDenied(clinic, "update patient.patient_consent set given = false");
        assertDenied(clinic, "delete from patient.patient_consent");
        assertDenied(clinic, "truncate patient.patient_consent");
        assertThat(consentRows(clinic, id)).isEqualTo(1);
    }

    @Test
    void consentWritesDoNotCreateAuditEvents() throws Exception {
        Clinic clinic = newClinic();
        UUID id = UUID.fromString(ok(HttpMethod.POST, PATIENTS, clinic.owner(), patientJson(
                ",\"consents\":[{\"kind\":\"RECORDS\",\"given\":true}]"), 201).get("id").asText());
        long afterCreate = count(clinic, "select count(*) from platform.audit_event");
        ok(HttpMethod.POST, consents(id), clinic.owner(), body("RECORDS", false), 200);
        assertThat(count(clinic, "select count(*) from platform.audit_event")).isEqualTo(afterCreate);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action ilike '%CONSENT%'")).isZero();
    }

    // ---------------- Record access (AC7, AC8, AC10) ----------------

    @Test
    void openingAPatientFileWritesExactlyOneRowPerCall() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        assertThat(accessRows(clinic, id)).isZero(); // creating the patient is not opening the file
        ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.as("DOCTOR"), null, 200);
        assertThat(accessRows(clinic, id)).isEqualTo(1);
        ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.owner(), null, 200);
        assertThat(accessRows(clinic, id)).isEqualTo(2);
    }

    @Test
    void listSearchFamilyAndConsentEndpointsWriteNoAccessRows() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        ok(HttpMethod.GET, PATIENTS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, PATIENTS + "?q=Ramesh", clinic.owner(), null, 200);
        ok(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.owner(), null, 200);
        ok(HttpMethod.GET, consents(id), clinic.owner(), null, 200);
        ok(HttpMethod.POST, consents(id), clinic.owner(), body("RECORDS", true), 200);
        assertThat(count(clinic, "select count(*) from patient.record_access")).isZero();
    }

    @Test
    void notFoundPatientWritesNoAccessRow() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID inA = newPatient(a);
        problem(call(HttpMethod.GET, PATIENTS + "/" + inA, b.owner(), null), 404);
        problem(call(HttpMethod.GET, PATIENTS + "/" + UUID.randomUUID(), a.owner(), null), 404);
        assertThat(count(a, "select count(*) from patient.record_access")).isZero();
        assertThat(count(b, "select count(*) from patient.record_access")).isZero();
    }

    @Test
    void ownerListsNewestFirstWithNamesAndDefaultLimitFive() throws Exception {
        Clinic clinic = newClinic();
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID p = newPatient(clinic);
            patients.add(p);
            ok(HttpMethod.GET, PATIENTS + "/" + p, clinic.owner(), null, 200);
        }
        JsonNode list = ok(HttpMethod.GET, ACCESS, clinic.owner(), null, 200);
        assertThat(list.size()).isEqualTo(5);
        assertThat(list.get(0).get("patientId").asText()).isEqualTo(patients.get(5).toString());
        assertThat(list.get(4).get("patientId").asText()).isEqualTo(patients.get(1).toString());
        assertThat(list.get(0).get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(list.get(0).get("userName").asText()).isNotBlank().isNotEqualTo("A staff member");
        assertThat(list.get(0).get("at").asText()).isNotBlank();

        assertThat(ok(HttpMethod.GET, ACCESS + "?limit=1", clinic.owner(), null, 200).size()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, ACCESS + "?limit=50", clinic.owner(), null, 200).size()).isEqualTo(6);
        problem(call(HttpMethod.GET, ACCESS + "?limit=0", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, ACCESS + "?limit=51", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, ACCESS + "?limit=abc", clinic.owner(), null), 400);
    }

    @Test
    void nonOwnerRolesCannotReadTheAccessList() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.GET, ACCESS, clinic.as(role), null), 403);
        }
    }

    @Test
    void accessRowStoresOnlyIdsAndATimestamp() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.owner(), null, 200);
        List<String> columns = db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var st = c.prepareStatement("select * from patient.record_access");
                 var rs = st.executeQuery()) {
                var meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    out.add(meta.getColumnName(i));
                }
            }
            return out;
        });
        assertThat(columns).containsExactlyInAnyOrder("id", "tenant_id", "patient_id", "user_id", "at");
        String row = db().inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select r::text from patient.record_access r"));
        assertThat(row).doesNotContain("Ramesh", "Kulkarni", "Shivaji");
    }

    @Test
    void accessRowsCannotBeUpdatedDeletedOrTruncatedByTheApp() throws Exception {
        Clinic clinic = newClinic();
        UUID id = newPatient(clinic);
        ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.owner(), null, 200);
        assertDenied(clinic, "update patient.record_access set at = now()");
        assertDenied(clinic, "delete from patient.record_access");
        assertDenied(clinic, "truncate patient.record_access");
        assertThat(accessRows(clinic, id)).isEqualTo(1);
    }

    @Test
    void anotherTenantSeesNoAccessRows() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID id = newPatient(a);
        ok(HttpMethod.GET, PATIENTS + "/" + id, a.owner(), null, 200);
        assertThat(ok(HttpMethod.GET, ACCESS, a.owner(), null, 200).size()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, ACCESS + "?limit=50", b.owner(), null, 200).size()).isZero();
        assertThat(count(b, "select count(*) from patient.record_access")).isZero();
        assertThat(count(b, "select count(*) from patient.patient_consent")).isZero();
        assertThat((long) db().<Long>inTenant(b.tenantId(), c -> DbAccess.count(c,
                "select count(*) from patient.record_access where tenant_id = ?", a.tenantId()))).isZero();
    }

    // ---------------- helpers ----------------

    private void assertDenied(Clinic clinic, String sql) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql);
            return null;
        })).isInstanceOf(SQLException.class);
    }

    private String patientJson(String extra) {
        return "{\"name\":\"Ramesh Kulkarni\",\"age\":45,\"sex\":\"M\",\"mobile\":\"" + uniqueMobile()
                + "\",\"area\":\"Shivaji Nagar\",\"language\":\"mr\"" + extra + "}";
    }
}
