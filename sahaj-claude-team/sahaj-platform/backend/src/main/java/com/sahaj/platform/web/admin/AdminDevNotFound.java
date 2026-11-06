package com.sahaj.platform.web.admin;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The one answer for everything about the dev-only endpoint that is not a success (ADR-008 D3):
 * status 404, empty body, no {@code Content-Type}, {@code Cache-Control: no-store}, nothing else.
 * It does not depend on the method, path, headers or configuration, so a production build, a
 * switched-off endpoint, a refused caller and an already used endpoint look identical.
 *
 * <p>{@link HttpServletResponse#reset()} first: a security chain that ran before (for example the
 * request id header) leaves nothing behind, so the bytes match the answer written ahead of Spring
 * Security. {@code setStatus}, never {@code sendError}: no error page is rendered.
 */
public final class AdminDevNotFound {

    private AdminDevNotFound() {
    }

    public static void write(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setHeader("Cache-Control", "no-store");
    }
}
