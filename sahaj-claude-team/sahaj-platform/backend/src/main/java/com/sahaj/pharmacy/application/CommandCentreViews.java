package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;

/**
 * The CommandCentre of docs/api/pharmacy-sales.yaml (SAH-80-20): each card is an object with its own {@code state} (OK, ERROR or
 * EMPTY) so one card failing never takes the others down. A card the caller may not see, or that the data cannot give, is null.
 */
public final class CommandCentreViews {

    private CommandCentreViews() {
    }

    public record TodaysSales(String state, Long netPaise, Integer billCount, Long averagePaise) {
    }

    public record GrossMargin(String state, String percent) {
    }

    public record NearExpiry90(String state, Long valuePaise, Integer batchCount, Long returnablePaise) {
    }

    public record Reorder(String state, Integer productCount) {
    }

    public record RxWaiting(String state, boolean opdConnected, Integer opdWaiting, boolean ipdConnected, Integer ipdWaiting,
                            Integer indentsWaiting) {
    }

    public record UdharCard(String state, Long duePaise, Long over60DaysPaise) {
    }

    public record Bar(String band, int batchCount, Long valuePaise) {
    }

    public record ExpiryRisk(String state, List<Bar> bars) {
    }

    public record LivePrescription(String source, java.util.UUID id, Instant at, String patientName, int itemCount,
                                   String availability, Integer shortCount, String action) {
    }

    public record LivePrescriptions(String state, List<LivePrescription> items) {
    }

    public record H1Card(int billsToday, int overridesToReview) {
    }

    public record ScheduleXCard(int toReview, boolean balanced) {
    }

    public record Compliance(String state, H1Card h1, ScheduleXCard scheduleX, SalesReturnViews.LicenceStatus drugLicence) {
    }

    public record CommandCentre(Instant asOf, StockViews.StoreRef store, TodaysSales todaysSales, GrossMargin grossMargin,
                                NearExpiry90 nearExpiry90, Reorder reorder, RxWaiting rxWaiting, UdharCard udhar,
                                ExpiryRisk expiryRisk, LivePrescriptions livePrescriptions, Compliance compliance) {
    }
}
