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
- **Done (9 Nov 2026).**
  - **New files:**
    - ADR-017-ipd-patient-scope-guard-and-break-glass.md (PROPOSED)
    - docs/architecture/SAH-34-ward-scoping-and-break-glass.md
  - **ADR-016 (ward scoping)** got a pointer line to DEC-031 and ADR-017.
  - **ipd.yaml** (additive; the tech lead parsed it with SnakeYAML and it is OK):
    - `x-scope-guard` on 26 operations, the shared OutsideScope response, and the scope fields on the list and the Safety board;
    - 4 emergency-access endpoints (picker, grant, owner list, review);
    - 3 ward endpoints for the data part of 34A, marked `x-needs-human` because the screen is not in the mockup.
  - **Stored shape (additive only):**
    - `ipd.ward_assignment`;
    - `ipd.config.nurse_ward_scope`;
    - `ipd.emergency_access_grant` with a database clock and set-once review;
    - `ipd.clinical_event.grant_id`.
  - **Coverage:** `IpdScopeCoverageTest` proves that no endpoint skips the guard.
  - **Open points for the human:**
    - OQ-34-26: keep or drop `otherWardsPastTarget`, which is optional;
    - the missing 34A screen;
    - OQ-34-15 and OQ-34-31;
    - the clinical sign-off before any clinic switches scoping on.
  - **Build order:** db-engineer, then java-developer ∥ angular-developer, QA and reviews, after the backend lock is free.

## db-engineer (9 Nov 2026, running)
- Writes V2026_12_06_1000__ipd_ward_scope_and_break_glass.sql and docs/database/SAH-34.md (data part only). It waits for the backend lock before `validate db`.
- **db-engineer done (9 Nov 2026).**
  - **Files:** V2026_12_06_1000__ipd_ward_scope_and_break_glass.sql and docs/database/SAH-34.md.
  - **Verification:** `validate db` PASSED (exit 0). It applied the migration on a clean schema and the existing suite stayed green. The plans are expected, not run; QA or the java-developer fills in the real output.
  - **Tables:**
    - ipd.ward_assignment: FORCE RLS; column-level grants; a guard trigger; no delete.
    - ipd.config.nurse_ward_scope: default false.
    - ipd.emergency_access_grant: a trigger stamps the database clock (30 minutes); the review can be made once only and never by the grantee.
    - ipd.clinical_event.grant_id.
  - **Notes for the java-developer:**
    - Use column-level updates (`@DynamicUpdate` or a native update).
    - Test liveness in SQL as `expires_at > clock_timestamp()`, both at the start of the request and under the admission row lock.
    - The error table is in section 7 of the design note.
  - **Go-live note (tech lead decision):** adding `grant_id` to ipd.clinical_event holds a table lock while the check and the key are validated, which takes milliseconds in dev and test. If that table holds more than about 5 million rows on release day, split the constraints into NOT VALID plus a follow-up VALIDATE. Nothing is released yet, so this is recorded as a go-live check for the human.
- **The build is held.** The java-developer and angular-developer for SAH-34 wait for SAH-37's QA to finish, because they change the same files (AdmissionService, AdmittedCards and the IPD controllers). The switch is off by default, so no clinic is scoped until the 34A screen exists.
