/**
 * Public API of the {@code com.sahaj.ipd} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.ipd}'s internal sub-packages. This story adds no public types yet; the package
 * exists so later stories have a place to put them.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} makes Spring Modulith treat this
 * package as exposed once it has types in it (ADR-001 D2); without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.ipd.api;
