package com.sahaj.platform.application;

/** {@code PinCodeSent} in {@code docs/api/platform.yaml} (SAH-31): the wait before a new code and where it went. */
public record PinCodeSentView(int resendAfterSeconds, String mobileMasked) {
}
