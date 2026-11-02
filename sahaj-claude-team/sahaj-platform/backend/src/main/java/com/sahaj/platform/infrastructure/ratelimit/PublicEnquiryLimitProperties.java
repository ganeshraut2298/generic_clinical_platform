package com.sahaj.platform.infrastructure.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limit for the public sales-enquiry endpoint: {@code maxRequests} per client address per
 * {@code window} (SAH-2: 5 per hour). {@code maxTrackedKeys} bounds memory.
 */
@ConfigurationProperties(prefix = "sahaj.public-enquiry-limit")
public record PublicEnquiryLimitProperties(
        @DefaultValue("5") int maxRequests,
        @DefaultValue("1h") Duration window,
        @DefaultValue("100000") int maxTrackedKeys) {
}
