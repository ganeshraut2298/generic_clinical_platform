# SAH-29 · IPD packages (read-only slice) — handoffs

Decision: DEC-030 (the human chose the read-only slice, 9 Nov 2026). Branch: the current working branch; the tree holds other stories' work.
Approvals: the human's chat go-ahead of 9 Nov 2026. The `SAH-29 | stories` and `contract` lines in docs/approvals.md are still the human's to add.

## solution-architect (9 Nov 2026)
- New design note docs/architecture/SAH-29-ipd-packages.md and ipd.yaml changes: GET /packages (OWNER, FRONT_DESK, NURSE, DOCTOR), the package copy on AdmissionDetail, and the running bill rules from the mockup `ipLines`.
- Stored shape: new table ipd.package (SELECT-only for the app), and three nullable all-or-none columns on ipd.admission with no write grant. A set-once trigger is recommended. Nothing destructive.
- The YAML was parsed by the tech lead with SnakeYAML: OK, 53 paths.
- Open points: DEC-030.

## business-analyst (9 Nov 2026, running)
- Aligns docs/stories/SAH-29.md with the read-only decision.

## Next
- db-engineer, then java-developer ∥ angular-developer, QA, and the reviews. These wait until SAH-37's QA and fix pass have released the backend lock, because a new migration would change the database under their Maven runs.
