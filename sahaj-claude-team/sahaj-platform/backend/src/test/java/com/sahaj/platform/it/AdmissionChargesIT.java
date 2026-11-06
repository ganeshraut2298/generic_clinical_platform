package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-25 extra charges on the real {@code sahaj_test} database: the ten services, grouped lines and the multiplier,
 * the 50-row cap, error cases and their order, roles, the append-only table (privilege, column grants, triggers,
 * composite foreign key), audit and privacy, the patient access log, row level security, and the review-fix
 * regressions. Test plan: docs/test-plans/SAH-25.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class AdmissionChargesIT extends IpdBillingSupport {

    private String chargesUrl(String admissionId) {
        return ADMISSIONS + "/" + admissionId + "/charges";
    }

    private org.springframework.test.web.servlet.ResultActions postCharge(Clinic clinic, String role, String admissionId,
                                                                          String body) throws Exception {
        return call(HttpMethod.POST, chargesUrl(admissionId), clinic.as(role), body);
    }

    private static String key(String serviceKey) {
        return "{\"serviceKey\":\"" + serviceKey + "\"}";
    }

    private long basePaise(String[] auditRow) throws Exception {
        return objectMapper.readTree(auditRow[3]).get("basePaise").asLong();
    }

    private long chargeRows(Clinic clinic) throws SQLException {
        return rows(clinic, "admission_charge");
    }

    // ---------------- module state ----------------

    @Test
    void ipdOffBothNewEndpointsAre403ModuleOffForEveryRoleAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        String id = UUID.randomUUID().toString();
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            JsonNode p = problem(call(HttpMethod.GET, CHARGE_SERVICES, clinic.as(role), null), 403);
            if ("OWNER".equals(role)) {
                assertType(p, "module-off");
            }
        }
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            JsonNode p = problem(postCharge(clinic, role, id, key("ecg")), 403);
            if ("OWNER".equals(role)) {
                assertType(p, "module-off");
            }
        }
        assertThat(chargeRows(clinic)).isZero();
        assertThat(ipdAudits(clinic)).isZero();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        ok(HttpMethod.GET, CHARGE_SERVICES, clinic.owner(), null, 200);
    }

    @Test
    void readonlyAddingACharge409IpdReadonlyBeforeAnyOtherCheckAndNothingChanges() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "ecg");
        expireTrial(clinic);
        long rows = chargeRows(clinic);
        long audits = ipdAudits(clinic);

        assertThat(ok(HttpMethod.GET, CHARGE_SERVICES, clinic.owner(), null, 200)).hasSize(10);
        JsonNode d = detail(clinic, id);
        assertThat(d.get("canAddCharge").asBoolean()).isFalse();
        assertLine(d.get("bill"), "ECG", 30_000, 1);
        String[][] attempts = {{id, key("ecg")}, {id, key("bogus")}, {UUID.randomUUID().toString(), key("cbc")},
                {UUID.randomUUID().toString(), key("bogus")}};
        for (String role : CHARGERS) {
            for (String[] a : attempts) {
                JsonNode p = problem(postCharge(clinic, role, a[0], a[1]), 409);
                assertType(p, "ipd-readonly");
                assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
            }
        }
        assertThat(chargeRows(clinic)).isEqualTo(rows);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
    }

    // ---------------- AC7 the ten services, grouping, multiplier ----------------

    @Test
    void eachOfTheTenServicesAddsOneGroupedLineAtItsBasePriceOnAGeneralBed() throws Exception {
        Clinic clinic = hospital();
        JsonNode admitted = quickAdmit(clinic);
        String id = admitted.get("id").asText();
        long expectedTotal = DAY_ONE_GENERAL;
        List<String> expectedNames = new ArrayList<>(List.of("Bed · General", "Doctor visits · Dr Asha", "Nursing care"));
        for (Object[] s : SERVICES) {
            JsonNode d = charge(clinic, id, (String) s[0]);
            JsonNode bill = d.get("bill");
            expectedNames.add((String) s[1]);
            expectedTotal += (Long) s[2];
            assertThat(lineNames(bill)).as((String) s[0]).containsExactlyElementsOf(expectedNames);
            assertLine(bill, (String) s[1], (Long) s[2], 1);
            assertThat(bill.get("totalPaise").asLong()).isEqualTo(expectedTotal);
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(expectedTotal);
            assertBillAddsUp(bill);
            assertThat(d.get("canAddCharge").asBoolean()).isTrue();
            // the response is the whole admission, not just the bill
            assertThat(d.get("id").asText()).isEqualTo(id);
            assertThat(d.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        }
        assertThat(expectedTotal).isEqualTo(160_000 + 385_000);
        assertThat(chargeRows(clinic)).isEqualTo(10);
        // the rows hold the key, name, base price, quantity 1 and the adder
        for (Object[] s : SERVICES) {
            assertThat(values(clinic, "select service_name, unit_paise::text, qty::text, added_by::text "
                    + "from ipd.admission_charge where admission_id = ?::uuid and service_key = ?", id, s[0]))
                    .containsExactly((String) s[1], String.valueOf(s[2]), "1", clinic.ownerUserId().toString());
        }
        assertThat(detail(clinic, id).get("bill").get("totalPaise").asLong()).isEqualTo(expectedTotal);
    }

    @Test
    void theCategoryMultiplierScalesEachExtraChargeUnitButTheRowKeepsTheBasePrice() throws Exception {
        Clinic clinic = hospital();
        // semi 1.25, pvt 1.5, icu 2.0
        Object[][] cases = {{"semi", 1_200_000L, 125L}, {"pvt", 1_500_000L, 150L}, {"icu", 2_000_000L, 200L}};
        for (Object[] c : cases) {
            String id = admit(clinic, (String) c[0], "Self", (Long) c[1]).get("id").asText();
            for (Object[] s : SERVICES) {
                JsonNode bill = charge(clinic, id, (String) s[0]).get("bill");
                long unit = Math.round((Long) s[2] * (Long) c[2] / 100.0);
                assertLine(bill, (String) s[1], unit, 1);
                assertBillAddsUp(bill);
            }
            assertThat(values(clinic, "select unit_paise::text from ipd.admission_charge where admission_id = ?::uuid "
                    + "and service_key = 'visit'", id)).containsExactly("50000");
        }
        // a rate change after the charge was added changes the line (computed on read, base price stored)
        String id = admit(clinic, "gen", "Self", 1_000_000).get("id").asText();
        assertLine(charge(clinic, id, "xray").get("bill"), "Chest X-ray", 45_000, 1);
        setCategory(clinic, "gen", 80_000, "1.5");
        assertLine(detail(clinic, id).get("bill"), "Chest X-ray", 67_500, 1);
        setCategory(clinic, "gen", 80_000, "0.5");
        assertLine(detail(clinic, id).get("bill"), "Chest X-ray", 22_500, 1);
        assertThat(values(clinic, "select unit_paise::text from ipd.admission_charge where admission_id = ?::uuid", id))
                .containsExactly("45000");
    }

    @Test
    void quantityAccumulatesInOneLinePerServiceInOrderOfTheFirstCharge() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "ecg");
        charge(clinic, id, "cbc");
        charge(clinic, id, "ecg");
        JsonNode bill = charge(clinic, id, "ecg").get("bill");
        assertThat(lineNames(bill)).containsExactly("Bed · General", "Doctor visits · Dr Asha", "Nursing care", "ECG",
                "CBC");
        assertLine(bill, "ECG", 30_000, 3);
        assertLine(bill, "CBC", 30_000, 1);
        assertThat(billLine(bill, "ECG").get("amountPaise").asLong()).isEqualTo(90_000);
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL + 120_000);
        assertThat(chargeRows(clinic)).as("one row per add").isEqualTo(4);
        assertThat(count(clinic, "select count(*) from ipd.admission_charge where qty <> 1")).isZero();
        // the extra doctor visit is its own line, apart from the daily doctor visits
        JsonNode withVisit = charge(clinic, id, "visit").get("bill");
        assertLine(withVisit, "Extra doctor visit", 50_000, 1);
        assertLine(withVisit, "Doctor visits · Dr Asha", 50_000, 1);
    }

    @Test
    void extraJsonFieldsUnitPaiseQtyTenantIdAndAddedByAreIgnoredAndTheServerValuesAreStored() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        String body = "{\"serviceKey\":\"ecg\",\"unitPaise\":1,\"qty\":50,\"tenantId\":\"" + other.tenantId()
                + "\",\"addedBy\":\"" + other.ownerUserId() + "\",\"servicePaise\":1,\"serviceName\":\"Free\","
                + "\"admissionId\":\"" + UUID.randomUUID() + "\"}";
        JsonNode d = ok(HttpMethod.POST, chargesUrl(id), clinic.as("NURSE"), body, 200);

        // One ECG at the catalogue price, quantity 1: the client values changed nothing.
        assertLine(d.get("bill"), "ECG", 30_000, 1);
        assertThat(d.get("bill").get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL + 30_000);
        assertThat(chargeRows(clinic)).isOne();
        assertThat(values(clinic, "select service_key || '|' || service_name || '|' || unit_paise || '|' || qty "
                + "|| '|' || tenant_id::text || '|' || added_by::text || '|' || admission_id::text "
                + "from ipd.admission_charge"))
                .containsExactly("ecg|ECG|30000|1|" + clinic.tenantId() + "|" + clinic.ownerUserId() + "|" + id);
        // Nothing was written for the other clinic, and the audit names the caller, not the body's addedBy.
        assertThat(chargeRows(other)).isZero();
        List<String[]> audit = audit(clinic, "IPD_CHARGE_ADDED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(basePaise(audit.get(0))).isEqualTo(30_000L);
    }

    @Test
    void chargesOfOneAdmissionNeverAppearOnAnotherAdmissionInTheSameClinic() throws Exception {
        Clinic clinic = hospital();
        String a = quickAdmit(clinic).get("id").asText();
        String b = quickAdmit(clinic).get("id").asText();
        charge(clinic, a, "oxygen");
        assertThat(lineNames(detail(clinic, b).get("bill"))).doesNotContain("Oxygen (per day)");
        assertThat(row(list(clinic), b).get("runningBillPaise").asLong()).isEqualTo(DAY_ONE_GENERAL);
        assertThat(row(list(clinic), a).get("runningBillPaise").asLong()).isEqualTo(DAY_ONE_GENERAL + 80_000);
    }

    @Test
    void chargesAreBilledEveryDayNotMultipliedByBillDays() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "ivfluid");
        backdate(clinic, id, istMidnight(istToday().minusDays(2)).plusSeconds(3600));
        JsonNode bill = detail(clinic, id).get("bill");
        assertThat(bill.get("billDays").asInt()).isEqualTo(3);
        assertLine(bill, "IV fluids (per bottle)", 35_000, 1);
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL * 3 + 35_000);
    }

    // ---------------- the 50-row cap ----------------

    @Test
    void theFiftyFirstChargeOfTheSameServiceIs400AndNothingIsWrittenButOtherServicesAndAdmissionsStillWork()
            throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        String other = quickAdmit(clinic).get("id").asText();
        JsonNode last = null;
        for (int i = 0; i < 50; i++) {
            last = charge(clinic, id, "ecg");
        }
        assertLine(last.get("bill"), "ECG", 30_000, 50);
        assertThat(billLine(last.get("bill"), "ECG").get("amountPaise").asLong()).isEqualTo(1_500_000);
        long audits = audits(clinic, "IPD_CHARGE_ADDED");
        assertThat(audits).isEqualTo(50);

        JsonNode p = problem(postCharge(clinic, "OWNER", id, key("ecg")), 400);
        assertFieldError(p, "serviceKey");
        assertThat(p.get("fieldErrors").get(0).get("message").asText())
                .isEqualTo("At most 50 charges of the same service can be added to one admission");
        assertThat(count(clinic, "select count(*) from ipd.admission_charge where admission_id = ?::uuid and "
                + "service_key = 'ecg'", id)).isEqualTo(50);
        assertThat(audits(clinic, "IPD_CHARGE_ADDED")).isEqualTo(audits);
        // another service of the same admission, and the same service on another admission, are fine
        assertLine(charge(clinic, id, "cbc").get("bill"), "CBC", 30_000, 1);
        assertLine(charge(clinic, other, "ecg").get("bill"), "ECG", 30_000, 1);
    }

    @Test
    void fourParallelAddsWithTwoSlotsLeftGiveExactlyTwoOkAndTwo400() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        for (int i = 0; i < 48; i++) {
            charge(clinic, id, "nebulisation");
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> attempt = () -> {
                    start.await();
                    return mvc.perform(post(chargesUrl(id)).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                            .content(key("nebulisation"))).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 200, 400, 400);
        assertThat(count(clinic, "select count(*) from ipd.admission_charge where admission_id = ?::uuid", id)).isEqualTo(50);
    }

    // ---------------- 400 / 404 / 409 ----------------

    @Test
    void unknownMissingOrMalformedServiceKeysGet400WithAFieldErrorAndWriteNothing() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        long audits = ipdAudits(clinic);
        String[] bodies = {key("bogus"), key(""), key(" "), key("VISIT"), key("ecg "), key("Ecg"), key("visit;drop"),
                "{\"serviceKey\":null}", "{}", "{\"service\":\"ecg\"}", "{\"serviceKey\":\"" + "x".repeat(300) + "\"}"};
        for (String b : bodies) {
            JsonNode p = problem(postCharge(clinic, "OWNER", id, b), 400);
            assertFieldError(p, "serviceKey");
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isEqualTo("Choose a service from the list");
        }
        problem(postCharge(clinic, "OWNER", id, "not json"), 400);
        problem(postCharge(clinic, "OWNER", id, null), 400);
        problem(call(HttpMethod.POST, ADMISSIONS + "/not-a-uuid/charges", clinic.owner(), key("ecg")), 400);
        assertThat(chargeRows(clinic)).isZero();
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        assertThat(detail(clinic, id).get("bill").get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL);
    }

    @Test
    void aJsonNullBodyIs400NotA500() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        problem(postCharge(clinic, "OWNER", id, "null"), 400);
        assertThat(chargeRows(clinic)).isZero();
    }

    @Test
    void anInvalidKeyOnAnUnknownAdmissionIs400AndAValidKeyOnAnUnknownAdmissionIs404() throws Exception {
        Clinic clinic = hospital();
        quickAdmit(clinic);
        UUID unknown = UUID.randomUUID();
        assertFieldError(problem(postCharge(clinic, "OWNER", unknown.toString(), key("bogus")), 400), "serviceKey");
        JsonNode p = problem(postCharge(clinic, "OWNER", unknown.toString(), key("ecg")), 404);
        assertThat(p.get("status").asInt()).isEqualTo(404);
        assertThat(chargeRows(clinic)).isZero();
    }

    @Test
    void aDischargedAdmissionRefusesChargesWith409NotAdmittedAndTheExactMessage() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "ecg");
        discharge(clinic, id);
        long audits = audits(clinic, "IPD_CHARGE_ADDED");
        for (String role : CHARGERS) {
            JsonNode p = problem(postCharge(clinic, role, id, key("cbc")), 409);
            assertType(p, "not-admitted");
            assertThat(p.get("detail").asText()).isEqualTo("This patient is not admitted any more. A charge cannot be added.");
        }
        assertThat(chargeRows(clinic)).isOne();
        assertThat(audits(clinic, "IPD_CHARGE_ADDED")).isEqualTo(audits);
        // the 50 cap and the not-admitted check: an unknown key is still 400 first
        assertFieldError(problem(postCharge(clinic, "OWNER", id, key("bogus")), 400), "serviceKey");
    }

    // ---------------- roles ----------------

    @Test
    void ownerFrontDeskAndNurseMayAddChargesEveryOtherRoleGets403AndAnonymousGets401() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        long expected = 0;
        for (String role : CHARGERS) {
            JsonNode d = ok(HttpMethod.POST, chargesUrl(id), clinic.as(role), key("injection"), 200);
            expected++;
            assertThat(d.get("canAddCharge").asBoolean()).as(role).isTrue();
            assertLine(d.get("bill"), "Injection (IV/IM)", 15_000, (int) expected);
            // the mobile number is shown to the owner and front desk only, also in this response
            if ("NURSE".equals(role)) {
                assertThat(d.hasNonNull("mobile")).isFalse();
            } else {
                assertThat(d.hasNonNull("mobile")).as(role).isTrue();
            }
        }
        for (String role : NON_CHARGERS) {
            problem(postCharge(clinic, role, id, key("injection")), 403);
        }
        mvc.perform(post(chargesUrl(id)).contentType(MediaType.APPLICATION_JSON).content(key("injection")))
                .andExpect(status().isUnauthorized());
        assertThat(chargeRows(clinic)).isEqualTo(expected);
        assertThat(audits(clinic, "IPD_CHARGE_ADDED")).isEqualTo(expected);
    }

    // ---------------- tenant isolation ----------------

    @Test
    void anotherClinicCannotSeeOrAddChargesToAnAdmissionOfThisClinic() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        String idA = quickAdmit(a).get("id").asText();
        charge(a, idA, "xray");
        charge(a, idA, "xray");
        String idB = quickAdmit(b).get("id").asText();

        problem(postCharge(b, "OWNER", idA, key("ecg")), 404);
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + idA, b.owner(), null), 404);
        assertThat(chargeRows(a)).isEqualTo(2);
        assertThat(chargeRows(b)).as("tenant B sees none of tenant A's charges").isZero();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.admission_charge"));
        assertThat(unbound).as("no tenant bound").isZero();
        assertThat(count(b, "select count(*) from ipd.admission_charge where admission_id = ?::uuid", idA)).isZero();
        assertThat(count(b, "select count(*) from platform.audit_event where action = 'IPD_CHARGE_ADDED'")).isZero();
        // B's own bill is unaffected by A's charges
        JsonNode bBill = detail(b, idB).get("bill");
        assertThat(bBill.get("totalPaise").asLong()).isEqualTo(DAY_ONE_GENERAL);
        assertThat(lineNames(bBill)).doesNotContain("Chest X-ray");
        assertThat(list(b).get("items")).hasSize(1);
        assertThat(row(list(b), idB).get("runningBillPaise").asLong()).isEqualTo(DAY_ONE_GENERAL);
        // A's bill has the two X-rays
        assertLine(detail(a, idA).get("bill"), "Chest X-ray", 45_000, 2);
        // B charging its own admission is not blocked by A's rows and A's remain two
        charge(b, idB, "xray");
        assertThat(chargeRows(a)).isEqualTo(2);
        assertThat(chargeRows(b)).isOne();
    }

    @Test
    void aChargeRowCannotPointAtAnotherClinicsAdmissionOrCarryAnotherTenantId() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        String idA = quickAdmit(a).get("id").asText();
        String idB = quickAdmit(b).get("id").asText();
        String insert = "insert into ipd.admission_charge (admission_id, service_key, service_name, unit_paise, added_by) "
                + "values (?::uuid, 'ecg', 'ECG', 30000, ?)";
        // positive control: B's own admission accepts the row (rolled back)
        db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, insert, idB, b.ownerUserId());
            return null;
        });
        // A's admission from B's session: the composite foreign key (tenant_id, admission_id) finds no such pair
        assertDenied(b, insert, idA, b.ownerUserId());
        // tenant_id is not an insertable column at all, so a foreign tenant id is refused before RLS
        assertDenied(b, "insert into ipd.admission_charge (tenant_id, admission_id, service_key, service_name, unit_paise, "
                + "added_by) values (?, ?::uuid, 'ecg', 'ECG', 30000, ?)", a.tenantId(), idA, b.ownerUserId());
        // no tenant bound: nothing can be inserted
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, insert, idB, b.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(chargeRows(a)).isZero();
        assertThat(chargeRows(b)).isZero();
    }

    // ---------------- append-only table ----------------

    @Test
    void chargeTableIsAppendOnlyForSahajAppAndItsOwnerAndOnlyTheListedColumnsAreInsertable() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        charge(clinic, id, "ecg");
        charge(clinic, id, "cbc");
        // privilege: sahaj_app can neither change nor remove a row
        assertDenied(clinic, "update ipd.admission_charge set unit_paise = 1");
        assertDenied(clinic, "update ipd.admission_charge set qty = 50 where admission_id = ?::uuid", id);
        assertDenied(clinic, "delete from ipd.admission_charge");
        assertDenied(clinic, "delete from ipd.admission_charge where admission_id = ?::uuid", id);
        assertDenied(clinic, "truncate ipd.admission_charge");
        assertThat(chargeRows(clinic)).isEqualTo(2);

        String cols = "(admission_id, service_key, service_name, unit_paise, added_by)";
        // an explicit id, added_at or qty is denied (V2026_11_16_1000 took qty out of the grant: one row is one unit)
        assertDenied(clinic, "insert into ipd.admission_charge (id, admission_id, service_key, service_name, unit_paise, "
                + "added_by) values (gen_random_uuid(), ?::uuid, 'ecg', 'ECG', 30000, ?)", id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge (added_at, admission_id, service_key, service_name, "
                + "unit_paise, added_by) values (now() - interval '9 days', ?::uuid, 'ecg', 'ECG', 30000, ?)", id,
                clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge (admission_id, service_key, service_name, unit_paise, qty, "
                + "added_by) values (?::uuid, 'ecg', 'ECG', 30000, 1, ?)", id, clinic.ownerUserId());
        // positive control: the permitted columns work (rolled back), so the denials above are the grants
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.admission_charge " + cols + " values (?::uuid, 'ecg', 'ECG', 30000, ?)",
                    id, clinic.ownerUserId());
            return null;
        });
        // check constraints
        assertDenied(clinic, "insert into ipd.admission_charge " + cols + " values (?::uuid, 'surgery', 'Surgery', 1, ?)",
                id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge " + cols + " values (?::uuid, 'ecg', '', 1, ?)", id,
                clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge " + cols + " values (?::uuid, 'ecg', 'ECG', -1, ?)", id,
                clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge " + cols + " values (?::uuid, 'ecg', 'ECG', 100000001, ?)",
                id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge (admission_id, service_key, service_name, unit_paise, qty, "
                + "added_by) values (?::uuid, 'ecg', 'ECG', 1, 0, ?)", id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission_charge (admission_id, service_key, service_name, unit_paise, qty, "
                + "added_by) values (?::uuid, 'ecg', 'ECG', 1, 51, ?)", id, clinic.ownerUserId());

        // the table owner is stopped by the triggers (defence in depth); NO FORCE only lets the owner see the rows
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "truncate ipd.admission_charge");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "alter table ipd.admission_charge no force row level security");
            DbAccess.execute(c, "update ipd.admission_charge set unit_paise = 1");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "alter table ipd.admission_charge no force row level security");
            DbAccess.execute(c, "delete from ipd.admission_charge");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(chargeRows(clinic)).isEqualTo(2);
        assertThat(values(clinic, "select sum(unit_paise)::text from ipd.admission_charge")).containsExactly("60000");
        String forced = db().asMigrator(c -> DbAccess.text(c, "select relforcerowsecurity::text from pg_class "
                + "where oid = 'ipd.admission_charge'::regclass"));
        assertThat(forced).isEqualTo("true");
    }

    // ---------------- audit and privacy ----------------

    @Test
    void auditCarriesIdsServiceKeyAndBasePriceOnlyAndNoNameMobileOrReasonIsAuditedOrLogged(CapturedOutput output)
            throws Exception {
        Clinic clinic = hospital();
        String secretName = "Zubin Wadkar" + uniqueMobile().substring(5);
        String secretReason = "ZXQ-" + UUID.randomUUID();
        Person p = person(clinic, secretName);
        Map<String, Object> m = body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "semi"));
        m.put("reason", secretReason);
        m.put("depositPaise", 1_200_000);
        String id = admitOk(clinic, m).get("id").asText();

        charge(clinic, id, "oxygen");
        charge(clinic, id, "oxygen");
        charge(clinic, id, "physio");
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.owner(), null, 200);
        ok(HttpMethod.GET, BOARD, clinic.owner(), null, 200);
        ok(HttpMethod.GET, CHARGE_SERVICES, clinic.owner(), null, 200);

        List<String[]> rows = audit(clinic, "IPD_CHARGE_ADDED");
        assertThat(rows).hasSize(3);
        List<String> chargeIds = new ArrayList<>();
        try (var c = db().app()) {
            c.setAutoCommit(false);
            try (var bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            try (var ps = c.prepareStatement("select id::text from ipd.admission_charge order by added_at, service_key")) {
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        chargeIds.add(rs.getString(1));
                    }
                }
            }
        }
        assertThat(chargeIds).hasSize(3);
        List<String> auditedEntityIds = new ArrayList<>();
        for (String[] r : rows) {
            assertThat(r[0]).isEqualTo("IPD_CHARGE");
            auditedEntityIds.add(r[1]);
            assertThat(r[2]).as("no before image").isNull();
            assertThat(r[3]).contains(id).contains("serviceKey").contains("basePaise");
            assertThat(r[4]).isEqualTo(clinic.ownerUserId().toString());
        }
        assertThat(auditedEntityIds).containsExactlyInAnyOrderElementsOf(chargeIds);
        // base price of the service, not the semi-private price after the multiplier (80000 x 1.25 = 100000)
        assertThat(basePaise(rows.get(0))).isEqualTo(80_000L);
        assertThat(rows.get(0)[3]).contains("oxygen");
        assertThat(basePaise(rows.get(2))).isEqualTo(40_000L);
        assertThat(rows.get(2)[3]).contains("physio");

        assertThat(count(clinic, "select count(*) from ipd.admission where reason = ?", secretReason)).isOne();
        assertThat(count(clinic, "select count(*) from patient.patient where name = ? and mobile = ?", secretName,
                p.mobile())).isOne();
        for (String secret : new String[] {secretReason, secretName, p.mobile(), "Zubin"}) {
            assertThat(count(clinic, "select count(*) from platform.audit_event where audit_event::text like ?",
                    "%" + secret + "%")).as("audit rows must not contain " + secret).isZero();
            assertThat(output.getAll()).as("log must not contain " + secret).doesNotContain(secret);
        }
    }

    @Test
    void openingTheDetailAndAddingAChargeWriteAPatientAccessRowButTheListAndTheBoardDoNot() throws Exception {
        Clinic clinic = hospital();
        Person p = person(clinic, "Kishor Lonkar");
        String id = admitOk(clinic, body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen"))).get("id").asText();
        assertThat(recordAccess(clinic, p.id())).isZero();

        // A charge POST returns the full sheet (name, reason, mobile), so it logs the access like an open (security L2).
        charge(clinic, id, "ecg");
        assertThat(recordAccess(clinic, p.id())).isOne();
        charge(clinic, id, "cbc");
        assertThat(recordAccess(clinic, p.id())).isEqualTo(2);
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, BOARD, clinic.owner(), null, 200);
        ok(HttpMethod.GET, CHARGE_SERVICES, clinic.owner(), null, 200);
        problem(postCharge(clinic, "DOCTOR", id, key("ecg")), 403);
        problem(postCharge(clinic, "OWNER", id, key("bogus")), 400);
        assertThat(recordAccess(clinic, p.id())).as("list, board, services and refused POSTs: no access row")
                .isEqualTo(2);

        detail(clinic, id);
        assertThat(recordAccess(clinic, p.id())).isEqualTo(3);
        ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as("NURSE"), null, 200);
        assertThat(recordAccess(clinic, p.id())).isEqualTo(4);
        charge(clinic, id, "xray");
        assertThat(recordAccess(clinic, p.id())).isEqualTo(5);
    }

    // ---------------- review-fix regressions ----------------

    @Test
    void aMergedAwayPatientCannotBeAdmittedAndTheKeptRecordCan() throws Exception {
        Clinic clinic = hospital();
        String mobile = uniqueMobile();
        String create = "{\"name\":\"%s\",\"age\":45,\"sex\":\"M\",\"mobile\":\"" + mobile + "\"}";
        JsonNode keep = ok(HttpMethod.POST, PATIENTS, clinic.owner(), create.formatted("Ramesh K"), 201);
        JsonNode dup = ok(HttpMethod.POST, PATIENTS, clinic.owner(), create.formatted("Ramesh Kulkarni"), 201);
        ok(HttpMethod.POST, PATIENTS + "/merge", clinic.as("FRONT_DESK"),
                "{\"keepId\":\"" + keep.get("id").asText() + "\",\"mergeId\":\"" + dup.get("id").asText() + "\"}", 200);
        long admissions = rows(clinic, "admission");
        UUID bed = newBed(clinic, "gen");

        JsonNode p = problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(),
                js(body(UUID.fromString(dup.get("id").asText()), clinic.doctorMembershipId(), bed))), 409);
        assertType(p, "patient-merged");
        assertThat(p.get("detail").asText()).isEqualTo("This patient record was merged into another. Use the kept record.");
        assertThat(rows(clinic, "admission")).isEqualTo(admissions);
        assertThat(rows(clinic, "deposit")).isZero();
        assertThat(values(clinic, "select status from ipd.bed where id = ?", bed)).containsExactly("free");
        assertThat(audits(clinic, "IPD_ADMITTED")).isZero();
        // the kept record is admitted to the same bed
        admitOk(clinic, body(UUID.fromString(keep.get("id").asText()), clinic.doctorMembershipId(), bed));
        assertThat(rows(clinic, "admission")).isEqualTo(admissions + 1);
    }

    @Test
    void controlAndTextDirectionCharactersInTheReasonGet400WithAFieldErrorAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        UUID patient = newPatient(clinic);
        long admissions = rows(clinic, "admission");
        String[] bad = {"Fever\nNurse says ok", "Fe\u0007ver", "a\u0000b", "x\u0085y", "pay ‮txt", "⁦x",
                "tab\there", "del\u007F"};
        for (String reason : bad) {
            Map<String, Object> m = body(patient, clinic.doctorMembershipId(), bed);
            m.put("reason", reason);
            JsonNode p = problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(m)), 400);
            assertFieldError(p, "reason");
        }
        assertThat(rows(clinic, "admission")).isEqualTo(admissions);
        assertThat(values(clinic, "select status from ipd.bed where id = ?", bed)).containsExactly("free");
        // a plain reason, with Devanagari text and punctuation, is fine
        Map<String, Object> ok = body(patient, clinic.doctorMembershipId(), bed);
        ok.put("reason", "ताप आणि खोकला, 3 days (high)");
        assertThat(admitOk(clinic, ok).get("reason").asText()).isEqualTo("ताप आणि खोकला, 3 days (high)");
    }
}
