package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-80-8 to SAH-80-10 and SAH-80-21 (pharmacy-sales.yaml 0.3.7, WP-J and WP-N, DEC-089): the Schedule H1 bill (prescription,
 * missing prescriber, override), the H1 register rows and the Cancelled reversal, Schedule X bills with the second signer and the
 * ledger signer on COUNTER_SALE, the Schedule X register and entries, and the h1-register report masked per store (name and address
 * in full only for the owner and a pharmacist member, export owner and pharmacist only). Written, not run. Test plan:
 * docs/test-plans/SAH-79.md section 11 (T80-9-*, T80-10-*, T80-21-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacySalesRegistersApiIT extends Sah80SalesSupport {

    private static final String NAME = "Test Patient";

    private Map<String, Object> h1Cart(Sale s, int qty, Map<String, Object> prescription) {
        Map<String, Object> c = new LinkedHashMap<>(cart(walkIn(NAME), null, cl(s.h1Id(), qty)));
        c.put("prescription", prescription);
        return c;
    }

    private JsonNode h1Bill(Sale s, Pn who, int qty) throws Exception {
        Map<String, Object> c = h1Cart(s, qty, typedRx(NAME));
        return post(who, url(s.w().pharmacyId(), "bills"), billBody(quote(who, s.w(), c, 200), c, "CASH"), 201);
    }

    private Map<String, Object> xCart(Sale s, int qty) {
        Map<String, Object> c = new LinkedHashMap<>(cart(walkIn(NAME), null, cl(s.xId(), qty)));
        c.put("prescription", typedRx(NAME));
        return c;
    }

    private JsonNode xBill(Sale s, Pn who, int qty, Map<String, Object> signer, int expected) throws Exception {
        Map<String, Object> c = xCart(s, qty);
        Map<String, Object> body = billBody(quote(who, s.w(), c, 200), c, "CASH");
        body.put("secondSigner", signer);
        return post(who, url(s.w().pharmacyId(), "bills"), body, expected);
    }

    // ---------------- 80-9 Schedule H1 ----------------

    @Test
    void anH1BillNeedsAPrescriptionAndAPharmacistAndWritesARegisterRowWithThePatientName() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Map<String, Object> noRx = new LinkedHashMap<>(cart(walkIn(NAME), null, cl(s.h1Id(), 2)));
        JsonNode q = quote(w.keeper(), w, noRx, 200);
        assertThat(q.get("needsPrescription").asBoolean()).isTrue();
        assertThat(q.get("blockers").toString()).contains("PRESCRIPTION");
        assertThat(q.get("canSave").asBoolean()).isFalse();
        assertThat(post(w.keeper(), url(w.pharmacyId(), "bills"), billBody(q, noRx, "CASH"), 400).toString()).contains("prescription");
        // a cashier cannot complete an H1 bill
        Map<String, Object> asked = h1Cart(s, 2, typedRx(NAME));
        JsonNode cq = quote(s.cashier(), w, asked, 200);
        assertThat(cq.get("needsPharmacist").asBoolean()).isTrue();
        JsonNode denied = post(s.cashier(), url(w.pharmacyId(), "bills"), billBody(cq, asked, "CASH"), 403);
        assertType(denied, "pharmacist-required");
        assertThat(billsSaved(w)).isZero();
        JsonNode bill = h1Bill(s, w.keeper(), 2);
        assertThat(bill.get("pharmacist").get("name").asText()).isEqualTo("Pia Pharmacist");
        JsonNode reg = get(w.keeper(), url(w.pharmacyId(), "registers/h1"), 200);
        assertThat(reg.get("entriesToday").asInt()).isOne();
        JsonNode row = reg.get("items").get(0);
        assertThat(row.get("kind").asText()).isEqualTo("ENTRY");
        assertThat(row.get("patientName").asText()).isEqualTo(NAME);
        assertThat(row.get("patientAddress").asText()).isEqualTo("12 Test Road, Testville");
        assertThat(row.get("billNo").asText()).isEqualTo(bill.get("billNo").asText());
        assertThat(row.get("qty").asInt()).isEqualTo(2);
        assertThat(row.get("rxCopy").get("source").asText()).isEqualTo("TYPED");
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REGISTER_VIEWED'")).isOne();
        assertThat(get(s.cashier(), url(w.pharmacyId(), "registers/h1"), 403).get("type").asText()).contains("forbidden");
    }

    @Test
    void aMissingPrescriberBlocksTheBillUntilAPharmacistOverridesWithAReason() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Map<String, Object> rx = typedRx(NAME);
        rx.put("prescriberName", null);
        rx.put("prescriberRegNo", null);
        Map<String, Object> c = h1Cart(s, 1, rx);
        JsonNode q = quote(w.keeper(), w, c, 200);
        assertThat(q.get("blockers").toString()).contains("H1_PRESCRIBER");
        assertThat(q.get("canSave").asBoolean()).isFalse();
        Map<String, Object> over = new LinkedHashMap<>(c);
        over.put("lines", List.of(m("productId", s.h1Id(), "qty", 1, "h1Override", m("reasonKey", "h1-override.prescriber-confirmed", "note", null))));
        JsonNode oq = quote(w.keeper(), w, over, 200);
        assertThat(oq.get("canSave").asBoolean()).isTrue();
        assertThat(oq.get("lines").get(0).get("h1Overridden").asBoolean()).isTrue();
        post(w.keeper(), url(w.pharmacyId(), "bills"), billBody(oq, over, "CASH"), 201);
        JsonNode reg = get(w.keeper(), url(w.pharmacyId(), "registers/h1"), 200);
        assertThat(reg.get("missingPrescriberToday").asInt()).isOne();
        assertThat(reg.get("items").get(0).get("override")).isNotNull();
        assertThat(reg.get("items").get(0).get("override").isNull()).isFalse();
        // an override reason that does not exist, and the override by a cashier, are refused
        Map<String, Object> badReason = new LinkedHashMap<>(c);
        badReason.put("lines", List.of(m("productId", s.h1Id(), "qty", 1, "h1Override", m("reasonKey", "h1-override.no-such", "note", null))));
        post(w.keeper(), url(w.pharmacyId(), "sale/quote"), badReason, 400);
        assertThat(call(HttpMethod.POST, url(w.pharmacyId(), "sale/quote"), s.cashier().auth(), js(over)).andReturn().getResponse().getStatus())
                .as("the override is for a pharmacist").isIn(400, 403);
    }

    @Test
    void cancellingAnH1BillAddsACancelledReversalToTheRegister() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = h1Bill(s, w.keeper(), 2);
        Map<String, Object> cancel = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-item", "note", null, "backOnShelf", true, "secondSigner", null);
        post(w.keeper(), url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel"), cancel, 200);
        JsonNode items = get(w.keeper(), url(w.pharmacyId(), "registers/h1"), 200).get("items");
        assertThat(items).hasSize(2);
        JsonNode reversal = items.get(0);
        assertThat(reversal.get("kind").asText()).isEqualTo("REVERSAL");
        assertThat(reversal.get("reversalReason").asText()).isEqualTo("CANCELLED");
        assertThat(reversal.get("qty").asInt()).isEqualTo(-2);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.h1_register_row")).as("append only: both rows stay").isEqualTo(2);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void theH1RegisterRangeIsCheckedAndAnotherClinicSeesNothing() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        h1Bill(s, w.keeper(), 1);
        String from = todayIst().minusMonths(13).toString();
        assertType(problem(call(HttpMethod.GET, url(w.pharmacyId(), "registers/h1?from=" + from + "&to=" + todayIst()), w.keeper().auth(), null), 400), "range-too-long");
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "registers/h1?from=2026-13-45"), w.keeper().auth(), null), 400);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "registers/h1"), other.owner(), null), 404);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "registers/h1?prescriber=nobody"), 200).get("items")).isEmpty();
    }

    // ---------------- 80-10 Schedule X ----------------

    @Test
    void aScheduleXBillNeedsTheSecondSignerAndTheLedgerAndRegisterCarryBothSigners() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode missing = xBill(s, w.keeper(), 3, null, 400);
        assertFieldMessage(missing, "secondSigner", "A second signature is needed for Schedule X items.");
        assertType(xBill(s, w.keeper(), 3, signer(w.keeper(), pin), 403), "second-signer-same");
        assertThat(billsSaved(w)).isZero();
        assertThat(bal(w, w.pharmacyId(), "XB001", "AVAILABLE")).isEqualTo(20);
        JsonNode denied = xBill(s, s.cashier(), 3, signer(w.second(), pin), 403);
        assertType(denied, "pharmacist-required");
        JsonNode bill = xBill(s, w.keeper(), 3, signer(w.second(), pin), 201);
        assertThat(bal(w, w.pharmacyId(), "XB001", "AVAILABLE")).isEqualTo(17);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'COUNTER_SALE' and second_signer_membership_id = ?::uuid",
                w.second().membershipId().toString())).as("the ledger line of the sale carries the second signer").isOne();
        JsonNode reg = get(w.keeper(), url(w.pharmacyId(), "registers/schedule-x"), 200);
        JsonNode item = null;
        for (JsonNode i : reg.get("items")) {
            if (s.xId().equals(i.get("product").get("productId").asText())) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("issued").asInt()).isEqualTo(3);
        assertThat(item.get("book").asInt()).isEqualTo(17);
        assertThat(item.get("status").asText()).isEqualTo("NOT_COUNTED");
        JsonNode entries = get(w.keeper(), url(w.pharmacyId(), "registers/schedule-x/sales"), 200).get("items");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("sourceType").asText()).isEqualTo("BILL");
        assertThat(entries.get(0).get("documentNo").asText()).isEqualTo(bill.get("billNo").asText());
        assertThat(entries.get(0).get("firstSigner").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(entries.get(0).get("secondSigner").get("name").asText()).isEqualTo("Second Owner");
        assertThat(get(s.cashier(), url(w.pharmacyId(), "registers/schedule-x"), 403).get("type").asText()).contains("forbidden");
        assertLedgerEqualsBalances(w);
    }

    @Test
    void cancellingAScheduleXBillAddsAReversalAndPutsTheStockBack() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode bill = xBill(s, w.keeper(), 2, signer(w.second(), pin), 201);
        Map<String, Object> cancel = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-quantity", "note", null, "backOnShelf", true, "secondSigner", signer(w.second(), pin));
        post(w.keeper(), url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel"), cancel, 200);
        assertThat(bal(w, w.pharmacyId(), "XB001", "AVAILABLE")).isEqualTo(20);
        JsonNode entries = get(w.keeper(), url(w.pharmacyId(), "registers/schedule-x/sales"), 200).get("items");
        assertThat(entries).hasSize(2);
        assertThat(entries.toString()).contains("BILL_CANCEL").contains("REVERSAL");
        assertLedgerEqualsBalances(w);
    }

    // ---------------- 80-21 the h1-register report, masked per store (DEC-089) ----------------

    @Test
    void theH1ReportShowsTheNameInFullOnlyToTheOwnerAndAPharmacistMemberAndMasksItForOthers() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        h1Bill(s, w.keeper(), 1);
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        String path = PH + "/reports/h1-register?storeId=" + w.pharmacyId();
        JsonNode asKeeper = get(w.keeper(), path, 200);
        assertThat(asKeeper.get("rows").toString()).contains(NAME).contains("12 Test Road");
        JsonNode asOwner = get(owner(w), path, 200);
        assertThat(asOwner.get("rows").toString()).contains(NAME);
        JsonNode asHod = get(hod, path, 200);
        assertThat(asHod.get("rows").toString()).as("masked for a head of department who is not a pharmacist").doesNotContain(NAME).doesNotContain("12 Test Road").contains("T***");
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REGISTER_VIEWED'")).isEqualTo(3);
        assertThat(call(HttpMethod.GET, path, s.cashier().auth(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
        // a pharmacist of another store is not a member of this one
        String annex = idOf(createStore(w.clinic(), "Annex Pharmacy", "ANX", "PHARMACY"));
        Pn other = crew(w.clinic(), "PHARMACIST", "Ola Other");
        mapStaff(w.clinic(), annex, other, "STORE_USER");
        int status = call(HttpMethod.GET, path, other.auth(), null).andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 404);
        String all = call(HttpMethod.GET, PH + "/reports/h1-register", other.auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(all).as("no unmasked name of a store the pharmacist is not a member of").doesNotContain(NAME);
    }

    @Test
    void theH1ReportExportIsForTheOwnerAndAPharmacistMemberOnlyAndIsAudited() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        h1Bill(s, w.keeper(), 1);
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        String path = PH + "/reports/h1-register/export?storeId=" + w.pharmacyId();
        assertType(problem(call(HttpMethod.GET, path, hod.auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.GET, path, s.cashier().auth(), null), 403), "forbidden");
        String csv = call(HttpMethod.GET, path, w.keeper().auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(NAME);
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REGISTER_EXPORTED'")).isOne();
        String regPath = url(w.pharmacyId(), "registers/h1/export");
        assertThat(call(HttpMethod.GET, regPath, hod.auth(), null).andReturn().getResponse().getStatus()).isIn(403);
    }
}
