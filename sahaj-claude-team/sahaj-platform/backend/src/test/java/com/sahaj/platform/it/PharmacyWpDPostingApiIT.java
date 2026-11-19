package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-1, posting an approved opening-stock import (docs/api/pharmacy.yaml 0.3.3, operations pharmacyPreviewOpeningStockPosting
 * and pharmacyPostOpeningStock): the preview, the post, the replay of the same clientKey, already-posted, not-approved,
 * batch-mrp-mismatch, an expired line to Quarantine, batch numbers that differ only in case, the ledger reconciling with the
 * balances, roles, the module switch and another clinic. Test plan: docs/test-plans/SAH-79.md (T79-1-*).
 */
@OnTestDatabase
class PharmacyWpDPostingApiIT extends Sah79ApiSupport {

    private static final String NOT_APPROVED_TEXT = "This import is not approved.";
    private static final String ALREADY_POSTED_TEXT = "This import was already posted.";

    @Test
    void thePreviewOfAnApprovedImportShowsTheTotalsTheNamesAndThatItCanBePosted() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        JsonNode p = ok(HttpMethod.GET, previewUrl(w.pharmacyId(), id), w.keeper().auth(), null, 200);
        assertThat(p.get("importId").asText()).isEqualTo(id);
        assertThat(p.get("store").get("storeId").asText()).isEqualTo(w.pharmacyId());
        assertThat(p.get("lineCount").asInt()).isEqualTo(3);
        assertThat(p.get("totalQuantity").asInt()).isEqualTo(CLEAN_FILE_QUANTITY);
        assertThat(p.get("totalValuePaise").asLong()).isEqualTo(CLEAN_FILE_PAISE);
        assertThat(p.get("uploadedBy").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(p.get("approvedBy").get("name").asText()).isEqualTo("Second Owner");
        assertThat(p.get("canPost").asBoolean()).isTrue();
        assertThat(p.get("postLockReason").isNull()).isTrue();
        assertThat(p.get("expiredLines")).isEmpty();
        assertThat(p.get("mrpMismatchLines")).isEmpty();
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).as("a preview writes nothing").isZero();
    }

    @Test
    void thePreviewOfAnImportThatIsNotApprovedCannotPostAndNamesTheReason() throws Exception {
        Sw w = stockWorld();
        String id = idOf(draftOfCleanFile(w.keeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        JsonNode p = ok(HttpMethod.GET, previewUrl(w.pharmacyId(), id), w.keeper().auth(), null, 200);
        assertThat(p.get("canPost").asBoolean()).isFalse();
        assertThat(p.get("postLockReason").asText()).isEqualTo("NOT_APPROVED");
    }

    @Test
    void postingWritesTheLedgerTheBalancesAndTheImportAndTheLedgerSumsEqualTheBalances() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        JsonNode posted = postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        assertThat(posted.get("status").asText()).isEqualTo("APPROVED");
        assertThat(posted.get("posted").asBoolean()).isTrue();
        assertThat(posted.get("postedAt").isNull()).isFalse();
        assertThat(posted.get("postedBy").isNull()).isFalse();
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isEqualTo(3);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'OPENING_STOCK' and state = 'AVAILABLE' "
                + "and actor_kind = 'USER' and store_id = ?::uuid", w.pharmacyId())).isEqualTo(3);
        assertThat(count(w.clinic(), "select coalesce(sum(qty_in - qty_out), 0) from pharmacy.stock_ledger")).isEqualTo(CLEAN_FILE_QUANTITY);
        assertThat(count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance where store_id = ?::uuid and state = 'AVAILABLE'",
                w.pharmacyId())).isEqualTo(CLEAN_FILE_QUANTITY);
        assertThat(count(w.clinic(), "select count(*) from (select b.store_id, b.batch_id, b.state, b.qty, "
                + "(select coalesce(sum(l.qty_in - l.qty_out), 0) from pharmacy.stock_ledger l where l.store_id = b.store_id and l.batch_id = b.batch_id "
                + "and l.state = b.state) as ledger from pharmacy.stock_balance b) x where x.qty <> x.ledger")).as("ledger sums equal the balances").isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.batch where source = 'OPENING_STOCK'")).isEqualTo(3);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_POSTED")).hasSize(1);
        // the stock reads now show it
        JsonNode overview = ok(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/stock/overview", w.keeper().auth(), null, 200);
        assertThat(overview.get("batchCount").asInt()).isEqualTo(3);
        assertThat(overview.get("skuCount").asInt()).isEqualTo(2);
        assertThat(overview.get("empty").asBoolean()).isFalse();
    }

    @Test
    void theSameClientKeyAgainPostsNothingTwiceAndAnswersTheSameImport() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        UUID key = UUID.randomUUID();
        JsonNode first = postImport(w.keeper().auth(), w.pharmacyId(), id, key);
        JsonNode replay = postImport(w.keeper().auth(), w.pharmacyId(), id, key);
        assertThat(replay.get("postedAt").asText()).isEqualTo(first.get("postedAt").asText());
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isEqualTo(3);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_POSTED")).hasSize(1);
    }

    @Test
    void aSecondPostWithAnotherKeyIsAlreadyPostedWithTheTimeAndPostsNothing() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        JsonNode p = problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.keeper().auth(), clientKeyBody(UUID.randomUUID())), 409);
        assertType(p, "already-posted");
        assertThat(p.get("detail").asText()).isEqualTo(ALREADY_POSTED_TEXT);
        assertThat(p.get("postedAt")).as("postedAt is in the body").isNotNull();
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isEqualTo(3);
        assertThat(count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance")).isEqualTo(CLEAN_FILE_QUANTITY);
    }

    @Test
    void anImportThatIsNotApprovedAnswers409NotApprovedAndChangesNothing() throws Exception {
        Sw w = stockWorld();
        String draft = idOf(draftOfCleanFile(w.keeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        JsonNode p = problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), draft), w.keeper().auth(), clientKeyBody(UUID.randomUUID())), 409);
        assertType(p, "not-approved");
        assertThat(p.get("detail").asText()).isEqualTo(NOT_APPROVED_TEXT);
        submit(w.keeper().auth(), w.pharmacyId(), draft);
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), draft), w.keeper().auth(), clientKeyBody(UUID.randomUUID())), 409),
                "not-approved");
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isZero();
        assertThat(pharmacyRows(w.clinic(), "stock_balance")).isZero();
        assertThat(pharmacyRows(w.clinic(), "batch")).isZero();
    }

    @Test
    void aBatchWithTheSameKeyButAnotherMrpAnswers409BatchMrpMismatchWithTheRowsAndPostsNothing() throws Exception {
        Sw w = stockWorld();
        postedImport(w); // pharmacy store: TESTB001 of product A at MRP 45.50
        // the Central Store file has TESTB001 of product A at MRP 50.00 with the same expiry
        String id = idOf(startImport(w.clinic().owner(), w.centralId(), 1));
        addRows(w.clinic().owner(), w.centralId(), id, rows(row(1, "CS", w.codeA(), "TESTB001", farExpiry(), "50.00", "30.25", "10")));
        submit(w.clinic().owner(), w.centralId(), id);
        ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null, 200);
        JsonNode preview = ok(HttpMethod.GET, previewUrl(w.centralId(), id), w.clinic().owner(), null, 200);
        assertThat(preview.get("canPost").asBoolean()).isFalse();
        assertThat(preview.get("postLockReason").asText()).isEqualTo("MRP_MISMATCH");
        assertThat(preview.get("mrpMismatchLines")).hasSize(1);
        assertThat(preview.get("mrpMismatchLines").get(0).get("rowNo").asInt()).isEqualTo(1);
        long ledger = pharmacyRows(w.clinic(), "stock_ledger");
        JsonNode p = problem(call(HttpMethod.POST, postUrl(w.centralId(), id), w.clinic().owner(), clientKeyBody(UUID.randomUUID())), 409);
        assertType(p, "batch-mrp-mismatch");
        assertThat(p.get("lines")).isNotNull();
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).as("nothing posted").isEqualTo(ledger);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_balance where store_id = ?::uuid", w.centralId())).isZero();
    }

    @Test
    void batchNumbersThatDifferOnlyInCaseAreOneBatchAndTopUpTheSameBatchRow() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        long batches = pharmacyRows(w.clinic(), "batch");
        String id = idOf(startImport(w.clinic().owner(), w.centralId(), 1));
        addRows(w.clinic().owner(), w.centralId(), id, rows(row(1, "CS", w.codeA(), "testb001", farExpiry(), "45.50", "30.25", "7")));
        submit(w.clinic().owner(), w.centralId(), id);
        ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null, 200);
        postImport(w.clinic().owner(), w.centralId(), id, UUID.randomUUID());
        assertThat(pharmacyRows(w.clinic(), "batch")).as("the same batch, not a new one").isEqualTo(batches);
        assertThat(count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance where store_id = ?::uuid", w.centralId())).isEqualTo(7);
    }

    @Test
    void aLineWhoseExpiryHasPassedSinceApprovalIsPostedToQuarantineAndNotToAvailable() throws Exception {
        Sw w = stockWorld();
        String id = idOf(draftOfCleanFile(w.keeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.opening_stock_line set expiry_date = current_date - 5 where import_id = ?::uuid "
                + "and batch_no = 'TESTB003'", id);
        submit(w.keeper().auth(), w.pharmacyId(), id);
        ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        JsonNode preview = ok(HttpMethod.GET, previewUrl(w.pharmacyId(), id), w.keeper().auth(), null, 200);
        assertThat(preview.get("expiredLines")).hasSize(1);
        assertThat(preview.get("expiredLines").get(0).get("batchNo").asText()).isEqualTo("TESTB003");
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        assertThat(count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance where state = 'QUARANTINE'")).isEqualTo(3);
        assertThat(count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance where state = 'AVAILABLE'")).isEqualTo(140);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where state = 'QUARANTINE' and txn_type = 'OPENING_STOCK'")).isEqualTo(1);
        JsonNode expired = ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "view=EXPIRED"), w.keeper().auth(), null, 200);
        assertThat(expired.get("items")).hasSize(1);
    }

    @Test
    void aScheduleXLineIsPostedWithoutASecondSignerBecauseOpeningStockIsADeclaredCount() throws Exception {
        Sw w = stockWorld();
        Map<String, Object> body = ownBody("Test controlled item (fake)");
        body.put("schedule", "X");
        JsonNode x = createOwn(w.clinic(), body);
        String id = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 1));
        addRows(w.keeper().auth(), w.pharmacyId(), id, rows(row(1, "PHARM", x.get("code").asText(), "XB001", farExpiry(), "10", "5", "9")));
        submit(w.keeper().auth(), w.pharmacyId(), id);
        ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'OPENING_STOCK'")).isEqualTo(1);
    }

    // ---------------- roles, tenant, module ----------------

    @Test
    void theOwnerMayPostAndAPharmacistNotMappedToTheStoreAnswers404AndAnotherRoleAnswers403() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        // the Central Store pharmacist is not mapped at the pharmacy: unmapped store, 404
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.csUser().auth(), clientKeyBody(UUID.randomUUID())), 404), "not-found");
        for (String role : List.of("FRONT_DESK", "NURSE", "DOCTOR", "BILLING")) {
            Pn other = crew(w.clinic(), role);
            JsonNode p = problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), other.auth(), clientKeyBody(UUID.randomUUID())), 403);
            assertThat(p.get("status").asInt()).as(role).isEqualTo(403);
            problem(call(HttpMethod.GET, previewUrl(w.pharmacyId(), id), other.auth(), null), 403);
        }
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isZero();
        assertThat(postImport(w.clinic().owner(), w.pharmacyId(), id, UUID.randomUUID()).get("posted").asBoolean()).isTrue();
    }

    @Test
    void anotherClinicCannotSeeOrPostTheImport() throws Exception {
        Sw w = stockWorld();
        Sw other = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), other.clinic().owner(), clientKeyBody(UUID.randomUUID())), 404), "not-found");
        problem(call(HttpMethod.GET, previewUrl(w.pharmacyId(), id), other.clinic().owner(), null), 404);
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isZero();
    }

    @Test
    void theModuleSwitchedOffAnswers403ModuleOffOnBothOperations() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.clinic().owner(), clientKeyBody(UUID.randomUUID())), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, previewUrl(w.pharmacyId(), id), w.clinic().owner(), null), 403), "module-off");
    }

    @Test
    void aBadBodyAnswers400AndABadPathIdAnswers400WithAProblemBody() throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.keeper().auth(), "{}"), 400), "validation");
        assertThat(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.keeper().auth(), "{\"clientKey\":\"x\"}"), 400).get("type").asText()).as("a clientKey that is not a UUID").matches(".*/(validation|malformed-request)");
        assertType(problem(call(HttpMethod.POST, postUrl(w.pharmacyId(), id), w.keeper().auth(), "not json"), 400), "malformed-request");
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/opening-stock-imports/not-a-uuid/posting-preview",
                w.keeper().auth(), null), 400), "bad-request");
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isZero();
    }
}
