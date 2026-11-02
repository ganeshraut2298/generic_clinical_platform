package com.sahaj.opd.web;

import com.sahaj.opd.application.ServiceView;
import java.util.UUID;

/** {@code Service} in {@code docs/api/opd.yaml}: the price in whole rupees. */
public record ServiceResponse(UUID id, String name, String category, long priceRupees, boolean active) {

    public static ServiceResponse from(ServiceView view) {
        return new ServiceResponse(view.id(), view.name(), view.category(), view.priceRupees(), view.active());
    }
}
