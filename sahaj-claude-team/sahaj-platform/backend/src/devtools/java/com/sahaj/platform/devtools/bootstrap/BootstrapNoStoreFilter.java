package com.sahaj.platform.devtools.bootstrap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@code Cache-Control: no-store} and {@code Pragma: no-cache} on every response of the bootstrap chain before
 * anything else can answer (the body-size filter's 413, the network filter's 403, the controller). The empty 404 is
 * written by {@code AdminDevNotFound.write}, which resets the response first, so the 404 keeps the exact bytes of the
 * other dev-only 404s (no {@code Pragma}).
 */
final class BootstrapNoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
