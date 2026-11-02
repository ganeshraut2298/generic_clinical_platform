package com.sahaj.opd.api;

import java.util.Map;
import java.util.UUID;

/**
 * What {@code billing} needs from the clinic's fee settings to cap a discount (SAH-10 AC3): the
 * signed-in role's maximum discount percent, from SAH-7's {@code discountLimits}. {@code billing}
 * reads this through {@code opd.api} rather than joining {@code opd.opd_settings} directly. A role
 * missing from the map has no discount right (0%); the caller treats {@code OWNER} as having no
 * limit regardless of what this map carries, same as SAH-7's own rule. Implemented by {@code opd};
 * runs in the caller's transaction (tenant already bound).
 */
public interface DiscountLimitPort {

    /** Maps a role name to a percent from 0 to 100; a clinic that never saved fee settings gets the defaults. */
    Map<String, Integer> discountLimits(UUID tenantId);
}
