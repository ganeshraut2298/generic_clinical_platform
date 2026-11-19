package com.sahaj.platform.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SAH-78 (ADR-028 D2): the clinic's web address (the slug chosen in setup, for example {@code sunrise-baner}), read once
 * by {@code pharmacy} to derive the three-character unit code. Implemented by {@code platform}; runs in the caller's
 * transaction (the clinic is already bound). Another clinic's id is simply absent. Nothing here is personal data.
 */
public interface ClinicWebAddressPort {

    Optional<String> webAddress(UUID tenantId);
}
