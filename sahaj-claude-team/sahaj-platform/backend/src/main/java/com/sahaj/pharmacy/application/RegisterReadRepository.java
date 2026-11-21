package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads of the append-only legal registers (SAH-80-9 and 80-10; ADR-030 S7, S8) and of the AVAILABLE ledger for the Schedule X
 * balance register. Select only. A row holds a patient name, age and address (BR-34): the service passes them to the screen and
 * nowhere else. {@code from} is inclusive and {@code to} exclusive, both IST day starts as instants.
 */
public interface RegisterReadRepository {

    /** One H1 register row as stored. */
    record H1Row(UUID id, String kind, UUID reversesRowId, String reversalReason, String reversalRef, Instant occurredAt,
                 String billNo, UUID patientId, String patientName, int patientAge, String patientAddress,
                 String prescriberName, String prescriberRegNo, LocalDate prescriptionDate, String rxSource, UUID rxRecordId,
                 UUID productId, UUID prescribedProductId, UUID batchId, int qty, UUID pharmacistMembershipId,
                 String overrideReasonKey) {
    }

    /** One Schedule X register row as stored. */
    record XRow(UUID id, String kind, Instant occurredAt, String sourceType, String sourceNo, String reversalReason,
                UUID patientId, String patientName, int patientAge, String prescriberName, String prescriberRegNo,
                LocalDate prescriptionDate, UUID productId, UUID batchId, int qty, UUID firstSigner, UUID secondSigner) {
    }

    /** The AVAILABLE ledger of one product at the store: before the range, in it and at its end. */
    record XBalance(UUID productId, boolean ndps, long opening, long received, long issued, long closing) {
    }

    /** The counted quantity of the product in the latest closed stock audit of the store and the day it closed. */
    record XCount(long countedQty, LocalDate countedOn) {
    }

    List<H1Row> h1Page(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike, int limit,
                       int offset);

    long h1Count(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike);

    /** All rows of the filter, oldest first, at most {@code limit} (the export reads one more than the cap). */
    List<H1Row> h1All(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike, int limit);

    /** ENTRY rows in the range; with {@code overridesOnly} only those saved with an H1 override (a missing prescriber). */
    int h1Entries(UUID tenantId, UUID storeId, Instant from, Instant to, boolean overridesOnly);

    List<XRow> xPage(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, int limit, int offset);

    long xCount(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId);

    List<XBalance> xBalances(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId);

    Map<UUID, XCount> latestCounts(UUID tenantId, UUID storeId, Collection<UUID> productIds);
}
