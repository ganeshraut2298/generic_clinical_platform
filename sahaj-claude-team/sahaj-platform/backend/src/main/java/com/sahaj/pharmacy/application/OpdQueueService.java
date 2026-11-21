package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.integration.OpdPrescriptionSource;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-11 and 80-12, the OPD prescription queue behind the pharmacy-owned port TP-2 {@link OpdPrescriptionSource} (docs/api/
 * pharmacy-sales.yaml pharmacyOpdQueue, pharmacyGetOpdPrescription, pharmacySetOpdLinePick, pharmacyClaimOpdPrescription,
 * pharmacyCloseOpdPrescriptionRest, pharmacySetProductLink; ADR-030 S12, DEC-072 Q2, DEC-088 item 7).
 *
 * <p>In this release the port is the no-op: the queue read answers 200 with {@code connected false}, no rows and zero counts (the tab
 * shows "Not connected"); every other operation of the queue answers 409 {@code not-connected} after the store guard and the mode
 * check and before it looks at the body. Typed prescriptions keep working through the bill. Standalone mode answers 409
 * {@code integration-off} first. A connected port is not wired anywhere in phase 1; if one ever reports itself connected before this
 * service is built out, every operation answers 409 {@code not-connected} too, never a half answer. Nothing is written.
 */
@Service
public class OpdQueueService {

    static final String NOT_CONNECTED = "Prescriptions are not connected. Bill the items typed in.";
    static final int REFRESH_SECONDS = 30;
    private static final int MAX_SIZE = 25;
    private static final Set<String> STATUSES = Set.of("NEW", "PICKING", "PICKED", "NEEDS_ACTION", "PART_SUPPLIED");

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SaleSupport support;
    private final OpdPrescriptionSource source;

    public OpdQueueService(PharmacyGate gate, StoreAccessGuard guard, SaleSupport support, OpdPrescriptionSource source) {
        this.gate = gate;
        this.guard = guard;
        this.support = support;
        this.source = source;
    }

    /** The queue: 200 with connected false while the port is the no-op. */
    @Transactional(readOnly = true)
    public SaleCheckViews.OpdQueuePage queue(UUID tenantId, UUID userId, String rawStoreId, String rawQ, String rawStatus,
                                             String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        support.requireIntegrated(tenantId);
        String q = SaleSupport.blank(rawQ);
        if (q != null && q.length() > 40) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type at most 40 characters.")));
        }
        if (rawStatus != null && !rawStatus.isBlank() && !STATUSES.contains(rawStatus)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("status", "Choose a valid option")));
        }
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        if (source.connected()) {
            throw PharmacyProblemException.notConnected(NOT_CONNECTED);
        }
        return new SaleCheckViews.OpdQueuePage(List.of(), 0, page, size, false, REFRESH_SECONDS,
                new SaleCheckViews.OpdCounts(0, 0, 0, 0));
    }

    /**
     * Every other operation of the queue (view, pick, claim, close the rest) and the product link: the store guard (sale store and
     * the capability the operation names), the mode check, then 409 {@code not-connected}. {@code pharmacistOnly} is the capability
     * sellScheduled (close the rest) or dispense (the product link, which is not tied to a sale store).
     */
    @Transactional(readOnly = true)
    public void refuse(UUID tenantId, UUID userId, String rawStoreId, StoreCapability capability, boolean saleStore,
                       String... rawIds) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        for (String id : rawIds) {
            PharmacyParams.pathId(id);
        }
        if (saleStore) {
            guard.requireSale(caller, storeId, capability);
        } else {
            guard.require(caller, storeId, capability);
        }
        support.requireIntegrated(tenantId);
        throw PharmacyProblemException.notConnected(NOT_CONNECTED);
    }
}
