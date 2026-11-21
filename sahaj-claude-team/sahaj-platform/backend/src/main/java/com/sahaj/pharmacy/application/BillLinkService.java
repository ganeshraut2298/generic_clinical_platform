package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleBillRepository.BillRow;
import com.sahaj.pharmacy.application.SaleInputs.LinkPatientInput;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-2, linking a "Link to patient pending" bill to the registered patient, once (docs/api/pharmacy-sales.yaml
 * pharmacyLinkBillToPatient; ADR-030 S6). A pharmacist member of the store, Integrated mode only. The bill must still be pending (else
 * 409 {@code link-already-set}); the patient must exist (404; a failing patient port is 503). The register rows keep what was typed at
 * the sale. The audit row holds the bill id and the patient id, never a name.
 */
@Service
public class BillLinkService {

    static final String ALREADY_LINKED = "This bill is already linked.";
    private static final Logger log = LoggerFactory.getLogger(BillLinkService.class);
    private static final String ENTITY_TYPE = "PHARMACY_BILL";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SaleBillRepository bills;
    private final SaleBillAssembler assembler;
    private final SaleSupport support;
    private final DocumentEventRepository events;
    private final AuditLog auditLog;

    public BillLinkService(PharmacyGate gate, StoreAccessGuard guard, SaleBillRepository bills, SaleBillAssembler assembler,
                           SaleSupport support, DocumentEventRepository events, AuditLog auditLog) {
        this.gate = gate;
        this.guard = guard;
        this.bills = bills;
        this.assembler = assembler;
        this.support = support;
        this.events = events;
        this.auditLog = auditLog;
    }

    @Transactional
    public SaleViews.Bill link(UUID tenantId, UUID userId, String rawStoreId, String rawBillId,
                               PharmacyInputs.Body<LinkPatientInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        LinkPatientInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL_SCHEDULED);
        support.requireIntegrated(tenantId);
        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        if (in.patientId() == null) {
            errors.add(new FieldMessage("patientId", "Choose a patient"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        support.patient(tenantId, in.patientId(), false);
        BillRow bill = bills.findForUpdate(tenantId, billId).filter(b -> b.storeId().equals(storeId))
                .orElseThrow(() -> new NotFoundException(SaleSupport.NOT_FOUND));
        if (!bill.linkPending()) {
            throw PharmacyProblemException.saleConflict("link-already-set", "Already linked", ALREADY_LINKED);
        }
        if (version != bill.version()) {
            throw PharmacyProblemException.changed(assembler.view(caller, userId, bill));
        }
        if (!bills.linkPatient(tenantId, billId, bill.version(), in.patientId(), caller.actorId())) {
            throw PharmacyProblemException.changed(null);
        }
        events.insert(tenantId, store.unitId(), "BILL", billId, "LINKED", storeId, caller.actorId(), "PHARMACIST", null, null,
                null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("billId", billId.toString());
        after.put("patientId", in.patientId().toString());
        auditLog.record("PHARMACY_BILL_LINKED", ENTITY_TYPE, billId, Map.of("linkPending", true), after);
        log.info("Bill linked billId={}", billId);
        return assembler.view(caller, userId, bills.find(tenantId, billId).orElseThrow());
    }
}
