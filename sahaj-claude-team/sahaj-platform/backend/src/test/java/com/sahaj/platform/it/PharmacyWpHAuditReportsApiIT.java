package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * SAH-79-17 to SAH-79-19 (pharmacy.yaml 0.3.6, WP-H): the stock audit lifecycle (start, blind count sheet, counts, submit,
 * variance review, recount, stock moved, approve and post, the second person rule, the short-stock refusal and its replay), the
 * blind count rule 24 over every read, the audit line guard in the database, the reports (catalogue tiers, run, filters, range
 * limit, the CSV with its error answers, the 50,000 row cap) and the approvals inbox filter. Test plan: docs/test-plans/SAH-79.md
 * section 9 (T79-17-*, T79-18-*, T79-19-*, T79-H-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpHAuditReportsApiIT extends Sah79StoreUseSupport {

    private static final Set<String> SHEET_KEYS = Set.of("auditLineId", "product", "batch", "rack", "barcode", "counted", "notCounted",
            "notCountedReason", "recount");
    private static final String COUNT_MISMATCH = "stock-adjustment.count-mismatch";

    // ---------------- helpers ----------------

    private String audits(String storeId) {
        return url(storeId, "stock-audits");
    }

    private JsonNode startAll(Pn who, String storeId, int expected) throws Exception {
        return post(who, audits(storeId), m("clientKey", UUID.randomUUID().toString(), "scope", "ALL_AVAILABLE"), expected);
    }

    private JsonNode startProducts(Pn who, String storeId, List<String> products, int expected) throws Exception {
        return post(who, audits(storeId), m("clientKey", UUID.randomUUID().toString(), "scope", "PRODUCTS", "productIds", products), expected);
    }

    private JsonNode sheet(Pn who, String storeId, JsonNode audit) throws Exception {
        return get(who, audits(storeId) + "/" + idOf(audit) + "/count-sheet", 200);
    }

    private JsonNode lineOfBatch(JsonNode sheet, String batchNo) {
        for (JsonNode l : sheet.get("items")) {
            if (batchNo.equalsIgnoreCase(l.get("batch").get("batchNo").asText())) {
                return l;
            }
        }
        throw new AssertionError("No count line for " + batchNo);
    }

    private Map<String, Object> cnt(JsonNode line, int counted) {
        return m("auditLineId", line.get("auditLineId").asText(), "counted", counted, "notCounted", false);
    }

    private JsonNode save(Pn who, String storeId, JsonNode audit, long version, List<Map<String, Object>> lines, int expected) throws Exception {
        return put(who, audits(storeId) + "/" + idOf(audit) + "/counts", m("version", version, "lines", lines), expected);
    }

    private JsonNode put(Pn who, String path, Map<String, Object> body, int expected) throws Exception {
        return expected < 300 ? ok(HttpMethod.PUT, path, who.auth(), js(body), expected)
                : problem(call(HttpMethod.PUT, path, who.auth(), js(body)), expected);
    }

    private JsonNode submit(Pn who, String storeId, JsonNode audit, int expected) throws Exception {
        return post(who, audits(storeId) + "/" + idOf(audit) + "/submit",
                m("clientKey", UUID.randomUUID().toString(), "version", versionNow(storeId, audit)), expected);
    }

    private long versionNow(String storeId, JsonNode audit) throws Exception {
        return ok(HttpMethod.GET, audits(storeId) + "/" + idOf(audit), currentOwner.auth(), null, 200).get("version").asLong();
    }

    private Pn currentOwner;

    private JsonNode variance(Pn who, String storeId, JsonNode audit, int expected) throws Exception {
        return get(who, audits(storeId) + "/" + idOf(audit) + "/variance", expected);
    }

    private JsonNode reasons(Pn who, String storeId, JsonNode audit, JsonNode review) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : review.get("lines")) {
            lines.add(m("auditLineId", l.get("auditLineId").asText(), "reasonKey", COUNT_MISMATCH, "note", null));
        }
        return put(who, audits(storeId) + "/" + idOf(audit) + "/variance", m("version", review.get("version").asLong(), "lines", lines), 200);
    }

    private JsonNode approveAndPost(Pn who, String storeId, JsonNode audit, int expected) throws Exception {
        return post(who, audits(storeId) + "/" + idOf(audit) + "/approve-and-post",
                m("clientKey", UUID.randomUUID().toString(), "version", versionNow(storeId, audit)), expected);
    }

    /** An owner-bound world: the owner reviews, the keeper (pharmacist, store user) counts. */
    private Uw auditWorld() throws Exception {
        Uw u = useWorld();
        currentOwner = new Pn(u.w().clinic(), null, null, null, "OWNER", u.w().clinic().owner());
        return u;
    }

    /** Counts the pharmacy: TESTB001 98 (a loss of 2), TESTB003 3 and TESTB002 40; returns the started audit after the keeper's submit. */
    private JsonNode countedAndSubmitted(Uw u, int a001) throws Exception {
        Sw w = u.w();
        JsonNode audit = startAll(w.keeper(), w.pharmacyId(), 201);
        JsonNode sheet = sheet(w.keeper(), w.pharmacyId(), audit);
        save(w.keeper(), w.pharmacyId(), audit, sheet.get("version").asLong(),
                List.of(cnt(lineOfBatch(sheet, "TESTB001"), a001), cnt(lineOfBatch(sheet, "TESTB003"), 3), cnt(lineOfBatch(sheet, "TESTB002"), 40)), 200);
        return submit(w.keeper(), w.pharmacyId(), audit, 200);
    }

    // ---------------- 79-17 lifecycle ----------------

    @Test
    void anAuditIsStartedCountedSubmittedReviewedAndPostedWithTheLedgerEqualToTheBalances() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode audit = startAll(w.keeper(), w.pharmacyId(), 201);
        assertThat(audit.get("number").asText()).startsWith("AUD-");
        assertThat(audit.get("status").asText()).isEqualTo("COUNTING");
        assertThat(audit.get("itemsInCount").asInt()).isEqualTo(3);
        startAll(w.keeper(), w.pharmacyId(), 409);
        JsonNode sheet = sheet(w.keeper(), w.pharmacyId(), audit);
        assertThat(sheet.get("items")).hasSize(3);
        save(w.keeper(), w.pharmacyId(), audit, sheet.get("version").asLong(),
                List.of(cnt(lineOfBatch(sheet, "TESTB001"), 98), cnt(lineOfBatch(sheet, "TESTB003"), 3), cnt(lineOfBatch(sheet, "TESTB002"), 40)), 200);
        JsonNode submitted = submit(w.keeper(), w.pharmacyId(), audit, 200);
        assertThat(submitted.get("status").asText()).isEqualTo("WAITING_REVIEW");
        JsonNode review = variance(w.clinic().owner() == null ? null : currentOwner, w.pharmacyId(), audit, 200);
        assertThat(review.get("lines")).hasSize(1);
        JsonNode line = review.get("lines").get(0);
        assertThat(line.get("system").asInt()).isEqualTo(100);
        assertThat(line.get("counted").asInt()).isEqualTo(98);
        assertThat(line.get("diff").asInt()).isEqualTo(-2);
        assertThat(review.get("varianceValuePaise").asLong()).isNegative();
        JsonNode ownerView = ok(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit), w.clinic().owner(), null, 200);
        assertThat(ownerView.get("withVariance").asInt()).isEqualTo(1);
        // a difference with no reason is refused
        problem(call(HttpMethod.POST, audits(w.pharmacyId()) + "/" + idOf(audit) + "/approve-and-post", w.clinic().owner(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", ownerView.get("version").asLong()))), 400);
        reasons(currentOwner, w.pharmacyId(), audit, review);
        JsonNode posted = approveAndPost(currentOwner, w.pharmacyId(), audit, 200);
        assertThat(posted.get("status").asText()).isEqualTo("CLOSED");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(98);
        assertThat(ledger(w, "STOCK_AUDIT_ADJUSTMENT")).isEqualTo(1);
        assertLedgerEqualsBalances(w);
        // closed: no new count lines, a new audit may be started
        save(w.keeper(), w.pharmacyId(), audit, posted.get("version").asLong(), List.of(cnt(lineOfBatch(sheet, "TESTB001"), 1)), 409);
        startAll(w.keeper(), w.pharmacyId(), 201);
    }

    @Test
    void theCountSheetIsBlindForEveryCallerAndTheCounterNeverSeesAVarianceFigure() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode audit = countedAndSubmitted(u, 98);
        for (Pn who : List.of(w.keeper(), currentOwner)) {
            JsonNode sheet = sheet(who, w.pharmacyId(), audit);
            for (JsonNode line : sheet.get("items")) {
                Set<String> keys = new TreeSet<>();
                for (Iterator<String> it = line.fieldNames(); it.hasNext();) {
                    keys.add(it.next());
                }
                assertThat(keys).isEqualTo(new TreeSet<>(SHEET_KEYS));
            }
            assertThat(sheet.toString()).doesNotContain("\"system\"").doesNotContain("\"book").doesNotContain("\"diff\"").doesNotContain("valuePaise");
        }
        JsonNode counterView = get(w.keeper(), audits(w.pharmacyId()) + "/" + idOf(audit), 200);
        assertThat(counterView.get("withVariance").isNull()).isTrue();
        assertThat(counterView.get("varianceValuePaise").isNull()).isTrue();
        assertThat(counterView.toString()).doesNotContain("\"qtyBefore\":1").doesNotContain("\"qtyBefore\":98");
        JsonNode list = get(w.keeper(), audits(w.pharmacyId()), 200);
        assertThat(list.toString()).doesNotContain("-412");
        for (JsonNode row : list.get("items")) {
            assertThat(row.get("withVariance") == null || row.get("withVariance").isNull()).isTrue();
        }
        // the counter has no access to the review
        problem(call(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit) + "/variance", w.keeper().auth(), null), 403);
        // the counter submit response is still blind
        JsonNode second = startAll(w.csUser(), w.centralId(), 400);
        assertThat(second.toString()).contains("Nothing to count");
    }

    @Test
    void whileAnAuditIsOpenNoStockReadAnswersTheSystemQuantityOfTheAuditedProductToTheCounter() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode audit = startProducts(w.keeper(), w.pharmacyId(), List.of(w.productA()), 201);
        String a = w.productA();
        List<String> reads = List.of(
                url(w.pharmacyId(), "stock/overview"),
                url(w.pharmacyId(), "stock/batches?size=100"),
                url(w.pharmacyId(), "stock/products/" + a),
                url(w.pharmacyId(), "stock/low-stock"),
                url(w.pharmacyId(), "stock/fefo-pick?productId=" + a + "&quantity=5"),
                url(w.pharmacyId(), "stock/products/" + a + "/states"),
                PH + "/stock/products/" + a + "/stores",
                url(w.pharmacyId(), "reorder-suggestions"),
                url(w.pharmacyId(), "stock-adjustments"),
                PH + "/reports/store-stock",
                PH + "/reports/batch-expiry",
                PH + "/reports/below-reorder");
        int ownerSeen = 0;
        List<String> leaks = new ArrayList<>();
        for (String path : reads) {
            MvcResult counter = call(HttpMethod.GET, path, w.keeper().auth(), null).andReturn();
            MvcResult owner = call(HttpMethod.GET, path, w.clinic().owner(), null).andReturn();
            if (owner.getResponse().getStatus() == 200) {
                ownerSeen += numbersOfProduct(objectMapper.readTree(owner.getResponse().getContentAsString()), a, false).size();
            }
            if (counter.getResponse().getStatus() == 200) {
                List<String> found = numbersOfProduct(objectMapper.readTree(counter.getResponse().getContentAsString()), a, false);
                if (!found.isEmpty()) {
                    leaks.add(path + " -> " + found);
                }
            }
        }
        assertThat(ownerSeen).as("the reviewer sees quantities, so the check has teeth").isGreaterThan(0);
        assertThat(leaks).as("quantities of the audited product answered to a blind counter").isEmpty();
        // the ledger report: lines of the audited product carry no quantity for the counter
        String from = todayIst().minusDays(1).toString();
        String to = todayIst().plusDays(1).toString();
        JsonNode ledgerReport = get(w.keeper(), PH + "/reports/stock-ledger?productId=" + a + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to, 200);
        assertThat(ledgerReport.get("items")).isNotEmpty();
        for (JsonNode l : ledgerReport.get("items")) {
            assertThat(l.get("qtyIn").isNull()).isTrue();
            assertThat(l.get("qtyOut").isNull()).isTrue();
            assertThat(l.get("balance").isNull()).isTrue();
        }
        JsonNode ownerLedger = get(currentOwner, PH + "/reports/stock-ledger?productId=" + a + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to, 200);
        assertThat(ownerLedger.get("items").get(0).get("qtyIn").isNull() && ownerLedger.get("items").get(0).get("qtyOut").isNull()).isFalse();
        // the CSV writes empty cells and no digit of the quantity column of a blind line
        MvcResult csv = csvCall(w.keeper(), PH + "/reports/stock-ledger/export?productId=" + a + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("100");
        // an audit on another store does not blind this one: the Central Store has no audit
        assertThat(audit.get("status").asText()).isEqualTo("COUNTING");
    }

    @Test
    void theBlindMessagesCarryNoNumberAndTheTransferAndReturnAndDamageReadsHideTheQuantity() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        startProducts(w.keeper(), w.pharmacyId(), List.of(w.productA()), 201);
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode ret = post(w.keeper(), url(w.pharmacyId(), "store-returns"),
                m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 101, "reasonKey", REASON_RETURN), 400);
        assertThat(ret.toString()).contains("This is more than the quantity in stock.").doesNotContain("from 1 to 100");
        JsonNode retBad = post(w.keeper(), url(w.pharmacyId(), "store-returns"),
                m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 0, "reasonKey", REASON_RETURN), 400);
        assertThat(retBad.toString()).contains("Enter a whole number from 1 to 999999.");
        JsonNode dmg = post(w.keeper(), url(w.pharmacyId(), "damage-reports"), damageBody(batch, "AVAILABLE", 101, "WRITE_OFF"), 400);
        assertThat(dmg.toString()).contains("This is more than the quantity in stock.").doesNotContain("from 1 to 100");
        JsonNode t = transfer(u, w.keeper(), w.productA(), 5, 201);
        JsonNode lines = get(w.keeper(), url(w.pharmacyId(), "transfers/" + idOf(t)), 200);
        for (JsonNode l : lines.get("lines")) {
            assertThat(l.get("availableAtSender").isNull()).isTrue();
        }
        JsonNode ownerLines = get(currentOwner, url(w.pharmacyId(), "transfers/" + idOf(t)), 200);
        assertThat(ownerLines.get("lines").get(0).get("availableAtSender").isNull()).isFalse();
        // transfer dispatch of more than the stock: the refusal carries no quantity for the counter
        JsonNode big = transfer(u, w.keeper(), w.productA(), 100, 201);
        storeReturn(u, w.keeper(), "TESTB001", 10, 201);
        JsonNode p = problem(call(HttpMethod.POST, url(w.pharmacyId(), "transfers/" + idOf(big) + "/dispatch"), w.keeper().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", big.get("version").asLong()))), 409);
        assertThat(p.get("type").asText()).endsWith("insufficient-stock");
        assertThat(p.has("available") && !p.get("available").isNull()).as("available in the problem").isFalse();
        assertThat(p.toString()).doesNotContain("\"available\":90").doesNotContain("\"available\":100");
    }

    @Test
    void aConsumptionAboveTheStockOfAnAuditedWardAnswersNoQuantityToTheCounter() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        startProducts(w.keeper(), u.wardId(), List.of(w.productB()), 201);
        JsonNode p = post(w.keeper(), url(u.wardId(), "consumptions"), consumeBody(PURPOSE, List.of(pl(w.productB(), 41))), 409);
        assertThat(p.get("type").asText()).endsWith("insufficient-stock");
        assertThat(p.toString()).doesNotContain("\"available\":40");
        JsonNode ownerSees = post(currentOwner, url(u.wardId(), "consumptions"), consumeBody(PURPOSE, List.of(pl(w.productB(), 41))), 409);
        assertThat(ownerSees.toString()).contains("40");
    }

    @Test
    void theCountSheetRefusesBadCountsStaleVersionsAndUncountedSubmits() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode audit = startAll(w.keeper(), w.pharmacyId(), 201);
        JsonNode sheet = sheet(w.keeper(), w.pharmacyId(), audit);
        long v = sheet.get("version").asLong();
        JsonNode line = lineOfBatch(sheet, "TESTB001");
        save(w.keeper(), w.pharmacyId(), audit, v, List.of(cnt(line, -1)), 400);
        save(w.keeper(), w.pharmacyId(), audit, v, List.of(cnt(line, 1_000_000)), 400);
        save(w.keeper(), w.pharmacyId(), audit, v, List.of(m("auditLineId", line.get("auditLineId").asText(), "counted", null, "notCounted", true,
                "notCountedReasonKey", null)), 400);
        save(w.keeper(), w.pharmacyId(), audit, v, List.of(m("auditLineId", UUID.randomUUID().toString(), "counted", 1, "notCounted", false)), 400);
        JsonNode saved = save(w.keeper(), w.pharmacyId(), audit, v, List.of(cnt(line, 50)), 200);
        assertThat(saved.get("counted").asInt()).isEqualTo(1);
        save(w.keeper(), w.pharmacyId(), audit, v, List.of(cnt(line, 51)), 409);
        // two lines are still open
        problem(call(HttpMethod.POST, audits(w.pharmacyId()) + "/" + idOf(audit) + "/submit", w.keeper().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", saved.get("version").asLong()))), 400);
        // a line marked not counted with a reason satisfies the submit
        JsonNode second = save(w.keeper(), w.pharmacyId(), audit, saved.get("version").asLong(),
                List.of(m("auditLineId", lineOfBatch(sheet, "TESTB003").get("auditLineId").asText(), "counted", null, "notCounted", true,
                        "notCountedReasonKey", COUNT_MISMATCH), cnt(lineOfBatch(sheet, "TESTB002"), 40)), 200);
        post(w.keeper(), audits(w.pharmacyId()) + "/" + idOf(audit) + "/submit",
                m("clientKey", UUID.randomUUID().toString(), "version", second.get("version").asLong()), 200);
    }

    @Test
    void aRecountReturnsTheLineBlindAndAStockMoveSinceTheCountStopsThePost() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode submitted = countedAndSubmitted(u, 98);
        JsonNode review = variance(currentOwner, w.pharmacyId(), submitted, 200);
        assertThat(review.get("lines").get(0).get("stockMoved").asBoolean()).isFalse();
        // stock moves after the book quantity was fixed
        storeReturn(u, w.keeper(), "TESTB001", 1, 201);
        JsonNode moved = variance(currentOwner, w.pharmacyId(), submitted, 200);
        assertThat(moved.get("lines").get(0).get("stockMoved").asBoolean()).isTrue();
        reasons(currentOwner, w.pharmacyId(), submitted, moved);
        JsonNode p = approveAndPost(currentOwner, w.pharmacyId(), submitted, 409);
        assertThat(p.get("type").asText()).endsWith("stock-moved");
        assertThat(p.toString()).contains("Stock moved since the count. Recount.");
        assertThat(ledger(w, "STOCK_AUDIT_ADJUSTMENT")).isZero();
        JsonNode line = moved.get("lines").get(0);
        JsonNode recount = post(currentOwner, audits(w.pharmacyId()) + "/" + idOf(submitted) + "/recount",
                m("version", versionNow(w.pharmacyId(), submitted), "auditLineIds", List.of(line.get("auditLineId").asText())), 200);
        assertThat(recount.get("status").asText()).isEqualTo("RECOUNT");
        JsonNode sheet = sheet(w.keeper(), w.pharmacyId(), submitted);
        assertThat(sheet.get("items")).hasSize(1);
        assertThat(sheet.get("items").get(0).get("counted").isNull()).isTrue();
        assertThat(sheet.get("items").get(0).get("recount").asBoolean()).isTrue();
        // a line that is not open for recount is refused while RECOUNT
        save(w.keeper(), w.pharmacyId(), submitted, sheet.get("version").asLong(), List.of(cnt(line, 97)), 200);
        JsonNode again = submit(w.keeper(), w.pharmacyId(), submitted, 200);
        assertThat(again.get("status").asText()).isEqualTo("WAITING_REVIEW");
        JsonNode review2 = variance(currentOwner, w.pharmacyId(), submitted, 200);
        assertThat(review2.get("lines").get(0).get("stockMoved").asBoolean()).isFalse();
        reasons(currentOwner, w.pharmacyId(), submitted, review2);
        approveAndPost(currentOwner, w.pharmacyId(), submitted, 200);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(97);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void theSubmitterCannotApproveAndAnAuditWaitsForTheApproverWhenARuleApplies() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        ownerAlways(w, "STOCK_ADJUSTMENT");
        JsonNode audit = startAll(w.second(), w.pharmacyId(), 201);
        JsonNode sheet = sheet(w.second(), w.pharmacyId(), audit);
        save(w.second(), w.pharmacyId(), audit, sheet.get("version").asLong(),
                List.of(cnt(lineOfBatch(sheet, "TESTB001"), 99), cnt(lineOfBatch(sheet, "TESTB003"), 3), cnt(lineOfBatch(sheet, "TESTB002"), 40)), 200);
        post(w.second(), audits(w.pharmacyId()) + "/" + idOf(audit) + "/submit",
                m("clientKey", UUID.randomUUID().toString(), "version", versionNow(w.pharmacyId(), audit)), 200);
        JsonNode review = ok(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit) + "/variance", w.second().auth(), null, 200);
        ok(HttpMethod.PUT, audits(w.pharmacyId()) + "/" + idOf(audit) + "/variance", w.second().auth(), js(m("version", review.get("version").asLong(), "lines",
                List.of(m("auditLineId", review.get("lines").get(0).get("auditLineId").asText(), "reasonKey", COUNT_MISMATCH, "note", null)))), 200);
        JsonNode refused = problem(call(HttpMethod.POST, audits(w.pharmacyId()) + "/" + idOf(audit) + "/approve-and-post", w.second().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", versionNow(w.pharmacyId(), audit)))), 403);
        assertThat(refused.get("type").asText()).endsWith("self-approval");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
        // the owner (another person) approves and the adjustment posts
        approveAndPost(currentOwner, w.pharmacyId(), audit, 200);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(99);
    }

    @Test
    void aShortStockAtPostingRefusesTheLineAndSendsItToRecountAndTheReplayAnswersTheSameWithNothingPosted() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode audit = startAll(w.keeper(), u.wardId(), 201);
        JsonNode sheet = sheet(w.keeper(), u.wardId(), audit);
        JsonNode a1 = lineOfBatch(sheet, "TESTB001");
        save(w.keeper(), u.wardId(), audit, sheet.get("version").asLong(),
                List.of(cnt(a1, 0), cnt(lineOfBatch(sheet, "TESTB003"), 3), cnt(lineOfBatch(sheet, "TESTB002"), 40)), 200);
        JsonNode submitted = submit(w.keeper(), u.wardId(), audit, 200);
        JsonNode review = variance(currentOwner, u.wardId(), submitted, 200);
        assertThat(review.get("lines").get(0).get("suggestConsumption").asBoolean()).isFalse();
        reasons(currentOwner, u.wardId(), submitted, review);
        // the ward used the 100 units meanwhile, linked to this audit line (not "stock moved"): the post then has nothing to take from
        Map<String, Object> use = consumeBody(PURPOSE, List.of(pl(w.productA(), 100)));
        use.put("stockAuditLineId", a1.get("auditLineId").asText());
        int status = call(HttpMethod.POST, url(u.wardId(), "consumptions"), w.keeper().auth(), js(use)).andReturn().getResponse().getStatus();
        assertThat(status).as("a consumption linked to an audit line of this store").isEqualTo(201);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = m("clientKey", key, "version", versionNow(u.wardId(), audit));
        JsonNode first = problem(call(HttpMethod.POST, audits(u.wardId()) + "/" + idOf(audit) + "/approve-and-post", w.clinic().owner(), js(body)), 409);
        assertThat(first.get("type").asText()).endsWith("insufficient-stock");
        assertThat(ledger(w, "STOCK_AUDIT_ADJUSTMENT")).isZero();
        // the refusal is committed: the audit went to recount
        JsonNode after = ok(HttpMethod.GET, audits(u.wardId()) + "/" + idOf(audit), w.clinic().owner(), null, 200);
        assertThat(after.get("status").asText()).isEqualTo("RECOUNT");
        // replay with the same client key: the same refusal, nothing posted
        int replay = call(HttpMethod.POST, audits(u.wardId()) + "/" + idOf(audit) + "/approve-and-post", w.clinic().owner(), js(body)).andReturn().getResponse().getStatus();
        assertThat(replay).isIn(409, 200);
        assertThat(ledger(w, "STOCK_AUDIT_ADJUSTMENT")).isZero();
        assertThat(bal(w, u.wardId(), "TESTB001", "AVAILABLE")).isZero();
    }

    @Test
    void anAuditRefusesBadScopeAnotherClinicAndAnUnmappedRole() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        post(w.keeper(), audits(w.pharmacyId()), m("clientKey", UUID.randomUUID().toString(), "scope", "PRODUCTS"), 400);
        post(w.keeper(), audits(w.pharmacyId()), m("clientKey", UUID.randomUUID().toString(), "scope", "EVERYTHING"), 400);
        post(w.keeper(), audits(w.pharmacyId()), m("clientKey", UUID.randomUUID().toString(), "scope", "ALL_AVAILABLE", "storeId", "x"), 400);
        startProducts(w.keeper(), w.pharmacyId(), List.of(UUID.randomUUID().toString()), 400);
        JsonNode audit = startAll(w.keeper(), w.pharmacyId(), 201);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit), other.owner(), null), 404);
        problem(call(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit) + "/count-sheet", other.owner(), null), 404);
        problem(call(HttpMethod.GET, audits(w.pharmacyId()), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        assertThat(call(HttpMethod.GET, audits(w.pharmacyId()), frontDesk.auth(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
        Pn stranger = crew(w.clinic(), "PHARMACIST", "Unmapped Pharmacist");
        assertThat(call(HttpMethod.GET, audits(w.pharmacyId()) + "/" + idOf(audit) + "/count-sheet", stranger.auth(), null).andReturn().getResponse().getStatus())
                .isIn(403, 404);
    }

    @Test
    void theAuditLineGuardRefusesCountsAfterSubmitAndABookQuantityRaiseAndAnyChangeAfterClose() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode submitted = countedAndSubmitted(u, 98);
        String audit = idOf(submitted);
        db().inTenant(w.clinic().tenantId(), c -> {
            mustRefuse(c, "23514", "stock audit counts can change only while the audit is counting",
                    "update pharmacy.stock_audit_line set counted_qty = 5 where stock_audit_id = ?::uuid and counted_qty = 98", audit);
            mustRefuse(c, "23514", "stock audit book quantity is fixed at submit",
                    "update pharmacy.stock_audit_line set book_qty = book_qty + 1 where stock_audit_id = ?::uuid and counted_qty = 98", audit);
            mustRefuse(c, "23514", "stock audit line identity cannot change",
                    "update pharmacy.stock_audit_line set product_id = gen_random_uuid() where stock_audit_id = ?::uuid", audit);
            mustAccept(c, "update pharmacy.stock_audit_line set book_qty = book_qty - 1 where stock_audit_id = ?::uuid and counted_qty = 98", audit);
            return null;
        });
        JsonNode review = variance(currentOwner, w.pharmacyId(), submitted, 200);
        reasons(currentOwner, w.pharmacyId(), submitted, review);
        approveAndPost(currentOwner, w.pharmacyId(), submitted, 200);
        db().inTenant(w.clinic().tenantId(), c -> {
            mustRefuse(c, "23514", "stock audit is final", "update pharmacy.stock_audit_line set variance_note = 'x' where stock_audit_id = ?::uuid", audit);
            return null;
        });
    }

    // ---------------- 79-18 and 79-19 reports ----------------

    private MvcResult csvCall(Pn who, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(java.net.URI.create(path)).with(who.auth()).accept("text/csv")).andReturn();
    }

    private MvcResult csvCall(org.springframework.test.web.servlet.request.RequestPostProcessor auth, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(java.net.URI.create(path)).with(auth).accept("text/csv")).andReturn();
    }

    private List<String> groupKeys(JsonNode catalogue) {
        List<String> g = new ArrayList<>();
        catalogue.get("groups").forEach(x -> g.add(x.get("group").asText()));
        return g;
    }

    private List<String> reportKeys(JsonNode catalogue) {
        List<String> k = new ArrayList<>();
        catalogue.get("groups").forEach(x -> x.get("reports").forEach(r -> k.add(r.get("key").asText())));
        return k;
    }

    /** The numeric, non-null quantity-like fields of the objects that belong to the product (a product id or a product child). */
    private List<String> numbersOfProduct(JsonNode node, String productId, boolean inProduct) {
        List<String> found = new ArrayList<>();
        walk(node, productId, inProduct, "$", found);
        return found;
    }

    private static final java.util.regex.Pattern QTY_NAME = java.util.regex.Pattern.compile("(?i)(avail|qty|quantity|instock|intransit|pending|quarantine|balance|^value$|valuepaise)");

    private void walk(JsonNode n, String productId, boolean inProduct, String path, List<String> found) {
        if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                walk(n.get(i), productId, inProduct, path + "[" + i + "]", found);
            }
            return;
        }
        if (!n.isObject()) {
            return;
        }
        boolean here = inProduct || productId.equals(textOf(n, "productId"))
                || (n.get("product") != null && n.get("product").isObject() && productId.equals(textOf(n.get("product"), "productId")));
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> f = it.next();
            if (here && f.getValue().isNumber() && QTY_NAME.matcher(f.getKey()).find() && !f.getKey().toLowerCase().contains("requested")) {
                found.add(path + "." + f.getKey() + "=" + f.getValue());
            }
            walk(f.getValue(), productId, here, path + "." + f.getKey(), found);
        }
    }

    private static String textOf(JsonNode n, String field) {
        return n.get(field) != null && n.get(field).isTextual() ? n.get(field).asText() : null;
    }

    @Test
    void theCatalogueShowsOnlyTheReportsTheCallerMayRunWithGroupLabels() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        JsonNode owner = get(currentOwner, PH + "/reports", 200);
        assertThat(groupKeys(owner)).containsExactly("STOCK", "MOVEMENT", "PURCHASE");
        assertThat(owner.get("groups").get(0).get("label").asText()).isEqualTo("Stock");
        assertThat(reportKeys(owner)).contains("store-stock", "batch-expiry", "below-reorder", "indent-register", "transfer-register", "ward-consumption",
                "returns-write-offs", "po-vs-grn", "purchase-register", "debit-notes-pending");
        JsonNode central = get(w.csUser(), PH + "/reports", 200);
        assertThat(groupKeys(central)).contains("PURCHASE");
        JsonNode keeper = get(w.keeper(), PH + "/reports", 200);
        assertThat(groupKeys(keeper)).doesNotContain("PURCHASE");
        assertThat(reportKeys(keeper)).contains("store-stock", "below-reorder");
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        problem(call(HttpMethod.GET, PH + "/reports", frontDesk.auth(), null), 403);
        // a purchase report is refused to the counter, a store user may run the stock ones
        problem(call(HttpMethod.GET, PH + "/reports/purchase-register?from=" + todayIst().minusDays(5) + "&to=" + todayIst(), w.keeper().auth(), null), 403);
    }

    @Test
    void everyReportRunsAsSqlForTheOwnerWithTypedColumnsAndPagedRows() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        String from = todayIst().minusDays(2).toString();
        String to = todayIst().plusDays(1).toString();
        for (String key : List.of("store-stock", "batch-expiry", "below-reorder", "indent-register", "transfer-register", "ward-consumption",
                "returns-write-offs", "po-vs-grn", "purchase-register", "debit-notes-pending")) {
            JsonNode page = get(currentOwner, PH + "/reports/" + key + "?from=" + from + "&to=" + to + "&size=50", 200);
            assertThat(page.get("reportKey").asText()).isEqualTo(key);
            assertThat(page.get("columns")).isNotEmpty();
            for (JsonNode col : page.get("columns")) {
                assertThat(col.get("kind").asText()).isIn("TEXT", "INTEGER", "PAISE", "RATE", "PERCENT", "DATE", "DATE_TIME");
            }
            for (JsonNode row : page.get("rows")) {
                assertThat(row.size()).as(key + " cells equal columns").isEqualTo(page.get("columns").size());
            }
            assertThat(page.toString()).as(key).doesNotContain("Pia Pharmacist").doesNotContain("patientName");
        }
        JsonNode stock = get(currentOwner, PH + "/reports/store-stock", 200);
        assertThat(stock.get("rows")).isNotEmpty();
        assertThat(stock.get("total").asInt()).isPositive();
        JsonNode paged = get(currentOwner, PH + "/reports/store-stock?size=1&page=1", 200);
        assertThat(paged.get("rows").size()).isLessThanOrEqualTo(1);
        // filters and bad input
        get(currentOwner, PH + "/reports/store-stock?storeId=" + w.pharmacyId(), 200);
        get(currentOwner, PH + "/reports/store-stock?size=201", 400);
        get(currentOwner, PH + "/reports/no-such-report", 400);
        get(currentOwner, PH + "/reports/store-stock?storeId=" + UUID.randomUUID(), 404);
        get(currentOwner, PH + "/reports/indent-register", 400);
        get(currentOwner, PH + "/reports/indent-register?from=" + to + "&to=" + from, 400);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, PH + "/reports/store-stock?storeId=" + w.pharmacyId(), other.owner(), null), 404);
        JsonNode otherStock = ok(HttpMethod.GET, PH + "/reports/store-stock", other.owner(), null, 200);
        assertThat(otherStock.get("rows")).isEmpty();
    }

    @Test
    void aReportRangeOfMoreThanTwelveMonthsAnswers400RangeTooLongOnScreenAndInTheCsv() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        String from = todayIst().minusDays(400).toString();
        String to = todayIst().toString();
        JsonNode p = get(currentOwner, PH + "/reports/indent-register?from=" + from + "&to=" + to, 400);
        assertThat(p.get("type").asText()).endsWith("range-too-long");
        assertThat(p.toString()).contains("Choose at most 12 months.");
        JsonNode l = get(currentOwner, PH + "/reports/stock-ledger?productId=" + w.productA() + "&from=" + from + "&to=" + to, 400);
        assertThat(l.get("type").asText()).endsWith("range-too-long");
        // 366 days is allowed
        get(currentOwner, PH + "/reports/stock-ledger?productId=" + w.productA() + "&from=" + todayIst().minusDays(366) + "&to=" + todayIst(), 200);
        MvcResult csv = csvCall(currentOwner, PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&from=" + from + "&to=" + to);
        assertThat(csv.getResponse().getStatus()).isEqualTo(400);
        assertThat(csv.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(objectMapper.readTree(csv.getResponse().getContentAsString()).get("type").asText()).endsWith("range-too-long");
        MvcResult other = csvCall(currentOwner, PH + "/reports/indent-register/export?from=" + from + "&to=" + to);
        assertThat(other.getResponse().getStatus()).isEqualTo(400);
        assertThat(other.getResponse().getContentType()).startsWith("application/problem+json");
    }

    @Test
    void theStockLedgerReportAndItsCsvCarryTheMovementsWithABomAndAFilenameAndAreAudited() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        String from = todayIst().minusDays(1).toString();
        String to = todayIst().plusDays(1).toString();
        JsonNode page = get(currentOwner, PH + "/reports/stock-ledger?productId=" + w.productA() + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to, 200);
        assertThat(page.get("items")).isNotEmpty();
        assertThat(page.get("items").get(0).get("transaction").asText()).isNotBlank();
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REPORT_VIEWED'")).isGreaterThanOrEqualTo(1);
        MvcResult csv = csvCall(currentOwner, PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("sahaj-stock-ledger-" + from + "-" + to + ".csv");
        byte[] bytes = csv.getResponse().getContentAsByteArray();
        assertThat(bytes[0] & 0xff).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xff).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xff).isEqualTo(0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8).split("\n").length).isGreaterThanOrEqualTo(2);
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REPORT_EXPORTED'")).isEqualTo(1);
        MvcResult stock = csvCall(currentOwner, PH + "/reports/store-stock/export");
        assertThat(stock.getResponse().getStatus()).isEqualTo(200);
        assertThat(stock.getResponse().getHeader("Content-Disposition")).contains("sahaj-store-stock-").contains(".csv");
    }

    @Test
    void everyCsvErrorIsProblemJsonWhateverTheAcceptHeaderSays() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        String from = todayIst().minusDays(1).toString();
        String to = todayIst().toString();
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        Clinic other = pharmacyClinic();
        List<MvcResult> errors = List.of(
                csvCall(currentOwner, PH + "/reports/stock-ledger/export?from=" + from + "&to=" + to),
                csvCall(currentOwner, PH + "/reports/stock-ledger/export?productId=" + UUID.randomUUID() + "&from=" + from + "&to=" + to),
                csvCall(currentOwner, PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&storeId=" + UUID.randomUUID() + "&from=" + from + "&to=" + to),
                csvCall(frontDesk.auth(), PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&from=" + from + "&to=" + to),
                csvCall(w.keeper(), PH + "/reports/purchase-register/export?from=" + from + "&to=" + to),
                csvCall(currentOwner, PH + "/reports/nothing/export"),
                csvCall(other.owner(), PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&from=" + from + "&to=" + to));
        for (MvcResult r : errors) {
            assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(objectMapper.readTree(r.getResponse().getContentAsString()).get("status").asInt()).isEqualTo(r.getResponse().getStatus());
        }
        // another clinic's product is not found, never an empty file
        assertThat(errors.get(6).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aCsvOfMoreThan50000RowsIsRefusedWith409ExportTooLargeAndNothingIsSent() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        String member = w.keeper().membershipId().toString();
        commitAsTenant(w.clinic().tenantId(), "insert into pharmacy.stock_ledger (unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, "
                + "balance_after, actor_kind, actor_membership_id) select (select id from pharmacy.unit limit 1), gen_random_uuid(), 'STOCK_ADJUSTMENT', ?::uuid, "
                + "'AVAILABLE', ?::uuid, ?::uuid, 1, 0, 1, 'USER', ?::uuid from generate_series(1, 50001)", w.pharmacyId(), w.productA(), batch, member);
        String from = todayIst().minusDays(1).toString();
        String to = todayIst().plusDays(1).toString();
        MvcResult csv = csvCall(currentOwner, PH + "/reports/stock-ledger/export?productId=" + w.productA() + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to);
        assertThat(csv.getResponse().getStatus()).isEqualTo(409);
        assertThat(csv.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode p = objectMapper.readTree(csv.getResponse().getContentAsString());
        assertThat(p.get("type").asText()).endsWith("export-too-large");
        assertThat(p.toString()).contains("Narrow the date range.");
        assertThat(csv.getResponse().getContentAsByteArray().length).isLessThan(2000);
        // the screen still pages
        JsonNode page = get(currentOwner, PH + "/reports/stock-ledger?productId=" + w.productA() + "&storeId=" + w.pharmacyId() + "&from=" + from + "&to=" + to + "&size=200", 200);
        assertThat(page.get("items").size()).isLessThanOrEqualTo(200);
        assertThat(page.get("total").asInt()).isGreaterThan(50000);
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_REPORT_EXPORTED'")).isZero();
    }

    // ---------------- inbox ----------------

    @Test
    void theApprovalsInboxFiltersByTypeKeepsTheCountsForTheCallerAndRefusesBadParameters() throws Exception {
        Uw u = auditWorld();
        Sw w = u.w();
        ownerAlways(w, "WRITE_OFF", "STOCK_TRANSFER");
        damage(u, w.keeper(), "TESTB001", 2, "WRITE_OFF", 201);
        transfer(u, w.keeper(), w.productA(), 3, 201);
        JsonNode all = get(currentOwner, PH + "/approvals/inbox", 200);
        assertThat(all.get("counts").get("stock").asInt()).isEqualTo(2);
        assertThat(all.get("counts").get("all").asInt()).isEqualTo(2);
        assertThat(all.get("moreWaiting").asBoolean()).isFalse();
        JsonNode stock = get(currentOwner, PH + "/approvals/inbox?type=STOCK", 200);
        assertThat(stock.get("items")).hasSize(2);
        JsonNode indents = get(currentOwner, PH + "/approvals/inbox?type=INDENTS", 200);
        assertThat(indents.get("items")).isEmpty();
        assertThat(indents.get("counts").get("stock").asInt()).as("counts ignore the type").isEqualTo(2);
        assertThat(get(currentOwner, PH + "/approvals/inbox?type=PURCHASE", 200).get("items")).isEmpty();
        get(currentOwner, PH + "/approvals/inbox?type=SALES", 400);
        get(currentOwner, PH + "/approvals/inbox?size=51", 400);
        get(currentOwner, PH + "/approvals/inbox?page=-1", 400);
        // the raiser never sees their own request; a plain pharmacist is refused; another clinic sees nothing
        problem(call(HttpMethod.GET, PH + "/approvals/inbox", w.keeper().auth(), null), 403);
        Clinic other = pharmacyClinic();
        JsonNode otherInbox = ok(HttpMethod.GET, PH + "/approvals/inbox", other.owner(), null, 200);
        assertThat(otherInbox.get("items")).isEmpty();
    }
}
