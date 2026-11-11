package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.devtools.DevToolsChecks;
import com.sahaj.platform.infrastructure.admin.IpLiterals;
import com.sahaj.platform.web.admin.AdminRequestPaths;
import com.sahaj.platform.web.admin.DevEndpointGate;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The per-request checks G1 to G5 and G7 of ADR-008 D3 and R1. Every check must pass; any doubt (including a
 * runtime error) refuses. A refusal is the empty 404 written by main code, so the caller learns
 * nothing about which check failed. G6 (no admin exists yet) is checked atomically in the database.
 *
 * <ul>
 *   <li>G1 exactly {@code POST /api/v1/admin/dev/sample-admin} (decoded lookup path, case-sensitive);
 *   <li>G2 the servlet remote address is a loopback IP literal;
 *   <li>G3 none of the forwarding headers is present (their values are never read);
 *   <li>G4 {@code Host} is {@code 127.0.0.1}, {@code localhost} or {@code [::1]}, with an optional port;
 *   <li>G5 no {@code Origin} and no {@code Sec-Fetch-*} header (browsers send them; curl does not);
 *   <li>G7 exactly one {@code X-Sahaj-Dev-Nonce} header equal to this start's nonce (constant-time comparison; a
 *       missing, empty, malformed or wrong value is the same empty 404 and is not counted by the rate limit).
 * </ul>
 */
public final class DevSampleAdminGate implements DevEndpointGate {

    public static final String PATH = AdminRequestPaths.BASE + "/dev/sample-admin";

    /** G3: presence of any of these 16 headers (case-insensitive name) means "came through a proxy". */
    static final List<String> FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
            "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Server",
            "X-Original-Forwarded-For", "X-Real-IP", "X-Client-IP", "Client-IP", "True-Client-IP", "CF-Connecting-IP",
            "Fastly-Client-IP", "X-Cluster-Client-IP", "Via");

    private final DevSampleAdminNonce nonce;

    DevSampleAdminGate(DevSampleAdminNonce nonce) {
        this.nonce = nonce;
    }

    /** No value of the nonce is printed. */
    @Override
    public String toString() {
        return "DevSampleAdminGate[redacted]";
    }

    @Override
    public boolean permits(HttpServletRequest request) {
        try {
            return exactEndpoint(request)
                    && loopbackCaller(request)
                    && noForwardingHeader(request)
                    && localHost(request)
                    && noBrowserHeader(request)
                    && validNonce(request);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** G1. */
    static boolean exactEndpoint(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PATH.equals(AdminRequestPaths.lookupPath(request));
    }

    /** G2. {@code getRemoteAddr()} only, parsed as a literal, never resolved. */
    static boolean loopbackCaller(HttpServletRequest request) {
        Optional<InetAddress> address = IpLiterals.parse(request.getRemoteAddr());
        return address.isPresent() && address.get().isLoopbackAddress();
    }

    /** G3. Presence alone means "came through a proxy". */
    static boolean noForwardingHeader(HttpServletRequest request) {
        for (String name : FORWARDING_HEADERS) {
            if (request.getHeader(name) != null) {
                return false;
            }
        }
        return true;
    }

    /** G4. Defeats DNS rebinding: a rebound page sends its own host name. The rule is shared (DevToolsChecks). */
    static boolean localHost(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Host"));
        return values.size() == 1 && DevToolsChecks.localHostValue(values.get(0));
    }

    /** G7. Exactly one header, equal to the nonce of this start. Missing or repeated is a refusal. */
    boolean validNonce(HttpServletRequest request) {
        return nonce.accepts(Collections.list(request.getHeaders(DevSampleAdminNonce.HEADER)));
    }

    /** G5. */
    static boolean noBrowserHeader(HttpServletRequest request) {
        if (request.getHeader("Origin") != null) {
            return false;
        }
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase(Locale.ROOT).startsWith("sec-fetch-")) {
                return false;
            }
        }
        return true;
    }
}
