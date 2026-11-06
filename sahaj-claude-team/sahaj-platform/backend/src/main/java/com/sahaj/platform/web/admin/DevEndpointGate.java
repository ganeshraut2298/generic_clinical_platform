package com.sahaj.platform.web.admin;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides whether a request under {@code /api/v1/admin/dev} may reach the dev-only endpoint
 * (ADR-008 D3). Main code defines only this interface and {@link AdminDevPathFilter}; no
 * implementation exists in a production build, so every such request answers the same empty 404.
 * An implementation must fail closed: any doubt means {@code false}.
 */
public interface DevEndpointGate {

    boolean permits(HttpServletRequest request);
}
