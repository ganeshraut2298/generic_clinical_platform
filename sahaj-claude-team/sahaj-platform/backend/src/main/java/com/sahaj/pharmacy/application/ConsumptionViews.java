package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of SAH-79-16 (docs/api/pharmacy.yaml 0.3.6 Consumption, ConsumptionPage, AdmittedPatient). */
public final class ConsumptionViews {

    private ConsumptionViews() {
    }

    public record Line(StockViews.ProductRef product, StockViews.BatchRef batch, int quantity) {
    }

    /** {@code ipNumber} only for a patient entry, never a name; {@code orderedBy} is a doctor's name or the typed other name. */
    public record Consumption(UUID id, StockViews.StoreRef store, String type, StockViews.ReasonRef purpose, String ipNumber,
                              String orderedBy, String chargeStatus, PharmacyViews.Person recordedBy, Instant recordedAt,
                              String costBookedTo, List<Line> lines, PharmacyViews.Person secondSigner) {
    }

    public record Page(List<Consumption> items, long total, int page, int size) {
    }

    /** The name is for on-screen verification only (ADR-029 P14): never stored, never logged. */
    public record AdmittedPatient(UUID admissionId, String ipNumber, String patientName, String bedLabel) {
    }

    public record AdmittedList(List<AdmittedPatient> items) {
    }

    public record OrderedByList(List<PharmacyViews.Person> items) {
    }
}
