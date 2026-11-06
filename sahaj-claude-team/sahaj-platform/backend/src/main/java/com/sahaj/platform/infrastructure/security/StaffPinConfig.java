package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.StaffPinHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the staff PIN hasher from {@code SAHAJ_PIN_PEPPER} (SAH-31, ADR-010 D3). The value comes from the
 * environment only: there is no default in any profile and no value in code or in the configuration files.
 *
 * <ul>
 *   <li><b>Missing or blank:</b> the application starts (a clinic that only runs OPD needs no PIN), logs one WARN
 *       line without any value, and every PIN operation fails closed with 503 {@code pin-unavailable}.</li>
 *   <li><b>Present but invalid</b> (not Base64, fewer than 32 bytes, or equal to {@code SAHAJ_JWT_SECRET}): the
 *       application refuses to start, because a wrong key would make every PIN fail in a way that looks like wrong
 *       tries.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class StaffPinConfig {

    private static final Logger log = LoggerFactory.getLogger(StaffPinConfig.class);

    @Bean
    StaffPinHasher staffPinHasher(@Value("${sahaj.pin.pepper:}") String pepper, JwtProperties jwtProperties) {
        StaffPinHasher hasher = StaffPinHasher.create(pepper, jwtProperties.secret());
        if (!hasher.isAvailable()) {
            log.warn("Staff PIN checks are off: SAHAJ_PIN_PEPPER is not set");
        }
        return hasher;
    }
}
