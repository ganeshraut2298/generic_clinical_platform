package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-79 WP-D, the rules the database itself keeps (docs/database/SAH-79-inventory.md sections 3, 5 and 11): the batch
 * identity and the frozen MRP, stock never below zero, the shape of a ledger line, the approval decision checks, the stock
 * adjustment, the client key, the event row. Each test probes in one rolled-back transaction as sahaj_app with the tenant
 * bound; the clinic, stores, product and vendor are real (API). Test plan: docs/test-plans/SAH-79.md.
 */
@OnTestDatabase
class PharmacyWpDRulesIT extends Sah79Support {

    private static final String FAR = "2031-03-31";

    // ---------------- batch ----------------

    @Test
    void aBatchIsOnePerProductBatchNumberIgnoringCaseAndEdgeSpacesAndExpiryAndTheKeyIsGenerated() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String first = batch(c, w, "ab1", FAR);
            assertThat(str(c, "select batch_key from pharmacy.batch where id = ?::uuid", first)).isEqualTo("AB1");
            mustRefuse(c, "23505", "uq_batch_identity", "insert into pharmacy.batch (unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                    + "values (?::uuid, ?::uuid, ' AB1 ', ?::date, 45.5, 'OPENING_STOCK', ?::uuid)", w.unit(), w.product(), FAR, w.actor());
            mustRefuse(c, "23505", "uq_batch_identity", "insert into pharmacy.batch (unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                    + "values (?::uuid, ?::uuid, 'Ab1', ?::date, 99, 'GRN', ?::uuid)", w.unit(), w.product(), FAR, w.actor());
            // the same number with another expiry is another batch
            mustAccept(c, "insert into pharmacy.batch (unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                    + "values (?::uuid, ?::uuid, 'ab1', '2031-04-30', 45.5, 'OPENING_STOCK', ?::uuid)", w.unit(), w.product(), w.actor());
        });
    }

    @Test
    void theBatchChecksRefuseABadNumberMrpRateSourceAndAnUnknownProductOrVendor() throws Exception {
        Db w = dbWorld();
        Db other = dbWorld();
        asTenant(w, c -> {
            String base = "insert into pharmacy.batch (unit_id, product_id, batch_no, expiry_date, mrp, purchase_rate, vendor_id, source, created_by) "
                    + "values (?::uuid, ?::uuid, ?, '2031-03-31', ?, ?, ?::uuid, ?, ?::uuid)";
            mustRefuse(c, "23514", "ck_batch_mrp", base, w.unit(), w.product(), "M0", 0, null, null, "GRN", w.actor());
            mustRefuse(c, "23514", "ck_batch_mrp", base, w.unit(), w.product(), "M1", -1, null, null, "GRN", w.actor());
            mustRefuse(c, "23514", "ck_batch_purchase_rate", base, w.unit(), w.product(), "R1", 10, -0.5, null, "GRN", w.actor());
            mustRefuse(c, "23514", "ck_batch_no_not_blank", base, w.unit(), w.product(), "   ", 10, null, null, "GRN", w.actor());
            mustRefuse(c, "23514", "ck_batch_no_length", base, w.unit(), w.product(), "N".repeat(21), 10, null, null, "GRN", w.actor());
            mustRefuse(c, "23514", "ck_batch_source", base, w.unit(), w.product(), "S1", 10, null, null, "PURCHASE", w.actor());
            mustRefuse(c, "23503", "fk_batch_product", base, w.unit(), newId(), "P1", 10, null, null, "GRN", w.actor());
            mustRefuse(c, "23503", "fk_batch_vendor", base, w.unit(), w.product(), "V1", 10, null, other.vendor(), "GRN", w.actor());
            // the longest number and a zero rate are fine; a vendor of this clinic is fine
            mustAccept(c, base, w.unit(), w.product(), "N".repeat(20), 0.0001, 0, w.vendor(), "GRN", w.actor());
            mustAccept(c, base, w.unit(), w.product(), "FOUND1", 10, null, null, "FOUND", w.actor());
        });
    }

    @Test
    void theMrpAndTheIdentityOfABatchNeverChangeButTheRateAndTheVendorMay() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String id = batch(c, w, "FRZ1", FAR);
            mustRefuse(c, "23514", "batch MRP cannot change", "update pharmacy.batch set mrp = 50 where id = ?::uuid", id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set batch_no = 'FRZ2' where id = ?::uuid", id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set expiry_date = '2032-01-01' where id = ?::uuid", id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set product_id = ?::uuid where id = ?::uuid", newId(), id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set source = 'GRN' where id = ?::uuid", id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set first_received_at = now() - interval '1 day' where id = ?::uuid", id);
            mustRefuse(c, "23514", "batch identity cannot change", "update pharmacy.batch set created_by = ?::uuid where id = ?::uuid", newId(), id);
            mustAccept(c, "update pharmacy.batch set purchase_rate = 12.34, vendor_id = ?::uuid, updated_by = ?::uuid, version = version + 1 where id = ?::uuid",
                    w.vendor(), w.actor(), id);
            assertThat(str(c, "select purchase_rate::text from pharmacy.batch where id = ?::uuid", id)).isEqualTo("12.3400");
            assertThat(str(c, "select mrp::text from pharmacy.batch where id = ?::uuid", id)).isEqualTo("45.5000");
        });
    }

    // ---------------- balance ----------------

    @Test
    void aBalanceCanNeverBeBelowZeroAndHasOneRowPerStoreBatchAndStateAndItsProductIsTheBatchsProduct() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "BAL1", FAR);
            String row = balance(c, w, w.central(), batch, "AVAILABLE", 5);
            mustRefuse(c, "23514", "ck_stock_balance_qty", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'IN_TRANSIT', -1, ?::uuid)", w.unit(), w.central(), w.product(), batch, w.actor());
            mustRefuse(c, "23514", "ck_stock_balance_qty", "update pharmacy.stock_balance set qty = qty - 6 where id = ?::uuid", row);
            assertThat(number(c, "select qty from pharmacy.stock_balance where id = ?::uuid", row)).isEqualTo(5);
            mustAccept(c, "update pharmacy.stock_balance set qty = qty - 5, version = version + 1 where id = ?::uuid", row);
            assertThat(number(c, "select qty from pharmacy.stock_balance where id = ?::uuid", row)).isZero();
            mustRefuse(c, "23514", "ck_stock_balance_qty", "update pharmacy.stock_balance set qty = qty - 1 where id = ?::uuid", row);
            // the same store, batch and state again
            mustRefuse(c, "23505", "uq_stock_balance_key", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'AVAILABLE', 1, ?::uuid)", w.unit(), w.central(), w.product(), batch, w.actor());
            // another state, another store: fine
            mustAccept(c, "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'QUARANTINE', 1, ?::uuid)", w.unit(), w.central(), w.product(), batch, w.actor());
            mustAccept(c, "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'AVAILABLE', 1, ?::uuid)", w.unit(), w.pharmacy(), w.product(), batch, w.actor());
            // an unknown state; a product that is not the batch's product; an unknown batch
            mustRefuse(c, "23514", "ck_stock_balance_state", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'LOST', 1, ?::uuid)", w.unit(), w.central(), w.product(), batch, w.actor());
            mustRefuse(c, "23503", "fk_stock_balance_batch", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'IN_TRANSIT', 1, ?::uuid)", w.unit(), w.central(), newId(), batch, w.actor());
            mustRefuse(c, "23503", "fk_stock_balance_batch", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'IN_TRANSIT', 1, ?::uuid)", w.unit(), w.central(), w.product(), newId(), w.actor());
        });
    }

    @Test
    void theKeyOfABalanceNeverChangesButTheQuantityMay() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "KEY1", FAR);
            String other = batch(c, w, "KEY2", FAR);
            String row = balance(c, w, w.central(), batch, "AVAILABLE", 5);
            for (String change : new String[] {"store_id = '" + w.pharmacy() + "'::uuid", "batch_id = '" + other + "'::uuid",
                "product_id = '" + newId() + "'::uuid", "state = 'QUARANTINE'", "unit_id = '" + newId() + "'::uuid"}) {
                mustRefuse(c, "23514", "stock balance key cannot change", "update pharmacy.stock_balance set " + change + " where id = ?::uuid", row);
            }
            mustAccept(c, "update pharmacy.stock_balance set qty = 9, updated_by = ?::uuid, version = version + 1 where id = ?::uuid", w.actor(), row);
        });
    }

    @Test
    void twoSessionsTakingTheLastUnitThroughTheLockOrderLeaveTheSecondWithZeroAndTheDatabaseRefusesItsWrite() throws Exception {
        Db w = dbWorld();
        String batch;
        String row;
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            bind(c, w);
            batch = batch(c, w, "LAST1", FAR);
            row = balance(c, w, w.central(), batch, "AVAILABLE", 1);
            c.commit();
        }
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection a = db().app(); Connection b = db().app()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            bind(a, w);
            bind(b, w);
            assertThat(number(a, "select qty from pharmacy.stock_balance where id = ?::uuid for update", row)).isOne();
            CountDownLatch started = new CountDownLatch(1);
            final String rowId = row;
            Future<Long> second = pool.submit(() -> {
                started.countDown();
                return number(b, "select qty from pharmacy.stock_balance where id = ?::uuid for update", rowId); // waits for A
            });
            started.await();
            Thread.sleep(500);
            assertThat(second.isDone()).as("the second session waits for the lock").isFalse();
            DbAccess.execute(a, "update pharmacy.stock_balance set qty = qty - 1 where id = ?::uuid", row);
            a.commit();
            assertThat(second.get(10, TimeUnit.SECONDS)).as("the second sees zero after the first commits").isZero();
            SQLException refusal = refused(b, "update pharmacy.stock_balance set qty = qty - 1 where id = ?::uuid", row);
            thatRefusal(refusal).isNotNull();
            assertThat(refusal.getSQLState()).isEqualTo("23514");
            b.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(w.clinic(), "select qty from pharmacy.stock_balance where id = ?::uuid", row)).isZero();
    }

    private void bind(Connection c, Db w) throws SQLException {
        try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            bind.setString(1, w.tenant().toString());
            bind.execute();
        }
    }

    // ---------------- ledger ----------------

    private static final String LEDGER = "insert into pharmacy.stock_ledger (unit_id, movement_id, txn_type, doc_type, doc_id, store_id, state, product_id, batch_id, "
            + "qty_in, qty_out, balance_after, actor_kind, actor_membership_id, second_signer_membership_id, admission_id, reason_key, reverses_ledger_id) "
            + "values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid)";

    private SQLException ledger(Connection c, Db w, String batch, String txn, String docType, String docId, int in, int out, int after,
                                String kind, String actor, String signer, String admission, String reason, String reverses) throws SQLException {
        return refused(c, LEDGER, w.unit(), newId(), txn, docType, docId, w.central(), w.product(), batch, in, out, after, kind, actor, signer, admission, reason, reverses);
    }

    private void ledgerMustRefuse(Connection c, Db w, String batch, String state, String message, String txn, String docType, String docId, int in, int out,
                                  int after, String kind, String actor, String signer, String admission, String reverses) throws SQLException {
        SQLException e = ledger(c, w, batch, txn, docType, docId, in, out, after, kind, actor, signer, admission, null, reverses);
        thatRefusal(e).as(message).isNotNull();
        assertThat(e.getSQLState()).as(message).isEqualTo(state);
        assertThat(e.getMessage()).as(message).contains(message);
    }

    @Test
    void aLedgerLineHasOneDirectionAFloorOfZeroAndTheActorAndSignerRules() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "LED1", FAR);
            String a = newId();
            String b = newId();
            // a good line
            thatRefusal(ledger(c, w, batch, "OPENING_STOCK", "OPENING_STOCK_IMPORT", newId(), 5, 0, 5, "USER", a, null, null, null, null)).isNull();
            // one direction
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_one_direction", "GRN", null, null, 0, 0, 0, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_one_direction", "GRN", null, null, 3, 2, 3, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_qty_in", "GRN", null, null, -1, 1, 0, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_qty_out", "GRN", null, null, 1, -4, 0, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_balance_after", "GRN", null, null, 0, 1, -1, "USER", a, null, null, null);
            // actor kind
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_actor", "GRN", null, null, 1, 0, 1, "SYSTEM", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_actor", "GRN", null, null, 1, 0, 1, "USER", null, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_actor_kind", "GRN", null, null, 1, 0, 1, "ROBOT", a, null, null, null);
            // second signer
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_signer_differs", "GRN", null, null, 1, 0, 1, "USER", a, a, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_signer_needs_actor", "EXPIRY_QUARANTINE", null, null, 0, 1, 0, "SYSTEM", null, b, null, null);
            thatRefusal(ledger(c, w, batch, "GRN", null, null, 1, 0, 1, "USER", a, b, null, null, null)).as("a different second signer").isNull();
            // document pair
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_doc_pair", "GRN", "GRN", null, 1, 0, 1, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_doc_pair", "GRN", null, newId(), 1, 0, 1, "USER", a, null, null, null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_doc_type", "GRN", "grn", newId(), 1, 0, 1, "USER", a, null, null, null);
            // admission id exactly for patient-wise consumption
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_admission", "WARD_CONSUMPTION", null, null, 0, 1, 0, "USER", a, null, newId(), null);
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_admission", "WARD_CONSUMPTION_PATIENT", null, null, 0, 1, 0, "USER", a, null, null, null);
            thatRefusal(ledger(c, w, batch, "WARD_CONSUMPTION_PATIENT", null, null, 0, 1, 0, "USER", a, null, newId(), null, null)).isNull();
            // txn types
            ledgerMustRefuse(c, w, batch, "23514", "ck_stock_ledger_txn_type", "MYSTERY", null, null, 1, 0, 1, "USER", a, null, null, null);
            // a system line: expiry quarantine with no document
            thatRefusal(ledger(c, w, batch, "EXPIRY_QUARANTINE", null, null, 0, 2, 3, "SYSTEM", null, null, null, "damage.expired", null)).isNull();
            // a line cannot reverse itself
            String self = newId();
            SQLException selfReverse = refused(c, "insert into pharmacy.stock_ledger (id, unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, "
                    + "qty_out, balance_after, actor_kind, actor_membership_id, reverses_ledger_id) values (?::uuid, ?::uuid, ?::uuid, 'GRN', ?::uuid, 'AVAILABLE', "
                    + "?::uuid, ?::uuid, 1, 0, 1, 'USER', ?::uuid, ?::uuid)", self, w.unit(), newId(), w.central(), w.product(), batch, a, self);
            thatRefusal(selfReverse).isNotNull();
            assertThat(selfReverse.getMessage()).contains("ck_stock_ledger_not_self_reverse");
            // the reason key format
            SQLException badReason = ledger(c, w, batch, "GRN", null, null, 1, 0, 1, "USER", a, null, null, "Bad Reason", null);
            thatRefusal(badReason).isNotNull();
            assertThat(badReason.getMessage()).contains("ck_stock_ledger_reason");
        });
    }

    @Test
    void everyOneOfTheTwentyFourTransactionTypesOfTheContractIsAccepted() throws Exception {
        Db w = dbWorld();
        String[] types = {"OPENING_STOCK", "GRN", "EXPIRY_QUARANTINE", "STOCK_ADJUSTMENT", "VENDOR_RETURN_HOLD", "VENDOR_RETURN_RELEASE", "VENDOR_RETURN",
            "INDENT_DISPATCH", "INDENT_RECEIPT_ACCEPTED", "INDENT_RECEIPT_REJECTED", "RETURN_ACCEPTED", "SHORTAGE_FOUND", "SHORTAGE_WRITE_OFF", "STORE_RETURN",
            "DAMAGE_QUARANTINE", "DAMAGE_RELEASE", "DAMAGE_SEND_TO_CS", "WRITE_OFF", "TRANSFER_DISPATCH", "TRANSFER_RECEIPT_ACCEPTED",
            "TRANSFER_RECEIPT_REJECTED", "WARD_CONSUMPTION", "WARD_CONSUMPTION_PATIENT", "STOCK_AUDIT_ADJUSTMENT"};
        assertThat(types).hasSize(24);
        asTenant(w, c -> {
            String batch = batch(c, w, "TYP1", FAR);
            for (String type : types) {
                String admission = type.equals("WARD_CONSUMPTION_PATIENT") ? newId() : null;
                thatRefusal(ledger(c, w, batch, type, null, null, 1, 0, 1, "USER", w.actor(), null, admission, null, null)).as(type).isNull();
            }
            assertThat(number(c, "select count(*) from pharmacy.stock_ledger where batch_id = ?::uuid", batch)).isEqualTo(24);
        });
    }

    @Test
    void theLedgerSumEqualsTheBalanceAfterAPostingAndTheReconciliationQueryReturnsNoRow() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "REC1", FAR);
            balance(c, w, w.central(), batch, "AVAILABLE", 10);
            ledgerIn(c, w, w.central(), batch, 10);
            assertThat(number(c, "select count(*) from (select b.store_id, b.batch_id, b.state, b.qty, coalesce(sum(l.qty_in - l.qty_out), 0) as ledger_qty "
                    + "from pharmacy.stock_balance b left join pharmacy.stock_ledger l on l.tenant_id = b.tenant_id and l.store_id = b.store_id "
                    + "and l.batch_id = b.batch_id and l.state = b.state group by b.store_id, b.batch_id, b.state, b.qty "
                    + "having b.qty <> coalesce(sum(l.qty_in - l.qty_out), 0)) x")).isZero();
            // a balance moved without a ledger line is caught by the same query
            DbAccess.execute(c, "update pharmacy.stock_balance set qty = 7 where batch_id = ?::uuid", batch);
            assertThat(number(c, "select count(*) from (select b.batch_id from pharmacy.stock_balance b left join pharmacy.stock_ledger l on l.tenant_id = b.tenant_id "
                    + "and l.store_id = b.store_id and l.batch_id = b.batch_id and l.state = b.state group by b.store_id, b.batch_id, b.state, b.qty "
                    + "having b.qty <> coalesce(sum(l.qty_in - l.qty_out), 0)) x")).isOne();
        });
    }

    // ---------------- approvals ----------------

    @Test
    void nobodyApprovesTheirOwnRequestAndADecisionCopiesTheRaiserOfItsRequest() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String boss = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            String base = "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as, reason_key) "
                    + "values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, 'OWNER', ?)";
            mustRefuse(c, "23514", "ck_approval_decision_not_self", base, w.unit(), request, 1, "APPROVED", raiser, raiser, null);
            mustRefuse(c, "23514", "approval decision raised_by must match the request", base, w.unit(), request, 1, "APPROVED", boss, newId(), null);
            mustRefuse(c, "23514", "ck_approval_decision_reject_reason", base, w.unit(), request, 1, "REJECTED", boss, raiser, null);
            mustRefuse(c, "23503", "fk_approval_decision_request", base, w.unit(), newId(), 1, "APPROVED", boss, raiser, null);
            mustRefuse(c, "23514", "ck_approval_decision_decision", base, w.unit(), request, 1, "MAYBE", boss, raiser, null);
            mustRefuse(c, "23514", "ck_approval_decision_level", base, w.unit(), request, 3, "APPROVED", boss, raiser, null);
            mustAccept(c, base, w.unit(), request, 1, "APPROVED", boss, raiser, null);
        });
    }

    @Test
    void oneFinalDecisionPerLevelAndTheSamePersonCannotApproveBothLevelsButCanRejectAfterApproving() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String boss = newId();
            String other = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            decision(c, w, request, 1, "APPROVED", boss, raiser);
            mustRefuse(c, "23505", "uq_approval_decision_level", "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, "
                    + "acting_as) values (?::uuid, ?::uuid, 1, 'APPROVED', ?::uuid, ?::uuid, 'HOD')", w.unit(), request, other, raiser);
            mustRefuse(c, "23505", "uq_approval_decision_one_approval_each", "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, "
                    + "raised_by, acting_as) values (?::uuid, ?::uuid, 2, 'APPROVED', ?::uuid, ?::uuid, 'OWNER')", w.unit(), request, boss, raiser);
            // another person at level 2 is accepted
            mustAccept(c, "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as) "
                    + "values (?::uuid, ?::uuid, 2, 'APPROVED', ?::uuid, ?::uuid, 'OWNER')", w.unit(), request, other, raiser);
        });
        asTenant(w, c -> {
            String raiser = newId();
            String boss = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            decision(c, w, request, 1, "APPROVED", boss, raiser);
            // the unique index covers approvals only: the same person may decide level 2 as a rejection
            mustAccept(c, "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as, reason_key) "
                    + "values (?::uuid, ?::uuid, 2, 'REJECTED', ?::uuid, ?::uuid, 'OWNER', 'approval-reject.other')", w.unit(), request, boss, raiser);
        });
    }

    @Test
    void aRequestHasAtMostOnePendingRequestPerDocumentTheLevelMatchesTheStatusAndADecidedRequestIsFinal() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String subject = newId();
            String first = approvalRequest(c, w, subject, raiser);
            String insert = "insert into pharmacy.approval_request (unit_id, transaction_type, subject_type, subject_id, rule_store_id, raised_by, rule_version_id, "
                    + "amount_paise, current_level, status, created_by) values (?::uuid, 'STOCK_ADJUSTMENT', 'STOCK_ADJUSTMENT', ?::uuid, ?::uuid, ?::uuid, "
                    + "(select rule_version_id from pharmacy.approval_request where id = ?::uuid), 5, ?, ?, ?::uuid)";
            mustRefuse(c, "23505", "uq_approval_request_one_pending", insert, w.unit(), subject, w.central(), raiser, first, 1, "PENDING", raiser);
            // the level and the status go together
            mustRefuse(c, "23514", "ck_approval_request_level_pending", insert, w.unit(), newId(), w.central(), raiser, first, null, "PENDING", raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_pending", insert, w.unit(), newId(), w.central(), raiser, first, 1, "APPROVED", raiser);
            // the raiser is the creator
            mustRefuse(c, "23514", "ck_approval_request_raiser", insert, w.unit(), newId(), w.central(), raiser, first, 1, "PENDING", newId());
            // decide the first: then a second request for the same document is accepted
            mustAccept(c, "update pharmacy.approval_request set status = 'APPROVED', current_level = null, version = version + 1 where id = ?::uuid", first);
            mustAccept(c, insert, w.unit(), subject, w.central(), raiser, first, 1, "PENDING", raiser);
            // a decided request is final
            mustRefuse(c, "23514", "approval request is final (APPROVED)", "update pharmacy.approval_request set status = 'REJECTED', current_level = null where id = ?::uuid", first);
            mustRefuse(c, "23514", "approval request is final (APPROVED)", "update pharmacy.approval_request set subject_no = 'X' where id = ?::uuid", first);
        });
    }

    @Test
    void aPendingRequestKeepsItsIdentityAndItsLevelNeverGoesBack() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            for (String change : new String[] {"amount_paise = 1", "urgent = true", "has_schedule_x = true", "has_high_value_item = true", "raised_by = '" + newId() + "'::uuid",
                "rule_store_id = '" + w.pharmacy() + "'::uuid", "max_rate_rise_percent = 5", "escalate_after_minutes = 30",
                "transaction_type = 'INDENT'", "subject_type = 'INDENT'", "subject_id = '" + newId() + "'::uuid", "raised_at = now() - interval '1 day'"}) {
                mustRefuse(c, "23514", "approval request identity cannot change", "update pharmacy.approval_request set " + change + " where id = ?::uuid", request);
            }
            // free while pending: the number, the level (up), the time the level was reached
            mustAccept(c, "update pharmacy.approval_request set subject_no = 'GRN-0001', level_entered_at = now(), version = version + 1 where id = ?::uuid", request);
            mustAccept(c, "update pharmacy.approval_request set current_level = 2, version = version + 1 where id = ?::uuid", request);
            mustRefuse(c, "23514", "approval request level cannot go back", "update pharmacy.approval_request set current_level = 1 where id = ?::uuid", request);
        });
    }

    @Test
    void theLevelRowsOfARequestFollowTheirKindColumns() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            String base = "insert into pharmacy.approval_request_level (unit_id, request_id, level_no, approver_kind, condition_kind, amount_paise, percent, store_types, "
                    + "applies, rule_text, created_by) values (?::uuid, ?::uuid, ?, 'OWNER', ?, ?, ?, ?::text[], true, 'text', ?::uuid)";
            mustRefuse(c, "23514", "ck_approval_request_level_amount_kind", base, w.unit(), request, 1, "ABOVE_AMOUNT", null, null, null, raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_amount_kind", base, w.unit(), request, 1, "ALWAYS", 100, null, null, raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_percent_kind", base, w.unit(), request, 1, "ABOVE_PERCENT", null, null, null, raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_store_types_kind", base, w.unit(), request, 1, "STORE_TYPE", null, null, null, raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_store_types", base, w.unit(), request, 1, "STORE_TYPE", null, null, "{FOO}", raiser);
            mustRefuse(c, "23514", "ck_approval_request_level_percent", base, w.unit(), request, 1, "ABOVE_PERCENT", null, 100.5, null, raiser);
            mustAccept(c, base, w.unit(), request, 1, "ABOVE_AMOUNT", 5000, null, null, raiser);
            mustRefuse(c, "23505", "uq_approval_request_level", base, w.unit(), request, 1, "ALWAYS", null, null, null, raiser);
            mustAccept(c, base, w.unit(), request, 2, "STORE_TYPE", null, null, "{PHARMACY,WARD}", raiser);
        });
    }

    // ---------------- stock adjustment ----------------

    @Test
    void anAdjustmentMustChangeTheStockHasADifferenceAndOneWaitingRowPerBatchAndStore() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String batch = batch(c, w, "ADJ1", FAR);
            String request = approvalRequest(c, w, newId(), raiser);
            mustRefuse(c, "23514", "ck_stock_adjustment_changes", "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, "
                    + "physical_qty, value_paise, reason_key, status, created_by) values (?::uuid, 1, 'ADJ-0001', ?::uuid, ?::uuid, ?::uuid, 5, 5, 0, "
                    + "'stock-adjustment.breakage', 'WAITING_APPROVAL', ?::uuid)", w.unit(), w.central(), w.product(), batch, raiser);
            String first = adjustment(c, w, batch, request, 1, 10, 7, raiser);
            assertThat(number(c, "select difference from pharmacy.stock_adjustment where id = ?::uuid", first)).isEqualTo(-3);
            String more = adjustment(c, w, newBatchOf(c, w, "ADJ2"), request, 2, 3, 8, raiser);
            assertThat(number(c, "select difference from pharmacy.stock_adjustment where id = ?::uuid", more)).isEqualTo(5);
            // a second waiting adjustment for the same batch and store
            SQLException waiting = refused(c, "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, physical_qty, "
                    + "value_paise, reason_key, status, created_by) values (?::uuid, 3, 'ADJ-0003', ?::uuid, ?::uuid, ?::uuid, 1, 2, 0, 'stock-adjustment.breakage', "
                    + "'WAITING_APPROVAL', ?::uuid)", w.unit(), w.central(), w.product(), batch, raiser);
            thatRefusal(waiting).isNotNull();
            assertThat(waiting.getSQLState()).isEqualTo("23505");
            assertThat(waiting.getMessage()).contains("uq_stock_adjustment_one_waiting");
            // the same batch in another store is fine
            mustAccept(c, "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, physical_qty, value_paise, reason_key, "
                    + "status, created_by) values (?::uuid, 4, 'ADJ-0004', ?::uuid, ?::uuid, ?::uuid, 1, 2, 0, 'stock-adjustment.breakage', 'WAITING_APPROVAL', ?::uuid)",
                    w.unit(), w.pharmacy(), w.product(), batch, raiser);
            // a duplicate number or sequence
            mustRefuse(c, "23505", "uq_stock_adjustment_doc_no", "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, "
                    + "physical_qty, value_paise, reason_key, status, created_by) values (?::uuid, 9, 'ADJ-0001', ?::uuid, ?::uuid, ?::uuid, 1, 2, 0, "
                    + "'stock-adjustment.breakage', 'WAITING_APPROVAL', ?::uuid)", w.unit(), w.pharmacy(), w.product(), newBatchOf(c, w, "ADJ3"), raiser);
        });
    }

    private String newBatchOf(Connection c, Db w, String no) throws SQLException {
        return batch(c, w, no, FAR);
    }

    @Test
    void aWaitingAdjustmentGoesToPostedOnceAndThenNothingChangesAndARejectedOneCannotStartThatWay() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String batch = batch(c, w, "ADJP", FAR);
            String request = approvalRequest(c, w, newId(), raiser);
            String id = adjustment(c, w, batch, request, 1, 10, 7, raiser);
            // identity of a waiting one is frozen
            for (String change : new String[] {"book_qty = 11", "physical_qty = 6", "value_paise = 5", "reason_key = 'stock-adjustment.unknown'", "note = 'x'",
                "doc_no = 'ADJ-0099'", "created_by = '" + newId() + "'::uuid", "store_id = '" + w.pharmacy() + "'::uuid"}) {
                mustRefuse(c, "23514", "stock adjustment cannot change once saved", "update pharmacy.stock_adjustment set " + change + " where id = ?::uuid", id);
            }
            // POSTED needs posted_at, and the other way round
            mustRefuse(c, "23514", "ck_stock_adjustment_posted", "update pharmacy.stock_adjustment set status = 'POSTED' where id = ?::uuid", id);
            mustRefuse(c, "23514", "ck_stock_adjustment_posted", "update pharmacy.stock_adjustment set posted_at = now() where id = ?::uuid", id);
            mustAccept(c, "update pharmacy.stock_adjustment set status = 'POSTED', posted_at = now(), version = version + 1 where id = ?::uuid", id);
            mustRefuse(c, "23514", "stock adjustment is final (POSTED)", "update pharmacy.stock_adjustment set status = 'POSTED' where id = ?::uuid", id);
            mustRefuse(c, "23514", "stock adjustment is final (POSTED)", "update pharmacy.stock_adjustment set version = version + 1 where id = ?::uuid", id);
            // once posted a new waiting adjustment for the same batch and store is accepted
            mustAccept(c, "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, physical_qty, value_paise, reason_key, "
                    + "status, created_by) values (?::uuid, 2, 'ADJ-0002', ?::uuid, ?::uuid, ?::uuid, 7, 9, 0, 'stock-adjustment.breakage', 'WAITING_APPROVAL', ?::uuid)",
                    w.unit(), w.central(), w.product(), batch, raiser);
            // rejected cannot be an insert
            mustRefuse(c, "23514", "stock adjustment cannot start as rejected", "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, "
                    + "batch_id, book_qty, physical_qty, value_paise, reason_key, status, approval_request_id, created_by) values (?::uuid, 3, 'ADJ-0003', ?::uuid, ?::uuid, "
                    + "?::uuid, 7, 9, 0, 'stock-adjustment.breakage', 'REJECTED', ?::uuid, ?::uuid)", w.unit(), w.pharmacy(), w.product(), batch, request, raiser);
        });
    }

    @Test
    void theSecondSignerOfAnAdjustmentIsNeverTheRaiserAndARejectedOneNeedsItsApprovalRequest() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String batch = batch(c, w, "ADJS", FAR);
            String insert = "insert into pharmacy.stock_adjustment (unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, physical_qty, value_paise, "
                    + "reason_key, status, second_signer_membership_id, created_by) values (?::uuid, ?, ?, ?::uuid, ?::uuid, ?::uuid, 5, 1, 0, "
                    + "'stock-adjustment.theft-or-loss', 'WAITING_APPROVAL', ?::uuid, ?::uuid)";
            mustRefuse(c, "23514", "ck_stock_adjustment_signer_differs", insert, w.unit(), 1, "ADJ-0001", w.central(), w.product(), batch, raiser, raiser);
            mustAccept(c, insert, w.unit(), 1, "ADJ-0001", w.central(), w.product(), batch, newId(), raiser);
            String id = str(c, "select id::text from pharmacy.stock_adjustment where doc_no = 'ADJ-0001'");
            mustRefuse(c, "23514", "ck_stock_adjustment_rejected", "update pharmacy.stock_adjustment set status = 'REJECTED' where id = ?::uuid", id);
            // doc_seq and doc_no checks
            mustRefuse(c, "23514", "ck_stock_adjustment_doc_seq", insert, w.unit(), 0, "ADJ-0002", w.pharmacy(), w.product(), batch, newId(), raiser);
            mustRefuse(c, "23514", "ck_stock_adjustment_doc_no", insert, w.unit(), 2, "A".repeat(17), w.pharmacy(), w.product(), batch, newId(), raiser);
        });
    }

    // ---------------- client key and event ----------------

    @Test
    void aClientKeyIsUsedOncePerCallerAndAnotherCallerMayStoreTheSameKeyButTheIndexFindsBoth() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String key = newId();
            String me = newId();
            String sql = "insert into pharmacy.client_request (unit_id, membership_id, client_key, operation, subject_id) values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid)";
            mustAccept(c, sql, w.unit(), me, key, "pharmacyPostOpeningStock", newId());
            mustRefuse(c, "23505", "uq_client_request_key", sql, w.unit(), me, key, "pharmacyPostOpeningStock", newId());
            mustAccept(c, sql, w.unit(), newId(), key, "pharmacyPostOpeningStock", newId());
            assertThat(number(c, "select count(*) from pharmacy.client_request where client_key = ?::uuid", key)).isEqualTo(2);
            mustRefuse(c, "23514", "ck_client_request_operation", sql, w.unit(), me, newId(), "", newId());
            mustRefuse(c, "23514", "ck_client_request_operation", sql, w.unit(), me, newId(), "o".repeat(61), newId());
            mustAccept(c, sql, w.unit(), me, newId(), "o".repeat(60), newId());
        });
    }

    @Test
    void aDocumentEventHasAVocabularyPatternAFixedActingAsAndNonNegativeQuantities() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String sql = "insert into pharmacy.document_event (unit_id, doc_type, doc_id, event, store_id, actor_membership_id, acting_as, qty_before, qty_after, reason_key, note) "
                    + "values (?::uuid, ?, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)";
            mustAccept(c, sql, w.unit(), "GOODS_RECEIPT", newId(), "DECIDED", w.central(), newId(), "HOD", 5, 0, "approval-reject.other", "A short note");
            mustAccept(c, sql, w.unit(), "NEW_FUTURE_DOC", newId(), "CREATED", w.central(), newId(), null, null, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_doc_type", sql, w.unit(), "grn", newId(), "CREATED", w.central(), newId(), null, null, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_doc_type", sql, w.unit(), "X", newId(), "CREATED", w.central(), newId(), null, null, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_event", sql, w.unit(), "GRN", newId(), "created", w.central(), newId(), null, null, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_acting_as", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), "BOSS", null, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_qty_before", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, -1, null, null, null);
            mustRefuse(c, "23514", "ck_document_event_qty_after", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, null, -1, null, null);
            mustRefuse(c, "23514", "ck_document_event_note", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, null, null, null, "");
            mustRefuse(c, "23514", "ck_document_event_note", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, null, null, null, "n".repeat(301));
            mustAccept(c, sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, null, null, null, "n".repeat(300));
            mustRefuse(c, "23514", "ck_document_event_reason", sql, w.unit(), "GRN", newId(), "CREATED", w.central(), newId(), null, null, null, "Bad Key", null);
            // the occurred-at time is the database clock
            assertThat(number(c, "select count(*) from pharmacy.document_event where occurred_at > now() - interval '1 minute'")).isPositive();
        });
    }

    // ---------------- linked ward ----------------

    @Test
    void aLinkedWardIsOnlyAllowedOnAWardStoreAndHasNoForeignKey() throws Exception {
        Db w = dbWorld();
        String ward = idOf(createStore(w.clinic(), "Ward One", "W1", "WARD"));
        asTenant(w, c -> {
            mustRefuse(c, "23514", "ck_store_ipd_ward", "update pharmacy.store set ipd_ward_id = ?::uuid where id = ?::uuid", newId(), w.pharmacy());
            mustRefuse(c, "23514", "ck_store_ipd_ward", "update pharmacy.store set ipd_ward_id = ?::uuid where id = ?::uuid", newId(), w.central());
            mustAccept(c, "update pharmacy.store set ipd_ward_id = ?::uuid where id = ?::uuid", newId(), ward);
            mustAccept(c, "update pharmacy.store set ipd_ward_id = null where id = ?::uuid", w.pharmacy());
            assertThat(number(c, "select count(*) from pg_constraint where conrelid = 'pharmacy.store'::regclass and contype = 'f' and "
                    + "pg_get_constraintdef(oid) like '%ipd_ward_id%'")).as("no foreign key to another module").isZero();
        });
    }
}
