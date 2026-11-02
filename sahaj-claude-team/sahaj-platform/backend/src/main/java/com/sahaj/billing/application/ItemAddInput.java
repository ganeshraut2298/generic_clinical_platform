package com.sahaj.billing.application;

import java.util.UUID;

/** {@code POST /billing/bills/{visitId}/items} body in {@code docs/api/billing.yaml} (SAH-10). */
public record ItemAddInput(UUID serviceId, Integer qty) {
}
