package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-2 unit settings on the real database: defaults, validation, FEFO and negative stock as facts, Integration mode
 * and its audit, owner-only change, read for the pharmacist and the Central Store user, tenant isolation, and two owners
 * saving at once. Test plan: docs/test-plans/SAH-78.md (T78-2-*).
 */
@OnTestDatabase
class PharmacyUnitSettingsIT extends Sah78Support {

    private static Map<String, Object> body(Object version, String gstin, String licenceNo, Object till, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("gstin", gstin);
        m.put("drugLicenceNo", licenceNo);
        m.put("drugLicenceValidTill", till);
        m.put("integrationMode", mode);
        return m;
    }

    private JsonNode save(Clinic clinic, Map<String, Object> body, int status) throws Exception {
        return ok(HttpMethod.PUT, UNIT, clinic.owner(), js(body), status);
    }

    // ---------------- read ----------------

    @Test
    void theFirstVisitShowsTheDefaultsTheFixedFactsAndWritesNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode u = ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200);
        assertThat(u.get("unitName").asText()).isEqualTo("Sahaj test clinic");
        assertThat(u.get("unitCode").asText()).matches("[A-Z0-9]{3}");
        assertThat(u.get("gstin").isNull()).isTrue();
        assertThat(u.get("drugLicenceNo").isNull()).isTrue();
        assertThat(u.get("drugLicenceValidTill").isNull()).isTrue();
        assertThat(u.get("drugLicenceExpired").asBoolean()).isFalse();
        assertThat(u.get("integrationMode").asText()).as("OQ-78-14, contract default").isEqualTo("INTEGRATED");
        assertThat(u.get("fefo").asBoolean()).isTrue();
        assertThat(u.get("negativeStockBlocked").asBoolean()).isTrue();
        assertThat(u.get("ipdAvailable").asBoolean()).as("the clinic's IPD module is Off").isFalse();
        assertThat(u.get("saved").asBoolean()).isFalse();
        assertThat(u.get("version").asLong()).isZero();
        assertThat(pharmacyRows(clinic, "unit")).as("a read creates no unit row").isZero();
    }

    @Test
    void ipdAvailableFollowsTheClinicsIpdModule() throws Exception {
        Clinic clinic = pharmacyClinic();
        ok(HttpMethod.POST, MODULES + "/ipd-trial", clinic.owner(), null, 200);
        assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("ipdAvailable").asBoolean()).isTrue();
    }

    // ---------------- save ----------------

    @Test
    void aValidSaveUpperCasesTheGstinBumpsTheVersionAndIsAuditedWithBeforeAndAfter() throws Exception {
        Clinic clinic = pharmacyClinic();
        LocalDate till = todayIst().plusYears(2);
        JsonNode saved = save(clinic, body(0, "27aaaaa0000a1z5", " TEST-DL-0001 ", till.toString(), "STANDALONE"), 200);
        assertThat(saved.get("gstin").asText()).isEqualTo(GSTIN_OK);
        assertThat(saved.get("drugLicenceNo").asText()).isEqualTo("TEST-DL-0001");
        assertThat(saved.get("drugLicenceValidTill").asText()).isEqualTo(till.toString());
        assertThat(saved.get("drugLicenceExpired").asBoolean()).isFalse();
        assertThat(saved.get("integrationMode").asText()).isEqualTo("STANDALONE");
        assertThat(saved.get("saved").asBoolean()).isTrue();
        assertThat(saved.get("version").asLong()).isOne();
        assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).toString()).isEqualTo(saved.toString());

        List<List<String>> changed = auditRowsOf(clinic, "PHARMACY_UNIT_SETTINGS_CHANGED");
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).get(0)).isEqualTo("PHARMACY_UNIT");
        assertThat(changed.get(0).get(2)).as("before").doesNotContain(GSTIN_OK);
        assertThat(changed.get(0).get(3)).as("after holds the changed fields").contains(GSTIN_OK).contains("TEST-DL-0001")
                .contains("STANDALONE");
        assertThat(changed.get(0).get(4)).isEqualTo(clinic.ownerUserId().toString());
        List<List<String>> mode = auditRowsOf(clinic, "PHARMACY_INTEGRATION_MODE_CHANGED");
        assertThat(mode).hasSize(1);
        assertThat(mode.get(0).get(2)).contains("INTEGRATED");
        assertThat(mode.get(0).get(3)).contains("STANDALONE");
    }

    @Test
    void theNewModeShowsInEveryPersonsContextFromThenOnAndNoOpdOrIpdCapabilityIsListedInEitherMode() throws Exception {
        Clinic clinic = pharmacyClinic();
        Pn pharmacist = crew(clinic, "PHARMACIST");
        assertThat(contextOf(pharmacist.auth()).get("unit").get("integrationMode").asText()).isEqualTo("INTEGRATED");
        save(clinic, body(0, null, null, null, "STANDALONE"), 200);
        JsonNode standalone = contextOf(pharmacist.auth());
        assertThat(standalone.get("unit").get("integrationMode").asText()).isEqualTo("STANDALONE");
        assertThat(contextOf(clinic.owner()).get("unit").get("integrationMode").asText()).isEqualTo("STANDALONE");
        save(clinic, body(1, null, null, null, "INTEGRATED"), 200);
        JsonNode integrated = contextOf(pharmacist.auth());
        assertThat(integrated.get("unit").get("integrationMode").asText()).isEqualTo("INTEGRATED");
        for (JsonNode c : new JsonNode[] {standalone, integrated}) {
            assertThat(c.get("capabilities").toString()).doesNotContain("OPD").doesNotContain("IPD");
        }
        assertThat(auditRowsOf(clinic, "PHARMACY_INTEGRATION_MODE_CHANGED")).hasSize(2);
    }

    @Test
    void anEmptyGstinIsAllowedAndAPastLicenceDateIsAllowedAndShownExpired() throws Exception {
        Clinic clinic = pharmacyClinic();
        LocalDate past = todayIst().minusDays(30);
        JsonNode saved = save(clinic, body(0, "", "OLD-LICENCE-1", past.toString(), "INTEGRATED"), 200);
        assertThat(saved.get("gstin").isNull()).as("shown as Not set by the screen").isTrue();
        assertThat(saved.get("drugLicenceExpired").asBoolean()).isTrue();
        // the licence date of today is not expired
        JsonNode today = save(clinic, body(1, null, "OLD-LICENCE-1", todayIst().toString(), "INTEGRATED"), 200);
        assertThat(today.get("drugLicenceExpired").asBoolean()).isFalse();
        // a saved value can be cleared again
        JsonNode cleared = save(clinic, body(2, null, null, null, "INTEGRATED"), 200);
        assertThat(cleared.get("drugLicenceNo").isNull()).isTrue();
        assertThat(cleared.get("drugLicenceValidTill").isNull()).isTrue();
    }

    @Test
    void savingTheSameValuesAgainChangesNothingAndWritesNoSecondAudit() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode first = save(clinic, body(0, GSTIN_OK, "TEST-DL-0001", null, "INTEGRATED"), 200);
        JsonNode again = save(clinic, body(1, GSTIN_OK, "TEST-DL-0001", null, "INTEGRATED"), 200);
        assertThat(again.get("version").asLong()).isEqualTo(first.get("version").asLong());
        assertThat(auditRowsOf(clinic, "PHARMACY_UNIT_SETTINGS_CHANGED")).hasSize(1);
    }

    // ---------------- validation ----------------

    @Test
    void aBadGstinIs400WithTheFieldMessageAndNothingChanges() throws Exception {
        Clinic clinic = pharmacyClinic();
        for (String bad : new String[] {"ABC", "27AAAAA0000A1Z", "27AAAAA0000A1Z55", "27AAAAA0000A0Z5", "27AAAAA0000A1X5",
            "AAAAAAA0000A1Z5", "27AAAAA0000A1Z!"}) {
            JsonNode p = problem(call(HttpMethod.PUT, UNIT, clinic.owner(), js(body(0, bad, null, null, "INTEGRATED"))), 400);
            assertType(p, "validation");
            assertFieldMessage(p, "gstin", GSTIN_TEXT);
        }
        assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("saved").asBoolean()).isFalse();
        assertThat(auditRowsOf(clinic, "PHARMACY_UNIT_SETTINGS_CHANGED")).isEmpty();
    }

    @Test
    void aLicenceNumberOver40CharactersOrADateThatIsNotADateIs400ButFortyCharactersAreAccepted() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode tooLong = problem(call(HttpMethod.PUT, UNIT, clinic.owner(),
                js(body(0, null, "L".repeat(41), null, "INTEGRATED"))), 400);
        assertThat(tooLong.get("fieldErrors").get(0).get("field").asText()).isEqualTo("drugLicenceNo");
        JsonNode notADate = problem(call(HttpMethod.PUT, UNIT, clinic.owner(),
                "{\"version\":0,\"gstin\":null,\"drugLicenceNo\":null,\"drugLicenceValidTill\":\"31-03-2028\",\"integrationMode\":\"INTEGRATED\"}"), 400);
        assertType(notADate, "validation");
        assertThat(notADate.get("fieldErrors").get(0).get("field").asText()).isEqualTo("drugLicenceValidTill");
        JsonNode impossible = problem(call(HttpMethod.PUT, UNIT, clinic.owner(),
                "{\"version\":0,\"gstin\":null,\"drugLicenceNo\":null,\"drugLicenceValidTill\":\"2028-02-31\",\"integrationMode\":\"INTEGRATED\"}"), 400);
        assertType(impossible, "validation");
        assertThat(save(clinic, body(0, null, "L".repeat(40), null, "INTEGRATED"), 200).get("drugLicenceNo").asText()).hasSize(40);
    }

    @Test
    void aMissingOrUnknownIntegrationModeAndAMissingVersionAreRefusedTogetherWithTheirFields() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode p = problem(call(HttpMethod.PUT, UNIT, clinic.owner(), "{\"gstin\":\"bad\"}"), 400);
        assertType(p, "validation");
        List<String> fields = new ArrayList<>();
        p.get("fieldErrors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("version", "gstin", "integrationMode");
        JsonNode unknownMode = problem(call(HttpMethod.PUT, UNIT, clinic.owner(),
                "{\"version\":0,\"integrationMode\":\"HYBRID\"}"), 400);
        assertThat(unknownMode.get("fieldErrors").get(0).get("field").asText()).isEqualTo("integrationMode");
        assertType(problem(call(HttpMethod.PUT, UNIT, clinic.owner(), "{\"version\":-1,\"integrationMode\":\"INTEGRATED\"}"), 400), "validation");
        assertType(problem(call(HttpMethod.PUT, UNIT, clinic.owner(), "not json"), 400), "malformed-request");
        assertType(problem(call(HttpMethod.PUT, UNIT, clinic.owner(), null), 400), "malformed-request");
    }

    @Test
    void aRequestThatTriesToChangeFefoNegativeStockOrTheClinicNameOrCodeIs400AndNothingChanges() throws Exception {
        Clinic clinic = pharmacyClinic();
        for (String extra : new String[] {"\"fefo\":false", "\"negativeStockBlocked\":false", "\"fefo\":true",
            "\"unitName\":\"Other name\"", "\"unitCode\":\"ZZZ\"", "\"unitId\":\"" + java.util.UUID.randomUUID() + "\"",
            "\"tenantId\":\"" + java.util.UUID.randomUUID() + "\""}) {
            JsonNode p = problem(call(HttpMethod.PUT, UNIT, clinic.owner(),
                    "{\"version\":0,\"integrationMode\":\"INTEGRATED\"," + extra + "}"), 400);
            assertType(p, "validation");
        }
        JsonNode u = ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200);
        assertThat(u.get("saved").asBoolean()).isFalse();
        assertThat(u.get("fefo").asBoolean()).isTrue();
        assertThat(u.get("negativeStockBlocked").asBoolean()).isTrue();
        assertThat(u.get("unitName").asText()).isEqualTo("Sahaj test clinic");
    }

    @Test
    void aSaveThatOmitsAFieldTheContractRequiresIs400AndDoesNotSilentlyClearTheSavedValues() throws Exception {
        // docs/api/pharmacy.yaml UnitSettingsInput: required [version, gstin, drugLicenceNo, drugLicenceValidTill, integrationMode]; null clears, a missing field is not null
        Clinic clinic = pharmacyClinic();
        save(clinic, body(0, GSTIN_OK, "TEST-DL-0001", todayIst().plusYears(1).toString(), "INTEGRATED"), 200);
        JsonNode p = problem(call(HttpMethod.PUT, UNIT, clinic.owner(), "{\"version\":1,\"integrationMode\":\"STANDALONE\"}"), 400);
        assertType(p, "validation");
        JsonNode u = ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200);
        assertThat(u.get("gstin").asText()).as("GSTIN must not be cleared by a request that did not send it").isEqualTo(GSTIN_OK);
        assertThat(u.get("drugLicenceNo").asText()).isEqualTo("TEST-DL-0001");
        assertThat(u.get("integrationMode").asText()).isEqualTo("INTEGRATED");
    }

    // ---------------- roles and tenants ----------------

    @Test
    void onlyTheOwnerChangesAndThePharmacistAndCentralStoreUserRead() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        save(clinic, body(0, GSTIN_OK, "TEST-DL-0001", null, "INTEGRATED"), 200);
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn centralUser = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[0]), centralUser, "CENTRAL_STORE_USER");
        for (Pn reader : new Pn[] {pharmacist, centralUser}) {
            JsonNode u = ok(HttpMethod.GET, UNIT, reader.auth(), null, 200);
            assertThat(u.get("gstin").asText()).as("documents need GSTIN and licence").isEqualTo(GSTIN_OK);
            assertThat(u.get("drugLicenceNo").asText()).isEqualTo("TEST-DL-0001");
        }
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "COUNSELLOR", "VISITING_DOCTOR", "STORE_STAFF"}) {
            Pn p = crew(clinic, role);
            assertType(problem(call(HttpMethod.GET, UNIT, p.auth(), null), 403), "forbidden");
            JsonNode put = problem(call(HttpMethod.PUT, UNIT, p.auth(), js(body(1, null, null, null, "STANDALONE"))), 403);
            assertType(put, "forbidden");
        }
        for (Pn reader : new Pn[] {pharmacist, centralUser}) {
            JsonNode put = problem(call(HttpMethod.PUT, UNIT, reader.auth(), js(body(1, null, null, null, "STANDALONE"))), 403);
            assertThat(put.get("detail").asText()).isEqualTo("Only the clinic owner can do this.");
        }
        assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("version").asLong()).as("unchanged").isOne();
    }

    @Test
    void anotherClinicNeverSeesMySettingsAndHasItsOwnVersionAndDefaults() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        save(a, body(0, GSTIN_OK, "A-LICENCE-1", null, "STANDALONE"), 200);
        JsonNode seenByB = ok(HttpMethod.GET, UNIT, b.owner(), null, 200);
        assertThat(seenByB.get("gstin").isNull()).isTrue();
        assertThat(seenByB.get("integrationMode").asText()).isEqualTo("INTEGRATED");
        assertThat(seenByB.get("version").asLong()).isZero();
        assertThat(seenByB.toString()).doesNotContain(GSTIN_OK).doesNotContain("A-LICENCE-1");
        // B saves with version 0 although A's unit is at 1: separate rows
        save(b, body(0, null, "B-LICENCE-1", null, "INTEGRATED"), 200);
        assertThat(ok(HttpMethod.GET, UNIT, a.owner(), null, 200).get("drugLicenceNo").asText()).isEqualTo("A-LICENCE-1");
        // the request has no unit or tenant id: one in the body is refused
        assertType(problem(call(HttpMethod.PUT, UNIT, b.owner(),
                "{\"version\":1,\"integrationMode\":\"INTEGRATED\",\"tenantId\":\"" + a.tenantId() + "\"}"), 400), "validation");
        problem(call(HttpMethod.GET, UNIT + "/" + a.tenantId(), b.owner(), null), 404);
    }

    // ---------------- optimistic locking ----------------

    @Test
    void aStaleVersionIs409SettingsChangedWithTheCurrentValuesAndNothingIsOverwritten() throws Exception {
        Clinic clinic = pharmacyClinic();
        save(clinic, body(0, GSTIN_OK, "TEST-DL-0001", null, "INTEGRATED"), 200);
        JsonNode p = problem(call(HttpMethod.PUT, UNIT, clinic.owner(), js(body(0, null, "OVERWRITE", null, "STANDALONE"))), 409);
        assertType(p, "settings-changed");
        JsonNode current = p.get("current");
        assertThat(current).as("the 409 carries the current record").isNotNull();
        assertThat(current.get("version").asLong()).isOne();
        assertThat(current.get("gstin").asText()).isEqualTo(GSTIN_OK);
        assertThat(current.get("drugLicenceNo").asText()).isEqualTo("TEST-DL-0001");
        JsonNode u = ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200);
        assertThat(u.get("drugLicenceNo").asText()).isEqualTo("TEST-DL-0001");
        assertThat(u.get("integrationMode").asText()).isEqualTo("INTEGRATED");
    }

    @Test
    void twoOwnersSavingAtTheSameMomentOneWinsAndTheOtherGets409SettingsChangedWithCurrent() throws Exception {
        Clinic clinic = pharmacyClinic();
        save(clinic, body(0, null, null, null, "INTEGRATED"), 200); // version 1, both owners hold it
        Pn second = crew(clinic, "OWNER", "Second owner");
        RequestPostProcessor[] owners = {clinic.owner(), second.auth()};
        String[] licences = {"OWNER-ONE-LIC", "OWNER-TWO-LIC"};
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> bodies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                Callable<String> attempt = () -> {
                    start.await();
                    var r = mvc.perform(MockMvcRequestBuilders.put(UNIT).with(owners[n]).contentType(MediaType.APPLICATION_JSON)
                            .content(js(body(1, null, licences[n], null, "INTEGRATED")))).andReturn().getResponse();
                    return r.getStatus() + "|" + r.getContentAsString();
                };
                bodies.add(pool.submit(attempt));
            }
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (Future<String> f : bodies) {
                answers.add(f.get());
            }
            long ok = answers.stream().filter(a -> a.startsWith("200|")).count();
            long conflict = answers.stream().filter(a -> a.startsWith("409|")).count();
            assertThat(ok).as(answers.toString()).isOne();
            assertThat(conflict).as(answers.toString()).isOne();
            String loser = answers.stream().filter(a -> a.startsWith("409|")).findFirst().orElseThrow();
            JsonNode problem = objectMapper.readTree(loser.substring(4));
            assertType(problem, "settings-changed");
            assertThat(problem.get("current").get("version").asLong()).isEqualTo(2);
            String winnerLicence = problem.get("current").get("drugLicenceNo").asText();
            assertThat(winnerLicence).isIn((Object[]) licences);
            assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("drugLicenceNo").asText()).isEqualTo(winnerLicence);
        } finally {
            pool.shutdownNow();
        }
    }
}
