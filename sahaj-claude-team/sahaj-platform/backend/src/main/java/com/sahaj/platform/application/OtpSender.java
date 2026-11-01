package com.sahaj.platform.application;

/**
 * Delivers a one-time code to a mobile number (SAH-3). Phase 1 has no real SMS/WhatsApp gateway
 * (out of scope); the only implementation is {@code LogOtpSender}, active in the {@code dev} and
 * {@code test} profiles only. No bean is registered for any other profile on purpose, so the
 * application fails to start in production rather than silently dropping codes.
 */
public interface OtpSender {

    void send(String mobile, String code);
}
