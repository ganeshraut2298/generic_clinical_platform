# DEC-063 · ADR-028 sign-off (tenancy, auth, boundaries) and how the 3 developer lanes run

Date: 17 Nov 2026. Status: ANSWERED (A, A). Raised by: tech lead from the architect hand-back (docs/architecture/adr/ADR-028-pharmacy-inventory-module.md).
Blocked: `docs/api/pharmacy.yaml` (contract), then DB and code for SAH-78.

## Q1 ADR-028 points marked [TENANCY] [AUTH] [BOUNDARY]
- OQ-PHI-1 [TENANCY]: the tenant stays the clinic or hospital; "unit" is a `pharmacy.unit` row and a `unit_id` column, one unit per tenant, no RLS change.
- OQ-PHI-2 [BOUNDARY]: pharmacy sits on top; it calls platform, patient, opd, ipd and billing `api` packages only; nothing depends on pharmacy (older modules declare ports, pharmacy implements them, as with IpdOccupancyPort).
- OQ-PHI-3 [AUTH]: store-scoped access in one StoreAccessGuard; `{storeId}` in every path; an unmapped store gives 404, a missing capability 403; the owner reaches all stores except pharmacist-only actions; the PHARMACIST store role only for PHARMACIST members. Revised to include the Store staff team role (DEC-062 Q3).
- OQ-PHI-4 [AUTH/menu]: `platform.module_entitlement.pharmacy_on`, switched on by the Sahaj admin; the entry shows for the owner and mapped staff.
- Others with recommendations: tab order as the BRD (OQ-PHI-7; Phase 1 shows only Inventory setup); opening-stock posting in SAH-79 (OQ-PHI-9); Phase 3 touch points T2/T5/T7 each get their own story (OQ-PHI-10); offline billing is its own design story, with a new package to be decided then (OQ-PHI-11); BRD §10 answers OQ-PHI-12..16; multi-branch Later (OQ-PHI-17); compliance check before Phase 3 (OQ-PHI-19); pharmacist registration number in Phase 3 (OQ-PHI-20); money in paise (OQ-PHI-21, needs a human edit to `.claude/rules/postgresql.md`).
Options: A (recommended) accept all as written; B review ADR-028 first.

## Q2 Three developer lanes vs one writer per area
The hook allows one writer per area (`backend/`, `frontend/`) per working tree, and finished agents keep their lock for 2 h.
- A (recommended): WP-A → WP-B → WP-C in sequence, Java and Angular in parallel within each. Works with no config change. Lanes 1–3 are the three work packages.
- B: the human changes `.claude/hooks/guard-ownership.mjs` (for example lock per file, or release on agent stop) so the 3 lanes write at once. Faster; the human edits the hook.

## Answer (human, AskUserQuestion, 17 Nov 2026): Q1 A, Q2 A
1. ADR-028 accepted as written (OQ-PHI-1..21 recommendations), with D4 revised to include the Store staff team role (DEC-062 Q3). The architect may write `docs/api/pharmacy.yaml`. This is a chat sign-off, not an approvals.md line.
2. Developer lanes run as sequential work packages WP-A → WP-B → WP-C, with java-developer and angular-developer in parallel inside each. No hook change.

## Next (tech lead)
After the requirements and architecture locks go stale (about 23:50 IST, 17 Nov): BA touch-up of SAH-78 stories (Store staff role, ADR-028 alignment, OQ answers) in parallel with the architect (ADR-028 D4 revision + pharmacy.yaml for SAH-78). Then BA 2 writes SAH-79. DB and code wait for the SAH-78 branch and `scripts/db/schemas.sql` (human).
