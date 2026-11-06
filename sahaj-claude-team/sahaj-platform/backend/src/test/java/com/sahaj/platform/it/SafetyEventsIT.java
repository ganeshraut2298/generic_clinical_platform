package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ActorDirectoryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-33 AC16, AC17 and ADR-007 D13: the clinical audit trail (GET /clinical-events) on the real {@code sahaj_test}
 * database: keyset paging on (at, seq) with ties on the time, the first page of 14 and the later pages of 50, a bad limit
 * or cursor, the nurse's scope, names read when the page is built (patient name, bed and staff name, "Team member" when
 * unknown), the entries the actions write, no way to edit or delete, and the staff name lookup of the platform module on
 * real data. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyEventsIT extends SafetyTestSupport {

    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";

    @Autowired
    private ActorDirectoryPort actors;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"));
    }

    private List<String> allIdsNewestFirst(Clinic clinic) throws Exception {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select id::text from ipd.clinical_event order by at desc, seq desc");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asText()));
        return out;
    }

    // ---------------- the first page and the later pages ----------------

    @Test
    void theFirstPageHoldsFourteenEntriesNewestFirstAndEarlierEntriesLoadFiftyAtATimeWithNoGapAndNoRepeat() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), Instant.now().minusSeconds(600), 35);
        insertEvents(w.clinic(), adm.id(), w.doctor().userId(), Instant.now().minusSeconds(60), 35);
        List<String> expected = allIdsNewestFirst(w.clinic());
        assertThat(expected).hasSize(70);

        JsonNode first = eventsOf(w.owner().auth(), null);
        assertThat(first.get("items")).hasSize(14);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 14));
        assertThat(ids(eventsOf(w.owner().auth(), "limit=14"))).isEqualTo(ids(first));
        // newest first: the 35 newer entries come before the 35 older ones, and within one time the higher seq first
        Instant previous = Instant.MAX;
        for (JsonNode item : first.get("items")) {
            Instant at = instant(item.get("at"));
            assertThat(at).isBeforeOrEqualTo(previous);
            previous = at;
        }

        JsonNode second = eventsOf(w.owner().auth(), "limit=50&cursor=" + first.get("nextCursor").asText());
        assertThat(second.get("items")).hasSize(50);
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(14, 64));
        assertThat(second.get("nextCursor").isNull()).isFalse();
        JsonNode third = eventsOf(w.owner().auth(), "limit=50&cursor=" + second.get("nextCursor").asText());
        assertThat(ids(third)).containsExactlyElementsOf(expected.subList(64, 70));
        assertThat(third.get("nextCursor").isNull()).as("no older entries: no cursor").isTrue();
        assertThat(ids(eventsOf(w.owner().auth(), "limit=50")).size()).isEqualTo(50);
        assertThat(eventsOf(w.owner().auth(), "limit=1").get("items")).hasSize(1);
        for (Staff s : new Staff[] {w.doctor(), w.nurse()}) {
            assertThat(ids(eventsOf(s.auth(), null))).as(s.role()).containsExactlyElementsOf(expected.subList(0, 14));
        }
    }

    @Test
    void exactlyFourteenEntriesHaveNoCursorAndFifteenHaveOneThatLeadsToTheLastEntry() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), Instant.now().minusSeconds(10), 14);
        JsonNode page = eventsOf(w.owner().auth(), null);
        assertThat(page.get("items")).hasSize(14);
        assertThat(page.get("nextCursor").isNull()).isTrue();
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), Instant.now().minusSeconds(20), 1);
        JsonNode fifteen = eventsOf(w.owner().auth(), null);
        assertThat(fifteen.get("items")).hasSize(14);
        assertThat(fifteen.get("nextCursor").isNull()).isFalse();
        JsonNode rest = eventsOf(w.owner().auth(), "cursor=" + fifteen.get("nextCursor").asText());
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("nextCursor").isNull()).isTrue();
        // an empty clinic
        Clinic empty = hospital();
        JsonNode none = eventsOf(empty.owner(), null);
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("nextCursor").isNull()).isTrue();
        assertThat(instant(none.get("asOf"))).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
    }

    @Test
    void manyEntriesWithTheSameTimeAreNeitherSkippedNorRepeatedWhateverThePageSize() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        Instant tie = Instant.now().minusSeconds(120);
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), tie, 40);
        // one real save writes two entries with one instant as well
        save(w.clinic(), adm.id(), medium3());
        List<String> expected = allIdsNewestFirst(w.clinic());
        assertThat(expected).hasSize(42);
        for (int size : new int[] {1, 5, 7, 14, 41, 50}) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode page = eventsOf(w.owner().auth(), "limit=" + size + (cursor == null ? "" : "&cursor=" + cursor));
                assertThat(page.get("items").size()).isLessThanOrEqualTo(size);
                seen.addAll(ids(page));
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
                pages++;
                assertThat(pages).as("limit " + size).isLessThan(100);
            } while (cursor != null);
            assertThat(seen).as("limit " + size).containsExactlyElementsOf(expected);
            assertThat(new HashSet<>(seen)).hasSize(seen.size());
        }
        // a new entry arriving between two pages shifts nothing: the next page continues from the cursor
        JsonNode first = eventsOf(w.owner().auth(), "limit=10");
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), Instant.now(), 3);
        JsonNode second = eventsOf(w.owner().auth(), "limit=10&cursor=" + first.get("nextCursor").asText());
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(10, 20));
    }

    // ---------------- bad limit and cursor ----------------

    @Test
    void aLimitThatIsNotAWholeNumberFromOneToFiftyAndACursorThisServerDidNotMakeAre400() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        insertEvents(w.clinic(), adm.id(), w.owner().userId(), Instant.now().minusSeconds(5), 20);
        for (String limit : new String[] {"0", "51", "-1", "abc", "1.5", "", "1000", "99999999999", "+5", "14x", "1e1", "0x10"}) {
            for (Staff s : new Staff[] {w.owner(), w.nurse()}) {
                JsonNode p = problem(call(HttpMethod.GET, EVENTS + "?limit=" + limit, s.auth(), null), 400);
                assertType(p, "validation");
                assertThat(fieldNames(p)).as("limit=" + limit).containsExactly("limit");
                assertThat(fieldMessage(p, "limit")).isEqualTo(RELOAD);
            }
        }
        for (String ok : new String[] {"1", "14", "50"}) {
            assertThat(call(HttpMethod.GET, EVENTS + "?limit=" + ok, w.owner().auth(), null).andReturn().getResponse().getStatus())
                    .as("limit=" + ok).isEqualTo(200);
        }
        String good = eventsOf(w.owner().auth(), "limit=5").get("nextCursor").asText();
        String[] badCursors = {"!!", "abc", "a%20b", "a+b/c=", "AAAA", "x".repeat(65), good + "!", good.substring(0, good.length() - 1) + "~",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("12.x".getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("-5.1".getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("1234567890123456789.1".getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("123".getBytes())};
        for (String cursor : badCursors) {
            JsonNode p = problem(call(HttpMethod.GET, EVENTS + "?cursor=" + cursor, w.owner().auth(), null), 400);
            assertType(p, "validation");
            assertThat(fieldNames(p)).as("cursor=" + cursor).containsExactly("cursor");
            assertThat(fieldMessage(p, "cursor")).isEqualTo(RELOAD);
        }
        assertThat(call(HttpMethod.GET, EVENTS + "?cursor=" + good, w.owner().auth(), null).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        // the order of the checks: the role first (403 for a front desk person with a bad limit)
        assertType(problem(call(HttpMethod.GET, EVENTS + "?limit=abc", w.clinic().as("FRONT_DESK"), null), 403), "forbidden");
    }

    // ---------------- who sees which entries ----------------

    @Test
    void ownerAndDoctorSeeEveryEntryAndTheNurseSeesHerOwnAndThoseOfAdmittedPatients() throws Exception {
        Ward w = ward();
        Adm admitted = admitOne(w.clinic());
        Adm gone = admitOne(w.clinic());
        Instant base = Instant.now().minusSeconds(300);
        insertEvents(w.clinic(), admitted.id(), w.owner().userId(), base, 2);                       // visible: admitted patient
        insertEvents(w.clinic(), null, w.owner().userId(), base.plusSeconds(1), 3);                 // hidden: no patient, not hers
        insertEvents(w.clinic(), null, w.nurse().userId(), base.plusSeconds(2), 4);                 // visible: hers
        insertEvents(w.clinic(), gone.id(), w.owner().userId(), base.plusSeconds(3), 5);            // hidden once discharged
        insertEvents(w.clinic(), gone.id(), w.nurse().userId(), base.plusSeconds(4), 6);            // visible: hers
        insertEvents(w.clinic(), admitted.id(), w.doctor().userId(), base.plusSeconds(5), 1);       // visible: admitted patient
        signAndDischarge(w.clinic(), gone.id());

        List<String> all = allIdsNewestFirst(w.clinic());
        List<String> hers = db().inTenant(w.clinic().tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select e.id::text from ipd.clinical_event e left join ipd.admission a "
                    + "on a.tenant_id = e.tenant_id and a.id = e.admission_id where e.actor = ?::uuid or a.status = 'ADMITTED' "
                    + "order by e.at desc, e.seq desc")) {
                ps.setString(1, w.nurse().userId().toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
        assertThat(all.size()).isGreaterThan(hers.size());
        for (Staff s : new Staff[] {w.owner(), w.doctor()}) {
            assertThat(ids(eventsOf(s.auth(), "limit=50"))).as(s.role()).containsExactlyElementsOf(all.subList(0, Math.min(50, all.size())));
        }
        List<String> seen = ids(eventsOf(w.nurse().auth(), "limit=50"));
        assertThat(seen).containsExactlyElementsOf(hers.subList(0, Math.min(50, hers.size())));
        // the rule in words: 2 + 4 + 6 + 1 inserted entries are hers (plus the signing and discharge write none)
        assertThat(hers.size()).isEqualTo(2 + 4 + 6 + 1);
        for (String hidden : db().inTenant(w.clinic().tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select id::text from ipd.clinical_event where (admission_id is null and actor = ?::uuid) "
                    + "or (admission_id = ?::uuid and actor = ?::uuid)")) {
                ps.setString(1, w.owner().userId().toString());
                ps.setString(2, gone.id());
                ps.setString(3, w.owner().userId().toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        })) {
            assertThat(seen).doesNotContain(hidden);
        }
        // a doctor whose membership became a nurse's gets the nurse's scope at once
        try {
            setMembershipRole(w.clinic(), w.doctor().userId(), "NURSE");
            // his own entry (1) and the entries of the admitted patient (2 + 1 of his own, already counted): 3 in all
            assertThat(ids(eventsOf(w.doctor().auth(), "limit=50"))).hasSize(3);
        } finally {
            setMembershipRole(w.clinic(), w.doctor().userId(), "DOCTOR");
        }
    }

    // ---------------- names are read when the page is built ----------------

    @Test
    void thePatientNameTheBedAndTheStaffNameAreReadAtDisplayTimeAndNeverStoredInTheEntry() throws Exception {
        Ward w = ward();
        Adm adm = admitNamed(w.clinic(), "Meera Kulkarni " + uniqueMobile().substring(5));
        String id = raise(w.clinic(), adm, medium3());
        ackOk(w.doctor().auth(), id, 0);
        respondOk(w.owner().auth(), id, 1, "NO_CHANGE", PLAN);

        JsonNode items = eventsOf(w.nurse().auth(), null).get("items");
        JsonNode closed = items.get(0);
        JsonNode acked = items.get(1);
        assertThat(closed.get("kind").asText()).isEqualTo("esc");
        assertThat(closed.get("text").asText()).isEqualTo("Escalation closed: Reviewed, no change needed");
        assertThat(closed.get("actorName").asText()).isEqualTo(w.owner().name());
        assertThat(closed.get("admissionId").asText()).isEqualTo(adm.id());
        assertThat(closed.get("patientName").asText()).isEqualTo(adm.name());
        assertThat(closed.get("bedCode").asText()).isEqualTo(adm.bedCode());
        assertThat(acked.get("text").asText()).isEqualTo("Acknowledged escalation after 0 min");
        assertThat(acked.get("actorName").asText()).isEqualTo(w.doctor().name());
        assertThat(instant(closed.get("at"))).isAfterOrEqualTo(instant(acked.get("at")));
        assertThat(items.get(2).get("text").asText()).startsWith("Raised early warning escalation");
        assertThat(items.get(3).get("kind").asText()).isEqualTo("info");
        // nothing identifying is stored in the entries
        assertThat(String.join("|", eventLines(w.clinic(), adm.id()))).doesNotContain(adm.name()).doesNotContain("Meera")
                .doesNotContain(w.doctor().name()).doesNotContain(w.owner().name()).doesNotContain(PLAN);

        // rename the patient and a staff member: the next read shows the new names, the stored entries are unchanged
        List<String> storedBefore = eventLines(w.clinic(), adm.id());
        ok(HttpMethod.PATCH, PATIENTS + "/" + adm.patientId(), w.clinic().owner(), "{\"name\":\"Meera Joshi\"}", 200);
        commitAsTenant(w.clinic().tenantId(), "update platform.membership set display_name = ? where user_id = ?::uuid",
                "Dr Renamed Doctor", w.doctor().userId().toString());
        JsonNode renamed = eventsOf(w.owner().auth(), null).get("items");
        assertThat(renamed.get(0).get("patientName").asText()).isEqualTo("Meera Joshi");
        assertThat(renamed.get(1).get("actorName").asText()).isEqualTo("Dr Renamed Doctor");
        assertThat(renamed.get(0).get("actorName").asText()).isEqualTo(w.owner().name());
        assertThat(eventLines(w.clinic(), adm.id())).isEqualTo(storedBefore);
        // the board reads names at display time too
        JsonNode row = escRow(boardOf(w.nurse().auth()), id);
        assertThat(row.get("patientName").asText()).isEqualTo("Meera Joshi");
        assertThat(row.get("acknowledgedByName").asText()).isEqualTo("Dr Renamed Doctor");
    }

    @Test
    void anUnknownUserIsTeamMemberAnEntryWithNoPatientHasNoPatientAndNoBedAndADischargedPatientKeepsHisLastBed() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        Instant at = Instant.now().minusSeconds(30);
        insertEvents(w.clinic(), null, UUID.randomUUID(), at, 1);
        insertEvents(w.clinic(), adm.id(), UUID.randomUUID(), at.plusSeconds(1), 1);
        JsonNode items = eventsOf(w.owner().auth(), null).get("items");
        JsonNode withPatient = items.get(0);
        JsonNode without = items.get(1);
        assertThat(without.get("actorName").asText()).isEqualTo("Team member");
        assertThat(without.get("admissionId").isNull()).isTrue();
        assertThat(without.get("patientName").isNull()).isTrue();
        assertThat(without.get("bedCode").isNull()).isTrue();
        assertThat(withPatient.get("actorName").asText()).isEqualTo("Team member");
        assertThat(withPatient.get("patientName").asText()).isEqualTo(adm.name());
        assertThat(withPatient.get("bedCode").asText()).isEqualTo(adm.bedCode());
        signAndDischarge(w.clinic(), adm.id());
        JsonNode after = eventsOf(w.owner().auth(), null).get("items");
        assertThat(after.get(0).get("patientName").asText()).isEqualTo(adm.name());
        assertThat(after.get(0).get("bedCode").asText()).as("the last bed of a discharged patient").isEqualTo(adm.bedCode());
    }

    @Test
    void anotherClinicsEntriesAreNeverListedAndTheEntriesHoldNoPlanNameOrNote() throws Exception {
        Ward a = ward();
        Ward b = ward();
        Adm adm = admitNamed(a.clinic(), "Sunita Deshmukh " + uniqueMobile().substring(5));
        String id = raise(a.clinic(), adm, noted(high7(), "ZXQ private note"));
        respondOk(a.doctor().auth(), id, 0, "RAPID_RESPONSE", PLAN);
        insertEvents(b.clinic(), null, b.owner().userId(), Instant.now().minusSeconds(5), 2);
        String a1 = eventsOf(a.owner().auth(), "limit=50").toString();
        String b1 = eventsOf(b.owner().auth(), "limit=50").toString();
        assertThat(eventsOf(a.owner().auth(), "limit=50").get("items")).hasSize(3);
        assertThat(eventsOf(b.owner().auth(), "limit=50").get("items")).hasSize(2);
        assertThat(b1).doesNotContain(adm.name()).doesNotContain(id).doesNotContain("Raised early warning");
        assertThat(a1).doesNotContain(PLAN).doesNotContain("ZXQ").doesNotContain(adm.mobile());
        for (JsonNode item : eventsOf(a.owner().auth(), "limit=50").get("items")) {
            assertThat(item.get("text").asText().length()).isBetween(1, 300);
            assertThat(item.get("text").asText()).doesNotContain("Sunita").doesNotContain("Deshmukh");
        }
    }

    // ---------------- no way to edit or delete ----------------

    @Test
    void theApiHasNoWayToAddEditOrDeleteAnEntry() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        raise(w.clinic(), adm, medium3());
        String entry = eventsOf(w.owner().auth(), null).get("items").get(0).get("id").asText();
        long before = rows(w.clinic(), "clinical_event");
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            for (String url : new String[] {EVENTS, EVENTS + "/" + entry}) {
                int status = call(method, url, w.owner().auth(), "{\"text\":\"changed\"}").andReturn().getResponse().getStatus();
                assertThat(status).as(method + " " + url).isBetween(400, 499);
            }
        }
        assertThat(rows(w.clinic(), "clinical_event")).isEqualTo(before);
        assertThat(count(w.clinic(), "select count(*) from ipd.clinical_event where text = 'changed'")).isZero();
        // even in Read-only, which blocks writes with its own answer, the log can be read
        expireTrial(w.clinic());
        assertThat(eventsOf(w.nurse().auth(), null).get("items")).isNotEmpty();
    }

    // ---------------- the staff names of the platform module on real data ----------------

    @Test
    void theActorDirectoryGivesTheDisplayNameElseTheFullNameOfEachUserOfTheClinicAndNobodyElse() throws Exception {
        Ward w = ward();
        Ward other = ward();
        UUID unknown = UUID.randomUUID();
        commitAsTenant(w.clinic().tenantId(), "update platform.membership set display_name = ? where user_id = ?::uuid",
                "Dr Directory Name", w.doctor().userId().toString());
        Map<UUID, String> names = names(w.clinic(), w.owner().userId(),
                List.of(w.owner().userId(), w.doctor().userId(), w.nurse().userId(), unknown, other.owner().userId()));
        assertThat(names).containsEntry(w.doctor().userId(), "Dr Directory Name");
        assertThat(names).containsEntry(w.nurse().userId(), w.nurse().name());
        assertThat(names).containsEntry(w.owner().userId(), nameOf(w.clinic(), w.owner().userId()));
        assertThat(names).as("an unknown user is absent (the caller shows 'Team member')").doesNotContainKey(unknown);
        assertThat(names).as("another clinic's user is not found in this clinic").doesNotContainKey(other.owner().userId());
        assertThat(names).hasSize(3);
        assertThat(names(w.clinic(), w.owner().userId(), List.of())).isEmpty();
        assertThat(names(w.clinic(), w.owner().userId(), List.of(unknown))).isEmpty();
        Set<UUID> many = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            many.add(UUID.randomUUID());
        }
        many.add(w.nurse().userId());
        assertThat(names(w.clinic(), w.owner().userId(), many)).containsOnlyKeys(w.nurse().userId());
        // the lookup joins a transaction that is already open: without one it refuses
        assertThatThrownBy(() -> actors.displayNames(w.clinic().tenantId(), List.of(w.nurse().userId())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private Map<UUID, String> names(Clinic clinic, UUID asUser, java.util.Collection<UUID> userIds) {
        return runAs(authentication(clinic.tenantId(), asUser, "OWNER"),
                () -> new TransactionTemplate(transactionManager).execute(s -> actors.displayNames(clinic.tenantId(), userIds)));
    }
}
