package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-14 to SAH-79-16 (pharmacy.yaml 0.3.6, WP-G): the store return to the Central Store, the damage report with each outcome
 * and the approval link, store-to-store transfers (create, approval, dispatch, receipt, cancel), ward consumption and the ward
 * link (Integrated-only reads answer integration-off in phase 1). Each flow ends with the ledger equal to the balances. Test
 * plan: docs/test-plans/SAH-79.md section 9 (T79-14-*, T79-15-*, T79-16-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpGStoreUseApiIT extends Sah79StoreUseSupport {

    // ---------------- 79-14 store return ----------------

    @Test
    void aStoreReturnMovesAvailableToPendingReturnWritesAWaitingItemAndNothingEntersTheCentralStore() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        long centralBefore = bal(w, w.centralId(), "TESTB001", "AVAILABLE");
        JsonNode r = storeReturn(u, w.keeper(), "TESTB001", 10, 201);
        assertThat(r.get("number").asText()).startsWith("RET-");
        assertThat(r.get("quantity").asInt()).isEqualTo(10);
        assertThat(r.get("item").get("status").asText()).isEqualTo("WAITING");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(90);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "PENDING_RETURN")).isEqualTo(10);
        assertThat(bal(w, w.centralId(), "TESTB001", "AVAILABLE")).isEqualTo(centralBefore);
        assertThat(ledger(w, "STORE_RETURN")).isGreaterThanOrEqualTo(1);
        assertThat(count(w.clinic(), "select count(*) from platform.audit_event where action = 'PHARMACY_STORE_RETURN_CREATED'")).isEqualTo(1);
        JsonNode again = get(w.keeper(), url(w.pharmacyId(), "store-returns/" + idOf(r)), 200);
        assertThat(idOf(again)).isEqualTo(idOf(r));
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aStoreReturnWithTheSameClientKeyReplaysAndMovesNothingTwice() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        Map<String, Object> body = m("clientKey", UUID.randomUUID().toString(), "batchId", batchId(w, w.pharmacyId(), "TESTB001"),
                "quantity", 5, "reasonKey", REASON_RETURN, "note", null);
        JsonNode first = post(w.keeper(), url(w.pharmacyId(), "store-returns"), body, 201);
        JsonNode second = post(w.keeper(), url(w.pharmacyId(), "store-returns"), body, 200);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "PENDING_RETURN")).isEqualTo(5);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STORE_RETURN' and store_id = ?::uuid", w.pharmacyId()))
                .isEqualTo(2); // out of Available and into Pending return: two ledger rows (pharmacy.yaml pharmacyCreateStoreReturn)
    }

    @Test
    void aStoreReturnRefusesBadInputWithAProblemAndMovesNothing() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        String path = url(w.pharmacyId(), "store-returns");
        assertThat(post(w.keeper(), path, m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 0, "reasonKey", REASON_RETURN), 400)
                .toString()).contains("quantity");
        JsonNode above = post(w.keeper(), path, m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 101, "reasonKey", REASON_RETURN), 400);
        assertThat(firstMessage(above)).contains("Enter a whole number from 1 to 100");
        post(w.keeper(), path, m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 1, "reasonKey", "store-return.no-such-reason"), 400);
        post(w.keeper(), path, m("clientKey", UUID.randomUUID().toString(), "batchId", UUID.randomUUID().toString(), "quantity", 1, "reasonKey", REASON_RETURN), 400);
        post(w.keeper(), path, m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 1, "reasonKey", REASON_RETURN, "tenantId", "x"), 400);
        post(w.keeper(), path, m("batchId", batch, "quantity", 1, "reasonKey", REASON_RETURN), 400);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "PENDING_RETURN")).isZero();
    }

    @Test
    void theCentralStoreCannotReturnToItselfAndAnotherClinicOrRoleIsRefused() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode r = storeReturn(u, w.keeper(), "TESTB001", 1, 201);
        int status = call(HttpMethod.POST, url(w.centralId(), "store-returns"), w.csUser().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 1, "reasonKey", REASON_RETURN))).andReturn().getResponse().getStatus();
        assertThat(status).isIn(400, 403, 404);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "store-returns/" + idOf(r)), other.owner(), null), 404);
        problem(call(HttpMethod.POST, url(w.pharmacyId(), "store-returns"), other.owner(),
                js(m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 1, "reasonKey", REASON_RETURN))), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        int fd = call(HttpMethod.POST, url(w.pharmacyId(), "store-returns"), frontDesk.auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", 1, "reasonKey", REASON_RETURN))).andReturn().getResponse().getStatus();
        assertThat(fd).isEqualTo(403);
    }

    // ---------------- 79-14 damage report ----------------

    @Test
    void aDamageReportWrittenOffAtOnceWhenNoRuleApplies() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode d = damage(u, w.keeper(), "TESTB003", 3, "WRITE_OFF", 201);
        assertThat(d.get("number").asText()).startsWith("DMG-");
        assertThat(d.get("status").asText()).isEqualTo("WRITTEN_OFF");
        assertThat(d.get("outcome").asText()).isEqualTo("Written off at Main Pharmacy");
        assertThat(bal(w, w.pharmacyId(), "TESTB003", "AVAILABLE")).isZero();
        assertThat(bal(w, w.pharmacyId(), "TESTB003", "QUARANTINE")).isZero();
        assertThat(ledger(w, "DAMAGE_QUARANTINE")).isGreaterThanOrEqualTo(1);
        assertThat(ledger(w, "WRITE_OFF")).isGreaterThanOrEqualTo(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aDamageReportSentToTheCentralStoreAtOnceWhenNoRuleApplies() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode d = damage(u, w.keeper(), "TESTB001", 4, "SEND_TO_CS", 201);
        assertThat(d.get("status").asText()).isEqualTo("SENT_TO_CS");
        assertThat(d.get("outcome").asText()).isEqualTo("Sent to the Central Store");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "PENDING_RETURN")).isEqualTo(4);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(96);
        assertThat(ledger(w, "DAMAGE_SEND_TO_CS")).isGreaterThanOrEqualTo(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aDamageReportThatNeedsApprovalQuarantinesAtOnceAndWritesOffOnApproval() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        ownerAlways(w, "WRITE_OFF");
        JsonNode d = damage(u, w.keeper(), "TESTB001", 6, "WRITE_OFF", 201);
        assertThat(d.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(d.get("outcome").isNull()).isTrue();
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "QUARANTINE")).isEqualTo(6);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(94);
        JsonNode approval = d.get("approval");
        assertThat(approval.get("status").asText()).isEqualTo("PENDING");
        // the reporter cannot approve (rule 18)
        problem(call(HttpMethod.POST, decisionUrl(approval.get("requestId").asText()), w.keeper().auth(),
                js(decision(1, approval.get("requestVersion").asLong(), "APPROVED", null, null))), 403);
        decide(w.second(), approval, "APPROVED", null, null, 200);
        JsonNode after = get(w.keeper(), url(w.pharmacyId(), "damage-reports/" + idOf(d)), 200);
        assertThat(after.get("status").asText()).isEqualTo("WRITTEN_OFF");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "QUARANTINE")).isZero();
        assertThat(ledger(w, "WRITE_OFF")).isGreaterThanOrEqualTo(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void anApprovedDamageReportCanSendTheUnitsToTheCentralStore() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        ownerAlways(w, "WRITE_OFF");
        JsonNode d = damage(u, w.keeper(), "TESTB001", 2, "SEND_TO_CS", 201);
        assertThat(d.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        decide(w.second(), d.get("approval"), "APPROVED", null, null, 200);
        JsonNode after = get(w.keeper(), url(w.pharmacyId(), "damage-reports/" + idOf(d)), 200);
        assertThat(after.get("status").asText()).isEqualTo("SENT_TO_CS");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "PENDING_RETURN")).isEqualTo(2);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "QUARANTINE")).isZero();
        assertThat(ledger(w, "DAMAGE_SEND_TO_CS")).isGreaterThanOrEqualTo(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aRejectedDamageReportIsReleasedToAvailableOrKeptInQuarantineByTheDeciderChoice() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        ownerAlways(w, "WRITE_OFF");
        JsonNode released = damage(u, w.keeper(), "TESTB001", 5, "WRITE_OFF", 201);
        decide(w.second(), released.get("approval"), "REJECTED", REJECT_OTHER, "RELEASE_TO_AVAILABLE", 200);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "damage-reports/" + idOf(released)), 200).get("status").asText()).isEqualTo("RELEASED");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "QUARANTINE")).isZero();
        JsonNode kept = damage(u, w.keeper(), "TESTB001", 7, "WRITE_OFF", 201);
        decide(w.second(), kept.get("approval"), "REJECTED", REJECT_OTHER, "KEEP_IN_QUARANTINE", 200);
        JsonNode keptNow = get(w.keeper(), url(w.pharmacyId(), "damage-reports/" + idOf(kept)), 200);
        assertThat(keptNow.get("status").asText()).isEqualTo("KEPT_IN_QUARANTINE");
        assertThat(keptNow.get("outcome").asText()).isEqualTo("Kept in quarantine");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "QUARANTINE")).isEqualTo(7);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void theDamageApprovalRequestIsAttachedToTheReportWithAVersionBump() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        ownerAlways(w, "WRITE_OFF");
        JsonNode d = damage(u, w.keeper(), "TESTB001", 2, "WRITE_OFF", 201);
        String requestId = d.get("approval").get("requestId").asText();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.damage_report where id = ?::uuid and approval_request_id = ?::uuid",
                idOf(d), requestId)).isEqualTo(1);
        assertThat(d.get("version").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_request where id = ?::uuid and subject_type = 'DAMAGE_REPORT'", requestId)).isEqualTo(1);
    }

    @Test
    void aDamageReportRefusesBadInputOnTheCentralStoreAndOnAnotherClinic() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        String path = url(w.pharmacyId(), "damage-reports");
        assertThat(firstMessage(post(w.keeper(), path, damageBody(batch, "AVAILABLE", 101, "WRITE_OFF"), 400))).contains("Enter a whole number from 1 to 100");
        post(w.keeper(), path, damageBody(batch, "AVAILABLE", 0, "WRITE_OFF"), 400);
        post(w.keeper(), path, damageBody(batch, "QUARANTINE", 1, "WRITE_OFF"), 400);
        post(w.keeper(), path, damageBody(batch, "SALEABLE", 1, "WRITE_OFF"), 400);
        post(w.keeper(), path, damageBody(batch, "AVAILABLE", 1, "DESTROY"), 400);
        Map<String, Object> badReason = damageBody(batch, "AVAILABLE", 1, "WRITE_OFF");
        badReason.put("reasonKey", "damage.no-such");
        post(w.keeper(), path, badReason, 400);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
        // at the Central Store only WRITE_OFF
        // the Central Store has no opening stock of its own: post one batch (product B, TESTB001, 10 units), as PharmacyWpEDebitNoteApiIT.notEligible does
        String importId = idOf(startImport(w.clinic().owner(), w.centralId(), 1));
        addRows(w.clinic().owner(), w.centralId(), importId, rows(row(1, "CS", w.codeB(), "TESTB001", farExpiry(), "20.00", "12.00", "10")));
        submit(w.clinic().owner(), w.centralId(), importId);
        ok(HttpMethod.POST, importUrl(w.centralId(), importId) + "/approve", w.second().auth(), null, 200);
        postImport(w.clinic().owner(), w.centralId(), importId, UUID.randomUUID());
        String centralBatch = batchId(w, w.centralId(), "TESTB001");
        assertThat(centralBatch).as("the opening-stock batch TESTB001 is found in the Central Store").isNotBlank();
        int status = call(HttpMethod.POST, url(w.centralId(), "damage-reports"), w.csUser().auth(), js(damageBody(centralBatch, "AVAILABLE", 1, "SEND_TO_CS")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(400, 404);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.POST, path, other.owner(), js(damageBody(batch, "AVAILABLE", 1, "WRITE_OFF"))), 404);
    }

    // ---------------- 79-15 transfers ----------------

    @Test
    void aTransferIsRequestedApprovedAtLevelZeroAndMovesNothingUntilDispatch() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode t = transfer(u, w.keeper(), w.productA(), 6, 201);
        assertThat(t.get("number").asText()).startsWith("TRF-");
        assertThat(t.get("status").asText()).isEqualTo("APPROVED");
        assertThat(t.get("totalValuePaise").asLong()).isPositive();
        assertThat(t.get("lines")).hasSize(1);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
        assertThat(ledger(w, "TRANSFER_DISPATCH")).isZero();
        JsonNode list = get(w.keeper(), url(w.pharmacyId(), "transfers"), 200);
        assertThat(list.get("items")).hasSize(1);
        assertThat(get(w.keeper(), url(u.wardId(), "transfers"), 200).get("items")).hasSize(1);
    }

    @Test
    void aTransferDispatchedThenReceivedMovesFefoUnitsAndReconciles() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode t = transfer(u, w.keeper(), w.productA(), 6, 201);
        JsonNode dispatched = post(w.keeper(), url(w.pharmacyId(), "transfers/" + idOf(t) + "/dispatch"),
                m("clientKey", UUID.randomUUID().toString(), "version", t.get("version").asLong()), 200);
        assertThat(dispatched.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(94);
        assertThat(bal(w, u.wardId(), "TESTB001", "IN_TRANSIT")).isEqualTo(6);
        assertThat(ledger(w, "TRANSFER_DISPATCH")).isGreaterThanOrEqualTo(2);
        assertLedgerEqualsBalances(w);
        String dispatchId = dispatched.get("dispatch").get("dispatchId").asText();
        JsonNode dispatch = get(w.keeper(), url(u.wardId(), "dispatches/" + dispatchId), 200);
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (JsonNode line : dispatch.get("lines")) {
            lines.add(m("dispatchLineId", line.get("id").asText(), "accepted", line.get("dispatched").asInt(), "rejected", 0, "rejectReasonKey", null,
                    "note", null, "skipped", false));
        }
        post(w.keeper(), url(u.wardId(), "dispatches/" + dispatchId + "/receipt"),
                m("clientKey", UUID.randomUUID().toString(), "dispatchVersion", dispatch.get("version").asLong(), "lines", lines, "secondSigner", null), 200);
        assertThat(bal(w, u.wardId(), "TESTB001", "IN_TRANSIT")).isZero();
        assertThat(bal(w, u.wardId(), "TESTB001", "AVAILABLE")).isEqualTo(106);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "transfers/" + idOf(t)), 200).get("status").asText()).isEqualTo("RECEIVED");
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aTransferThatNeedsApprovalWaitsThenIsApprovedAndOnlyThenDispatches() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        ownerAlways(w, "STOCK_TRANSFER");
        JsonNode t = transfer(u, w.keeper(), w.productA(), 3, 201);
        assertThat(t.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        String dispatchUrl = url(w.pharmacyId(), "transfers/" + idOf(t) + "/dispatch");
        problem(call(HttpMethod.POST, dispatchUrl, w.keeper().auth(), js(m("clientKey", UUID.randomUUID().toString(), "version", t.get("version").asLong()))), 409);
        decide(w.second(), t.get("approval"), "APPROVED", null, null, 200);
        JsonNode approved = get(w.keeper(), url(w.pharmacyId(), "transfers/" + idOf(t)), 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        post(w.keeper(), dispatchUrl, m("clientKey", UUID.randomUUID().toString(), "version", approved.get("version").asLong()), 200);
        assertThat(bal(w, u.wardId(), "TESTB001", "IN_TRANSIT")).isEqualTo(3);
    }

    @Test
    void aCancelledTransferKeepsItsNumberAndCannotBeDispatched() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode t = transfer(u, w.keeper(), w.productA(), 2, 201);
        JsonNode cancelled = post(w.keeper(), url(w.pharmacyId(), "transfers/" + idOf(t) + "/cancel"),
                m("version", t.get("version").asLong(), "reasonKey", REJECT_OTHER, "note", "Raised by mistake"), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("number").asText()).isEqualTo(t.get("number").asText());
        problem(call(HttpMethod.POST, url(w.pharmacyId(), "transfers/" + idOf(t) + "/dispatch"), w.keeper().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", cancelled.get("version").asLong()))), 409);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
    }

    @Test
    void aTransferRefusesBadInputWithAProblem() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        String path = url(w.pharmacyId(), "transfers");
        Map<String, Object> same = m("clientKey", UUID.randomUUID().toString(), "fromStoreId", w.pharmacyId(), "toStoreId", w.pharmacyId(),
                "reasonKey", REASON_TRANSFER, "lines", List.of(pl(w.productA(), 1)));
        assertThat(post(w.keeper(), path, same, 400).toString()).contains("Choose two different stores");
        Map<String, Object> toCentral = m("clientKey", UUID.randomUUID().toString(), "fromStoreId", w.pharmacyId(), "toStoreId", w.centralId(),
                "reasonKey", REASON_TRANSFER, "lines", List.of(pl(w.productA(), 1)));
        assertThat(post(w.keeper(), path, toCentral, 400).toString()).contains("Use Return to Central Store");
        transfer(u, w.keeper(), w.productA(), 0, 400);
        transfer(u, w.keeper(), w.productA(), 1000, 400);
        Map<String, Object> dup = m("clientKey", UUID.randomUUID().toString(), "fromStoreId", w.pharmacyId(), "toStoreId", u.wardId(),
                "reasonKey", REASON_TRANSFER, "lines", List.of(pl(w.productA(), 1), pl(w.productA(), 2)));
        post(w.keeper(), path, dup, 400);
        Map<String, Object> badReason = m("clientKey", UUID.randomUUID().toString(), "fromStoreId", w.pharmacyId(), "toStoreId", u.wardId(),
                "reasonKey", "transfer.nothing", "lines", List.of(pl(w.productA(), 1)));
        post(w.keeper(), path, badReason, 400);
        Map<String, Object> noLines = m("clientKey", UUID.randomUUID().toString(), "fromStoreId", w.pharmacyId(), "toStoreId", u.wardId(),
                "reasonKey", REASON_TRANSFER, "lines", List.of());
        post(w.keeper(), path, noLines, 400);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.transfer")).isZero();
    }

    @Test
    void aDispatchAfterTheStockWasTakenAnswers409InsufficientStockAndMovesNothing() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode t = transfer(u, w.keeper(), w.productA(), 100, 201);
        storeReturn(u, w.keeper(), "TESTB001", 10, 201);
        JsonNode p = problem(call(HttpMethod.POST, url(w.pharmacyId(), "transfers/" + idOf(t) + "/dispatch"), w.keeper().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", t.get("version").asLong()))), 409);
        assertThat(p.get("type").asText()).endsWith("insufficient-stock");
        assertThat(bal(w, u.wardId(), "TESTB001", "IN_TRANSIT")).isZero();
        assertThat(ledger(w, "TRANSFER_DISPATCH")).isZero();
    }

    @Test
    void theTransferStoresReadListsTheOtherStoresAndAnotherClinicSeesNothing() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        JsonNode list = get(w.keeper(), url(w.pharmacyId(), "transfer-stores"), 200);
        List<String> ids = new java.util.ArrayList<>();
        list.get("items").forEach(i -> ids.add(i.get("storeId").asText()));
        assertThat(ids).contains(w.centralId(), u.wardId()).doesNotContain(w.pharmacyId());
        assertThat(ids.get(0)).isEqualTo(w.centralId());
        JsonNode t = transfer(u, w.keeper(), w.productA(), 1, 201);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "transfers/" + idOf(t)), other.owner(), null), 404);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "transfers"), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        assertThat(call(HttpMethod.GET, url(w.pharmacyId(), "transfers"), frontDesk.auth(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------- 79-16 consumption and the ward link ----------------

    @Test
    void aGeneralConsumptionTakesFefoUnitsFromTheWardAndReplaysByClientKey() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        Map<String, Object> body = consumeBody(PURPOSE, List.of(pl(w.productA(), 4)));
        JsonNode c = post(w.keeper(), url(u.wardId(), "consumptions"), body, 201);
        assertThat(c.get("type").asText()).isEqualTo("GENERAL");
        assertThat(c.get("chargeStatus").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(c.get("ipNumber").isNull()).isTrue();
        assertThat(c.get("costBookedTo").asText()).isEqualTo("Ward 3B");
        assertThat(bal(w, u.wardId(), "TESTB001", "AVAILABLE")).isEqualTo(96);
        assertThat(ledger(w, "WARD_CONSUMPTION")).isGreaterThanOrEqualTo(1);
        JsonNode replay = post(w.keeper(), url(u.wardId(), "consumptions"), body, 200);
        assertThat(idOf(replay)).isEqualTo(idOf(c));
        assertThat(bal(w, u.wardId(), "TESTB001", "AVAILABLE")).isEqualTo(96);
        assertThat(get(w.keeper(), url(u.wardId(), "consumptions/" + idOf(c)), 200).get("lines")).isNotEmpty();
        JsonNode today = get(w.keeper(), url(u.wardId(), "consumptions"), 200);
        assertThat(today.get("items")).hasSize(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aConsumptionRefusesBadInputAndStockAboveTheAvailableQuantity() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        String path = url(u.wardId(), "consumptions");
        post(w.keeper(), path, consumeBody(null, List.of(pl(w.productA(), 1))), 400);
        post(w.keeper(), path, consumeBody("consumption-purpose.nothing", List.of(pl(w.productA(), 1))), 400);
        post(w.keeper(), path, consumeBody(PURPOSE, List.of(pl(w.productA(), 0))), 400);
        post(w.keeper(), path, consumeBody(PURPOSE, List.of(pl(w.productA(), 1), pl(w.productA(), 2))), 400);
        post(w.keeper(), path, consumeBody(PURPOSE, List.of()), 400);
        JsonNode p = post(w.keeper(), path, consumeBody(PURPOSE, List.of(pl(w.productB(), 41))), 409);
        assertThat(p.get("type").asText()).endsWith("insufficient-stock");
        assertThat(bal(w, u.wardId(), "TESTB002", "AVAILABLE")).isEqualTo(40);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.consumption")).isZero();
    }

    @Test
    void aPatientConsumptionAndTheAdmittedPatientsReadAnswerIntegrationOffInPhaseOne() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        Map<String, Object> patient = consumeBody(null, List.of(pl(w.productA(), 1)));
        patient.put("type", "PATIENT");
        patient.put("admissionId", UUID.randomUUID().toString());
        patient.put("orderedBy", m("otherName", "Dr Test Visiting"));
        JsonNode p = post(w.keeper(), url(u.wardId(), "consumptions"), patient, 409);
        assertThat(p.get("type").asText()).endsWith("integration-off");
        assertThat(get(w.keeper(), url(u.wardId(), "consumption/admitted-patients"), 409).get("type").asText()).endsWith("integration-off");
        assertThat(bal(w, u.wardId(), "TESTB001", "AVAILABLE")).isEqualTo(100);
    }

    @Test
    void theWardLinkIsOwnerOnlyAndAnswersIntegrationOffUntilTheIpdReaderExists() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        problem(call(HttpMethod.GET, PH + "/ipd-wards", w.clinic().owner(), null), 409);
        assertThat(call(HttpMethod.GET, PH + "/ipd-wards", w.keeper().auth(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
        JsonNode store = ok(HttpMethod.GET, STORES + "/" + u.wardId(), w.clinic().owner(), null, 200);
        long version = store.get("version").asLong();
        problem(call(HttpMethod.PUT, url(u.wardId(), "linked-ward"), w.clinic().owner(),
                js(m("version", version, "wardId", UUID.randomUUID().toString()))), 409);
        // a pharmacy store cannot be linked
        int notWard = call(HttpMethod.PUT, url(w.pharmacyId(), "linked-ward"), w.clinic().owner(), js(m("version", 0, "wardId", UUID.randomUUID().toString())))
                .andReturn().getResponse().getStatus();
        assertThat(notWard).isIn(400, 409);
        assertThat(call(HttpMethod.PUT, url(u.wardId(), "linked-ward"), w.keeper().auth(), js(m("version", version, "wardId", null)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theOrderedByOptionsAreAListAndAnotherClinicDoesNotSeeTheEntries() throws Exception {
        Uw u = useWorld();
        Sw w = u.w();
        assertThat(get(w.keeper(), PH + "/consumption/ordered-by-options", 200).get("items").isArray()).isTrue();
        JsonNode c = post(w.keeper(), url(u.wardId(), "consumptions"), consumeBody(PURPOSE, List.of(pl(w.productB(), 1))), 201);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(u.wardId(), "consumptions/" + idOf(c)), other.owner(), null), 404);
        problem(call(HttpMethod.GET, url(u.wardId(), "consumptions"), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Front Desk");
        assertThat(call(HttpMethod.POST, url(u.wardId(), "consumptions"), frontDesk.auth(), js(consumeBody(PURPOSE, List.of(pl(w.productB(), 1)))))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
