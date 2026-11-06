package com.sahaj.platform.web;

import com.sahaj.platform.application.PinCodeSentView;

/** {@code PinCodeSent} in {@code docs/api/platform.yaml} (SAH-31): the wait before a new code and the masked number. */
public record PinCodeSentResponse(int resendAfterSeconds, String mobileMasked) {

    public static PinCodeSentResponse from(PinCodeSentView view) {
        return new PinCodeSentResponse(view.resendAfterSeconds(), view.mobileMasked());
    }
}
