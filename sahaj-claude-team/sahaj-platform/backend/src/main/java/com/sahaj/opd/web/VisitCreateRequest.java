package com.sahaj.opd.web;

import com.sahaj.opd.application.VisitCreateInput;
import java.util.UUID;

/** {@code VisitCreate} in {@code docs/api/opd.yaml} (SAH-9); validated by {@code VisitService}. */
public record VisitCreateRequest(UUID patientId, UUID doctorMembershipId) {

    public VisitCreateInput toInput() {
        return new VisitCreateInput(patientId, doctorMembershipId);
    }
}
