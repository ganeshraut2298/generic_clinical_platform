package com.sahaj.platform.devtools;

import com.sahaj.platform.infrastructure.admin.IpLiterals;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.core.env.Environment;

/**
 * The static checks that more than one dev-tools feature needs, kept in one place so they are never copied
 * (ADR-018 D2): the profile rule, the proxy rule, the loopback bind rule, the {@code Host} rule and the loopback
 * address rule. The sample admin (ADR-008 C1, C4, C5, G4) and the bootstrap registration (ADR-018 B2, B6, B7, K4)
 * both call these. Plain reads; no value is printed or logged.
 */
public final class DevToolsChecks {

    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private DevToolsChecks() {
    }

    /** Profile {@code dev} is active and every active profile is {@code dev} or {@code test}. */
    public static boolean profilesAllowed(Environment env) {
        boolean dev = false;
        for (String profile : env.getActiveProfiles()) {
            if ("dev".equals(profile)) {
                dev = true;
            } else if (!"test".equals(profile)) {
                return false;
            }
        }
        return dev;
    }

    /**
     * No proxy configuration and no cloud platform. With a forward-headers strategy other than {@code none}, or a
     * remote-IP or protocol header configured, {@code getRemoteAddr()} may come from a header, so the loopback check
     * would mean nothing: fail closed. Spring Boot also turns forward headers on when it detects a cloud platform
     * (Kubernetes, Cloud Foundry, Heroku and others), so {@code spring.main.cloud-platform} must be blank (any value,
     * {@code none} included, refuses) and {@link CloudPlatform#getActive} must find none (ADR-008 R6).
     */
    public static boolean noProxyConfiguration(Environment env) {
        if (!isBlank(env.getProperty("spring.main.cloud-platform")) || CloudPlatform.getActive(env) != null) {
            return false;
        }
        String strategy = env.getProperty("server.forward-headers-strategy");
        if (strategy != null && !strategy.isBlank() && !"none".equalsIgnoreCase(strategy.trim())) {
            return false;
        }
        return isBlank(env.getProperty("server.tomcat.remoteip.remote-ip-header"))
                && isBlank(env.getProperty("server.tomcat.remoteip.protocol-header"));
    }

    /** {@code server.address} is set and is a loopback IP literal (a host name is never resolved). */
    public static boolean bindsToLoopback(Environment env) {
        String address = env.getProperty("server.address");
        if (address == null || address.isBlank()) {
            return false;
        }
        Optional<InetAddress> parsed = IpLiterals.parse(address.toLowerCase(Locale.ROOT));
        return parsed.isPresent() && parsed.get().isLoopbackAddress();
    }

    /**
     * A {@code Host} (or {@code X-Forwarded-Host}) value: {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, in any
     * letter case, with an optional {@code :<port>} of one to five digits. Defeats DNS rebinding: a rebound page
     * sends its own host name.
     */
    public static boolean localHostValue(String value) {
        if (value == null) {
            return false;
        }
        String host = value.trim().toLowerCase(Locale.ROOT);
        String port = null;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return false;
            }
            String rest = host.substring(close + 1);
            host = host.substring(0, close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return false;
                }
                port = rest.substring(1);
            }
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        if (port != null && !PORT.matcher(port).matches()) {
            return false;
        }
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    /** The text is an IP literal (never resolved) and it is a loopback address. */
    public static boolean loopbackLiteral(String text) {
        Optional<InetAddress> address = IpLiterals.parse(text);
        return address.isPresent() && address.get().isLoopbackAddress();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
