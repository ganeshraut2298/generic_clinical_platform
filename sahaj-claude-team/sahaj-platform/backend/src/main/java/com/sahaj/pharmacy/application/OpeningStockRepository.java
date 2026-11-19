package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.OpeningStockRules;
import com.sahaj.pharmacy.domain.RejectReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.opening_stock_import}, {@code pharmacy.opening_stock_line} and
 * {@code pharmacy.opening_stock_line_issue} (tenant tables, row level security, no delete). Who uploaded or decided an import
 * is a membership id (the contract names people by membership). The database protects the order of writes: lines and issues
 * only while the import is DRAFT, so a service writes lines, then issues, then the totals and the status of the import last.
 */
public interface OpeningStockRepository {

    /**
     * One import. While it is DRAFT the counts and totals are worked out from its lines at read time; once it has left DRAFT
     * they are the figures fixed at submit.
     */
    record ImportRow(UUID id, UUID storeId, ImportStatus status, int rowCount, UUID uploadedBy, UUID clientKey,
                     Instant createdAt, Instant submittedAt, UUID decidedBy, Instant decidedAt, RejectReason rejectReason,
                     String rejectNote, UUID replacedByImportId, int receivedRows, int lineCount, long totalQuantity,
                     long totalValuePaise, int errorCount, int warningCount, long version, Instant postedAt,
                     UUID postedBy) {

        /** An import that is not posted (the form of every row before SAH-79 WP-D). */
        public ImportRow(UUID id, UUID storeId, ImportStatus status, int rowCount, UUID uploadedBy, UUID clientKey,
                         Instant createdAt, Instant submittedAt, UUID decidedBy, Instant decidedAt, RejectReason rejectReason,
                         String rejectNote, UUID replacedByImportId, int receivedRows, int lineCount, long totalQuantity,
                         long totalValuePaise, int errorCount, int warningCount, long version) {
            this(id, storeId, status, rowCount, uploadedBy, clientKey, createdAt, submittedAt, decidedBy, decidedAt,
                    rejectReason, rejectNote, replacedByImportId, receivedRows, lineCount, totalQuantity, totalValuePaise,
                    errorCount, warningCount, version, null, null);
        }

        public boolean posted() {
            return postedAt != null;
        }
    }

    /** One line of an approved import as posting reads it: the parsed values, never the raw cells. */
    record PostingLine(UUID lineId, int rowNo, UUID productId, String productName, String batchNo, LocalDate expiryDate,
                       BigDecimal mrp, BigDecimal purchaseRate, int quantity) {
    }

    record Slice<T>(List<T> items, long total) {
    }

    /** What the server read from one row of the file. */
    record LineIn(int rowNo, OpeningStockRules.Raw raw, UUID productId, OpeningStockRules.Parsed parsed) {
    }

    record LineRow(UUID id, int rowNo, String storeCode, String productCode, UUID productId, String productName,
                   String batch, LocalDate expiryDate, BigDecimal mrp, BigDecimal purchaseRate, Integer quantity,
                   Long valuePaise) {
    }

    /** A line already stored for the import: its id, row number and the seven cells exactly as they were sent. */
    record StoredRow(UUID lineId, int rowNo, OpeningStockRules.Raw raw) {
    }

    record IssueRow(UUID lineId, int rowNo, String field, String code, String severity) {
    }

    record LineRef(UUID id, int rowNo) {
    }

    // ---- imports ----

    /**
     * Takes the clinic's unit row {@code FOR SHARE} until the transaction ends. Every row written here points at the unit
     * (a foreign key takes a key-share lock on it), and every master write takes the unit row {@code FOR UPDATE} first. So a
     * writer of an import takes the unit row first, before the store, import and product rows: one lock order for all, and
     * no deadlock with a store switch-off or a product edit (D-2). Nothing happens when the clinic has no unit row yet.
     */
    void lockUnitShared(UUID tenantId);

    /** One import of this store; another clinic's, another store's or an unknown id is absent. */
    Optional<ImportRow> find(UUID tenantId, UUID storeId, UUID importId);

    /** The same read with {@code for update}: two writers of one import run one after the other. */
    Optional<ImportRow> findForUpdate(UUID tenantId, UUID storeId, UUID importId);

    /** Imports started with this client key, by any uploader of the clinic. */
    List<ImportRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** The import of the store in this status (there is at most one WAITING_APPROVAL and one APPROVED per store). */
    Optional<ImportRow> findWithStatus(UUID tenantId, UUID storeId, ImportStatus status);

    /**
     * The person's own DRAFT imports of the store, locked {@code FOR UPDATE} (security I10): a line write that holds one of
     * them finishes before it is replaced. Called under the store row lock, before the new import is inserted.
     */
    List<ImportRow> draftsOf(UUID tenantId, UUID storeId, UUID uploadedBy);

    /**
     * How many imports this person started for the store in [{@code from}, {@code to}) whatever became of them (L8, the starts
     * limit). Called under the store row lock, so two starts cannot both count the same number.
     */
    int countStarted(UUID tenantId, UUID storeId, UUID uploadedBy, Instant from, Instant to);

    /** Newest first. */
    Slice<ImportRow> listForStore(UUID tenantId, UUID storeId, int limit, int offset);

    /** Newest first, every store of the clinic; {@code status} null for all. */
    Slice<ImportRow> listAll(UUID tenantId, ImportStatus status, int limit, int offset);

    UUID insert(UUID tenantId, UUID unitId, UUID storeId, int rowCount, UUID uploadedBy, UUID clientKey);

    /** DRAFT or WAITING_APPROVAL to REPLACED. False when the version is not current. */
    boolean markReplaced(UUID tenantId, UUID id, long version, UUID replacedByImportId, UUID actor);

    /** DRAFT to WAITING_APPROVAL ({@code submittedAt} set) or to NOT_ACCEPTED, with the figures fixed now. */
    boolean markSubmitted(UUID tenantId, UUID id, long version, ImportStatus status, int lineCount, long totalQuantity,
                          long totalValuePaise, int errorCount, int warningCount, Instant submittedAt, UUID actor);

    /** The lines of the import for posting, by row number: only rows with every parsed value (an APPROVED import has no other). */
    List<PostingLine> postingLines(UUID tenantId, UUID importId);

    /**
     * Sets {@code posted_at}, {@code posted_by} and adds 1 to the version, in one update that matches only an APPROVED, not yet
     * posted import ({@code where status = 'APPROVED' and posted_at is null}). False means 0 rows: already posted.
     */
    boolean markPosted(UUID tenantId, UUID id, UUID postedBy);

    boolean markApproved(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt);

    /** DRAFT or WAITING_APPROVAL to REJECTED (DEC-076: an owner may reject a draft). False when the version is not current. */
    boolean markRejected(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt, RejectReason reason,
                         String note);

    /** Sets the stock flag on every product that appears in the import (one way: it is never cleared). */
    void markProductsHasStock(UUID tenantId, UUID importId, UUID actor);

    // ---- lines and issues ----

    /** Inserts the row, or replaces the row of the same number (a safe retry). Returns the line id. */
    UUID upsertLine(UUID tenantId, UUID unitId, UUID importId, LineIn line, UUID actor);

    /**
     * DEC-077 I7 and L8(b): changed rows written to the import so far ({@code line_rows_written}); the cap is 2 x rowCount. Read
     * under the import row lock. Not part of {@link ImportRow}: the contract has no field for it. The old request counter
     * {@code line_request_count} stays in the table and is no longer read or written.
     */
    int lineRowsWritten(UUID tenantId, UUID importId);

    /** Adds {@code rows} (at least 1) to {@code line_rows_written} of a DRAFT import, in the transaction of the request. */
    void addLineRowsWritten(UUID tenantId, UUID importId, int rows, UUID actor);

    /**
     * Lines whose product is no longer ACTIVE (switched off after the row was written), by row number. Submit flags them
     * as ERROR {@code product-unknown} (DEC-077 L10).
     */
    List<LineRef> linesWithInactiveProduct(UUID tenantId, UUID importId);

    /** The stored lines of these row numbers of the import (security L8: a row sent again unchanged writes nothing). */
    List<StoredRow> storedRows(UUID tenantId, UUID importId, Collection<Integer> rowNos);

    /** Marks the current issues of these rows superseded (the only change an issue allows). */
    void supersedeIssues(UUID tenantId, UUID importId, Collection<Integer> rowNos);

    void insertIssue(UUID tenantId, UUID unitId, UUID lineId, String field, String code, String severity, UUID actor);

    /** Lines whose product and batch (ignoring case) appear on another line of the import. */
    List<LineRef> repeatedBatchLines(UUID tenantId, UUID importId);

    /** Lines that already carry a current issue with this code. */
    List<UUID> linesWithIssue(UUID tenantId, UUID importId, String code);

    /** By row number; {@code withIssues} keeps only the rows that carry a current error or warning. */
    Slice<LineRow> lines(UUID tenantId, UUID importId, boolean withIssues, int limit, int offset);

    /** Current (not superseded) issues of these lines. */
    List<IssueRow> issuesOfLines(UUID tenantId, Collection<UUID> lineIds);

    /** The first current ERROR issues of the import, by row number. */
    List<IssueRow> errorIssues(UUID tenantId, UUID importId, int limit);
}
