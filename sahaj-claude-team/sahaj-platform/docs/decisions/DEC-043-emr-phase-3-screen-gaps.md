# DEC-043 · EMR phase 3 screens: mockup gaps and choices (SAH-48, 50, 55-59)

Date: 12 Nov 2026. Status: DEFAULT APPLIED 12 Nov 2026 (DEC-023: no answer; the human said "continue the existing work"). Raised by: tech lead, from the angular-developer's hand-back (logged in docs/handoffs/SAH-43.md).
Default if unanswered (DEC-023): keep as built.

1. Second list view called "Table" (story) not "Grid" (mockup).
2. Body map "Or pick a body part": with a pin selected it changes that pin's place; with none it drops a new pin.
3. "Pain scale 0–10" (24th field type in the mockup palette) not built (not in story or contract).
4. Form starters live on the forms list page, not a "Templates" tab inside the builder.
5. Field width limited to the three drawn choices (Full, Half, Third).
6. Course page: Payments is a third tab; Balance shown in the note under the panel.
7. Past visit details start closed; "Open discharge summary" opens the IPD page (no deep link exists).
8. Added because the story needs them, not drawn: "Search medicines" and "Add as typed medicine"; "No local findings"; "Change date, work or charge" on a planned sitting; "Move to day care / IPD".
9. **Marathi and Hindi words on the printed form** (Patient, Age / sex, UHID, Signature, Reg. no.) are not in the mockup; best-effort words supplied in `forms/form-print.logic.ts`, tagged "not reviewed". Need a language reviewer or your decision.
10. Body-map skin tones and finding colours are data colours in the component, not theme tokens.
11. Not built yet: start-a-course dialog/entry (belongs to phase 4 specialty screens; a billing-counter link needs a decision); mockup course actions with no API (Mark done, Patient did not come, Rebook, Remove).
12. Disabled with "Later": tooth chart (phase 4), photo/file/signature/handwriting; WhatsApp/ABHA share and send (DEC-040).

## Answer
No explicit answer; default applied: keep all 12 as built. Item 9 (Marathi/Hindi print words) stays tagged "not reviewed" until a language reviewer or the human supplies the words. This is not an approval line.
