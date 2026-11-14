package com.sahaj.platform.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SAH-55 (ADR-022 D1, D9): the clinic name, address and phone for the printed case paper, read from the clinic profile
 * (SAH-7) without {@code opd} touching {@code platform} tables. Implemented by {@code platform}; runs in the caller's
 * transaction (the clinic is already bound). Another clinic's id is simply absent. Nothing here is patient data.
 */
public interface ClinicLetterheadPort {

    /** {@code address} joins the profile's address, city and PIN; either of the last two parts may be missing. */
    record Letterhead(String clinicName, String address, String phone) {
    }

    Optional<Letterhead> find(UUID tenantId);
}
