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

## business-analyst (9 Nov 2026): done
- docs/requirements/SAH-29.md (new) and docs/stories/SAH-29.md are aligned with DEC-030.
- AC5 now says that no segment line appears on the bill after a transfer.

## db-engineer (9 Nov 2026, running)
- Writes V2026_12_05_1000__ipd_packages.sql and docs/database/SAH-29.md. It waits for the backend lock before `validate db`.

## db-engineer (9 Nov 2026): migration written; validation inferred, not run by the author
- **Files:** V2026_12_05_1000__ipd_packages.sql and docs/database/SAH-29.md.
- **Built:**
  - ipd.package: FORCE RLS, SELECT only for the app, never deleted, seeds nothing.
  - Three nullable columns on ipd.admission: an all-or-none check and range checks; the app has no write grant.
  - A set-once trigger: it refuses a change or a clear, and refuses setting a package on a DISCHARGED admission, including by an insert.
- **Validation:** the author never got the backend lock, so it ran no `validate db` itself.
  - The SAH-34 db-engineer's `validate db` (exit 0, clean schema) ran after this file and the SAH-34 migration were both on disk and in target/classes (19:49 and 19:50), so it applied V2026_12_05 as well and passed. This is an inference from file times and from the engineer's note that V05 existed. If a precise proof is wanted, QA or the java-developer re-runs `validate db`.
- **Notes for the java-developer:**
  - The package list query is `... where active order by created_at, id`.
  - Add the three columns to the admission reads and map them as not insertable or updatable.
  - A test sets a package through the migrator: `no force` RLS inside one transaction, the insert or update, then `force` again, as `DischargeSupport.insertSignoffAt` does. The SQL is in docs/database/SAH-29.md section 4.
  - Do not insert a DISCHARGED admission with a package directly. Any test that lists the ipd tables needs `ipd.package` added.
- **Next:** java-developer ∥ angular-developer. They wait for SAH-37's QA to finish.
