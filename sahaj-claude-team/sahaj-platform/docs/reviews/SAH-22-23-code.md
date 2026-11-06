# Code review · SAH-22 (wards, rooms, beds, charges) and SAH-23 (admission and deposits)

Reviewer: code-reviewer (read-only, partial review), 5 Nov 2026. Verdict: **approve with comments** (no Blocker, no Major).
Verified: FY rollover in April (IST), IP number sequence inside the admit transaction, advisory lock then bed `for update`, check order matches AC4, minimum-deposit formula equal on server and client, paise throughout, bed removal order (occupied then history, FK fallback), forward-only migration with append-only deposits and forced RLS, role gates, audit rows without name/mobile/reason.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| 1 | Minor | AdmissionService.list | N+1 patient lookups per admitted row | Batch `findAll` on PatientLookupPort, or cache |
| 2 | Minor | AdmissionService:227-228 | financialYear computed twice; admitted_at from DB now() | Compute once |
| 3 | Minor | AdmissionService.addDeposit | No lock on the admission; unsafe once discharge exists | Lock before status check (note for discharge story) |
| 4 | Minor | IpdService / AdmissionService | gate(), now(), categories() duplicated | Extract IpdGate / IpdCatalog |
| 5 | Minor | AdmissionController | No @Valid (validation in service) | Accept |
| 6 | Minor | IpdService.buildLayout | O(wards x beds) grouping | groupingBy |
| 7 | Minor | ipd.bed DELETE grant, no updated_* on admission | Clinical rows should not be hard-deleted | Replace with retired flag when transfer/discharge lands |
| 8-10 | Nit | duplicate room-insert block; silent "Patient" fallback; client and server both compute deposit pill | tidy |

Not checked: test results and quality, contract field-by-field, SAH-22 repository files, platform changes beyond NavigationPolicy, most frontend files and mockup fidelity.
