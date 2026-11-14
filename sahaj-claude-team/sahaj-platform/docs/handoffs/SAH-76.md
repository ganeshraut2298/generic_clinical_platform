# SAH-76 · Nursing integration, Sprints 1-2 (DEC-055)

## Stage 1 · business-analyst (15 Nov 2026)
Files: docs/requirements/SAH-76.md (gap table 17 rows, BR-1..18, OQ-76-1..29), docs/stories/SAH-76.md (6 stories 76-1..76-6, all Must).
Gap check: built D3 only; partly A1, A10, B1, C2, D1, D2, D5, F1, L2; new A2, A3, B2, C1, D4, D6, M1. Built orders = 14 drugs, 4 fixed frequencies, no dose/start/duration; no PRN, STAT, inbox, change, hold, undo, override, versions.
Validation (tech lead): `validate docs` fails only on the known ADM-2.md. Both files name the Nursing sheet screen.
Needs human (go-live conditions marked *): OQ-76-1/2 allergy override vs built hard stop; OQ-76-9 changed version back to pharmacist; OQ-76-10* who marks STAT; OQ-76-15* dose window 60/60 vs spec 30/30; OQ-76-17*/19* override and PRN limits; OQ-76-5 give before acknowledge; OQ-76-6 no "nurse in-charge" role; OQ-76-18 wrong-band text; OQ-76-21 nursing settings page; OQ-76-22 quality reports (no screen); OQ-76-4 build inside Nursing sheet, not a full page; OQ-76-12 no renal/weight dosing; OQ-76-24 F1 uses ward profile frequency.
Human-only: add reference screens to screen-map.md; `SAH-76 · stories · APPROVED` in approvals.md.
Next: solution-architect (blocked until `stories` approved).
