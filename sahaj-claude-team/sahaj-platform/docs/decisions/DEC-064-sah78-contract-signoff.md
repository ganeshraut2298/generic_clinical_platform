# DEC-064 · SAH-78 contract sign-off and the last open points

Date: 17 Nov 2026. Status: ANSWERED (A, A, A). Raised by: tech lead from the architect hand-back (ADR-028 revision 2, R2-1..R2-16).
Blocked: db-engineer (WP-A), then developers.

## Q1 Contract and defaults
docs/api/pharmacy.yaml 0.1.0 (Masters, 78-1..10), platform.yaml 0.2.0, admin.yaml 0.6.0 (additive only). Defaults proposed:
- R2-2: Integration mode defaults to Integrated.
- R2-3: Store staff cannot be Approver or Delegate.
- R2-5: a pharmacist with no store mapping still sees the entry and can read masters.
- R2-6: strength is required on own products (the mockup's kit has none).
- R2-7 and R2-8: the transaction type list and preset meanings as proposed; the discount limit in % (mockup 05).
- R2-9: delegate at most 90 days.
- R2-10: no two numbering series share a prefix.
- R2-12: bill series per store deferred to SAH-80.
- R2-13: no NDPS flag in Phase 1 (compliance check before Phase 3).
A (recommended): accept the contract with these defaults. B: review first.

## Q2 Switching the module on (R2-4)
The Sahaj admin must turn `pharmacy_on` on per clinic. That needs an additive change in the admin portal (admin service, `platform.admin_set_modules`, a switch on the admin Modules tab), outside the DEC-061 additive list.
A (recommended): allow this additive admin change. B: no admin UI now; dev/test data turns it on.

## Q3 Existing role check constraint
Adding STORE_STAFF means replacing `ck_membership_role` on the existing membership table with a wider check (all old values still valid, no row changes), as migration 1019 did.
A (recommended): allow. B: hold the Store staff role.

## Decided by the tech lead (not an approval)
R2-16 Flyway placeholder for the sample catalogue (dev/test only). The Store staff label and a "no access" landing page for an empty menu are covered by the additive list.

## Answer (human, AskUserQuestion, 17 Nov 2026): Q1 A, Q2 A, Q3 A
1. Contract accepted (pharmacy.yaml 0.1.0, platform.yaml 0.2.0, admin.yaml 0.6.0) with all R2 defaults as listed. Chat sign-off; `docs/approvals.md` has no SAH-78 line (hooks run merge-only, DEC-001).
2. Additive admin-portal change allowed: a Pharmacy switch on the admin Modules tab (admin service, `platform.admin_set_modules`), nothing else in the admin portal.
3. Widening `ck_membership_role` to add STORE_STAFF in a new migration is allowed (no row changes).

## Follow-up (tech lead, 17 Nov 2026): Q2 cannot be built yet
The db-engineer found that `platform.admin_set_modules` and the other admin DB functions were never written (ADM-2/ADM-3 parked on the role `sahaj_admin_definer`). There is nothing to extend additively. Until ADM-3 is built, `pharmacy_on` is set by test fixtures and the dev setup only; ADM-3 adds the `p_pharmacy_on` parameter and the admin Modules switch.
