package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.sahaj.platform.support.LogCapture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Release audit G-4 (DEC-025, SAH-35/36/38; security review S36-1): the request log. Every {@code /api/**} request writes
 * exactly one INFO line on the logger {@code sahaj.access} after it ends:
 * {@code request method=.. path=<template> status=.. durationMs=.. tenantId=.. userId=.. requestId=..}. The path is the
 * template Spring MVC matched ({@code {id}}, never the real id), {@code unmatched} when no handler was reached; there is no
 * query string, header, body, name, mobile number or PIN in it. Signed-in calls carry the caller's clinic and user id, a call
 * without a session carries {@code -}; the request id equals the {@code X-Request-Id} header of the answer. Test plan:
 * docs/test-plans/SAH-35-36-38-audit.md.
 */
@OnTestDatabase
@WithPinPepper
class ApiAccessLogIT extends RoundSupport {

    private static final String ACCESS_LOGGER = "sahaj.access";
    private static final Pattern LINE = Pattern.compile("^request method=(\\S+) path=(\\S+) status=(\\d+) durationMs=(\\d+) "
            + "tenantId=(\\S+) userId=(\\S+) requestId=(\\S+)$");

    private record Line(String method, String path, int status, long durationMs, String tenantId, String userId,
                        String requestId, String raw) {
    }

    private static List<ILoggingEvent> accessEvents(LogCapture logs) {
        return logs.events().stream().filter(e -> ACCESS_LOGGER.equals(e.getLoggerName())).toList();
    }

    /** The one access line written during the capture; fails on zero or on two. */
    private static Line theOnlyLine(LogCapture logs) {
        List<ILoggingEvent> events = accessEvents(logs);
        assertThat(events).as("exactly one sahaj.access line per request").hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        String text = events.get(0).getFormattedMessage();
        Matcher m = LINE.matcher(text);
        assertThat(m.matches()).as("shape of the line: " + text).isTrue();
        return new Line(m.group(1), m.group(2), Integer.parseInt(m.group(3)), Long.parseLong(m.group(4)), m.group(5), m.group(6),
                m.group(7), text);
    }

    private record Fix(Ward w, String hep) {
    }

    private Fix fix() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        return new Fix(w, hep);
    }

    private static void assertNoPersonalText(String line, Ward w) {
        assertThat(line).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain(w.uhid())
                .doesNotContain(w.adm().id()).doesNotContain("?").doesNotContain("=Asha").doesNotContain("9000000001");
    }

    // ---------------- AC: one INFO line, template path, status, duration, caller ----------------

    @Test
    void aSignedInReadWritesOneInfoLineWithThePathTemplateStatusDurationAndTheCallersTenantUserAndRequestId() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        MvcResult result;
        Line line;
        try (LogCapture logs = new LogCapture()) {
            result = mvc.perform(get(marUrl(w.adm().id())).with(w.nurse().auth())).andReturn();
            line = theOnlyLine(logs);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(line.method()).isEqualTo("GET");
        assertThat(line.path()).as("the template, not the admission id").isEqualTo("/api/v1/ipd/admissions/{id}/mar");
        assertThat(line.status()).isEqualTo(200);
        assertThat(line.durationMs()).isBetween(0L, 60_000L);
        assertThat(line.tenantId()).isEqualTo(w.clinic().tenantId().toString());
        assertThat(line.userId()).isEqualTo(w.nurse().userId().toString());
        String header = result.getResponse().getHeader("X-Request-Id");
        assertThat(header).isNotBlank();
        assertThat(line.requestId()).isEqualTo(header);
        assertThat(UUID.fromString(line.requestId())).isNotNull();
        assertNoPersonalText(line.raw(), w);
    }

    @Test
    void everyRequestHasItsOwnRequestIdAndTheLineMatchesTheHeaderOfItsOwnAnswer() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String first;
        String second;
        try (LogCapture logs = new LogCapture()) {
            MvcResult a = mvc.perform(get(marUrl(w.adm().id())).with(w.nurse().auth())).andReturn();
            MvcResult b = mvc.perform(get(marUrl(w.adm().id())).with(w.doctor().auth())).andReturn();
            List<ILoggingEvent> events = accessEvents(logs);
            assertThat(events).hasSize(2);
            first = events.get(0).getFormattedMessage();
            second = events.get(1).getFormattedMessage();
            assertThat(first).endsWith("requestId=" + a.getResponse().getHeader("X-Request-Id"))
                    .contains("userId=" + w.nurse().userId());
            assertThat(second).endsWith("requestId=" + b.getResponse().getHeader("X-Request-Id"))
                    .contains("userId=" + w.doctor().userId());
        }
        assertThat(first.substring(first.indexOf("requestId="))).isNotEqualTo(second.substring(second.indexOf("requestId=")));
    }

    // ---------------- AC: no query values, no body, no names, no PIN ----------------

    @Test
    void aQueryStringWithANameAndAMobileNumberNeverReachesTheLine() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        for (String url : new String[] {marUrl(w.adm().id()) + "?name=Asha&mobile=9000000001",
            ADMITTED_LIST + "?status=ADMITTED&name=Asha&mobile=9000000001"}) {
            try (LogCapture logs = new LogCapture()) {
                mvc.perform(get(java.net.URI.create(url)).with(w.nurse().auth())).andReturn();
                Line line = theOnlyLine(logs);
                assertThat(line.path()).startsWith("/api/v1/ipd/admissions");
                assertThat(line.raw()).doesNotContain("Asha").doesNotContain("9000000001").doesNotContain("mobile=")
                        .doesNotContain("name=").doesNotContain("status=ADMITTED").doesNotContain("?");
                assertThat(line.status()).isEqualTo(200);
            }
        }
    }

    @Test
    void aGiveCallWithAPinAWristbandAndAPatientNameInItsBodyWritesAnAccessLineWithNoneOfThem() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String wrongPin = "9306".equals(w.nurse2Pin()) ? "9307" : "9306";
        // a wrong PIN (403) and then the right one (201): one line each, no PIN, no wristband, no second nurse
        for (Object[] attempt : new Object[][] {{wrongPin, 403}, {w.nurse2Pin(), 201}}) {
            String pin = (String) attempt[0];
            try (LogCapture logs = new LogCapture()) {
                MvcResult r = doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), pin)).andReturn();
                assertThat(r.getResponse().getStatus()).isEqualTo((int) (Integer) attempt[1]);
                Line line = theOnlyLine(logs);
                assertThat(line.method()).isEqualTo("POST");
                assertThat(line.path()).isEqualTo("/api/v1/ipd/medicine-orders/{id}/doses");
                assertThat(line.status()).isEqualTo((int) (Integer) attempt[1]);
                assertThat(line.tenantId()).isEqualTo(w.clinic().tenantId().toString());
                assertThat(line.userId()).isEqualTo(w.nurse().userId().toString());
                assertThat(hasBarePin(line.raw(), pin)).as("no PIN in the line").isFalse();
                assertThat(line.raw()).doesNotContain(f.hep()).doesNotContain(w.nurse2().membershipId().toString())
                        .doesNotContain(w.nurse2().userId().toString()).doesNotContain("pin").doesNotContain("Pin")
                        .doesNotContain("wristband");
                assertNoPersonalText(line.raw(), w);
            }
        }
    }

    // ---------------- AC: no session, unknown path, other clinic, failures ----------------

    @Test
    void aCallWithoutASessionIsLoggedAsUnmatchedWith401AndDashesForTenantAndUser() throws Exception {
        MvcResult result;
        Line line;
        try (LogCapture logs = new LogCapture()) {
            result = mvc.perform(get(ADMITTED_LIST + "?name=Asha&mobile=9000000001")).andReturn();
            line = theOnlyLine(logs);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(line.path()).isEqualTo("unmatched");
        assertThat(line.status()).isEqualTo(401);
        assertThat(line.tenantId()).isEqualTo("-");
        assertThat(line.userId()).isEqualTo("-");
        assertThat(line.requestId()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(line.raw()).doesNotContain("Asha").doesNotContain("9000000001");
    }

    @Test
    void anUnknownPathIsLoggedAsUnmatchedAndNeverWithTheCallersText() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String hostile = "/api/v1/ipd/no-such-thing/Asha-Rane-9000000001";
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(get(java.net.URI.create(hostile)).with(w.nurse().auth())).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            // a signed-in 404 reaches Spring's static-resource fallback, whose pattern is "/**"; the filter maps that catch-all
            // to "unmatched" (code review #5), so the line shows neither "/**" nor the caller's path.
            assertThat(line.path()).isEqualTo("unmatched").isNotEqualTo("/**");
            assertThat(line.raw()).doesNotContain("/**");
            assertThat(line.status()).isEqualTo(404);
            assertThat(line.tenantId()).isEqualTo(w.clinic().tenantId().toString());
            assertThat(line.raw()).doesNotContain("Asha").doesNotContain("9000000001").doesNotContain("no-such-thing");
        }
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(get(java.net.URI.create(hostile))).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(line.status()).isEqualTo(401);
            assertThat(line.path()).isEqualTo("unmatched");
            assertThat(line.raw()).doesNotContain("Asha").doesNotContain("9000000001");
        }
    }

    @Test
    void anotherClinicsCallToThisClinicsPatientIs404AndItsLineNamesOnlyTheCallersClinic() throws Exception {
        Fix f = fix();
        Ward mine = f.w();
        Ward theirs = ward();
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(get(marUrl(mine.adm().id())).with(theirs.nurse().auth())).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).as("another clinic's admission is invisible").isEqualTo(404);
            assertThat(line.status()).isEqualTo(404);
            assertThat(line.path()).isEqualTo("/api/v1/ipd/admissions/{id}/mar");
            assertThat(line.tenantId()).isEqualTo(theirs.clinic().tenantId().toString());
            assertThat(line.userId()).isEqualTo(theirs.nurse().userId().toString());
            assertThat(line.raw()).doesNotContain(mine.clinic().tenantId().toString()).doesNotContain(mine.adm().id())
                    .doesNotContain(mine.nurse().userId().toString());
        }
    }

    @Test
    void aMalformedIdIsLoggedWithTheTemplateAndNeverWithTheBadText() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(get(java.net.URI.create(ADMITTED_LIST + "/not-a-uuid-Asha/mar")).with(w.nurse().auth()))
                    .andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(line.status()).isEqualTo(400);
            assertThat(line.path()).isEqualTo("/api/v1/ipd/admissions/{id}/mar");
            assertThat(line.raw()).doesNotContain("not-a-uuid").doesNotContain("Asha");
        }
    }

    @Test
    void anOversizedBodyIsLoggedAs413WithTheCallersIdsAndNoBody() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        byte[] big = ("{\"pad\":\"Asha" + "a".repeat(65_540) + "\"}").getBytes(StandardCharsets.UTF_8);
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(post(dosesUrl(f.hep())).with(w.nurse().auth()).contentType(MediaType.APPLICATION_JSON)
                    .content(big)).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(413);
            assertThat(line.status()).isEqualTo(413);
            assertThat(line.path()).as("refused before any handler").isEqualTo("unmatched");
            assertThat(line.tenantId()).isEqualTo(w.clinic().tenantId().toString());
            assertThat(line.userId()).isEqualTo(w.nurse().userId().toString());
            assertThat(line.requestId()).isEqualTo(r.getResponse().getHeader("X-Request-Id"));
            assertThat(line.raw()).doesNotContain("Asha").doesNotContain("aaaa");
        }
    }

    @Test
    void anUnexpectedServerErrorIsStillOneLineWith500AndNoSecretFromTheException() throws Exception {
        Ward w = ward();
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(get("/api/v1/platform/test-failures/boom").with(w.clinic().owner())).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(500);
            assertThat(line.status()).isEqualTo(500);
            assertThat(line.path()).isEqualTo("/api/v1/platform/test-failures/boom");
            assertThat(line.raw()).doesNotContain(com.sahaj.platform.support.FailureProbeController.SECRET_IN_MESSAGE);
        }
    }

    @Test
    void aPathOutsideApiWritesNoAccessLine() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(get("/not-the-api")).andReturn();
            assertThat(accessEvents(logs)).isEmpty();
        }
    }
}
