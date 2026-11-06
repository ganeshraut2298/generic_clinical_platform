# ADR-005 · IPD emergency queue: pre-registration identity in `ipd`, admit hand-off, and menu badges from the owning module

Story: SAH-28 (IPD part 7: emergency queue, triage and Emergency setup)
Mockup screen: IPD → Emergency (`ipEr`, `erreg`, `erseen`, `eradmit`, `errel`, `erref`, `erlvl`), tile "Emergency" (`vIpd`), sidebar item "Emergency" (`csBadges2`, `NV_MODS`), Clinic setup → Emergency and triage (`cfgIpEr`, key `iper`, `iptri`, `iptg`, `ipbays`)
Module: ipd (owner), platform (menu key only), patient (unchanged)
Status: PROPOSED (5 Nov 2026), with the SAH-28 contract. It is accepted when the human approves `contract`.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/stories/SAH-28.md`, `docs/decisions/DEC-014-ipd-open-questions-proceed-with-proposals.md` (OQ-28-1, -3, -4, -5, -6), `docs/api/ipd.yaml`, `docs/api/platform.yaml` (`Me.menu`), ADR-002 (tenancy and audit), ADR-004

## Context

1. An emergency patient must be queued in seconds, often without a name ("Unknown male, about 40"), a known age or a mobile. Patients (`patient.patient`, SAH-8) requires name, age, sex and a 10-digit mobile, and only OWNER and FRONT_DESK may register a patient. OQ-28-1 (DEC-014) says: do not create a patient at arrival; register only when admitting, through the existing registration dialog.
2. The Admit sheet (SAH-23) already takes a `patientId` and checks it through `patient.api.PatientLookupPort`.
3. The mockup puts a live count on the sidebar item "Emergency". `GET /platform/me` returns `menu` as a list of keys, is cached by the shell for the whole session, and is built by `platform`. `platform` must not depend on `ipd`.
4. Phase 1 has no notifications service (OQ-28-5) and no billing link for a release bill (OQ-28-3).

## Decision

### D1 · The emergency case keeps its own short identity inside `ipd`

`ipd.emergency_case` stores `display_name`, `age`, `sex`, `mobile` and `complaint` as typed at the desk, with `patient_id` null. No row is written in `patient`. These fields are shown on screen only. They are never logged and never put in audit text (AC12). The case is linked to a patient only when it is admitted: `patient_id` = the admitted patient.

Options:
- **A · Identity on the case, patient registered at admit (chosen).** This follows OQ-28-1. It needs no `patient.api` change and does not fill Patients with duplicate or "Unknown" records that would later need a merge.
- **B · Register in Patients at arrival through a new `patient.api` port.** Rejected for now. Patients needs a mobile and an age, and its write roles exclude NURSE and DOCTOR. Released or referred patients would stay in Patients as half-records.

### D2 · The admit hand-off uses existing endpoints plus one query parameter

- `GET /ipd/admission-options?emergencyCaseId=` returns `emergencyCase` (`EmergencyHandoff`). It holds the prefill data and `suggestedBedId`, which the server computes from the same bed order the sheet shows.
- The client gets the patient from the existing patient search or the existing `POST /patient/patients` (the registration dialog, prefilled).
- `POST /ipd/admissions` gets the optional `emergencyCaseId`. `AdmissionService` calls the emergency service in the same transaction, under the clinic IPD advisory lock, with the case row locked for update. The admission is stored with source EMERGENCY, and the case becomes ADMITTED and is linked to the admission.
- **No new `patient.api` port** and no change to `patient.yaml`. `ipd` still depends only on `platform.api`, `patient.api` and `opd.api` (ADR-004). It gains no new dependency.
- Opening the hand-off (a 200 answer to `GET /ipd/admission-options?emergencyCaseId=`) writes one audit row `IPD_ER_HANDOFF_OPENED` with the case id and the acting user id only, never the name, age, sex, mobile or complaint. It is the read trail for the one screen that shows a single case with its mobile (security review M3). The queue list and the counts stay unlogged, as every list is. The existing audit log is used; `PatientAccessPort` is not extended, because the case has no patient record yet.

### D3 · Menu badges come from the owning module, not from `/me`

`platform` adds only the key `emergency` to `/me.menu` (`NavigationPolicy`, based on its own entitlement data). The count comes from `GET /api/v1/ipd/emergency/summary` (`EmergencyCounts`: counts only, no names). The shell calls it when the menu has the key: after sign-in, every 60 seconds, and from any `EmergencyQueue` answer it sees. This is the rule for every future menu badge.

Options:
- **A · Summary endpoint per module (chosen).** No `platform → ipd` dependency. `/me` stays cacheable. The badge refreshes without reloading the brand and preferences. No patient data is involved.
- **B · `badges` map on `/me` filled through an SPI in `platform.api` that `ipd` implements.** Rejected. It turns `/me` into a cross-module aggregator. The count would also be stale for the whole session unless `/me` is polled.
- **C · The shell polls the full `GET /ipd/emergency`.** Rejected. It sends names and complaints every minute to every page.

### D4 · Waits are computed, not stored. Bays and levels follow fixed rules

- `waitingMinutes` and `overTarget` are computed on read from `arrived_at` and the current targets, on the database clock (D6). No scheduler is used. Phase 1 sends no alerts (OQ-28-5).
- There is no order rule between the target times of a scale. Each target is checked on its own (0 to 240 in steps of 5), so Red 120 with Green 5 is accepted. The mockup has no such rule. **This decision is pending the human** (code review SAH-28 item 7, DEC-013 item 10). No order check is added without the human's approval.
- A bay is given only at registration, only to level 1, and only within ER-1 to ER-<bays>, under the clinic IPD advisory lock. A partial unique index stops two open cases from holding the same bay. Re-triage and scale changes never move a bay. Closing a case frees its bay by status. The `bay` value stays on the closed row as history (OQ-28-6).
- A scale change maps the open cases' levels in the settings transaction and logs each one in the append-only `ipd.emergency_triage_event` (AC10, OQ-28-2).

### D5 · Treat and release creates nothing outside `ipd`

"Treat and release" and "Refer out" only close the case. No OPD visit, bill or letter is created (OQ-28-3).

### D6 · One clock and one snapshot for every emergency read and write

- **Writes.** Every emergency timestamp comes from the database clock, read once per action after the clinic IPD advisory lock is taken: `clock_timestamp()`, never the transaction-start `now()` and never the application clock. This covers `arrived_at`, `seen_at`, `closed_at`, the triage event `recorded_at` and the instants put in the audit data. Within one action every stamp is the same instant, with one exception at registration (below). The column defaults become `clock_timestamp()` as a backstop, in a new migration (the applied migration is not edited). Because the stamp is taken after the lock, two racing re-triages are stored in the order they were applied. This holds AC12's "who and when" (code review 2, QA F3).
- **Registration stamps (SAH-28, 5 Nov 2026).** `sahaj_app` has no INSERT grant on `ipd.emergency_case.arrived_at` or on the triage event `recorded_at`, so on registration both are stamped by their column default `clock_timestamp()` at insert, not by the explicit clock read. Each is a few microseconds after that read, and the first triage event's `recorded_at` is at or after the case's `arrived_at`, because the case row is inserted first in the same transaction, after the clinic lock. Order is guaranteed; exact equality with the explicit read (and with an instant put in the audit data) is not. Accepted: the difference is far below the one-minute resolution of `waitingMinutes`, and the missing grant keeps the app from back-dating an arrival.
- **Reads.** `waitingMinutes` is whole minutes from `arrived_at` to the same database clock, and that instant is returned as `asOf`. The queue (`GET /emergency`), the counts (`GET /emergency/summary` and the counts in `GET /admissions`) and the hand-off (`GET /admission-options?emergencyCaseId=`) each read the setup and the cases in one REPEATABLE READ transaction. A scale change committed during the read cannot then pair a level-4 or level-5 case with a 3-level setup, so a poll cannot get a 500 (code review 1). The queue and the counts are read-only transactions. The hand-off is read-write only because it writes the D2 audit row.

Options:
- **A · Database clock after the lock, plus REPEATABLE READ reads (chosen).** One source of time, so the stored order is the applied order. Small cost: one clock read per action and a snapshot per poll.
- **B · Application clock everywhere.** Rejected. Several app instances can disagree on the time, and the `arrived_at` default would still be a second source.
- **C · Clamp unknown levels on read.** Rejected. It hides the race instead of removing it and could show a wrong level on a triage screen.

## Consequences

**Positive.**
- No new module, service, library or cross-module dependency.
- Patients gets no unknown or half records.
- The queue works for every role that triages.
- The badge rule is reusable, for example later for the IPD deposit-alert badge or the Staff pending-leave badge.

**Negative / accepted.**
- **Mobile cleared on closure (security review M1(a)).** The case's `mobile` is set to null in the same UPDATE that closes the case, whether it is released, referred or admitted. The Admit hand-off reads it while the case is open, before the admit transaction closes it. An admitted patient's mobile is then held by `patient`. A released or referred patient's mobile is gone. The database guard accepts `mobile` becoming null only on the open-to-closed change.
- Name, age, sex and complaint of closed cases stay in `ipd.emergency_case`, which is never deleted (story section 3). This is personal data outside `patient`. **Their retention period, pseudonymisation after it, the data-principal rights procedure and a correction path are still open for the human** (DEC-013 item 12; security review M1(b) to (d)). Until then no API returns a closed case's name or complaint after the closing action. The closing action's own answer still carries them, for its toast. The queue shows open cases only, and a repeated client key on a closed case answers only id, level and status (security review L1).
- An unknown patient with no age or no reachable mobile cannot be registered in Patients today, so cannot be admitted until those are filled. NURSE and DOCTOR cannot register a patient (SAH-8 roles). OQ-28-1 follow-up for the human.
- A treated and released patient is not billed (OQ-28-3, high priority).
- The shell makes one extra small request every 60 seconds for users with the Emergency item. There are at most 100 IPD clinics.
- Each opening of the Admit hand-off adds one audit row (D2). This is low volume, because it happens once per admission attempt from Emergency.

## Review fixes (SAH-28 code and security review, 5 Nov 2026)

Sources: `docs/reviews/SAH-28-code.md`, `docs/reviews/SAH-28-security.md`. The contract `docs/api/ipd.yaml` is updated to match.

| Review item | Change | Where |
|---|---|---|
| Code 1 | The queue, counts and hand-off reads use one REPEATABLE READ snapshot. A scale change during a read cannot give a 500. | D6; `GET /emergency`, `/emergency/summary`, `/admission-options` |
| Code 2, QA F3 | Every emergency timestamp comes from `clock_timestamp()`, read once per action after the clinic lock. `waitingMinutes` and `asOf` use the same database clock. | D6; the description of each emergency operation; `EmergencyCase.waitingMinutes`, `EmergencyQueue.asOf` |
| Code 5(a) | Added to the message list: "Name cannot contain control or text-direction characters" and "Complaint cannot contain control or text-direction characters". Both are checked on the NFC text before trimming. | `POST /emergency/cases` |
| Code 5(b) | `identityUnknown` is required. If it is missing, null or not a JSON boolean, the answer is a 400 field error: field `identityUnknown`, "Reload the page and try again.". It is never read as false. | `POST /emergency/cases`, `EmergencyCaseInput` |
| Code 5(c) | Strict types. `age` and `level` must be JSON numbers and `identityUnknown` a JSON boolean. Strings such as "40" are refused with that field's message, never converted. This tightens the review's "accept" to match the tech lead's instruction. | `POST /emergency/cases`, `EmergencyCaseInput` |
| Code 6, QA F4 | The third sort key reads "then id (as a UUID compare)": `java.util.UUID` order, not the PostgreSQL uuid order. | `GET /emergency`, `EmergencyQueue.cases` |
| Code 7, QA F5 | No order rule between target times is recorded. **The decision is pending the human.** | D4; `PUT /emergency-settings` |
| Security M1(a) | `mobile` is cleared in the closing UPDATE (released, referred, admitted). The hand-off reads it while the case is open. Retention of name and complaint stays open (DEC-013 item 12). | Consequences; release, refer, `POST /admissions`, `EmergencyHandoff.mobile` |
| Security M3 | A 200 answer from the hand-off writes `IPD_ER_HANDOFF_OPENED` (case id and acting user id only). The contract text "writes no record-access row" now says this. The queue list stays unlogged. | D2; `GET /admission-options` |
| Security L1 | On a repeated client key, `actedCase` is the full `EmergencyCase` only while the case is WAITING or SEEN and the caller is its registrant. Otherwise it is the new `EmergencyCaseRef` (id, level, status; `additionalProperties: false`) and the client shows no toast. No existing small schema fitted. | `POST /emergency/cases`, `EmergencyQueue.actedCase`, `EmergencyCaseRef` |

No module boundary, dependency or phase 1 scope changes. `ipd` still uses only `platform.api`, `patient.api` and `opd.api`.
