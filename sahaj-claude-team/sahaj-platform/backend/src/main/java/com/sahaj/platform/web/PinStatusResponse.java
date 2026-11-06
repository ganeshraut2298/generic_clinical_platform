package com.sahaj.platform.web;

import com.sahaj.platform.application.PinStatusView;
import java.time.Instant;

/**
 * {@code PinStatus} in {@code docs/api/platform.yaml} (SAH-31). Every field is always present (null where the
 * contract allows it). Never carries the PIN, the hash or the full mobile number.
 */
public record PinStatusResponse(boolean available, String state, Instant setAt, Instant lockedUntil,
                                Integer triesLeft, String notSetReason, String mobileMasked) {

    public static PinStatusResponse from(PinStatusView view) {
        return new PinStatusResponse(view.available(), view.state().name(), view.setAt(), view.lockedUntil(),
                view.triesLeft(), view.notSetReason(), view.mobileMasked());
    }
}
