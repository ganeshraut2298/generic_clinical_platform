package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.platform.api.AuditLog;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that writes {@code pharmacy.store_staff} rows, with their audit events, for the store service (an HOD or
 * delegate chosen on a store) and the staff service (the mapping screen). One row per person and store: a re-map
 * re-activates the removed row (ADR-028 D4). Must be called inside the caller's transaction with the store row locked
 * ({@code StoreRepository.findForUpdate}), and after the caller has made its own checks.
 */
@Component
class StaffMappingWriter {

    static final String ENTITY_TYPE = "PHARMACY_STORE_STAFF";

    private final StoreStaffRepository staff;
    private final AuditLog auditLog;

    StaffMappingWriter(StoreStaffRepository staff, AuditLog auditLog) {
        this.staff = staff;
        this.auditLog = auditLog;
    }

    /**
     * Gives the person {@code role} in the store: a new row, the re-activated removed row, or the active row with a new
     * role. Dates are for DELEGATE only. Audit PHARMACY_STAFF_MAPPED, or PHARMACY_STAFF_MAPPING_CHANGED for an active row.
     */
    StoreStaff assign(UUID tenantId, UUID unitId, UUID storeId, UUID membershipId, StoreRole role, LocalDate from,
                      LocalDate to, UUID actorUserId) {
        LocalDate delegateFrom = role == StoreRole.DELEGATE ? from : null;
        LocalDate delegateTo = role == StoreRole.DELEGATE ? to : null;
        Optional<StoreStaff> existing = staff.findForMember(tenantId, storeId, membershipId);
        if (existing.isEmpty()) {
            UUID id = staff.insert(tenantId, unitId, storeId, membershipId, role, delegateFrom, delegateTo, actorUserId);
            auditLog.record("PHARMACY_STAFF_MAPPED", ENTITY_TYPE, id, null,
                    values(storeId, membershipId, role, delegateFrom, delegateTo, "ACTIVE"));
            return staff.find(tenantId, id).orElseThrow();
        }
        StoreStaff row = existing.get();
        if (!staff.update(tenantId, row.id(), row.version(), role, delegateFrom, delegateTo, true, actorUserId)) {
            throw new IllegalStateException("A store mapping changed under the store lock");
        }
        auditLog.record(row.active() ? "PHARMACY_STAFF_MAPPING_CHANGED" : "PHARMACY_STAFF_MAPPED", ENTITY_TYPE, row.id(),
                row.active() ? values(row) : values(storeId, membershipId, row.storeRole(), row.delegateFrom(),
                        row.delegateTo(), "REMOVED"),
                values(storeId, membershipId, role, delegateFrom, delegateTo, "ACTIVE"));
        return staff.find(tenantId, row.id()).orElseThrow();
    }

    /**
     * Turns an HOD or delegate into a plain Store user (kept, audited): used when a new HOD or delegate is set on the
     * store, or the delegation ends.
     */
    void demote(UUID tenantId, StoreStaff row, UUID actorUserId) {
        if (!staff.update(tenantId, row.id(), row.version(), StoreRole.STORE_USER, null, null, true, actorUserId)) {
            throw new IllegalStateException("A store mapping changed under the store lock");
        }
        auditLog.record("PHARMACY_STAFF_MAPPING_CHANGED", ENTITY_TYPE, row.id(), values(row),
                values(row.storeId(), row.membershipId(), StoreRole.STORE_USER, null, null, "ACTIVE"));
    }

    /** Audit values of a mapping: ids, role, dates and status only. */
    static Map<String, Object> values(StoreStaff row) {
        return values(row.storeId(), row.membershipId(), row.storeRole(), row.delegateFrom(), row.delegateTo(),
                row.active() ? "ACTIVE" : "REMOVED");
    }

    static Map<String, Object> values(UUID storeId, UUID membershipId, StoreRole role, LocalDate from, LocalDate to,
                                      String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("membershipId", membershipId.toString());
        values.put("storeRole", role.name());
        values.put("delegateFrom", from == null ? null : from.toString());
        values.put("delegateTo", to == null ? null : to.toString());
        values.put("status", status);
        return values;
    }
}
