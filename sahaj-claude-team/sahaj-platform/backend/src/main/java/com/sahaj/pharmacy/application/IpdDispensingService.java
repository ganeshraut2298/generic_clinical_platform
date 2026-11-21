package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.integration.IpdChargePort;
import com.sahaj.pharmacy.integration.IpdOrderSource;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-13 to 80-17, the pharmacy side of IPD dispensing (docs/api/pharmacy-sales.yaml WP-M: pharmacyIpdQueue, pharmacyIpdOrderSet,
 * pharmacySetIpdLinePick, pharmacyListIpdIssues, pharmacyCreateIpdIssue, pharmacyGetIpdIssue, pharmacyWardReturnableItems,
 * pharmacyRecordWardReturn, pharmacyAcceptWardReturn, pharmacyRefuseWardReturn, pharmacyChargesWaiting, pharmacyPostWaitingCharges,
 * pharmacyDischarges, pharmacyMarkDischargeCleared; ADR-030 S12, DEC-072 Q2, DEC-089 item for run 13).
 *
 * <p>The ports TP-4 {@link IpdOrderSource} and TP-5 {@link IpdChargePort} are the pharmacy-owned no-op ones in phase 1, so nothing here
 * writes anything. Every operation answers, in this order: the store guard (a sale store, and the capability the contract names;
 * the owner reads the dispense screens through readRegisters), the mode check (409 {@code integration-off} in Standalone), then:
 * <ul>
 * <li>the lists (queue, discharges, issues, returnable items, charges waiting) answer 200 and empty, with {@code connected false};</li>
 * <li>an operation that needs an IPD order, issue or ward return answers what the contract lists for the no-op case: 409
 * {@code not-connected} (order set, line pick, dispense, post charges, mark cleared), 409 {@code not-issued} (record a ward return:
 * nothing was ever issued to a ward from this store) or 404 (one issue or ward return: none exists);</li>
 * <li>a port that reports itself connected before this service is built out is refused with 409 {@code not-connected} too, never
 * with a half answer.</li>
 * </ul>
 * The bodies of the writes are not read: nothing can be saved. Nothing is written and nothing in IPD, billing or OPD is touched.
 */
@Service
public class IpdDispensingService {

    static final String ORDERS_NOT_CONNECTED = "IPD orders are not connected yet.";
    static final String CHARGES_NOT_CONNECTED = "The IPD bill is not connected yet. Charges stay waiting.";
    static final String NOT_ISSUED = "Nothing was issued to a ward from this store for this item.";
    static final String CHARGES_NOT_BUILT = "Posting charges is not available yet.";
    static final int REFRESH_SECONDS = 30;
    private static final int MAX_SIZE = 25;

    /** The lanes of the queue. */
    enum Lane { STAT, URGENT, ROUTINE, DISCHARGE }

    /** The charge states of an IPD issue. */
    enum ChargeStatus { CHARGED, CHARGE_PENDING }

    /** What an operation answers once the guard and the mode check have passed. */
    public enum Refusal { NOT_CONNECTED, NOT_ISSUED, NOT_FOUND }

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SaleSupport support;
    private final IpdOrderSource orders;
    private final IpdChargePort charges;
    private final SalesReturnService salesReturns;

    public IpdDispensingService(PharmacyGate gate, StoreAccessGuard guard, SaleSupport support, IpdOrderSource orders,
                                IpdChargePort charges, SalesReturnService salesReturns) {
        this.salesReturns = salesReturns;
        this.gate = gate;
        this.guard = guard;
        this.support = support;
        this.orders = orders;
        this.charges = charges;
    }

    // ---- the lists ----

    @Transactional(readOnly = true)
    public IpdViews.IpdQueuePage queue(UUID tenantId, UUID userId, String rawStoreId, String rawLane, String rawPage,
                                       String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guardRead(caller, storeId);
        support.requireIntegrated(tenantId);
        PharmacyParams.optionalEnum("lane", rawLane, Lane.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        refuseIfPortLive();
        return new IpdViews.IpdQueuePage(List.of(), 0, page, size, false, REFRESH_SECONDS, new IpdViews.QueueCounts(0, 0, 0, null));
    }

    @Transactional(readOnly = true)
    public IpdViews.IpdIssuePage issues(UUID tenantId, UUID userId, String rawStoreId, String rawAdmissionId,
                                        String rawChargeStatus, String rawDate, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, readCapability(caller));
        support.requireIntegrated(tenantId);
        PharmacyParams.optionalUuid("admissionId", rawAdmissionId, "Choose an admission");
        PharmacyParams.optionalEnum("chargeStatus", rawChargeStatus, ChargeStatus.class);
        date(rawDate);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        return new IpdViews.IpdIssuePage(List.of(), 0, page, size);
    }

    @Transactional(readOnly = true)
    public IpdViews.WardReturnable returnable(UUID tenantId, UUID userId, String rawStoreId, String rawQ) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.requireSale(caller, storeId, StoreCapability.RECORD_WARD_RETURN);
        support.requireIntegrated(tenantId);
        String q = SaleSupport.blank(rawQ);
        if (q == null || q.length() < 2 || q.length() > 80) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type 2 to 80 characters.")));
        }
        return new IpdViews.WardReturnable(List.of());
    }

    /** Charges and credits waiting to post: the owner or a PHARMACIST member; empty while TP-5 is the no-op. */
    @Transactional(readOnly = true)
    public IpdViews.ChargesWaitingPage chargesWaiting(UUID tenantId, UUID userId, String rawStoreId, String rawPage,
                                                      String rawSize) {
        scopedCaller(tenantId, userId, rawStoreId);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        return new IpdViews.ChargesWaitingPage(List.of(), 0, page, size, charges.connected(),
                new IpdViews.ChargeCounts(0, 0, 0));
    }

    @Transactional(readOnly = true)
    public IpdViews.DischargeList discharges(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guardRead(caller, storeId);
        support.requireIntegrated(tenantId);
        refuseIfPortLive();
        return new IpdViews.DischargeList(false, List.of());
    }

    /**
     * pharmacyReturnsWaiting (SAH-80-15): works in both modes. The counter returns waiting for a pharmacist are real rows; the ward
     * half is empty while TP-4 is the no-op (no ward return can have been recorded). A cashier sees the returns they started only.
     */
    @Transactional(readOnly = true)
    public IpdViews.ReturnsWaitingPage returnsWaiting(UUID tenantId, UUID userId, String rawStoreId, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        IpdViews.ReturnsWaitingSlice slice = salesReturns.waitingCounterReturns(caller, storeId, page, size);
        return new IpdViews.ReturnsWaitingPage(slice.rows(), slice.total(), page, size, charges.connected());
    }

    // ---- the operations that need an order, an issue or a ward return ----

    /** Guard, mode check, then the refusal the contract lists for the no-op port. */
    @Transactional(readOnly = true)
    public void refuse(UUID tenantId, UUID userId, String rawStoreId, StoreCapability capability, boolean read, boolean saleStore,
                       Refusal refusal, String... rawIds) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        for (String id : rawIds) {
            PharmacyParams.pathId(id);
        }
        StoreCapability needed = read ? readCapability(caller) : capability;
        if (saleStore) {
            guard.requireSale(caller, storeId, needed);
        } else {
            guard.require(caller, storeId, needed);
        }
        support.requireIntegrated(tenantId);
        switch (refusal) {
            case NOT_ISSUED:
                throw PharmacyProblemException.saleConflict("not-issued", "Not issued", NOT_ISSUED);
            case NOT_FOUND:
                throw new NotFoundException("This record was not found.");
            case NOT_CONNECTED:
            default:
                throw PharmacyProblemException.notConnected(ORDERS_NOT_CONNECTED);
        }
    }

    /** Post waiting charges: the owner or a PHARMACIST member, the mode check, then 409 not-connected (TP-5 is the no-op). */
    @Transactional(readOnly = true)
    public void postWaiting(UUID tenantId, UUID userId, String rawStoreId) {
        scopedCaller(tenantId, userId, rawStoreId);
        throw PharmacyProblemException.notConnected(charges.connected() ? CHARGES_NOT_BUILT : CHARGES_NOT_CONNECTED);
    }

    // ---- helpers ----

    /** The owner reads the dispense screens through readRegisters; everyone else needs dispense (a PHARMACIST member). */
    private static StoreCapability readCapability(PharmacyCaller caller) {
        return caller.isOwner() ? StoreCapability.READ_REGISTERS : StoreCapability.DISPENSE;
    }

    private void guardRead(PharmacyCaller caller, UUID storeId) {
        guard.requireSale(caller, storeId, readCapability(caller));
    }

    /** The SCOPED operations of charges: owner or pharmacist, the optional store reachable, the mode check. */
    private PharmacyCaller scopedCaller(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        if (!caller.isOwner() && !caller.isPharmacist()) {
            throw new ForbiddenException("forbidden", "Not allowed", PharmacyGate.NOT_ALLOWED);
        }
        UUID storeId = PharmacyParams.optionalUuid("storeId", rawStoreId, "Choose a store");
        if (storeId != null) {
            guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        }
        support.requireIntegrated(tenantId);
        return caller;
    }

    private void refuseIfPortLive() {
        if (orders.connected()) {
            throw PharmacyProblemException.notConnected(ORDERS_NOT_CONNECTED);
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            throw new InvalidFieldsException(List.of(new FieldMessage("date", "Enter a valid date")));
        }
    }
}
