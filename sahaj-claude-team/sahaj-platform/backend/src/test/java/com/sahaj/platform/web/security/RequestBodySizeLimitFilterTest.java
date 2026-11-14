package com.sahaj.platform.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter =
            new RequestBodySizeLimitFilter(new ProblemJsonWriter(new ObjectMapper()));

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static void signIn() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "user", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    @Test
    void ignoresPathsOutsideTheApi() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void guardsThePublicPrefix() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void rejectsADeclaredContentLengthOverTheLimitWithoutRunningTheChain() throws Exception {
        byte[] body = "a".repeat((int) RequestBodySizeLimitFilter.MAX_BODY_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        // setContent also fixes the declared Content-Length, which the filter checks first.
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("the chain must not run for an oversized body");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void letsAnOrdinarySmallBodyThrough() throws Exception {
        byte[] body = "{\"name\":\"Asha\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[][] seenByChain = new byte[1][];
        FilterChain chain = (req, res) -> seenByChain[0] = StreamUtils.copyToByteArray(req.getInputStream());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenByChain[0]).isEqualTo(body);
    }

    // ---- release audit G-1: signed-in endpoints ----

    private static final long SIGNED_IN_LIMIT = 64 * 1024;

    private static MockHttpServletRequest postOf(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent("a".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static FilterChain mustNotRun() {
        return (req, res) -> {
            throw new AssertionError("the chain must not run for an oversized body");
        };
    }

    @Test
    void guardsEveryApiPathButNotOtherPaths() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/ipd/admissions/x/doses")))
                .isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/actuator/health"))).isTrue();
    }

    @Test
    void aSignedInBodyOfExactlyTheLimitGoesThrough() throws Exception {
        signIn();
        MockHttpServletRequest request = postOf("/api/v1/ipd/doses", (int) SIGNED_IN_LIMIT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] seen = new int[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = StreamUtils.copyToByteArray(req.getInputStream()).length);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen[0]).isEqualTo((int) SIGNED_IN_LIMIT);
    }

    @Test
    void aSignedInBodyOverTheLimitAnswers413ProblemJsonByDeclaredLength() throws Exception {
        signIn();
        MockHttpServletRequest request = postOf("/api/v1/ipd/doses", (int) SIGNED_IN_LIMIT + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("payload-too-large").contains("Request too large")
                .contains("The request body must be 64 KB or smaller.");
    }

    @Test
    void aSignedInChunkedBodyWithNoDeclaredLengthIsStoppedAtTheLimit() throws Exception {
        signIn();
        long[] bytesPulled = new long[1];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ipd/seen") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override
                    public int read() {
                        bytesPulled[0]++;
                        return 'a';
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        bytesPulled[0] += len;
                        java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                        return len;
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    }
                };
            }
        };
        // no content length is declared, as with Transfer-Encoding: chunked
        assertThat(request.getContentLengthLong()).isEqualTo(-1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(bytesPulled[0]).as("reading stopped just past the cap, an endless stream is not read").isLessThan(SIGNED_IN_LIMIT + 8192);
    }

    // ---- security review #3: no principal means the 4 KB cap ----

    @Test
    void aCallerWithNoPrincipalGetsThe4KbCapOnANonPublicPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(postOf("/api/v1/ipd/doses", 5000), response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("The request body must be 4 KB or smaller.");
    }

    @Test
    void anAnonymousAuthenticationAlsoGetsThe4KbCap() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.AnonymousAuthenticationToken("key", "anonymous",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ANONYMOUS"))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(postOf("/api/v1/ipd/doses", 5000), response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void aChunkedBodyWithNoPrincipalIsStoppedAtTheFourKbCap() throws Exception {
        long[] bytesPulled = new long[1];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ipd/seen") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override
                    public int read() {
                        bytesPulled[0]++;
                        return 'a';
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        bytesPulled[0] += len;
                        java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                        return len;
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    }
                };
            }
        };
        assertThat(request.getContentLengthLong()).isEqualTo(-1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(bytesPulled[0]).as("an anonymous caller is read for no more than about 4 KB")
                .isLessThanOrEqualTo(RequestBodySizeLimitFilter.MAX_BODY_BYTES + 4096);
    }

    @Test
    void aSignedInCallerKeepsThe64KbCap() throws Exception {
        signIn();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(postOf("/api/v1/ipd/doses", 5000), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void thePublicCapStaysAt4KbEvenWhenTheSignedInCapIsHigher() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(postOf("/api/v1/platform/public/sales-enquiries", 5000), response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("The request body must be 4 KB or smaller.");
    }

    @Test
    void aPercentEncodedPublicPathStillGetsThePublicCap() throws Exception {
        MockHttpServletRequest request = postOf("/api/v1/platform/public/sales-enquiries", 5000);
        request.setRequestURI("/api/v1/platform/%70ublic/sales-enquiries");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void theSignedInCapIsConfigurable() throws Exception {
        signIn();
        RequestBodySizeLimitFilter small =
                new RequestBodySizeLimitFilter(new ProblemJsonWriter(new ObjectMapper()), 8 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();

        small.doFilter(postOf("/api/v1/ipd/doses", 8 * 1024 + 1), response, mustNotRun());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("The request body must be 8 KB or smaller.");
    }

    @Test
    void aCapBelowThePublicCapIsRaisedToIt() throws Exception {
        RequestBodySizeLimitFilter tiny = new RequestBodySizeLimitFilter(new ProblemJsonWriter(new ObjectMapper()), 10);
        MockHttpServletResponse response = new MockHttpServletResponse();

        tiny.doFilter(postOf("/api/v1/ipd/doses", 4096), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- DEC-051 M2 (ADR-022 D11): the two form-save paths take up to 256 KB ----

    private static final String FORM_KEY = "f_abcdefghij";

    @Test
    void aSignedInFormDraftOf200KbIsAcceptedOnBothFormSavePaths() throws Exception {
        signIn();
        for (String path : new String[] {"/api/v1/opd/emr/forms", "/api/v1/opd/emr/forms/" + FORM_KEY + "/draft"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            int[] seen = new int[1];

            filter.doFilter(postOf(path, 200 * 1024), response,
                    (req, res) -> seen[0] = StreamUtils.copyToByteArray(req.getInputStream()).length);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(seen[0]).as(path).isEqualTo(200 * 1024);
        }
    }

    @Test
    void aFormSaveOfExactly256KbGoesThroughAndOneByteMoreAnswers413With256Kb() throws Exception {
        signIn();
        MockHttpServletResponse exact = new MockHttpServletResponse();
        filter.doFilter(postOf("/api/v1/opd/emr/forms", (int) RequestBodySizeLimitFilter.FORM_SAVE_MAX_BODY_BYTES), exact,
                (req, res) -> { });
        assertThat(exact.getStatus()).isEqualTo(200);

        MockHttpServletResponse over = new MockHttpServletResponse();
        filter.doFilter(postOf("/api/v1/opd/emr/forms/" + FORM_KEY + "/draft",
                (int) RequestBodySizeLimitFilter.FORM_SAVE_MAX_BODY_BYTES + 1), over, mustNotRun());
        assertThat(over.getStatus()).isEqualTo(413);
        assertThat(over.getContentAsString()).contains("The request body must be 256 KB or smaller.");
    }

    @Test
    void theFormSaveCapDoesNotApplyToAnyOtherPathOrToACallerWithNoPrincipal() throws Exception {
        signIn();
        for (String path : new String[] {"/api/v1/opd/emr/forms/" + FORM_KEY, "/api/v1/opd/emr/forms/" + FORM_KEY + "/publish",
                "/api/v1/opd/emr/forms/" + FORM_KEY + "/draft/x", "/api/v1/opd/emr/records/x/draft",
                "/api/v1/opd/emr/forms/F_ABCDEFGHIJ/draft"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(postOf(path, 100 * 1024), response, mustNotRun());
            assertThat(response.getStatus()).as(path).isEqualTo(413);
            assertThat(response.getContentAsString()).as(path).contains("64 KB or smaller");
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        filter.doFilter(postOf("/api/v1/opd/emr/forms", 5000), anonymous, mustNotRun());
        assertThat(anonymous.getStatus()).isEqualTo(413);
        assertThat(anonymous.getContentAsString()).contains("4 KB or smaller");
    }

    @Test
    void aConfiguredSignedInCapAbove256KbIsKeptOnTheFormSavePaths() throws Exception {
        signIn();
        RequestBodySizeLimitFilter large =
                new RequestBodySizeLimitFilter(new ProblemJsonWriter(new ObjectMapper()), 512 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();

        large.doFilter(postOf("/api/v1/opd/emr/forms", 300 * 1024), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aRequestWithNoBodyGoesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ipd/admissions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] ran = new boolean[1];

        filter.doFilter(request, response, (req, res) -> ran[0] = true);

        assertThat(ran[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void describeShowsWholeKilobytesOrBytes() {
        assertThat(RequestBodySizeLimitFilter.describe(65536)).isEqualTo("64 KB");
        assertThat(RequestBodySizeLimitFilter.describe(5000)).isEqualTo("5000 bytes");
    }
}
