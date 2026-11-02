/**
 * Public API of the {@code com.sahaj.billing} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.billing}'s internal sub-packages. SAH-10 still adds no public type here:
 * {@code billing} only ever calls into {@code opd.api}, {@code patient.api} and {@code platform.api},
 * never the other way around, so nothing yet needs a type of its own to call back into
 * {@code billing}. The package exists so a later story (IPD billing, say) has a place to put one.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} makes Spring Modulith treat this
 * package as exposed once it has types in it (ADR-001 D2); without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.billing.api;
