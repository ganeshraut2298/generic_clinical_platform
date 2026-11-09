package com.sahaj.platform.web.security;

import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Rejects an oversized request body with 413. Two caps apply: {@value #MAX_BODY_BYTES} bytes on the public endpoints
 * ({@code /api/v1/platform/public/**}) and for any request with no authenticated principal, which need no login and so
 * are the easiest target for a flood of large bodies, and a configurable cap (default 64 KB, property
 * {@code sahaj.request-body.max-bytes}) for a signed-in caller on every other {@code /api/v1/**} endpoint, so a
 * signed-in caller cannot make the server read a huge body either (release audit G-1). The filter must therefore run
 * after the filter that authenticates the caller. The admin chain runs it before its token filter, so every admin
 * request has the 4 KB cap, which is enough for its sign-in bodies. The largest legitimate body in the contracts is a
 * few KB.
 *
 * <p>The whole body is read into memory here, before the controller or Jackson sees any of it:
 * the declared {@code Content-Length} is checked first (rejects an honest oversized request
 * without reading a byte), and then the bytes are read and counted as they arrive, which also
 * catches a request that lies about its length or sends none (chunked transfer encoding). Reading
 * eagerly, rather than relying on an exception thrown deep inside body parsing, means the 413 is
 * answered here and never gets reinterpreted by the generic error handler as a 500 or a 400.
 *
 * <p>The path is matched on the decoded lookup path Spring MVC itself uses
 * ({@link UrlPathHelper}), not the raw {@code getRequestURI()}, so a percent-encoded path cannot
 * dodge the check the way it could dodge a raw string comparison.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 4 * 1024;
    static final long DEFAULT_SIGNED_IN_MAX_BODY_BYTES = 64 * 1024;

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private static final String PUBLIC_PREFIX = "/api/v1/platform/public/";
    private static final String API_PREFIX = "/api/v1/";

    private final ProblemJsonWriter problemWriter;
    private final long signedInMaxBytes;

    public RequestBodySizeLimitFilter(ProblemJsonWriter problemWriter) {
        this(problemWriter, DEFAULT_SIGNED_IN_MAX_BODY_BYTES);
    }

    public RequestBodySizeLimitFilter(ProblemJsonWriter problemWriter, long signedInMaxBytes) {
        this.problemWriter = problemWriter;
        this.signedInMaxBytes = Math.max(signedInMaxBytes, MAX_BODY_BYTES);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH_HELPER.getLookupPathForRequest(request).startsWith(API_PREFIX);
    }

    /**
     * The cap for this request: 4 KB on the public endpoints and for any caller with no authenticated principal (an
     * anonymous caller is refused with 401 later anyway, so it must not make the server read 64 KB first); the
     * configured cap only for a signed-in caller on every other API path.
     */
    private long limitFor(HttpServletRequest request) {
        if (PATH_HELPER.getLookupPathForRequest(request).startsWith(PUBLIC_PREFIX)) {
            return MAX_BODY_BYTES;
        }
        return isSignedIn() ? signedInMaxBytes : MAX_BODY_BYTES;
    }

    private static boolean isSignedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            reject(response, limit);
            return;
        }
        byte[] body = readUpToLimit(request.getInputStream(), limit);
        if (body == null) {
            reject(response, limit);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response, long limit) throws IOException {
        problemWriter.write(response, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE,
                "payload-too-large", "Request too large",
                "The request body must be " + describe(limit) + " or smaller."));
    }

    /** "4 KB", "64 KB"; a cap that is not a whole number of KB is shown in bytes. */
    static String describe(long bytes) {
        return bytes % 1024 == 0 ? (bytes / 1024) + " KB" : bytes + " bytes";
    }

    /** Reads at most {@code limit + 1} bytes; returns {@code null} if the body is longer than {@code limit}. */
    private static byte[] readUpToLimit(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // The body is already fully buffered, so there is nothing to wait for.
        }
    }
}
