package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.pharmacy.it.PharmacyStoreProbe;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Code review N-1 and security L7 (SAH-78 fix round 2, DEC-071; ADR-028 R5-1): a store denial must never need a second
 * pooled connection while the request holds its first. The request pool of this class is cut to THREE connections (a property
 * override of this class only, so it has its own Spring context), and many more denied calls than that run at the same moment
 * from mapped people who lack the capability. Before the fix the guard wrote the audit row in a REQUIRES_NEW transaction while
 * the service transaction still held a connection, so three or more parallel denials held all three connections and waited for
 * a fourth until the connection timeout: a 500 or 503 and a lost audit row. Now the handler writes the row after the
 * transaction ended.
 *
 * <p>Expected: no connection timeout (every call answers 403 {@code store-permission-denied}), one
 * {@code PHARMACY_STORE_ACCESS_DENIED} row per denial with the clinic's tenant, entity = the store id and {@code after} = the
 * membership id and the capability only; a 404 (unmapped, foreign or unknown store) writes no audit row. Test plan:
 * docs/test-plans/SAH-78.md (T78-N1-*).
 */
@OnTestDatabase
@Import(PharmacyStoreProbe.class)
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1",
    "spring.datasource.hikari.connection-timeout=6000"})
class PharmacyStoreDenialPoolIT extends Sah78BSupport {

    private static final int POOL = 3;
    private static final String DENIED_TEXT = "You cannot do this in this store.";

    @Autowired
    private DataSource dataSource;

    /** One request of a wave. */
    private record Req(HttpMethod method, String path, RequestPostProcessor auth, String body) {
    }

    /** Sends every request at the same moment (one thread each behind a start latch) and returns the responses in order. */
    private List<MvcResult> wave(List<Req> requests) throws Exception {
        int n = requests.size();
        ExecutorService threads = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Req r : requests) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call(r.method(), r.path(), r.auth(), r.body()).andReturn();
                }));
            }
            ready.await();
            start.countDown();
            List<MvcResult> out = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            threads.shutdownNow();
        }
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private record Room(Clinic clinic, JsonNode central, JsonNode pharmacy, JsonNode ward, List<Pn> nurses) {

        String pharmacyId() {
            return pharmacy.get("id").asText();
        }
    }

    /** A clinic with three stores and {@code people} nurses, each mapped (Store user) to the pharmacy store only. */
    private Room room(int people) throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        List<Pn> nurses = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            Pn nurse = crew(clinic, "NURSE");
            mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
            nurses.add(nurse);
        }
        return new Room(clinic, stores[0], stores[1], ward, nurses);
    }

    private void assertDenied(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("a denial answers 403, never a 500 or 503 from the pool: " + r.getResponse().getContentAsString())
                .isEqualTo(403);
        JsonNode p = body(r);
        assertType(p, "store-permission-denied");
        assertThat(p.get("detail").asText()).isEqualTo(DENIED_TEXT);
    }

    /** Every audit row is for this store, has no before values, and holds the membership id and the capability only. */
    private Map<String, Integer> assertAuditRows(Room room, String storeId, Set<String> capabilities, int expectedRows) throws Exception {
        List<List<String>> rows = auditRowsOf(room.clinic(), "PHARMACY_STORE_ACCESS_DENIED");
        assertThat(rows).as("one audit row per denial").hasSize(expectedRows);
        Map<String, Integer> perMembership = new HashMap<>();
        for (List<String> row : rows) {
            assertThat(row.get(0)).isEqualTo("PHARMACY_STORE");
            assertThat(row.get(1)).as("the entity is the store").isEqualTo(storeId);
            assertThat(row.get(2)).as("no before values").isNull();
            assertThat(row.get(4)).as("the actor is the caller").isNotNull();
            JsonNode after = objectMapper.readTree(row.get(3));
            List<String> keys = new ArrayList<>();
            after.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("ids and the capability name only").containsExactlyInAnyOrder("membershipId", "capability");
            assertThat(capabilities).contains(after.get("capability").asText());
            perMembership.merge(after.get("membershipId").asText(), 1, Integer::sum);
            assertThat(row.get(3)).doesNotContain("Main Pharmacy").doesNotContain("Test nurse");
        }
        // the rows carry the clinic's tenant: the same rows are counted by tenant id as sahaj_app with that tenant bound
        assertThat(count(room.clinic(), "select count(*) from platform.audit_event where action = 'PHARMACY_STORE_ACCESS_DENIED' "
                + "and tenant_id = ?::uuid", room.clinic().tenantId().toString())).isEqualTo(expectedRows);
        return perMembership;
    }

    private void assertPoolIsIdle() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        for (int i = 0; i < 20 && hikari.getHikariPoolMXBean().getActiveConnections() > 0; i++) {
            Thread.sleep(100); // a connection is given back a moment after the answer is written
        }
        assertThat(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection()).as("no thread still waits for a connection").isZero();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).as("every connection was given back").isZero();
    }

    @Test
    void thePoolOfThisClassHasThreeConnections() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(POOL);
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(6000);
    }

    @Test
    void manyMoreParallelDenialsThanThePoolHasConnectionsAllAnswer403AndWriteOneAuditRowEach() throws Exception {
        Room room = room(12); // 12 people, a pool of 3
        String pharmacy = room.pharmacyId();
        // 12 callers list the imports (needs UPLOAD_OPENING_STOCK) and the same 12 write rows to an import (the same capability,
        // refused inside a service transaction that has done a read): 24 denials at the same moment on 3 connections
        List<Req> requests = new ArrayList<>();
        for (Pn nurse : room.nurses()) {
            requests.add(new Req(HttpMethod.GET, importsUrl(pharmacy), nurse.auth(), null));
        }
        for (Pn nurse : room.nurses()) {
            requests.add(new Req(HttpMethod.POST, importUrl(pharmacy, UUID.randomUUID()) + "/lines", nurse.auth(),
                    js(rows(row(1, "PHARM", "G-000101", "B1", farExpiry(), "1", "1", "1")))));
        }
        List<MvcResult> answers = wave(requests);
        for (MvcResult r : answers) {
            assertDenied(r);
        }
        Map<String, Integer> perPerson = assertAuditRows(room, pharmacy, Set.of("UPLOAD_OPENING_STOCK"), 24);
        assertThat(new TreeSet<>(perPerson.keySet())).as("every one of the 12 callers is named").isEqualTo(
                new TreeSet<>(room.nurses().stream().map(n -> n.membershipId().toString()).toList()));
        assertThat(perPerson.values()).as("two denials each").containsOnly(2);
        assertThat(auditRowsOf(pharmacyClinicOther(), "PHARMACY_STORE_ACCESS_DENIED")).as("another clinic sees none").isEmpty();
        assertThat(pharmacyRows(room.clinic(), "opening_stock_import")).as("the refused calls wrote nothing").isZero();
        assertPoolIsIdle();
    }

    @Test
    void theThreeTransactionShapesOfTheProbeEachKeepTheirAuditRowUnderParallelDenials() throws Exception {
        Room room = room(6);
        String pharmacy = room.pharmacyId();
        String original = values(room.clinic(), "select name from pharmacy.store where id = ?::uuid", pharmacy).get(0);
        List<Req> requests = new ArrayList<>();
        String[] shapes = {"", "rolled-back/", "read-only/"}; // noRollbackFor, plain @Transactional, readOnly
        for (String shape : shapes) {
            for (Pn nurse : room.nurses()) {
                requests.add(new Req(HttpMethod.GET, PH + "/it-probe/" + shape + "stores/" + pharmacy + "/APPROVE", nurse.auth(), null));
            }
        }
        List<MvcResult> answers = wave(requests); // 18 at the same moment on 3 connections
        for (MvcResult r : answers) {
            assertDenied(r);
        }
        Map<String, Integer> perPerson = assertAuditRows(room, pharmacy, Set.of("APPROVE"), 18);
        assertThat(perPerson.values()).as("three denials each, one per shape").containsOnly(3);
        assertThat(values(room.clinic(), "select name from pharmacy.store where id = ?::uuid", pharmacy).get(0))
                .as("the rolled-back shape wrote nothing that stayed").isEqualTo(original);
        assertPoolIsIdle();
    }

    @Test
    void notFoundStoresWriteNoAuditRowEvenMixedWithDenialsInTheSameParallelWave() throws Exception {
        Room room = room(6);
        Clinic other = pharmacyClinic();
        JsonNode foreign = centralStore(other);
        String pharmacy = room.pharmacyId();
        String ward = idOf(room.ward()); // the nurses are not mapped to it
        List<Req> requests = new ArrayList<>();
        for (Pn nurse : room.nurses()) {
            requests.add(new Req(HttpMethod.GET, importsUrl(pharmacy), nurse.auth(), null));              // mapped, no capability: 403
            requests.add(new Req(HttpMethod.GET, importsUrl(ward), nurse.auth(), null));                  // not mapped: 404
            requests.add(new Req(HttpMethod.GET, importsUrl(idOf(foreign)), nurse.auth(), null));         // another clinic's store: 404
            requests.add(new Req(HttpMethod.GET, importsUrl(UUID.randomUUID()), nurse.auth(), null));     // unknown id: 404
            requests.add(new Req(HttpMethod.GET, PH + "/it-probe/rolled-back/stores/" + ward + "/RECEIVE", nurse.auth(), null)); // 404 in a rolled-back shape
        }
        List<MvcResult> answers = wave(requests);
        int denied = 0;
        int notFound = 0;
        for (int i = 0; i < answers.size(); i++) {
            MvcResult r = answers.get(i);
            if (i % 5 == 0) {
                assertDenied(r);
                denied++;
            } else {
                assertThat(r.getResponse().getStatus()).as("request " + i + ": " + r.getResponse().getContentAsString()).isEqualTo(404);
                assertType(body(r), "not-found");
                notFound++;
            }
        }
        assertThat(denied).isEqualTo(6);
        assertThat(notFound).isEqualTo(24);
        assertAuditRows(room, pharmacy, Set.of("UPLOAD_OPENING_STOCK"), 6); // 6 denials, 24 not-found answers wrote nothing
        assertThat(auditRowsOf(other, "PHARMACY_STORE_ACCESS_DENIED")).as("the foreign clinic's store was never touched").isEmpty();
        assertPoolIsIdle();
    }

    @Test
    void theOwnerDeniedAScheduleXCapabilityInParallelIsAuditedToo() throws Exception {
        // the owner reaches every store but not pharmacist-only capabilities: a denial for a person who is not "mapped"
        Room room = room(0);
        String pharmacy = room.pharmacyId();
        List<Req> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(new Req(HttpMethod.GET, PH + "/it-probe/stores/" + pharmacy + "/RECEIVE_SCHEDULE_X", room.clinic().owner(), null));
        }
        for (MvcResult r : wave(requests)) {
            assertDenied(r);
        }
        Map<String, Integer> perPerson = assertAuditRows(room, pharmacy, Set.of("RECEIVE_SCHEDULE_X"), 8);
        assertThat(perPerson).hasSize(1);
        assertPoolIsIdle();
    }

    /** A second clinic whose audit must stay empty. */
    private Clinic pharmacyClinicOther() throws Exception {
        return pharmacyClinic();
    }
}
