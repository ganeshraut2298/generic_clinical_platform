package com.sahaj.pharmacy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The ten SAH-79 reports (docs/api/pharmacy.yaml 0.3.6, pharmacyRunReport) with their group, label, title, columns and who may
 * run them (ADR-029 P8, OQ-79-21). The stock ledger has its own paths and is not in this list. Money cells are paise integers.
 */
public enum ReportDef {

    STORE_STOCK("store-stock", Group.STOCK, "Store stock", Tier.STOCK_ONLY, false,
            cols(t("store", "Store"), t("product", "Product"), i("available", "Available"), i("inTransit", "In transit"),
                    i("pendingReturn", "Pending return"), i("quarantine", "Quarantine"), i("reorderLevel", "Reorder level"),
                    p("value", "Value"))),
    BATCH_EXPIRY("batch-expiry", Group.STOCK, "Batch and expiry", Tier.STOCK_ONLY, false,
            cols(t("store", "Store"), t("product", "Product"), t("batch", "Batch"), d("expiry", "Expiry"), t("state", "State"),
                    i("qty", "Quantity"), p("mrp", "MRP"), p("purchaseRate", "Purchase rate"), p("landed", "Landed cost"),
                    new Col("margin", "Margin", Kind.PERCENT), t("supplier", "Supplier"), t("status", "Status"))),
    BELOW_REORDER("below-reorder", Group.STOCK, "Below reorder level", Tier.STOCK_ONLY, false,
            cols(t("store", "Store"), t("product", "Product"), i("available", "Available"), i("reorderLevel", "Reorder level"))),
    INDENT_REGISTER("indent-register", Group.MOVEMENT, "Indent register", Tier.MOVEMENT, true,
            cols(t("indentNo", "Indent no"), t("requestingStore", "Requesting store"), t("issuingStore", "Issuing store"),
                    t("priority", "Priority"), t("raisedBy", "Raised by"), dt("raisedAt", "Raised at"), t("status", "Status"),
                    i("approvedQty", "Approved"), i("dispatchedQty", "Dispatched"), i("receivedQty", "Received"),
                    i("rejectedQty", "Rejected"), i("shortQty", "Short"), i("backOrderQty", "Back-ordered"))),
    TRANSFER_REGISTER("transfer-register", Group.MOVEMENT, "Transfer register", Tier.MOVEMENT, true,
            cols(t("transferNo", "Transfer no"), t("fromStore", "From store"), t("toStore", "To store"), t("reason", "Reason"),
                    t("raisedBy", "Raised by"), dt("raisedAt", "Raised at"), t("status", "Status"),
                    i("approvedQty", "Approved"), i("dispatchedQty", "Dispatched"), i("receivedQty", "Received"),
                    i("rejectedQty", "Rejected"), i("shortQty", "Short"), i("backOrderQty", "Back-ordered"))),
    WARD_CONSUMPTION("ward-consumption", Group.MOVEMENT, "Ward consumption", Tier.MOVEMENT, true,
            cols(d("date", "Date"), t("store", "Store"), t("product", "Product"), t("batch", "Batch"), i("qty", "Quantity"),
                    t("type", "Type"), t("purpose", "Purpose"), t("ipNumber", "IP number"), t("user", "User"),
                    t("chargeStatus", "Charge status"))),
    RETURNS_WRITE_OFFS("returns-write-offs", Group.MOVEMENT, "Returns and write-offs", Tier.MOVEMENT, true,
            cols(d("date", "Date"), t("docNo", "Document no"), t("type", "Type"), t("store", "Store"), t("product", "Product"),
                    t("batch", "Batch"), i("qty", "Quantity"), p("valuePaise", "Value"), t("reason", "Reason"),
                    t("approver", "Approver"), t("status", "Status"))),
    PO_VS_GRN("po-vs-grn", Group.PURCHASE, "Purchase orders against receipts", Tier.PURCHASE, true,
            cols(t("poNo", "PO no"), t("vendor", "Vendor"), t("product", "Product"), i("orderedQty", "Ordered"),
                    i("receivedQty", "Received"), i("shortQty", "Short"), t("status", "Status"),
                    d("deliveryExpected", "Delivery expected"))),
    PURCHASE_REGISTER("purchase-register", Group.PURCHASE, "Purchase register", Tier.PURCHASE, true,
            cols(d("date", "Date"), t("grnNo", "GRN no"), t("vendor", "Vendor"), t("vendorGstin", "Vendor GSTIN"),
                    t("invoiceNo", "Invoice no"), d("invoiceDate", "Invoice date"), t("product", "Product"), t("hsn", "HSN"),
                    t("batch", "Batch"), i("qty", "Quantity"), p("taxablePaise", "Taxable value"),
                    new Col("gstRate", "GST rate", Kind.PERCENT), p("gstPaise", "GST"), p("discountPaise", "Discount"),
                    p("totalPaise", "Total"))),
    DEBIT_NOTES_PENDING("debit-notes-pending", Group.PURCHASE, "Debit notes pending", Tier.PURCHASE, false,
            cols(t("dnNo", "Debit note no"), t("vendor", "Vendor"), p("amountPaise", "Amount"), t("step", "Step"),
                    i("ageDays", "Age in days"), t("olderThan30", "Older than 30 days"))),
    // SAH-80-21, the Sales & compliance group (docs/api/pharmacy-sales.yaml WP-N). Access: the owner, a pharmacist member, the HOD and
    // the current delegate of a selling store; never a cashier.
    SALES_MARGIN("sales-margin", Group.SALES_COMPLIANCE, "Sales and margin", Tier.SALES, true,
            cols(d("date", "Date"), t("store", "Store"), i("bills", "Bills"), i("qty", "Quantity"),
                    p("salesPaise", "Sales"), p("discountPaise", "Discount"), p("taxPaise", "GST"),
                    p("costPaise", "Cost"), new Col("marginPercent", "Margin", Kind.PERCENT))),
    SALES_RETURNS("sales-returns", Group.SALES_COMPLIANCE, "Sales returns", Tier.SALES, true,
            cols(t("creditNoteNo", "Credit note no"), t("billNo", "Bill no"), dt("date", "Date"), t("product", "Product"),
                    t("batch", "Batch"), i("qty", "Quantity"), t("reason", "Reason"), t("condition", "Condition"),
                    t("refundMode", "Refund mode"), p("amountPaise", "Amount"), t("approver", "Approver"),
                    t("finishedBy", "Finished by"))),
    GST_SALES_REGISTER("gst-sales-register", Group.SALES_COMPLIANCE, "GST sales register", Tier.SALES, true,
            cols(dt("date", "Date"), t("billNo", "Bill or credit note no"), t("hsn", "HSN"),
                    new Col("gstRate", "GST rate", Kind.PERCENT), p("taxablePaise", "Taxable value"), p("cgstPaise", "CGST"),
                    p("sgstPaise", "SGST"), p("totalPaise", "Total"), t("cancelled", "Cancelled"))),
    H1_REGISTER("h1-register", Group.SALES_COMPLIANCE, "Schedule H1 register", Tier.SALES, true,
            cols(dt("date", "Date and time"), t("billNo", "Bill number"), t("kind", "Kind"), t("patientName", "Patient name"),
                    i("patientAge", "Age"), t("patientAddress", "Address"), t("prescriber", "Prescriber"),
                    t("prescriberRegNo", "Registration number"), d("prescriptionDate", "Prescription date"),
                    t("drug", "Drug"), t("batch", "Batch"), i("qty", "Quantity"), t("pharmacist", "Pharmacist"),
                    t("override", "Override reason"))),
    SCHEDULE_X_REGISTER("schedule-x-register", Group.SALES_COMPLIANCE, "Schedule X and NDPS register", Tier.SALES, true,
            cols(t("store", "Store"), t("product", "Product"), t("ndps", "NDPS"), i("opening", "Opening"),
                    i("received", "Received"), i("issued", "Issued"), i("book", "Book"), i("physical", "Physical"),
                    t("status", "Status"))),
    ACTIVITY_LOG("activity-log", Group.SALES_COMPLIANCE, "Activity log", Tier.SALES, true,
            cols(dt("at", "Date and time"), t("store", "Store"), t("type", "Type"), t("documentNo", "Document no"),
                    t("person", "Person"), t("reason", "Reason")));

    /** Catalogue groups with the card titles of mockup 15. */
    public enum Group {
        STOCK("Stock"), MOVEMENT("Movement"), PURCHASE("Purchase"), SALES_COMPLIANCE("Sales & compliance");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Who may run the report: every tier needs the store scope; PURCHASE is the owner and the Central Store user only. */
    public enum Tier { STOCK_ONLY, MOVEMENT, PURCHASE, SALES }

    public enum Kind { TEXT, INTEGER, PAISE, RATE, PERCENT, DATE, DATE_TIME }

    public record Col(String key, String label, Kind kind) {
    }

    private final String key;
    private final Group group;
    private final String label;
    private final Tier tier;
    private final boolean dated;
    private final List<Col> columns;

    ReportDef(String key, Group group, String label, Tier tier, boolean dated, List<Col> columns) {
        this.key = key;
        this.group = group;
        this.label = label;
        this.tier = tier;
        this.dated = dated;
        this.columns = columns;
    }

    public String key() {
        return key;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    public Tier tier() {
        return tier;
    }

    /** True when from and to are required (the movement and purchase reports; the stock reports are "now"). */
    public boolean dated() {
        return dated;
    }

    public List<Col> columns() {
        return columns;
    }

    /** The report of this key, empty for an unknown key (and for the SAH-80 keys until they are built). */
    public static Optional<ReportDef> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (ReportDef def : values()) {
            if (def.key.equals(k)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    private static List<Col> cols(Col... given) {
        return new ArrayList<>(List.of(given));
    }

    private static Col t(String key, String label) {
        return new Col(key, label, Kind.TEXT);
    }

    private static Col i(String key, String label) {
        return new Col(key, label, Kind.INTEGER);
    }

    private static Col p(String key, String label) {
        return new Col(key, label, Kind.PAISE);
    }

    private static Col d(String key, String label) {
        return new Col(key, label, Kind.DATE);
    }

    private static Col dt(String key, String label) {
        return new Col(key, label, Kind.DATE_TIME);
    }
}
