# Live OPD journey, 14 Nov 2026

Decision: docs/decisions/DEC-054-live-opd-journey-test.md. Script: `node frontend/e2e/live/opd-journey.mjs` (run tag "Live", resumable; state kept per tag). Last run: 2026-11-14T19:27:55.506Z UTC.
Everything below was done through the app's own HTTP API on the dev platform as the clinic owner (and as the fake doctors and nurse). Patients are fake ("Test <Dept> nn Live", mobiles 900001xxxx). Nothing was deleted. No ids, tokens, codes or cookies are printed.

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
- doctor "Dr Test OG Live" not added: the free plan allows 5 users in total (already known)
- doctor "Dr Test Paeds Live" not added: the free plan allows 5 users in total (already known)
- doctor "Dr Test Physio Live" not added: the free plan allows 5 users in total (already known)
- doctor "Dr Test Surgery Live" not added: the free plan allows 5 users in total (already known)
- doctor "Dr Test Onco Live" not added: the free plan allows 5 users in total (already known)
- Obstetrics & Gynaecology has no doctor of its own (free plan: 5 users in total, none left): visits are booked with Dr Test GM Live and the EMR record is switched to the "Obstetrics & Gynaecology Live" department
- Paediatrics has no doctor of its own (free plan: 5 users in total, none left): visits are booked with Dr Test GM Live and the EMR record is switched to the "Paediatrics Live" department
- Physiotherapy has no doctor of its own (free plan: 5 users in total, none left): visits are booked with Dr Test Dental Live and the EMR record is switched to the "Physiotherapy Live" department
- General Surgery has no doctor of its own (free plan: 5 users in total, none left): visits are booked with Dr Test Dental Live and the EMR record is switched to the "General Surgery Live" department
- Medical Oncology has no doctor of its own (free plan: 5 users in total, none left): visits are booked with Dr Test GM Live and the EMR record is switched to the "Medical Oncology Live" department
- tariff services for surgery not checked: doctor-surgery sign-in ended at "NOT_REGISTERED" (expected APP). SETUP means the clinic setup is not complete; NOT_REGISTERED means this mobile has no membershi…

## Summary by department

Cell = patients where the step is OK / patients run. "Full journey" = every core step OK (register, appointment, arrive and token, queue, open EMR, fill required, finish check, sign, bill at counter, cash payment, receipt check).

| Department | Patients | Appt+token | Queue | EMR open | Required filled | Finish check | Signed | Bill at counter | Cash paid | Full journey | Extras OK |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| General Medicine | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 4/5 | 4/5 | 4/5 | 5/5 |
| Dental | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 |
| Obstetrics & Gynaecology | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 |
| Paediatrics | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 |
| Physiotherapy | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 |
| General Surgery | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 0/5 |
| Medical Oncology | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 5/5 | 0/5 |

## Failures (1)

- **gm-01 · bill_at_counter** (FAIL) `GET /billing/counter` → 200
  - detail: the bill is not on the counter lists (awaiting, dues, paid today); bill total ₹ 450, balance ₹ 450

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
| extras | OK | PUT /opd/emr/records/{id}/draft | 200 |  | extra sections saved: exam, followup, procedures |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile gm |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 150; follow-up 2026-11-21 booked 2026-11-21 09:00; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | FAIL | GET /billing/counter | 200 |  | the bill is not on the counter lists (awaiting, dues, paid today); bill total ₹ 450, balance ₹ 450 |
| payment_cash | BLOCKED |  |  |  | not run: step "bill_at_counter" failed |
| receipt | BLOCKED |  |  |  | not run: step "bill_at_counter" failed |

**Test Gm 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00003 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 1 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 1, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | OK | PUT /opd/emr/records/{id}/draft | 200 |  | extra sections saved: exam, followup, procedures |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile gm |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 150; follow-up 2026-11-22 booked 2026-11-22 09:00; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER], Dressing ₹ 150 [EMR]; total ₹ 450, paid ₹ 0, balance ₹ 450 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 450 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Gm 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00004 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:30 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 2 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 2, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | OK | PUT /opd/emr/records/{id}/draft | 200 |  | extra sections saved: exam |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile gm |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Gm 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00005 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:45 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 3 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 3, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | OK | PUT /opd/emr/records/{id}/draft | 200 |  | extra sections saved: exam, followup, procedures |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 150; follow-up 2026-11-22; prints PRESCRIPTION; profile gm |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 150; follow-up 2026-11-22 booked 2026-11-22 09:15; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER], Dressing ₹ 150 [EMR]; total ₹ 450, paid ₹ 0, balance ₹ 450 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 450 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Gm 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00006 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:00 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 4 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 4, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Medicine Live" PUBLISHED v1, profile gm, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved vitals,complaints,diagnosis,rx; ready to complete (round 2) |
| extras | OK | PUT /opd/emr/records/{id}/draft | 200 |  | extra sections saved: exam, followup, procedures |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 150; follow-up 2026-11-22; prints PRESCRIPTION; profile gm |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 150; follow-up 2026-11-22 booked 2026-11-22 09:30; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER], Dressing ₹ 150 [EMR]; total ₹ 450, paid ₹ 0, balance ₹ 450 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 450 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

### Dental

**Test Dental 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00007 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:00 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 5 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 5, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Dental Live" PUBLISHED v1, profile dental, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,dental_medhx,diagnosis,tx_plan; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | saved dental_hx, dental_pain, dental_chart, tx_plan, consent; estimate ₹ 6700 (SIGNED); done today rows 1 |
| dental_course_from_plan | OK | POST /opd/emr/courses | 201 |  | course TC-0001 started from the plan: 3 sittings |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 1200; follow-up 2026-11-22; prints PRESCRIPTION; profile dental next sitting 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 1200; follow-up 2026-11-22 booked 2026-11-22 09:00; course next sitting 2026-11-22; patient record UPDATED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER], Composite filling · tooth 46 ₹ 1200 [EMR_PLAN]; total ₹ 1600, paid ₹ 0, balance ₹ 1600 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 1600 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |
| course_payment_receipt | OK | GET /billing/courses/{id}/receipts/1 | 200 |  | course part payment ₹ 500 cash, receipt RC-1, balance after ₹ 6850 |

**Test Dental 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00008 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:15 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 6 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 6, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Dental Live" PUBLISHED v1, profile dental, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,dental_medhx,diagnosis,tx_plan; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | saved dental_hx, dental_pain, dental_chart, tx_plan, consent; estimate ₹ 6700 (SIGNED); done today rows 1 |
| dental_course_from_plan | OK | POST /opd/emr/courses | 201 |  | course TC-0002 started from the plan: 3 sittings |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 1200; follow-up 2026-11-22; prints PRESCRIPTION; profile dental next sitting 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 1200; follow-up 2026-11-22 booked 2026-11-22 09:15; course next sitting 2026-11-22; patient record UPDATED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER], Composite filling · tooth 46 ₹ 1200 [EMR_PLAN]; total ₹ 1600, paid ₹ 0, balance ₹ 1600 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 1600 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |
| course_payment_receipt | OK | GET /billing/courses/{id}/receipts/2 | 200 |  | course part payment ₹ 500 cash, receipt RC-2, balance after ₹ 6850 |

**Test Dental 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00009 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:30 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 7 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 7, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Dental Live" PUBLISHED v1, profile dental, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,dental_medhx,diagnosis,tx_plan; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | saved dental_hx, dental_pain, dental_chart, tx_plan, consent; estimate ₹ 6700 (SIGNED); done today rows 1 |
| dental_course_from_plan | OK | POST /opd/emr/courses | 201 |  | course TC-0003 started from the plan: 3 sittings |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 1200; follow-up 2026-11-22; prints PRESCRIPTION; profile dental next sitting 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 1200; follow-up 2026-11-22 booked 2026-11-22 09:30; course next sitting 2026-11-22; patient record UPDATED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER], Composite filling · tooth 46 ₹ 1200 [EMR_PLAN]; total ₹ 1600, paid ₹ 0, balance ₹ 1600 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 1600 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |
| course_payment_receipt | OK | GET /billing/courses/{id}/receipts/3 | 200 |  | course part payment ₹ 500 cash, receipt RC-3, balance after ₹ 6850 |

**Test Dental 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00010 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 06:45 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 8 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 8, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Dental Live" PUBLISHED v1, profile dental, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,dental_medhx,diagnosis,tx_plan; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | saved dental_hx, dental_pain, dental_chart, tx_plan, consent; estimate ₹ 6700 (SIGNED); done today rows 1 |
| dental_course_from_plan | OK | POST /opd/emr/courses | 201 |  | course TC-0004 started from the plan: 3 sittings |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 1200; follow-up 2026-11-22; prints PRESCRIPTION; profile dental next sitting 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 1200; follow-up 2026-11-22 booked 2026-11-22 09:45; course next sitting 2026-11-22; patient record UPDATED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER], Composite filling · tooth 46 ₹ 1200 [EMR_PLAN]; total ₹ 1600, paid ₹ 0, balance ₹ 1600 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 1600 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |
| course_payment_receipt | OK | GET /billing/courses/{id}/receipts/4 | 200 |  | course part payment ₹ 500 cash, receipt RC-4, balance after ₹ 6850 |

**Test Dental 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00011 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:00 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 9 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 9, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Dental Live" PUBLISHED v1, profile dental, stage MOVED -> WITH_DOCTOR |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,dental_medhx,diagnosis,tx_plan; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | saved dental_hx, dental_pain, dental_chart, tx_plan, consent; estimate ₹ 6700 (SIGNED); done today rows 1 |
| dental_course_from_plan | OK | POST /opd/emr/courses | 201 |  | course TC-0005 started from the plan: 3 sittings |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 1, ₹ 1200; follow-up 2026-11-22; prints PRESCRIPTION; profile dental next sitting 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 1, ₹ 1200; follow-up 2026-11-22 booked 2026-11-22 10:00; course next sitting 2026-11-22; patient record UPDATED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER], Composite filling · tooth 46 ₹ 1200 [EMR_PLAN]; total ₹ 1600, paid ₹ 0, balance ₹ 1600 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 1600 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |
| course_payment_receipt | OK | GET /billing/courses/{id}/receipts/5 | 200 |  | course part payment ₹ 500 cash, receipt RC-5, balance after ₹ 6850 |

### Obstetrics & Gynaecology

**Test Og 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00012 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 10 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 10, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Obstetrics & Gynaecology Live" PUBLISHED v1, profile og, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,anc; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | LMP set (about 28 weeks); mode ANC, GA 28w0d, alerts none |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile og |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Og 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00013 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:30 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 11 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 11, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Obstetrics & Gynaecology Live" PUBLISHED v1, profile og, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,anc; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | LMP set (about 28 weeks); mode ANC, GA 28w0d, alerts none |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile og |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Og 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00014 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:45 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 12 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 12, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Obstetrics & Gynaecology Live" PUBLISHED v1, profile og, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,anc; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | LMP set (about 28 weeks); mode ANC, GA 28w0d, alerts none |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile og |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Og 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00015 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:00 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 13 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 13, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Obstetrics & Gynaecology Live" PUBLISHED v1, profile og, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,anc; saved menstrual; ready to complete (round 3) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | gynae sections gyn_exam, contraception; mode GYNAE, GA 0w0d, alerts none |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile og |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Og 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00016 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 14 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 14, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Obstetrics & Gynaecology Live" PUBLISHED v1, profile og, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,anc; saved menstrual; ready to complete (round 3) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | gynae sections gyn_exam, contraception; mode GYNAE, GA 0w0d, alerts none |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile og |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

### Paediatrics

**Test Paeds 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00017 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:30 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 15 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 15, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Paediatrics Live" PUBLISHED v1, profile paeds, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,growth,immunization; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | dose row 3.4 ml (81 mg) saved; vaccines given today: bcg, opv_0; DOB recorded true; due today 5, overdue 7 |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile paeds |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Paeds 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00018 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:45 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 16 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 16, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Paediatrics Live" PUBLISHED v1, profile paeds, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,growth,immunization; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | dose row 3.9 ml (93 mg) saved; vaccines given today: bcg, opv_0; DOB recorded true; due today 5, overdue 12 |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile paeds |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Paeds 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00019 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:00 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 17 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 17, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Paediatrics Live" PUBLISHED v1, profile paeds, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,growth,immunization; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | dose row 5.4 ml (129 mg) saved; vaccines given today: bcg, opv_0; DOB recorded true; due today 1, overdue 20 |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile paeds |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Paeds 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00020 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 18 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 18, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Paediatrics Live" PUBLISHED v1, profile paeds, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,growth,immunization; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | dose row 6.5 ml (156 mg) saved; vaccines given today: bcg, opv_0; DOB recorded true; due today 3, overdue 22 |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile paeds |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Paeds 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00021 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:30 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 19 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 19, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Paediatrics Live" PUBLISHED v1, profile paeds, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,rx,growth,immunization; ready to complete (round 2) |
| extras | OK | GET /opd/emr/records/{id} | 200 |  | dose row 7 ml (168 mg) saved; vaccines given today: bcg, opv_0; DOB recorded true; due today 3, overdue 25 |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile paeds |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

### Physiotherapy

**Test Physio 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00022 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:15 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 20 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 20, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Physiotherapy Live" PUBLISHED v1, profile physio, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,pain; saved modalities; ready to complete (round 3) |
| extras | OK | POST /opd/emr/courses/{id}/sittings/{id}/link-visit | 200 |  | saved posture, rom, func_scales, goals, modalities, hep, sessions; session linked to the package; course TC-0006, 3 sessions |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile physio session 1 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; course next sitting 2026-11-16; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Physio 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00023 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:30 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 21 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 21, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Physiotherapy Live" PUBLISHED v1, profile physio, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,pain; saved modalities; ready to complete (round 3) |
| extras | OK | POST /opd/emr/courses/{id}/sittings/{id}/link-visit | 200 |  | saved posture, rom, func_scales, goals, modalities, hep, sessions; session linked to the package; course TC-0007, 3 sessions |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile physio session 1 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; course next sitting 2026-11-16; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Physio 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00024 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 07:45 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 22 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 22, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Physiotherapy Live" PUBLISHED v1, profile physio, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,pain; saved modalities; ready to complete (round 3) |
| extras | OK | POST /opd/emr/courses/{id}/sittings/{id}/link-visit | 200 |  | saved posture, rom, func_scales, goals, modalities, hep, sessions; session linked to the package; course TC-0008, 3 sessions |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile physio session 1 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; course next sitting 2026-11-16; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Physio 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00025 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:00 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 23 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 23, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Physiotherapy Live" PUBLISHED v1, profile physio, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,pain; saved modalities; ready to complete (round 3) |
| extras | OK | POST /opd/emr/courses/{id}/sittings/{id}/link-visit | 200 |  | saved posture, rom, func_scales, goals, modalities, hep, sessions; session linked to the package; course TC-0009, 3 sessions |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile physio session 1 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; course next sitting 2026-11-16; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Physio 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00026 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:15 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 24 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 24, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Physiotherapy Live" PUBLISHED v1, profile physio, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | saved complaints,diagnosis,pain; saved modalities; ready to complete (round 3) |
| extras | OK | POST /opd/emr/courses/{id}/sittings/{id}/link-visit | 200 |  | saved posture, rom, func_scales, goals, modalities, hep, sessions; session linked to the package; course TC-0010, 3 sessions |
| recheck_ready | OK | GET /opd/emr/records/{id} | 200 |  | ready to complete (round 1) |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION; profile physio session 1 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; course next sitting 2026-11-16; patient record NOT_CHANGED; prints PRESCRIPTION |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

### General Surgery

**Test Surgery 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00027 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:30 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 25 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 25, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Surgery Live" PUBLISHED v1, profile surgery, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: OT booking; blockers:  / saved sx_findings, sx_preop / booking: Booked. This clinic has no IPD, so no ward bed was requested. (book state BOOKED, fitness: Fit for surgery, payer Self-pay) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| ot_booking | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET; profile surgery OT 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Surgery 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00028 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 08:45 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 26 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 26, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Surgery Live" PUBLISHED v1, profile surgery, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: OT booking; blockers:  / saved sx_findings, sx_preop / booking: Booked. This clinic has no IPD, so no ward bed was requested. (book state BOOKED, fitness: Fit for surgery, payer Self-pay) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| ot_booking | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET; profile surgery OT 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Surgery 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00029 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:00 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 27 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 27, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Surgery Live" PUBLISHED v1, profile surgery, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: OT booking; blockers:  / saved sx_findings, sx_preop / booking: Booked. This clinic has no IPD, so no ward bed was requested. (book state BOOKED, fitness: Fit for surgery, payer Self-pay) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| ot_booking | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET; profile surgery OT 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Surgery 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00030 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:15 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 28 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 28, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Surgery Live" PUBLISHED v1, profile surgery, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: OT booking; blockers:  / saved sx_findings, sx_preop / booking: Booked. This clinic has no IPD, so no ward bed was requested. (book state BOOKED, fitness: Fit for surgery, payer Self-pay) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| ot_booking | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET; profile surgery OT 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Surgery 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00031 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:30 with Dr Test Dental Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 29 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 29, stage VITALS, NEW, fee ₹ 400; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "General Surgery Live" PUBLISHED v1, profile surgery, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: OT booking; blockers:  / saved sx_findings, sx_preop / booking: Booked. This clinic has no IPD, so no ward bed was requested. (book state BOOKED, fitness: Fit for surgery, payer Self-pay) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| ot_booking | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up none; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET; profile surgery OT 2026-11-22 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up none; patient record NOT_CHANGED; prints PRESCRIPTION,ADMISSION_LETTER,PREOP_SHEET |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 400 [COUNTER]; total ₹ 400, paid ₹ 0, balance ₹ 400 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 400 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

### Medical Oncology

**Test Onco 01 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00032 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 09:45 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 30 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 30, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Medical Oncology Live" PUBLISHED v1, profile onco, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: today's decision, send chemo order; blockers:  / saved vitals; cancer journey v1 saved / decision GIVE_AS_PLANNED / chemo order SENT, second check by a named checker (SIGN_IN) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up 2026-12-06; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR; profile onco next cycle 2026-12-06 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up 2026-12-06 booked 2026-12-06 09:00; patient record NOT_CHANGED; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Onco 02 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00033 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 10:00 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 31 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 31, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Medical Oncology Live" PUBLISHED v1, profile onco, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: today's decision, send chemo order; blockers:  / saved vitals; cancer journey v1 saved / decision GIVE_AS_PLANNED / chemo order SENT, second check by a named checker (SIGN_IN) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up 2026-12-06; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR; profile onco next cycle 2026-12-06 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up 2026-12-06 booked 2026-12-06 09:15; patient record NOT_CHANGED; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Onco 03 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00034 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 10:15 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 32 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 32, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Medical Oncology Live" PUBLISHED v1, profile onco, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: today's decision, send chemo order; blockers:  / saved vitals; cancer journey v1 saved / decision GIVE_AS_PLANNED / chemo order SENT, second check by a named checker (SIGN_IN) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up 2026-12-06; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR; profile onco next cycle 2026-12-06 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up 2026-12-06 booked 2026-12-06 09:30; patient record NOT_CHANGED; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Onco 04 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00035 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 10:30 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 33 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 33, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Medical Oncology Live" PUBLISHED v1, profile onco, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: today's decision, send chemo order; blockers:  / saved vitals; cancer journey v1 saved / decision GIVE_AS_PLANNED / chemo order SENT, second check by a named checker (SIGN_IN) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up 2026-12-06; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR; profile onco next cycle 2026-12-06 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up 2026-12-06 booked 2026-12-06 09:45; patient record NOT_CHANGED; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

**Test Onco 05 Live**

| Step | Result | Request | Status | Problem type | Note or detail |
| --- | --- | --- | --- | --- | --- |
| register | OK | POST /patient/patients | 201 |  | registered, UHID PT-00036 |
| appointment | OK | POST /opd/appointments | 201 |  | booked 2026-11-15 10:45 with Dr Test GM Live |
| arrive_token | OK | POST /opd/appointments/{id}/token | 200 |  | arrived, token 34 |
| queue | OK | GET /opd/visits/today?mine=true | 200 |  | in queue as token 34, stage VITALS, NEW, fee ₹ 300; doctor's "mine" list does not show it yet (stage VITALS) |
| open_emr | OK | GET /opd/visits/today | 200 |  | opened (new), layout "Medical Oncology Live" PUBLISHED v1, profile onco, stage MOVED -> WITH_DOCTOR (switched department) |
| fill_required | OK | GET /opd/emr/records/{id} | 200 |  | first pass: still not ready after 7 rounds; still needed: today's decision, send chemo order; blockers:  / saved vitals; cancer journey v1 saved / decision GIVE_AS_PLANNED / chemo order SENT, second check by a named checker (SIGN_IN) / ready to complete (round 1) |
| extras | BLOCKED |  |  |  | not run: step "fill_required" failed |
| finish_check | OK | POST /opd/emr/records/{id}/finish-check | 200 |  | ready; stage -> BILLING (WILL_MOVE); bill lines 0, ₹ 0; follow-up 2026-12-06; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR; profile onco next cycle 2026-12-06 |
| sign | OK | POST /opd/emr/records/{id}/sign | 200 |  | signed; stage MOVED -> BILLING; bill lines 0, ₹ 0; follow-up 2026-12-06 booked 2026-12-06 10:00; patient record NOT_CHANGED; prints PRESCRIPTION,CYCLE_RECORD,CHEMO_CALENDAR |
| bill_at_counter | OK | GET /billing/counter | 200 |  | awaiting; lines: Consultation ₹ 300 [COUNTER]; total ₹ 300, paid ₹ 0, balance ₹ 300 |
| payment_cash | OK | POST /billing/bills/{id}/payments | 200 |  | cash ₹ 300 collected; balance now ₹ 0 |
| receipt | OK | GET /opd/visits/today | 200 |  | bill shows payment line and balance 0 (visit stage DONE). GAP: billing.yaml has no visit receipt operation (SAH-10 has none); only course receipts RC-n exist |

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

## Run 4 (15 Nov 2026, server restarted with the SpecialtyDone fix)

Probe: GET opd/visits/today 200. Fix check: new patient gm-03, section exam alone saved in 200 ms (200). Full run: `SAHAJ_HTTP_TIMEOUT_MS=20000 node frontend/e2e/live/opd-journey.mjs --per 5` (35 patients, resumed gm-01 and gm-02, no hang, no 503, no timeout).

Result: 25 of 35 full journeys (gm 4/5, dental 5, og 5, paeds 5, physio 5, surgery 0, onco 0). Extras that worked: gm exam/procedures (Dressing BILL_TODAY)/followup, dental plan and next sitting, ANC, paeds vaccines, physio sessions linked to package (course next sitting), receipt via bill payment line. Surgery OT booking and oncology chemo order were never reached.

Failures:
| Patient | Step | Request | Status | Problem type | Owner and exact problem |
| --- | --- | --- | --- | --- | --- |
| gm-01 | bill_at_counter | GET /billing/counter | 200 | none | Bill total ₹ 450 (Consultation + Dressing) balance ₹ 450 is not in awaiting, dues or paid-today. gm-01 visit was booked 14 Nov, run on 15 Nov; probable cause is the counter lists being today-only, not a defect (unconfirmed). gm-02..05 (today) show the bill as awaiting. Owner if real: java (billing counter list filter). |
| surgery-01..05 | fill_required | GET /opd/emr/records/{id} | 200 | none | After 7 rounds still needed: "OT booking"; completeBlockers empty. The script books the OT only in the later ot_booking step, after fill_required demands readiness (script ordering, qa-owned). Whether the API lets the record be ready before booking is the open question. |
| onco-01..05 | fill_required | GET /opd/emr/records/{id} | 200 | none | Still needed: "today’s decision" and "send chemo order". Script records the decision only when rec.specialty.onco.suggestion exists and sends the order only when no other item remains; neither happened, so either the suggestion is absent for these fake records (angular/java: check specialty.onco in the record) or the script ordering is wrong. Needs a read of the onco record; the --probe as onco returned no JSON. |

Not run because of the above: surgery OT booking, oncology chemo order and second check.

## Run 5 (15 Nov 2026, script ordering fixed)

Script changes (qa-owned): surgery now fills plan, consent, diagnosis and the extras first, books the OT last (POST surgery-booking with Idempotency-Key and current revision), then re-checks readiness. Oncology now saves onc_bsa, onc_labs, onc_tox and onc_ecog in one draft, reads the suggestion, saves the decision on the screen, then PUT chemo-order, checker-options, PUT checker, the nurse confirms through the checker list, the doctor sends; no edit between order and send. Commands: `SAHAJ_HTTP_TIMEOUT_MS=20000 node frontend/e2e/live/opd-journey.mjs --only surgery --per 5`, the same with `--only onco`, then `--only gm --per 1`. Earlier patients were resumed from the saved state, none recreated. No hang, no 503, no timeout.

| Department | Patient | Furthest step reached |
| --- | --- | --- |
| Surgery | surgery-01 to surgery-05 | receipt check (OT booking done, finish check, sign, bill at counter, cash payment all OK) |
| Oncology | onco-01 to onco-05 | receipt check (decision GIVE_AS_PLANNED, chemo order SENT with second check by the nurse, finish check, sign, follow-up booked 6 Dec 2026, bill, cash payment all OK) |
| General medicine | gm-01 | FAIL at bill_at_counter (rerun) |

Overall: 5 of 5 surgery and 5 of 5 oncology full journeys; with earlier runs 34 of 35 full journeys.

Failures:

| Patient | Step | Request | Status | Problem type | Owner and exact problem |
| --- | --- | --- | --- | --- | --- |
| gm-01 | bill_at_counter | GET /billing/counter | 200 | none | Bill ₹ 450 (balance ₹ 450, visit booked 14 Nov) is still not in awaiting, dues or paid-today. The 30-day lookback fix is not visible: the dev server has probably not been restarted since java's change (not confirmable from here). Re-run `--only gm --per 1` after the restart. Owner if still failing after the restart: java (billing counter). gm-01 cash payment and receipt stay BLOCKED meanwhile. |

Observations (not failures):
- Surgery bill has consultation only (₹ 400, 0 bill lines from the visit): no OT or procedure charge reaches the counter; by design for phase 1 unless the mockup says otherwise (human to confirm).
- Surgery, oncology: no doctor of their own (free plan, 5 users); visits use the fallback doctor, so the consultation fee follows that doctor (oncology ₹ 300, not the ₹ 800 intended).
- Checker for chemotherapy was the existing second staff member (the test nurse); no plan-limit blocker occurred.
- Surgery extras (sx_findings, sx_preop) and oncology vitals plus cancer journey are now saved inside the fill step, so the "Extras OK" column shows 0/5 for these two departments only because the separate extras step is no longer used.

## Handoff

- Story: DEC-054 live OPD journey (checkout feature/SAH-39-home-kinetic-background)
- From: qa-engineer (Run 5)
- Status: PARTIAL, 34 of 35 full journeys. Surgery 5/5 and oncology 5/5 now complete; gm-01 counter list still fails (restart of the dev server pending)
- Files changed: frontend/e2e/live/opd-journey.mjs, docs/test-plans/live-opd-journey-2026-11-14.md (state file frontend/e2e/live/opd-journey.state.Live.json updated by the script)
- Needs: human or java-developer to restart the dev server so the counter lookback applies, then qa-engineer re-runs gm-01; human to confirm that a surgery visit with no OT charge on the bill is intended
- Next agents: code-reviewer and security-reviewer
