# Code re-review · DEC-025 audit fix pass (SAH-35, SAH-36, SAH-38)

Reviewer: code-reviewer, read-only. Date: 8 Nov 2026.

**Verdict: APPROVE WITH COMMENTS.** There are no Blocker or Major findings. Every G-item fix does what the QA report says. No test assertion was weakened: MedicineRoundIT tightened `>=3` to `==1`, and the layout tolerances are 1 px or less.

| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | ProblemExceptionHandler.java `primaryDbMessage` (~398-433) | Cutting at the first quote also strips the table and constraint names, so the log line can no longer be used to diagnose the error. | java-developer | FIX with security #4: keep identifiers after relation/column/constraint/table/schema; cut values (quotes after value phrases, `Detail`, `Key (`, text after a colon) |
| 2 | Minor | docs/api/ipd.yaml, platform.yaml | The malformed-`{id}` 400 is listed on only 4 operations, and the 413 appears only in the description. | solution-architect | FIX: add shared `BadRequest` and `PayloadTooLarge` responses, or state the general rule once (no behaviour change) |
| 3 | Minor | shared/format/ist-clock.ts:14-20 | The shell clock shows lowercase am/pm, but the UI rule is "11:05 AM". | angular-developer | FIX |
| 4 | Minor | RequestBodySizeLimitFilter.java:63-64, SecurityConfig.java:163-164 | An anonymous caller can send up to 64 KB before getting a 413. | java-developer | FIX (same as security #3): 4 KB cap when there is no principal |
| 5 | Nit | AccessLogFilter javadoc, ApiAccessLogIT | A signed-in 404 logs `/**`, but the doc says `unmatched`. | java-developer | FIX: map `/**` to `unmatched` (IT owned by qa-engineer) |
| 6 | Nit | MedicineRoundReader.java:45-56 | A constructor that only tests use. | java-developer | FIX |
| 7 | Nit | ist-clock.ts, ist-date.ts, emergency.model.ts, pin.model.ts line 1 | An import sits above the file header comment. | angular-developer | FIX |
| 8 | Nit | team.page.ts, emergency.model.ts, pin.model.ts, config-format.ts | Formatting-only re-wraps are mixed into the fix pass. | tech lead | Proposed as a separate `style:` commit |
| 9 | Nit | admitted-panel.component.ts ~118-130; give-sheet.component.ts ~45 | Member order and import order are wrong. | angular-developer | FIX |
| 10 | Info | frontend/e2e mar/safety/sah31-world | New in the index, so they cannot be compared against HEAD. Reading them shows stronger assertions. `serve.mjs E2E_NO_KINETIC` is SAH-39 scaffolding. | tech lead | Commit SAH-39 items separately; `git add -f` the e2e files |

Carried over for the human: R-1 (the frosted-glass dialog takes longer than 150 ms to show its first frame at 1920 px), G-5 (contrast), and the `validate frontend` flag.
