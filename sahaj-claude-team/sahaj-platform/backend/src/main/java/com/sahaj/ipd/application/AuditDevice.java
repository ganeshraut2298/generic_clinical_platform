package com.sahaj.ipd.application;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * SAH-76 (ADR-027 D11, OQ-76-22): the device label of the current request for audit rows: {@code {id, agent}}. The id is
 * the client's {@code X-Sahaj-Device} (8 to 36 characters of A-Z a-z 0-9 and hyphen), else null; the agent is the
 * user-agent family (Chrome, Edge, Firefox, Safari, Other). No IP address, no serial. A missing or malformed header is
 * never refused. Outside a web request (a job, a test) the label is {@code {id: null, agent: Other}}.
 */
@Component
public class AuditDevice {

    public static final String HEADER = "X-Sahaj-Device";
    /** Request attribute where {@code AuditDeviceFilter} stores the label once per request. */
    public static final String ATTRIBUTE = "com.sahaj.ipd.auditDevice";

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{8,36}$");

    /** The label of the current request. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> label() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            Object stored = servlet.getRequest().getAttribute(ATTRIBUTE);
            if (stored instanceof Map) {
                return (Map<String, Object>) stored;
            }
            return label(servlet.getRequest().getHeader(HEADER), servlet.getRequest().getHeader("User-Agent"));
        }
        return label(null, null);
    }

    /** Pure: builds the label from the two header values (either may be null). */
    public static Map<String, Object> label(String deviceHeader, String userAgent) {
        Map<String, Object> label = new LinkedHashMap<>();
        String id = deviceHeader == null ? null : deviceHeader.trim();
        label.put("id", id != null && ID.matcher(id).matches() ? id : null);
        label.put("agent", agentFamily(userAgent));
        return java.util.Collections.unmodifiableMap(label);
    }

    /** Edge and Opera name themselves before Chrome, Chrome before Safari; anything else is Other. */
    static String agentFamily(String userAgent) {
        if (userAgent == null) {
            return "Other";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("edg/") || ua.contains("edge/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Other";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return "Other";
    }
}
