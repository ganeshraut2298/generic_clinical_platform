# SAH-34 · IPD ward scoping and break-glass — handoffs

Decisions: DEC-031 (the human accepted the recommended path and the break-glass deviations, 9 Nov 2026). The nurse-to-ward screen (34A) waits for the human to add it to the mockup.
Approvals: the human's chat answers of 9 Nov 2026. The `SAH-34 | stories` and `contract` lines in docs/approvals.md are still the human's to add.

## solution-architect (9 Nov 2026)
- ADR docs/architecture/adr/ADR-016-ipd-ward-scoping-and-break-glass.md. The file name clashes with the admin ADR-016; it should become ADR-017 (human housekeeping).

## business-analyst (9 Nov 2026)
- docs/requirements/SAH-34.md (17 rules) and docs/stories/SAH-34.md (4 stories, 77 ACs):
  - 34B scope enforcement (AC 1–25);
  - 34C Admitted list UI (26–43);
  - 34E break-glass (44–63);
  - 34F owner review (64–77).
- 34A and 34D are placeholders. 34G is out of scope.
- 35 open questions with defaults applied (DEC-023). Safety items for the clinical sign-off (OQ-30-6): OQ-34-26 and OQ-34-15.
- **Mockup deviation proposed by the BA, needs the human:** a scoped nurse also sees a count, with no names, of escalations past target in other wards (AC 20, 37). It is not in the mockup or the ADR. If the human refuses, only those parts are removed.
- OQ-34-35: the data part of 34A (ward table and clinic switch) is needed by 34B. Recommended: build it with 34B. Until the screen exists no owner can switch scoping on.
- `validate docs` fails only on ADM-2.md, as before.

## solution-architect, contract (9 Nov 2026, running)
