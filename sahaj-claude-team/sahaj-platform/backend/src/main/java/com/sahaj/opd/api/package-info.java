/**
 * Public API of the {@code com.sahaj.opd} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.opd}'s internal sub-packages. It was empty on purpose through SAH-9: {@code opd}
 * depends on {@code platform.api}, so every call that {@code platform} makes into {@code opd}
 * (OPD defaults at setup, starter services of a specialty template, the setup overview facts) is
 * an interface in {@code com.sahaj.platform.api} that {@code opd} implements - a call in each
 * direction would be a module cycle, which {@code ModularityTests} refuses.
 *
 * <p>SAH-10 adds {@link com.sahaj.opd.api.VisitBillingPort}, {@link com.sahaj.opd.api.ServiceLookupPort}
 * and {@link com.sahaj.opd.api.DiscountLimitPort}: {@code billing} reads a visit's billing detail,
 * the price list and the fee settings' discount limits through this package. This is the other
 * direction - {@code billing} depends on {@code opd}, never the reverse - so it adds no cycle.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.opd.api;
