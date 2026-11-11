package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.devtools.DevToolsChecks;
import com.sahaj.platform.web.admin.AdminRequestPaths;
import com.sahaj.platform.web.admin.DevEndpointGate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * The per-request checks K1 to K6 of ADR-018 D2 (K7, "no platform admin exists", is checked in the database on every
 * request). Every check must pass; any doubt, including a runtime error, refuses. A refusal is the empty 404 written by
 * main code, so the caller learns nothing about which check failed. It refuses every path it does not own.
 *
 * <ul>
 *   <li>K1 exactly {@code POST /api/v1/admin/bootstrap/unlock} or {@code .../register} (decoded lookup path,
 *       case-sensitive); any other method is a refusal, never 405, and {@code OPTIONS} is one too;
 *   <li>K2 the servlet remote address is a loopback IP literal;
 *   <li>K3 forwarding headers may only narrow, never widen: only the four allowed ones, each at most once, and every
 *       {@code X-Forwarded-For} element is an IP literal that is loopback. The values are checked, never used as the
 *       caller's address;
 *   <li>K4 {@code Host} is {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, with an optional port;
 *   <li>K5 {@code Origin} absent or exactly {@code http(s)://} plus a K4 host; {@code Sec-Fetch-Site} absent or
 *       {@code same-origin};
 *   <li>K6 {@code Content-Type} is {@code application/json} (parameters allowed).
 * </ul>
 */
public final class BootstrapGate implements DevEndpointGate {

    public static final String BASE = AdminRequestPaths.BASE + "/bootstrap";
    public static final String UNLOCK_PATH = BASE + "/unlock";
    public static final String REGISTER_PATH = BASE + "/register";

    /** K3: these headers are refused whenever they are present (ADR-008 G3 minus the four that may narrow). */
    static final List<String> REFUSED_FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-Prefix",
            "X-Forwarded-Server", "X-Original-Forwarded-For", "X-Real-IP", "X-Client-IP", "Client-IP",
            "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP", "X-Cluster-Client-IP", "Via");

    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    @Override
    public String toString() {
        return "BootstrapGate[]";
    }

    @Override
    public boolean permits(HttpServletRequest request) {
        try {
            return exactEndpoint(request)
                    && loopbackCaller(request)
                    && forwardingOnlyNarrows(request)
                    && localHost(request)
                    && sameOriginOrNone(request)
                    && jsonContent(request);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** K1. */
    static boolean exactEndpoint(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = AdminRequestPaths.lookupPath(request);
        return UNLOCK_PATH.equals(path) || REGISTER_PATH.equals(path);
    }

    /** K2. {@code getRemoteAddr()} only, parsed as a literal, never resolved. */
    static boolean loopbackCaller(HttpServletRequest request) {
        return DevToolsChecks.loopbackLiteral(request.getRemoteAddr());
    }

    /** K3. */
    static boolean forwardingOnlyNarrows(HttpServletRequest request) {
        for (String name : REFUSED_FORWARDING_HEADERS) {
            if (request.getHeader(name) != null) {
                return false;
            }
        }
        List<String> forwardedFor = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (forwardedFor.size() > 1) {
            return false;
        }
        if (forwardedFor.size() == 1 && !everyElementLoopback(forwardedFor.get(0))) {
            return false;
        }
        List<String> host = Collections.list(request.getHeaders("X-Forwarded-Host"));
        if (host.size() > 1 || (host.size() == 1 && !DevToolsChecks.localHostValue(host.get(0)))) {
            return false;
        }
        List<String> proto = Collections.list(request.getHeaders("X-Forwarded-Proto"));
        if (proto.size() > 1 || (proto.size() == 1 && !isHttpOrHttps(proto.get(0)))) {
            return false;
        }
        List<String> port = Collections.list(request.getHeaders("X-Forwarded-Port"));
        return port.size() <= 1 && (port.isEmpty() || PORT.matcher(port.get(0).trim()).matches());
    }

    /** Every comma-separated element is a bare IP literal (no port, no name, not empty) and a loopback address. */
    static boolean everyElementLoopback(String value) {
        String[] elements = value.split(",", -1);
        for (String raw : elements) {
            String element = raw.trim();
            if (element.isEmpty() || element.indexOf('%') >= 0) {
                return false;
            }
            boolean literal = IPV4.matcher(element).matches() || IPV6.matcher(element).matches();
            if (!literal || !DevToolsChecks.loopbackLiteral(element)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHttpOrHttps(String value) {
        String proto = value.trim().toLowerCase(Locale.ROOT);
        return proto.equals("http") || proto.equals("https");
    }

    /** K4. */
    static boolean localHost(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Host"));
        return values.size() == 1 && DevToolsChecks.localHostValue(values.get(0));
    }

    /** K5. A script on another site sends its own Origin; curl sends none. */
    static boolean sameOriginOrNone(HttpServletRequest request) {
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() > 1) {
            return false;
        }
        if (origins.size() == 1 && !localOrigin(origins.get(0))) {
            return false;
        }
        List<String> fetchSite = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        return fetchSite.size() <= 1 && (fetchSite.isEmpty() || "same-origin".equals(fetchSite.get(0)));
    }

    /** Exactly {@code http://} or {@code https://} followed by a K4 host, and nothing after it. */
    static boolean localOrigin(String origin) {
        String lower = origin.toLowerCase(Locale.ROOT);
        String authority;
        if (lower.startsWith("http://")) {
            authority = origin.substring("http://".length());
        } else if (lower.startsWith("https://")) {
            authority = origin.substring("https://".length());
        } else {
            return false;
        }
        if (authority.isEmpty() || authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0
                || authority.indexOf('#') >= 0 || authority.indexOf('@') >= 0 || !authority.equals(authority.trim())) {
            return false;
        }
        return DevToolsChecks.localHostValue(authority);
    }

    /** K6. */
    static boolean jsonContent(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Content-Type"));
        if (values.size() != 1) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(values.get(0));
            return "application".equals(type.getType()) && "json".equals(type.getSubtype());
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }
}
