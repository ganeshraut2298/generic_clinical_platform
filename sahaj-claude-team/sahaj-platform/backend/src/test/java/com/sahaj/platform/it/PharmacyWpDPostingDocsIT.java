package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79 WP-D migrations 1350 (the one allowed posting update of an approved opening-stock import), 1380 (five more document
 * types, eleven reason lists, 36 seeded reasons, the fixed reason, {@code next_document_no} for the new types) and the migration
 * list itself (1000 to 1390 apply in order, none failed, the checksums of the applied files are the files'). Test plan:
 * docs/test-plans/SAH-79.md.
 */
@OnTestDatabase
class PharmacyWpDPostingDocsIT extends Sah79Support {

    private static final String[] NEW_TYPES = {"DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT"};

    // ---------------- opening stock posting ----------------

    private record Import(Clinic clinic, String storeId, String importId, String keeper, String approver) {
    }

    /** A clean file at the Central Store: returns the import in the given stage (DRAFT, WAITING_APPROVAL, APPROVED). */
    private Import importAt(String stage) throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(stores[0]), keeper, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        JsonNode own = createOwn(clinic, ownBody("Test dressing kit (OT)"));
        JsonNode draft = draftOfCleanFile(keeper.auth(), "CS", idOf(stores[0]), "G-000101", own.get("code").asText());
        String id = idOf(draft);
        if (!stage.equals("DRAFT")) {
            submit(keeper.auth(), idOf(stores[0]), id);
        }
        if (stage.equals("APPROVED")) {
            ok(HttpMethod.POST, importUrl(idOf(stores[0]), id) + "/approve", second.auth(), null, 200);
        }
        return new Import(clinic, idOf(stores[0]), id, keeper.membershipId().toString(), second.membershipId().toString());
    }

    private void probe(Import i, Work work) throws SQLExceptionWrapper {
        try {
            db().inTenant(i.clinic().tenantId(), c -> {
                work.run(c);
                return null;
            });
        } catch (java.sql.SQLException e) {
            throw new SQLExceptionWrapper(e);
        }
    }

    private static final class SQLExceptionWrapper extends RuntimeException {
        SQLExceptionWrapper(java.sql.SQLException e) {
            super(e);
        }
    }

    @Test
    void anApprovedImportAcceptsExactlyOnePostingUpdateThatSetsPostedAtAndPostedByAndNothingElse() throws Exception {
        Import i = importAt("APPROVED");
        String poster = UUID.randomUUID().toString();
        probe(i, c -> {
            assertThat(str(c, "select status from pharmacy.opening_stock_import where id = ?::uuid", i.importId())).isEqualTo("APPROVED");
            assertThat(number(c, "select count(*) from pharmacy.opening_stock_import where id = ?::uuid and posted_at is null and posted_by is null", i.importId())).isOne();
            // not final in the old sense: the posting is the only update allowed
            mustRefuse(c, "23514", "opening stock import is final (APPROVED)", "update pharmacy.opening_stock_import set status = 'REJECTED' where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED)", "update pharmacy.opening_stock_import set total_quantity = 1 where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED)", "update pharmacy.opening_stock_import set version = version + 1 where id = ?::uuid", i.importId());
            // posted_at without posted_by, and posted_by without posted_at
            mustRefuse(c, "23514", null, "update pharmacy.opening_stock_import set posted_at = now() where id = ?::uuid", i.importId());
            mustRefuse(c, "23514", null, "update pharmacy.opening_stock_import set posted_by = ?::uuid where id = ?::uuid", poster, i.importId());
            // the posting together with another column
            for (String other : new String[] {"total_quantity = 1", "status = 'REJECTED'", "row_count = 9", "line_count = 99", "decided_by = '" + UUID.randomUUID() + "'::uuid",
                "total_value_paise = 5"}) {
                mustRefuse(c, "23514", "can change only posted_at and posted_by", "update pharmacy.opening_stock_import set posted_at = now(), posted_by = ?::uuid, " + other
                        + " where id = ?::uuid", poster, i.importId());
            }
            // the one allowed update (with the usual updated_* and version)
            mustAccept(c, "update pharmacy.opening_stock_import set posted_at = now(), posted_by = ?::uuid, updated_at = now(), updated_by = ?::uuid, version = version + 1 "
                    + "where id = ?::uuid", poster, poster, i.importId());
            assertThat(number(c, "select count(*) from pharmacy.opening_stock_import where id = ?::uuid and posted_at is not null and posted_by = ?::uuid", i.importId(), poster)).isOne();
            // a posted import is final
            mustRefuse(c, "23514", "opening stock import is final (APPROVED, posted)", "update pharmacy.opening_stock_import set posted_at = now(), posted_by = ?::uuid where id = ?::uuid",
                    poster, i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED, posted)", "update pharmacy.opening_stock_import set updated_by = ?::uuid where id = ?::uuid", poster, i.importId());
            mustRefuse(c, "23514", "opening stock import is final (APPROVED, posted)", "update pharmacy.opening_stock_import set posted_at = null, posted_by = null where id = ?::uuid", i.importId());
        });
        // rolled back: nothing was posted
        assertThat(count(i.clinic(), "select count(*) from pharmacy.opening_stock_import where posted_at is not null")).isZero();
    }

    @Test
    void aDraftOrWaitingImportCannotCarryAPostingAndAnApprovalCannotSetItInTheSameUpdate() throws Exception {
        Import draft = importAt("DRAFT");
        probe(draft, c -> {
            mustRefuse(c, "23514", "opening stock import can be posted only after approval", "update pharmacy.opening_stock_import set posted_at = now(), posted_by = ?::uuid "
                    + "where id = ?::uuid", UUID.randomUUID().toString(), draft.importId());
        });
        Import waiting = importAt("WAITING_APPROVAL");
        probe(waiting, c -> {
            mustRefuse(c, "23514", "opening stock import can be posted only after approval", "update pharmacy.opening_stock_import set posted_at = now(), posted_by = ?::uuid "
                    + "where id = ?::uuid", UUID.randomUUID().toString(), waiting.importId());
            mustRefuse(c, "23514", "opening stock import can be posted only after approval", "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, "
                    + "decided_at = now(), posted_at = now(), posted_by = ?::uuid where id = ?::uuid", waiting.approver(), waiting.approver(), waiting.importId());
        });
    }

    @Test
    void theOldMessagesOfTheOtherStatesAreUnchangedByTheNewFunction() throws Exception {
        Import waiting = importAt("WAITING_APPROVAL");
        probe(waiting, c -> {
            mustRefuse(c, "23514", "can only be approved, rejected or replaced", "update pharmacy.opening_stock_import set status = 'DRAFT' where id = ?::uuid", waiting.importId());
            mustRefuse(c, "23514", "identity cannot change", "update pharmacy.opening_stock_import set row_count = 99 where id = ?::uuid", waiting.importId());
            mustRefuse(c, "23514", "totals are fixed once submitted", "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), "
                    + "total_value_paise = 1 where id = ?::uuid", waiting.approver(), waiting.importId());
            mustAccept(c, "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, decided_at = now() where id = ?::uuid", waiting.approver(), waiting.importId());
        });
        Import approved = importAt("APPROVED");
        // the application still answers 409 for a second approval of the same file and the status stays approved
        assertThat(importStatuses(approved.clinic(), approved.storeId())).contains("APPROVED");
    }

    @Test
    void theColumnsAndTheCheckOfThePostingExistWithNoDataInEveryRow() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = 'pharmacy' and table_name = 'opening_stock_import' "
                    + "and column_name in ('posted_at', 'posted_by') and is_nullable = 'YES'")).isEqualTo(2);
            assertThat(DbAccess.count(c, "select count(*) from pg_constraint where conname = 'ck_opening_stock_import_posted' and conrelid = 'pharmacy.opening_stock_import'::regclass"))
                    .isOne();
            return null;
        });
    }

    // ---------------- document types and numbers ----------------

    @Test
    void theDocumentSeriesAndCounterAcceptTheTenTypesAndRefuseAnyOther() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            int seq = 0;
            for (String type : new String[] {"PURCHASE_ORDER", "GRN", "INDENT", "TRANSFER", "BILL", "DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT"}) {
                seq++;
                mustAccept(c, "insert into pharmacy.document_series (unit_id, doc_type, prefix, financial_year_part, sequence_length, restarts_each_fy, created_by) "
                        + "values (?::uuid, ?, ?, false, 4, false, ?::uuid)", w.unit(), type, "S" + seq, w.actor());
                mustAccept(c, "insert into pharmacy.document_counter (unit_id, doc_type, fiscal_year, next_value) values (?::uuid, ?, 0, 2)", w.unit(), type);
            }
            mustRefuse(c, "23514", "ck_document_series_doc_type", "insert into pharmacy.document_series (unit_id, doc_type, prefix, financial_year_part, sequence_length, "
                    + "restarts_each_fy, created_by) values (?::uuid, 'FOO', 'FOO', false, 4, false, ?::uuid)", w.unit(), w.actor());
            mustRefuse(c, "23514", "ck_document_counter_doc_type", "insert into pharmacy.document_counter (unit_id, doc_type, fiscal_year, next_value) values (?::uuid, 'FOO', 0, 2)", w.unit());
            mustRefuse(c, "23514", "ck_document_series_doc_type", "insert into pharmacy.document_series (unit_id, doc_type, prefix, financial_year_part, sequence_length, "
                    + "restarts_each_fy, created_by) values (?::uuid, 'CREDIT_NOTE', 'CN/', true, 6, true, ?::uuid)", w.unit(), w.actor());
        });
    }

    @Test
    void nextDocumentNoReturnsOneTwoThreeForEachNewTypeAndForTheOldOnesAndRaises22023ForAnUnknownType() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            for (String type : new String[] {"PURCHASE_ORDER", "GRN", "INDENT", "TRANSFER", "BILL", "DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT"}) {
                assertThat(number(c, "select pharmacy.next_document_no(?)", type)).as(type + " first").isEqualTo(1);
                assertThat(number(c, "select pharmacy.next_document_no(?)", type)).as(type + " second").isEqualTo(2);
                assertThat(number(c, "select pharmacy.next_document_no(?)", type)).as(type + " third").isEqualTo(3);
            }
            for (String type : new String[] {"FOO", "credit_note", "", "CREDIT_NOTE"}) {
                mustRefuse(c, "22023", "unknown document type", "select pharmacy.next_document_no(?)", type);
            }
            mustRefuse(c, "22023", "unknown document type", "select pharmacy.next_document_no(null)");
            // the new types never restart: fiscal year 0; only the bill restarts by default
            for (String type : NEW_TYPES) {
                assertThat(number(c, "select fiscal_year from pharmacy.document_counter where doc_type = ?", type)).as(type).isZero();
            }
            assertThat(number(c, "select fiscal_year from pharmacy.document_counter where doc_type = 'BILL'")).isGreaterThan(2000);
            assertThat(number(c, "select next_value from pharmacy.document_counter where doc_type = 'DEBIT_NOTE'")).isEqualTo(4);
        });
    }

    @Test
    void nextDocumentNoWithoutATenantOrWithoutAUnitIsRefusedAndOneClinicsNumbersAreNeverAnotherClinics() throws Exception {
        Db a = dbWorld();
        Db b = dbWorld();
        asTenant(a, c -> {
            assertThat(number(c, "select pharmacy.next_document_no('DEBIT_NOTE')")).isOne();
            assertThat(number(c, "select pharmacy.next_document_no('DEBIT_NOTE')")).isEqualTo(2);
        });
        asTenant(b, c -> assertThat(number(c, "select pharmacy.next_document_no('DEBIT_NOTE')")).as("the other clinic starts at 1").isOne());
        db().asApp(c -> {
            thatRefusal(refused(c, "select pharmacy.next_document_no('GRN')")).isNotNull();
            return null;
        });
        // the application cannot call the function as the migrator without a tenant either: no tenant bound
        asOwner(c -> mustRefuse(c, "42501", "no tenant bound", "select pharmacy.next_document_no('GRN')"));
    }

    // ---------------- reasons ----------------

    @Test
    void thereAreThirtySixSeededReasonsInElevenListsAndEveryKeyHasTheDocumentedShape() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.reason_default")).isEqualTo(36);
            assertThat(DbAccess.count(c, "select count(distinct list_key) from pharmacy.reason_default")).isEqualTo(11);
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.reason_default where reason_key !~ '^[a-z0-9][a-z0-9.-]{0,63}$'")).isZero();
            long[][] expected = {};
            assertThat(expected).isEmpty();
            String[][] perList = {{"store-return", "3"}, {"sales-return", "4"}, {"damage", "3"}, {"stock-adjustment", "5"}, {"receipt-reject", "5"}, {"cs-change", "4"},
                {"approval-reject", "4"}, {"transfer", "3"}, {"consumption-purpose", "3"}, {"dispute", "1"}, {"vendor-return", "1"}};
            for (String[] e : perList) {
                assertThat(DbAccess.count(c, "select count(*) from pharmacy.reason_default where list_key = ?", e[0])).as(e[0]).isEqualTo(Long.parseLong(e[1]));
            }
            // the keys of the lists start with the list key
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.reason_default where reason_key not like list_key || '.%'")).isZero();
            assertThat(DbAccess.text(c, "select label from pharmacy.reason_default where reason_key = 'receipt-reject.short'")).isEqualTo("Short / not received");
            assertThat(DbAccess.text(c, "select label from pharmacy.reason_default where reason_key = 'stock-adjustment.count-mismatch'")).isEqualTo("Count mismatch");
            assertThat(DbAccess.text(c, "select label from pharmacy.reason_default where reason_key = 'stock-adjustment.theft-or-loss'")).isEqualTo("Theft or loss");
            return null;
        });
    }

    @Test
    void theFixedReasonCannotHaveAnInactiveStateRowAndEveryOtherReasonCan() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String sql = "insert into pharmacy.reason_default_state (unit_id, reason_key, active, created_by) values (?::uuid, ?, ?, ?::uuid)";
            mustRefuse(c, "23514", "ck_reason_default_state_fixed", sql, w.unit(), "receipt-reject.short", false, w.actor());
            mustAccept(c, sql, w.unit(), "receipt-reject.short", true, w.actor());
            mustRefuse(c, "23514", "ck_reason_default_state_fixed", "update pharmacy.reason_default_state set active = false where reason_key = 'receipt-reject.short'");
            mustAccept(c, sql, w.unit(), "receipt-reject.damaged-in-transit", false, w.actor());
            mustAccept(c, sql, w.unit(), "cs-change.other", false, w.actor());
        });
    }

    @Test
    void aClinicsOwnReasonIsAcceptedInEachOfTheElevenListsAndAnUnknownListIsRefused() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            int sort = 0;
            for (String list : LIST_KEYS) {
                sort++;
                mustAccept(c, "insert into pharmacy.reason (unit_id, list_key, reason_key, label, sort, created_by) values (?::uuid, ?, ?, ?, ?, ?::uuid)",
                        w.unit(), list, "c-" + UUID.randomUUID(), "Own " + list, sort, w.actor());
            }
            mustRefuse(c, "23514", "ck_reason_list", "insert into pharmacy.reason (unit_id, list_key, reason_key, label, sort, created_by) values (?::uuid, 'bogus', ?, 'X', 1, ?::uuid)",
                    w.unit(), "c-" + UUID.randomUUID(), w.actor());
            // reason_default is global and select-only for the application: even a good row is refused (42501); the list check is proved through the clinic's own table above
            mustRefuse(c, "42501", "permission denied", "insert into pharmacy.reason_default (reason_key, list_key, label, sort) values ('x.y', 'bogus', 'X', 1)");
        });
    }

    // ---------------- the migration list ----------------

    @Test
    void migrations1000To1390ApplyInOrderNoneFailedAndTheChecksumsOfTheAppliedFilesAreTheFilesAsTheyAre() throws Exception {
        List<String> versions = new ArrayList<>();
        List<Boolean> success = new ArrayList<>();
        List<Integer> checksums = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        asOwner(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select version, success, checksum, script from platform.flyway_schema_history "
                    + "where version like '2026.12.17.%' order by installed_rank")) {
                while (rs.next()) {
                    versions.add(rs.getString(1));
                    success.add(rs.getBoolean(2));
                    checksums.add(rs.getInt(3));
                    scripts.add(rs.getString(4));
                }
            }
            assertThat(DbAccess.count(c, "select count(*) from platform.flyway_schema_history where success = false")).isZero();
        });
        assertThat(success).as("no failed migration").doesNotContain(false);
        String[] expected = {"1000", "1010", "1020", "1030", "1100", "1200", "1210", "1220", "1230", "1240", "1250", "1260", "1300", "1310", "1320", "1330", "1340", "1350",
            "1360", "1370", "1380", "1390"};
        List<String> numbers = new ArrayList<>();
        for (String v : versions) {
            numbers.add(v.substring("2026.12.17.".length()));
        }
        assertThat(numbers).as("all of 1000 to 1390 are applied, in version order (a hardening migration of 1270 to 1299 may sit between 1260 and 1300)")
                .containsSubsequence(expected);
        assertThat(numbers).as("applied in ascending version order").isSorted();
        int afterLast = numbers.indexOf("1260") + 1;
        assertThat(numbers.subList(afterLast, numbers.indexOf("1300"))).as("only migrations of the reserved range 1270 to 1299 lie between 1260 and 1300")
                .allMatch(n -> Integer.parseInt(n) >= 1270 && Integer.parseInt(n) <= 1299);
        assertThat(numbers.subList(numbers.indexOf("1300"), numbers.size())).as("1300 to 1390 follow in order and nothing else after them")
                .startsWith("1300", "1310", "1320", "1330", "1340", "1350", "1360", "1370", "1380", "1390");
        for (int k = 0; k < scripts.size(); k++) {
            String number = numbers.get(k);
            if (Integer.parseInt(number) < 1000) {
                continue;
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/migration/" + scripts.get(k))) {
                assertThat(in).as(scripts.get(k)).isNotNull();
                assertThat(flywayChecksum(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))).as("checksum of " + scripts.get(k)).isEqualTo(checksums.get(k));
            }
        }
    }

    /** Flyway's checksum of a SQL migration: CRC-32 over the lines (each trimmed of its line end, the byte order mark removed). */
    private static int flywayChecksum(String content) {
        CRC32 crc = new CRC32();
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(text));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                crc.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return (int) crc.getValue();
    }
}
