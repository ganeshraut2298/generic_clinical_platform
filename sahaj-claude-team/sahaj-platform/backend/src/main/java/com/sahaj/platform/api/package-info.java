/**
 * Public API of the {@code com.sahaj.platform} module.
 *
 * <p>Other modules may depend only on classes in this package (or on domain events), never on
 * {@code com.sahaj.platform}'s internal sub-packages. It holds the audit port
 * ({@link com.sahaj.platform.api.AuditLog}), the signed-in user ({@link com.sahaj.platform.api.ClinicUser})
 * and owner check ({@link com.sahaj.platform.api.OwnerGuard}), {@link com.sahaj.platform.api.PlanLimits}, the
 * exceptions that the platform error handler maps to 404, 409 and 400 problems, and the ports that
 * {@code opd} implements for {@code platform} (SAH-7: {@link com.sahaj.platform.api.OpdDefaultsPort},
 * {@link com.sahaj.platform.api.SpecialtyServicesPort}, {@link com.sahaj.platform.api.OpdSetupStatus}),
 * the team lookup {@code opd} and {@code billing} read a member's name, role and fee through
 * ({@link com.sahaj.platform.api.TeamMemberPort}), and the actor directory {@code billing} reads a
 * payment's or closing's "who" through (SAH-10: {@link com.sahaj.platform.api.ActorDirectoryPort}),
 * so that dependencies between modules run one way only.
 *
 * <p>{@link org.springframework.modulith.NamedInterface} is what makes Spring Modulith actually
 * treat this package as exposed (ADR-001 D2); without it every sub-package is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.sahaj.platform.api;
