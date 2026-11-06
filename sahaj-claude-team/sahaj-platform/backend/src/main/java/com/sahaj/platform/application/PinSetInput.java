package com.sahaj.platform.application;

/**
 * The body of {@code PUT /me/pin}: the one-time code and the new PIN twice. Holds secrets while it lives, so
 * {@link #toString()} prints nothing of them.
 */
public record PinSetInput(String code, String newPin, String confirmPin) {

    @Override
    public String toString() {
        return "PinSetInput[redacted]";
    }
}
