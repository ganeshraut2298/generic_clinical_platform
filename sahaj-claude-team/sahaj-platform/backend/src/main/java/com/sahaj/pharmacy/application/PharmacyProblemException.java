package com.sahaj.pharmacy.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Pharmacy and Inventory error whose problem carries more than a type and a detail (docs/api/pharmacy.yaml): a 409
 * {@code settings-changed} with {@code current}, a 409 {@code store-has-stock} with {@code lockedFields}. The pharmacy
 * error handler turns it into an RFC 9457 problem. The detail is fixed plain English written here; it never carries what
 * was typed. Plain 409s with only a type and a detail use {@code platform.api.ConflictException} instead.
 */
public class PharmacyProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String slug;
    private final String title;
    private final transient Map<String, Object> extensions = new LinkedHashMap<>();

    protected PharmacyProblemException(int status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    /** Extra problem members such as {@code current} or {@code lockedFields}. */
    public Map<String, Object> extensions() {
        return extensions;
    }

    public PharmacyProblemException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    /** 400 {@code bad-request}: a path id that is not an id. Never says what was sent. */
    public static PharmacyProblemException badRequest() {
        return new PharmacyProblemException(400, "bad-request", "Bad Request",
                "The request could not be understood. Check it and try again.");
    }

    /** 409 {@code settings-changed}: someone else saved first. {@code current} is the record as its GET returns it. */
    public static PharmacyProblemException settingsChanged(Object current) {
        return new PharmacyProblemException(409, "settings-changed", "Changed by someone else",
                "Someone else saved this a moment ago. Check the current values and save again.")
                .with("current", current);
    }

    /** 409 {@code rules-changed} (SAH-78-9): someone saved the approval rules first. {@code current} is the rules as GET returns them. */
    public static PharmacyProblemException rulesChanged(Object current) {
        return new PharmacyProblemException(409, "rules-changed", "Changed by someone else",
                "Someone else saved the approval rules a moment ago. Check the current rules and save again.")
                .with("current", current);
    }

    /** 409 {@code store-has-stock} on a change of type or code. */
    public static PharmacyProblemException storeHasStockLocked(java.util.List<String> lockedFields) {
        return new PharmacyProblemException(409, "store-has-stock", "This store has stock",
                "This store has stock. Type and code cannot change.").with("lockedFields", lockedFields);
    }

    public static final String OPEN_IMPORT = "Finish or reject the opening stock file of this store first.";

    /** 409 {@code store-has-stock} on a switch-off. */
    public static PharmacyProblemException storeHasStockSwitchOff() {
        return new PharmacyProblemException(409, "store-has-stock", "This store has stock",
                "This store has stock. Clear it in Inventory first.");
    }

    /**
     * 409 {@code open-import} (DEC-071 option A, contract 0.1.3 rule 14): the store, or a product on a line, is part of an
     * opening-stock file that is DRAFT or WAITING_APPROVAL. Nothing was written.
     */
    public static PharmacyProblemException openImport() {
        return new PharmacyProblemException(409, "open-import", "Opening stock file is open", OPEN_IMPORT);
    }

    /** 400 {@code global-fields-read-only}: a change to the global fields of an adopted product. */
    public static PharmacyProblemException globalFieldsReadOnly() {
        return new PharmacyProblemException(400, "global-fields-read-only", "Global fields are read-only",
                "Global fields cannot be changed. Use Local settings.");
    }

    /** 409 {@code possible-duplicate}: the same salt, strength and form exists; {@code candidates} are the matches. */
    public static PharmacyProblemException possibleDuplicate(Object candidates) {
        return new PharmacyProblemException(409, "possible-duplicate", "Possible duplicate",
                "A product with the same salt, strength and form exists.").with("candidates", candidates);
    }

    // ---- SAH-79 WP-D (docs/api/pharmacy.yaml 0.3.0) ----

    /** One failing line of a 409: a document line id or an opening-stock row number, the product, what is usable. */
    public record FailingLine(java.util.UUID lineId, Integer rowNo, java.util.UUID productId, Integer available) {
    }

    /** 409 {@code insufficient-stock}: nothing was written. {@code available} is the usable quantity of the first failing line. */
    public static PharmacyProblemException insufficientStock(int available, java.util.List<FailingLine> lines) {
        return new PharmacyProblemException(409, "insufficient-stock", "Not enough stock",
                "There is not enough stock for this. Nothing was changed.").with("available", available)
                .with("lines", lines);
    }

    /** 409 {@code batch-mrp-mismatch} (BR-10): the same batch with a different MRP; at most 100 lines. Nothing was written. */
    public static PharmacyProblemException batchMrpMismatch(java.util.List<FailingLine> lines) {
        return new PharmacyProblemException(409, "batch-mrp-mismatch", "Same batch with a different MRP",
                "Same batch with a different MRP. Check the batch number.").with("lines", lines);
    }

    /** 409 {@code batch-expired}: an expired batch is never received into or released to Available. */
    public static PharmacyProblemException batchExpired() {
        return new PharmacyProblemException(409, "batch-expired", "Batch has expired",
                "This batch has expired. It cannot be used.");
    }

    /** 409 {@code not-approved} (79-1): the import is not APPROVED. */
    public static PharmacyProblemException notApproved() {
        return new PharmacyProblemException(409, "not-approved", "Import is not approved", "This import is not approved.");
    }

    /** 409 {@code already-posted} (79-1): carries {@code postedAt}. Nothing was posted twice. */
    public static PharmacyProblemException alreadyPosted(java.time.Instant postedAt) {
        return new PharmacyProblemException(409, "already-posted", "Already posted", "This import was already posted.")
                .with("postedAt", postedAt);
    }

    /** 409 {@code changed}: the document or the quantity moved since the screen read it; {@code current} is what it is now. */
    public static PharmacyProblemException changed(Object current) {
        return new PharmacyProblemException(409, "changed", "Changed by someone else",
                "This changed a moment ago. Check the current values and try again.").with("current", current);
    }

    /** 409 {@code already-decided} (BR-15): the request was decided or is at another level; {@code current} is the request. */
    public static PharmacyProblemException alreadyDecided(Object current) {
        return new PharmacyProblemException(409, "already-decided", "Already decided",
                "This request was already decided.").with("current", current);
    }

    /** 409 {@code adjustment-waiting}: another adjustment of this batch in this store waits for approval. */
    public static PharmacyProblemException adjustmentWaiting() {
        return new PharmacyProblemException(409, "adjustment-waiting", "Adjustment is waiting",
                "An adjustment for this batch is already waiting.");
    }

    /** 409 {@code doc-state}: the document is not in a state that allows this; the sentence says what to do instead. */
    public static PharmacyProblemException docState(String sentence) {
        return new PharmacyProblemException(409, "doc-state", "Not allowed in this state", sentence);
    }

    /** 409 {@code vendor-blocked}: the vendor's drug licence has expired (SAH-79-6). */
    public static PharmacyProblemException vendorBlocked() {
        return new PharmacyProblemException(409, "vendor-blocked", "Vendor is blocked",
                "This vendor's drug licence has expired. It cannot get a new purchase order.");
    }

    /** 409 {@code duplicate-invoice}: the vendor already has a receipt with this invoice number (SAH-79-7). */
    public static PharmacyProblemException duplicateInvoice() {
        return new PharmacyProblemException(409, "duplicate-invoice", "Invoice already received",
                "That invoice was already received.");
    }

    /** 400 {@code invoice-total-mismatch} (OQ-79-9b): the typed invoice total is more than 100 paise from the lines. */
    public static PharmacyProblemException invoiceTotalMismatch() {
        return new PharmacyProblemException(400, "invoice-total-mismatch", "Invoice total does not match",
                "Invoice total does not match the lines.");
    }

    /** 409 {@code client-key-reused}: the key belongs to another caller or another operation. */
    public static PharmacyProblemException clientKeyReused() {
        return new PharmacyProblemException(409, "client-key-reused", "Request already used", ProductService.KEY_REUSED);
    }

    /**
     * 422 {@code opening-stock-invalid}: the file failed its checks and the import is now NOT_ACCEPTED. The status change must
     * be kept, so the service that throws it lists this class in {@code noRollbackFor}.
     */
    public static final class NotAccepted extends PharmacyProblemException {

        private static final long serialVersionUID = 1L;

        public NotAccepted(int errorCount, Object rowIssues) {
            super(422, "opening-stock-invalid", "File not accepted", (errorCount == 1 ? "1 row has a problem."
                    : errorCount + " rows have problems.") + " Fix them and upload the file again.");
            with("errorCount", errorCount);
            with("rowIssues", rowIssues);
        }
    }
}
