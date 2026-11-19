package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.pharmacy.it.PharmacyStoreProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-028 D4 layer 3, the store guard, on the real stack (security chain, gate, guard, problem handler with the denial audit, PostgreSQL). The probe implements PharmacyApi, so the handler applies to it as to every pharmacy controller (fix round 2, DEC-071).
 * An unmapped, foreign, unknown or switched-off store answers 404; a mapped caller (or the owner) without the capability
 * answers 403 {@code store-permission-denied} and an audit row with ids only. The operation is the test-only
 * {@link PharmacyStoreProbe}, because WP-A has no product operation that needs a capability (see that class). Test plan:
 * docs/test-plans/SAH-78.md (T78-4-WS-*, T78-GUARD-*).
 */
@OnTestDatabase
@Import(PharmacyStoreProbe.class)
class PharmacyStoreGuardIT extends Sah78Support {

    private static final String NOT_FOUND_TEXT = "We could not find that.";
    private static final String DENIED_TEXT = "You cannot do this in this store.";

    private static String probe(Object storeId, String capability) {
        return PH + "/it-probe/stores/" + storeId + "/" + capability;
    }

    private List<List<String>> denials(Clinic clinic) throws Exception {
        return auditRowsOf(clinic, "PHARMACY_STORE_ACCESS_DENIED");
    }

    private void assertNotFound(JsonNode p) {
        assertType(p, "not-found");
        assertThat(p.get("status").asInt()).isEqualTo(404);
        assertThat(p.get("detail").asText()).isEqualTo(NOT_FOUND_TEXT);
    }

    @Test
    void anUnmappedForeignUnknownOrSwitchedOffStoreAnswersTheSame404ForANonOwnerAndConfirmsNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode off = createStore(clinic, "Old Store", "OLD", "SUB_STORE");
        Clinic other = pharmacyClinic();
        JsonNode foreign = centralStore(other);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(off), nurse, "STORE_USER");
        ok(HttpMethod.POST, STORES + "/" + idOf(off) + "/switch-off", clinic.owner(), null, 200);
        // the mapped store works
        ok(HttpMethod.GET, probe(idOf(stores[1]), "RAISE_INDENT"), nurse.auth(), null, 200);
        JsonNode unmapped = problem(call(HttpMethod.GET, probe(idOf(ward), "RAISE_INDENT"), nurse.auth(), null), 404);
        JsonNode unknown = problem(call(HttpMethod.GET, probe(UUID.randomUUID(), "RAISE_INDENT"), nurse.auth(), null), 404);
        JsonNode foreignStore = problem(call(HttpMethod.GET, probe(idOf(foreign), "RAISE_INDENT"), nurse.auth(), null), 404);
        JsonNode switchedOff = problem(call(HttpMethod.GET, probe(idOf(off), "RAISE_INDENT"), nurse.auth(), null), 404);
        for (JsonNode p : new JsonNode[] {unmapped, unknown, foreignStore, switchedOff}) {
            assertNotFound(p);
        }
        assertThat(unmapped.get("title")).isEqualTo(unknown.get("title"));
        assertThat(unmapped.get("type")).isEqualTo(foreignStore.get("type"));
        assertThat(denials(clinic)).as("a 404 is not a denial: nothing is audited").isEmpty();
        assertThat(denials(other)).isEmpty();
    }

    @Test
    void theOwnerReachesEveryActiveStoreWithoutAMappingButNotSwitchedOffOrForeignOnesAndNotPharmacistOnlyActions() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode off = createStore(clinic, "Old Store", "OLD", "SUB_STORE");
        ok(HttpMethod.POST, STORES + "/" + idOf(off) + "/switch-off", clinic.owner(), null, 200);
        Clinic other = pharmacyClinic();
        JsonNode foreign = centralStore(other);
        for (JsonNode s : stores) {
            for (String capability : new String[] {"RAISE_INDENT", "APPROVE", "RECEIVE", "CONSUME_RETURN", "UPLOAD_OPENING_STOCK"}) {
                ok(HttpMethod.GET, probe(idOf(s), capability), clinic.owner(), null, 200);
            }
        }
        assertNotFound(problem(call(HttpMethod.GET, probe(idOf(off), "RAISE_INDENT"), clinic.owner(), null), 404));
        assertNotFound(problem(call(HttpMethod.GET, probe(idOf(foreign), "RAISE_INDENT"), clinic.owner(), null), 404));
        assertNotFound(problem(call(HttpMethod.GET, probe(UUID.randomUUID(), "RAISE_INDENT"), clinic.owner(), null), 404));
        // Schedule X is for pharmacists only: the owner is refused and the refusal is audited
        JsonNode denied = problem(call(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE_SCHEDULE_X"), clinic.owner(), null), 403);
        assertType(denied, "store-permission-denied");
        assertThat(denied.get("detail").asText()).isEqualTo(DENIED_TEXT);
        assertThat(denials(clinic)).hasSize(1);
    }

    @Test
    void aMappedCallerWithoutTheCapabilityGets403StorePermissionDeniedAndOneAuditRowWithIdsOnly() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE", "Nina Nurse");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        ok(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE"), nurse.auth(), null, 200);
        assertThat(denials(clinic)).as("an allowed call writes no denial").isEmpty();
        JsonNode p = problem(call(HttpMethod.GET, probe(idOf(stores[1]), "APPROVE"), nurse.auth(), null), 403);
        assertType(p, "store-permission-denied");
        assertThat(p.get("detail").asText()).isEqualTo(DENIED_TEXT);
        assertThat(p.get("title").asText()).isEqualTo("Not allowed in this store");
        List<List<String>> rows = denials(clinic);
        assertThat(rows).hasSize(1);
        List<String> row = rows.get(0);
        assertThat(row.get(0)).isEqualTo("PHARMACY_STORE");
        assertThat(row.get(1)).as("the entity is the store").isEqualTo(idOf(stores[1]));
        assertThat(row.get(2)).as("no before values").isNull();
        assertThat(row.get(4)).as("the actor is the caller").isNotNull();
        JsonNode after = objectMapper.readTree(row.get(3));
        List<String> keys = new ArrayList<>();
        after.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("ids and the capability name only").containsExactlyInAnyOrder("membershipId", "capability");
        assertThat(after.get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(after.get("capability").asText()).isEqualTo("APPROVE");
        assertThat(row.get(3)).doesNotContain("Nina").doesNotContain(nurse.mobile()).doesNotContain("Main Pharmacy");
        // a second refusal is a second row
        problem(call(HttpMethod.GET, probe(idOf(stores[1]), "UPLOAD_OPENING_STOCK"), nurse.auth(), null), 403);
        assertThat(denials(clinic)).hasSize(2);
    }

    @Test
    void theCapabilitiesFollowTheStoreRoleTheTeamRoleAndTheStoreFlagOnEveryCall() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode pharmacy = createStore(clinic, "Main Pharmacy", "PHARM", "PHARMACY");
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        mapStaff(clinic, idOf(pharmacy), pharmacist, "STORE_USER");
        mapStaff(clinic, idOf(pharmacy), nurse, "STORE_USER");
        mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        // central store user: receives and uploads, does not raise indents or approve
        ok(HttpMethod.GET, probe(idOf(central), "RECEIVE"), keeper.auth(), null, 200);
        ok(HttpMethod.GET, probe(idOf(central), "UPLOAD_OPENING_STOCK"), keeper.auth(), null, 200);
        assertType(problem(call(HttpMethod.GET, probe(idOf(central), "RAISE_INDENT"), keeper.auth(), null), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.GET, probe(idOf(central), "RECEIVE_SCHEDULE_X"), keeper.auth(), null), 403), "store-permission-denied");
        // the pharmacist alone gets Schedule X
        ok(HttpMethod.GET, probe(idOf(pharmacy), "RECEIVE_SCHEDULE_X"), pharmacist.auth(), null, 200);
        assertType(problem(call(HttpMethod.GET, probe(idOf(pharmacy), "RECEIVE_SCHEDULE_X"), nurse.auth(), null), 403), "store-permission-denied");
        // consumption follows the store's flag: a Store user at a Pharmacy cannot, at a Ward can
        assertType(problem(call(HttpMethod.GET, probe(idOf(pharmacy), "CONSUME_RETURN"), nurse.auth(), null), 403), "store-permission-denied");
        ok(HttpMethod.GET, probe(idOf(ward), "CONSUME_RETURN"), nurse.auth(), null, 200);
        // the owner switches the flag on for the Pharmacy: the next call sees it (nothing is cached)
        JsonNode fresh = ok(HttpMethod.GET, STORES + "/" + idOf(pharmacy), clinic.owner(), null, 200);
        // StoreUpdate requires all eight fields; Map.of cannot hold the two explicit nulls
        java.util.Map<String, Object> update = new java.util.LinkedHashMap<>();
        update.put("version", fresh.get("version").asLong());
        update.put("name", "Main Pharmacy");
        update.put("code", "PHARM");
        update.put("type", "PHARMACY");
        update.put("saleAllowed", true);
        update.put("consumptionAllowed", true);
        update.put("hodMembershipId", null);
        update.put("delegate", null);
        ok(HttpMethod.PUT, STORES + "/" + idOf(pharmacy), clinic.owner(), js(update), 200);
        ok(HttpMethod.GET, probe(idOf(pharmacy), "CONSUME_RETURN"), nurse.auth(), null, 200);
        // a mapping removed a moment ago is gone at once: 404, not 403
        JsonNode mapping = mappingOf(clinic, idOf(ward), nurse);
        ok(HttpMethod.POST, staffUrl(idOf(ward), idOf(mapping)) + "/remove", clinic.owner(), null, 200);
        assertNotFound(problem(call(HttpMethod.GET, probe(idOf(ward), "CONSUME_RETURN"), nurse.auth(), null), 404));
    }

    @Test
    void aCallerWithNoMappingAtAllAndAModuleOffClinicAreRefusedBeforeTheGuardIsReached() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn plain = crew(clinic, "STORE_STAFF");
        JsonNode p = problem(call(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE"), plain.auth(), null), 403);
        assertType(p, "forbidden");
        assertThat(p.get("detail").asText()).isEqualTo(NO_ACCESS_TEXT);
        pharmacyOn(clinic, false);
        assertType(problem(call(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE"), clinic.owner(), null), 403), "module-off");
        assertThat(denials(clinic)).isEmpty();
    }

    @Test
    void aSwitchedOffTeamMemberIsRefusedWith401BeforeTheGuard() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        ok(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE"), nurse.auth(), null, 200);
        ok(HttpMethod.POST, TEAM + "/" + nurse.membershipId() + "/deactivate", clinic.owner(), null, 200);
        problem(call(HttpMethod.GET, probe(idOf(stores[1]), "RECEIVE"), nurse.auth(), null), 401);
    }

    @Test
    void anotherClinicsMappingGivesNoAccessHereAndNoTenantIdFromTheRequestIsTrusted() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        JsonNode[] storesA = centralAndPharmacy(a);
        centralAndPharmacy(b);
        Pn nurseB = crew(b, "NURSE");
        JsonNode storeOfB = createStore(b, "Ward B", "WB", "WARD");
        mapStaff(b, idOf(storeOfB), nurseB, "STORE_USER");
        RequestPostProcessor viaHeader = request -> {
            request.addHeader("X-Tenant-Id", a.tenantId().toString());
            return request;
        };
        // the caller's own store works; A's store is a 404 for B's nurse, header or not
        ok(HttpMethod.GET, probe(idOf(storeOfB), "RAISE_INDENT"), nurseB.auth(), null, 200);
        assertNotFound(problem(call(HttpMethod.GET, probe(idOf(storesA[1]), "RAISE_INDENT") + "?tenantId=" + a.tenantId(),
                request -> viaHeader.postProcessRequest(nurseB.auth().postProcessRequest(request)), null), 404));
    }

    // ---------------- the denial audit survives a roll back (security L4, code review F-2; since fix round 2 the problem handler writes the row after the transaction, N-1) ----------------

    private static String probeVia(String shape, Object storeId, String capability) {
        return PH + "/it-probe/" + shape + "/stores/" + storeId + "/" + capability;
    }

    private String storeName(Clinic clinic, Object storeId) throws Exception {
        return values(clinic, "select name from pharmacy.store where id = ?::uuid", storeId.toString()).get(0);
    }

    @Test
    void theDenialAuditRowSurvivesWhenTheCallersTransactionRollsBackAndTheCallersOwnWriteIsGone() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        String original = storeName(clinic, idOf(stores[1]));
        // control: with the capability the same endpoint commits its marker write, so the roll back below is really observed
        ok(HttpMethod.GET, probeVia("rolled-back", idOf(stores[1]), "RECEIVE"), nurse.auth(), null, 200);
        assertThat(storeName(clinic, idOf(stores[1]))).as("control: the marker was committed").isEqualTo(original + PharmacyStoreProbe.MARKER);
        assertThat(denials(clinic)).isEmpty();
        // refused: the plain @Transactional (no noRollbackFor) rolls its marker write back, the handler writes the audit row afterwards, so it is kept
        JsonNode p = problem(call(HttpMethod.GET, probeVia("rolled-back", idOf(stores[1]), "APPROVE"), nurse.auth(), null), 403);
        assertType(p, "store-permission-denied");
        assertThat(storeName(clinic, idOf(stores[1]))).as("the caller's own write was rolled back")
                .isEqualTo(original + PharmacyStoreProbe.MARKER);
        List<List<String>> rows = denials(clinic);
        assertThat(rows).as("the denial audit row is committed although the request rolled back").hasSize(1);
        assertThat(rows.get(0).get(1)).isEqualTo(idOf(stores[1]));
        assertThat(objectMapper.readTree(rows.get(0).get(3)).get("capability").asText()).isEqualTo("APPROVE");
        assertThat(rows.get(0).get(3)).doesNotContain("Nina").doesNotContain(nurse.mobile());
    }

    @Test
    void theDenialAuditRowIsKeptAlsoForACallerInAReadOnlyTransactionAndForEachRefusal() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        assertType(problem(call(HttpMethod.GET, probeVia("read-only", idOf(stores[1]), "APPROVE"), nurse.auth(), null), 403), "store-permission-denied");
        assertThat(denials(clinic)).as("a read-only caller still keeps the row").hasSize(1);
        assertType(problem(call(HttpMethod.GET, probeVia("rolled-back", idOf(stores[1]), "UPLOAD_OPENING_STOCK"), nurse.auth(), null), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.GET, probeVia("read-only", idOf(stores[1]), "UPLOAD_OPENING_STOCK"), nurse.auth(), null), 403), "store-permission-denied");
        assertThat(denials(clinic)).hasSize(3);
        // the audit row is bound to the right clinic: another clinic sees none
        Clinic other = pharmacyClinic();
        assertThat(denials(other)).isEmpty();
    }

    @Test
    void aStoreThatIsNotFoundWritesNoDenialRowEvenInTheRolledBackShape() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        assertNotFound(problem(call(HttpMethod.GET, probeVia("rolled-back", idOf(stores[0]), "RECEIVE"), nurse.auth(), null), 404));
        assertNotFound(problem(call(HttpMethod.GET, probeVia("rolled-back", UUID.randomUUID(), "RECEIVE"), nurse.auth(), null), 404));
        assertThat(denials(clinic)).isEmpty();
    }
}
