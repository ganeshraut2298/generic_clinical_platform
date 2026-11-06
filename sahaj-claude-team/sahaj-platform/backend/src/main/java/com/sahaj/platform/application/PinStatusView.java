package com.sahaj.platform.application;

import com.sahaj.platform.domain.PinState;
import java.time.Instant;

/**
 * {@code PinStatus} in {@code docs/api/platform.yaml} (SAH-31): the signed-in person's own PIN status. The mobile is
 * already masked here. Never holds a PIN or a hash.
 */
public record PinStatusView(boolean available, PinState state, Instant setAt, Instant lockedUntil, Integer triesLeft,
                            String notSetReason, String mobileMasked) {
}
