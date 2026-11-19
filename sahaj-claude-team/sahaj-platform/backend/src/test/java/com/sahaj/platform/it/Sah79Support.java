package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-79 WP-D migration tests (V2026_12_17_1300 to 1390, docs/database/SAH-79-inventory.md). The
 * clinic, its stores, one product and one vendor are made through the real API (committed); every probe of a table then runs
 * in one transaction as {@code sahaj_app} with the tenant bound and is rolled back, so nothing a test inserts stays. A refused
 * statement is wrapped in a savepoint so one test can check many rules in one transaction. Quantities, names and codes are
 * invented.
 */
abstract class Sah79Support extends Sah78CSupport {

    /** A clinic with module on, a Central Store, a Pharmacy store, one adopted product and one vendor. */
    protected record Db(Clinic clinic, String unit, String central, String pharmacy, String product, String vendor, String actor) {
        UUID tenant() {
            return clinic.tenantId();
        }
    }

    protected Db dbWorld() throws Exception {
        Masters w = mastersClinic();
        JsonNode product = adopt(w.clinic(), "G-000101");
        JsonNode vendor = createVendor(w.clinic(), vendorBody("Db Vendor (fake)"));
        String unit = values(w.clinic(), "select id::text from pharmacy.unit").get(0);
        return new Db(w.clinic(), unit, w.centralId(), w.pharmacyId(), product.get("productId").asText(), idOf(vendor), UUID.randomUUID().toString());
    }

    @FunctionalInterface
    protected interface Work {
        void run(Connection c) throws SQLException;
    }

    /** Runs the work as sahaj_app with the tenant bound; always rolled back. */
    protected void asTenant(Db w, Work work) throws SQLException {
        db().inTenant(w.tenant(), c -> {
            work.run(c);
            return null;
        });
    }

    /** Runs the work as the owner role sahaj_migrator, rolled back. */
    protected void asOwner(Work work) throws SQLException {
        db().asMigrator(c -> {
            work.run(c);
            return null;
        });
    }

    /** Lets the owner role see and write every row of a table inside the (rolled back) transaction, so a row trigger can be reached. */
    protected static void openToOwner(Connection c, String table) throws SQLException {
        DbAccess.execute(c, "create policy tmp_qa_owner on " + table + " for all to sahaj_migrator using (true) with check (true)");
    }

    /** Executes the statement inside a savepoint; returns the refusal, or null when it was accepted (the effect stays in the transaction). */
    protected static SQLException refused(Connection c, String sql, Object... params) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            DbAccess.execute(c, sql, params);
            c.releaseSavepoint(sp);
            return null;
        } catch (SQLException e) {
            c.rollback(sp);
            return e;
        }
    }

    /** The statement must be refused with this SQLSTATE and a message that contains the text; returns nothing, throws on a mismatch. */
    protected static void mustRefuse(Connection c, String state, String message, String sql, Object... params) throws SQLException {
        SQLException e = refused(c, sql, params);
        if (e == null) {
            throw new AssertionError("Accepted, but " + state + " '" + message + "' was expected: " + sql);
        }
        if (!state.equals(e.getSQLState()) || (message != null && !e.getMessage().contains(message))) {
            throw new AssertionError("Expected " + state + " containing '" + message + "' but got " + e.getSQLState() + " '" + e.getMessage()
                    + "' for: " + sql);
        }
    }

    protected static void mustAccept(Connection c, String sql, Object... params) throws SQLException {
        SQLException e = refused(c, sql, params);
        if (e != null) {
            throw new AssertionError("Refused (" + e.getSQLState() + " " + e.getMessage() + ") but should be accepted: " + sql);
        }
    }

    /** AssertJ finds assertThat(SQLException) ambiguous (a SQLException is both Iterable and Throwable): assert on it as an Object. */
    protected static org.assertj.core.api.ObjectAssert<Object> thatRefusal(SQLException refusal) {
        return org.assertj.core.api.Assertions.assertThat((Object) refusal);
    }

    protected static long number(Connection c, String sql, Object... params) throws SQLException {
        return DbAccess.count(c, sql, params);
    }

    protected static String str(Connection c, String sql, Object... params) throws SQLException {
        return DbAccess.text(c, sql, params);
    }

    // ---------------- rows ----------------

    protected static String newId() {
        return UUID.randomUUID().toString();
    }

    /** A batch of the product (source OPENING_STOCK, MRP 45.50, expiry in the far future); returns its id. */
    protected String batch(Connection c, Db w, String batchNo, String expiry) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.batch (id, unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?, ?::date, 45.50, 'OPENING_STOCK', ?::uuid)", id, w.unit(), w.product(), batchNo, expiry, w.actor());
        return id;
    }

    protected String balance(Connection c, Db w, String storeId, String batchId, String state, int qty) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.stock_balance (id, unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid)", id, w.unit(), storeId, w.product(), batchId, state, qty, w.actor());
        return id;
    }

    /** One ledger line of an opening-stock receipt of the quantity into the store (no document). */
    protected String ledgerIn(Connection c, Db w, String storeId, String batchId, int qty) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.stock_ledger (id, unit_id, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, "
                + "balance_after, actor_kind, actor_membership_id) values (?::uuid, ?::uuid, ?::uuid, 'OPENING_STOCK', ?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, "
                + "?, 0, ?, 'USER', ?::uuid)", id, w.unit(), newId(), storeId, w.product(), batchId, qty, qty, w.actor());
        return id;
    }

    protected String approvalRequest(Connection c, Db w, String subjectId, String raisedBy) throws SQLException {
        String ruleVersion = newId();
        DbAccess.execute(c, "insert into pharmacy.approval_rule_version (id, unit_id, version_no, preset, created_by) values (?::uuid, ?::uuid, "
                + "(select coalesce(max(version_no), 0) + 1 from pharmacy.approval_rule_version), 'CUSTOM', ?::uuid)", ruleVersion, w.unit(), w.actor());
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.approval_request (id, unit_id, transaction_type, subject_type, subject_id, rule_store_id, raised_by, "
                + "rule_version_id, amount_paise, current_level, status, created_by) values (?::uuid, ?::uuid, 'STOCK_ADJUSTMENT', 'STOCK_ADJUSTMENT', "
                + "?::uuid, ?::uuid, ?::uuid, ?::uuid, 100000, 1, 'PENDING', ?::uuid)", id, w.unit(), subjectId, w.central(), raisedBy, ruleVersion, raisedBy);
        return id;
    }

    protected String decision(Connection c, Db w, String requestId, int level, String decision, String decidedBy, String raisedBy) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.approval_decision (id, unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as, reason_key) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, 'OWNER', ?)", id, w.unit(), requestId, level, decision, decidedBy, raisedBy,
                decision.equals("REJECTED") ? "approval-reject.other" : null);
        return id;
    }

    protected String adjustment(Connection c, Db w, String batchId, String requestId, int seq, int book, int physical, String createdBy)
            throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.stock_adjustment (id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty, physical_qty, "
                + "value_paise, reason_key, status, approval_request_id, created_by) values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, 1000, "
                + "'stock-adjustment.count-mismatch', 'WAITING_APPROVAL', ?::uuid, ?::uuid)", id, w.unit(), seq, "ADJ-" + String.format("%04d", seq),
                w.central(), w.product(), batchId, book, physical, requestId, createdBy);
        return id;
    }

    /** The ids of one row in each of the nine new tables, all committed for one clinic. */
    protected record Rows(String batch, String balance, String ledger, String clientRequest, String documentEvent, String request,
                          String level, String decision, String adjustment, String raiser, String decider) {
    }

    protected Rows commitRows(Db w) throws SQLException {
        String raiser = newId();
        String decider = newId();
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, w.tenant().toString());
                bind.execute();
            }
            String batch = batch(c, w, "QA-B1", "2031-03-31");
            String balance = balance(c, w, w.central(), batch, "AVAILABLE", 10);
            String ledger = ledgerIn(c, w, w.central(), batch, 10);
            String clientRequest = newId();
            DbAccess.execute(c, "insert into pharmacy.client_request (id, unit_id, membership_id, client_key, operation, subject_id) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'pharmacyPostOpeningStock', ?::uuid)", clientRequest, w.unit(), raiser, newId(), newId());
            String event = newId();
            DbAccess.execute(c, "insert into pharmacy.document_event (id, unit_id, doc_type, doc_id, event, store_id, actor_membership_id) "
                    + "values (?::uuid, ?::uuid, 'STOCK_ADJUSTMENT', ?::uuid, 'CREATED', ?::uuid, ?::uuid)", event, w.unit(), newId(), w.central(), raiser);
            String adjustmentId = newId();
            String request = approvalRequest(c, w, adjustmentId, raiser);
            String level = newId();
            DbAccess.execute(c, "insert into pharmacy.approval_request_level (id, unit_id, request_id, level_no, approver_kind, condition_kind, applies, "
                    + "rule_text, created_by) values (?::uuid, ?::uuid, ?::uuid, 1, 'OWNER', 'ALWAYS', true, 'Owner always', ?::uuid)", level, w.unit(), request, raiser);
            String decision = decision(c, w, request, 1, "APPROVED", decider, raiser);
            String adjustment = adjustment(c, w, batch, request, 1, 10, 7, raiser);
            c.commit();
            return new Rows(batch, balance, ledger, clientRequest, event, request, level, decision, adjustment, raiser, decider);
        }
    }

    protected static PreparedStatement prepared(Connection c, String sql) throws SQLException {
        return c.prepareStatement(sql);
    }

    protected static HttpMethod get() {
        return HttpMethod.GET;
    }
}
