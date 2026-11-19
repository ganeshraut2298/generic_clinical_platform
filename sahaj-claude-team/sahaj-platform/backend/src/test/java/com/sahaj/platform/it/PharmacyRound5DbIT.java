package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Migration V2026_12_17_1740 at the database (docs/database/SAH-79-inventory.md section 20.5; direct SQL as sahaj_app with the
 * tenant bound, every probe rolled back): the short-supply-only debit note (CREATED and PICKED to CREDIT_NOTE_RECEIVED, the
 * credit insert, the relaxed ck_debit_note_handed_over_status), the goods receipt APPROVED to DISCARDED, the purchase order
 * SENT to CLOSED_SHORT (still refused), ck_stock_ledger_system_types (parent, every partition, a partition made afterwards) and
 * the m-7 function comment. Test plan: docs/test-plans/SAH-79.md (T79-1740-*).
 */
@OnTestDatabase
class PharmacyRound5DbIT extends Sah79Support {

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    private static final String NOT_ALLOWED = "debit note cannot go from ";
    private static final String CREDIT_AFTER_HAND_OVER = "credit note can be recorded only after hand-over";

    // ---------------- debit note helpers ----------------

    /** A receipt with one line (draft), returns the line id; the note's SHORT_SUPPLY line points at it. */
    private String receiptLine(Connection c, Db w) throws SQLException {
        String receipt = newId();
        DbAccess.execute(c, "insert into pharmacy.goods_receipt (id, unit_id, store_id, vendor_id, invoice_no, invoice_date, invoice_total_paise, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, current_date, 1000, ?::uuid)", receipt, w.unit(), w.central(), w.vendor(),
                "INV-" + SEQ.incrementAndGet(), w.actor());
        String line = newId();
        DbAccess.execute(c, "insert into pharmacy.goods_receipt_line (id, unit_id, goods_receipt_id, product_id, sort, batch_no, expiry_date, qty, billed_qty, ptr, mrp, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?, '2031-03-31', 5, 10, 10, 20, ?::uuid)", line, w.unit(), receipt, w.product(),
                "SS" + SEQ.incrementAndGet(), w.actor());
        return line;
    }

    private String draftNote(Connection c, Db w) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.debit_note (id, unit_id, store_id, vendor_id, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                id, w.unit(), w.central(), w.vendor(), w.actor());
        return id;
    }

    private String shortLine(Connection c, Db w, String noteId) throws SQLException {
        String line = newId();
        DbAccess.execute(c, "insert into pharmacy.debit_note_line (id, unit_id, debit_note_id, sort, line_type, product_id, goods_receipt_line_id, qty, rate, credit_percent, "
                + "credit_paise, created_by) values (?::uuid, ?::uuid, ?::uuid, 1, 'SHORT_SUPPLY', ?::uuid, ?::uuid, 5, 10, 100, 5000, ?::uuid)",
                line, w.unit(), noteId, w.product(), receiptLine(c, w), w.actor());
        return line;
    }

    private String stockLine(Connection c, Db w, String noteId, int sort) throws SQLException {
        String batch = batch(c, w, "DN" + SEQ.incrementAndGet(), "2031-03-31");
        String line = newId();
        DbAccess.execute(c, "insert into pharmacy.debit_note_line (id, unit_id, debit_note_id, sort, line_type, product_id, batch_id, source_state, qty, rate, credit_percent, "
                + "credit_paise, created_by) values (?::uuid, ?::uuid, ?::uuid, ?, 'STOCK_RETURN', ?::uuid, ?::uuid, 'AVAILABLE', 2, 10, 100, 2000, ?::uuid)",
                line, w.unit(), noteId, sort, w.product(), batch, w.actor());
        return line;
    }

    private static final String TO_CREATED = "update pharmacy.debit_note set status = 'CREATED', doc_seq = ?, doc_no = ? where id = ?::uuid";

    private void numbered(Connection c, String id, String status) throws SQLException {
        int s = SEQ.incrementAndGet();
        DbAccess.execute(c, "update pharmacy.debit_note set status = ?, doc_seq = ?, doc_no = ? where id = ?::uuid", status, s, "DN-" + s, id);
    }

    /** A note in the state: short-supply-only (goods false) or with one STOCK_RETURN line (goods true). */
    private String noteIn(Connection c, Db w, String state, boolean goods) throws SQLException {
        String id = draftNote(c, w);
        if (goods) {
            stockLine(c, w, id, 1);
        } else {
            shortLine(c, w, id);
        }
        switch (state) {
            case "DRAFT" -> { }
            case "WAITING_APPROVAL" -> numbered(c, id, "WAITING_APPROVAL");
            case "CREATED" -> numbered(c, id, "CREATED");
            case "PICKED" -> {
                numbered(c, id, "CREATED");
                DbAccess.execute(c, "update pharmacy.debit_note set status = 'PICKED', picked_at = now(), picked_by = ?::uuid where id = ?::uuid", newId(), id);
            }
            case "HANDED_OVER" -> {
                numbered(c, id, "CREATED");
                DbAccess.execute(c, "update pharmacy.debit_note set status = 'HANDED_OVER', handed_over_at = now(), handed_over_by = ?::uuid, representative_name = 'Rep (fake)' "
                        + "where id = ?::uuid", newId(), id);
            }
            case "CANCELLED" -> {
                numbered(c, id, "CREATED");
                DbAccess.execute(c, "update pharmacy.debit_note set status = 'CANCELLED' where id = ?::uuid", id);
            }
            default -> throw new IllegalArgumentException(state);
        }
        return id;
    }

    private SQLException toReceived(Connection c, String id) throws SQLException {
        return refused(c, "update pharmacy.debit_note set status = 'CREDIT_NOTE_RECEIVED' where id = ?::uuid", id);
    }

    private SQLException credit(Connection c, Db w, String noteId) throws SQLException {
        return refused(c, "insert into pharmacy.debit_note_credit (unit_id, debit_note_id, credit_note_no, credit_note_date, amount_paise, difference_paise, applied_paise, "
                + "unapplied_credit_paise, created_by) values (?::uuid, ?::uuid, 'CN-1', current_date, 1000, 0, 0, 1000, ?::uuid)", w.unit(), noteId, w.actor());
    }

    // ---------------- 1740 section 1: the short-supply-only note ----------------

    @Test
    void aNoteWithShortSupplyLinesOnlyMovesFromCreatedAndFromPickedToCreditNoteReceivedWithNoHandOverTime() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String from : List.of("CREATED", "PICKED")) {
                String id = noteIn(c, w, from, false);
                thatRefusal(toReceived(c, id)).as("short supply only, from " + from).isNull();
                assertThat(str(c, "select status || '/' || coalesce(handed_over_at::text, 'none') from pharmacy.debit_note where id = ?::uuid", id))
                        .as("no hand-over data is invented").isEqualTo("CREDIT_NOTE_RECEIVED/none");
            }
        });
    }

    @Test
    void aNoteWithALiveStockReturnLineStillNeedsHandOverFirstFromCreatedAndPicked() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String from : List.of("CREATED", "PICKED")) {
                String id = noteIn(c, w, from, true);
                SQLException e = toReceived(c, id);
                thatRefusal(e).as("goods, from " + from).isNotNull();
                assertThat(e.getSQLState()).isEqualTo("23514");
                assertThat(e.getMessage()).contains(NOT_ALLOWED + from + " to CREDIT_NOTE_RECEIVED");
            }
            String handed = noteIn(c, w, "HANDED_OVER", true);
            thatRefusal(toReceived(c, handed)).as("HANDED_OVER to CREDIT_NOTE_RECEIVED").isNull();
        });
    }

    @Test
    void aRemovedStockReturnLineCountsAsNoGoodsAndMixedLinesNeedTheHandOver() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            // one STOCK_RETURN line, removed while DRAFT, and one short supply: no goods
            String id = draftNote(c, w);
            String stock = stockLine(c, w, id, 1);
            shortLine(c, w, id);
            mustAccept(c, "update pharmacy.debit_note_line set removed = true where id = ?::uuid", stock);
            numbered(c, id, "CREATED");
            thatRefusal(toReceived(c, id)).as("the removed goods line counts as none").isNull();
            // a live STOCK_RETURN line next to a short supply: goods
            String mixed = draftNote(c, w);
            stockLine(c, w, mixed, 1);
            shortLine(c, w, mixed);
            numbered(c, mixed, "CREATED");
            thatRefusal(toReceived(c, mixed)).as("a live goods line").isNotNull();
        });
    }

    @Test
    void creditNoteReceivedStaysFinalAndTheOtherStartingStatesAreStillRefused() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String from : List.of("DRAFT", "WAITING_APPROVAL", "CANCELLED")) {
                String id = noteIn(c, w, from, false);
                SQLException e = toReceived(c, id);
                thatRefusal(e).as(from + " to CREDIT_NOTE_RECEIVED").isNotNull();
                assertThat(e.getSQLState()).isEqualTo("23514");
            }
            String done = noteIn(c, w, "CREATED", false);
            mustAccept(c, "update pharmacy.debit_note set status = 'CREDIT_NOTE_RECEIVED' where id = ?::uuid", done);
            mustRefuse(c, "23514", "debit note is final (CREDIT_NOTE_RECEIVED)", "update pharmacy.debit_note set status = 'CANCELLED' where id = ?::uuid", done);
        });
    }

    @Test
    void theHandedOverCheckIsRelaxedForCreditNoteReceivedButNotForHandedOver() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String id = noteIn(c, w, "CREATED", true);
            // HANDED_OVER with no hand-over time: the check (the guard sees a legal move)
            mustRefuse(c, "23514", "ck_debit_note_handed_over_status", "update pharmacy.debit_note set status = 'HANDED_OVER' where id = ?::uuid", id);
        });
        asOwner(c -> {
            assertThat(str(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_debit_note_handed_over_status'"))
                    .contains("HANDED_OVER").doesNotContain("CREDIT_NOTE_RECEIVED");
            assertThat(number(c, "select count(*) from pg_constraint where conname = 'ck_debit_note_handed_over_status' and convalidated")).isOne();
        });
    }

    @Test
    void theCreditInsertIsAcceptedInCreatedAndPickedForAShortSupplyOnlyNoteAndRefusedWhereTheNoteHasGoodsOrIsNotYetCreated() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String state : List.of("CREATED", "PICKED")) {
                thatRefusal(credit(c, w, noteIn(c, w, state, false))).as("short supply only in " + state).isNull();
                SQLException e = credit(c, w, noteIn(c, w, state, true));
                thatRefusal(e).as("goods in " + state).isNotNull();
                assertThat(e.getMessage()).contains(CREDIT_AFTER_HAND_OVER);
            }
            thatRefusal(credit(c, w, noteIn(c, w, "HANDED_OVER", true))).as("goods, HANDED_OVER").isNull();
            for (String state : List.of("DRAFT", "WAITING_APPROVAL", "CANCELLED")) {
                for (boolean goods : new boolean[] {false, true}) {
                    SQLException e = credit(c, w, noteIn(c, w, state, goods));
                    thatRefusal(e).as(state + " goods=" + goods).isNotNull();
                    assertThat(e.getMessage()).contains(CREDIT_AFTER_HAND_OVER);
                }
            }
            // a second credit for the same note stays refused
            String id = noteIn(c, w, "CREATED", false);
            thatRefusal(credit(c, w, id)).isNull();
            SQLException second = credit(c, w, id);
            thatRefusal(second).isNotNull();
            assertThat(second.getMessage()).contains("uq_debit_note_credit_note");
            // either order works: the credit first, then the status move
            thatRefusal(toReceived(c, id)).as("the status move after the credit").isNull();
        });
    }

    // ---------------- 1740 section 2: the goods receipt and the purchase order ----------------

    private String receipt(Connection c, Db w, String invoice) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.goods_receipt (id, unit_id, store_id, vendor_id, invoice_no, invoice_date, invoice_total_paise, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, current_date, 1000, ?::uuid)", id, w.unit(), w.central(), w.vendor(), invoice, w.actor());
        return id;
    }

    private void receiptTo(Connection c, String id, String status) throws SQLException {
        DbAccess.execute(c, "update pharmacy.goods_receipt set status = ? where id = ?::uuid", status, id);
    }

    @Test
    void anApprovedGoodsReceiptCanBeDiscardedDirectlyAndThenTheInvoiceNumberIsFreeAgain() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String id = receipt(c, w, "INV-FREE");
            receiptTo(c, id, "WAITING_APPROVAL");
            receiptTo(c, id, "APPROVED");
            mustRefuse(c, "23505", "uq_goods_receipt_invoice", "insert into pharmacy.goods_receipt (unit_id, store_id, vendor_id, invoice_no, invoice_date, invoice_total_paise, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ' inv-free ', current_date, 1000, ?::uuid)", w.unit(), w.central(), w.vendor(), w.actor());
            mustAccept(c, "update pharmacy.goods_receipt set status = 'DISCARDED' where id = ?::uuid", id);
            mustAccept(c, "insert into pharmacy.goods_receipt (unit_id, store_id, vendor_id, invoice_no, invoice_date, invoice_total_paise, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, 'INV-FREE', current_date, 1000, ?::uuid)", w.unit(), w.central(), w.vendor(), w.actor());
        });
    }

    @Test
    void theOtherReceiptMovesAreAsBeforeApprovedToPostedAndDraftAcceptedPostedAndDiscardedFinalDraftToApprovedRefused() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String toDraft = receipt(c, w, "INV-A");
            receiptTo(c, toDraft, "WAITING_APPROVAL");
            receiptTo(c, toDraft, "APPROVED");
            mustAccept(c, "update pharmacy.goods_receipt set status = 'DRAFT' where id = ?::uuid", toDraft);

            String posted = receipt(c, w, "INV-B");
            receiptTo(c, posted, "WAITING_APPROVAL");
            receiptTo(c, posted, "APPROVED");
            int s = SEQ.incrementAndGet();
            mustAccept(c, "update pharmacy.goods_receipt set status = 'POSTED', doc_seq = ?, doc_no = ?, posted_at = now(), posted_by = ?::uuid where id = ?::uuid",
                    s, "GRN-" + s, w.actor(), posted);
            mustRefuse(c, "23514", "goods receipt is final (POSTED)", "update pharmacy.goods_receipt set status = 'DISCARDED' where id = ?::uuid", posted);

            String gone = receipt(c, w, "INV-C");
            mustAccept(c, "update pharmacy.goods_receipt set status = 'DISCARDED' where id = ?::uuid", gone);
            mustRefuse(c, "23514", "goods receipt is final (DISCARDED)", "update pharmacy.goods_receipt set status = 'DRAFT' where id = ?::uuid", gone);

            String draft = receipt(c, w, "INV-D");
            mustRefuse(c, "23514", "goods receipt cannot go from DRAFT to APPROVED", "update pharmacy.goods_receipt set status = 'APPROVED' where id = ?::uuid", draft);
        });
    }

    private String order(Connection c, Db w) throws SQLException {
        String id = newId();
        int s = SEQ.incrementAndGet();
        DbAccess.execute(c, "insert into pharmacy.purchase_order (id, unit_id, store_id, vendor_id, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                id, w.unit(), w.central(), w.vendor(), w.actor());
        DbAccess.execute(c, "update pharmacy.purchase_order set status = 'APPROVED', doc_seq = ?, doc_no = ? where id = ?::uuid", s, "PO-" + s, id);
        return id;
    }

    @Test
    void aSentPurchaseOrderStillCannotBeClosedShortButAPartlyReceivedOneCan() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String sent = order(c, w);
            mustAccept(c, "update pharmacy.purchase_order set status = 'SENT', sent_on = current_date where id = ?::uuid", sent);
            mustRefuse(c, "23514", "purchase order cannot go from SENT to CLOSED_SHORT", "update pharmacy.purchase_order set status = 'CLOSED_SHORT' where id = ?::uuid", sent);
            mustAccept(c, "update pharmacy.purchase_order set status = 'PARTLY_RECEIVED' where id = ?::uuid", sent);
            mustAccept(c, "update pharmacy.purchase_order set status = 'CLOSED_SHORT' where id = ?::uuid", sent);
            mustRefuse(c, "23514", "purchase order is final (CLOSED_SHORT)", "update pharmacy.purchase_order set status = 'RECEIVED' where id = ?::uuid", sent);
        });
    }

    // ---------------- 1740 section 3: L16 ----------------

    private SQLException ledger(Connection c, Db w, String type, String actorKind, String occurredAt) throws SQLException {
        boolean user = actorKind.equals("USER");
        return refused(c, "insert into pharmacy.stock_ledger (unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, balance_after, "
                + "actor_kind, actor_membership_id, occurred_at) values (?::uuid, ?::uuid, ?, ?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, 5, 0, 5, ?, ?::uuid, "
                + "coalesce(?::timestamptz, clock_timestamp()))", w.unit(), newId(), type, w.central(), w.product(), batch(c, w, "L16" + SEQ.incrementAndGet(), "2031-03-31"),
                actorKind, user ? w.actor() : null, occurredAt);
    }

    @Test
    void aSystemLineOfAnyTypeButTheExpiryQuarantineIsRefusedByTheCheckInTwoMonthsAndAUserLineOfEachTypePasses() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String later = str(c, "select (date_trunc('month', now()) + interval '2 months' + interval '3 days')::text");
            for (String at : new String[] {null, later}) {
                for (String type : List.of("GRN", "VENDOR_RETURN_HOLD", "OPENING_STOCK")) {
                    SQLException e = ledger(c, w, type, "SYSTEM", at);
                    thatRefusal(e).as("SYSTEM " + type + " at " + at).isNotNull();
                    assertThat(e.getSQLState()).isEqualTo("23514");
                    assertThat(e.getMessage()).contains("ck_stock_ledger_system_types");
                }
                thatRefusal(ledger(c, w, "EXPIRY_QUARANTINE", "SYSTEM", at)).as("SYSTEM EXPIRY_QUARANTINE at " + at).isNull();
                for (String type : List.of("GRN", "VENDOR_RETURN_HOLD", "OPENING_STOCK", "EXPIRY_QUARANTINE", "WRITE_OFF")) {
                    thatRefusal(ledger(c, w, type, "USER", at)).as("USER " + type + " at " + at).isNull();
                }
            }
        });
    }

    @Test
    void theSystemTypesCheckIsOnTheParentEveryPartitionAndAPartitionMadeAfterwardsAndIsValidated() throws Exception {
        asOwner(c -> {
            assertThat(number(c, "select count(*) from pg_constraint where conrelid = 'pharmacy.stock_ledger'::regclass and conname = 'ck_stock_ledger_system_types' and convalidated"))
                    .as("on the parent, validated").isOne();
            long partitions = number(c, "select count(*) from pg_inherits where inhparent = 'pharmacy.stock_ledger'::regclass");
            assertThat(partitions).isGreaterThanOrEqualTo(6);
            assertThat(number(c, "select count(*) from pg_inherits i join pg_constraint k on k.conrelid = i.inhrelid and k.conname = 'ck_stock_ledger_system_types' "
                    + "where i.inhparent = 'pharmacy.stock_ledger'::regclass and k.convalidated")).as("every partition carries it, validated").isEqualTo(partitions);
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2030-04-15', 1)")).isOne();
            assertThat(number(c, "select count(*) from pg_constraint where conrelid = 'pharmacy.stock_ledger_y2030m04'::regclass and conname = 'ck_stock_ledger_system_types'"))
                    .as("a partition made afterwards has it from its first row").isOne();
            assertThat(str(c, "select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'pharmacy.stock_ledger_y2030m04'::regclass and conname = 'ck_stock_ledger_system_types'"))
                    .contains("EXPIRY_QUARANTINE").contains("USER");
        });
    }

    // ---------------- 1740 section 4: m-7 ----------------

    @Test
    void theStoreSwitchOffGuardCommentSaysStockPostingTakesNoStoreLock() throws Exception {
        asOwner(c -> {
            String comment = str(c, "select obj_description('pharmacy.guard_store_inactive_stock()'::regprocedure, 'pg_proc')");
            assertThat(comment).contains("StockPosting takes no store lock");
        });
    }
}
