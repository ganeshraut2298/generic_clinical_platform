package com.sahaj.platform.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SAH-43 (ADR-019 D7): the clinic's own specialty code ({@code gp}, {@code dental}, {@code paeds} or
 * {@code homeo}) that {@code opd} uses as the second rule of the EMR template choice. Implemented by
 * {@code platform}, the owner of {@code platform.clinic_profile}; {@code opd} never queries that table. Runs in
 * the caller's transaction (tenant already bound). Not exposed over HTTP.
 */
public interface ClinicSpecialtyPort {

    /** Empty when the clinic has not chosen a specialty, has no profile yet, or the id is not the bound clinic. */
    Optional<String> specialtyCode(UUID tenantId);
}
