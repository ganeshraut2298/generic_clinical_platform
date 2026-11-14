package com.sahaj.opd.application.emr.finish;

import com.sahaj.opd.api.EmrAdmissionRequestPort;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RequestRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-3 (ADR-025 D10): implements {@link EmrAdmissionRequestPort} so {@code ipd} (which already depends on
 * {@code opd.api}) can list the doctors' open requests and close them in its admission transaction. Lock order is ipd's:
 * the IPD lock first, then these rows. The diagnoses text and the note are clinical: screen only, never logged.
 */
@Component
class EmrAdmissionRequestPortAdapter implements EmrAdmissionRequestPort {

    private final EmrFinishRepository finish;

    EmrAdmissionRequestPortAdapter(EmrFinishRepository finish) {
        this.finish = finish;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenRequest> listOpen(UUID tenantId) {
        return finish.listOpenRequests(tenantId).stream().map(EmrAdmissionRequestPortAdapter::open).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpenRequest> lockOpen(UUID tenantId, UUID requestId) {
        return finish.lockOpenRequest(tenantId, requestId).map(EmrAdmissionRequestPortAdapter::open);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAdmitted(UUID tenantId, UUID actorUserId, AdmittedFacts facts) {
        List<RequestRow> open = finish.openRequestsOf(tenantId, facts.patientId(), facts.emergencyCaseId());
        for (RequestRow request : open) {
            finish.markRequestAdmitted(tenantId, request.id(), facts.admissionId(), actorUserId);
        }
        if (facts.admissionRequestId() != null && open.stream().noneMatch(r -> r.id().equals(facts.admissionRequestId()))) {
            finish.markRequestAdmitted(tenantId, facts.admissionRequestId(), facts.admissionId(), actorUserId);
        }
        if (facts.emergencyCaseId() != null && facts.patientId() != null) {
            finish.erRecordOfCase(tenantId, facts.emergencyCaseId())
                    .ifPresent(recordId -> finish.linkPatient(tenantId, recordId, facts.patientId()));
        }
    }

    private static OpenRequest open(RequestRow row) {
        return new OpenRequest(row.id(), row.recordId(), row.patientId(), row.erCaseId(), row.admissionType(),
                row.plannedStayDays(), row.requestedByMembershipId(), row.reasonText(), row.procedureText(), row.note(),
                row.createdAt());
    }
}
