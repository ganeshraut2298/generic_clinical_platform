package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-16: the IPD ward of a ward store (docs/api/pharmacy.yaml pharmacyListIpdWards, pharmacySetLinkedWard). OWNER only.
 * Wards come from the {@link AdmittedPatientsReader} port, which is a no-op in phase 1, so a link cannot be set until the
 * IPD adapter exists (409 integration-off); clearing a link is always allowed.
 */
@Service
public class LinkedWardService {

    static final String ONLY_WARD = "Only a ward store can be linked to a ward";
    static final String CHOOSE_WARD = "Choose a ward";
    private static final String ENTITY_TYPE = "PHARMACY_STORE";

    private final PharmacyGate gate;
    private final UnitRepository units;
    private final StoreRepository stores;
    private final StoreAssembler assembler;
    private final AdmittedPatientsReader reader;
    private final AuditLog auditLog;

    public LinkedWardService(PharmacyGate gate, UnitRepository units, StoreRepository stores, StoreAssembler assembler,
                             AdmittedPatientsReader reader, AuditLog auditLog) {
        this.gate = gate;
        this.units = units;
        this.stores = stores;
        this.assembler = assembler;
        this.reader = reader;
        this.auditLog = auditLog;
    }

    /** The wards to choose from. 409 integration-off when the unit is Standalone or no reader is connected. */
    @Transactional(readOnly = true)
    public PharmacyViews.WardList wards(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        requireIntegrated(tenantId);
        List<PharmacyViews.WardRef> items = reader.wards(tenantId).stream()
                .map(w -> new PharmacyViews.WardRef(w.wardId(), w.name())).toList();
        return new PharmacyViews.WardList(items);
    }

    @Transactional
    public PharmacyViews.Store setLinkedWard(UUID tenantId, UUID userId, String rawStoreId,
                                             PharmacyInputs.Body<PharmacyInputs.LinkedWard> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.LinkedWard input = body.read();
        if (input.version() == null || input.version() < 0) {
            throw invalid("version", StoreService.RELOAD);
        }
        if (PharmacyInputs.Sent.missing(input.wardId())) {
            throw invalid("wardId", UnitService.REQUIRED);
        }
        UUID wardId = input.wardId().value();
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(() -> new NotFoundException("This record was not found."));
        if (store.type() != StoreType.WARD) {
            throw invalid("storeId", ONLY_WARD);
        }
        if (wardId != null) {
            requireIntegrated(tenantId);
            boolean known = reader.wards(tenantId).stream().anyMatch(w -> w.wardId().equals(wardId));
            if (!known) {
                throw invalid("wardId", CHOOSE_WARD);
            }
        }
        UUID before = stores.linkedWards(tenantId).get(storeId);
        if (input.version() != store.version()
                || !stores.setLinkedWard(tenantId, storeId, store.version(), wardId, userId)) {
            throw PharmacyProblemException.settingsChanged(assembler.one(tenantId, storeId).orElseThrow());
        }
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("wardId", before == null ? null : before.toString());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("wardId", wardId == null ? null : wardId.toString());
        auditLog.record("PHARMACY_STORE_LINKED_WARD_CHANGED", ENTITY_TYPE, storeId, was, now);
        return assembler.one(tenantId, storeId).orElseThrow();
    }

    private void requireIntegrated(UUID tenantId) {
        boolean integrated = units.find(tenantId).map(u -> u.integrationMode() == IntegrationMode.INTEGRATED).orElse(false);
        if (!integrated) {
            throw new IntegrationOffException();
        }
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }
}
