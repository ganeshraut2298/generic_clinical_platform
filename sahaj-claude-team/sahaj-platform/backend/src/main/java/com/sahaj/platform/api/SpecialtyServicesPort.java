package com.sahaj.platform.api;

import java.util.List;
import java.util.UUID;

/**
 * Port that applying a specialty template (SAH-7 AC9) uses to load the template's starter
 * services into the clinic's price list. Implemented by {@code opd}, which owns the price list
 * (same direction as {@link OpdDefaultsPort}: {@code opd} depends on {@code platform.api}).
 *
 * <p>Runs in the caller's transaction. A service is added only when the clinic has no service of
 * that name (ignoring case, active or not); nothing is ever deleted or overwritten.
 */
public interface SpecialtyServicesPort {

    /** A starter service from a template. The price is in paise (₹150 is 15000). */
    record TemplateService(String name, String category, long pricePaise) {
    }

    /**
     * @param tenantId    the caller's own clinic
     * @param actorUserId the owner applying the template
     * @return how many services were really added
     */
    int addMissingServices(UUID tenantId, UUID actorUserId, List<TemplateService> services);
}
