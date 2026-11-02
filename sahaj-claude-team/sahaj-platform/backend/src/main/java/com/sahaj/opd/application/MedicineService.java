package com.sahaj.opd.application;

import com.sahaj.opd.application.MedicineRepository.MedicinePage;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC3, AC5: the global medicine list and this clinic's favourites. The list is read-only for
 * everyone; the owner marks and unmarks favourites, which list first for the doctors of that clinic
 * only. Marking is idempotent; unmarking a medicine that is not a favourite is a 404.
 */
@Service
public class MedicineService {

    static final int PAGE_SIZE = 20;
    private static final String ENTITY_TYPE = "MEDICINE";

    private final MedicineRepository medicineRepository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public MedicineService(MedicineRepository medicineRepository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.medicineRepository = medicineRepository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public MedicinePage search(UUID tenantId, String query, boolean favouritesOnly, int page) {
        int safePage = Math.max(page, 0);
        return medicineRepository.search(tenantId, query, favouritesOnly, PAGE_SIZE, safePage * PAGE_SIZE);
    }

    @Transactional
    public void markFavourite(UUID tenantId, UUID actorUserId, UUID medicineId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        String name = medicineRepository.findName(medicineId)
                .orElseThrow(() -> new NotFoundException("Medicine not found"));
        if (medicineRepository.addFavourite(medicineId, actorUserId) == 1) {
            auditLog.record("MEDICINE_FAVOURITED", ENTITY_TYPE, medicineId, snapshot(name, false), snapshot(name, true));
        }
    }

    @Transactional
    public void unmarkFavourite(UUID tenantId, UUID actorUserId, UUID medicineId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (medicineRepository.removeFavourite(tenantId, medicineId) == 0) {
            throw new NotFoundException("Medicine is not a favourite");
        }
        String name = medicineRepository.findName(medicineId).orElse(null);
        auditLog.record("MEDICINE_UNFAVOURITED", ENTITY_TYPE, medicineId, snapshot(name, true), snapshot(name, false));
    }

    private static Map<String, Object> snapshot(String medicineName, boolean favourite) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("medicine", medicineName);
        values.put("favourite", favourite);
        return values;
    }
}
