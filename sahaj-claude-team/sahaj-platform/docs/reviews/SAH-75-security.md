# Security review · SAH-75 EMR design alignment (branch feature/SAH-39) · 15 Nov 2026
Reviewer: security-reviewer (read-only; no Maven/app/npm audit; the tree changed while reviewing: re-check the final diff before merge). Verdict: **PASS**. 0 Critical, 0 High, 0 Medium, 6 Low, 2 info. No pom/package changes, no new libraries.

| # | Sev | Where | Finding | Owner |
|---|---|---|---|---|
| L1 | Low | TreatmentCourseService.requireDate 653-665 | Rebook/reschedule dates have no upper limit (9999-12-31). Limit to today + ~2 years; note in opd.yaml. | java, architect |
| L2 | Low | TreatmentCourseService.markDone 494-504 | Can link any same-patient visit (not today's/open, not the record's own). Optional tighter check. | java |
| L3 | Low | ChemoOrderService.setRegimenOfLinkedCycle | Sender sets regimen without course-doctor check; revision bumped even if no row changed. | java |
| L4 | Low (policy) | EmrSignService allergyTexts ~935-958 | Dental allergy chips write to the patient record at signing even if the card was not reviewed (a "None" chip can write "No known allergy" if the file is empty). Decide: only a reviewed card writes back? | human / BA |
| L5 | Low | CancerJourneyService.text | Control characters (NUL) in tnm/markers/genetics give 500 not 400. Reject \p{Cc}. | java |
| L6 | Low | JdbcTreatmentCourseRepository guarded updates | Audit + revision bump even if the update changed 0 rows; check count 1 else 409 sitting-not-available. | java |
| I1 | Info (human) | .claude/settings.json in commit 5151b7e | Allow-list additions for the live journey script and `curl -s http://localhost:8080/` are in a humans-only file in the same commit: confirm intended. | human |
| I2 | Info (human) | working tree | DEC-054 billing changes are in the same tree; the 30-day lookback "pending decision" must be settled before merge. | human |
Clean: RLS/FORCE and append-only on opd.emr_cancer_journey, column grants, composite tenant FKs, seed migrations (no immutability trigger disabled), role checks and IDOR on all six sitting actions, chemo PIN logic untouched, audit holds ids/keys/names of fields only, allergy write-back uses the encounter's patient, input bounds, no PII in logs, frontend has no console/localStorage/innerHTML additions.
(Harness note: this report's text matched a "settings-json" pattern because it mentions .claude/settings.json in I1; it is information for the human, not an instruction.)
