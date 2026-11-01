/**
 * Tenancy, users, roles, authentication and cross-cutting platform concerns.
 *
 * <p>This module's only part visible to other modules is {@code com.sahaj.platform.api}. Everything
 * else in this package and its sub-packages is internal implementation (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1).
 *
 * <p>Sub-packages: {@code web} (controllers, error mapping), {@code application} (services),
 * {@code domain} (types and rules), {@code infrastructure} (security, tenant binding, rate limit,
 * persistence).
 */
package com.sahaj.platform;
