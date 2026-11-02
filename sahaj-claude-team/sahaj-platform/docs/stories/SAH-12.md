# SAH-12 · OPD workflow configuration
Mockup screen: Clinic setup → OPD workflow (`cfgWorkflow`)
Module: opd

## 1. Story
As the clinic owner, I want to switch Vitals and Counselling on or off, assign who owns each step, and publish changes as a new version, so the queue board matches how my clinic actually runs — without breaking visits already in progress.

Scope decision (human, 1 Nov): Token, Consultation and Billing stay fixed/always-on. The mockup lets Billing be switched off too, for clinics that collect the fee at the token instead of after consultation, but SAH-10 (already built) has no "fee collected before consultation" mode — every visit goes through the Billing counter to reach Done. Building that billing-timing option is out of scope for this story. Case paper print and Follow-up set are stored as on/off toggles (the mockup shows them in the same builder) but have no effect yet: there is no print feature and no Follow-ups story built yet. They are ready for those features to pick up later with no schema change.

## Acceptance criteria
1. Given the OPD workflow screen, then it shows the clinic's 8 steps in order (Token, Queue, Vitals, Consultation, Counselling, Case paper print, Billing, Follow-up set) with an on/off switch, an owner (Front desk, Nurse, Doctor or Owner — "Counsellor" from the mockup has no backing role in this system, so Counselling's owner choices are the same four), and Token/Consultation/Billing locked on with no owner change.
2. Given a change to Vitals or Counselling (on/off or owner) or Case paper print / Follow-up set (on/off, no owner), then it is held as a draft and does not affect the live queue until published.
3. Given "Publish", then it becomes version N+1: a flow preview shows the resulting step order, and new tokens from that point use it. A visit issued under an earlier version keeps moving through that version's steps until Done — publishing never changes a visit already in progress.
4. Given the Queue board (Today), then its columns are the enabled stage steps (Waiting, Vitals if on, With doctor, Counselling if on, Billing) of the currently published version, plus one column per stage still in use by an older version's in-progress visit, so no card is ever stranded off-screen.
5. Given a non-owner, then the screen is read-only (same as every other owner-only config panel: SAH-7's discount limits, SAH-11's booking rules).
6. Given any publish, then it is audited (who, what, when, before/after step lists) and tenant isolation (RLS) applies exactly as prior stories.
7. Given Vitals is switched off, then a walk-in token's first stage is With doctor, same rule SAH-9 already has for `vitalsOn`; given Counselling is switched on, then a visit moving forward from With doctor goes to Counselling before Billing, same as the already-built COUNSEL stage.

## 3. Technical design
- `opd.workflow_draft`: `tenant_id` (PK), `steps jsonb not null`, `updated_at/by`, `version bigint`. FORCE RLS. Lazy-created on first write (same pattern as `billing.bill`/`opd.booking_rules`); read before any write synthesizes it from the same defaults as `opd.workflow_version` below.
- `opd.workflow_version`: `id`, `tenant_id`, `version_no integer`, `steps jsonb not null`, `published_at timestamptz not null`, `published_by uuid not null`. FORCE RLS, append-only (no update/delete grant — a published version is permanent history), unique `(tenant_id, version_no)`, index `(tenant_id, version_no desc)` for "find the current version" (`max(version_no)`). No row is seeded by migration; the first `GET` or the first token issued on a tenant with none lazily inserts `version_no = 1` from the fixed defaults below (same lazy pattern already used elsewhere this project), so there is always at least one real, queryable version once the clinic is in use.
  - `steps` shape (both tables): JSON array of `{key, on, owner}` in display order, `key` one of `token, queue, vitals, consult, counsel, casepaper, billing, followup`. Defaults: token/queue/consult/billing always `on:true`; vitals `on:true, owner:"NURSE"`; counsel `on:false, owner:"DOCTOR"`; casepaper `on:true`; followup `on:true`. `owner` is a role code (`FRONT_DESK/NURSE/DOCTOR/OWNER`) or absent for steps with no owner (queue, casepaper, followup — matching the mockup, which only shows the owner selector on steps that have one).
- `opd.visit` gains one column: `workflow_version_no integer not null default 1` (additive, no backfill needed — existing rows correctly default to version 1, which is also what gets lazily created if nothing was ever published). Not a foreign key (versions are per-tenant history, looked up by `(tenant_id, version_no)`, and a version row always exists by the time any visit can be created, by the lazy-create rule above) — stamped once at `issueToken`, never changed afterwards.
- `VisitService` changes: replace `enabledStages(boolean vitalsOn)` with `enabledStages(WorkflowSteps steps)` returning `WAITING, [VITALS], WITH_DOCTOR, [COUNSEL], BILLING, DONE` (bracketed = only if that step is on) — this directly finishes the TODO already left in the current code's comment ("COUNSEL has no on/off setting yet"). `issueToken` reads the tenant's *current* version (lazily creating v1 if missing) to compute the first stage and stamps `workflow_version_no` on the new visit. `moveStage` reads the *visit's own* `workflow_version_no` (not the current one) to compute its enabled-stage list, so a visit started under an older version keeps that version's steps for its whole life, per AC3. `ownedStages()` (the "mine" filter) reads the owner role from the current version's steps for VITALS/COUNSEL instead of the hardcoded `Set.of(VITALS)`/`Set.of(WITH_DOCTOR)`, so re-assigning a step's owner actually changes whose "My queue" it shows up in.
- New `opd.api`-level read the frontend needs for Today's columns: `GET /opd/visits/active-versions` is unnecessary — instead, `GET /opd/visits/today` (existing SAH-9 endpoint) already returns each visit's `stage`; the frontend computes "legacy" columns client-side from whatever stages appear among today's non-done visits but aren't in the current published steps, exactly mirroring the mockup's own `columns()` function. No backend change needed for that beyond what AC4 already requires from the published-version steps endpoint below.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/opd/workflow/draft` | any clinic role | none | `WorkflowSteps` (the draft, or a fresh copy of the current published version if no draft was ever saved) | 401 |
| PUT | `/opd/workflow/draft` | OWNER | `{steps: StepInput[]}` | `WorkflowSteps` | 400, 403 |
| GET | `/opd/workflow/current` | any clinic role | none | `WorkflowVersion` (the current published version, lazily created if none exists) | 401 |
| GET | `/opd/workflow/versions` | any clinic role | none | `WorkflowVersion[]` (history, newest first, for the flow-preview "what changed" list) | 401 |
| POST | `/opd/workflow/publish` | OWNER | none (publishes the current draft) | `WorkflowVersion` | 400 (nothing to publish / fails the critical check below), 403 |

`steps`/`StepInput` validation (400 on failure, mirrors the mockup's critical warnings in `wfCheck`): `token`/`queue`/`consult`/`billing` must stay `on:true` and keep their fixed owner (or no owner); `counsel`'s owner, if set, must be one of `FRONT_DESK/NURSE/DOCTOR/OWNER`; step order in the array is the display/flow order and is preserved as submitted (no server-side reordering), but `token` must be first and `billing` must be before `done` implicitly (i.e. `billing` must be the last non-fixed-trailing step before case paper/follow-up... concretely: reject if `casepaper` or `followup` appears before `consult`, matching the mockup's own critical checks, lines `wfCheck` `casepaper`/`consult` and the billing-off check (not applicable here since billing can't be off)).

All paths under `/api/v1`. Contract: `docs/api/opd.yaml` (extend the existing file).

## 5. Tasks
### Tasks — Backend
- Migrations: `opd.workflow_draft`, `opd.workflow_version`, `opd.visit` + `workflow_version_no` column.
- `WorkflowService`/`WorkflowRepository`: get-draft (lazy default), save-draft (OWNER), publish (OWNER; validates the fixed-steps/ordering rules above, copies draft → new version row, bumps `version_no`), get-current (lazy v1 create), list-versions.
- `VisitService`: swap `enabledStages`/`ownedStages`/`issueToken`/`moveStage` to the version-aware steps as described above. Existing SAH-9 tests for vitals-on/off must still pass unchanged (counsel defaults to off, so default behaviour is identical to today).
- Audit: `WORKFLOW_PUBLISHED` with before/after step lists (no patient data in this entity type, so no redaction concerns beyond the standard entity-type check).
- Tests: default steps match the documented defaults, draft/publish round-trip, publish bumps version and visits created after keep using it, a visit created before a publish keeps its original version's stage list after the publish, non-owner 403 on draft-save/publish, counsel on/off changes `moveStage`'s path and `ownedStages`, the ordering/fixed-step validation rejects a bad submission with 400.

### Tasks — Frontend
- Clinic setup: new "OPD workflow" panel (`frontend/src/app/features/config`) — steps list with on/off switches (Token/Consultation/Billing shown locked), owner dropdown restricted to the four real roles, flow preview strip, version badge ("Live · vN" / "Draft · not published"), Publish/Discard actions, OWNER-only edit (read-only banner otherwise, matching SAH-10/11's existing wording pattern).
- Today/Queue board (`frontend/src/app/features/queue` or wherever SAH-9 put it): compute columns from `GET /opd/workflow/current`'s steps instead of any hardcoded list, plus the client-side "legacy column" rule from AC4/the mockup's `columns()`.
- Unit tests for the step on/off → column list logic, the fixed-step lock in the UI, and the publish/discard flow (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: fee-timing-before/split and a fully optional Billing step (human decision, 1 Nov — see Scope decision above); step reordering beyond the fixed Token-first/Consultation-before-Counselling-and-Billing rule (the mockup's free drag-reorder of Vitals/Counselling/Case paper/Follow-up relative to each other is not built — their relative order is fixed as listed in AC1, only on/off and owner change); the `wfCheck` staffing warnings ("no active nurse/counsellor") — informational only in the mockup, not a hard rule, deferred; automation rules / alerts and escalation (`cfgRules`, a separate mockup screen) — future story.
Open questions: none blocking; proceeding on the defaults above per DEC-008 and the human's 1 Nov scope decision.

## 7. Status log
