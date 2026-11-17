package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-10 / DEC-054: the billing counter keeps an unpaid bill of an earlier day (Billing stage under awaiting, Done with
 * a balance under dues, oldest first), drops fully paid earlier visits and anything older than 30 days, and never
 * shows another clinic's visit. Rows are backdated only with {@link BackdateSupport}, on visits of a fresh clinic.
 */
@OnTestDatabase
class BillingCounterEarlierDaysIT extends SweepSupport {

    private static final String BILLING = "/api/v1/billing";
    private static final String VISITS = "/api/v1/opd/visits";

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

    private void pay(Clinic clinic, UUID visit, long paise) throws Exception {
        ok(HttpMethod.POST, BILLING + "/bills/" + visit + "/payments", clinic.owner(),
                "{\"mode\":\"CASH\",\"amountPaise\":" + paise + "}", 200);
    }

    private void backdate(Clinic clinic, UUID visit, int daysAgo) throws Exception {
        BackdateSupport.setVisitCreatedAt(db(), clinic.tenantId(), visit.toString(), BackdateSupport.noon(daysAgo));
    }

    private JsonNode counter(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(row -> out.add(row.get("visitId").asText()));
        return out;
    }

    private static List<String> allIds(JsonNode counter) {
        List<String> out = new ArrayList<>();
        out.addAll(ids(counter.get("awaiting")));
        out.addAll(ids(counter.get("dues")));
        out.addAll(ids(counter.get("paidToday")));
        return out;
    }

    @Test
    void unpaidVisitFromYesterdayIsAwaitingWithItsFee() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        backdate(clinic, visit, 1);
        JsonNode c = counter(clinic);
        assertThat(ids(c.get("awaiting"))).containsExactly(visit.toString());
        JsonNode row = c.get("awaiting").get(0);
        assertThat(row.get("totalPaise").asLong()).isEqualTo(30000L);
        assertThat(row.get("balancePaise").asLong()).isEqualTo(30000L);
        assertThat(c.get("dues").size()).isZero();
        assertThat(c.get("paidToday").size()).isZero();
    }

    @Test
    void earlierDoneVisitWithABalanceIsUnderDues() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, visit, 10000);
        BackdateSupport.setVisitStage(db(), clinic.tenantId(), visit.toString(), "DONE");
        backdate(clinic, visit, 3);
        JsonNode c = counter(clinic);
        assertThat(ids(c.get("dues"))).containsExactly(visit.toString());
        assertThat(c.get("dues").get(0).get("balancePaise").asLong()).isEqualTo(20000L);
        assertThat(c.get("awaiting").size()).isZero();
    }

    @Test
    void earlierFullyPaidVisitDoesNotAppear() throws Exception {
        Clinic clinic = newClinic();
        UUID visit = visitInBilling(clinic);
        pay(clinic, visit, 30000); // balance zero: the visit becomes DONE
        backdate(clinic, visit, 1);
        assertThat(allIds(counter(clinic))).doesNotContain(visit.toString());
    }

    @Test
    void visitOlderThanThirtyDaysDoesNotAppearAndThirtyDaysStillDoes() throws Exception {
        Clinic clinic = newClinic();
        UUID inside = visitInBilling(clinic);
        UUID outside = visitInBilling(clinic);
        backdate(clinic, inside, 30);
        backdate(clinic, outside, 31);
        List<String> shown = allIds(counter(clinic));
        assertThat(shown).contains(inside.toString());
        assertThat(shown).doesNotContain(outside.toString());
    }

    @Test
    void earlierRowsAreOldestFirstAndTodayStaysOnTheCounter() throws Exception {
        Clinic clinic = newClinic();
        UUID today = visitInBilling(clinic);
        UUID yesterday = visitInBilling(clinic);
        UUID older = visitInBilling(clinic);
        UUID oldest = visitInBilling(clinic);
        backdate(clinic, yesterday, 1);
        backdate(clinic, older, 5);
        backdate(clinic, oldest, 20);
        List<String> awaiting = ids(counter(clinic).get("awaiting"));
        assertThat(awaiting).containsExactlyInAnyOrder(today.toString(), yesterday.toString(), older.toString(),
                oldest.toString());
        assertThat(awaiting.indexOf(oldest.toString())).isLessThan(awaiting.indexOf(older.toString()));
        assertThat(awaiting.indexOf(older.toString())).isLessThan(awaiting.indexOf(yesterday.toString()));

        // dues: oldest first as well
        UUID dueNew = visitInBilling(clinic);
        UUID dueOld = visitInBilling(clinic);
        for (UUID v : List.of(dueNew, dueOld)) {
            pay(clinic, v, 10000);
            BackdateSupport.setVisitStage(db(), clinic.tenantId(), v.toString(), "DONE");
        }
        backdate(clinic, dueNew, 2);
        backdate(clinic, dueOld, 12);
        List<String> dues = ids(counter(clinic).get("dues"));
        assertThat(dues).containsExactly(dueOld.toString(), dueNew.toString());
    }

    @Test
    void anotherClinicsEarlierVisitNeverAppears() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID inA = visitInBilling(a);
        UUID inB = visitInBilling(b);
        backdate(a, inA, 2);
        backdate(b, inB, 2);
        assertThat(allIds(counter(a))).containsExactly(inA.toString());
        assertThat(allIds(counter(b))).containsExactly(inB.toString());
    }
}
