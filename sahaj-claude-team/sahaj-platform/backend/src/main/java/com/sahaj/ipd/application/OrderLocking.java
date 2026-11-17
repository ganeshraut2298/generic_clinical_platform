package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.NotFoundException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SAH-31 and SAH-76 (ADR-009 D10, ADR-027 D13): the one way every order action takes its lock. A plain read of the order
 * gives its admission (404 when missing or another clinic's); the bare admission row is locked {@code for no key update}
 * and read joined in a new statement (404); the scope guard runs on that admission next (SAH-34: a scoped nurse out of
 * scope gets 403 {@code outside-scope} before any state answer, also for a discharged admission); the order is read
 * again, because it may have changed while the lock was awaited; the admission must be ADMITTED (409); the patient must
 * be found (404, never a placeholder in the answer to an action). Takes no other lock. Not a Spring bean: each service
 * builds one over its own repositories.
 */
final class OrderLocking {

    /** Pharmacist paths: the role is always scope ALL, so there is nothing to guard. */
    static final Consumer<AdmissionRow> NO_SCOPE_GUARD = admission -> { };

    private final MedicineOrderRepository orders;
    private final AdmissionRepository admissions;
    private final PatientClinicalPort patients;

    OrderLocking(MedicineOrderRepository orders, AdmissionRepository admissions, PatientClinicalPort patients) {
        this.orders = orders;
        this.admissions = admissions;
        this.patients = patients;
    }

    /** The order as read under the admission lock, with the admitted admission and its patient. */
    record Locked(OrderRow order, AdmissionRow admission, PatientClinical patient) {

        @Override
        public String toString() {
            return "Locked[orderId=" + order.id() + "]";
        }
    }

    Locked lock(UUID tenantId, UUID orderId, Consumer<AdmissionRow> scopeGuard) {
        OrderRow first = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        scopeGuard.accept(admission);
        OrderRow current = orders.findById(tenantId, orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!"ADMITTED".equals(admission.status())) {
            throw notAdmitted();
        }
        return new Locked(current, admission, patient(tenantId, admission));
    }

    /** The admission's patient; a missing one is a 404 and never a placeholder name. */
    PatientClinical patient(UUID tenantId, AdmissionRow admission) {
        return patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    static ConflictException notAdmitted() {
        return new ConflictException("not-admitted", "Patient is not admitted", MedicineRules.NOT_ADMITTED);
    }

    static ConflictException stateConflict(String title, String detail) {
        return new ConflictException(MedicineOrderService.STATE_PROBLEM, title, detail);
    }

    static ConflictException orderChanged() {
        return new ConflictException("order-changed", "Order was changed", MedicineRules.ORDER_CHANGED);
    }
}
