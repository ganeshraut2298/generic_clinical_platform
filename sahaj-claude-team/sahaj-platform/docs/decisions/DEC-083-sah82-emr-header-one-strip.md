# DEC-083 · SAH-82 EMR header: one strip, title and notes, top bar, branch

Date: 19 Nov 2026 · Story: SAH-82 · Status: ANSWERED (human, chat, 19 Nov 2026)

## Context
The analyst's SAH-82 proposal (docs/requirements/SAH-82.md) had 4 open questions the design does not decide. The design (emr-config screens 04-11) draws only the patient band. The department switch, visit type and Admit to IPD came from approved stories SAH-52 and SAH-67.

## Questions, options and answers
1. **OQ-82-1 Where the undrawn controls go.** Options: (a) a slim action row above the band (recommended, 2 rows); (b) inside the patient band; (c) a "More" menu.
   **Answer: "all in one strip, to optimize height".** One strip at desktop width: Back · avatar · name over the meta line · safety chips · department switch · visit type · Admit to IPD. The tech lead's reading for the stories:
   - At 1280 px and wider everything sits in one strip. Its height is set by the two text lines (name and meta line).
   - Safety rules stay. The allergy, Emergency and MLC chips are always visible and come first. The allergy text is never cut off. Lower-priority chips (Known, Last visit) may shorten with "…" and show the full text on tap.
   - Narrower widths may wrap into a second line. The analyst defines the wrap order (the controls wrap first, never the safety chips).
2. **OQ-82-3/4 "Consultation" title and grey notes.** **Answer: recommended.** Hide the OPD title visually and keep it as the accessible name. Keep "Daily progress note" and "Emergency assessment" visible. The two notes go behind an "i" info control next to the department.
3. **OQ-82-2 Doctor and department in the app top bar.** **Answer: recommended, no.** They stay in the EMR strip; a shell change would be a separate story.
4. **Branch.** **Answer: recommended.** SAH-82 and SAH-83 are built on the current branch feature/SAH-39-home-kinetic-background as additive work (as DEC-061 did for SAH-78), with one commit per story.

OQ-82-5..11 take the analyst's recommended defaults (the human gave no other answer).

## Blocked
Nothing. Next: the analyst revises SAH-82 to the one-strip arrangement, then the human adds `SAH-82 | stories | APPROVED`.
