# DEC-027 · SAH-37 patient card setup: safety and privacy points

Date: 9 Nov 2026. Status: DECIDED. Blocks: SAH-37 architecture (stories are done; see docs/stories/SAH-37.md, section 3).
Go-ahead to work on further stories: human in chat, 9 Nov 2026. That go-ahead does not answer these questions.

## Questions (each has a Recommended option from the BA)
1. **OQ-37-1 (a): the default card.** The mockup's standard card shows vitals tiles, Diagnosis and an Allergies line. SAH-36 built a smaller card.
   - Recommended: use the mockup default, limited to fields that have data.
   - Consequence: the Admitted list then sends diagnosis and five vitals for every patient to OWNER, DOCTOR and NURSE, and writes no access row (DEC-021 / OQ-36-7).
   - Safer option: keep the SAH-36 fields as the default, and let the owner switch the extra fields on.
2. **OQ-37-1 (b): renaming safety labels.** The mockup lets the owner rename Allergies, Early warning, UHID and Alerts.
   - Recommended: these labels are fixed. This differs from the mockup, but is the safer reading.
3. **OQ-37-2: allergy on the Compact layout.** The mockup hides allergy when it is "None known" or "Not recorded".
   - Recommended: Compact always shows "Allergies: Not recorded" or "Allergies: None known". This differs from the mockup, but is the safer reading.
4. **OQ-37-5: the Expanded layout's "Last nursing note".** It would put free clinical text for every patient on a shared screen.
   - Recommended: not built in SAH-37.
5. **OQ-37-11: the "Check" and "Critical" marks on vital tiles.** The mockup's own limits differ from the SAH-30 early warning bands.
   - Recommended: use the SAH-30 per-reading points (3 = Critical, 1 or 2 = Check). This creates no new threshold, and the existing clinical sign-off OQ-30-6 covers it.

## Tech lead recommendation
- Take the BA's recommendations for 2, 3, 4 and 5. Each is the safer reading, and none adds a new clinical rule.
- For 1(a), take the safer option: the default stays the SAH-36 card plus the locked Allergies line, and the owner may switch on the vitals and Diagnosis. This keeps the list's data the same unless an owner chooses more.
- If a field is switched on, it is sent only for roles that already read it, as rule 11 says.
- Record as a go-live condition: a clinician signs off the default card and the tile marks.

## Human answer
9 Nov 2026, AskUserQuestion: "SAH-36 card, owner adds (Recommended)". The default card is the SAH-36 card plus the locked Allergies line; the owner can switch on vitals and diagnosis. Points 2-5 take the safer defaults stated in the question (fixed safety labels, allergy always on Compact, no nursing note, tile marks from the SAH-30 points). Go-live condition: a clinician signs off the default card and the tile marks.
