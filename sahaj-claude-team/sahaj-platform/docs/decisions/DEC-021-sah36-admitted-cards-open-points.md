# DEC-021 · SAH-36 Admitted patient cards: open points (proceeding on the safer proposals, DEC-014)

Status: PROCEEDING. Stories: docs/stories/SAH-36.md, docs/requirements/SAH-36.md (BA, 7 Nov 2026). Mockup screen: IPD -> Admitted.

## Scope built (5 stories)
Cards/Table (default Cards, 2/3/4 per row, 24 per page); search + ward filter on both views; flags already in the list (Going home today for all four roles, Deposit low for OWNER and FRONT_DESK only); safety flags from existing records (Not seen N min, Doctor reviewing, Allergy, EWS, Vitals due) for OWNER, DOCTOR, NURSE only, role-gated by the server, 60-second reload; card actions (Nursing, EMR, Transfer, Discharge, name -> admission sheet).
OUT: Customize card / Patient card setup (SAH-37 candidate), My patients and Roles and access (ward scoping, OQ-33-2/OQ-30-3), workflow stage (chip, stage chips, Update stage), Dose overdue flag (after SAH-35), Emergency access flag (SAH-34), Case paper, Deposit action, Assigned nurse.

## Questions that need the human (proposal in brackets; build proceeds on it)
- OQ-36-1 (patient safety): Not seen, Doctor reviewing, Allergy and EWS flags are never folded into "+N"; only Vitals due, Going home today and Deposit low may fold. [as proposed]
- OQ-36-2 (stage): no stage data exists; ship without the stage chip, stage chips and Update stage. A small story later once you accept the 8 stages, who may change a stage, and that each change is logged.
- OQ-36-7 (DPDP): the card shows allergy, score and escalation for many patients at once, possibly on a shared nurses' station screen. The list writes no record-access row (as the existing list and Safety board). [keep; confirm this meets the access-log policy]
- OQ-36-3: Deposit is not on the card (mockup default hides it); reachable through the patient's name. Tell me if OWNER and FRONT_DESK need it on the card now.
- OQ-36-4: role conflicts between mockup default card and server: Transfer (mockup lets DOCTOR; server does not), Discharge (mockup hides it from FRONT_DESK; server lets FRONT_DESK complete a discharge), Deposit low hidden from doctors/nurses though the table shows the bill. [narrower side built]
- OQ-36-6: "Allergy not recorded" shows no flag (as mockup); a patient never asked looks like a safe one.
- OQ-36-5, -8 (UHID not MRN), -9 (filters apply to Table too), -10 (minutes up to 59 s behind), -11 (Reason field on Move absent, OQ-26-5), -12 (new wording; date "24 Oct 2026, 9:20 AM"), -13 (list order, no sickest-first): sign-off items.

## Architect brief
`GET /admissions` needs, role-gated (absent, not null-filled): escalation status/minutes/target, latest score and risk, vitals due, allergy status+text via the patient port, UHID, room name, ward id, action abilities; ONE batch read, no per-patient calls; index check for escalations + latest vitals of all admitted patients read every 60 s by up to 100 hospitals. Contract edits to docs/api/ipd.yaml wait for the SAH-35 architect to finish (same file).

## Human answer (chat, 7 Nov 2026) - OQ-36-7 / ADR-013 Q1
The Admitted card list writes NO record-access row (option "No row for the list"), as the existing Admitted list and Safety board. Opening Nursing, EMR or the admission sheet from a card writes the usual rows. Recorded as a privacy decision by the human.

## Architect result (ADR-013, 7 Nov 2026)
Contract: GET /admissions extended (AdmissionList.asOf, wards, card abilities; AdmittedPatientRow with uhid, wardId, roomName, optional clinical flags absent for FRONT_DESK); membership re-read (inactive member now 403); search/ward filter/paging on the device; Transfer/Discharge/name open the existing admission sheet at a start view; list reload every 60 s replaces the summary poll on that tab; roles narrower than the mockup (Transfer: OWNER, NURSE, FRONT_DESK; Discharge: OWNER, DOCTOR). Other sign-offs: Q6 keep the red "Open safety board" banner against the mockup's last override, Q7 Room "—" when rooms are off, Q8 "1 day" wording, Q9 new 403 for an inactive membership, Q10 missing approval lines. No migration expected; db-engineer to check plans R1, R3, R4 (conditional vitals index).
Build order: SAH-35 Java/Angular (running) first, then SAH-36 (same list code).

## Angular build notes (7 Nov 2026) - for sign-off
Built: Cards (default) and Table, 2/3/4 per row (default 3; 2 at <=1100 px, 1 at <=700 px), search "Name, UHID or bed", ward filter, paging 24, flags per ADR-013, actions from server abilities, 60 s reload replacing the summary poll on that tab; unit 1,914 (83 new + 20). Deviations: UHID not MRN; length of stay "Day 1 (today)"/"N days"/"1 day"; "Admitted on" with year and IST; flag line shows one non-safety flag, safety flags (Not seen/Doctor reviewing, Allergy, EWS) never folded and the line may wrap; filters also apply to Table; red "Open safety board" banner kept; Room "—" when rooms are off; Table has no paging; toolbar and Show-more 44 px (mockup 36); flag chips stay 22 px (non-interactive).
RISK for the human: the "+N" fold chip is reachable only by tooltip or screen reader, so on a touch device (tablet) the folded Vitals due / Going home today / Deposit low cannot be revealed. A tap popover would fix it but changes the mockup. Default (DEC-023): leave as built; revisit with the human at UAT.
Not checked in a browser yet: 360/1280 px layouts, hover and focus rings (QA).

## Java build notes (7 Nov 2026)
Backend unit 3,163 (57 new). GET /admissions: IPD gate, role, membership re-read (403 for inactive member), fixed set of reads, no writes. clinical absent (not null) for FRONT_DESK, tested on raw JSON. Abilities per ADR-013 D5. Missing patient record kept as "Patient".
Config change (per ADR-013 instructions): HTTP JSON response compression turned ON in application.yml (`server.compression.enabled: ${SAHAJ_HTTP_COMPRESSION:true}`, application/json, min 2KB). BREACH risk judged low (bearer-token API, no attacker-controlled echo); set SAHAJ_HTTP_COMPRESSION=false if a reverse proxy already compresses. Security review to confirm.
Dead code: SafetyService.banner(tenantId, actor, role, patientMap) kept for SAH-35 tests.
QA must update: AdmissionDepositsIT (~369, 1050, 1176, 1198-1212), SafetyBoardIT (~190, 421-433) for the new shape and 403 rules; new ITs per ADR-013 QA list; query plans (placement left join to ipd.room; latest vitals lateral limit 1) outstanding (db-engineer could not run psql).

## Review results and decisions (7 Nov 2026, tech lead)
Code review: APPROVE WITH COMMENTS (docs/reviews/SAH-36-code.md). Security review: PASS WITH CONDITIONS (docs/reviews/SAH-36-security.md). No Critical or High.
- HTTP compression (R36-1, S36-3): kept ON (default). BREACH reasoning and the four rules are now ADR-013 D14. DEPLOYMENT NOTE for the human: with a reverse proxy, compress there for `/api/v1/ipd/admissions` only and set `SAHAJ_HTTP_COMPRESSION=false`. HUMAN TO DO: add `SAHAJ_HTTP_COMPRESSION` (default true) and `SAHAJ_DB_POOL_SIZE` (default 10; tests use 5) to `.env.example`.
- Data minimisation (S36-2, R36-5): FRONT_DESK requests will no longer load allergy text (ADR-013 D15; additive change in patient.api: `PatientSummary.uhid` and `PatientLookupPort.findAllCurrent`). Fix pass: java-developer.
- GO-LIVE CONDITION (S36-1, human/policy): record the "no access row for the ward list and Safety board" exception in the privacy register (purpose: ward safety overview; roles OWNER, DOCTOR, NURSE) and confirm request logs (tenant, user, path, time) are kept 180 days in India. Revisit when ward scoping (OQ-33-2) arrives.
- Still for the human at UAT: R36-2 "+N" chip hides non-safety flags (Vitals due) on cards that have a safety flag and is unreadable on touch/keyboard (let the flag line wrap or make "+N" a 44 px button with a popover); R36-6/I36-1 bill fields still sent to DOCTOR and NURSE (follow-up story if minimisation wanted).
