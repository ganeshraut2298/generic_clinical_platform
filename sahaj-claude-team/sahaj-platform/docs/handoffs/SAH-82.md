# SAH-82 · EMR consultation header: compact layout and data arrangement

Raised: 19 Nov 2026, human (chat): "Need to optimize the EMR component and senior product owner arrange data properly", with a screenshot of the live OPD Dental consultation header.
Design: docs/design/emr-config/.../screens/04-DoctorGeneral.png, 06-DoctorDental.png (doctor screens 04-11). Built: frontend/src/app/features/emr/record/emr-header.component.*.
Branch: not switched yet. The working tree is shared with the SAH-76 and SAH-78/79 sessions on feature/SAH-39-home-kinetic-background, so a switch would move their files. Asking the human.

## Tech lead gap reading (before BA)
- Design: one compact patient band (avatar, name, one meta line, chips on the right of the same band). Department and doctor are in the top app bar. There is no title row, layout notes, department select, visit-type control or Admit button in the drawn header.
- Built: 3 loose rows. Row 1 has the title, the 2 grey layout notes, the department select, the visit type and Admit. Row 2 is the patient line, with the doctor pushed to the far right. Row 3 is the allergy chip alone.
- Expected scope: frontend only (all values are already in the record). The analyst confirms.

## Stage log
- 19 Nov · business-analyst started (requirement + stories + OQ-82-n).
- 19 Nov · business-analyst DONE. docs/requirements/SAH-82.md (a 31-row element table, 15 rules, OQ-82-1..11) and docs/stories/SAH-82.md (82-1 patient band, 82-2 department/doctor line and the title, 82-3 visit type/IPD day/triage, 82-4 Back/department switch/Admit, 82-5 tablet/phone/sticky, 82-6 specialty chips into the band (Could)). Frontend only; no API or DB change. Out of scope as data: ABHA linked (Later), doctor qualification, Chair, Gynae husband/blood group, token number.
  Proposed: 2 rows at desktop. A slim action row (Back · department and doctor · visit type · Admit) and the drawn patient band (avatar, name, one meta line, chips on the right; allergy, Emergency and MLC first, never on their own row at 1280 px and wider). The band is always sticky. The page's sticky offsets (150/140 px) must follow the new height.
  The BA had no shell. Tech lead ran `node scripts/validate.mjs docs`: SAH-82 passes; the only failure is the old ADM-2.md file name (already open, not this story).
  Next: human answers OQ-82-1..4 (the rest take the BA defaults unless the human says otherwise), adds `SAH-82 | stories | APPROVED`. The architect only confirms "frontend only" (no contract), then angular-developer, QA, reviews.
- 19 Nov · DEC-083 answered: one strip (all controls in the band row at desktop), title/notes behind an info icon, no top-bar move, stay on the current branch. BA revising the stories.
