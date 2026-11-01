package com.sahaj.platform.infrastructure.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fails startup rather than run with a reverse-proxy header configuration that would let any
 * client forge the address the rate limiter and the audit trail see.
 *
 * <p>{@code server.tomcat.remoteip.remote-ip-header} (from {@code SAHAJ_REMOTE_IP_HEADER}) is only
 * safe when {@code server.tomcat.remoteip.internal-proxies} (from {@code SAHAJ_TRUSTED_PROXIES})
 * names the actual reverse proxy addresses: a blank value, or one broad enough to match an
 * arbitrary public address, trusts the header from anyone. The default of both properties stays
 * empty ({@code ${SAHAJ_TRUSTED_PROXIES:}}), so the header is ignored unless both are set.
 */
@Configuration(proxyBeanMethods = false)
public class TrustedProxyStartupCheck {

    /** Addresses that must never match a well-formed "trusted proxies" pattern. */
    private static final List<String> PUBLIC_PROBE_ADDRESSES =
            List.of("8.8.8.8", "203.0.113.7", "2001:4860:4860::8888");

    @Bean
    InitializingBean trustedProxyConfigurationIsNotTooPermissive(
            @Value("${server.tomcat.remoteip.remote-ip-header:}") String remoteIpHeader,
            @Value("${server.tomcat.remoteip.internal-proxies:}") String trustedProxies) {
        return () -> validate(remoteIpHeader, trustedProxies);
    }

    static void validate(String remoteIpHeader, String trustedProxies) {
        if (remoteIpHeader == null || remoteIpHeader.isBlank()) {
            return;
        }
        if (trustedProxies == null || trustedProxies.isBlank()) {
            throw new IllegalStateException(
                    "SAHAJ_REMOTE_IP_HEADER is set but SAHAJ_TRUSTED_PROXIES is blank. "
                    + "That would let any client's forwarding header change the address the rate "
                    + "limiter and the audit trail see. Set SAHAJ_TRUSTED_PROXIES to the known "
                    + "reverse proxy addresses, or leave SAHAJ_REMOTE_IP_HEADER unset.");
        }
        Pattern pattern = Pattern.compile(trustedProxies);
        for (String probe : PUBLIC_PROBE_ADDRESSES) {
            if (pattern.matcher(probe).matches()) {
                throw new IllegalStateException(
                        "SAHAJ_TRUSTED_PROXIES (" + trustedProxies + ") matches an arbitrary public "
                        + "address. Restrict it to the known reverse proxy addresses only.");
            }
        }
    }
}
