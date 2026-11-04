package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Sweep: SAH-10 billing on the real database - counter lists, items, discount limits per role, partial and
 * full payment (visit becomes DONE at zero balance), refund (owner only), day closing and its 409.
 * The consultation fee of the added doctor is Rs 300 (30000 paise).
 */
@OnTestDatabase
class BillingIT extends SweepSupport {

    private static final String BILLING = "/api/v1/billing";
    private static final String VISITS = "/api/v1/opd/visits";
    private static final String[] NON_COLLECTORS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "BILLING", "PHARMACIST", "COUNSELLOR"};

    /** A visit that has reached the BILLING stage. */
    private UUID visitInBilling(Clinic clinic) throws Exception {
        JsonNode v = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        String id = v.get("id").asText();
        String stage = v.get("stage").asText();
        while (!stage.equals("BILLING")) {
            String next = stage.equals("VITALS") ? "WITH_DOCTOR" : "BILLING";
            ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"" + next + "\"}", 200);
            stage = next;
        }
        return UUID.fromString(id);
    }

    private UUID service(Clinic clinic, String name, int rupees) throws Exception {
        return readId(call(HttpMethod.POST, "/api/v1/opd/services", clinic.owner(),
                "{\"name\":\"" + name + " " + UUID.randomUUID().toString().substring(0, 6) + "\",\"category\":\"Lab\",\"priceRupees\":" + rupees + "}").andReturn());
    }

    private JsonNode bill(Clinic clinic, UUID visit) throws Exception {
        return ok(HttpMethod.GET, BILLING + "/bills/" + visit, clinic.owner(), null, 200);
    }

    private JsonNode pay(Clinic clinic, String role, UUID visit, String mode, long paise, int expected) throws Exception {
        return ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/payments", clinic.as(role),
                "{\"mode\":\"" + mode + "\",\"amountPaise\":" + paise + "}", expected);
    }

    private String stageOf(Clinic clinic, UUID visit) throws Exception {
        for (JsonNode v : ok(HttpMethod.GET, VISITS + "/today", clinic.owner(), null, 200)) {
            if (v.get("id").asText().equals(visit.toString())) {
                return v.get("stage").asText();
            }
        }
        throw new AssertionError("visit not on board");
    }

    // ---------------- counter ----------------

    @Test
    void counterIsEmptyThenListsAVisitAwaitingPaymentWithItsFee() throws Exception {
        Clinic clinic = newClinic();
        JsonNode empty = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(empty.get("awaiting").size()).isZero();
        assertThat(empty.get("dues").size()).isZero();
        assertThat(empty.get("paidToday").size()).isZero();
        UUID visit = visitInBilling(clinic);
        JsonNode counter = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(counter.get("awaiting").size()).isEqualTo(1);
        JsonNode row = counter.get("awaiting").get(0);
        assertThat(row.get("visitId").asText()).isEqualTo(visit.toString());
        assertThat(row.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(row.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(row.get("totalPaise").asLong()).isEqualTo(30000L);
        assertThat(row.get("balancePaise").asLong()).isEqualTo(30000L);
        assertThat(row.get("tokenNo").asInt()).isEqualTo(1);
    }

    @Test
    void visitsStillBeforeBillingAreNotOnTheCounter() throws Exception {
        Clinic clinic = newClinic(true);
        ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        assertThat(ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200).get("awaiting").size()).isZero();
    }

    @Test
    void everyRoleCanReadTheCounterAndABill() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            assertThat(ok(HttpMethod.GET, BILLING + "/counter", clinic.as(role), null, 200).get("awaiting").size()).isEqualTo(1);
            JsonNode b = ok(HttpMethod.GET, BILLING + "/bills/" + visit, clinic.as(role), null, 200);
            boolean collector = role.equals("OWNER") || role.equals("FRONT_DESK");
            assertThat(b.get("canCollect").asBoolean()).as(role).isEqualTo(collector);
        }
    }

    @Test
    void counterOfAnotherClinicIsInvisible() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID visit = visitInBilling(a);
        assertThat(ok(HttpMethod.GET, BILLING + "/counter", b.owner(), null, 200).get("awaiting").size()).isZero();
        problem(call(HttpMethod.GET, BILLING + "/bills/" + visit, b.owner(), null), 404);
    }

    // ---------------- bill ----------------

    @Test
    void billShowsPatientDoctorConsultationLineAndRoleLimit() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        JsonNode b = bill(clinic, visit);
        assertThat(b.get("visitId").asText()).isEqualTo(visit.toString());
        assertThat(b.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(b.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(b.get("visitType").asText()).isEqualTo("NEW");
        assertThat(b.get("items").size()).isEqualTo(1);
        assertThat(b.get("items").get(0).get("name").asText()).isEqualTo("Consultation");
        assertThat(b.get("items").get(0).get("removable").asBoolean()).isFalse();
        assertThat(b.get("subtotalPaise").asLong()).isEqualTo(30000L);
        assertThat(b.get("totalPaise").asLong()).isEqualTo(30000L);
        assertThat(b.get("paidPaise").asLong()).isZero();
        assertThat(b.get("balancePaise").asLong()).isEqualTo(30000L);
        assertThat(b.get("discountLimitPercent").asInt()).isEqualTo(100);
        assertThat(ok(HttpMethod.GET, BILLING + "/bills/" + visit, clinic.as("FRONT_DESK"), null, 200).get("discountLimitPercent").asInt()).isEqualTo(10);
        assertThat(ok(HttpMethod.GET, BILLING + "/bills/" + visit, clinic.as("NURSE"), null, 200).get("discountLimitPercent").asInt()).isZero();
    }

    @Test
    void readingABillDoesNotCreateOneAndUnknownVisitIs404() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        bill(clinic, visit);
        bill(clinic, visit);
        problem(call(HttpMethod.GET, BILLING + "/bills/" + UUID.randomUUID(), clinic.owner(), null), 404);
        problem(call(HttpMethod.GET, BILLING + "/bills/xyz", clinic.owner(), null), 400);
    }

    // ---------------- items ----------------

    @Test
    void addItemRaisesTheTotalByPriceTimesQuantityAndRemoveTakesItBack() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Blood test", 150);
        JsonNode added = ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.as("FRONT_DESK"),
                "{\"serviceId\":\"" + svc + "\",\"qty\":2}", 200);
        assertThat(added.get("items").size()).isEqualTo(2);
        assertThat(added.get("subtotalPaise").asLong()).isEqualTo(30000L + 2 * 15000L);
        assertThat(added.get("balancePaise").asLong()).isEqualTo(60000L);
        JsonNode line = added.get("items").get(1);
        assertThat(line.get("removable").asBoolean()).isTrue();
        assertThat(line.get("qty").asInt()).isEqualTo(2);
        // default quantity is 1
        JsonNode again = ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.owner(), "{\"serviceId\":\"" + svc + "\"}", 200);
        assertThat(again.get("subtotalPaise").asLong()).isEqualTo(75000L);
        String itemId = line.get("id").asText();
        JsonNode removed = ok(HttpMethod.DELETE, BILLING + "/bills/" + visit + "/items/" + itemId, clinic.owner(), null, 200);
        assertThat(removed.get("subtotalPaise").asLong()).isEqualTo(45000L);
        problem(call(HttpMethod.DELETE, BILLING + "/bills/" + visit + "/items/" + itemId, clinic.owner(), null), 404);
    }

    @Test
    void laterPriceChangeDoesNotRewriteAnExistingBillLine() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Dressing", 100);
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":1}", 200);
        ok(HttpMethod.PATCH, "/api/v1/opd/services/" + svc, clinic.owner(), "{\"priceRupees\":900}", 200);
        assertThat(bill(clinic, visit).get("subtotalPaise").asLong()).isEqualTo(40000L);
    }

    @Test
    void addItemValidation() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Injection", 50);
        String url = BILLING + "/bills/" + visit + "/items";
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":0}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":21}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":-2}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":\"two\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"qty\":1}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + UUID.randomUUID() + "\",\"qty\":1}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"nope\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{}"), 400);
        ok(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":20}", 200);
        ok(HttpMethod.PATCH, "/api/v1/opd/services/" + svc, clinic.owner(), "{\"active\":false}", 200);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":1}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + UUID.randomUUID() + "/items", clinic.owner(), "{\"serviceId\":\"" + svc + "\"}"), 404);
    }

    @Test
    void anotherClinicsServiceCannotBeBilledAndItsBillIsUntouchable() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID visitA = visitInBilling(a);
        UUID svcB = service(b, "Foreign", 10);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + visitA + "/items", a.owner(), "{\"serviceId\":\"" + svcB + "\"}"), 400);
        UUID svcA = service(a, "Own", 10);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + visitA + "/items", b.owner(), "{\"serviceId\":\"" + svcA + "\"}"), 404);
        JsonNode added = ok(HttpMethod.POST, BILLING + "/bills/" + visitA + "/items", a.owner(), "{\"serviceId\":\"" + svcA + "\"}", 200);
        problem(call(HttpMethod.DELETE, BILLING + "/bills/" + visitA + "/items/" + added.get("items").get(1).get("id").asText(), b.owner(), null), 404);
    }

    @Test
    void theConsultationLineCannotBeRemovedAndItemWritesAreCollectorsOnly() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Xray", 200);
        problem(call(HttpMethod.DELETE, BILLING + "/bills/" + visit + "/items/" + visit, clinic.owner(), null), 404);
        for (String role : NON_COLLECTORS) {
            problem(call(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.as(role), "{\"serviceId\":\"" + svc + "\"}"), 403);
            problem(call(HttpMethod.DELETE, BILLING + "/bills/" + visit + "/items/" + UUID.randomUUID(), clinic.as(role), null), 403);
        }
    }

    // ---------------- discount ----------------

    @Test
    void frontDeskDiscountUpToItsLimitIsAppliedAndAboveIsRefused() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        String url = BILLING + "/bills/" + visit + "/discount";
        JsonNode ok10 = ok(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":10,\"reason\":\"Regular patient\"}", 200);
        assertThat(ok10.get("discountPercent").asInt()).isEqualTo(10);
        assertThat(ok10.get("discountPaise").asLong()).isEqualTo(3000L);
        assertThat(ok10.get("totalPaise").asLong()).isEqualTo(27000L);
        assertThat(ok10.get("discountReason").asText()).isEqualTo("Regular patient");
        JsonNode p = problem(call(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":11}"), 400);
        assertThat(p.toString()).contains("percent");
        assertThat(bill(clinic, visit).get("discountPercent").asInt()).isEqualTo(10);
        // 0 clears it
        JsonNode cleared = ok(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":0}", 200);
        assertThat(cleared.get("discountPaise").asLong()).isZero();
        assertThat(cleared.get("totalPaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void ownerHasNoLimitAndTheLimitFollowsTheSavedFeeSettings() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        String url = BILLING + "/bills/" + visit + "/discount";
        JsonNode full = ok(HttpMethod.PUT, url, clinic.owner(), "{\"percent\":100}", 200);
        assertThat(full.get("totalPaise").asLong()).isZero();
        ok(HttpMethod.PUT, "/api/v1/opd/fee-settings", clinic.owner(),
                "{\"feeTiming\":\"before\",\"followUpDays\":7,\"followUpFee\":\"free\",\"discountLimits\":{\"OWNER\":100,\"FRONT_DESK\":25},\"upiOnBill\":true}", 200);
        ok(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":25}", 200);
        problem(call(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":26}"), 400);
        assertThat(ok(HttpMethod.GET, BILLING + "/bills/" + visit, clinic.as("FRONT_DESK"), null, 200).get("discountLimitPercent").asInt()).isEqualTo(25);
        // a limit of zero means no discount at all
        ok(HttpMethod.PUT, "/api/v1/opd/fee-settings", clinic.owner(),
                "{\"feeTiming\":\"before\",\"followUpDays\":7,\"followUpFee\":\"free\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true}", 200);
        problem(call(HttpMethod.PUT, url, clinic.as("FRONT_DESK"), "{\"percent\":1}"), 400);
    }

    @Test
    void discountValidationAndRoles() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        String url = BILLING + "/bills/" + visit + "/discount";
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{\"percent\":-1}"), 400);
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{\"percent\":101}"), 400);
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{\"percent\":\"ten\"}"), 400);
        problem(call(HttpMethod.PUT, BILLING + "/bills/" + UUID.randomUUID() + "/discount", clinic.owner(), "{\"percent\":5}"), 404);
        for (String role : NON_COLLECTORS) {
            problem(call(HttpMethod.PUT, url, clinic.as(role), "{\"percent\":5}"), 403);
        }
    }

    @Test
    void discountOnABillWithItemsAppliesToTheWholeSubtotal() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Scan", 700);
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":1}", 200);
        JsonNode b = ok(HttpMethod.PUT, BILLING + "/bills/" + visit + "/discount", clinic.owner(), "{\"percent\":50}", 200);
        assertThat(b.get("subtotalPaise").asLong()).isEqualTo(100000L);
        assertThat(b.get("totalPaise").asLong()).isEqualTo(50000L);
    }

    // ---------------- payments ----------------

    @Test
    void partialPaymentKeepsTheVisitInBillingAndFullPaymentMakesItDone() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        JsonNode part = pay(clinic, "FRONT_DESK", visit, "CASH", 10000, 200);
        assertThat(part.get("paidPaise").asLong()).isEqualTo(10000L);
        assertThat(part.get("balancePaise").asLong()).isEqualTo(20000L);
        assertThat(part.get("payments").size()).isEqualTo(1);
        assertThat(part.get("payments").get(0).get("mode").asText()).isEqualTo("CASH");
        assertThat(part.get("payments").get(0).get("recordedByName").asText()).isNotBlank();
        assertThat(stageOf(clinic, visit)).isEqualTo("BILLING");
        JsonNode counter = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(counter.get("awaiting").get(0).get("balancePaise").asLong()).isEqualTo(20000L);
        JsonNode full = ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/payments", clinic.owner(),
                "{\"mode\":\"UPI\",\"amountPaise\":20000,\"reference\":\"UTR123\"}", 200);
        assertThat(full.get("balancePaise").asLong()).isZero();
        assertThat(full.get("payments").size()).isEqualTo(2);
        assertThat(full.get("payments").get(1).get("reference").asText()).isEqualTo("UTR123");
        assertThat(stageOf(clinic, visit)).isEqualTo("DONE");
        JsonNode after = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(after.get("awaiting").size()).isZero();
        assertThat(after.get("dues").size()).isZero();
        assertThat(after.get("paidToday").size()).isEqualTo(1);
        assertThat(after.get("paidToday").get(0).get("balancePaise").asLong()).isZero();
    }

    @Test
    void fullPaymentInOneGoWithCardAndWithADiscountAndItem() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        UUID svc = service(clinic, "Procedure", 200);
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/items", clinic.owner(), "{\"serviceId\":\"" + svc + "\",\"qty\":1}", 200);
        ok(HttpMethod.PUT, BILLING + "/bills/" + visit + "/discount", clinic.owner(), "{\"percent\":10}", 200);
        // (30000 + 20000) * 90% = 45000
        JsonNode b = pay(clinic, "OWNER", visit, "CARD", 45000, 200);
        assertThat(b.get("totalPaise").asLong()).isEqualTo(45000L);
        assertThat(b.get("balancePaise").asLong()).isZero();
        assertThat(stageOf(clinic, visit)).isEqualTo("DONE");
    }

    @Test
    void paymentValidation() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        String url = BILLING + "/bills/" + visit + "/payments";
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":0}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":-100}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":30001}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":100}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"BITCOIN\",\"amountPaise\":100}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"cash\",\"amountPaise\":100}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":\"lots\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":99999999999999999999}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + UUID.randomUUID() + "/payments", clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":100}"), 404);
        assertThat(bill(clinic, visit).get("paidPaise").asLong()).isZero();
    }

    @Test
    void anotherPaymentAfterTheBillIsSettledIsRefused() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, "OWNER", visit, "CASH", 30000, 200);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + visit + "/payments", clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":1}"), 400);
    }

    @Test
    void paymentsAreCollectorsOnly() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        for (String role : NON_COLLECTORS) {
            pay(clinic, role, visit, "CASH", 100, 403);
        }
        assertThat(bill(clinic, visit).get("paidPaise").asLong()).isZero();
    }

    @Test
    void aPaymentBeforeTheVisitReachesBillingThatWouldSettleItIsRefusedCleanly() throws Exception {
        Clinic clinic = newClinic(true);
        JsonNode v = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        UUID visit = UUID.fromString(v.get("id").asText());
        // a part payment on a visit still at VITALS is fine
        pay(clinic, "OWNER", visit, "CASH", 100, 200);
        // settling it from VITALS cannot move it to DONE: clean 400, no payment kept
        pay(clinic, "OWNER", visit, "CASH", 29900, 400);
        assertThat(bill(clinic, visit).get("paidPaise").asLong()).isEqualTo(100L);
    }

    @Test
    void aFreeFollowUpWithNothingToPayCanStillBeClosedByMovingItToDone() throws Exception {
        Clinic clinic = newClinic(false);
        UUID patient = newPatient(clinic);
        String body = "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}";
        ok(HttpMethod.POST, VISITS, clinic.owner(), body, 201);
        JsonNode followUp = ok(HttpMethod.POST, VISITS, clinic.owner(), body, 201);
        assertThat(followUp.get("feePaise").asLong()).isZero();
        String id = followUp.get("id").asText();
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        JsonNode b = bill(clinic, UUID.fromString(id));
        assertThat(b.get("totalPaise").asLong()).isZero();
        assertThat(b.get("balancePaise").asLong()).isZero();
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"DONE\"}", 200);
    }

    // ---------------- refunds ----------------

    @Test
    void ownerRefundIsRecordedAsANegativePaymentAndTheVisitShowsUnderDues() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, "OWNER", visit, "CASH", 30000, 200);
        JsonNode refunded = ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/refund", clinic.owner(),
                "{\"amountPaise\":5000,\"mode\":\"CASH\",\"reason\":\"Service not given\"}", 200);
        assertThat(refunded.get("paidPaise").asLong()).isEqualTo(25000L);
        assertThat(refunded.get("balancePaise").asLong()).isEqualTo(5000L);
        JsonNode last = refunded.get("payments").get(refunded.get("payments").size() - 1);
        assertThat(last.get("amountPaise").asLong()).isEqualTo(-5000L);
        JsonNode counter = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(counter.get("dues").size()).isEqualTo(1);
        assertThat(counter.get("dues").get(0).get("balancePaise").asLong()).isEqualTo(5000L);
        assertThat(counter.get("paidToday").size()).isZero();
        // the whole amount can be refunded, not more
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/refund", clinic.owner(), "{\"amountPaise\":25000,\"mode\":\"UPI\",\"reason\":\"Cancelled\"}", 200);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + visit + "/refund", clinic.owner(), "{\"amountPaise\":1,\"mode\":\"UPI\",\"reason\":\"Extra\"}"), 400);
    }

    @Test
    void refundIsOwnerOnly() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, "OWNER", visit, "CASH", 30000, 200);
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            problem(call(HttpMethod.POST, BILLING + "/bills/" + visit + "/refund", clinic.as(role),
                    "{\"amountPaise\":100,\"mode\":\"CASH\",\"reason\":\"Try\"}"), 403);
        }
        assertThat(bill(clinic, visit).get("paidPaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void refundValidation() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        String url = BILLING + "/bills/" + visit + "/refund";
        // nothing collected yet
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":100,\"mode\":\"CASH\",\"reason\":\"No payment\"}"), 400);
        pay(clinic, "OWNER", visit, "CASH", 10000, 200);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":10001,\"mode\":\"CASH\",\"reason\":\"Too much\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":0,\"mode\":\"CASH\",\"reason\":\"Zero\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":-5,\"mode\":\"CASH\",\"reason\":\"Neg\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":100,\"mode\":\"CASH\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":100,\"mode\":\"CASH\",\"reason\":\"  \"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":100,\"mode\":\"GOLD\",\"reason\":\"x\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"mode\":\"CASH\",\"reason\":\"x\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + UUID.randomUUID() + "/refund", clinic.owner(),
                "{\"amountPaise\":100,\"mode\":\"CASH\",\"reason\":\"x\"}"), 404);
    }

    // ---------------- day closing ----------------

    @Test
    void dayClosingComparesCountedCashWithExpectedCashNetOfCashRefunds() throws Exception {
        Clinic clinic = newClinic();
        UUID v1 = visitInBilling(clinic);
        UUID v2 = visitInBilling(clinic);
        pay(clinic, "OWNER", v1, "CASH", 30000, 200);
        pay(clinic, "OWNER", v2, "UPI", 30000, 200);
        ok(HttpMethod.POST, BILLING + "/bills/" + v1 + "/refund", clinic.owner(), "{\"amountPaise\":4000,\"mode\":\"CASH\",\"reason\":\"Partial\"}", 200);
        JsonNode closed = ok(HttpMethod.POST, BILLING + "/day-closing", clinic.as("FRONT_DESK"),
                "{\"countedCashPaise\":25000,\"note\":\"Short by 100\"}", 201);
        assertThat(closed.get("expectedCashPaise").asLong()).isEqualTo(26000L);
        assertThat(closed.get("countedCashPaise").asLong()).isEqualTo(25000L);
        assertThat(closed.get("diffPaise").asLong()).isEqualTo(-1000L);
        assertThat(closed.get("note").asText()).isEqualTo("Short by 100");
        assertThat(closed.get("closedByName").asText()).isNotBlank();
        assertThat(closed.get("businessDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(closed.get("closedAt").asText()).isNotBlank();
    }

    @Test
    void closingTheSameDayTwiceIsConflict() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{\"countedCashPaise\":0}", 201);
        JsonNode p = problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{\"countedCashPaise\":0}"), 409);
        assertThat(p.get("type").asText()).endsWith("day-already-closed");
        problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.as("FRONT_DESK"), "{\"countedCashPaise\":5}"), 409);
    }

    @Test
    void closingWithNoPaymentsAndNoNoteWorksAndEachClinicClosesIndependently() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode closed = ok(HttpMethod.POST, BILLING + "/day-closing", a.owner(), "{\"countedCashPaise\":1000,\"note\":null}", 201);
        assertThat(closed.get("expectedCashPaise").asLong()).isZero();
        assertThat(closed.get("diffPaise").asLong()).isEqualTo(1000L);
        ok(HttpMethod.POST, BILLING + "/day-closing", b.owner(), "{\"countedCashPaise\":0}", 201);
    }

    @Test
    void dayClosingValidationAndRoles() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{\"countedCashPaise\":-1}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{\"countedCashPaise\":\"x\"}"), 400);
        problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{bad"), 400);
        for (String role : NON_COLLECTORS) {
            problem(call(HttpMethod.POST, BILLING + "/day-closing", clinic.as(role), "{\"countedCashPaise\":0}"), 403);
        }
        // none of the refused or invalid attempts closed the day
        ok(HttpMethod.POST, BILLING + "/day-closing", clinic.owner(), "{\"countedCashPaise\":0}", 201);
    }

    @Test
    void billingChangesAreAuditedWithoutPatientNames() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, "OWNER", visit, "CASH", 30000, 200);
        String events = ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=50", clinic.owner(), null, 200).toString();
        assertThat(events).contains("PAYMENT_RECORDED").doesNotContain("Ramesh").doesNotContain("Kulkarni");
    }

    @Test
    void unauthenticatedBillingCallIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BILLING + "/counter"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
}
