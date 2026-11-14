# ADR-024 · Patient date of birth and the one age rule

Story: SAH-73 (SAH-73-1 to SAH-73-5) · Optional patient date of birth
Mockup screen: Patients (sidebar → Patients); EMR mockup #DoctorPaeds and the dental chart (consumers)
Module: **patient** (owner of the date and of the age rule). opd reads both through `patient.api`. No change to billing or ipd contracts.
Status: PROPOSED (13 Nov 2026), awaiting `SAH-73 · contract · APPROVED`
Source: DEC-045 and its addendum (all defaults SO-73-1 to SO-73-12 accepted; SO-73-10: covered by the existing records consent)
Builds on: ADR-002 (tenancy D1 to D4, permission D6, audit D7), ADR-009 D9 and ADR-013 D15 (patient ports, data minimisation per port), ADR-020 D14, ADR-023 D11 (`PatientClinicalPort.dateOfBirth`), H9 (immunization windows), H15 (dental Child tab under 13)
Contracts: `docs/api/patient.yaml` 0.1.0 → **0.2.0**, `docs/api/opd.yaml` 0.5.2 → **0.5.3**

---

## Context

A patient has a whole-number age only (`patient.patient.age smallint not null`, SAH-8). Paediatrics needs months and days, the vaccine windows are in days, weeks and months (H9), the dental Child tab needs an exact "under 13", and a stored age goes stale. DEC-045 adds an optional date of birth. The ADR has to settle five things that every module depends on:

1. where the age is worked out, so the Patients list, the EMR header, the queue, the bill and the IPD pick show the same number on the same IST day;
2. how the small-child age text ("18 months", "10 weeks", "9 days") and the Paediatrics months and days are carried;
3. who may see the date itself (BR73-12) and how it is kept out of lists, logs, audit values and URLs;
4. what the stored `age` column means once a date exists, and the merge rule;
5. validation and audit.

What exists already (read in the code, not changed by this ADR): `PatientClinicalPort.PatientClinical` already has a nullable `dateOfBirth` (always null today); opd's `PaedsRules.vaccineStates`, `RuleTag.ageMonths/ageDaysPart` and `DentalRules.childTabDefault` already compute from a date of birth and an IST "today"; with no date every non-given vaccine is LATER and `childTabDefault` is false. Every other module reads the age through `PatientLookupPort` or `PatientClinicalPort`, which `patient` fills from its own row (`PatientLookup`, `PatientClinicalLookup`).

Two existing gaps this story's criteria touch (found while reading; fixed as part of SAH-73, see D9):
- The list filter `kids` is `age < 18` in `JdbcPatientRepository`; the mockup (`ptMatch`: `p.age<13`) and SAH-73-3 say **under 13**.
- The patient audit snapshot (`PatientService.snapshot`) writes `name` and `mobile` values; SAH-73-1 says no name, mobile or date of birth in any audit value.

---

## Decisions

### D1 · The age is worked out in one pure function in `patient.api`, on every read, from the IST date

**Rule.** A patient's age is never read from a stored number when a date of birth exists. It is computed **on every read**, in Java, by one pure class `com.sahaj.patient.api.PatientAge` (no Spring, no database, Java 17), as of **today in `Asia/Kolkata`**.

- "Today" is `LocalDate.now(clock)` with a `Clock` in `Asia/Kolkata` (injected, so tests fix the day). Never `current_date` in SQL (that is the database server's zone), never the request.
- The `patient` module computes it for its own responses and fills it into both ports (`PatientLookupPort.PatientSummary.age`, `PatientClinicalPort.PatientClinical.age` and the new `ageDetail`). So every module receives the same number from the same function on the same day, and **no other module computes a patient's age itself**.
- The one exception is arithmetic that only opd needs, on dates opd owns (vaccine window dates, the age at an earlier growth point): opd calls the same `PatientAge` static functions with the `dateOfBirth` and the `asOf` date the port returned (or the record date for a past point). opd's own `RuleTag.ageMonths/ageDaysPart/ageDays` are replaced by `PatientAge` so there is one implementation.

**Definitions** (all from `java.time`, which gives the 29 Feb rule of SO-73-9 for free):

| Value | Definition |
| --- | --- |
| `years` | `Period.between(dob, asOf).getYears()` (completed years). Born 29 Feb 2020: 6 on 28 Feb 2027, 7 on 1 Mar 2027. |
| `totalMonths` | `Period.between(dob, asOf).toTotalMonths()` (completed months) |
| `daysAfterMonths` | `Period.between(dob, asOf).getDays()` (days after the completed months) |
| `totalDays` | `ChronoUnit.DAYS.between(dob, asOf)` |
| unit and value (BR73-7) | `years ≥ 2` → YEARS, `years`; else `totalMonths ≥ 3` → MONTHS, `totalMonths` (3 to 23); else `totalDays ≥ 14` → WEEKS, `totalDays / 7` floored (2 to 13); else DAYS, `totalDays` (0 to 13) |
| no date of birth | YEARS, the stored whole number (BR73-5), unchanged over time; months and days are null |

**Filters in SQL use a date bound, never a function of the column.** "Completed years ≥ N" is exactly `dob ≤ asOf.minusYears(N)` (Java's `minusYears` on today matches `Period.between`, including 29 Feb). So the list chips are:
- `kids` (under 13): `(date_of_birth is null and age < 13) or date_of_birth > :kidsBound` with `kidsBound = today.minusYears(13)`;
- `seniors` (60+): `(date_of_birth is null and age >= 60) or date_of_birth <= :seniorsBound` with `seniorsBound = today.minusYears(60)`.
The bounds are computed in Java by `PatientAge` and bound as parameters. The duplicate check (same mobile, age within 2 years, similar name) already loads the family of one mobile and compares in Java; it compares the **effective years** of both sides, never the dates (BR73-10).

**Options considered.**
- A · Compute in the database (a generated column or a view with `age()`): rejected. A generated column cannot use "today" (not immutable); a view runs in the server's zone unless every caller sets it, and PostgreSQL's `age()` and Java's `Period` would need a parity proof forever. Two implementations of one clinical rule.
- B · Compute in the browser from the date: rejected as the authority. Billing and pharmacist must not receive the date (D3), so they could not compute; and the Paediatrics vaccine states are server-computed (ADR-023 D4).
- C · **Compute in the patient module's Java, one pure function, IST clock (chosen).** One implementation, unit-testable with fixed dates, reachable by opd through `patient.api`. The browser computes only the read-only preview in the registration dialog (D2), and the server's answer wins after Save.

### D2 · What is carried: `age` (number, as today) plus `ageDetail` (unit and value); months and days only to Paediatrics

- **Every patient response keeps `age`** (integer), now meaning the **effective completed years** (from the date when present, else the stored number). Nothing that reads `age` today changes shape: opd queue cards, appointments, Today, bills, IPD admission pick and cards, the EMR header `age`.
- **New `ageDetail { value, unit (YEARS | MONTHS | WEEKS | DAYS), fromDateOfBirth }`** on the Patients responses (`Patient`, `PatientSummary` in list and family) and on the EMR header and case paper patient. The screen turns it into text; the number is the server's:
  - YEARS: list and family rows "34" ("34 F"), record header "34 yrs · Female" (as today).
  - MONTHS "18 months", WEEKS "10 weeks", DAYS "9 days" (singular "1 day"), the same text in list rows ("18 months F") and headers ("18 months · Female").
- **Months and days** (growth, weight band, milestones, vaccine windows) go only to the Paediatrics view, in the existing `EmrSpecialtyView.paeds.ageMonths` and `ageDays`.
- **Not changed in this story:** the age text on queue cards, bills, IPD screens, and the EMR header and case paper of non-Paediatrics departments keep the integer (story SAH-73-4: "given another department, the age shows as before"). For a child of 18 months these show "1". That is correct completed years and is what SAH-73-3 asks ("worked out as of today, not a stored number"); a month text on those screens would be a new story (open point O1).
- `patient.api`: `PatientClinical` gains `PatientAge ageDetail` (computed by `patient` with its clock: `asOf`, `years`, `unit`, `value`, `fromDateOfBirth`, `totalMonths`, `daysAfterMonths`, `totalDays`, the last three null without a date). `PatientLookupPort.PatientSummary` does **not** gain the date or the detail (billing, queue and IPD do not need them; data minimisation as ADR-013 D15); only its `age` value becomes effective years. `PatientAge.toString` prints unit and value only, never the date.

### D3 · Who sees the date itself: a fixed allow-list in code, and the field is absent (not null) for everyone else

| Where | OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK | BILLING, PHARMACIST, COUNSELLOR, any custom role |
| --- | --- | --- |
| `GET /patient/patients/{id}` | `dateOfBirth` present (a date, or null when not recorded) | property **absent**; `age` and `ageDetail` only |
| `POST /patients`, `PATCH /patients/{id}`, `POST /patients/merge` answers | present (only OWNER and FRONT_DESK reach these) | not reachable (403) |
| `GET /patients`, `GET /patients/{id}/family` (lists) | **never** (BR73-12, BR73-17) | never |
| `PatientLookupPort` (queue, bills, IPD lists) | never | never |
| `PatientClinicalPort` (opd EMR, ipd MAR, admitted cards) | carries the date; **opd puts it only in `EmrSpecialtyView.paeds.dateOfBirth`** (Paediatrics records, read only by DOCTOR, VISITING_DOCTOR, OWNER with doctor details) for the vaccination card; ipd does not map it into any answer | — |

- The allow-list is by **system role key**, fixed in code like ADR-019 D8's capabilities, not a cell of the "Who can do what" grid (the mockup has no such cell). A custom role is outside the list (default deny). Decided once per request from the caller's membership, in one method of the patient web mapping, with a unit test per role.
- **Absent, not null:** null already means "not recorded" (BR73-14); a role filter that sends null would look like a clinical fact (the reasoning of ADR-013 D15). The java-developer uses a response type or `@JsonInclude(NON_ABSENT)` on an `Optional` for that one property.
- Nurse sees the date on the patient record only; the EMR stays closed to the nurse (SAH-62, 403).

### D4 · The stored `age` column stays `not null`; it is a fallback, refreshed on every write

- Additive nullable column `patient.patient.date_of_birth date`. No backfill, no guess from age (BR73-8); existing rows keep `date_of_birth null` and read exactly as today.
- `age` stays `smallint not null 0..120`. When a date is present, the service writes `age = completed years as of today` on every insert or update of that row (a fallback snapshot). **It is never read for display or rules while the date is present** (D1); the repository row type returns both, and only `PatientAge` decides.
- Clearing the date (BR73-9): the request may carry an `age`; if it does, that number is stored; if not, the stored age becomes the completed years worked out from the old date on that day. The record is never left without an age.
- **Options considered.** Making `age` nullable when a date exists (check `age is not null or date_of_birth is not null`): rejected; every reader of `int age` in four modules and the clear rule would need a null path, for no gain, since D1 already forbids reading the column when a date exists. Dropping `age` for dated patients later remains possible (expand and contract).
- The 120-year limit is enforced in the service (it depends on today). The database keeps a fixed sanity floor only (`date_of_birth >= date '1900-01-01'`), never a check on `current_date`.

### D5 · Write rules and validation (create, update, merge)

Create (`POST /patients`, `PatientCreate`):
1. `dateOfBirth` optional, nullable, an ISO full date `YYYY-MM-DD` with no time and no zone.
2. `age` required **only when `dateOfBirth` is absent or null** (BR73-1).
3. Both sent: `age` must equal the completed years from the date as of today, else 400 on `age` (BR73-6, SO-73-4). The form never sends both.
4. A date: the stored `age` is the completed years (D4).

Update (`PATCH /patients/{id}`, `PatientUpdate`), three states of `dateOfBirth`:
- **absent**: unchanged. An `age` sent while the record keeps a date must match its completed years (400 on `age` otherwise); a matching age is a no-op.
- **a date**: set or correct; the same checks as create; `age` sent must match.
- **explicit `null`**: clear (BR73-9); `age` optional, rule D4.
The java-developer must tell absent from null (for example by reading the body as a JSON tree or with a tri-state wrapper); a plain record field cannot.

Messages (SO-73-7), 400 `validation` with `fieldErrors`, the detail never echoes the input:

| Case | Field | Message |
| --- | --- | --- |
| Not a date, not `YYYY-MM-DD`, or a date that does not exist (31 Feb 2026), or not a string | `dateOfBirth` | "Enter a valid date of birth." |
| Later than today (IST) | `dateOfBirth` | "Date of birth cannot be in the future." |
| Completed years as of today above 120 | `dateOfBirth` | "Date of birth must be within the last 120 years." |
| Both sent and they disagree | `age` | "Age does not match the date of birth." |
| No date and no age, or age outside 0 to 120 | `age` | "Enter an age between 0 and 120" (SAH-8 text, unchanged) |

Today is accepted (a newborn). A parse failure of `dateOfBirth` must become this field error, not a generic body error, and the Jackson message (which quotes the value) must never reach the answer or a log.

Merge (`POST /patients/merge`, BR73-11, SO-73-8): the kept record's date stays; if it has none and the merged-away record has one, the date is copied to the kept record; if both have dates and they differ, the kept date stays and the answer carries `datesOfBirthDiffered: true`, which the screen shows as "The two records have different dates of birth. The date on the kept record was kept." When the kept record ends with a date, its stored `age` is refreshed from that date (D4) instead of the merged record's typed age. Without dates on either side the SAH-8 behaviour is unchanged.

Roles and tenancy unchanged (BR73-18): writes OWNER and FRONT_DESK (403 otherwise), another clinic's id 404, no session 401, the tenant from the session only (ADR-002 D2).

### D6 · Audit: one event per date change, no value; no name, mobile or date in any patient audit value

- Through the existing `platform.api` audit port, in the same transaction (ADR-002 D7).
- **New event `PATIENT_DATE_OF_BIRTH_CHANGED`** (entity `PATIENT`, the patient id) whenever an update or a merge sets, changes or clears the date. Before and after values hold only `{ "field": "dateOfBirth", "dateOfBirthRecorded": <bool> }` and the after value adds `"change": "SET" | "CHANGED" | "CLEARED"`. **Never the date, never an age derived from it** (an age with a known change day narrows the date).
- Registration: the existing create event; its snapshot carries `dateOfBirthRecorded`, no second event.
- **All patient audit snapshots** (create, update, merge) stop carrying `name` and `mobile` values (SAH-73-1 criterion). They carry `changedFields` (field names) plus the non-identifying values already there (sex, language, age, abhaLinked, allergyRecorded, chronicRecorded, mergedIntoId) and `dateOfBirthRecorded`. The `age` value in a snapshot is allowed only for an undated patient; for a dated patient the snapshot carries no age.
- A date of birth is never written to: application logs (no `toString`, MDC or exception message contains it), audit values, problem details, URLs, query strings, browser titles, browser storage, or exports.

### D7 · Paediatrics, dental and the EMR (opd 0.5.3)

All already computed by opd from `PatientClinical`; this ADR fixes the sources and the missing pieces.
- `EmrRecordHeader` and `EmrCasePaperContext.patient` gain `ageDetail` (null for an Emergency case with no patient). The client uses it for the Paediatrics header ("18 months · Boy") and the Paediatrics case paper meta; other departments keep `age` (BR73-17, SAH-73-4).
- `EmrSpecialtyView.paeds` gains `dateOfBirthRecorded` (false shows "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age.", SO-73-6) and `dateOfBirth` (the vaccination card only). `ageMonths` and `ageDays` = `PatientAge.totalMonths` and `daysAfterMonths`; null without a date. Growth note and milestone band use `ageMonths`; without a date: "Centile not available for this age", "Milestones for this age are not set up" (BR73-14).
- Vaccine states keep the rule in `PaedsRules.vaccineStates` (window from `dob + from`, to `dob + to`, end exclusive, `LocalDate.plusDays/plusWeeks/plusMonths`, today IST). Without a date: every not-given vaccine is LATER and none can be added (400 as SAH-62-2). A date corrected later recomputes the states on the next read; GIVEN rows keep their dates (BR73-16).
- `EmrSpecialtyHistory.growthPoints[].ageMonths` becomes nullable: with a date, the age at the point's date = `totalMonths + daysAfterMonths / 30.4375`, one decimal (chart position only, not a rule); without a date null and the point is not plotted (no made-up month count, BR73-14).
- `dental.childTabDefault` = **effective completed years < 13**: from the date when present, else from the whole-number age (SAH-73-5 changes today's "needs date of birth" to the fallback).
- Dose by weight unchanged (SAH-62-3): never uses the age.

### D8 · The browser preview

The registration dialog and the Edit details sheet show the worked-out age read-only before Save. The Angular app implements the D1 table once (one pure function with the same test vectors as the Java class, including 29 Feb and the 2-year, 3-month and 2-week edges) and takes "today" as the date in `Asia/Kolkata` (`Intl.DateTimeFormat` with `timeZone: 'Asia/Kolkata'`), not the browser's zone. After Save the server's `ageDetail` replaces the preview. When the date is filled the form sends no `age`; when the date is cleared in Edit details, Age pre-fills with the worked-out years and is editable.

### D9 · Module and boundary check

- patient owns the column, the rule, the validation, the audit. opd reads only through `patient.api` (`PatientClinicalPort`, the static `PatientAge`). billing and ipd: no change of code beyond receiving effective years through the ports they already call. No new module dependency, port, event, library or service; `ModularityTests` stay green. No cross-module table access.
- Fixed as part of SAH-73 because its criteria require it: the `kids` filter becomes under 13 (mockup `ptMatch`), and the audit snapshots drop name and mobile (D6).
- Not in phase 1 and not touched: ABDM date of birth, notifications (vaccine reminders stay "Needs the WhatsApp service", DEC-040), documents, analytics.

---

## Consequences

**Positive**
- One function, one clock: the list, the record, the EMR, the queue, the bill and the IPD pick cannot disagree on a day.
- Nothing changes for an undated patient; no migration guesses anything; the change is additive in both contracts.
- The date reaches only the five roles and only two places (patient detail, Paediatrics view); lists and the three non-clinical roles never carry it.
- The audit proves every change without holding the value.

**Negative / accepted**
- The stored `age` of a dated patient is a write-time snapshot that a raw query could misread. Mitigated by D1 (all reads through `PatientAge`) and a test with a stale column value.
- Queue cards, bills and IPD show "1" for an 18-month child (open point O1).
- The browser duplicates the D1 table for the preview; the shared test vectors keep it honest and the server wins after Save.
- `PATCH` has no optimistic lock in the SAH-8 contract (no `version` in the API); two simultaneous edits of the date: last write wins, both audited. Adding `If-Match` is a later contract change.
- Filters by date bound mean a dated child leaves "Children" at the IST midnight of the 13th birthday (1 Mar for a 29 Feb birthday), as the story asks.

---

## Data needed (for the db-engineer)

Schema `patient`, table `patient.patient` only. One new migration, additive.

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| `date_of_birth` | `date` | yes | null | Calendar date, no time. Check `ck_patient_dob_floor`: `date_of_birth is null or date_of_birth >= date '1900-01-01'` (sanity floor only; future and 120-year rules are in the service because they depend on today). Column comment: "Personal data (SAH-73). Never logged, never in audit values, never in list responses." |

- `age smallint not null 0..120` unchanged (D4).
- **No backfill, no data migration, no guess from age** (BR73-8). Existing rows read `null`.
- No new index: the chips filter by a date bound inside the tenant's active rows (`ix_patient_tenant_active`), a few thousand to tens of thousands rows per tenant; the duplicate check reads by mobile (`ix_patient_tenant_mobile`). Revisit if the list query plan says otherwise.
- RLS, policy, grants unchanged (table-level `select, insert, update` already covers the column; no delete).
- Tenant scope: as the row. Retention: as the patient record (never deleted; merges keep the pointer). Audit: no table change (platform's audit tables; values hold no date).
- Backward compatibility: a previous app version ignores the column.

---

## Open points (none blocks the contract)

- **O1** · Month text for small children on queue cards, bills and IPD screens (they show completed years). Not asked by SAH-73; a later story if the human wants it.
- **O2** · Contract wording not in the mockup: "0 days" for a baby born today, "1 day" singular, "yrs" only for 2 years and over.
- **O3** · The growth-chart month position formula (`/ 30.4375`, one decimal) is the architect's choice for plotting only.
