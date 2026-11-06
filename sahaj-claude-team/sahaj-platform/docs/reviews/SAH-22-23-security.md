# Security review · SAH-22 and SAH-23

Reviewer: security-reviewer (read-only), 5 Nov 2026. Verdict: **PASS** (no Critical or High). 2 Medium, 6 Low.

Passed: RLS enabled and forced on all 8 new tables with using and with check; every ipd-internal FK composite on (tenant_id, id); column-level grants on admission and deposit; deposit append-only (grants + trigger); role gates match the contract and are enforced with OwnerGuard where owner-only; every endpoint gated for module-off and ipd-readonly; no IDOR (all ids tenant-looked-up); admit serialised by advisory lock + bed row lock + partial unique index; IP number atomic; SQL parameterised; money in paise with limits; no patient data in logs or audit; no XSS sinks.

| # | Sev | Finding | Fix |
|---|---|---|---|
| M1 | Medium | Admission detail returns name, mobile and clinical reason to OWNER/FRONT_DESK/NURSE/DOCTOR but writes no `patient.record_access` row; mobile shown to all four roles | Record access on detail read via a patient.api port; return mobile only to OWNER and FRONT_DESK |
| M2 | Medium | `admission.patient_id` is a plain FK (FK checks ignore RLS): a future code path could attach another clinic's patient | New migration: unique (tenant_id, id) on patient.patient and composite FK from admission |
| L1 | Low | A merged-away duplicate patient id can be admitted a second time | Refuse when merged_into_id is set |
| L2 | Low | Deposits: no idempotency key, no reversal | Plan idempotency/void row in billing or discharge story |
| L3 | Low | Admit takes the clinic advisory lock before input validation | Validate shape first; optional lock_timeout |
| L4 | Low | Owner IPD trial switch is true in the dev profile and dev is the default profile | Startup guard refusing the flag outside dev/test; release checklist |
| L5 | Low | Control and bidi characters allowed in ward name and reason | Reject/strip |
| L6 | Low | IP number unique clash would be reported as "Already admitted" | Inspect constraint name |

Also: doctor picker excludes an owner who is also a doctor (functional gap); patient search sends the query in the URL (pre-existing).
Not checked: dependencies, tests, .env.example listing for SAHAJ_MODULES_OWNER_IPD_TRIAL, token revocation for deactivated members, later-story endpoints, other modules' changes.
