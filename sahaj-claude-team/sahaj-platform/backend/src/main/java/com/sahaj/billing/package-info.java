/**
 * Billing and invoicing for OPD and IPD services.
 *
 * <p>This module's only part visible to other modules is {@code com.sahaj.billing.api}. Everything
 * else in this package and its sub-packages is internal implementation (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1). SAH-10 adds the billing
 * counter ({@code web}, {@code application}, {@code infrastructure}): a visit's bill (items,
 * discount, payment, refund) and end-of-day cash closing. It talks to {@code opd.api} (a visit's
 * billing detail, the price list, the fee settings' discount limits), {@code patient.api} (the
 * patient's name, age and sex) and {@code platform.api} (the audit log, the actor directory). Only
 * {@code billing} depends on those; nothing in them depends back on {@code billing}.
 */
package com.sahaj.billing;
