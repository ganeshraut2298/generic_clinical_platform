package com.sahaj.platform.web;

/** Response of {@code POST /api/v1/platform/auth/otp/request}. Never contains the code. */
public record OtpRequestResponse(int resendAfterSeconds) {
}
