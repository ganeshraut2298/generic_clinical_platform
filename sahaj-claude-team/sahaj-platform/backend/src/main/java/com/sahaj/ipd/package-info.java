/**
 * Inpatient department: admissions, beds, wards and IPD workflows.
 *
 * <p>This module's only part visible to other modules is {@code com.sahaj.ipd.api}. Everything
 * else in this package and its sub-packages is internal implementation (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1). SAH-22 adds wards, rooms and
 * beds; SAH-23 adds admissions and deposits; SAH-27 adds discharge; SAH-28 adds the emergency queue, triage and Emergency setup;
 * SAH-30 adds nursing vitals, nursing notes, the early warning score and its escalation (ADR-006);
 * SAH-33 adds the Safety board, the three actions on an escalation and the clinical audit trail (ADR-007);
 * SAH-31 adds the EMR sheet, medicine orders with the safety check, and the pharmacist check (ADR-009, ADR-010);
 * SAH-32 adds the Medicines (MAR) tab and giving a dose with the bedside checks (ADR-011). {@code ipd} reaches other modules only through
 * {@code com.sahaj.platform.api}, {@code com.sahaj.patient.api} and (SAH-27, ADR-004) {@code com.sahaj.opd.api},
 * for the follow-up of a discharge.
 */
package com.sahaj.ipd;
