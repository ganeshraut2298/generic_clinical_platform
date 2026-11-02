package com.sahaj.patient.web;

import com.sahaj.platform.api.InvalidInputException;
import java.util.UUID;

/** {@code POST /patients/merge} body in {@code docs/api/patient.yaml}: {@code keepId} survives, {@code mergeId} is soft-merged away. */
public record MergeRequest(UUID keepId, UUID mergeId) {

    public UUID requireKeepId() {
        if (keepId == null) {
            throw new InvalidInputException("keepId", "Choose the patient record to keep");
        }
        return keepId;
    }

    public UUID requireMergeId() {
        if (mergeId == null) {
            throw new InvalidInputException("mergeId", "Choose the patient record to merge");
        }
        return mergeId;
    }
}
