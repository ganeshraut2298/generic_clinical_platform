/**
 * Public API of the {@code com.sahaj.hra} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.hra}'s internal sub-packages. It is empty on purpose: the module exposes nothing
 * directly. Approved leave reaches {@code opd} through the port {@code opd} owns
 * ({@code DoctorBlockedDatesPort}), which {@code hra} implements.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} makes Spring Modulith treat this
 * package as exposed once it has types in it (ADR-001 D2); without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.hra.api;
