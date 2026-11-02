/**
 * Patient registry: demographics, identifiers and patient-level master data.
 *
 * <p>This module's only part visible to other modules is {@code com.sahaj.patient.api}. Everything
 * else in this package and its sub-packages is internal implementation (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1). SAH-8 adds the patient
 * registry ({@code web}, {@code application}, {@code infrastructure}): register, search, update,
 * family grouping by mobile and soft merge. It talks to {@code platform} only through
 * {@code platform.api} (the signed-in clinic user and the audit log).
 */
package com.sahaj.patient;
