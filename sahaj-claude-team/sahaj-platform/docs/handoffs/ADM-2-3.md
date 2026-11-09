# ADM-2 / ADM-3 · Sahaj admin: clinics directory, modules, plan, activity log — handoffs

Decision: the human chose "Design only" (AskUserQuestion, 9 Nov 2026). Defaults for the open questions are in DEC-029 (DEC-023, reversible).

## solution-architect (9 Nov 2026)
- **New files:**
  - ADR-016-admin-directory-and-modules.md (amends ADR-003 rev 2)
  - docs/architecture/ADM-2-3-admin-directory.md
  - DEC-029-admin-directory-defaults.md
- **Contract:** docs/api/admin.yaml 0.3.1 → 0.4.0. The tech lead parsed it with SnakeYAML: OK, 12 paths.
- **Endpoints** under /api/v1/admin (PLATFORM_ADMIN):
  - POST /clinics/search
  - GET /clinics/{id}
  - PATCH /clinics/{id}/modules
  - PUT /clinics/{id}/plan
  - GET /clinics/{id}/activity
- **Design changes from the stories:**
  - Search is a POST, so search text stays out of URLs and logs.
  - The IPD filter is withdrawn, because the mockup has none.
  - The activity log lists applied actions only.
  - Two helper functions write no audit row of their own. The architect asks for a review.
  - Search text is escaped inside the database function.
- **Database (parked):** 8 SECURITY DEFINER functions and column-level grants for the role `sahaj_admin_definer`. The migration needs the role to exist, and the ADM-1 parked migrations to be moved and renumbered.
- **NEEDS HUMAN: NH-1 to NH-8, see DEC-029.**
  - NH-4 (plan choices) and NH-5 (what a plan changes) block ADM-3b.
  - NH-3 (the 90-day read-only rule), NH-6 and NH-7 are patient-safety, clinical or legal.
- **Tech lead notes:**
  - Two files are numbered ADR-016: the admin one and docs/architecture/adr/ADR-016-ipd-ward-scoping-and-break-glass.md (SAH-34). The ward scoping one should become ADR-017. That is a rename in the architect's folder, which I cannot do. Recorded for the human.
  - Nothing is built before the human creates the role and approves `contract`.
