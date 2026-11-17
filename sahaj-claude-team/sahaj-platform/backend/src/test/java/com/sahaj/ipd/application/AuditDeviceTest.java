package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditDeviceTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String EDGE = CHROME + " Edg/120.0";
    private static final String FIREFOX = "Mozilla/5.0 (Windows NT 10.0; rv:120.0) Gecko/20100101 Firefox/120.0";
    private static final String SAFARI =
            "Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";

    @Test
    void validIdIsKeptAndFamilyIsNamed() {
        Map<String, Object> label = AuditDevice.label("abc12345-DEF", CHROME);
        assertThat(label).containsEntry("id", "abc12345-DEF").containsEntry("agent", "Chrome");
    }

    @Test
    void familiesAreTold() {
        assertThat(AuditDevice.label(null, EDGE)).containsEntry("agent", "Edge");
        assertThat(AuditDevice.label(null, FIREFOX)).containsEntry("agent", "Firefox");
        assertThat(AuditDevice.label(null, SAFARI)).containsEntry("agent", "Safari");
        assertThat(AuditDevice.label(null, "curl/8.0")).containsEntry("agent", "Other");
        assertThat(AuditDevice.label(null, null)).containsEntry("agent", "Other");
    }

    @Test
    void missingOrMalformedIdIsNullNeverRefused() {
        assertThat(AuditDevice.label(null, CHROME).get("id")).isNull();
        assertThat(AuditDevice.label("short", CHROME).get("id")).isNull();
        assertThat(AuditDevice.label("has space in it!", CHROME).get("id")).isNull();
        assertThat(AuditDevice.label("a".repeat(37), CHROME).get("id")).isNull();
        assertThat(AuditDevice.label("a".repeat(36), CHROME).get("id")).isEqualTo("a".repeat(36));
    }

    @Test
    void currentRequestIsRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuditDevice.HEADER, "device-0001");
        request.addHeader("User-Agent", FIREFOX);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(new AuditDevice().label()).containsEntry("id", "device-0001").containsEntry("agent", "Firefox");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void noRequestGivesNullIdAndOther() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(new AuditDevice().label()).containsEntry("agent", "Other");
        assertThat(new AuditDevice().label().get("id")).isNull();
    }
}
