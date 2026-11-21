package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The response bodies of the WP-J sale checks and registers and of the WP-K queue of docs/api/pharmacy-sales.yaml (SAH-80-6 to
 * 80-11): DiscountRequest, SubstituteOptions, the H1 register, the Schedule X balance register and sale entries, OpdQueuePage. Plain
 * records, field names as the contract. Patient names appear only in the register rows (rule 29) and are never logged.
 */
public final class SaleCheckViews {

    private SaleCheckViews() {
    }

    // ---- 80-6 discount approval ----

    public record DiscountRequest(boolean required, UUID requestId, String status, String percent, long discountPaise,
                                  String ruleText, String waitingFor, boolean escalated, boolean needsAnotherApprover,
                                  boolean selfApprovalOnly, PharmacyViews.Person decidedBy, StockViews.ReasonRef rejectReason,
                                  Instant raisedAt) {
    }

    // ---- 80-7 substitute ----

    public record SubstituteChecks(boolean salt, String strength, String form) {
    }

    public record SubstituteOption(StockViews.ProductRef product, String mrp, Integer availableQty, String marginPerUnit,
                                   SubstituteChecks checks) {
    }

    public record SubstituteOptions(StockViews.ProductRef product, boolean outOfStock, String sentence,
                                    List<SubstituteOption> items) {
    }

    // ---- 80-9 H1 register ----

    public record RxCopy(String source, UUID rxId) {
    }

    public record H1RegisterRow(UUID rowId, String kind, UUID reversalOf, String reversalReason, Instant at, String billNo,
                                String patientName, Integer patientAge, String patientAddress, String prescriberName,
                                String prescriberRegNo, LocalDate prescriptionDate, StockViews.ProductRef drug,
                                StockViews.ProductRef prescribedDrug, StockViews.BatchRef batch, int qty, RxCopy rxCopy,
                                PharmacyViews.Person pharmacist, StockViews.ReasonRef override) {
    }

    public record H1RegisterPage(List<H1RegisterRow> items, long total, int page, int size, int entriesToday,
                                 int missingPrescriberToday) {
    }

    // ---- 80-10 Schedule X register ----

    /** The quantities are null for a blind caller on an audited product (rule 32); the row and its status chip stay. */
    public record ScheduleXBalanceRow(StockViews.ProductRef product, boolean ndps, Long opening, Long received, Long issued,
                                      Long book, Long physical, LocalDate physicalCountedOn, String status, Long difference) {
    }

    public record ScheduleXRegister(LocalDate from, LocalDate to, int toReview, List<ScheduleXBalanceRow> items,
                                    String note) {
    }

    public record ScheduleXEntry(UUID rowId, String kind, Instant at, String documentNo, String sourceType,
                                 String patientName, Integer patientAge, String prescriberName, String prescriberRegNo,
                                 LocalDate prescriptionDate, StockViews.ProductRef drug, StockViews.BatchRef batch, int qty,
                                 PharmacyViews.Person firstSigner, PharmacyViews.Person secondSigner) {
    }

    public record ScheduleXEntryPage(List<ScheduleXEntry> items, long total, int page, int size) {
    }

    // ---- 80-11 OPD queue (port not connected) ----

    public record OpdCounts(@com.fasterxml.jackson.annotation.JsonProperty("new") int newCount, int picking, int ready, int needsAction) {
    }

    public record OpdQueueRow(UUID rxId, String token, String patientName, String doctorName, int itemCount,
                              int waitingMinutes, String status, PharmacyViews.Person pickingBy, Integer readyMinutes) {
    }

    /** Field {@code connected} is false while the TP-2 port is the no-op: counts 0 and no rows. */
    public record OpdQueuePage(List<OpdQueueRow> items, long total, int page, int size, boolean connected,
                               int refreshAfterSeconds, OpdCounts counts) {
    }
}
