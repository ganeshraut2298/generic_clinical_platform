package com.sahaj.opd.web;

import com.sahaj.opd.application.ServiceInput;

/** {@code Service} on POST and {@code ServiceUpdate} on PATCH in {@code docs/api/opd.yaml}; on PATCH only the fields sent change. */
public record ServiceRequest(String name, String category, Integer priceRupees, Boolean active) {

    public ServiceInput toInput() {
        return new ServiceInput(name, category, priceRupees, active);
    }
}
