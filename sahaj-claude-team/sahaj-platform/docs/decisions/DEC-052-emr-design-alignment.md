# DEC-052 · Align the EMR with the design package (audit of 14 Nov 2026)

Date: 14 Nov 2026. Status: APPROVED (human, chat: "approved from my side need to make necessary changes [FE, BE, DB level]"). Raised by: tech lead from docs/reviews/EMR-design-audit-2026-11-14.md.
Scope: every Major and Minor gap in the audit, frontend, backend and database. Decisions already recorded (DEC-039, 041, 042, 043, 044, 048, 049, 051) stay unless listed below. Rule: where the doctor screens and the Main configurator tables disagree, the doctor screen wins (it is the more specific drawing); every clinical value stays tagged "value from the EMR mockup, not clinically reviewed".

## What is decided (tech lead reading of the approval; the human may change any line)
1. **Bug D1:** fix the Dental sitting key now (DentalFinishContributor) and prove it with EmrDentalSendToBillingIT.
2. **Prescription print format (G4):** stays available but is **not required** in any starter; an empty header prints the clinic letterhead default. Verified first by a test on a fresh visit.
3. **Layout:** doctor screens 50/50 (EMR left, past visits + case paper right); the left section nav only on General Medicine (as drawn); Quick tools tab for General Medicine, Paediatrics, Physiotherapy and Obstetrics & Gynaecology as drawn; per-department step subtitles, "n of m done" badges and ready-footer sentences as drawn; department accent colour applied to EMR list, case paper and course.
4. **Treatment course on the screens:** the course panel (Visits / Table / Payments) is embedded on Dental, Gynae (ANC course), Physiotherapy (package), Surgery (episode) and Oncology (chemo cycles), with a "start a course" entry where drawn. Course actions drawn in the design (Mark done, Patient did not come, Rebook/Reschedule, Remove, Extra session, Move to day care / IPD) are built — this supersedes DEC-043 item 11.
5. **Body map where drawn:** General Medicine "Local examination", Surgery "Local examination", Oncology exam "Body map" tab, Physiotherapy "Pain site".
6. **Required defaults follow the doctor screens:** General Medicine general examination required; Gynae mode menstrual history required; Surgery pre-op work-up, surgery plan/OT booking and consent required; Oncology chief complaints optional ("Other complaints"); Dental medical-history review required. Applied by forward migrations to the starter layouts (seed only; no clinic or patient data rewritten).
7. **Dental assessment cards as drawn:** Medical history and alerts (review button, alert banners), Dental history and habits, Pain, chip-style extra-oral and intra-oral examination, Radiographs and photos (file upload stays "Later" per the documents service).
8. **Oncology journey strip and watch chips:** diagnosis/stage, biology, intent/line, plan cycle chips, response; lifetime doxorubicin and scheme balance chips.
9. **Paediatrics "Mark all due as given" marks overdue vaccines too**, as drawn (clinical rule from the mockup, not clinically reviewed).
10. **All Minor gaps** in the audit are aligned to the design text and behaviour, except those covered by accepted decisions.
11. Not for agents: re-export `screens/16-VisitHistory.png` (design folder is human-only).

## Order of work
Wave 0 (no design questions): D1 fix, the stale-test fixes and failing-class run, the login page lint/test fixes, the G4 check.
Wave 1: business-analyst writes SAH-75 (design alignment) stories from the audit → solution-architect (contract and ADR for embedded course, journey strip data, dental assessment sections) → db-engineer (forward seed migrations) → java-developer ∥ angular-developer → qa-engineer → reviews.

## Addendum (human, chat, 14 Nov 2026): SAH-75 open questions
- OQ-75-7 Oncology journey: **a simple "Cancer journey" edit form per patient** (stage, biology, intent, line, board date) opened from the strip; cells read "Not recorded" until filled. The form layout is ours (not drawn).
- OQ-75-12 no course yet: **"No course started yet." and a "Start course" button.**
- OQ-75-16 Dental medical-history allergy chips: **update the patient's allergy record at signing** (like the EMR allergy section), so later visits and the prescription check see them.
- OQ-75-6 MLC banner "Photos are kept with the record.": **held until photo upload exists.**
- All other OQ-75 defaults in docs/requirements/SAH-75.md apply. The human's DEC-052 approval is recorded as the go-ahead for the SAH-75 stories (no approvals.md line written by agents).
- SAH-74 wording (tech lead): the login tabs are a Material radio group (`role="radio"`, `aria-checked`), not `aria-pressed`; the BA aligns SAH-74 criteria lines 36 and 43 in the next pass.
