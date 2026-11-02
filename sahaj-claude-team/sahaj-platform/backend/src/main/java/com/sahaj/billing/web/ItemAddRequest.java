package com.sahaj.billing.web;

import com.sahaj.billing.application.ItemAddInput;
import java.util.UUID;

/** {@code POST /billing/bills/{visitId}/items} body in {@code docs/api/billing.yaml} (SAH-10). */
public record ItemAddRequest(UUID serviceId, Integer qty) {

    public ItemAddInput toInput() {
        return new ItemAddInput(serviceId, qty);
    }
}
