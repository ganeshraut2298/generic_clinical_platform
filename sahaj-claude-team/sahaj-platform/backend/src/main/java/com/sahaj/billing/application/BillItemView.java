package com.sahaj.billing.application;

import java.util.UUID;

/** {@code BillItem} in {@code docs/api/billing.yaml} (SAH-10). {@code removable} is false only for the consultation line. */
public record BillItemView(UUID id, String name, long amountPaise, int qty, boolean removable) {
}
