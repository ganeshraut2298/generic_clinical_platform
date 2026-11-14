package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-48-4 (ADR-022 D7, DEC-042 H14): the services a doctor ticks as "Bill today" in the Procedures section of an OPD
 * visit become lines of the visit bill, one line per item id, counter lines untouched; a bill that has any payment refuses
 * the removal or a quantity change of an EMR line with 409 already-billed and the whole save is rolled back. Real
 * database, real billing endpoints, as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrBillLinesIT extends EmrPhase3Support {

    private record Ctx(Clinic clinic, Rec rec) {
    }

    private Ctx ctx() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        showProcedures(clinic);
        return new Ctx(clinic, startRecord(clinic, doctor));
    }

    private ObjectNode serviceItem(String serviceId, String text, int qty, String billing) {
        ObjectNode item = keyedItem(serviceId, text);
        item.put("attr1", String.valueOf(qty));
        item.put("attr2", billing);
        return item;
    }

    private ObjectNode procedures(ObjectNode... items) {
        return oneField("procedures", "procedure", listValue("SERVICES", items));
    }

    private JsonNode bill(Ctx c) throws Exception {
        return ok(HttpMethod.GET, BILLING + "/bills/" + c.rec().visitId(), c.clinic().owner(), null, 200);
    }

    private static List<JsonNode> emrLines(JsonNode bill) {
        List<JsonNode> lines = new ArrayList<>();
        for (JsonNode item : bill.get("items")) {
            if ("EMR".equals(item.get("source").asText())) {
                lines.add(item);
            }
        }
        return lines;
    }

    private void pay(Ctx c, long paise) throws Exception {
        ok(HttpMethod.POST, BILLING + "/bills/" + c.rec().visitId() + "/payments", c.clinic().owner(),
                "{\"mode\":\"CASH\",\"amountPaise\":" + paise + "}", 200);
    }

    @Test
    void aBillTodayServiceBecomesOneEmrLineAtTheTariffPriceAndACounterLineIsNeverTouched() throws Exception {
        Ctx c = ctx();
        String counter = serviceId(c.clinic(), "Counter test", 80);
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        ok(HttpMethod.POST, BILLING + "/bills/" + c.rec().visitId() + "/items", c.clinic().owner(),
                "{\"serviceId\":\"" + counter + "\",\"qty\":1}", 200);
        long consultation = bill(c).get("items").get(0).get("amountPaise").asLong();
        Rec saved = save(c.rec(), procedures(serviceItem(dressing, "Dressing", 2, "BILL_TODAY")));
        JsonNode bill = bill(c);
        assertThat(emrLines(bill)).hasSize(1);
        JsonNode line = emrLines(bill).get(0);
        assertThat(line.get("qty").asInt()).isEqualTo(2);
        assertThat(line.get("amountPaise").asLong()).as("the tariff price of one").isEqualTo(15000L);
        assertThat(line.get("removable").asBoolean()).isTrue();
        long counterLines = 0;
        for (JsonNode item : bill.get("items")) {
            if ("COUNTER".equals(item.get("source").asText()) && item.get("removable").asBoolean()) {
                counterLines++;
            }
        }
        assertThat(counterLines).as("the counter line stays").isEqualTo(1);
        assertThat(bill.get("subtotalPaise").asLong()).isEqualTo(consultation + 8000 + 2 * 15000);
        // the item in the record says it is on the bill, not yet on a paid bill
        assertThat(valueOf(saved.record(), "procedures", "procedure").get("items").get(0).get("billed").asText()).isEqualTo("ON_BILL");
        assertThat(valueOf(saved.record(), "procedures", "procedure").get("items").get(0).get("unitPricePaise").asLong()).isEqualTo(15000L);
        // clearing the item takes only the EMR line away
        save(saved, procedures());
        JsonNode after = bill(c);
        assertThat(emrLines(after)).isEmpty();
        assertThat(after.get("subtotalPaise").asLong()).isEqualTo(consultation + 8000);
    }

    @Test
    void theSameItemSavedAgainOrRetriedGivesOneLineAndAChangedQuantityUpdatesIt() throws Exception {
        Ctx c = ctx();
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        ObjectNode item = serviceItem(dressing, "Dressing", 1, "BILL_TODAY");
        Rec first = save(c.rec(), procedures(item));
        Rec again = save(first, procedures((ObjectNode) valueOf(first.record(), "procedures", "procedure").get("items").get(0).deepCopy()));
        assertThat(emrLines(bill(c))).as("one line per item id").hasSize(1);
        ObjectNode three = (ObjectNode) valueOf(again.record(), "procedures", "procedure").get("items").get(0).deepCopy();
        three.put("attr1", "3");
        save(again, procedures(three));
        List<JsonNode> lines = emrLines(bill(c));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("qty").asInt()).isEqualTo(3);
        assertThat(count(c.clinic(), "select count(*) from billing.bill_item where source_kind = 'EMR'")).isEqualTo(1);
        assertThat(auditCount(c.clinic(), "BILL_ITEM_ADDED_FROM_EMR")).isEqualTo(1);
        assertThat(auditCount(c.clinic(), "BILL_ITEM_QTY_CHANGED_FROM_EMR")).isEqualTo(1);
    }

    @Test
    void onlyActiveBillTodayItemsGiveALineAndNoChargeInPackageAndInactiveItemsGiveNone() throws Exception {
        Ctx c = ctx();
        String a = serviceId(c.clinic(), "Service A", 100);
        String b = serviceId(c.clinic(), "Service B", 200);
        String d = serviceId(c.clinic(), "Service C", 300);
        String e = serviceId(c.clinic(), "Service D", 400);
        ObjectNode off = serviceItem(e, "Service D", 1, "BILL_TODAY");
        off.put("active", false);
        Rec saved = save(c.rec(), procedures(serviceItem(a, "Service A", 1, "BILL_TODAY"), serviceItem(b, "Service B", 1, "NO_CHARGE"),
                serviceItem(d, "Service C", 1, "IN_PACKAGE"), off));
        List<JsonNode> lines = emrLines(bill(c));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("amountPaise").asLong()).isEqualTo(10000L);
        // the other three are on the record and on no bill
        assertThat(valueOf(saved.record(), "procedures", "procedure").get("items")).hasSize(4);
        assertThat(valueOf(saved.record(), "procedures", "procedure").get("items").get(1).hasNonNull("billed")).isFalse();
        // turning an inactive item on adds its line; turning a billed one to No charge removes it
        ObjectNode on = (ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(3).deepCopy();
        on.put("active", true);
        ObjectNode free = (ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(0).deepCopy();
        free.put("attr2", "NO_CHARGE");
        save(saved, procedures(free, (ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(1).deepCopy(),
                (ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(2).deepCopy(), on));
        List<JsonNode> after = emrLines(bill(c));
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("amountPaise").asLong()).isEqualTo(40000L);
    }

    @Test
    void aBillWithAPaymentRefusesTheRemovalOrAQuantityChangeWith409AlreadyBilledAndTheWholeSaveIsRolledBack() throws Exception {
        Ctx c = ctx();
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        Rec saved = save(c.rec(), procedures(serviceItem(dressing, "Dressing", 1, "BILL_TODAY")));
        pay(c, 10000);
        assertThat(valueOf(current(saved), "procedures", "procedure").get("items").get(0).get("billed").asText()).isEqualTo("ON_PAID_BILL");
        JsonNode billBefore = bill(c);
        long revision = current(saved).get("revision").asLong();
        long savesBefore = auditCount(c.clinic(), "EMR_RECORD_DRAFT_SAVED");
        // a removal together with a change in another section: both are refused
        JsonNode problem = refuseSave(saved, 409, "already-billed", procedures(),
                complaintsSection(typedItem("Fever")));
        assertThat(problem.get("detail").asText()).isEqualTo("Already billed. Ask billing to adjust.");
        ObjectNode more = (ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(0).deepCopy();
        more.put("attr1", "2");
        refuseSave(saved, 409, "already-billed", procedures(more));
        // nothing changed: the record, the other section, the bill and the audit trail
        JsonNode after = current(saved);
        assertThat(after.get("revision").asLong()).isEqualTo(revision);
        assertThat(valueOf(after, "procedures", "procedure").get("items")).hasSize(1);
        assertThat(valueOf(after, "procedures", "procedure").get("items").get(0).get("attr1").asText()).isEqualTo("1");
        assertThat(recordSection(after, "complaints").get("values").size()).isZero();
        JsonNode billAfter = bill(c);
        assertThat(billAfter).isEqualTo(billBefore);
        assertThat(auditCount(c.clinic(), "EMR_RECORD_DRAFT_SAVED")).isEqualTo(savesBefore);
        assertThat(auditCount(c.clinic(), "BILL_ITEM_REMOVED_FROM_EMR")).isZero();
        assertThat(auditCount(c.clinic(), "BILL_ITEM_QTY_CHANGED_FROM_EMR")).isZero();
        // a save that leaves the item as it is still works on a paid bill
        Rec same = save(saved, procedures((ObjectNode) valueOf(after, "procedures", "procedure").get("items").get(0).deepCopy()),
                complaintsSection(typedItem("Fever")));
        assertThat(recordSection(same.record(), "complaints").get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void aNewLineMayStillBeAddedToABillThatHasAPaymentAndItRaisesTheAmountDue() throws Exception {
        Ctx c = ctx();
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        String injection = serviceId(c.clinic(), "Injection", 70);
        Rec saved = save(c.rec(), procedures(serviceItem(dressing, "Dressing", 1, "BILL_TODAY")));
        pay(c, 10000);
        long balanceBefore = bill(c).get("balancePaise").asLong();
        Rec added = save(saved, procedures((ObjectNode) valueOf(saved.record(), "procedures", "procedure").get("items").get(0).deepCopy(),
                serviceItem(injection, "Injection", 1, "BILL_TODAY")));
        assertThat(valueOf(added.record(), "procedures", "procedure").get("items")).hasSize(2);
        JsonNode bill = bill(c);
        assertThat(emrLines(bill)).hasSize(2);
        assertThat(bill.get("balancePaise").asLong()).isEqualTo(balanceBefore + 7000L);
        assertThat(bill.get("paidPaise").asLong()).isEqualTo(10000L);
    }

    @Test
    void anUnknownInactiveTypedOrAnotherClinicsServiceIs400ValidationAndNothingIsStoredOrBilled() throws Exception {
        Ctx c = ctx();
        Clinic other = newClinic();
        String foreign = serviceId(other, "Foreign service", 90);
        String retired = serviceId(c.clinic(), "Retired service", 60);
        ok(HttpMethod.PATCH, SERVICES + "/" + retired, c.clinic().owner(), "{\"active\":false}", 200);
        ObjectNode[] bad = {serviceItem(newId(), "Nothing", 1, "BILL_TODAY"), serviceItem(foreign, "Foreign", 1, "BILL_TODAY"),
                serviceItem(retired, "Retired", 1, "BILL_TODAY"), serviceItem("not-a-uuid", "Broken", 1, "BILL_TODAY"), typedItem("Typed service")};
        for (ObjectNode item : bad) {
            JsonNode problem = refuseSave(c.rec(), 400, "validation", procedures(item));
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("sections[0].values.procedure");
        }
        assertThat(emrLines(bill(c))).isEmpty();
        assertThat(current(c.rec()).get("revision").asLong()).isEqualTo(c.rec().revision());
        // a quantity or billing value outside the lists is refused too
        String good = serviceId(c.clinic(), "Good service", 40);
        ObjectNode qty = serviceItem(good, "Good", 1, "BILL_TODAY");
        qty.put("attr1", "5");
        refuseSave(c.rec(), 400, "validation", procedures(qty));
        ObjectNode billing = serviceItem(good, "Good", 1, "BILL_TODAY");
        billing.put("attr2", "FREE");
        refuseSave(c.rec(), 400, "validation", procedures(billing));
        assertThat(count(c.clinic(), "select count(*) from billing.bill_item where source_kind = 'EMR'")).isZero();
    }

    @Test
    void theBillAuditsCarryIdsAndMoneyOnlyAndALaterPriceChangeNeverChangesALine() throws Exception {
        Ctx c = ctx();
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        Rec saved = save(c.rec(), procedures(serviceItem(dressing, "Dressing", 1, "BILL_TODAY")));
        ok(HttpMethod.PATCH, SERVICES + "/" + dressing, c.clinic().owner(), "{\"priceRupees\":999}", 200);
        assertThat(emrLines(bill(c)).get(0).get("amountPaise").asLong()).as("the price when it was added").isEqualTo(15000L);
        save(saved, procedures());
        List<List<String>> added = auditRows(c.clinic(), "BILL_ITEM_ADDED_FROM_EMR");
        List<List<String>> removed = auditRows(c.clinic(), "BILL_ITEM_REMOVED_FROM_EMR");
        assertThat(added).hasSize(1);
        assertThat(removed).hasSize(1);
        JsonNode after = objectMapper.readTree(added.get(0).get(2));
        assertThat(after.get("visitId").asText()).isEqualTo(c.rec().visitId());
        assertThat(after.get("serviceId").asText()).isEqualTo(dressing);
        assertThat(after.get("qty").asInt()).isEqualTo(1);
        assertThat(after.get("amountPaise").asLong()).isEqualTo(15000L);
        assertThat(after.size()).isEqualTo(4);
        for (List<String> row : added) {
            assertThat(row.get(1) + row.get(2)).doesNotContain("Dressing").doesNotContain("Ramesh");
        }
    }

    @Test
    void theBillingRoleSeesTheEmrLineOnTheCounterButCannotWriteTheEmrAndAnotherClinicNeverSeesTheBill() throws Exception {
        Ctx c = ctx();
        String dressing = serviceId(c.clinic(), "Dressing", 150);
        save(c.rec(), procedures(serviceItem(dressing, "Dressing", 1, "BILL_TODAY")));
        JsonNode seen = ok(HttpMethod.GET, BILLING + "/bills/" + c.rec().visitId(), c.clinic().as("BILLING"), null, 200);
        assertThat(emrLines(seen)).hasSize(1);
        Staff billing = addNonDoctor(c.clinic(), "BILLING");
        problemOf(HttpMethod.PUT, RECORDS + "/" + c.rec().id() + "/draft", billing.token(c.clinic()),
                draftBody(c.rec().revision() + 1, "ASSESSMENT", null, procedures()).toString(), 403, "forbidden");
        Clinic other = newClinic();
        problem(call(HttpMethod.GET, BILLING + "/bills/" + c.rec().visitId(), other.owner(), null), 404);
        assertThat(count(other, "select count(*) from billing.bill_item where source_kind = 'EMR'")).isZero();
    }
}
