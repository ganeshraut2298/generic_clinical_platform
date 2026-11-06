# ADR-004 · IPD discharge adds an OPD follow-up through an `opd.api` port

Story: SAH-27 (IPD part 6: discharge, final bill, Discharge setup)
Mockup screen: Transfer, Discharge, Case paper (card actions): `openIp`, `ipdis`, `openDis`, `ipdisseg`, `ipdisback`, `ipdisok`; Clinic setup → Discharge `cfgIpDis` (key `ipdis`); Follow-ups list `vFollowups` (SAH-14/16)
Module: ipd (caller), opd (owner of follow-ups), platform (one port method added)
Status: ACCEPTED (5 Nov 2026). Review decisions after the SAH-27 code and security reviews are recorded below.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/stories/SAH-27.md`, `docs/decisions/DEC-014-ipd-open-questions-proceed-with-proposals.md` (OQ-27-4, -8, -10), `docs/architecture/adr/ADR-002-tenancy-identity-and-audit.md` (D7: synchronous port, not an event), `docs/api/ipd.yaml`, `docs/api/opd.yaml`, migration `V2026_11_04_1000__opd_create_follow_up.sql`

## Context

When a patient is discharged (not Death), the mockup pushes a follow-up into the same list the OPD uses (`ipdisok`: `S.fus.push({... reason:'Review after discharge · '+a.dx})`). The Follow-ups list shows it like any other row. The Discharge setup hint says "The fee follows your follow-up rule (free within 6 days)", using the OPD fee settings (`S.fuFee`, `S.fuDays`).

Three facts shape the decision:

1. Follow-ups belong to `opd` (`opd.follow_up`, SAH-14). `ipd` may not write `opd` tables, and `opd` may not depend on `ipd` (CLAUDE.md; the task brief).
2. `opd.follow_up.source_visit_id` is `not null` and `unique`. A discharge has no OPD visit.
3. SAH-27 AC8 requires one transaction: if the follow-up is not created, the discharge must roll back, and the reverse.

Today `ipd` depends only on `platform.api` and `patient.api`. `opd` depends only on `platform.api` and `patient.api`. `billing` and `hra` already depend on `opd.api`. Nothing outside `ipd` imports `ipd`.

## Decision

### D1 · A synchronous port `com.sahaj.opd.api.FollowUpPort`, called inside the discharge transaction

`opd` publishes the port and implements it. `ipd` calls it as the last write of the discharge transaction. The adapter joins the caller's transaction (default propagation, never `REQUIRES_NEW`), so the follow-up and the discharge commit or roll back together.

```java
package com.sahaj.opd.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Follow-ups that other modules add (SAH-27: an IPD discharge), and the OPD follow-up fee rule they show.
 * Implemented by opd; runs in the caller's transaction (tenant already bound). opd keeps sourceAdmissionId
 * as an opaque id and never reads IPD data with it.
 */
public interface FollowUpPort {

    /** OPD fee settings (SAH-7): days = followUpDays (1..30), fee = "free", "half" or "full". Defaults when never saved. */
    record FeeRule(int days, String fee) {
    }

    /** A follow-up added by a discharge. reason: 1..200 characters (Unicode code points), already cut by the caller. */
    record DischargeFollowUp(UUID sourceAdmissionId, UUID patientId, UUID doctorMembershipId, LocalDate dueDate,
                             String reason) {
    }

    FeeRule feeRule(UUID tenantId);

    /**
     * Adds the follow-up and audits FOLLOW_UP_CREATED (patientId, doctorMembershipId, sourceAdmissionId, dueDate;
     * never the reason). Idempotent per sourceAdmissionId: a second call writes nothing and returns the first id.
     * A null field, or a blank or too long reason, throws IllegalArgumentException (a caller bug: 500, rolls back).
     *
     * @return the follow-up id
     */
    UUID createForDischarge(UUID tenantId, UUID actorUserId, DischargeFollowUp followUp);
}
```

Dependency added: `ipd → opd.api`. No cycle: `opd` never imports `ipd`, and `platform` and `patient` import neither. Spring Modulith `ModularityTests` proves it on every build.

Options considered:

- **A · Port in `opd.api`, same transaction (chosen).** Legal under the existing rule (a module may use another module's `api`). Atomic, as AC8 needs. Testable in one transaction. Same reasoning as ADR-002 D7.
- **B · `ipd` publishes an `IpdDischarged` event and `opd` listens.** Rejected. A Modulith listener runs after commit, on another thread, in another transaction: the discharge can commit while the follow-up is lost, which breaks AC8. It also makes `opd` depend on `ipd.api` for the event type, which the brief forbids.
- **C · `ipd` inserts into `opd.follow_up`.** Rejected. This is cross-module table access.
- **D · `ipd` keeps its own follow-up table and the Follow-ups list reads it through an `ipd.api` port.** Rejected. `opd` would depend on `ipd`. The list's counts, tabs and "returned" logic would also be split across two modules.

### D2 · Storage: the smallest safe change to `opd.follow_up`

```sql
alter table opd.follow_up alter column source_visit_id drop not null;
alter table opd.follow_up add column if not exists source_admission_id uuid;
alter table opd.follow_up add constraint uq_follow_up_source_admission unique (source_admission_id);
alter table opd.follow_up add constraint ck_follow_up_one_source
  check (num_nonnulls(source_visit_id, source_admission_id) = 1);
create index if not exists ix_follow_up_discharge_by_patient_doctor
  on opd.follow_up (tenant_id, patient_id, doctor_membership_id, created_at)
  where source_admission_id is not null;
```

- **Plain unique constraints, not partial unique indexes.** PostgreSQL 16 unique constraints treat NULLs as distinct by default. So the existing `uq_follow_up_source_visit` already allows many rows with a null visit and does not need to change. The SAH-14 `on conflict (source_visit_id) do nothing` keeps working unchanged. A partial index would force every `on conflict` to repeat its `where` predicate. The new `uq_follow_up_source_admission` works the same way and supports `on conflict (source_admission_id) do nothing`, which makes the port idempotent.
- **Existing rows stay valid.** Every existing row has `source_visit_id` set and gets `source_admission_id` null, so the check holds. Nothing is dropped or renamed. Relaxing `not null` and adding a nullable column are expand-only steps, so no contract phase is needed.
- **No foreign key on `source_admission_id`.** A key to `ipd.admission` would be a cross-schema FK, which is forbidden. The id is opaque to `opd`.
- RLS policy and grants are unchanged. `sahaj_app` already has table-level `select, insert, update`, which covers the new column. There is still no delete.
- The partial index serves the fee rule lookup in D3. `created_at` is the discharge instant, because the row is inserted in the discharge transaction.

### D3 · Follow-ups list and the fee rule, inside `opd`

- **List (SAH-14/16).** An admission follow-up is listed, counted, tabbed (Due today, Missed, Upcoming, Returned) and shown exactly like a visit follow-up: patient, mobile (by role, as today), doctor, due date, reason and status. This matches the mockup, which pushes the same row shape. No new UI. The API item gains a nullable `sourceAdmissionId`, and `sourceVisitId` becomes nullable (`docs/api/opd.yaml` `FollowUp`). The reason text is shown as the mockup shows it (OQ-27-10, DEC-014), masked by role for admission follow-ups (review decision b below).
- **Closing.** When the patient gets a token with the same doctor, `closeOnNewVisit` closes the oldest open follow-up of that patient and doctor, whatever its source. **Required fix:** `JdbcFollowUpRepository.CLOSE_OLDEST` filters `source_visit_id <> :returnedVisitId`. With a null `source_visit_id` that is NULL, so admission follow-ups would never close. It must become `source_visit_id is distinct from :returnedVisitId`.
- **Fee rule ("free within N days").** Today `VisitService.issueToken` makes a visit FOLLOW_UP when the patient's last visit with the same doctor is within `followUpDays`, and `feeFor` then applies `followUpFee`. A discharge counts as being seen by the admitting doctor at the discharge instant. The anchor becomes the later of `lastVisitAt(patient, doctor)` and `lastDischargeAt(patient, doctor)`, where the second is the latest `created_at` of an admission follow-up for that patient and doctor. The rest is unchanged. All data stays in `opd`, and `opd` learns nothing about IPD beyond its own row.
- **Consequence to know.** With the mockup's own defaults (follow-up 7 days after discharge, OPD free window 6 days), the planned review on day 7 falls outside the window, so it is a NEW visit at the full fee. The setup page shows both numbers side by side, so the owner can line them up. This is the literal rule the mockup states ("The fee follows your follow-up rule"). It is not an invented one.

### D4 · Who gets the follow-up, its date and its reason (built in `ipd`)

- **Doctor:** the admitting doctor (`ipd.admission.doctor_membership_id`; OQ-27-8). The doctor is not checked for being active. The existing FK to `platform.membership` holds.
- **Due date:** the Asia/Kolkata date of the discharge instant plus `ipd.config.followup_days` (default 7), stored on `ipd.discharge.followup_due` and sent as `dueDate`.
- **Reason:** `"Review after discharge · " + admission.reason`, cut to 200 Unicode code points. Use `codePointCount` / `offsetByCodePoints`, not `String.length()`, so the result matches `char_length(reason) <= 200`. Never logged or audited.
- **Death:** the port is not called and `followup_due` stays null.
- **Tracing (corrected).** `ipd` does not keep the returned id. The column `ipd.discharge.followup_id` was dropped in `V2026_11_20`. The follow-up of a discharge is traced from the `opd` side: `opd.follow_up.source_admission_id` holds the admission id (unique), so there is one place for the link and no second copy to keep in step.

### D5 · One `platform.api` method for the summary preview

The preview row "Stay" needs the admitting doctor's specialty. `TeamMemberPort.TeamMember` has no specialty. Add to `com.sahaj.platform.api.TeamMemberPort`:

```java
/** SAH-27: the doctor profile's specialty, empty when not set, not a doctor, or another clinic's id. */
Optional<String> doctorSpecialty(UUID tenantId, UUID membershipId);
```

A new method keeps every existing `new TeamMember(...)` call and test unchanged. `platform` implements it from `platform.doctor_profile.specialty`, which has been nullable since SAH-6.

### D6 · Money stays in `ipd` (OQ-27-4)

The final bill and settlement are stored only in `ipd` (`ipd.final_bill`). No `billing.api` port is added now (DEC-014). The Billing counter, day close and revenue do not show IPD money until the later billing-link story. That story is blocking for go-live.

## Consequences

**Positive.** Follow-ups have one owner and one list. The discharge and its follow-up are atomic. The only new dependency direction (`ipd → opd.api`) is checked by the build. The migration is expand-only and keeps every SAH-14 statement working. The fee rule is the OPD rule, not a second one.

**Negative / accepted.** `ipd` now fails to start if `opd` is missing. That is fine for a monolith. The `is distinct from` fix and the visit-type anchor change touch SAH-14/SAH-9 code, so their tests must be re-run (`FollowUpListsIT`, `FollowUpServiceTest`, `VisitService` tests). The mockup defaults make the discharge review chargeable (D3).

## Review decisions (tech lead, after `docs/reviews/SAH-27-code.md` and `docs/reviews/SAH-27-security.md`)

- **(a) Discharge sheet read is access-logged.** `GET /api/v1/ipd/admissions/{id}/discharge` returns the patient name and the diagnosis, so it writes one `patient.record_access` row (through `patient.api`) after the 404 and not-admitted checks pass. A 403, 404 or 409 answer writes nothing. It still changes no IPD data and never creates a settings row.
- **(b) Discharge reason masked by role in the Follow-ups list (OQ-27-10).** The stored reason stays `Review after discharge · <reason for admission>`. For admission-sourced follow-ups, roles outside OWNER, FRONT_DESK, NURSE, DOCTOR and VISITING_DOCTOR see only `Review after discharge`. Visit follow-ups are unchanged. Contract: `docs/api/opd.yaml` `FollowUp.reason`.
- **(c) Tenant-composite keys on `opd.follow_up`.** `V2026_11_21` makes the patient and doctor references of `opd.follow_up` tenant-composite (`(tenant_id, patient_id)`, `(tenant_id, doctor_membership_id)`), so a follow-up can never point at another clinic's patient or doctor, whichever module supplies the ids.
- **(d) Optional, later.** `uq_follow_up_source_admission` may become per clinic, `unique (tenant_id, source_admission_id)`, in line with (c). Not required now: admission ids are random UUIDs and the port is called only with the caller's own admission. If done, the port's `on conflict` target changes with it.

## Instructions

**db-engineer (one forward-only migration, `V<ts>__ipd_discharge_and_opd_follow_up_source.sql`, or two files, one per module):** the D2 SQL above, plus the `ipd` tables in "Data needed" in the handoff for SAH-27. Add comments on `source_admission_id` ("SAH-27: the IPD admission whose discharge added this follow-up; opaque, no FK") and `ck_follow_up_one_source`.

**java-developer:**
1. `opd.api.FollowUpPort` (D1), adapter in `opd.application` delegating to `FollowUpService.createForDischarge` (validate, `insertForAdmissionIfAbsent ... on conflict (source_admission_id) do nothing`, then on no insert `findIdBySourceAdmission`, then audit when inserted) and `feeRule` (from `FeeSettingsRepository`, defaults `FeeSettingsService.DEFAULTS`).
2. `FollowUpRepository`: `insertForAdmissionIfAbsent`, `findIdBySourceAdmission`, `lastDischargeAt(tenantId, patientId, doctorMembershipId)`. `FollowUpRow`, `FollowUpView.Item` and `FollowUpResponse` gain `sourceAdmissionId`; `sourceVisitId` may be null. Map with `rs.getObject(..., UUID.class)` (null-safe).
3. `CLOSE_OLDEST`: `is distinct from` (D3). `VisitService.issueToken`: anchor = later of last visit and last discharge (D3).
4. `TeamMemberPort.doctorSpecialty` (D5) and its platform implementation.
5. `ipd` discharge service: call `followUpPort.createForDischarge` last in the transaction, except for Death. Update the `package-info` of `com.sahaj.ipd` (now also uses `opd.api`) and `com.sahaj.opd.api` (adds `FollowUpPort`, used by `ipd`).
6. `ModularityTests` must stay green. Add unit tests for: admission follow-up is closed by a new visit; a return within the window after discharge is FOLLOW_UP; a second `createForDischarge` with the same admission writes nothing.

**angular-developer:** `FollowUp.sourceVisitId` becomes `string | null`, and `sourceAdmissionId: string | null` is added. The display does not change.
