package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.opd.application.MedicineRepository.MedicineRow;

/** {@code Medicine} in {@code docs/api/opd.yaml}. {@code favourite} is true for this clinic only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicineResponse(java.util.UUID id, String name, String form, String schedule, String usualDose,
                                boolean favourite) {

    public static MedicineResponse from(MedicineRow row) {
        return new MedicineResponse(row.id(), row.name(), row.form(), row.schedule(), row.usualDose(),
                row.favourite());
    }
}
