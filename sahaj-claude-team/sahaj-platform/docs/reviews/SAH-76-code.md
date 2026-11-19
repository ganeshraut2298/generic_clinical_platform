# SAH-76 · Code review

Date: 16 Nov 2026. Reviewer: code-reviewer (read-only). Branch: feature/SAH-39-home-kinetic-background, working tree vs HEAD.

**Verdict: CHANGES REQUIRED** (0 Blocker, 2 Major, 8 Minor, 4 Nit). No patient data seen in logs, audit maps, events or problem details; no new lock, library, scheduler or push path. Nothing was run; no browser check.

## Findings

| # | Severity | Where | Finding | Fix (owner) |
|---|---|---|---|---|
| 1 | Major | `MedicineAssembler.java:152-160, 262-272`; `ExecutionSlots.java:46-49`; `JdbcDoseRepository.java:51-55` | The EMR execution line (N late, next due) uses `doses.findBetween`, which includes corrected doses (undone, recorded in error); `ExecutionSlots.of` makes every row a `LiveDose` with no `live()` or PRN filter. After Undo or Recorded in error the MAR shows the slot due or late again but the doctor's EMR still counts it as recorded: "N late" under-counted, "Next due" skips the slot. Breaks ADR D2 / story 76-4 (one slot rule, counts agree). Attempt-1 corrected + attempt-2 live depends on map order. No test. | Filter like `MarAssembler.liveScheduled` (`d.live() && !"PRN".equals(d.doseKind())`), ideally reuse it; `notGiven` keeps all rows. Unit test (undone → LATE again) and IT on the EMR line after undo. (java-developer; qa-engineer for the IT) |
| 2 | Major (safety, contract decision) | `MedicineAdministration.java:398-411`; `MarAssembler.java:352-368` (`prn()`); `PrnRules.java`; ipd.yaml `PrnLine.lockReason` (line 4744) | A PRN order's start and end are never enforced: a 3-day PRN stays givable on day 4 until stopped; a PRN starting tomorrow is givable now. Contract silent. | Architect decides the answer (e.g. 409 `dose-not-due` or a new type) and adds `NOT_STARTED`, `ENDED` to `PrnLine.lockReason`; java applies it in `decide` and `MarAssembler.prn` with tests at start-1 min, start, end, end+1. Until then a go-live condition. |
| 3 | Minor | `NoticeClock.java:40-46` vs `DoseSlots.java:233-241` | STAT clocks disagree for up to 59 s (whole-minute rule in DoseSlots vs instant compare in NoticeClock): MAR says due while Orders tab says overdue. STAT slot defined in three places (`DoseSlots.onceSlot`, `OrderNoticeService.summary:335`, SQL `min(v.created_at)`). | Use the same whole-minute rule or one helper; pass the one slot instant; test at window + 30 s. (java-developer) |
| 4 | Minor | `OrderChangeService.java:130-131`; `MedicineRules.java:331` | `change()` raises the `doseUnit` 400 after the 409 checks; 0.4.2 A8 documents field order for placement only. | Document in contract, or look up the drug before the body check. (architect or java) |
| 5 | Minor | `OrderNoticeService.java:294-296, 156`; `PharmacistReviewService.java:203-206`; `MedicineAssembler.java:407-411` | Each ward-panel / review-list reload writes one record-access row per patient on the page (≤ 50), on every changeToken change, per open screen: write amplification. | Accept for the capacity note, or dedupe per user per patient for a short time. (architect decision) |
| 6 | Minor | `OrderNoticeService.java:338`; `JdbcOrderNoticeRepository.java:228-238` | Poll summary reads every open notice id each 30 s per screen just to hash them. | Hash in SQL or use count + max timestamps. (java-developer) |
| 7 | Minor | `order-notices.store.ts:63-71, 99-103, 28-29` | Fake `DestroyRef` cast into `startVisiblePoll`; after a 403 the poll stays dead for the session while other consumers are attached. | Return a stop function from `startVisiblePoll`; reset `refused` on a new consumer. (angular-developer) |
| 8 | Minor | `NursingSettingsService.java:277-286, 152-156` | A rename alone writes an audit row with identical before/after (name not in snapshot). | Add `name` to the snapshot or skip. (java-developer) |
| 9 | Minor | `DoseSlots.java:202-206` | Known: an older version's unrecorded slot before the change time can never be given or charted not given. | Keep on go-live list; option: allow it while the new version is unverified. (architect) |
| 10 | Minor | tests | Not covered: read-only / not-admitted on order actions, notice overdue lines, MAR states ON_HOLD/STOPPED/REPLACED/QUERIED via the MAR, device label on order actions, EMR line after correction (#1). | qa-engineer |
| 11 | Nit | `DoseRules.java:70`, `MedicineAdministration.java:401` | `hmm(Instant)` is cryptic. | Rename (java) |
| 12 | Nit | `OrderNotices.record`, `acknowledgeByDose`; `PharmacistReviews.openForDose`, `closeForDose` | `now` parameter unused. | Drop or use (java) |
| 13 | Nit | `AdmittedCards.java:72-78` | Setter injection `required=false`. | Constructor injection with no-op default, later (java) |
| 14 | Nit | `ipd-device.ts` | Device id in localStorage (no patient data; ADR-027 D11). | None |

## Checks that passed
- **Contract:** dose check order per ADR D7; audited refusals returned (committed) not thrown; problem types match; 0.4.1 replay rules (state for change/hold/resume/query/reply, key for dose and correction); 0.4.2 field order and 400 `slotAt`; role 403s and 400s not audited (ADR Q11).
- **DoseSlots single slot rule:** MAR, tab count, round, dose endpoint call it; only the STAT clock arithmetic (#3) and the ExecutionSlots input (#1) stray.
- **Locks and write order:** admission row `for no key update` the only lock; settings use the existing clinic advisory lock; head/version write order fits the deferred trigger; one READ_COMMITTED transaction.
- **Modules:** `com.sahaj.ipd` only, other modules via `.api`; platform only gains the CORS header; opd/billing/patient changes are DEC-054 and SAH-75 (already reviewed).
- **N+1:** fixed number of statements on MAR, round, cards, notices, queue (only #5 writes per row).
- **Dead code:** no `Stub*` left.
- **Frontend:** OnPush + signals; only timers are the display tick, undo countdown and visible-poll; no "alerted" text; PIN cleared after each answer; no override offered after a STOP; Safety tab adds `reviewsOpen`.
- **Re-checks:** AdmittedCardsTest D1, ScopeRaceIT 5 s, three 0-byte ITs deleted, VitalsCatalogIT +2 triggers, older-IT edits track the migration only; no weakened assertions, no `@Disabled`.

## Merge conditions outside this review
`docs/database/SAH-76-backfill-check.sql` run as postgres on sahaj_dev (DEC-056); human confirms the 0.4.1 and 0.4.2 line changes and the NEW texts.

---

# Round 2 · go-live fix round (17-18 Nov 2026)
Reviewer: code-reviewer (read-only: static reading and `git diff`; no Maven, npm or Playwright). The SAH-76 main code of this round is in HEAD 4192d3c "##IPD"; Pharmacy (SAH-78/79) was out of scope.

**Verdict: CHANGES REQUIRED** (0 Blocker, 1 Major, 4 Minor, 5 Nit). No patient data in logs, audit maps, deny entries, scan-stop rows or problem details.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| 1 | Major | `give-sheet.component.html:318-339`, `.scss`, `give-sheet.component.ts:541-545`, `shared/ui/sheet-toast.ts:16-22`, `e2e/mar.spec.ts:996` | QA D1 confirmed: the 8 s error toast covers Given / Record not given. The sheet grew with step 2 and the override; plain `.sheet` has no sticky footer (only `.sheet.wide`, `_console.scss:800`). | Sticky footer (the `wide` rule, as the mockup's MAR sheet); toasts `verticalPosition: 'top'` here, or drop the toast where an in-sheet line exists; rerun mar.spec.ts:996 desktop and phone. | angular; qa reruns |
| 2 | Minor | ipd.yaml (override D, ~1985), ADR-027 A9 vs migration `:124`, `SAH-76-review.md:28` | Doc only: "slot null for PRN" vs `slot_at NOT NULL`; java stores the DB minute and ignores it on read. Behaviour correct. | Reword: "slot, or the dose minute for a PRN (not compared)". Do not change the migration. | architect |
| 3 | Minor | `IpdJourneySupport.java:125-215` | `disable trigger user` disables every user trigger, including the deferred head-consistency check in `stampVersion`. Other helpers name one trigger. | Name the trigger; keep the head check live. | qa |
| 4 | Minor | tests | Not covered: exact 15:00, the PRN end boundary, read-only/not-admitted for query/reply/acknowledge, SELF_CHANGED on review rows; fixed-clock ITs fail before 08:05 IST. | Boundary ITs at 14:59/15:00/15:01; `greatest(clock, fixed)` in all fixed-clock ITs. | qa |
| 5 | Minor | `V2026_12_16_1000` vs Pharmacy `V2026_12_17_*` | A DB that applied a `V2026_12_17_*` file before 16_1000 fails Flyway validation. | Merge SAH-76 first or rebuild the dev DB; never out-of-order. | tech lead |
| 6 | Minor | `runbook-ipd-on.sql:17-19,81-82,106` | Snapshot "same keys as the app's" may drift once Pharmacy changes the entitlement adapter. No dry run yet. | Compare keys after Pharmacy lands; human dry run. | db |
| 7 | Nit | `JdbcDoseRepository.java:106,112` | `now()` (transaction start) vs `clock_timestamp()`; the window is longer, never shorter. | Note it as intended, or use `clock_timestamp()`. | java |
| 8 | Nit | `MarSupport.java:469-470`, `Sah76ReviewRoundIT.java:46` | Orphan Javadoc; `ageStops` duplicates `ageScanStops`. | Move the Javadoc; reuse the helper. | qa |
| 9 | Nit | `MedicineAdministration.java:281-422` | `decide` is ~140 lines. | Split by contract step later. | java |
| 10 | Nit | `runbook-ipd-on.sql:138` | `-v dry_run=0` also counts as a dry run (safe). | Document it. | db |
| 11 | Nit | `AdmittedCards.java:75,80` | Setter injection (earlier #13). | Later. | java |

Answers:
- **Check order** matches contract (E) in both transactions (`decide` from `prepare` and `recordLocked`).
- **Stop row:** it commits before the 409 (`DoseService` is not transactional; the refusal is returned, not thrown).
- **Wristband:** no override without a wristband.
- **Self-verification:** verify and query use the orderer or the current version's author, rechecked under the lock; a DB 23514 stays a 500.
- **Trigger helpers:** safe (one migrator transaction; rollback restores the trigger).
- **Boundaries and texts:** module boundaries clean; texts match 0.4.3.

Earlier findings:
- Closed: 1, 2, 3, 4 (by contract), 6, 7, 8, 11, 12.
- Still open:
  - 5: accepted.
  - 9: go-live list.
  - 10: partly closed.
  - 13: Nit.
  - 14: no action.

Merge conditions:
1. Fix #1 and rerun the e2e.
2. Settle the migration order (#5).
3. Human sign-off on the 7 NEW texts and the override position.
4. Human dry run of the runbook.
5. Clean full gate on a quiet tree.

---

# Round 3 · after round 2 (D1 fix, runbook M-2/L-2, ipd.yaml 0.4.4, QA round) · 18 Nov 2026
Reviewer: code-reviewer (read-only; static reading and `git diff` against HEAD 4192d3c; Pharmacy out of scope).

**Verdict: CHANGES REQUIRED** (0 Blocker, 1 Major, 4 Minor, 5 Nit). No new patient-data path. Once D8 is fixed, or accepted with the test adjusted: APPROVE WITH COMMENTS.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| 1 | Major | `frontend/e2e/mar.spec.ts:1021-1044`; `give-sheet.component.ts:567-570`; `shared/ui/sheet-toast.ts:21-22` | D8 open: on phone-360 the 8 s top toast covers the sheet's Close button (`gv-x`). Not a D1 regression (phone was already at the top). Do not set `pointer-events: none` on the pane (it would disable the toast's Close). | (a) shorter toast on phone (3-4 s), or (b) drop the toast where an in-sheet line has the same words (it is `announce: false`), or (c) a `panelClass` offset below the header. Rerun `mar.spec.ts:1021` on both projects. | angular; qa |
| 2 | Minor | `Sah76ReviewRoundIT.java:405,420,426,431`; `MarSupport.java:484-500` | R-14's 899 s "inside" leaves a 1 s margin before the request's `now()`; it can flake under load. The name overclaims "exactly". | Use 870 s for inside, keep 900 and 901 for outside, and reword the name and comment. | qa |
| 3 | Minor | `Sah76NursingSupport.java:224-236`; `Sah76NoCodeLoadedIT.java:51`; `Sah76MigrationIT.java:200` | `QAS76*` cleanup only in `@AfterEach`; a killed JVM leaks codes, so later preconditions fail. | Add a `@BeforeEach`/`@BeforeAll` sweep of `code like 'QAS76%'`. | qa |
| 4 | Minor | QA handoff | Round 2 #4 partly closed: 5 classes not proven before 08:05 IST; stop-reason text, PRN 15-minute edge and CorsPreflightIT not covered. | Run the 5 before 08:05 once, or fix the helpers; keep the rest on the go-live list. | qa |
| 5 | Minor | `V2026_12_16_1000` vs Pharmacy `V2026_12_17_*` | Merge order (round 2 #5) is unchanged. | Merge SAH-76 first or rebuild `sahaj_dev`; never out-of-order. | tech lead |
| 6 | Nit | `MedicineAdministration.java:376-377,619-641` | Up to 3 `drug_code` lookups per mismatch (primary-key reads, guarded). | Resolve the drug of the code once in `decide`. | java |
| 7 | Nit | `runbook-ipd-on.sql:57-59,68-69,77` | The mobile guard misses `98765.43210` / `98765-43210`; a refusal ends with `\quit`, so the exit code is 0. | Strip separators before the digit test; end a refusal with an error so the exit code is non-zero. | db |
| 8 | Nit | `ScopePlansIT.java:163-169`; `VitalsSupport.java:396-402` | Still use `disable trigger user` (outside the SAH-76 files). | Name the trigger later. | qa |
| 9 | Nit | `MedicineAdministration.java:281-428` | `decide` is ~148 lines. | Split later. | java |
| 10 | Nit | `AdmittedCards.java:75,80` | Setter injection. | Later. | java |

Confirmed:
- The 4-case decision matches (B1)/(E1) in both paths, and NO_CODE_LOADED comes before the PIN.
- NO_CODE_LOADED writes one `IPD_DOSE_REFUSED` with fixed keys and no stop row, deny event or mismatch audit.
- Scanned text is never stored or logged.
- The predicate is guarded (NFKC, 4-64 `[A-Z0-9-]`, PK read on global data), so there is no new oracle.
- Angular H1 matches; the D1 sticky-foot is scoped to the give sheet.
- Runbook M-2/L-2 are correct.
- Texts are identical in the contract, java, angular and tests.
- Residual (DEC-065 A, human): while barcodes are not loaded, a wrong medicine that is also not loaded gives "no code loaded", not a STOP. DEC-065 B is a go-live condition.

Round 2 status:
- Closed: 2, 6 (snapshot), 7, 8, 10.
- #1: closed for the footer; D8 is new #1.
- #3: closed in IpdJourneySupport (new #8 remains).
- #4: mostly closed (#2, #4).
- Open: 5, 9, 11; the 6 dry run is human.

Merge conditions:
1. D8 fixed, or accepted with the test adjusted.
2. Migration order.
3. Human sign-off: the 7 round-2 texts, "No code loaded" plus its detail, and the H1 behaviour (no toast, focus to the code box).
4. Runbook dry run and its negative test.
5. DEC-065 B pilot barcodes before the first real patient.
6. Clean full gate on a quiet tree.

## Round 4 (D8 check) · 18 Nov 2026
Reviewer: code-reviewer (read-only; static reading of the mar, sheet-toast and e2e diffs and the mockup; nothing run).

**Verdict: CHANGES REQUIRED** (0 Blocker, 1 Major, 4 Minor, 3 Nit). D8 itself is fixed: no path opens a toast while the give sheet is open; the Close button is proven clear by the hit-test at `mar.spec.ts:1037`. If a 360x740 browser check shows the lines in #1 are in view, #1 drops to Minor and the verdict becomes APPROVE WITH COMMENTS.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| 1 | Major | `give-sheet.component.html:308-320`; `give-sheet.component.ts:507-511, 538-543`; `e2e/mar.spec.ts:1898-1934` | `give-prn-lock` and `give-failure` are the last children of the scrolling sheet body (only the footer is sticky, `_console.scss:789-809`). The paths `prn-lock`, `key-reused`, `notice` (including the lost-answer case) and non-high-alert `pin` do not move focus or scroll, so on a 360x740 phone the line can be out of sight. The toast used to show it. Screen readers still get `role=alert`. The e2e checks use `toBeVisible()`, which does not test the viewport. | Bring the line into view after setting it (`afterNextRender` + `scrollIntoView({block:'nearest'})`), or render both lines in the sticky footer above the buttons. Add a 360 px e2e per path (PRN lock, 500, `client-key-reused`) that checks the line lies inside the viewport and above the footer. | angular; qa |
| 2 | Minor | `give-sheet.component.ts:507-511`; `mar.model.ts:828-854` | A 400 whose field keys point only to controls that are not drawn (for example `secondNurseId` on a non-high-alert sheet with a stale MAR) is now silent. | In `'fields'`, if no error key has a drawn control, set `failure` to the message. Add a unit test. | angular |
| 3 | Minor | `give-sheet.component.ts:445-456, 513-523`; `give-sheet.component.html:114-118` | `noCodeNotice` is cleared and set in the same handler, so a repeat `no-code-loaded` changes nothing in the DOM and is silent. | Clear `noCodeNotice` at the start of `send()`, with `mismatch` and `codeMismatch`. | angular |
| 4 | Minor | mockup `sahaj-mockup.html:8451-8456` vs `give-sheet.component.ts:485-545` | The mockup gives validation refusals ("Scan the wristband first.", second identifier, high-alert second nurse, "Choose why the dose is not given.") as toasts; the app now shows only in-sheet lines. A deliberate deviation from the mockup (rule 1), beyond round 3 option (b). | Human accepts it before merge; if not, keep the toast for validation refusals, placed below the sheet header. | tech lead (human) |
| 5 | Minor | `undo-toast.component.ts:46, 61, 71`; `dose-error-panel.component.ts:165`; `e2e/mar.spec.ts:1887` | Remaining toasts are inconsistent: not-given and reload toasts are announced, but the Given toast without undo, "Undone." and the marked-in-error toast stay `announce:false`, and the MAR page has no other live region. | Drop `announce:false` in those places (or record why not) and update the e2e at 1887. | angular; qa |
| 6 | Minor | `undo-toast.component.ts:61-71` | A late Undo answer (`done` 6 s, `refused` 8 s) can still open a toast over a give sheet opened in the meantime. Small window. | Defer or skip the toast while `MatDialog.openDialogs` is non-empty, or note the limit. | angular |
| 7 | Nit | `give-sheet.component.ts:476-479, 521-524` | `toast()` / `showGiven()` run before `dialogRef.close()`. Harmless. | Close first, then toast. | angular |
| 8 | Nit | `sheet-toast.ts:13-16`; `give-sheet.component.ts:126-137` | The `announce` doc and the give-sheet class doc do not state the new rule "no toast while the sheet is open". | Add it to the class doc. | angular |

**Checks:**
- **Silent paths:** none for screen readers except #2 and #3. Two paths are at risk for sighted users (#1).
- **Toast while open:** none from the sheet itself (each toast is followed by close; `takeUntilDestroyed`). The only residual source is #6.
- **e2e strength:** the pattern "in-sheet text first, then no snackbar" is sound, because `MatSnackBar.open` attaches synchronously. The rewritten R11 keeps the earlier strength.
- **Announcing after-close toasts:** correct; no double announcement.
- **Dead code:** none.

**Merge conditions (round 4):**
1. Fix #1 and rerun the mar.spec.ts F1/D8 tests on both projects.
2. The human accepts the mockup deviation in #4.
3. Decide #5 together with the e2e at 1887.
4. The round 3 conditions are unchanged: migration order, human text sign-off, runbook dry run, DEC-065 B, clean full gate.

## Round 5 (sheet header, layout height, presence) · 19 Nov 2026
Reviewer: code-reviewer (read-only; nothing run).

**Verdict: APPROVED WITH COMMENTS** (0 Blocker, 0 Major, 6 Minor, 4 Nit). The round 4 fixes work as intended: `layoutHeight` and `watchFooter` are correct and clean up fully; `GiveSheetPresence` is idempotent and does not leak; the late-Undo rule fits DEC-068 as narrowed to the give sheet; the MAR updates at once and only the toast is deferred; the header change is limited to `.sheet.sticky-foot` and does not leak to other sheets.

| # | Severity | File:line | Finding | Fix |
|---|---|---|---|---|
| 1 | Minor | `give-sheet-presence.ts:37-44`; `give-sheet.component.ts:487-497`; `undo-toast.component.ts:46-55, 63, 83` | A deferred Undo toast can replace the next dose's Undo toast. Dose A's Undo answer is slow; the nurse records dose B; `showGiven(B)` does not use the presence service, so B's toast opens at once while B's sheet is still closing; when B's sheet is destroyed, A's waiting "Undone." flushes and replaces B's toast. The dose stays recorded, but the 10 s Undo window of B is lost silently. No e2e records the second dose. | Route `showGiven` through `whenNoneOpen` too, so the queue runs in order. When a newer toast is queued, drop the older deferred Undone/refused text. Unit test (A deferred, B given, B's toast survives) and an e2e that records dose B. |
| 2 | Minor | `_console.scss:826-837`; mockup `:327-331, 1849-1850` | The sticky header differs from the mockup (not sticky, no background) and is not yet accepted (rule 1). `--sheet-solid` is not in `docs/design/tokens.md` (human-owned). | Human accepts or rejects; tech lead records a DEC; the light and dark visual check is a merge condition. |
| 3 | Minor | `_tokens.scss:24, 79` | `--sheet-solid` is a hand-computed hex and can drift if `--glass-hi`, `--bg` or `--scrim` change; dark is calculated only and has no e2e. The footer stays translucent, so header and footer look different. | Dark-theme pixel test and a tokens.md entry; or derive the value or add a comment listing what it depends on; or use the same solid value on the footer. |
| 4 | Minor | `e2e/mar.spec.ts:2588, 2612-2614, 2697` | The at-rest header test still has the old limit of 2 (the ruling is 3). The scrolled-content test is weaker than it looks: `behind.under` includes transparent containers, so it can pass with only a wrapper behind the header. | Limits and title to 3 (with the reason in a comment). Restrict `under` to `input,button,textarea` outside the header, or require a visible surface. |
| 5 | Minor | `undo-toast.component.spec.ts:27-28, 37, 190-196` | A mock for a dependency that no longer exists: `UndoToast` no longer injects `MatDialog`, yet the spec provides `otherDialogs` and asserts the length of its own fake. | Remove the provider and that assertion; rename the test to "with no give sheet open, shows at once". The e2e is the real proof. |
| 6 | Minor | `_console.scss:813-837, 1033-1035` | Phone keyboard not verified: sticky header (about 72 px) plus a wrapped footer (about 129 px) leave little room once the keyboard opens; the scroll paddings do not know about the keyboard. | Human check on a real phone (Android Chrome, iOS Safari) with the PIN and note boxes focused. If bad, use `dvh` and `interactive-widget=resizes-content`. |
| 7 | Nit | `_console.scss:831-832` | `backdrop-filter: blur(16px)` on an opaque header does nothing visible. | Remove (QA's scrolled test asserts the blur; update it together). |
| 8 | Nit | `undo-toast.component.ts:67-75`; `give-sheet.component.ts:488-490`; `sheet-toast.ts:13-20`; DEC-068:34 | Names and comments no longer match: `whenNoDialog` waits only for give sheets; the "close first, then toast" comment is not exact (`showGiven` runs while the component still exists); `announce:false` has no caller left outside specs; DEC-068 line 34 says "any open sheet". | Rename to `whenNoGiveSheet`, fix the comments, remove or justify the `announce` option; tech lead amends DEC-068. |
| 9 | Nit | `give-sheet-presence.ts:37-44` | No spec of its own. A `show` that throws drops the callbacks behind it and the error escapes the sheet's `onDestroy`. Queued callbacks keep drug names in a root service until a sheet closes (for example, logout with a sheet open). | Add `give-sheet-presence.spec.ts` (double release, two sheets, throwing callback, re-entrant `whenNoneOpen`); wrap each `show` in try/catch; clear the queue on logout. |
| 10 | Nit | `give-sheet.component.ts:652-655` | `transitionend` and `animationend` bubble from every control (focus rings, ripples, spinner); each runs `getComputedStyle` twice. | Optional: remove the listeners after the first end event after the pane shows, or filter on `event.target`. |

**Merge conditions (round 5):**
1. Fix #1 and add its e2e.
2. The human accepts the sticky header and `--sheet-solid` (#2) and does the light and dark visual check.
3. QA changes the limit to 3 and reruns the at-rest header test on both projects (#4).
4. Human phone-keyboard check (#6).
5. The round 3 and round 4 conditions are unchanged (migration order, human text sign-off, runbook dry run, DEC-065 B, clean full gate).
