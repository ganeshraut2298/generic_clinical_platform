package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.MasterItemRef;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * SAH-43-2 on the real database: the signed-in doctor's own quick options, ranked by how often and then how
 * recently the doctor used each item on signed visits, per master, per doctor, per clinic. The counts are written the
 * way the sign step of SAH-51 will write them, through {@code EmrUsageRecorder} inside a transaction (there is no
 * REST call that writes them). Expected orders are worked out here from the story, never copied from the query.
 * Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrQuickOptionsIT extends EmrSupport {

    private static final EmrMaster[] MASTERS_ALL = EmrMaster.values();

    private static Instant base() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.DAYS);
    }

    private static Instant hours(Instant base, int h) {
        return base.plus(h, ChronoUnit.HOURS);
    }

    // ---------------- who may ask ----------------

    @Test
    void aDoctorAVisitingDoctorAndAnOwnerWithDoctorDetailsGetTheirListForEveryMaster() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Test Visiting", "Dental", "BDS", "REG-" + shortId());
        Staff owner = ownerWithDoctorDetails(clinic, "General practice", "MBBS", "REG-" + shortId());
        for (Staff who : new Staff[] {doctor, visiting, owner}) {
            for (EmrMaster master : MASTERS_ALL) {
                JsonNode list = quick(clinic, who, master, null);
                assertThat(list.get("master").asText()).isEqualTo(master.key());
                assertThat(list.get("items").size()).as(who.role() + " " + master.key()).isEqualTo(10);
            }
        }
    }

    @Test
    void anOwnerWithoutDoctorDetailsIsRefusedWithDoctorDetailsMissingUntilTheyAddThem() throws Exception {
        Clinic clinic = bareOwnerClinic();
        for (EmrMaster master : MASTERS_ALL) {
            JsonNode problem = problemOf(HttpMethod.GET, QUICK + master.key(), clinic.owner(), null, 403,
                    "doctor-details-missing");
            assertThat(problem.get("detail").asText())
                    .isEqualTo("Add your doctor details in Team and access to write consultations.");
        }
        ownerWithDoctorDetails(clinic, "General practice", "MBBS", "REG-" + shortId());
        assertThat(get(QUICK + "complaints", clinic.owner()).get("items").size()).isEqualTo(10);
    }

    @Test
    void nurseFrontDeskBillingPharmacistAndCounsellorAreRefusedWith403() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            for (EmrMaster master : MASTERS_ALL) {
                problemOf(HttpMethod.GET, QUICK + master.key(), staff.token(clinic), null, 403, "forbidden");
            }
        }
    }

    @Test
    void theRoleInTheTokenIsNotTrustedAUserWhoIsNotADoctorAnymoreIsRefusedAtOnce() throws Exception {
        Clinic clinic = newClinic();
        Staff nurse = addNonDoctor(clinic, "NURSE");
        problemOf(HttpMethod.GET, QUICK + "complaints", nurse.tokenClaiming(clinic, "DOCTOR"), null, 403, "forbidden");

        Staff doctor = addDoctor(clinic, "General practice");
        assertThat(get(QUICK + "complaints", doctor.token(clinic)).get("items").size()).isEqualTo(10);
        commit(clinic.tenantId(), "update platform.membership set role = 'NURSE' where id = ?", doctor.membershipId());
        problemOf(HttpMethod.GET, QUICK + "complaints", doctor.token(clinic), null, 403, "forbidden");

        Staff gone = addDoctor(clinic, "General practice");
        setMembershipStatusOf(clinic, gone, "INACTIVE");
        problemOf(HttpMethod.GET, QUICK + "complaints", gone.token(clinic), null, 403, "forbidden");
    }

    // ---------------- parameters ----------------

    @Test
    void anUnknownMasterIsABadRequestAndTheLimitIsOneToFiftyWithTenAsTheDefault() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        for (String master : new String[] {"complaintz", "masters", "Complaints", "templates"}) {
            problemOf(HttpMethod.GET, QUICK + master, doctor.token(clinic), null, 400, "bad-request");
        }
        for (String limit : new String[] {"0", "-1", "51", "1000"}) {
            JsonNode problem = problemOf(HttpMethod.GET, QUICK + "complaints?limit=" + limit, doctor.token(clinic), null,
                    400, "validation");
            assertThat(fieldNames(problem)).contains("limit");
        }
        assertThat(call(HttpMethod.GET, QUICK + "complaints?limit=abc", doctor.token(clinic), null).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(quick(clinic, doctor, EmrMaster.COMPLAINTS, null).get("items").size()).isEqualTo(10);
        assertThat(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=1").get("items").size()).isEqualTo(1);
        assertThat(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=50").get("items").size()).isEqualTo(50);
        assertThat(quick(clinic, doctor, EmrMaster.DIAGNOSES, "limit=50").get("items").size()).isEqualTo(50);
        JsonNode badSpecialty = problemOf(HttpMethod.GET, QUICK + "complaints?specialty=ayur", doctor.token(clinic), null,
                400, "validation");
        assertThat(fieldNames(badSpecialty)).contains("specialty");
    }

    private static List<String> fieldNames(JsonNode problem) {
        return texts(problem.get("fieldErrors"), "field");
    }

    // ---------------- a doctor with no use yet ----------------

    @Test
    void aDoctorWithNoUseGetsEachMastersOwnOrderNeverAnEmptyList() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        for (EmrMaster master : MASTERS_ALL) {
            JsonNode list = quick(clinic, doctor, master, "limit=50");
            assertThat(keysOf(list)).as(master.key()).isEqualTo(itemKeys(master, 50));
            for (JsonNode item : list.get("items")) {
                assertThat(item.get("useCount").asInt()).isZero();
                assertThat(item.get("lastUsedAt").isNull()).isTrue();
                assertThat(item.get("name").asText()).isNotBlank();
                assertThat(item.get("itemKey").asText().length()).isBetween(1, 40);
            }
            JsonNode first = list.get("items").get(0);
            if (master == EmrMaster.DIAGNOSES) {
                assertThat(first.get("icd10Code").asText()).isEqualTo(first.get("itemKey").asText());
            } else {
                assertThat(first.get("icd10Code").isNull()).isTrue();
            }
            if (master == EmrMaster.MEDICINES) {
                assertThat(first.get("favourite").isBoolean()).isTrue();
                assertThat(first.get("form").isNull()).isFalse();
            } else {
                assertThat(first.get("favourite").isNull()).isTrue();
                assertThat(first.get("form").isNull()).isTrue();
            }
        }
        assertThat(usageRows(clinic)).as("reading never writes").isZero();
    }

    @Test
    void aSpecialtyOrdersOnlyTheNeverUsedTailAndHidesNothing() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        List<List<String>> complaints = globalRows(
                "select id::text, coalesce(specialty_hint, ''), sort_order from opd.emr_complaint");
        List<String> expected = complaints.stream()
                .sorted(Comparator.comparing((List<String> r) -> "dental".equals(r.get(1)) ? 0 : 1)
                        .thenComparingInt(r -> Integer.parseInt(r.get(2))))
                .map(r -> r.get(0)).toList();
        assertThat(keysOf(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=50&specialty=dental")))
                .isEqualTo(expected.subList(0, 50));

        // a used item stays first even when it is tagged for another specialty
        String used = expected.get(40);
        recordVisit(clinic, doctor.membershipId(), base(), ref(EmrMaster.COMPLAINTS, used));
        List<String> afterUse = keysOf(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=50&specialty=dental"));
        List<String> rest = new ArrayList<>(expected);
        rest.remove(used);
        assertThat(afterUse.get(0)).isEqualTo(used);
        assertThat(afterUse.subList(1, 50)).isEqualTo(rest.subList(0, 49));

        List<List<String>> diagnoses = globalRows("select code, coalesce(specialty, '') from opd.diagnosis "
                + "order by lower(name), code");
        List<String> diagnosisExpected = new ArrayList<>(diagnoses.stream().filter(r -> "paeds".equals(r.get(1)))
                .map(r -> r.get(0)).toList());
        diagnosisExpected.addAll(diagnoses.stream().filter(r -> !"paeds".equals(r.get(1))).map(r -> r.get(0)).toList());
        assertThat(keysOf(quick(clinic, doctor, EmrMaster.DIAGNOSES, "limit=50&specialty=paeds")))
                .isEqualTo(diagnosisExpected.subList(0, 50));
    }

    // ---------------- ranking ----------------

    @Test
    void usedItemsComeByCountThenRecencyThenTheUnusedInMasterOrderAndEachMasterIsRankedSeparately() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        Instant base = base();
        List<String> keys = itemKeys(EmrMaster.COMPLAINTS, 30);
        MasterItemRef a = ref(EmrMaster.COMPLAINTS, keys.get(10));
        MasterItemRef b = ref(EmrMaster.COMPLAINTS, keys.get(11));
        MasterItemRef c = ref(EmrMaster.COMPLAINTS, keys.get(12));
        MasterItemRef d = ref(EmrMaster.COMPLAINTS, keys.get(13));
        // A on 5 visits, B on 3 (the first three), C on 3 (the last three of its own, all later than B), D on 1
        assertThat(recordVisit(clinic, doctor.membershipId(), hours(base, 1), a, b)).isEqualTo(2);
        recordVisit(clinic, doctor.membershipId(), hours(base, 2), a, b);
        recordVisit(clinic, doctor.membershipId(), hours(base, 3), a, b);
        recordVisit(clinic, doctor.membershipId(), hours(base, 4), a, c);
        recordVisit(clinic, doctor.membershipId(), hours(base, 5), a, c);
        recordVisit(clinic, doctor.membershipId(), hours(base, 6), c);
        recordVisit(clinic, doctor.membershipId(), hours(base, 7), d);

        JsonNode list = quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=50");
        List<String> expected = new ArrayList<>(List.of(a.itemKey(), c.itemKey(), b.itemKey(), d.itemKey()));
        itemKeys(EmrMaster.COMPLAINTS, 50).stream().filter(k -> !expected.contains(k)).forEach(expected::add);
        assertThat(keysOf(list)).isEqualTo(expected.subList(0, 50));
        assertThat(list.get("items").get(0).get("useCount").asInt()).isEqualTo(5);
        assertThat(Instant.parse(list.get("items").get(0).get("lastUsedAt").asText())).isEqualTo(hours(base, 5));
        assertThat(list.get("items").get(1).get("useCount").asInt()).isEqualTo(3);
        assertThat(Instant.parse(list.get("items").get(1).get("lastUsedAt").asText())).isEqualTo(hours(base, 6));
        assertThat(list.get("items").get(2).get("useCount").asInt()).isEqualTo(3);
        assertThat(Instant.parse(list.get("items").get(2).get("lastUsedAt").asText())).isEqualTo(hours(base, 3));
        assertThat(list.get("items").get(3).get("useCount").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(4).get("useCount").asInt()).isZero();
        // at most N, and a small N cuts the used list, not the other way round
        assertThat(keysOf(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=3")))
                .containsExactly(a.itemKey(), c.itemKey(), b.itemKey());

        // the other masters are not touched by complaint use
        for (EmrMaster other : new EmrMaster[] {EmrMaster.DIAGNOSES, EmrMaster.INVESTIGATIONS, EmrMaster.ADVICE}) {
            assertThat(keysOf(quick(clinic, doctor, other, "limit=50"))).as(other.key()).isEqualTo(itemKeys(other, 50));
        }
    }

    @Test
    void everyMasterIsRankedByTheSameRuleSeparately() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        Instant base = base();
        for (EmrMaster master : MASTERS_ALL) {
            List<String> keys = itemKeys(master, 40);
            String twice = keys.get(25);
            String once = keys.get(5);
            recordVisit(clinic, doctor.membershipId(), hours(base, 1), ref(master, twice), ref(master, once));
            recordVisit(clinic, doctor.membershipId(), hours(base, 2), ref(master, twice));
            JsonNode list = quick(clinic, doctor, master, "limit=5");
            assertThat(list.get("items").get(0).get("itemKey").asText()).as(master.key()).isEqualTo(twice);
            assertThat(list.get("items").get(0).get("useCount").asInt()).isEqualTo(2);
            assertThat(list.get("items").get(1).get("itemKey").asText()).isEqualTo(once);
            assertThat(list.get("items").get(1).get("useCount").asInt()).isEqualTo(1);
        }
        assertThat(usageRows(clinic)).isEqualTo(10);
    }

    @Test
    void usedMedicinesThenTheClinicsFavouritesThenTheRestAndOnlyMedicinesCarryFormAndFavourite() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        List<String> base = itemKeys(EmrMaster.MEDICINES, 53);
        String used = base.get(10);
        String favourite = base.get(30);
        ok(HttpMethod.PUT, "/api/v1/opd/medicine-favourites/" + favourite, clinic.owner(), null, 204);
        recordVisit(clinic, doctor.membershipId(), base(), ref(EmrMaster.MEDICINES, used));

        List<String> expected = new ArrayList<>(List.of(used, favourite));
        base.stream().filter(k -> !expected.contains(k)).forEach(expected::add);
        JsonNode list = quick(clinic, doctor, EmrMaster.MEDICINES, "limit=50");
        assertThat(keysOf(list)).isEqualTo(expected.subList(0, 50));
        assertThat(list.get("items").get(0).get("useCount").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("favourite").asBoolean()).isFalse();
        assertThat(list.get("items").get(1).get("favourite").asBoolean()).isTrue();
        assertThat(list.get("items").get(1).get("useCount").asInt()).isZero();
        for (JsonNode item : list.get("items")) {
            assertThat(item.get("form").isNull()).isFalse();
            assertThat(item.get("favourite").isBoolean()).isTrue();
        }
    }

    @Test
    void theSameItemTwiceOnOneVisitCountsOnceAndAVisitCountsEachItemOnce() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        String key = itemKeys(EmrMaster.COMPLAINTS, 5).get(2);
        // a set cannot hold the same reference twice: two equal references are one
        Set<MasterItemRef> duplicates = new LinkedHashSet<>(List.of(ref(EmrMaster.COMPLAINTS, key), ref(EmrMaster.COMPLAINTS, key)));
        assertThat(duplicates).hasSize(1);
        assertThat(recordVisit(clinic, doctor.membershipId(), base(), ref(EmrMaster.COMPLAINTS, key),
                ref(EmrMaster.COMPLAINTS, key))).isEqualTo(1);
        assertThat(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=1").get("items").get(0).get("useCount").asInt())
                .isEqualTo(1);
        // one visit with items from three masters counts three items
        assertThat(recordVisit(clinic, doctor.membershipId(), base(), ref(EmrMaster.COMPLAINTS, key),
                ref(EmrMaster.DIAGNOSES, itemKeys(EmrMaster.DIAGNOSES, 1).get(0)),
                ref(EmrMaster.MEDICINES, itemKeys(EmrMaster.MEDICINES, 1).get(0)))).isEqualTo(3);
        assertThat(count(clinic, "select count(*) from opd.emr_item_usage where use_count <> 1 or item_key = ?", key))
                .isEqualTo(1);
    }

    @Test
    void textTheDoctorTypedAndItemsNotInTheirMasterAreNeverCountedStoredOrOffered() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        String complaint = itemKeys(EmrMaster.COMPLAINTS, 1).get(0);
        int counted = recordVisit(clinic, doctor.membershipId(), base(),
                ref(EmrMaster.COMPLAINTS, "Fever"),                         // typed text
                ref(EmrMaster.COMPLAINTS, UUID.randomUUID().toString()),    // looks right, is not in the master
                ref(EmrMaster.INVESTIGATIONS, complaint),                   // right id, wrong master
                ref(EmrMaster.DIAGNOSES, "Z99.99"),                         // a code that is not in the master
                ref(EmrMaster.DIAGNOSES, "viral fever"),                    // typed text
                ref(EmrMaster.MEDICINES, UUID.randomUUID().toString().toUpperCase()));
        assertThat(counted).isZero();
        assertThat(usageRows(clinic)).isZero();
        for (EmrMaster master : MASTERS_ALL) {
            for (JsonNode item : quick(clinic, doctor, master, "limit=50").get("items")) {
                assertThat(item.get("useCount").asInt()).isZero();
            }
        }
    }

    @Test
    void lastUsedKeepsTheLaterTimeEvenWhenAnEarlierVisitIsReportedAfterwards() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        MasterItemRef item = ref(EmrMaster.ADVICE, itemKeys(EmrMaster.ADVICE, 3).get(1));
        Instant later = base().plus(3, ChronoUnit.DAYS);
        Instant earlier = base();
        recordVisit(clinic, doctor.membershipId(), later, item);
        recordVisit(clinic, doctor.membershipId(), earlier, item);
        JsonNode first = quick(clinic, doctor, EmrMaster.ADVICE, "limit=1").get("items").get(0);
        assertThat(first.get("itemKey").asText()).isEqualTo(item.itemKey());
        assertThat(first.get("useCount").asInt()).isEqualTo(2);
        assertThat(Instant.parse(first.get("lastUsedAt").asText())).isEqualTo(later);
    }

    // ---------------- doctor, clinic ----------------

    @Test
    void twoDoctorsOfOneClinicEachSeeOnlyTheirOwnUseAndNobodyReadsAnotherDoctorsRanking() throws Exception {
        Clinic clinic = newClinic();
        Staff x = addDoctor(clinic, "General practice");
        Staff y = addDoctor(clinic, "General practice");
        Staff owner = ownerWithDoctorDetails(clinic, "General practice", "MBBS", "REG-" + shortId());
        List<String> keys = itemKeys(EmrMaster.COMPLAINTS, 40);
        for (int i = 1; i <= 3; i++) {
            recordVisit(clinic, x.membershipId(), hours(base(), i), ref(EmrMaster.COMPLAINTS, keys.get(30)));
        }
        recordVisit(clinic, y.membershipId(), base(), ref(EmrMaster.COMPLAINTS, keys.get(31)));

        JsonNode forX = quick(clinic, x, EmrMaster.COMPLAINTS, "limit=50");
        JsonNode forY = quick(clinic, y, EmrMaster.COMPLAINTS, "limit=50");
        assertThat(forX.get("items").get(0).get("itemKey").asText()).isEqualTo(keys.get(30));
        assertThat(forX.get("items").get(0).get("useCount").asInt()).isEqualTo(3);
        assertThat(forY.get("items").get(0).get("itemKey").asText()).isEqualTo(keys.get(31));
        assertThat(forY.get("items").get(0).get("useCount").asInt()).isEqualTo(1);
        assertThat(useCountOf(forY, keys.get(30))).isZero();
        assertThat(useCountOf(forX, keys.get(31))).isZero();

        // a request that names another doctor is ignored: the caller's own list comes back, owner included
        String others = "limit=50&doctorMembershipId=" + x.membershipId() + "&doctor=" + x.membershipId()
                + "&membershipId=" + x.membershipId();
        JsonNode ownerAsksForX = quick(clinic, owner, EmrMaster.COMPLAINTS, others);
        assertThat(keysOf(ownerAsksForX)).isEqualTo(itemKeys(EmrMaster.COMPLAINTS, 50));
        assertThat(useCountOf(ownerAsksForX, keys.get(30))).isZero();
        JsonNode yAsksForX = quick(clinic, y, EmrMaster.COMPLAINTS, others);
        assertThat(yAsksForX.get("items").get(0).get("itemKey").asText()).isEqualTo(keys.get(31));
        assertThat(useCountOf(yAsksForX, keys.get(30))).isZero();
    }

    private static int useCountOf(JsonNode list, String key) {
        for (JsonNode item : list.get("items")) {
            if (key.equals(item.get("itemKey").asText())) {
                return item.get("useCount").asInt();
            }
        }
        return -1;
    }

    @Test
    void aVisitingDoctorOfTwoClinicsHasTheirOwnCountsInEachAndNeitherShowsTheOthers() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID user = newClinic().ownerUserId(); // a third sign-up gives us a user who belongs to neither clinic
        Staff inA = addVisiting(a, user);
        Staff inB = addVisiting(b, user);
        assertThat(inA.userId()).isEqualTo(inB.userId());
        assertThat(inA.membershipId()).isNotEqualTo(inB.membershipId());
        List<String> keys = itemKeys(EmrMaster.COMPLAINTS, 20);
        recordVisit(a, inA.membershipId(), base(), ref(EmrMaster.COMPLAINTS, keys.get(15)));
        recordVisit(a, inA.membershipId(), hours(base(), 1), ref(EmrMaster.COMPLAINTS, keys.get(15)));
        for (int i = 0; i < 4; i++) {
            recordVisit(b, inB.membershipId(), hours(base(), i), ref(EmrMaster.COMPLAINTS, keys.get(16)));
        }
        recordVisit(b, inB.membershipId(), base(), ref(EmrMaster.COMPLAINTS, keys.get(15)));

        JsonNode inClinicA = quick(a, inA, EmrMaster.COMPLAINTS, "limit=50");
        JsonNode inClinicB = quick(b, inB, EmrMaster.COMPLAINTS, "limit=50");
        assertThat(useCountOf(inClinicA, keys.get(15))).isEqualTo(2);
        assertThat(useCountOf(inClinicA, keys.get(16))).isZero();
        assertThat(useCountOf(inClinicB, keys.get(16))).isEqualTo(4);
        assertThat(useCountOf(inClinicB, keys.get(15))).isEqualTo(1);
        assertThat(inClinicA.get("items").get(0).get("itemKey").asText()).isEqualTo(keys.get(15));
        assertThat(inClinicB.get("items").get(0).get("itemKey").asText()).isEqualTo(keys.get(16));
    }

    private Staff addVisiting(Clinic clinic, UUID userId) throws SQLException {
        addMembership(clinic.tenantId(), userId, "VISITING_DOCTOR");
        UUID membershipId = UUID.fromString(row(clinic, "select id::text from platform.membership where user_id = ?::uuid "
                + "and role = 'VISITING_DOCTOR'", userId).get(0));
        commit(clinic.tenantId(), "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, "
                + "registration_no) values (?, 'General practice', 0, 'MBBS', ?)", membershipId, "REG-" + shortId());
        return new Staff(membershipId, userId, "VISITING_DOCTOR");
    }

    @Test
    void anotherClinicNeverSeesTheCountsAndTheDatabaseHidesThemToo() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Staff doctorA = addDoctor(a, "General practice");
        Staff doctorB = addDoctor(b, "General practice");
        String key = itemKeys(EmrMaster.COMPLAINTS, 3).get(2);
        recordVisit(a, doctorA.membershipId(), base(), ref(EmrMaster.COMPLAINTS, key));
        assertThat(useCountOf(quick(b, doctorB, EmrMaster.COMPLAINTS, "limit=50"), key)).isZero();
        assertThat(useCountOf(quick(a, doctorA, EmrMaster.COMPLAINTS, "limit=50"), key)).isEqualTo(1);
        assertThat(usageRows(a)).isEqualTo(1);
        assertThat(usageRows(b)).isZero();
        assertThat(globalCount("select count(*) from opd.emr_item_usage")).as("no clinic bound sees nothing").isZero();
        // clinic B cannot name A's doctor to read A's rows either
        assertThat(count(b, "select count(*) from opd.emr_item_usage where doctor_membership_id = ?", doctorA.membershipId()))
                .isZero();
        // a token of clinic B with A's doctor user does not open A's data: the doctor is not a member of B
        problemOf(HttpMethod.GET, QUICK + "complaints", asUser(b.tenantId(), doctorA.userId(), "DOCTOR"), null, 403,
                "forbidden");
    }

    @Test
    void whatIsStoredHoldsNoPatientVisitOrTextColumn() throws Exception {
        List<String> columns = new ArrayList<>();
        globalRows("select column_name from information_schema.columns where table_schema = 'opd' "
                + "and table_name = 'emr_item_usage' order by ordinal_position").forEach(r -> columns.add(r.get(0)));
        assertThat(columns).containsExactlyInAnyOrder("tenant_id", "doctor_membership_id", "master", "item_key",
                "use_count", "last_used_at", "created_at", "updated_at");
    }

    // ---------------- the sign step contract ----------------

    @Test
    void theRecorderNeedsATransactionAndItsCountsRollBackWithIt() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        MasterItemRef item = ref(EmrMaster.COMPLAINTS, itemKeys(EmrMaster.COMPLAINTS, 1).get(0));
        assertThatThrownBy(() -> usageRecorder.recordSignedVisit(clinic.tenantId(), doctor.membershipId(), base(),
                Set.of(item))).isInstanceOf(IllegalTransactionStateException.class);

        Authentication auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        Object ignored = runAs(auth, () -> tx().execute(status -> {
            usageRecorder.recordSignedVisit(clinic.tenantId(), doctor.membershipId(), base(), Set.of(item));
            status.setRollbackOnly();
            return null;
        }));
        assertThat(ignored).isNull();
        assertThat(usageRows(clinic)).as("a signature that rolls back counts nothing").isZero();
    }

    @Test
    void visitsSignedAtTheSameMomentLoseNoCount() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        Staff other = addDoctor(clinic, "General practice");
        List<String> keys = itemKeys(EmrMaster.COMPLAINTS, 5);
        List<MasterItemRef> refs = new ArrayList<>();
        keys.forEach(k -> refs.add(ref(EmrMaster.COMPLAINTS, k)));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        Instant when = base();
        for (int i = 0; i < threads; i++) {
            boolean reversed = i % 2 == 1;
            UUID who = i % 4 == 3 ? other.membershipId() : doctor.membershipId();
            results.add(pool.submit(() -> {
                List<MasterItemRef> order = new ArrayList<>(refs);
                if (reversed) {
                    java.util.Collections.reverse(order);
                }
                start.await();
                return recordVisit(clinic, who, when, order.toArray(new MasterItemRef[0]));
            }));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(5);
        }
        pool.shutdown();
        // 8 visits: 2 of them by the second doctor (i = 3 and 7), 6 by the first
        for (String key : keys) {
            assertThat(useCountOf(quick(clinic, doctor, EmrMaster.COMPLAINTS, "limit=50"), key)).as(key).isEqualTo(6);
            assertThat(useCountOf(quick(clinic, other, EmrMaster.COMPLAINTS, "limit=50"), key)).as(key).isEqualTo(2);
        }
    }

    // ---------------- the index ----------------

    @Test
    void theRankedReadUsesTheRankingIndexAndNeedsNoSort() throws Exception {
        Clinic clinic = newClinic();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        List<String> plan = db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, use_count, last_used_at) "
                    + "select d.doc, 'complaints', gen_random_uuid()::text, 1 + (g % 9), now() - (g || ' minutes')::interval "
                    + "from (values (?::uuid), (?::uuid), (?::uuid)) d(doc), generate_series(1, 1500) g", d1, d2, d3);
            DbAccess.execute(c, "set local enable_seqscan = off");
            DbAccess.execute(c, "set local enable_bitmapscan = off");
            List<String> lines = new ArrayList<>();
            allRowsOf(c, "explain select item_key, use_count, last_used_at from opd.emr_item_usage where tenant_id = '"
                    + clinic.tenantId() + "' and doctor_membership_id = '" + d1 + "' and master = 'complaints' "
                    + "order by use_count desc, last_used_at desc limit 10").forEach(lines::add);
            return lines;
        });
        String text = String.join("\n", plan);
        assertThat(text).as("plan:\n" + text).contains("ix_emr_item_usage_ranking");
        assertThat(text).as("the index already delivers the order, so there is no sort step:\n" + text).doesNotContain("Sort");
    }

    @Test
    void theRankedReadWithTheMasterJoinStillStartsFromTheRankingIndex() throws Exception {
        Clinic clinic = newClinic();
        UUID d1 = UUID.randomUUID();
        List<String> plan = db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, use_count, last_used_at) "
                    + "select ?::uuid, 'complaints', m.id::text, 1 + (row_number() over ())::int % 5, now() "
                    + "from opd.emr_complaint m", d1);
            DbAccess.execute(c, "set local enable_seqscan = off");
            DbAccess.execute(c, "set local enable_bitmapscan = off");
            List<String> lines = new ArrayList<>();
            allRowsOf(c, "explain select m.id::text, m.name, u.use_count, u.last_used_at from opd.emr_item_usage u "
                    + "join opd.emr_complaint m on m.id::text = u.item_key where u.tenant_id = '"
                    + clinic.tenantId() + "' and u.doctor_membership_id = '" + d1 + "' and u.master = 'complaints' "
                    + "order by u.use_count desc, u.last_used_at desc, lower(m.name), m.id::text limit 10").forEach(lines::add);
            return lines;
        });
        assertThat(String.join("\n", plan)).as("plan").contains("ix_emr_item_usage_ranking");
    }

    private static List<String> allRowsOf(java.sql.Connection c, String sql, Object... params) throws SQLException {
        List<String> lines = new ArrayList<>();
        allRows(c, sql, params).forEach(r -> lines.add(r.get(0)));
        return lines;
    }
}
