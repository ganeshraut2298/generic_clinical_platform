package com.sahaj.platform.it;

import static com.sahaj.platform.it.BackdateSupport.istToday;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-16 Revenue report on the real {@code sahaj_test} database. Real bills, items, discount, payments and a
 * refund drive the "today" figures; earlier days are created by moving {@code opd.visit.created_at} and
 * inserting old {@code billing.payment} rows with committed SQL (see {@link BackdateSupport}).
 * Fees: Dr Asha Rao Rs 300 (30000 paise), the second doctor Rs 250 (25000 paise).
 */
@OnTestDatabase
class RevenueIT extends SweepSupport {

    private static final String BILLING = "/api/v1/billing";
    private static final String REVENUE = BILLING + "/revenue";
    private static final String VISITS = "/api/v1/opd/visits";
    private static final String[] NON_OWNERS = {"FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    // ---------------- helpers ----------------

    private String visitInBilling(Clinic clinic, UUID doctor) throws Exception {
        JsonNode v = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + doctor + "\"}", 201);
        String id = v.get("id").asText();
        String stage = v.get("stage").asText();
        while (!stage.equals("BILLING")) {
            String next = stage.equals("VITALS") ? "WITH_DOCTOR" : "BILLING";
            ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"" + next + "\"}", 200);
            stage = next;
        }
        return id;
    }

    private UUID service(Clinic clinic, String name, int rupees) throws Exception {
        return readId(call(HttpMethod.POST, "/api/v1/opd/services", clinic.owner(),
                "{\"name\":\"" + name + "\",\"category\":\"Lab\",\"priceRupees\":" + rupees + "}").andReturn());
    }

    private void addItem(Clinic clinic, String visit, UUID service, int qty) throws Exception {
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.owner(),
                "{\"serviceId\":\"" + service + "\",\"qty\":" + qty + "}", 200);
    }

    private void pay(Clinic clinic, String role, String visit, String mode, long paise) throws Exception {
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/payments", clinic.as(role),
                "{\"mode\":\"" + mode + "\",\"amountPaise\":" + paise + "}", 200);
    }

    private JsonNode revenue(Clinic clinic, String range) throws Exception {
        return getJson(REVENUE + "?range=" + range, clinic.owner());
    }

    private static long modeAmount(JsonNode report, String mode) {
        for (JsonNode m : report.get("modes")) {
            if (m.get("mode").asText().equals(mode)) {
                return m.get("amountPaise").asLong();
            }
        }
        throw new AssertionError("No mode " + mode);
    }

    private static JsonNode byName(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.get(field).asText().equals(value)) {
                return n;
            }
        }
        throw new AssertionError(value + " not in " + array);
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    /**
     * Today, doctor A (Asha, 30000): V1 consult plus 2 x Blood test (150 rupees) with 10 percent discount
     * ("Regular patient"), total 54000, paid 20000 cash and 34000 UPI. V2 paid 30000 by card. Doctor B (25000):
     * V3 paid 25000 cash, then 5000 cash refunded ("Service not given"), leaving 5000 due. V4 still at the counter
     * with 10000 UPI paid of 30000.
     */
    private Clinic todaysClinic() throws Exception {
        Clinic clinic = newClinic(true);
        UUID doctorB = addMember(clinic, "DOCTOR").membershipId();
        UUID a = clinic.doctorMembershipId();

        String v1 = visitInBilling(clinic, a);
        addItem(clinic, v1, service(clinic, "Blood test", 150), 2);
        ok(HttpMethod.PUT, BILLING + "/bills/" + v1 + "/discount", clinic.owner(),
                "{\"percent\":10,\"reason\":\"Regular patient\"}", 200);
        pay(clinic, "FRONT_DESK", v1, "CASH", 20000);
        pay(clinic, "FRONT_DESK", v1, "UPI", 34000);

        String v2 = visitInBilling(clinic, a);
        pay(clinic, "OWNER", v2, "CARD", 30000);

        String v3 = visitInBilling(clinic, doctorB);
        pay(clinic, "OWNER", v3, "CASH", 25000);
        ok(HttpMethod.POST, BILLING + "/bills/" + v3 + "/refund", clinic.owner(),
                "{\"amountPaise\":5000,\"mode\":\"CASH\",\"reason\":\"Service not given\"}", 200);

        String v4 = visitInBilling(clinic, a);
        pay(clinic, "OWNER", v4, "UPI", 10000);
        return clinic;
    }

    // ---------------- AC2 to AC6: every figure of a real day ----------------

    @Test
    void todayShowsEveryFigureFromRealBillsPaymentsDiscountAndRefund() throws Exception {
        Clinic clinic = todaysClinic();
        JsonNode r = revenue(clinic, "TODAY");

        assertThat(r.get("range").asText()).isEqualTo("TODAY");
        // 20000 + 34000 + 30000 + (25000 - 5000) + 10000
        assertThat(r.get("collectedPaise").asLong()).isEqualTo(114000L);
        // Billed = Done bills after discount: 54000 + 30000 + 25000. V4 is not done so it is not billed.
        assertThat(r.get("billedPaise").asLong()).isEqualTo(109000L);
        assertThat(r.get("patients").asInt()).isEqualTo(3);
        assertThat(r.get("averageBillPaise").asLong()).isEqualTo(36333L);
        assertThat(r.get("duePaise").asLong()).isEqualTo(5000L);
        assertThat(r.get("atCounterPaise").asLong()).isEqualTo(20000L);
        assertThat(r.get("discountPaise").asLong()).isEqualTo(6000L);
        assertThat(r.get("refundPaise").asLong()).isEqualTo(5000L);
        assertThat(r.get("cashInDrawerPaise").asLong()).isEqualTo(40000L);
        assertThat(r.get("collectedChangePercent").isNull()).as("nothing yesterday").isTrue();

        // Collections by day: one day, today.
        assertThat(r.get("days")).hasSize(1);
        assertThat(r.get("days").get(0).get("date").asText()).isEqualTo(istToday().toString());
        assertThat(r.get("days").get(0).get("collectedPaise").asLong()).isEqualTo(114000L);

        // How patients paid: always CASH, UPI, CARD in that order; cash is net of the cash refund.
        assertThat(names(r.get("modes"), "mode")).containsExactly("CASH", "UPI", "CARD");
        assertThat(modeAmount(r, "CASH")).isEqualTo(40000L);
        assertThat(modeAmount(r, "UPI")).isEqualTo(44000L);
        assertThat(modeAmount(r, "CARD")).isEqualTo(30000L);

        // By doctor: collected counts every payment, patients counts done bills. Highest collected first.
        JsonNode doctors = r.get("doctors");
        assertThat(doctors).hasSize(2);
        assertThat(doctors.get(0).get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(doctors.get(0).get("patients").asInt()).isEqualTo(2);
        assertThat(doctors.get(0).get("collectedPaise").asLong()).isEqualTo(94000L);
        assertThat(doctors.get(1).get("doctorName").asText()).isEqualTo("Member DOCTOR");
        assertThat(doctors.get(1).get("patients").asInt()).isEqualTo(1);
        assertThat(doctors.get(1).get("collectedPaise").asLong()).isEqualTo(20000L);

        // Top services: consultation plus items, by billed amount.
        JsonNode services = r.get("topServices");
        assertThat(names(services, "name")).containsExactly("Consultation", "Blood test");
        assertThat(services.get(0).get("billedPaise").asLong()).isEqualTo(85000L);
        assertThat(services.get(1).get("billedPaise").asLong()).isEqualTo(30000L);

        // Dues: only the done bill with a balance (V3 after its refund), not the one still at the counter.
        JsonNode dues = r.get("dues");
        assertThat(dues).hasSize(1);
        assertThat(dues.get(0).get("balancePaise").asLong()).isEqualTo(5000L);
        assertThat(dues.get(0).get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(dues.get(0).get("mobile").asText()).matches("[6-9][0-9]{9}");
        assertThat(dues.get(0).get("doctorName").asText()).isEqualTo("Member DOCTOR");
        assertThat(dues.get(0).get("date").asText()).isEqualTo(istToday().toString());
        assertThat(dues.get(0).get("visitId").asText()).matches("[0-9a-f-]{36}");

        // Discounts and refunds with their details.
        JsonNode adjustments = r.get("adjustments");
        assertThat(adjustments).hasSize(2);
        JsonNode discount = byName(adjustments, "kind", "DISCOUNT");
        assertThat(discount.get("amountPaise").asLong()).isEqualTo(6000L);
        assertThat(discount.get("detail").asText()).isEqualTo("10% - Regular patient");
        assertThat(discount.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(discount.get("date").asText()).isEqualTo(istToday().toString());
        JsonNode refund = byName(adjustments, "kind", "REFUND");
        assertThat(refund.get("amountPaise").asLong()).isEqualTo(5000L);
        assertThat(refund.get("detail").asText()).startsWith("Service not given - CASH - by ");
        assertThat(refund.get("date").asText()).isEqualTo(istToday().toString());

        // The week and month views include today with the same figures; the series just gets longer with zeros.
        for (String range : new String[] {"WEEK", "MONTH"}) {
            JsonNode wider = revenue(clinic, range);
            assertThat(wider.get("collectedPaise").asLong()).as(range).isEqualTo(114000L);
            assertThat(wider.get("billedPaise").asLong()).as(range).isEqualTo(109000L);
        }
    }

    @Test
    void anAllZeroReportHasEveryDayEveryModeAndEmptyLists() throws Exception {
        Clinic clinic = newClinic(true);
        for (String range : new String[] {"TODAY", "WEEK", "MONTH"}) {
            int length = range.equals("TODAY") ? 1 : range.equals("WEEK") ? 7 : 30;
            JsonNode r = revenue(clinic, range);
            assertThat(r.get("range").asText()).isEqualTo(range);
            for (String f : new String[] {"collectedPaise", "billedPaise", "patients", "averageBillPaise", "duePaise",
                    "atCounterPaise", "discountPaise", "refundPaise", "cashInDrawerPaise"}) {
                assertThat(r.get(f).asLong()).as(range + " " + f).isZero();
            }
            assertThat(r.get("collectedChangePercent").isNull()).isTrue();
            assertThat(r.get("days")).hasSize(length);
            assertThat(r.get("days").get(length - 1).get("date").asText()).isEqualTo(istToday().toString());
            assertThat(r.get("days").get(0).get("date").asText()).isEqualTo(istToday().minusDays(length - 1L).toString());
            for (JsonNode day : r.get("days")) {
                assertThat(day.get("collectedPaise").asLong()).isZero();
            }
            assertThat(names(r.get("modes"), "mode")).containsExactly("CASH", "UPI", "CARD");
            assertThat(r.get("doctors")).isEmpty();
            assertThat(r.get("topServices")).isEmpty();
            assertThat(r.get("dues")).isEmpty();
            assertThat(r.get("adjustments")).isEmpty();
        }
    }

    @Test
    void topServicesAreCappedAtSixLargestFirstWithConsultationCounted() throws Exception {
        Clinic clinic = newClinic(true);
        String visit = visitInBilling(clinic, clinic.doctorMembershipId());
        for (int i = 1; i <= 7; i++) {
            addItem(clinic, visit, service(clinic, "Svc " + i, i * 100), 1);
        }
        // 30000 consultation + 100..700 rupees of services = 310000 paise.
        pay(clinic, "OWNER", visit, "CASH", 310000);
        JsonNode services = revenue(clinic, "TODAY").get("topServices");
        assertThat(services).hasSize(6);
        // Svc 3 and the consultation tie at 30000: ties break by name, so Consultation first. Svc 1 and 2 drop out.
        assertThat(names(services, "name")).containsExactly("Svc 7", "Svc 6", "Svc 5", "Svc 4", "Consultation", "Svc 3");
        assertThat(services.get(0).get("billedPaise").asLong()).isEqualTo(70000L);
    }

    // ---------------- previous period, multi-day series, ranges ----------------

    /**
     * Today: V_a, 30000 cash, done. Yesterday: V_b, bill 40000 paid 40000 UPI. Five days ago: V_d, bill 40000,
     * 10000 cash paid (30000 due). Ten days ago: V_c, bill 40000, 20000 cash paid (20000 due). All done, all Dr Asha.
     */
    private Clinic multiDayClinic() throws Exception {
        Clinic clinic = newClinic(true);
        UUID a = clinic.doctorMembershipId();
        UUID dressing = service(clinic, "Dressing " + UUID.randomUUID().toString().substring(0, 6), 100);

        String va = visitInBilling(clinic, a);
        pay(clinic, "OWNER", va, "CASH", 30000);

        backdated(clinic, a, dressing, 1, "UPI", 40000);
        backdated(clinic, a, dressing, 5, "CASH", 10000);
        backdated(clinic, a, dressing, 10, "CASH", 20000);
        return clinic;
    }

    private void backdated(Clinic clinic, UUID doctor, UUID service, int daysAgo, String mode, long paid)
            throws Exception {
        String visit = visitInBilling(clinic, doctor);
        addItem(clinic, visit, service, 1); // creates the bill: 30000 consultation + 10000 = 40000
        BackdateSupport.setVisitCreatedAt(db(), clinic.tenantId(), visit, BackdateSupport.noon(daysAgo));
        BackdateSupport.insertPayment(db(), clinic.tenantId(), clinic.ownerUserId(), visit, mode, paid, null,
                BackdateSupport.noon(daysAgo));
        BackdateSupport.setVisitStage(db(), clinic.tenantId(), visit, "DONE");
    }

    @Test
    void todayComparesWithYesterdayAndIgnoresOlderDays() throws Exception {
        Clinic clinic = multiDayClinic();
        JsonNode r = revenue(clinic, "TODAY");
        assertThat(r.get("collectedPaise").asLong()).isEqualTo(30000L);
        // (30000 - 40000) / 40000
        assertThat(r.get("collectedChangePercent").asInt()).isEqualTo(-25);
        assertThat(r.get("billedPaise").asLong()).isEqualTo(30000L);
        assertThat(r.get("patients").asInt()).isEqualTo(1);
        assertThat(r.get("duePaise").asLong()).isZero();
        assertThat(r.get("dues")).isEmpty();
        assertThat(r.get("cashInDrawerPaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void weekHasSevenDaysWithZerosAndComparesWithThePreviousSevenDays() throws Exception {
        Clinic clinic = multiDayClinic();
        JsonNode r = revenue(clinic, "WEEK");
        assertThat(r.get("days")).hasSize(7);
        for (int i = 0; i < 7; i++) {
            long daysAgo = 6 - i;
            JsonNode day = r.get("days").get(i);
            assertThat(day.get("date").asText()).isEqualTo(istToday().minusDays(daysAgo).toString());
            long expected = daysAgo == 0 ? 30000L : daysAgo == 1 ? 40000L : daysAgo == 5 ? 10000L : 0L;
            assertThat(day.get("collectedPaise").asLong()).as("day -" + daysAgo).isEqualTo(expected);
        }
        assertThat(r.get("collectedPaise").asLong()).isEqualTo(80000L);
        // The ten-day-old 20000 is in the previous seven days: (80000 - 20000) / 20000 = +300 percent.
        assertThat(r.get("collectedChangePercent").asInt()).isEqualTo(300);
        assertThat(r.get("billedPaise").asLong()).isEqualTo(110000L);
        assertThat(r.get("patients").asInt()).isEqualTo(3);
        assertThat(r.get("averageBillPaise").asLong()).isEqualTo(36666L);
        assertThat(r.get("duePaise").asLong()).isEqualTo(30000L);
        assertThat(r.get("dues")).hasSize(1);
        assertThat(r.get("dues").get(0).get("balancePaise").asLong()).isEqualTo(30000L);
        assertThat(r.get("dues").get(0).get("date").asText()).isEqualTo(istToday().minusDays(5).toString());
        assertThat(modeAmount(r, "CASH")).isEqualTo(40000L);
        assertThat(modeAmount(r, "UPI")).isEqualTo(40000L);
        assertThat(modeAmount(r, "CARD")).isZero();
        // Cash in the drawer is today's cash only, whatever the range.
        assertThat(r.get("cashInDrawerPaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void monthHasThirtyDaysDuesOldestFirstAndNoChangeWhenThePreviousThirtyDaysAreEmpty() throws Exception {
        Clinic clinic = multiDayClinic();
        JsonNode r = revenue(clinic, "MONTH");
        assertThat(r.get("days")).hasSize(30);
        assertThat(r.get("days").get(0).get("date").asText()).isEqualTo(istToday().minusDays(29).toString());
        assertThat(r.get("days").get(19).get("date").asText()).isEqualTo(istToday().minusDays(10).toString());
        assertThat(r.get("days").get(19).get("collectedPaise").asLong()).isEqualTo(20000L);
        assertThat(r.get("collectedPaise").asLong()).isEqualTo(100000L);
        assertThat(r.get("collectedChangePercent").isNull()).isTrue();
        assertThat(r.get("billedPaise").asLong()).isEqualTo(150000L);
        assertThat(r.get("patients").asInt()).isEqualTo(4);
        assertThat(r.get("averageBillPaise").asLong()).isEqualTo(37500L);
        assertThat(r.get("duePaise").asLong()).isEqualTo(50000L);
        JsonNode dues = r.get("dues");
        assertThat(dues).hasSize(2);
        // Oldest first: ten days ago (20000), then five days ago (30000).
        assertThat(dues.get(0).get("date").asText()).isEqualTo(istToday().minusDays(10).toString());
        assertThat(dues.get(0).get("balancePaise").asLong()).isEqualTo(20000L);
        assertThat(dues.get(1).get("date").asText()).isEqualTo(istToday().minusDays(5).toString());
        assertThat(dues.get(1).get("balancePaise").asLong()).isEqualTo(30000L);
        assertThat(modeAmount(r, "CASH")).isEqualTo(60000L);
        assertThat(modeAmount(r, "UPI")).isEqualTo(40000L);
        assertThat(r.get("doctors")).hasSize(1);
        assertThat(r.get("doctors").get(0).get("patients").asInt()).isEqualTo(4);
        assertThat(r.get("doctors").get(0).get("collectedPaise").asLong()).isEqualTo(100000L);
        // Consultation 4 x 30000 and Dressing 3 x 10000.
        assertThat(r.get("topServices").get(0).get("name").asText()).isEqualTo("Consultation");
        assertThat(r.get("topServices").get(0).get("billedPaise").asLong()).isEqualTo(120000L);
        assertThat(r.get("topServices").get(1).get("billedPaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void aRefundOnAnEarlierDayReducesThatDaysNetAndShowsInTheRefundList() throws Exception {
        Clinic clinic = newClinic(true);
        UUID a = clinic.doctorMembershipId();
        UUID dressing = service(clinic, "Dressing " + UUID.randomUUID().toString().substring(0, 6), 100);
        String visit = visitInBilling(clinic, a);
        addItem(clinic, visit, dressing, 1);
        BackdateSupport.setVisitCreatedAt(db(), clinic.tenantId(), visit, BackdateSupport.noon(2));
        BackdateSupport.insertPayment(db(), clinic.tenantId(), clinic.ownerUserId(), visit, "CASH", 40000, null,
                BackdateSupport.noon(2));
        BackdateSupport.insertPayment(db(), clinic.tenantId(), clinic.ownerUserId(), visit, "CASH", -10000,
                "Wrong item", BackdateSupport.noon(1));
        BackdateSupport.setVisitStage(db(), clinic.tenantId(), visit, "DONE");

        JsonNode r = revenue(clinic, "WEEK");
        assertThat(r.get("collectedPaise").asLong()).isEqualTo(30000L);
        assertThat(r.get("refundPaise").asLong()).isEqualTo(10000L);
        assertThat(r.get("days").get(4).get("collectedPaise").asLong()).isEqualTo(40000L);
        assertThat(r.get("days").get(5).get("collectedPaise").asLong()).isEqualTo(-10000L);
        assertThat(r.get("duePaise").asLong()).isEqualTo(10000L);
        JsonNode refund = byName(r.get("adjustments"), "kind", "REFUND");
        assertThat(refund.get("date").asText()).isEqualTo(istToday().minusDays(1).toString());
        assertThat(refund.get("detail").asText()).startsWith("Wrong item - CASH - by ");
        // Not in today's report.
        assertThat(revenue(clinic, "TODAY").get("refundPaise").asLong()).isZero();
    }

    // ---------------- AC7: owner only; validation; isolation; audit ----------------

    @Test
    void everyRoleExceptTheOwnerGets403AsProblemJson() throws Exception {
        Clinic clinic = newClinic(true);
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.GET, REVENUE + "?range=TODAY", clinic.as(role), null), 403);
        }
        ok(HttpMethod.GET, REVENUE + "?range=TODAY", clinic.owner(), null, 200);
    }

    @Test
    void anonymousCallGets401() throws Exception {
        problem(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(REVENUE + "?range=TODAY")), 401);
    }

    @Test
    void aBadOrMissingRangeIs400WithProblemDetails() throws Exception {
        Clinic clinic = newClinic(true);
        for (String bad : new String[] {"?range=YEAR", "?range=today", "?range=", "?range=7", "?range=WEEK%20",
                "", "?other=TODAY"}) {
            problem(call(HttpMethod.GET, REVENUE + bad, clinic.owner(), null), 400);
        }
    }

    @Test
    void anotherClinicSeesNoneOfTheMoneyAndTheOwnersRoleIsStillChecked() throws Exception {
        Clinic a = todaysClinic();
        Clinic b = newClinic(true);
        for (String range : new String[] {"TODAY", "WEEK", "MONTH"}) {
            JsonNode r = revenue(b, range);
            assertThat(r.get("collectedPaise").asLong()).as(range).isZero();
            assertThat(r.get("billedPaise").asLong()).isZero();
            assertThat(r.get("duePaise").asLong()).isZero();
            assertThat(r.get("atCounterPaise").asLong()).isZero();
            assertThat(r.get("cashInDrawerPaise").asLong()).isZero();
            assertThat(r.get("doctors")).isEmpty();
            assertThat(r.get("dues")).isEmpty();
            assertThat(r.get("adjustments")).isEmpty();
            assertThat(r.get("topServices")).isEmpty();
        }
        // And A is unaffected by B existing.
        assertThat(revenue(a, "TODAY").get("collectedPaise").asLong()).isEqualTo(114000L);
        // A is not reachable with B's tenant even when B's owner is the signed-in user.
        assertThat(revenue(b, "TODAY").get("collectedPaise").asLong()).isZero();
    }

    @Test
    void readingTheReportWritesNoAuditRow() throws Exception {
        Clinic clinic = todaysClinic();
        long before = BackdateSupport.auditRows(db(), clinic.tenantId());
        revenue(clinic, "MONTH");
        assertThat(BackdateSupport.auditRows(db(), clinic.tenantId())).isEqualTo(before);
    }
}
