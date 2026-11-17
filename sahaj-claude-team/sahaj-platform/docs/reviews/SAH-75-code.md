# Code review · SAH-75 EMR design alignment (branch feature/SAH-39) · 15 Nov 2026
Reviewer: code-reviewer (read-only; no Maven; frontend sampled, not all 127 files). Verdict: **CHANGES REQUIRED** (Major #1-#3). Security assessed separately.
Sound: forward-only additive migrations, RLS ENABLE+FORCE on opd.emr_cancer_journey with append-only trigger and column grants, no `${`, starter-layout immutability triggers never disabled, module boundaries hold, one-charge done_at flow, test changes match the new seed, no skipped tests.

| # | Sev | Where | Finding | Owner |
|---|---|---|---|---|
| 1 | Major | frontend course-panel.component.ts:108-117 | Effect reads `store.record()` so every save re-runs load(), blanks and rebuilds the embedded course panel (open action state lost). Drive from a computed patient id; do not blank when a course is shown; spec: save twice, list called once. | angular |
| 2 | Major | backend ipd MedicineSafetyCheck.java:129 | `contains("none known")` makes "Peanuts; none known otherwise" NONE_KNOWN: chip says no allergy although one is named (safety). Drop it; use AllergyStatus.isNoneWord; test the case. | java |
| 3 | Major | frontend frame.logic.ts readyFooterText (og) | Invented clinical rule: next ANC visit = gestational weeks + 3. Not in contract, stories or mockup. Remove; show the doctor's value or "Ready to complete." | angular |
| 4 | Minor | TreatmentCourseService.markDone ~485-496 | null body gives NPE/500 instead of 400. | java |
| 5 | Minor | CancerJourneyService ~95-110 | A save with no change still appends a version and audits empty changedFields. Return latest, write nothing. | java |
| 6 | Minor | EmrRecordService:112, PatientService:45, AllergyStatus:27 | Three none-word lists; make AllergyStatus the single source. | java |
| 7 | Minor | onc-journey.logic.ts CYCLE_TIP, cycleText | "Delayed 1 week", "day 1" fixed strings, not data. | angular |
| 8 | Minor | frame.logic.ts | "procedure(s)", "vaccine(s)" etc. not plain English; use a plural() helper (check mockup text). | angular |
| 9 | Minor | docs/approvals.md | No SAH-75 rows (approvals recorded in DEC-052/053 only); branch is SAH-39 so one-story-per-branch not met. | human |
| 10 | Nit | V2026_12_14_1040 | Hard-coded count(*) <> 227 brittle. | db (optional) |
| 11 | Nit | body-map.component.scss | Raw hex colours instead of tokens (mockup may fix them). | angular |
| 12 | Nit | course-panel | "No course started yet." text pending OQ-75-12 (human answered: use it). | — |
Tests missing: finding 2 case and finding 1 double-load; QA to confirm `hasBarePin` rejects a standalone PIN and a PIN next to non-digits. Deferred scope 75-8 and 75-11: tech lead to record as deferred.
Next: fixes, then validate all, re-review changed files only.
