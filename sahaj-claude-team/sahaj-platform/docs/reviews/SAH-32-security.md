# Security review · SAH-32 (IPD MAR and giving a dose with the bedside checks)

Reviewer: security-reviewer (read-only), 6 Nov 2026. Saved by the tech lead. The reviewer only read code; QA ran the suites. No new runtime libraries.

**Verdict: PASS.** No Critical or High. 3 Medium, 4 Low, 7 Info. Nothing blocks the local merge. S1 and S2 (with HARD-2) and S3 block go-live; S3 needs a human decision.

## Medium
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| S1 | StaffPinOps.java:210-221; InMemoryStaffPinLimits.java:30-69; StaffPinService.java:170-192 | A colleague's PIN can be guessed quietly over time. The per-caller cap (5 per 15 min, 10 per 24 h) is in memory (resets on restart, per instance). `failed_count` resets on a right PIN, so up to 4 wrong tries between the colleague's own uses never lock and nobody sees them. DDMM birthdays are allowed PINs. A caller who finds a colleague's PIN can record high-alert doses with that colleague as "second check". | (a) durable per (caller, colleague) count of failed colleague checks (DB or count audit rows with an actor+action index), weekly cap per pair; (b) tell the colleague "N wrong tries of your PIN by colleagues since your last use"; (c) accept the residual risk in ADR-011 Q5 only after (a). | GO-LIVE BLOCKER with HARD-2. Not fixed in this story: needs a new table/notice screen (design + mockup question). Own story "HARD-3 colleague PIN guessing" proposed to the human. |
| S2 | StaffPinOps.java:224-243; StaffPinService.java:183-192; InMemoryStaffPinLimits.java:44-47 | A hostile colleague can lock a colleague's PIN twice a day; with a restart/second instance/second caller a third lock switches it off (409 `pin-not-set`); the colleague's failed-check bucket also fills, so anyone naming them gets 429. Neither the colleague nor the owner is told. | Notify the colleague/owner on lock or switch-off; colleague-caused locks do not count toward switch-off; ward procedure when no second PIN works; shared limiter. | GO-LIVE BLOCKER with HARD-2. Design choice (colleague-caused locks not counting toward switch-off) goes to the human together with S1. |
| S3 | DoseRules.java:126-129 ("· wristband scanned"); WristbandCheck.java:32-44; give-sheet.component.html:20-39 | The identity box accepts typing and the identity bar shows the UHID, so a nurse can type the UHID from the screen without seeing the wristband; the permanent clinical event still says "wristband scanned". Same weakness OQ-32-1 removed with the `sfscan` button, in a slower form. | Event text "wristband checked" / "identity entered"; or store an entry-mode hint (scanner typing speed); or hide the UHID in the identity bar while the box is empty (mockup change). | HUMAN DECISION (ADR-011 Q1, DEC-018). Recommended: change the permanent event text to "identity checked against UHID" so the record never claims a scan that may not have happened. Go-live blocker. |

## Low
| # | Where | Finding | Tech lead decision |
|---|---|---|---|
| S4 | StaffPinException.java:126-127 | A colleague's wrong PIN answer says "N tries left", telling a guesser to stop before a lock. | FIX now: colleague answer without the count ("That PIN is not right."); own PIN keeps the count. |
| S5 | MedicineAdministration.java:286-289, 370-380; MarService.java:62 | No rate limit on mismatches and MAR opens; a double tap of a mismatch writes two permanent deny entries; one access row per MAR open. | HARD-2 (limits). Accept double deny entry; noted. |
| S6 | WristbandCheck.java:23, 69-75 | UHID-shaped deny scan allows 2-6 letters + 5-34 digits (41 chars, ADR says 40); a mobile or ABHA-like number can pass into permanent event text. | FIX now: bound digits to what the UHID generator produces and align the contract/ADR to 40 (architect for the YAML wording). |
| S7 | DoseService.java | Non-high-alert doses need no re-authentication on an unattended signed-in screen. | Backlog: idle screen lock for ward devices. |

## Info
I1 replay compares second nurse by display name (no leak; add userId to TeamMember later). I2 clientKey of another order gives 400 (harmless, v4 UUIDs). I3 two-person rule leans on the owner (as SAH-31 I3). I4 second nurse types the PIN on the giver's device (inherent; masked, cleared). I5 timing differs between NOT_ELIGIBLE/NOT_SET/LOCKED and WRONG/OK, no extra enumeration. I6 `wristband` has no maxLength in ipd.yaml:2949 or on the input: FIX now (`maxlength="200"` and contract maxLength). I7 opening the MAR writes a record-access row, against story AC9; safer direction (DEC-018 item 5); each tab switch adds a row.

## Passed
Role checks (token role then module Off/Read-only then format then membership role; repeated under the lock); tenant isolation (FORCE RLS, select+insert policies, composite FKs, tenant-filtered queries, 404 across clinics); append-only table (column insert grant, DB-clock `recorded_at`, update/delete/truncate triggers, guard trigger invoker with fixed search_path); colleague PIN mechanics (own hash, constant time, DB-clock lockout, eligibility three times, outside any transaction, counted although the request fails, audit with ids only, PIN dropped before the locked transaction, redacted `toString`, pepper missing 503); wristband (NFKC, whitespace/control removal, upper case both sides, >200 mismatch, no name in answer, deny entry committed, no probing of other patients' UHIDs); replay (tenant scoped, key must belong to the path order, no PIN check, one access row); errors and logs free of patient data; DPDP access rows; frontend (no innerHTML/storage/console/URL; PIN password field, one-time-code, cleared; Enter does nothing in the box; sheet cannot close while running).

## Done well
Prepare → PIN → locked-transaction shape; deny entry committed by returning a result; defence in depth (Java, guard trigger, pair checks, composite FKs); `toString` redaction on every record with PIN/scan/names; fake "Scan wristband" button not built.

## Process note
SAH-30/31/32 `stories` and `contract` lines are missing from docs/approvals.md (human).
