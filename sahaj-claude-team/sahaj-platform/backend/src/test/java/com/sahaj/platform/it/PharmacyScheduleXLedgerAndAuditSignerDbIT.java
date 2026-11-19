package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Migrations 1720 and 1730 at the database (direct SQL as sahaj_app with the tenant bound, every probe rolled back):
 * L14, a ledger line by a person on one of the 17 person-driven types (ADR-029 P7) for a Schedule X product needs a second
 * signer who is not the actor; the 7 types that are not on the list, a system line and a Schedule H product pass; the trigger
 * is on the monthly partitions too; and the 1730 second signer of a stock audit (ck_stock_audit_signer_differs and
 * guard_stock_audit_write). Test plan: docs/test-plans/SAH-79.md (T79-L14-*, T79-1730-*).
 */
@OnTestDatabase
class PharmacyScheduleXLedgerAndAuditSignerDbIT extends Sah79Support {

    /** The 17 types of the ADR-029 P7 list. */
    private static final List<String> LISTED = List.of("GRN", "STOCK_ADJUSTMENT", "VENDOR_RETURN", "INDENT_DISPATCH", "INDENT_RECEIPT_ACCEPTED",
            "INDENT_RECEIPT_REJECTED", "TRANSFER_DISPATCH", "TRANSFER_RECEIPT_ACCEPTED", "TRANSFER_RECEIPT_REJECTED", "RETURN_ACCEPTED", "SHORTAGE_FOUND",
            "STORE_RETURN", "DAMAGE_QUARANTINE", "DAMAGE_SEND_TO_CS", "WARD_CONSUMPTION", "WARD_CONSUMPTION_PATIENT", "STOCK_AUDIT_ADJUSTMENT");
    /** The 7 types that are not on it. */
    private static final List<String> UNLISTED = List.of("OPENING_STOCK", "EXPIRY_QUARANTINE", "VENDOR_RETURN_HOLD", "VENDOR_RETURN_RELEASE",
            "SHORTAGE_WRITE_OFF", "WRITE_OFF", "DAMAGE_RELEASE");

    private static final String NEEDS_SIGNER = "schedule X movement needs a second signer";

    private record Products(String h, String adoptedX, String ownX) {
    }

    private Products products(Db w) throws Exception {
        JsonNode x = adopt(w.clinic(), "G-000117");
        Map<String, Object> own = ownBody("Test controlled own item (fake)");
        own.put("schedule", "X");
        JsonNode ownX = createOwn(w.clinic(), own);
        return new Products(w.product(), x.get("productId").asText(), ownX.get("productId").asText());
    }

    private String batchOf(Connection c, Db w, String productId, String batchNo) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.batch (id, unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?, '2031-03-31', 45.50, 'OPENING_STOCK', ?::uuid)", id, w.unit(), productId, batchNo, w.actor());
        return id;
    }

    /** One ledger insert; the signer and the actor kind are given; occurredAt null means now. */
    private SQLException line(Connection c, Db w, String type, String productId, String batchId, String actorKind, String signer, String occurredAt)
            throws SQLException {
        boolean user = actorKind.equals("USER");
        return refused(c, "insert into pharmacy.stock_ledger (id, unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, "
                + "balance_after, actor_kind, actor_membership_id, second_signer_membership_id, admission_id, occurred_at) values (?::uuid, ?::uuid, ?::uuid, ?, "
                + "?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, 5, 0, 5, ?, ?::uuid, ?::uuid, ?::uuid, coalesce(?::timestamptz, clock_timestamp()))",
                newId(), w.unit(), newId(), type, w.central(), productId, batchId, actorKind, user ? w.actor() : null, signer,
                type.equals("WARD_CONSUMPTION_PATIENT") ? newId() : null, occurredAt);
    }

    private void expectNeedsSigner(SQLException e, String what) {
        thatRefusal(e).as(what + " is refused").isNotNull();
        assertThat(e.getSQLState()).as(what).isEqualTo("23514");
        assertThat(e.getMessage()).as(what).contains(NEEDS_SIGNER);
    }

    // ---------------- L14 ----------------

    @Test
    void forEachOfTheSeventeenTypesAPersonsLineForAnAdoptedOrOwnScheduleXProductIsRefusedWithoutASignerAndAcceptedWithAnother() throws Exception {
        Db w = dbWorld();
        Products p = products(w);
        asTenant(w, c -> {
            for (Map.Entry<String, String> product : Map.of("adopted X", p.adoptedX(), "own X", p.ownX()).entrySet()) {
                String batch = batchOf(c, w, product.getValue(), "LX" + product.getKey().length());
                assertThat(LISTED).hasSize(17);
                for (String type : LISTED) {
                    expectNeedsSigner(line(c, w, type, product.getValue(), batch, "USER", null, null), product.getKey() + " " + type + " without a signer");
                    thatRefusal(line(c, w, type, product.getValue(), batch, "USER", newId(), null)).as(product.getKey() + " " + type + " with a signer").isNull();
                }
            }
            // the signer must differ from the actor (the ledger's own check)
            String b = batchOf(c, w, p.ownX(), "SAME1");
            SQLException same = refused(c, "insert into pharmacy.stock_ledger (unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, "
                    + "balance_after, actor_kind, actor_membership_id, second_signer_membership_id) values (?::uuid, ?::uuid, 'GRN', ?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, "
                    + "5, 0, 5, 'USER', ?::uuid, ?::uuid)", w.unit(), newId(), w.central(), p.ownX(), b, w.actor(), w.actor());
            thatRefusal(same).isNotNull();
            assertThat(same.getMessage()).contains("ck_stock_ledger_signer_differs");
        });
    }

    @Test
    void theSevenTypesThatAreNotListedASystemLineAndAScheduleHProductPassWithoutASigner() throws Exception {
        Db w = dbWorld();
        Products p = products(w);
        asTenant(w, c -> {
            assertThat(UNLISTED).hasSize(7);
            for (String productId : List.of(p.adoptedX(), p.ownX())) {
                String batch = batchOf(c, w, productId, "UL" + productId.substring(0, 6));
                for (String type : UNLISTED) {
                    thatRefusal(line(c, w, type, productId, batch, "USER", null, null)).as(type + " is not on the list").isNull();
                }
                // the system's own line (migration 1740, ck_stock_ledger_system_types): only the expiry quarantine; the trigger does not fire
                thatRefusal(line(c, w, "EXPIRY_QUARANTINE", productId, batch, "SYSTEM", null, null)).as("a system expiry quarantine").isNull();
                // a SYSTEM line of any other type is refused by that check (not by the Schedule X trigger)
                for (String type : List.of("GRN", "VENDOR_RETURN_HOLD", "OPENING_STOCK", "STOCK_ADJUSTMENT")) {
                    SQLException e = line(c, w, type, productId, batch, "SYSTEM", null, null);
                    thatRefusal(e).as("a system " + type + " line").isNotNull();
                    assertThat(e.getSQLState()).isEqualTo("23514");
                    assertThat(e.getMessage()).contains("ck_stock_ledger_system_types");
                }
            }
            // a Schedule H product needs no signer for any of the 17
            String h = batchOf(c, w, p.h(), "H-ONE");
            for (String type : LISTED) {
                thatRefusal(line(c, w, type, p.h(), h, "USER", null, null)).as("schedule H " + type).isNull();
            }
        });
    }

    @Test
    void theTriggerIsOnTheMonthlyPartitionsSoALineInTwoMonthsIsGuardedTooAndOpeningStockOfAScheduleXItemPasses() throws Exception {
        Db w = dbWorld();
        Products p = products(w);
        asTenant(w, c -> {
            String when = str(c, "select (date_trunc('month', now()) + interval '2 months' + interval '3 days')::text");
            String batch = batchOf(c, w, p.ownX(), "FUT1");
            expectNeedsSigner(line(c, w, "STOCK_ADJUSTMENT", p.ownX(), batch, "USER", null, when), "a line two months ahead");
            thatRefusal(line(c, w, "STOCK_ADJUSTMENT", p.ownX(), batch, "USER", newId(), when)).isNull();
            thatRefusal(line(c, w, "OPENING_STOCK", p.ownX(), batch, "USER", null, when)).as("opening stock of a Schedule X item passes (A-8)").isNull();
            // every partition carries the row trigger (cloned from the parent)
            assertThat(number(c, "select count(*) from pg_inherits i join pg_class p on p.oid = i.inhrelid where i.inhparent = 'pharmacy.stock_ledger'::regclass"))
                    .isGreaterThanOrEqualTo(6);
            assertThat(number(c, "select count(*) from pg_inherits i join pg_class p on p.oid = i.inhrelid where i.inhparent = 'pharmacy.stock_ledger'::regclass "
                    + "and not exists (select 1 from pg_trigger t where t.tgrelid = p.oid and t.tgname = 'trg_stock_ledger_schedule_x' and not t.tgisinternal)"))
                    .as("every partition has the Schedule X trigger").isZero();
        });
    }

    @Test
    void theL14FunctionIsNotCallableByTheAppAndTheTriggerFiresOnlyForAPersonWithoutASigner() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            thatRefusal(refused(c, "select pharmacy.guard_stock_ledger_schedule_x()")).isNotNull();
            assertThat(number(c, "select count(*) from pg_trigger where tgname = 'trg_stock_ledger_schedule_x' and tgrelid = 'pharmacy.stock_ledger'::regclass")).isOne();
            assertThat(str(c, "select pg_get_triggerdef(oid) from pg_trigger where tgname = 'trg_stock_ledger_schedule_x' and tgrelid = 'pharmacy.stock_ledger'::regclass"))
                    .contains("USER").contains("second_signer_membership_id IS NULL");
        });
    }

    // ---------------- 1730: the audit second signer ----------------

    private String audit(Connection c, Db w, String storeId, int seq) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.stock_audit (id, unit_id, doc_seq, doc_no, store_id, scope, created_by) values (?::uuid, ?::uuid, ?, ?, ?::uuid, 'PRODUCTS', ?::uuid)",
                id, w.unit(), seq, "AUD-" + String.format("%04d", seq), storeId, w.actor());
        return id;
    }

    private void line(Connection c, Db w, String auditId, String batchId, String countedBy) throws SQLException {
        DbAccess.execute(c, "insert into pharmacy.stock_audit_line (unit_id, stock_audit_id, product_id, batch_id, counted_qty, counted_by, counted_at, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 4, ?::uuid, now(), ?::uuid)", w.unit(), auditId, w.product(), batchId, countedBy, w.actor());
    }

    private static final String SUBMIT = "update pharmacy.stock_audit set status = 'WAITING_REVIEW', submitted_by = ?::uuid, submitted_at = now() where id = ?::uuid";

    @Test
    void aSecondSignerCannotBeGivenAtInsertWhileCountingOrRecountingAndNotBeforeTheVarianceIsPosted() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            mustRefuse(c, "23514", "second signer is set when the variance is posted", "insert into pharmacy.stock_audit (unit_id, doc_seq, doc_no, store_id, scope, "
                    + "created_by, second_signer_membership_id) values (?::uuid, 9, 'AUD-0009', ?::uuid, 'PRODUCTS', ?::uuid, ?::uuid)", w.unit(), w.central(), w.actor(), newId());
            String id = audit(c, w, w.central(), 1);
            mustRefuse(c, "23514", "second signer is set when the variance is posted", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", newId(), id);
            String submitter = newId();
            mustAccept(c, SUBMIT, submitter, id);
            mustAccept(c, "update pharmacy.stock_audit set status = 'RECOUNT' where id = ?::uuid", id);
            mustRefuse(c, "23514", "second signer is set when the variance is posted", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", newId(), id);
        });
    }

    @Test
    void theSignerIsSetOnceFromReviewNeverTheSubmitterNeverAPersonWhoCountedAndNeverChangedOrCleared() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "AUD-B1", "2031-03-31");
            String id = audit(c, w, w.central(), 1);
            String counter = newId();
            String submitter = newId();
            line(c, w, id, batch, counter);
            mustAccept(c, SUBMIT, submitter, id);
            // the submitter as signer: the check
            mustRefuse(c, "23514", "ck_stock_audit_signer_differs", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", submitter, id);
            // a person who counted a line: the guard
            mustRefuse(c, "23514", "second signer cannot be a person who counted", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", counter, id);
            String signer = newId();
            mustAccept(c, "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", signer, id);
            assertThat(str(c, "select second_signer_membership_id::text from pharmacy.stock_audit where id = ?::uuid", id)).isEqualTo(signer);
            // changed, and cleared: set once
            mustRefuse(c, "23514", "second signer is set once", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", newId(), id);
            mustRefuse(c, "23514", "second signer is set once", "update pharmacy.stock_audit set second_signer_membership_id = null where id = ?::uuid", id);
            // a bookkeeping update leaves the signer alone and passes
            mustAccept(c, "update pharmacy.stock_audit set updated_at = now(), updated_by = ?::uuid, version = version + 1 where id = ?::uuid", w.actor(), id);
            // the move on to approval keeps it
            mustAccept(c, "update pharmacy.stock_audit set status = 'WAITING_APPROVAL' where id = ?::uuid", id);
            assertThat(str(c, "select second_signer_membership_id::text from pharmacy.stock_audit where id = ?::uuid", id)).isEqualTo(signer);
        });
    }

    @Test
    void aClosedAuditIsFinalSoNoSignerCanBeAddedAndTheSignerCheckExistsAndIsValidated() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String id = audit(c, w, w.central(), 1);
            String submitter = newId();
            mustAccept(c, SUBMIT, submitter, id);
            mustAccept(c, "update pharmacy.stock_audit set status = 'CLOSED', closed_at = now() where id = ?::uuid", id);
            mustRefuse(c, "23514", "stock audit is final (CLOSED)", "update pharmacy.stock_audit set second_signer_membership_id = ?::uuid where id = ?::uuid", newId(), id);
            mustRefuse(c, "23514", "stock audit is final (CLOSED)", "update pharmacy.stock_audit set version = version + 1 where id = ?::uuid", id);
        });
        asOwner(c -> {
            assertThat(number(c, "select count(*) from pg_constraint where conname = 'ck_stock_audit_signer_differs' and convalidated")).isOne();
            assertThat(number(c, "select count(*) from information_schema.columns where table_schema = 'pharmacy' and table_name = 'stock_audit' "
                    + "and column_name = 'second_signer_membership_id' and is_nullable = 'YES'")).isOne();
        });
    }
}
