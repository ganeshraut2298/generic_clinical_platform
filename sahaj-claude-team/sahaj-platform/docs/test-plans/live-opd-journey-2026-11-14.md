# Live OPD journey, 14 Nov 2026

Decision: docs/decisions/DEC-054-live-opd-journey-test.md. Script: `node frontend/e2e/live/opd-journey.mjs` (run tag "Live", resumable; state kept per tag). Last run: 2026-11-14T19:07:12.785Z UTC.
Everything below was done through the app's own HTTP API on the dev platform as the clinic owner (and as the fake doctors and nurse). Patients are fake ("Test <Dept> nn Live", mobiles 900001xxxx). Nothing was deleted. No ids, tokens, codes or cookies are printed.

> **Run halted** at 2026-11-14T19:06:41.552Z: no answer from `PUT /opd/emr/records/{id}/draft` (server wedged). See Failures.

## Server checks
- GET /platform/health -> 200 UP
- GET /opd/emr/courses?kind=DENTAL -> 200; kind=BOGUS -> 400
- starter og layoutVersion 3 (status 200)
- starter onco layoutVersion 3 (status 200)
- starter gm layoutVersion 2 (status 200)
- starter dental layoutVersion 2 (status 200)
- starter surgery layoutVersion 2 (status 200)
- owner session: role OWNER, clinic "Apulki"
- modules: {"plan":"FREE","ipd":{"state":"off","trialEndsAt":null,"requested":false,"requestedAt":null},"ipdTrialAvailable":true,"hra":{"on":false},"menuCounts":{"simple":7,"advanced":10}}
- fee settings: timing before, follow-up 7 days / free
- workflow v1: steps on = token, queue, vitals, consult, casepaper, billing, followup

## Setup changes made in the clinic
- 15:56 UTC · added doctor "Dr Test GM Live" (DOCTOR, fee ₹ 300, fake mobile 9000000101)
- 15:56 UTC · added doctor "Dr Test Dental Live" (DOCTOR, fee ₹ 400, fake mobile 9000000102)
- 15:56 UTC · added service "Dressing" (Nursing, ₹ 150)
- 15:56 UTC · added service "Composite filling" (Dental, ₹ 1200)
- 15:56 UTC · added service "Root canal treatment" (Dental, ₹ 5500)
- 15:56 UTC · added department "General Medicine Live" from the General Medicine starter template with Dr Test GM Live assigned
- 15:56 UTC · published department "General Medicine Live" as v1
- 15:56 UTC · added department "Dental Live" from the Dental starter template with Dr Test Dental Live assigned
- 15:56 UTC · published department "Dental Live" as v1
- 15:56 UTC · added department "Obstetrics & Gynaecology Live" from the Obstetrics & Gynaecology starter template
- 15:56 UTC · published department "Obstetrics & Gynaecology Live" as v1
- 15:56 UTC · added department "Paediatrics Live" from the Paediatrics starter template
- 15:56 UTC · published department "Paediatrics Live" as v1
- 15:56 UTC · added department "Physiotherapy Live" from the Physiotherapy starter template
- 15:56 UTC · published department "Physiotherapy Live" as v1
- 15:56 UTC · added department "General Surgery Live" from the General Surgery starter template
- 15:56 UTC · published department "General Surgery Live" as v1
- 15:56 UTC · added department "Medical Oncology Live" from the Medical Oncology starter template
- 15:56 UTC · published department "Medical Oncology Live" as v1

Setup notes (not changes):
- could not add doctor "Dr Test OG Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.
- could not add doctor "Dr Test Paeds Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.
- could not add doctor "Dr Test Physio Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.
- could not add doctor "Dr Test Surgery Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.
- could not add doctor "Dr Test Onco Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.
- could not add nurse "Nurse Test Live": 409 https://sahaj.in/problems/plan-limit The free plan allows up to 5 users in total.

## Summary by department

Cell = patients where the step is OK / patients run. "Full journey" = every core step OK (register, appointment, arrive and token, queue, open EMR, fill required, finish check, sign, bill at counter, cash payment, receipt check).

| Department | Patients | Appt+token | Queue | EMR open | Required filled | Finish check | Signed | Bill at counter | Cash paid | Full journey | Extras OK |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| General Medicine | 2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 0/2 | 0/2 | 0/2 | 0/2 | 0/2 |

## Failures (4)

- **gm-01 · extras** (FAIL-EXTRA) `PUT /opd/emr/records/{id}/draft` → 503
  - detail: saved none; failed: section exam: 200  the server did not answer PUT /opd/emr/records/{id}/draft within the time limit; the run is stopped so that no more requests pile up on the server  || section followup: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.  || section procedures: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again. 
- **gm-01 · sign** (FAIL) `POST /opd/emr/records/{id}/sign` → 503 · problem `database-busy`
  - detail: This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.
- **gm-02 · extras** (FAIL-EXTRA) `PUT /opd/emr/records/{id}/draft` → 503
  - detail: saved none; failed: section exam: 200  the server did not answer PUT /opd/emr/records/{id}/draft within the time limit; the run is stopped so that no more requests pile up on the server  || section procedures: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.  || section followup: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again. 
- **gm-02 · sign** (FAIL) `POST /opd/emr/records/{id}/sign` → 503 · problem `database-busy`
  - detail: This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.

## Gaps (the API does not support the step)

- Visit receipt: billing.yaml 0.4.0 has no receipt operation for a visit bill (SAH-10 never had one; only course receipts RC-n exist, ADR-022 H4). The journey proves the payment through the bill (payment line, balance 0) and the counter lists.
- Doctor assignment to a starter-backed department: the contract only assigns doctors when a department is added (POST /emr/departments); there is no call to change them later.

<!-- triage:start -->
Triage, run 1 (14 Nov 2026, about 21:30 to 22:00 IST). The run stopped at the first patient; the dev server stopped answering and has to be restarted.

1. **BLOCKER, server wedged by one request (owner: java-developer; needs the server log to confirm).** On gm-01 (General Medicine, after "fill required" had saved fine) one `PUT /api/v1/opd/emr/records/{id}/draft` carrying the sections `exam` (EXAM_GENERAL), `procedures` (one SERVICES item, BILL_TODAY, qty 1, the clinic service "Dressing") and `followup` (FOLLOW_UP interval D7) got no answer for 60 s (client gave up; the server request never ended). Afterwards, for the same record: every `PUT …/draft` (exam alone, followup alone, procedures alone, 25 s each) and `POST …/sign` got no answer, while `GET …/records/{id}` and `POST …/finish-check` still answered. Finish-check run after the hung save showed "bill lines 0, follow-up none", so that first save never committed. About 10 minutes later every request that needs the database (OTP request, login) answered 500 internal-error or no answer: the connection pool is used up by requests that never end. `GET /platform/health` still says UP (it does not touch the database). This looks like the first save holding the record row (and a pool connection) and never finishing, then every later request on that record waiting on it and taking one more connection each. Which of the three sections starts it is NOT proven (the later single-section saves were queued behind the first). Most likely suspect from code reading: the `procedures` path, `EmrRecordService.syncServiceLines` (EmrRecordService.java:677-705) calling `EmrVisitChargeAdapter.syncEmrLines` (billing/application/EmrVisitChargeAdapter.java:56-110: `bills.getOrCreate`, `items.insertEmr`, audit) inside the draft transaction. Second suspect: the first FOLLOW_UP save. To confirm: restart the dev server, take a thread dump or the log line of the hung request (look for `Unhandled error` or a Hikari "connection not available" line) and check `pg_stat_activity` for a session "idle in transaction" or waiting on a lock. This script now saves each extra section on its own and halts the whole run at the first request without an answer, so the culprit section is named in the next run.
2. **Setup finding, not a bug.** The free plan allows 5 users in total (owner, front desk, one existing doctor, plus the two test doctors added here). The five other test doctors and the nurse (second checker) could not be added (409 `plan-limit`). Planned fallback in the script: Dr Test GM Live also takes og, paeds and onco visits and Dr Test Dental Live takes physio and surgery visits, the EMR record is switched to the matching "<dept> Live" department; the chemotherapy second check is then done by the other test doctor (a DOCTOR may be named checker). The departments for og, paeds, physio, surgery and onco therefore have no doctor assigned (the contract has no call to assign a doctor to an existing department).
3. **Observation.** The Queue "mine" list (`GET /opd/visits/today?mine=true`) is the My tasks filter by stage, not "my patients": a visit waiting at stage VITALS is not in the doctor's list. Not a defect; recorded so nobody expects it.
<!-- triage:end -->

## Per patient

### General Medicine

**Test Gm 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00002 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-14 06:00 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 2 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 2, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | FAIL (extra) | PUT /opd/emr/records/{id}/draft | 503 |  | saved none; failed: section exam: 200  the server did not answer PUT /opd/emr/records/{id}/draft within the time limit; the run is stopped so that no more requests pile up on the server  // section followup: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.  // section procedures: 503 database-busy This is taking longer than usual and w… |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile gm |
| sign | FAIL | POST /opd/emr/records/{id}/sign | 503 | database-busy | This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again. |
| bill_at_counter | BLOCKED |  |  |  | not run: step "sign" failed |
| payment_cash | BLOCKED |  |  |  | not run: step "sign" failed |
| receipt | BLOCKED |  |  |  | not run: step "sign" failed |

**Test Gm 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00003 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 1 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 1, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | FAIL (extra) | PUT /opd/emr/records/{id}/draft | 503 |  | saved none; failed: section exam: 200  the server did not answer PUT /opd/emr/records/{id}/draft within the time limit; the run is stopped so that no more requests pile up on the server  // section procedures: 503 database-busy This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again.  // section followup: 503 database-busy This is taking longer than usual and w… |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile gm |
| sign | FAIL | POST /opd/emr/records/{id}/sign | 503 | database-busy | This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again. |
| bill_at_counter | BLOCKED |  |  |  | not run: step "sign" failed |
| payment_cash | BLOCKED |  |  |  | not run: step "sign" failed |
| receipt | BLOCKED |  |  |  | not run: step "sign" failed |

## Run 2 (summary, restored; the script regenerates this file and had dropped the section)

gm-01, `--only gm --per 1`: PUT draft with section `exam` alone got no answer (client limit); followup alone and procedures alone then answered 503 `database-busy` (queued behind it); sign 503 `database-busy`. Other departments not run. Open question was: leftover lock on gm-01 or exam save blocks in code.

## Run 3 (15 Nov 2026)

Command: `SAHAJ_HTTP_TIMEOUT_MS=15000 node frontend/e2e/live/opd-journey.mjs --only gm --per 2 --from 2 --extras exam,procedures,followup`. gm-01 not touched.

Result for the NEW patient gm-02:

| Step | Result | Request | Status | Problem type | Elapsed |
| --- | --- | --- | --- | --- | --- |
| register, appointment, token, queue, open_emr | OK | | 201/200 | | normal |
| fill_required (vitals, complaints, diagnosis, rx; two PUT drafts) | OK | PUT /opd/emr/records/{id}/draft | 200 | | normal |
| extras: section exam alone (EXAM_GENERAL) | HUNG | PUT /opd/emr/records/{id}/draft | no answer | none | 15 s client limit reached, no 503 from the server |
| extras: procedures alone | NOT RUN separately | | | | halted, so as not to queue behind the hung save |
| extras: followup alone | NOT RUN separately | | | | same |
| extras: all three together | NOT RUN | | | | same |
| finish_check | OK | POST .../finish-check | 200 | | ready, bill lines 0 |
| sign | FAIL | POST .../sign | 503 | database-busy | server's own timeout fired |
| bill, payment, receipt | BLOCKED | | | | sign failed |

Conclusion on the two suspects: (a) is ruled out. gm-02 is a brand new record with no history, and the same request (PUT draft, exam section only) hangs on it while the PUT drafts for vitals, complaints, diagnosis and prescription on the same record answered 200 seconds earlier. So (b): the `exam` (EXAM_GENERAL) save blocks in code, reproducible on two different records, on a freshly restarted server with the new timeouts. The new timeout does not turn it into a 503, so it waits outside the timed database call, or the statement/lock timeout is longer than 15 s. Sign then returns 503 `database-busy`, consistent with the hung save still holding the record row (a lock timeout firing in sign).

Not answered: whether procedures or followup alone hang (not separated; the exam hang blocks that record). gm-03..05 and the six other departments were NOT run (stop at the first hung request, as instructed).

Script change (qa-engineer): each extra section is now timed and printed (`section <key> alone: saved/NO ANSWER ... ms`), and a hang is no longer swallowed inside the extras loop. Caution: the script rewrites this whole file on each run; keep the Run sections in a copy.

Needed from java-developer: look at the EXAM_GENERAL path in the draft save (EmrRecordService draft save, EmrComponentValues validation of EXAM_GENERAL, any `synchronized`/lock or regex with heavy backtracking on the exam text, e.g. "S1 S2 heard, no murmur"; a CPU-bound loop would show as a busy thread in a thread dump). Request body: sections [{key: exam, values: {component EXAM_GENERAL, schemaVersion 1, signs {pallor, icterus, cyanosis, oedema: ABSENT}, systems {cvs, rs, pa each {state NORMAL, text}}}}]. Server log line and thread dump are still needed; they are not readable from here.

## Handoff

- Story: DEC-054 live OPD journey (no story branch; current checkout feature/SAH-39-home-kinetic-background)
- From: qa-engineer
- Status: BLOCKED. exam save hangs on a new patient (gm-02): defect in code, not a leftover lock. 2 of 35 patients attempted, 0 full journeys
- Files changed: docs/test-plans/live-opd-journey-2026-11-14.md, frontend/e2e/live/opd-journey.mjs (timing, halt propagation)
- Needs: java-developer (EXAM_GENERAL draft save hang; thread dump and log); then qa-engineer rerun with procedures and followup alone on gm-03
- Next agents: java-developer, then code-reviewer and security-reviewer
