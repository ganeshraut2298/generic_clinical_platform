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
