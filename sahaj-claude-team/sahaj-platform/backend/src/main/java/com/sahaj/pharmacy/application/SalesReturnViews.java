package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The response bodies of the sales return, licence and activity operations of docs/api/pharmacy-sales.yaml (SAH-80-18, 80-19). Plain
 * records, field names as the contract. A name appears only where the contract says so and is never logged (rule 29).
 */
public final class SalesReturnViews {

    private SalesReturnViews() {
    }

    public record WindowState(String state, int days, LocalDate lastDay) {
    }

    public record ReturnableBillRow(UUID billId, String billNo, Instant savedAt, String buyerLabel, long netPayablePaise,
                                    WindowState window) {
    }

    public record ReturnableBillList(List<ReturnableBillRow> items) {
    }

    public record RefundMode(String mode, Long maxPaise, boolean original) {
    }

    public record ReturnableLine(UUID billLineId, StockViews.ProductRef product, StockViews.BatchRef batch, int soldQty,
                                 int returnedQty, int returnableQty, boolean returnable, long refundPerUnitPaise,
                                 String schedule) {
    }

    public record ReturnableBill(UUID billId, String billNo, Instant savedAt, String buyerLabel, long netPayablePaise,
                                 WindowState window, StockViews.StoreRef store, boolean namedCustomer, Long limitPaise,
                                 List<RefundMode> refundModes, List<ReturnableLine> lines) {
    }

    public record RefundView(String mode, long amountPaise) {
    }

    public record ReturnLineView(UUID returnLineId, UUID billLineId, StockViews.ProductRef product, StockViews.BatchRef batch,
                                 int qty, StockViews.ReasonRef condition, String destination, long refundPaise,
                                 long taxablePaise, long taxPaise, String gstRate) {
    }

    public record BillRef(UUID billId, String billNo, Instant savedAt, String buyerLabel) {
    }

    public record SalesReturn(UUID id, String status, String creditNoteNo, BillRef bill, StockViews.StoreRef store,
                              List<ReturnLineView> lines, StockViews.ReasonRef reason, List<RefundView> refunds,
                              long refundTotalPaise, PharmacyViews.Person startedBy, Instant startedAt,
                              PharmacyViews.Person acceptedBy, PharmacyViews.Person completedBy, Instant completedAt,
                              StockViews.ApprovalSummary approval, boolean canAccept, boolean canFinish,
                              java.util.Map<String, Object> print, List<StockViews.HistoryEvent> history, long version) {
    }

    public record SalesReturnRow(UUID id, String status, String creditNoteNo, String billNo, Instant at, String buyerLabel,
                                 long refundTotalPaise, PharmacyViews.Person startedBy) {
    }

    public record SalesReturnPage(List<SalesReturnRow> items, long total, int page, int size) {
    }

    // ---- 80-19 licences, registrations, activity ----

    public record LicenceStatus(String state, Integer daysLeft) {
    }

    public record PharmacistRegistration(UUID id, PharmacyViews.Person member, String registrationNo, LocalDate validTill,
                                         long version) {
    }

    public record LicenceRow(String kind, String document, String number, LocalDate validTill, LicenceStatus status,
                             PharmacistRegistration pharmacist) {
    }

    public record Licences(int warningDays, List<String> expired, List<LicenceRow> rows) {
    }

    public record ActivityRow(Instant at, PharmacyViews.Person by, String roleLabel, String type, String text,
                              StockViews.StoreRef store) {
    }

    public record ActivityPage(List<ActivityRow> items, long total, int page, int size) {
    }
}
