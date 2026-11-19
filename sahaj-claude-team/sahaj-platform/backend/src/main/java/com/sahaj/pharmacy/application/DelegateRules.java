package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.LocalDate;
import java.util.List;

/** The date rules of a delegate approver (docs/api/pharmacy.yaml DelegateInput; OQ-78-15, DEC-064 R2-9). */
final class DelegateRules {

    static final String SAME_AS_HOD = "The delegate must be a different person from the approver";
    static final String BAD_APPROVER = "Choose an active owner, doctor, nurse or pharmacist";

    private DelegateRules() {
    }

    /**
     * Adds a field error for each broken date rule (start date missing, end date missing, end before start, more than 90
     * calendar days counting both ends (DEC-068 Q1), end date in the past). Field names are passed in because the store body and the mapping body name them
     * differently. Texts are fixed; no submitted value is echoed.
     */
    static void check(LocalDate from, LocalDate to, LocalDate today, String fromField, String toField,
                      List<FieldMessage> errors) {
        if (from == null) {
            errors.add(new FieldMessage(fromField, "Enter the start date"));
        }
        if (to == null) {
            errors.add(new FieldMessage(toField, "Enter the end date"));
        }
        if (from == null || to == null) {
            return;
        }
        if (to.isBefore(from)) {
            errors.add(new FieldMessage(toField, "The end date must be on or after the start date"));
        } else if (DelegateState.tooLong(from, to)) {
            errors.add(new FieldMessage(toField, "A delegation can be at most 90 days"));
        } else if (to.isBefore(today)) {
            errors.add(new FieldMessage(toField, "The end date cannot be in the past"));
        }
    }
}
