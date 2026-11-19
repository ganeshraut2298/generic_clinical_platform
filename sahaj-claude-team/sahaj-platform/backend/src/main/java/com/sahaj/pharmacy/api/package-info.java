/**
 * Public API of the {@code com.sahaj.pharmacy} module (Pharmacy and Inventory, SAH-78, ADR-028 D1).
 *
 * <p>Other modules may depend only on classes in this package (or on domain events). Phase 1 puts nothing here: no module
 * depends on {@code pharmacy}. The one thing {@code platform} needs from it, the sidebar entry, is a port that
 * {@code platform} declares ({@code com.sahaj.platform.api.PharmacyAccessPort}) and {@code pharmacy} implements, so the
 * dependency runs from {@code pharmacy} to {@code platform} only.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} makes Spring Modulith treat this package as exposed once it has
 * types in it; without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.pharmacy.api;
