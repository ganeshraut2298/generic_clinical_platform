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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Rejects a request body over {@value #MAX_BODY_BYTES} bytes on the public endpoints
 * ({@code /api/v1/platform/public/**}), which need no login and so are the easiest target for a
 * flood of large bodies.
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

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private static final String GUARDED_PREFIX = "/api/v1/platform/public/";

    private final ProblemJsonWriter problemWriter;

    public RequestBodySizeLimitFilter(ProblemJsonWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH_HELPER.getLookupPathForRequest(request).startsWith(GUARDED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        byte[] body = readUpToLimit(request.getInputStream());
        if (body == null) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        problemWriter.write(response, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE,
                "payload-too-large", "Request too large", "The request body must be 4 KB or smaller."));
    }

    /** Reads at most {@code MAX_BODY_BYTES + 1} bytes; returns {@code null} if that was exceeded. */
    private static byte[] readUpToLimit(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
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
