package com.sahaj.billing.web;

import com.sahaj.billing.application.BillItemView;
import java.util.UUID;

/** {@code BillItem} in {@code docs/api/billing.yaml} (SAH-10). */
public record BillItemResponse(UUID id, String name, long amountPaise, int qty, boolean removable, String source) {

    public BillItemResponse(UUID id, String name, long amountPaise, int qty, boolean removable) {
        this(id, name, amountPaise, qty, removable, "COUNTER");
    }

    public static BillItemResponse from(BillItemView view) {
        return new BillItemResponse(view.id(), view.name(), view.amountPaise(), view.qty(), view.removable(),
                view.source());
    }
}
