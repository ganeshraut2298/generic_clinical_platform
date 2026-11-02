/**
 * Public API of the {@code com.sahaj.patient} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.patient}'s internal sub-packages. {@link com.sahaj.patient.api.PatientLookupPort}
 * (SAH-9) is what {@code opd} uses to read a patient's name, age and sex without joining
 * {@code patient.patient} directly.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} makes Spring Modulith treat this
 * package as exposed once it has types in it (ADR-001 D2); without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.patient.api;
