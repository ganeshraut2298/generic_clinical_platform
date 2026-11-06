package com.sahaj.platform.web;

import com.sahaj.platform.application.PinSetInput;

/**
 * {@code PinSetInput} in {@code docs/api/platform.yaml} (SAH-31). No bean validation here on purpose: the service
 * checks every field and answers all field errors together, in the order code, newPin, confirmPin. The values are
 * secrets, so {@link #toString()} prints nothing of them.
 */
public record PinSetRequest(String code, String newPin, String confirmPin) {

    PinSetInput toInput() {
        return new PinSetInput(code, newPin, confirmPin);
    }

    @Override
    public String toString() {
        return "PinSetRequest[redacted]";
    }
}
