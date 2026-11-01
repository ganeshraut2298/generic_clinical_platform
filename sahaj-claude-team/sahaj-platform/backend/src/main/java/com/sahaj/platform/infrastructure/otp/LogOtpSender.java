package com.sahaj.platform.infrastructure.otp;

import com.sahaj.platform.application.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * SAH-3: the only permitted place that logs a one-time code, and only in {@code dev} and
 * {@code test}. No bean of this type exists for any other profile and nothing else implements
 * {@link OtpSender}, so the application fails to start in production rather than silently
 * dropping codes (a missing required bean is a Spring Boot start-up failure).
 *
 * <p>The mobile number is still never written in clear, even here: it is masked to its last four
 * digits, the same as everywhere else in the codebase.
 */
@Component
@Profile({"dev", "test"})
public class LogOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LogOtpSender.class);

    @Override
    public void send(String mobile, String code) {
        log.info("One-time code for {}: {} (dev/test only - never logged in any other profile)", mask(mobile), code);
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "••••";
        }
        return "••••••" + mobile.substring(mobile.length() - 4);
    }
}
