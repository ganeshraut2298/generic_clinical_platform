package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Migrations 1710 and 1720 at the database (docs/database/SAH-79-inventory.md sections 11 to 18; direct SQL as sahaj_app with the
 * tenant bound): the merged guard of the opening-stock import (nine cases), {@code next_document_no} (the last-issued time, the
 * ten types, the errors, the year restart, the unit lock L11), I11a (a request becomes APPROVED only with its decisions), I11b (the
 * rule-set pointer moves forward), m-3 (the payable), m-4 (the indent only moves forward), m-5 (a store holding stock cannot go
 * INACTIVE), m-6 (the ledger index) and L13 (nobody signs or decides their own document, the database says so). Test plan:
 * docs/test-plans/SAH-79.md (T79-1710-*, T79-1720-*, T79-L13-*).
 */
@OnTestDatabase
class PharmacyRound4DbIT extends Sah79ApiSupport {

    private static final String[] TYPES = {"PURCHASE_ORDER", "GRN", "INDENT", "TRANSFER", "BILL", "DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN",
        "DAMAGE_REPORT", "STOCK_ADJUSTMENT"};

    // ---------------- guard_opening_stock_import_update (1710), nine cases ----------------

    private record Imp(Clinic clinic, String storeId, String importId, String uploader, String approver) {
    }

    private Imp importAt(String stage) throws Exception {
        Sw w = stockWorld();
        String id = idOf(draftOfCleanFile(w.keeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        if (!stage.equals("DRAFT")) {
            submit(w.keeper().auth(), w.pharmacyId(), id);
        }
        if (stage.equals("APPROVED")) {
            ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        }
        if (stage.equals("REJECTED")) {
            ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/reject", w.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}", 200);
        }
        return new Imp(w.clinic(), w.pharmacyId(), id, w.keeper().membershipId().toString(), w.second().membershipId().toString());
    }

    private void in(Imp i, Work work) throws Exception {
        db().inTenant(i.clinic().tenantId(), c -> {
            work.run(c);
            return null;
        });
    }

    private static final String UPD = "update pharmacy.opening_stock_import set ";

    @Test
    void theGuardKeepsTheFinalStatesOfARejectedImportAndNothingCanBeChangedOnIt() throws Exception {
        Imp i = importAt("REJECTED");
        in(i, c -> {
            mustRefuse(c, "23514", "opening stock import is final (REJECTED)", UPD + "version = version + 1 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "opening stock import is final (REJECTED)", UPD + "status = 'DRAFT', decided_by = null, decided_at = null, reject_reason_key = null where id = ?::uuid", i.importId());
        });
    }

    @Test
    void aDraftMayBeRejectedAndCannotCarryAPostingOrGoStraightToApproved() throws Exception {
        Imp i = importAt("DRAFT");
        in(i, c -> {
            mustRefuse(c, "23514", "can be posted only after approval", UPD + "posted_at = now(), posted_by = ?::uuid where id = ?::uuid", i.approver(), i.importId());
            mustRefuse(c, "23514", "cannot go from draft to APPROVED", UPD + "status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), submitted_at = now() where id = ?::uuid",
                    i.approver(), i.importId());
            mustAccept(c, UPD + "status = 'REJECTED', decided_by = ?::uuid, decided_at = now(), reject_reason_key = 'WRONG_FILE' where id = ?::uuid", i.approver(), i.importId());
            assertThat(str(c, "select status from pharmacy.opening_stock_import where id = ?::uuid", i.importId())).isEqualTo("REJECTED");
        });
    }

    @Test
    void inADraftTheCountersNeverGoDownAndTheIdentityNeverChanges() throws Exception {
        Imp i = importAt("DRAFT");
        in(i, c -> {
            assertThat(number(c, "select line_rows_written from pharmacy.opening_stock_import where id = ?::uuid", i.importId())).isEqualTo(3);
            mustRefuse(c, "23514", "line counter cannot go down", UPD + "line_rows_written = line_rows_written - 1 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "line counter cannot go down", UPD + "line_request_count = line_request_count - 1 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "identity cannot change", UPD + "row_count = 99 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "identity cannot change", UPD + "uploaded_by = ?::uuid where id = ?::uuid", newId(), i.importId());
            mustAccept(c, UPD + "line_rows_written = line_rows_written + 1, line_request_count = line_request_count + 1 where id = ?::uuid", i.importId());
        });
    }

    @Test
    void onceWaitingTheCountersAndTotalsAreFrozenAndOnlyApprovedRejectedOrReplacedFollow() throws Exception {
        Imp i = importAt("WAITING_APPROVAL");
        in(i, c -> {
            mustRefuse(c, "23514", "totals are fixed once submitted", UPD + "status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), line_rows_written = line_rows_written + 1 "
                    + "where id = ?::uuid", i.approver(), i.importId());
            mustRefuse(c, "23514", "totals are fixed once submitted", UPD + "status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), line_request_count = line_request_count + 1 "
                    + "where id = ?::uuid", i.approver(), i.importId());
            mustRefuse(c, "23514", "can only be approved, rejected or replaced", UPD + "status = 'DRAFT' where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "can be posted only after approval", UPD + "posted_at = now(), posted_by = ?::uuid where id = ?::uuid", i.approver(), i.importId());
        });
    }

    @Test
    void anApprovedImportAcceptsOnePostingUpdateAndThenIsFinalAndNothingElseChangesOnIt() throws Exception {
        Imp i = importAt("APPROVED");
        in(i, c -> {
            mustRefuse(c, "23514", "opening stock import is final (APPROVED)", UPD + "line_rows_written = line_rows_written + 1 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED)", UPD + "posted_at = now() where id = ?::uuid", i.importId());
            mustAccept(c, UPD + "posted_at = now(), posted_by = ?::uuid where id = ?::uuid", i.approver(), i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED, posted)", UPD + "posted_by = ?::uuid where id = ?::uuid", i.uploader(), i.importId());
        });
    }

    // ---------------- next_document_no (1710 and 1720) ----------------

    @Test
    void nextDocumentNoSetsAndMovesTheLastIssuedTimeAndCountsOneTwoThreeForEachOfTheTenTypes() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String type : TYPES) {
                assertThat(number(c, "select pharmacy.next_document_no(?)", type)).as(type).isOne();
                String first = str(c, "select last_issued_at::text from pharmacy.document_counter where doc_type = ?", type);
                assertThat(first).as(type + " last_issued_at is set").isNotNull();
                assertThat(number(c, "select pharmacy.next_document_no(?)", type)).as(type).isEqualTo(2);
                assertThat(number(c, "select count(*) from pharmacy.document_counter where doc_type = ? and last_issued_at is not null "
                        + "and last_issued_at >= ?::timestamptz", type, first)).as(type + " last_issued_at moves").isOne();
                assertThat(number(c, "select next_value from pharmacy.document_counter where doc_type = ?", type)).isEqualTo(3);
            }
            assertThat(TYPES).hasSize(10);
        });
    }

    @Test
    void nextDocumentNoRefusesAnUnknownTypeNoTenantAndNoUnitAndRestartsOnlyWhereTheSeriesSaysSo() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String bad : new String[] {"", "grn", "FOO"}) {
                mustRefuse(c, "22023", "unknown document type", "select pharmacy.next_document_no(?)", bad);
            }
            mustRefuse(c, "22023", "unknown document type", "select pharmacy.next_document_no(null)");
            number(c, "select pharmacy.next_document_no('BILL')");
            number(c, "select pharmacy.next_document_no('GRN')");
            assertThat(number(c, "select fiscal_year from pharmacy.document_counter where doc_type = 'BILL'")).as("the bill restarts each year").isGreaterThan(2000);
            assertThat(number(c, "select fiscal_year from pharmacy.document_counter where doc_type = 'GRN'")).as("a GRN does not").isZero();
            // a series saved to restart each year makes the counter carry the financial year
            DbAccess.execute(c, "insert into pharmacy.document_series (unit_id, doc_type, prefix, financial_year_part, sequence_length, restarts_each_fy, created_by) "
                    + "values (?::uuid, 'PURCHASE_ORDER', 'PO/', true, 4, true, ?::uuid)", w.unit(), w.actor());
            number(c, "select pharmacy.next_document_no('PURCHASE_ORDER')");
            assertThat(number(c, "select fiscal_year from pharmacy.document_counter where doc_type = 'PURCHASE_ORDER'")).isGreaterThan(2000);
        });
        db().asApp(c -> {
            mustRefuse(c, "42501", "no tenant bound", "select pharmacy.next_document_no('GRN')");
            return null;
        });
        Clinic noUnit = newClinic();
        db().inTenant(noUnit.tenantId(), c -> {
            mustRefuse(c, "P0002", "pharmacy unit is not set up", "select pharmacy.next_document_no('GRN')");
            return null;
        });
    }

    @Test
    void numbersOfOneClinicNeverShowInAnotherClinic() throws Exception {
        Db a = dbWorld();
        Db b = dbWorld();
        asTenant(a, c -> assertThat(number(c, "select pharmacy.next_document_no('STOCK_ADJUSTMENT')")).isOne());
        asTenant(a, c -> assertThat(number(c, "select pharmacy.next_document_no('STOCK_ADJUSTMENT')")).as("rolled back: still 1").isOne());
        asTenant(b, c -> assertThat(number(c, "select count(*) from pharmacy.document_counter")).isZero());
    }

    // ---------------- L11: the unit lock ----------------

    private Connection session(Db w) throws SQLException {
        Connection c = db().app();
        c.setAutoCommit(false);
        bind(c, w);
        return c;
    }

    /** The binding is transactional: after a rollback the tenant has to be bound again. */
    private static void bind(Connection c, Db w) throws SQLException {
        try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            bind.setString(1, w.tenant().toString());
            bind.execute();
        }
    }

    @Test
    void whileOneSessionHoldsAnIssuedNumberOpenALockOfTheUnitForUpdateWaitsAndTheReverseToo() throws Exception {
        Db w = dbWorld();
        try (Connection a = session(w); Connection b = session(w)) {
            assertThat(number(a, "select pharmacy.next_document_no('GRN')")).isOne(); // A holds the unit FOR SHARE
            DbAccess.execute(b, "set local lock_timeout = '700ms'");
            SQLException waited = refused(b, "select id from pharmacy.unit where id = ?::uuid for update", w.unit());
            thatRefusal(waited).as("the series save waits for the issue").isNotNull();
            assertThat(waited.getSQLState()).isEqualTo("55P03");
            b.rollback();
            a.rollback();
            bind(a, w);
            bind(b, w);
            // the reverse: B holds the unit FOR UPDATE, so A's issue waits
            DbAccess.execute(b, "select id from pharmacy.unit where id = ?::uuid for update", w.unit());
            DbAccess.execute(a, "set local lock_timeout = '700ms'");
            SQLException issueWaited = refused(a, "select pharmacy.next_document_no('GRN')");
            thatRefusal(issueWaited).as("the issue waits for the series save").isNotNull();
            assertThat(issueWaited.getSQLState()).isEqualTo("55P03");
            b.rollback();
            a.rollback();
            bind(a, w);
            bind(b, w);
            // two issues at once do not block each other (both FOR SHARE)
            assertThat(number(a, "select pharmacy.next_document_no('INDENT')")).isOne();
            DbAccess.execute(b, "set local lock_timeout = '700ms'");
            thatRefusal(refused(b, "select 1 from pharmacy.unit where id = ?::uuid for share", w.unit())).isNull();
            a.rollback();
            b.rollback();
        }
    }

    @Test
    void aSeriesSaveWhileAnIssueIsHeldOpenWaitsAndThenAnswers409SeriesLockedOnceTheNumberIsCommitted() throws Exception {
        Masters m = mastersClinic();
        Db w = new Db(m.clinic(), values(m.clinic(), "select id::text from pharmacy.unit").get(0), m.centralId(), m.pharmacyId(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = session(w)) {
            assertThat(number(a, "select pharmacy.next_document_no('GRN')")).isOne();
            Future<Integer> save = pool.submit(() -> call(HttpMethod.PUT, SERIES_URL + "/GRN", m.clinic().owner(), js(seriesBody(0, "GRX-", false, 4, false)))
                    .andReturn().getResponse().getStatus());
            try {
                save.get(1500, TimeUnit.MILLISECONDS);
                throw new AssertionError("the series save should wait for the open issue");
            } catch (java.util.concurrent.TimeoutException expected) {
                // waiting, as designed
            }
            a.commit();
            assertThat(save.get(20, TimeUnit.SECONDS)).as("series-locked").isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok(HttpMethod.GET, SERIES_URL, m.clinic().owner(), null, 200).get("items").toString()).contains("\"numbersIssued\":true");
    }

    // ---------------- I11a and I11b ----------------

    private String level(Connection c, Db w, String requestId, int no, boolean applies, String raiser) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.approval_request_level (id, unit_id, request_id, level_no, approver_kind, condition_kind, applies, rule_text, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?, 'OWNER', 'ALWAYS', ?, 'Owner always', ?::uuid)", id, w.unit(), requestId, no, applies, raiser);
        return id;
    }

    private static final String APPROVE = "update pharmacy.approval_request set status = 'APPROVED', current_level = null where id = ?::uuid";
    private static final String NEEDS_DECISION = "approval request needs an approved decision for each level that applies";

    @Test
    void aRequestBecomesApprovedOnlyWithAnApprovedDecisionForEachLevelThatAppliesAndOneWithoutLevelRowsIsLeftAlone() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String raiser = newId();
            String d1 = newId();
            String d2 = newId();
            // no level rows: permissive
            String none = approvalRequest(c, w, newId(), raiser);
            mustAccept(c, APPROVE, none);
            // a level that applies and no decision
            String waiting = approvalRequest(c, w, newId(), raiser);
            level(c, w, waiting, 1, true, raiser);
            mustRefuse(c, "23514", NEEDS_DECISION, APPROVE, waiting);
            // a rejected decision is not an approval
            String rejected = approvalRequest(c, w, newId(), raiser);
            level(c, w, rejected, 1, true, raiser);
            decision(c, w, rejected, 1, "REJECTED", d1, raiser);
            mustRefuse(c, "23514", NEEDS_DECISION, APPROVE, rejected);
            // a level that does not apply needs nothing
            String skipped = approvalRequest(c, w, newId(), raiser);
            level(c, w, skipped, 1, false, raiser);
            mustAccept(c, APPROVE, skipped);
            // two levels that apply: only the first decided, then both
            String two = approvalRequest(c, w, newId(), raiser);
            level(c, w, two, 1, true, raiser);
            level(c, w, two, 2, true, raiser);
            decision(c, w, two, 1, "APPROVED", d1, raiser);
            mustRefuse(c, "23514", NEEDS_DECISION, APPROVE, two);
            decision(c, w, two, 2, "APPROVED", d2, raiser);
            mustAccept(c, APPROVE, two);
            // withdrawing or rejecting is not touched by the new trigger
            String withdrawn = approvalRequest(c, w, newId(), raiser);
            level(c, w, withdrawn, 1, true, raiser);
            mustAccept(c, "update pharmacy.approval_request set status = 'WITHDRAWN', current_level = null where id = ?::uuid", withdrawn);
        });
    }

    @Test
    void theRuleSetPointerMovesOnlyToANewerVersionOfTheUnit() throws Exception {
        Masters m = mastersClinic();
        ok(HttpMethod.PUT, RULES_URL, m.clinic().owner(), js(rulesBody(0, null, java.util.Map.of())), 200);
        ok(HttpMethod.PUT, RULES_URL, m.clinic().owner(), js(rulesBody(1, 1_000_000, java.util.Map.of())), 200);
        db().inTenant(m.clinic().tenantId(), c -> {
            String v1 = str(c, "select id::text from pharmacy.approval_rule_version where version_no = 1");
            String v2 = str(c, "select id::text from pharmacy.approval_rule_version where version_no = 2");
            assertThat(str(c, "select current_version_id::text from pharmacy.approval_rule_set")).isEqualTo(v2);
            mustRefuse(c, "23514", "approval rule set can only move to a newer version", "update pharmacy.approval_rule_set set current_version_id = ?::uuid", v1);
            mustRefuse(c, "23514", "approval rule set can only move to a newer version", "update pharmacy.approval_rule_set set current_version_id = ?::uuid", newId());
            // the same pointer written again does not fire it
            mustAccept(c, "update pharmacy.approval_rule_set set current_version_id = ?::uuid", v2);
            // a newer version (made here) is accepted
            DbAccess.execute(c, "insert into pharmacy.approval_rule_version (unit_id, version_no, preset, created_by) select unit_id, 3, 'CUSTOM', created_by "
                    + "from pharmacy.approval_rule_set");
            mustAccept(c, "update pharmacy.approval_rule_set set current_version_id = (select id from pharmacy.approval_rule_version where version_no = 3)");
            return null;
        });
    }

    // ---------------- m-3, m-4, m-5, m-6 ----------------

    @Test
    void thePayableCheckAllowsOpenOnlyWhileCreditIsBelowTheAmountOrTheInvoiceIsZero() throws Exception {
        asOwner(c -> {
            String def = str(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_vendor_payable_settled' "
                    + "and conrelid = 'pharmacy.vendor_payable'::regclass");
            assertThat(def).contains("SETTLED_BY_CREDIT").contains("credited_paise = amount_paise").contains("OPEN").contains("credited_paise < amount_paise")
                    .contains("amount_paise = 0");
            assertThat(number(c, "select count(*) from pg_constraint where conname = 'ck_vendor_payable_settled' and conrelid = 'pharmacy.vendor_payable'::regclass "
                    + "and convalidated")).as("validated on a database with no fully credited OPEN row").isOne();
        });
    }

    private String indentAt(Connection c, Db w, String status, int seq) throws SQLException {
        String id = newId();
        DbAccess.execute(c, "insert into pharmacy.indent (id, unit_id, from_store_id, to_store_id, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                id, w.unit(), w.pharmacy(), w.central(), w.actor());
        DbAccess.execute(c, "update pharmacy.indent set status = 'APPROVED', doc_seq = ?, doc_no = ?, raised_at = now() where id = ?::uuid", seq, "IND-" + seq, id);
        List<String> path = switch (status) {
            case "APPROVED" -> List.of();
            case "PARTLY_DISPATCHED" -> List.of("PARTLY_DISPATCHED");
            case "DISPATCHED" -> List.of("DISPATCHED");
            case "RECEIVED" -> List.of("DISPATCHED", "RECEIVED");
            default -> throw new IllegalArgumentException(status);
        };
        for (String step : path) {
            DbAccess.execute(c, "update pharmacy.indent set status = ? where id = ?::uuid", step, id);
        }
        return id;
    }

    private static final String MOVE = "update pharmacy.indent set status = ? where id = ?::uuid";

    @Test
    void anIndentMovesForwardOnlyAfterApprovalAndTheBackMovesAreRefused() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            int seq = 0;
            // allowed moves
            String[][] ok = {{"APPROVED", "PARTLY_DISPATCHED"}, {"APPROVED", "DISPATCHED"}, {"APPROVED", "CANCELLED"}, {"PARTLY_DISPATCHED", "DISPATCHED"},
                {"PARTLY_DISPATCHED", "RECEIVED"}, {"PARTLY_DISPATCHED", "CLOSED"}, {"DISPATCHED", "RECEIVED"}, {"DISPATCHED", "CLOSED"}, {"RECEIVED", "CLOSED"}};
            for (String[] m : ok) {
                String id = indentAt(c, w, m[0], ++seq);
                mustAccept(c, MOVE, m[1], id);
            }
            // refused moves: RECEIVED back to APPROVED, and PARTLY_DISPATCHED to CANCELLED are the two named by the review
            String[][] no = {{"RECEIVED", "APPROVED"}, {"RECEIVED", "PARTLY_DISPATCHED"}, {"RECEIVED", "DISPATCHED"}, {"PARTLY_DISPATCHED", "CANCELLED"},
                {"PARTLY_DISPATCHED", "APPROVED"}, {"DISPATCHED", "APPROVED"}, {"DISPATCHED", "PARTLY_DISPATCHED"}, {"DISPATCHED", "CANCELLED"},
                {"APPROVED", "RECEIVED"}, {"APPROVED", "CLOSED"}};
            for (String[] m : no) {
                String id = indentAt(c, w, m[0], ++seq);
                mustRefuse(c, "23514", "indent cannot go from " + m[0] + " to " + m[1], MOVE, m[1], id);
            }
        });
    }

    @Test
    void aStoreCannotGoInactiveWhileAnyBalanceRowHoldsStockInAnyStateAndCanOnceAllAreZero() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "M5-B1", "2031-03-31");
            String off = "update pharmacy.store set status = 'INACTIVE', updated_at = now(), updated_by = ?::uuid, version = version + 1 where id = ?::uuid";
            String[] states = {"AVAILABLE", "IN_TRANSIT", "PENDING_RETURN", "QUARANTINE"};
            for (String state : states) {
                String row = balance(c, w, w.pharmacy(), batch, state, 1);
                mustRefuse(c, "23514", "store still holds stock", off, w.actor(), w.pharmacy());
                DbAccess.execute(c, "update pharmacy.stock_balance set qty = 0, version = version + 1 where id = ?::uuid", row);
            }
            // all zero now: accepted
            mustAccept(c, off, w.actor(), w.pharmacy());
            assertThat(str(c, "select status from pharmacy.store where id = ?::uuid", w.pharmacy())).isEqualTo("INACTIVE");
        });
    }

    @Test
    void theLedgerHasTheStoreTimeIndexOnTheParentAndOnEveryPartition() throws Exception {
        asOwner(c -> {
            assertThat(number(c, "select count(*) from pg_indexes where schemaname = 'pharmacy' and tablename = 'stock_ledger' and indexname = 'ix_stock_ledger_store_time'")).isOne();
            assertThat(str(c, "select indexdef from pg_indexes where schemaname = 'pharmacy' and indexname = 'ix_stock_ledger_store_time'"))
                    .contains("tenant_id").contains("store_id").contains("occurred_at");
            long partitions = number(c, "select count(*) from pg_inherits where inhparent = 'pharmacy.stock_ledger'::regclass");
            assertThat(partitions).isGreaterThanOrEqualTo(6);
            assertThat(number(c, "select count(*) from pg_inherits where inhparent = 'pharmacy.ix_stock_ledger_store_time'::regclass")).as("one index per partition").isEqualTo(partitions);
        });
    }

    // ---------------- L13 ----------------

    @Test
    void theDatabaseRefusesTheSamePersonAsSignerOrAsRaiserAndDeciderOnEveryTableThatHasSuchAColumn() throws Exception {
        asOwner(c -> {
            // every signer-differs and not-self constraint of the SAH-79 tables exists and is validated
            List<String> expected = List.of("ck_stock_adjustment_signer_differs", "ck_stock_ledger_signer_differs", "ck_stock_audit_signer_differs",
                    "ck_debit_note_signer_differs", "ck_return_item_signer_differs", "ck_shortage_signer_differs", "ck_store_return_signer_differs",
                    "ck_damage_report_signer_differs", "ck_approval_decision_not_self");
            for (String name : expected) {
                assertThat(number(c, "select count(*) from pg_constraint where conname = ? and convalidated and connamespace = 'pharmacy'::regnamespace", name))
                        .as(name + " (the ledger one is also on every partition)").isGreaterThanOrEqualTo(1);
            }
            assertThat(number(c, "select count(*) from pg_constraint where conname = 'ck_approval_request_raiser' and convalidated")).isOne();
        });
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "L13-B1", "2031-03-31");
            String raiser = newId();
            String request = approvalRequest(c, w, newId(), raiser);
            // the decider is the raiser
            mustRefuse(c, "23514", "ck_approval_decision_not_self", "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as) "
                    + "values (?::uuid, ?::uuid, 1, 'APPROVED', ?::uuid, ?::uuid, 'OWNER')", w.unit(), request, raiser, raiser);
            mustAccept(c, "insert into pharmacy.approval_decision (unit_id, request_id, level_no, decision, decided_by, raised_by, acting_as) "
                    + "values (?::uuid, ?::uuid, 1, 'APPROVED', ?::uuid, ?::uuid, 'OWNER')", w.unit(), request, newId(), raiser);
            // a request whose created_by is not its raiser
            mustRefuse(c, "23514", "ck_approval_request_raiser", "insert into pharmacy.approval_request (unit_id, transaction_type, subject_type, subject_id, rule_store_id, raised_by, "
                    + "rule_version_id, amount_paise, current_level, status, created_by) select unit_id, 'STOCK_ADJUSTMENT', 'STOCK_ADJUSTMENT', ?::uuid, ?::uuid, ?::uuid, "
                    + "rule_version_id, 1, 1, 'PENDING', ?::uuid from pharmacy.approval_request where id = ?::uuid", newId(), w.central(), raiser, newId(), request);
            // the adjustment's signer is its raiser
            String adj = newId();
            mustRefuse(c, "23514", "ck_stock_adjustment_signer_differs", "insert into pharmacy.stock_adjustment (id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, "
                    + "book_qty, physical_qty, value_paise, reason_key, status, approval_request_id, created_by, second_signer_membership_id) values (?::uuid, ?::uuid, 1, 'ADJ-0001', "
                    + "?::uuid, ?::uuid, ?::uuid, 10, 7, 1000, 'stock-adjustment.breakage', 'WAITING_APPROVAL', ?::uuid, ?::uuid, ?::uuid)",
                    adj, w.unit(), w.central(), w.product(), batch, request, raiser, raiser);
        });
    }

    @Test
    void everyCreatedByActorAndSignerColumnOfThePharmacySchemaHoldsAMembershipIdAfterARealFlow() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        noApprovalRules(w);
        ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 90, REASON_ADJ)), 201);
        String[][] pairs = {{"batch", "created_by"}, {"stock_balance", "created_by"}, {"stock_ledger", "actor_membership_id"},
            {"stock_ledger", "approved_by_membership_id"}, {"stock_ledger", "second_signer_membership_id"}, {"client_request", "membership_id"},
            {"document_event", "actor_membership_id"}, {"approval_request", "raised_by"}, {"approval_request", "created_by"},
            {"approval_request_level", "created_by"}, {"approval_decision", "decided_by"}, {"approval_decision", "raised_by"},
            {"stock_adjustment", "created_by"}, {"stock_adjustment", "second_signer_membership_id"}, {"purchase_order", "created_by"},
            {"opening_stock_import", "posted_by"}};
        db().inTenant(w.clinic().tenantId(), c -> {
            long checked = 0;
            for (String[] p : pairs) {
                if (number(c, "select count(*) from information_schema.columns where table_schema = 'pharmacy' and table_name = ? and column_name = ?", p[0], p[1]) == 0) {
                    continue;
                }
                long bad = number(c, "select count(*) from pharmacy." + p[0] + " where " + p[1] + " is not null and " + p[1] + " not in (select id from platform.membership)");
                assertThat(bad).as(p[0] + "." + p[1] + " holds only membership ids (never a user id)").isZero();
                checked++;
            }
            assertThat(checked).as("columns checked").isGreaterThanOrEqualTo(12);
            assertThat(number(c, "select count(*) from pharmacy.stock_ledger where actor_membership_id = ?::uuid", w.keeper().membershipId().toString()))
                    .as("the ledger names the caller's membership").isGreaterThanOrEqualTo(4);
            assertThat(number(c, "select count(*) from pharmacy.stock_adjustment where created_by = ?::uuid", w.keeper().membershipId().toString())).isOne();
            return null;
        });
    }
}
