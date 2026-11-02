# Database design · SAH-4 (clinic setup wizard)

Module: platform (+ one table in `opd`) · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-4.md` section 3, `docs/api/platform.yaml`, SAH-1/SAH-3 migrations 1000-1012.

Status: SQL written and applied — `node scripts/validate.mjs db` ran Flyway against `sahaj_test`
cleanly (no SQL error, no RLS-gate failure); see "Validation" below for the one unrelated failure
it did surface.

## Migrations (apply after 1000-1012, in this order)

| File | Creates |
| --- | --- |
| `V2026_11_01_1013__platform_create_setup_progress.sql` | `platform.setup_progress` |
| `V2026_11_01_1014__platform_create_clinic_profile.sql` | `platform.clinic_profile` |
| `V2026_11_01_1015__platform_create_doctor_profile.sql` | `platform.doctor_profile` |
| `V2026_11_01_1016__platform_create_tenant_slug.sql` | `platform.tenant_slug` |
| `V2026_11_01_1017__opd_create_opd_settings.sql` | `opd.opd_settings` |

1000-1012 are untouched (forward-only).

## Tables

### platform.setup_progress (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| tenant_id | uuid | no | pk; fk to `platform.tenant(id)` |
| current_step | integer | no | default 1; 1 to 5 |
| answers_json | jsonb | no | default `{}`; raw per-step answers, app-owned shape |
| completed_at | timestamptz | yes | set once by `setup/complete`; NULL = mid-setup |
| created_at, updated_at, version | | | standard |

- Policy `p_setup_progress_tenant` (FOR ALL): `tenant_id = platform.current_tenant()`.
- Grants: SELECT, INSERT, UPDATE. No DELETE (resume history; "done" is `completed_at`, not row absence).
- Rows: exactly one per tenant, forever. Negligible volume.
- **This is the signal SAH-3's login flow is missing** (flagged in SAH-1 point 4, left open in SAH-3 point... see "For java-developer" below).

### platform.clinic_profile (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| tenant_id | uuid | no | pk; fk to `platform.tenant(id)` |
| name | text | no | 1-160 chars |
| name_local | text | yes | ≤160 |
| address | text | yes | ≤400 |
| city | text | no | 1-120 chars |
| pin | char(6) | yes | `^[0-9]{6}$` |
| phone | text | yes | ≤20 |
| registration_no | text | yes | ≤80 |
| specialty_code | text | yes | gp, dental, paeds, homeo |
| brand | text | no | default 'blue'; blue/teal/green/maroon/custom |
| default_language | text | no | default 'en'; en/mr/hi |
| created_at, updated_at, version | | | standard |

- Policy `p_clinic_profile_tenant` (FOR ALL): tenant match.
- Grants: SELECT, INSERT, UPDATE. No DELETE.
- Written once, in the `setup/complete` transaction, from the accumulated `answers_json`; edited afterwards by SAH-7. Not written incrementally per step — that is `setup_progress.answers_json`'s job.
- `brand` duplicates `platform.tenant.brand` on purpose: `tenant.brand` is what auth/shell read today (SAH-1/SAH-5 depend on it); `clinic_profile.brand` is the wizard's own field per the story's table list. The app must write both in the same `setup/complete` transaction and keep them equal; `clinic_profile.brand` is the one SAH-7 edits later, and that edit must also update `tenant.brand`.
- Rows: one per tenant, forever.

### platform.doctor_profile (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| tenant_id | uuid | no | fk to `platform.tenant(id)` |
| membership_id | uuid | no | unique `uq_doctor_profile_membership`; fk to `platform.membership(id)` |
| specialty | text | no | 1-120 |
| fee_paise | bigint | no | default 0; ≥0 |
| degree | text | yes | ≤120 |
| registration_no | text | yes | ≤80 |
| mobile | char(10) | yes | `^[6-9][0-9]{9}$` |
| created_at, updated_at, version | | | standard |

- Indexes: `uq_doctor_profile_membership` (1:1 with membership), `ix_doctor_profile_tenant (tenant_id)` for the team list.
- Policy `p_doctor_profile_tenant` (FOR ALL): tenant match.
- Grants: SELECT, INSERT, UPDATE. No DELETE (doctor leaving = membership status REMOVED; row stays as history for past clinical/billing records that name them).
- `mobile` is additive beyond the story's column list — see open question in SAH-4 story ("doctors have no mobile field in the mockup... recommended: add a mobile field for doctors other than the owner"). Nullable; the owner-doctor's login mobile is `app_user.mobile` via their membership, so this column is only populated for doctor 2+ added in step 3 (and later via SAH-6).
- Rows: 1-2 per tenant on Free/Professional (plan limit), growing slowly later. Never deleted.

### platform.tenant_slug (no RLS, global)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| owner_tenant | uuid | no | pk; fk to `platform.tenant(id)`. Not named `tenant_id` on purpose — this codebase's own validation gate (`scripts/validate.mjs db`) treats any `tenant_id` column as requiring row level security, which this table deliberately has none of. |
| slug | text | no | unique `uq_tenant_slug_slug`; `^[a-z0-9][a-z0-9-]{2,23}$` |
| created_at | timestamptz | no | `now()` |
| updated_at | timestamptz | yes | |

- **Why this table exists**: `platform.tenant` has FORCE RLS on `p_tenant_self` (`id = current_tenant()`). Neither `sahaj_app` nor `sahaj_migrator` has `BYPASSRLS` (`scripts/db/init.sql`), and FORCE applies row security to the table owner too, so nothing — not even a `SECURITY DEFINER` function owned by `sahaj_migrator` — can read another tenant's row in `platform.tenant`. `GET /setup/slug-availability` genuinely needs to ask a cross-tenant question ("does any tenant already have this slug?"). Rather than weaken `platform.tenant`'s isolation (e.g. a `using (true)` SELECT policy, or a session-settable escape hatch any buggy query could flip), this is a narrow, RLS-free mirror holding nothing but `tenant_id` and `slug` — the same sensitivity level as `platform.app_user` (also no RLS, by the same reasoning).
- **Not the source of truth.** `platform.tenant.slug` (SAH-1, unique index already in place) stays authoritative. The application must write both in the same transaction, every time a slug is set or changed: now at `setup/complete`, and later if SAH-7 ever lets a clinic change its address. If the two ever disagree, trust `platform.tenant.slug`.
- Grants: SELECT, INSERT, UPDATE. No DELETE.
- Availability check: `select 1 from platform.tenant_slug where slug = :candidate` — exists ⇒ taken (`owner_tenant` tells you which tenant holds it, if needed). Alternatives (slug+city, slug+number per the story) are generated and checked the same way, in a loop, by the application.
- The real race guarantee (AC "slug race, unique index wins, returns 409") is `platform.tenant.slug`'s unique index, hit inside the same `setup/complete` insert/update that also writes `tenant_slug`; both unique constraints fire together under concurrent completes, and the app returns 409.
- Rows: one per tenant, forever.

### opd.opd_settings (RLS, FORCE) — schema `opd`, not `platform`
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| tenant_id | uuid | no | pk; fk to `platform.tenant(id)` |
| fee_timing | text | no | default 'before'; before/after/split |
| queue_on | boolean | no | default true |
| vitals_on | boolean | no | default false |
| follow_up_days | integer | no | default 7; 1-30 |
| follow_up_fee | text | no | default 'free'; free/half/full |
| created_at, updated_at, version | | | standard |

- Policy `p_opd_settings_tenant` (FOR ALL): tenant match. Grants: SELECT, INSERT, UPDATE. No DELETE.
- Per the story: "`opd_settings` is written through the `opd.api` port in schema `opd`" — platform's setup/complete code must call into the `opd` module's own API port rather than writing this table directly, keeping the module boundary (no cross-module table access, only `opd.api`).
- Field names here match the story's technical design (`fee_timing`, `queue_on`, `vitals_on`, `follow_up_days`, `follow_up_fee`); the API's `SetupOpd` schema uses different names (`feeTiming`, `queue`, `vitals`, `followUpDays`, `followUpFee`) and different value spellings for `feeTiming` (`before/after/split` vs the API's own `before/after/split` — these already match) and `followUpFee` (`free/half/full` vs API's `free/half/full` — also match once you read the API text carefully: the story AC used "Free, 50%, Full" as display labels for the same three values). java-developer maps API DTO → `opd.api` port call → this row; no column renaming needed in SQL.
- Rows: one per tenant, forever.

## For java-developer: the login/mid-setup signal

SAH-1 (point 4) and SAH-3 (DEC-007 writeup) both flagged this and left it for SAH-4: once a
tenant and an OWNER membership exist (created at sign-up, SAH-3), a login that finds "exactly
one ACTIVE membership" would return `AuthResult.next = APP` even if the owner never finished the
wizard. That is wrong per SAH-4 AC2 ("leave at step 3, log in again, return to step 3").

**The fix is `platform.setup_progress.completed_at`.** Before SAH-3's login path returns `APP`
for a tenant, it must check this tenant's `setup_progress` row:
- `completed_at IS NULL` ⇒ return `next = SETUP` instead of `APP` (owner goes back to the wizard, not the app).
- `completed_at IS NOT NULL` (or no row — should not happen once sign-up always creates one) ⇒ `next = APP` as today.

Whoever creates the tenant+membership at sign-up (SAH-3's code) must also insert the
`setup_progress` row (`current_step = 1`, empty `answers_json`, `completed_at = NULL`) in the
same transaction, so every tenant has exactly one row from the moment it exists. `/me`
(SAH-5)'s `clinic.setupComplete`/`clinic.setupPercent` fields read the same row.

## Decisions and open points

1. **`clinic_profile.brand` duplicates `tenant.brand`.** See note under that table. Needed because `tenant.brand` already exists (SAH-1) and is what auth/shell depend on, but the story's table list puts `brand` on `clinic_profile`. Keep both in sync in application code; flag to solution-architect if this duplication should instead be resolved by dropping one of them in a later ADR.
2. **`platform.tenant_slug` is a new table, not in the story's list**, added to make the cross-tenant slug-availability check possible under FORCE RLS without weakening `platform.tenant`'s isolation or needing `BYPASSRLS` (a role-attribute change that is out of a migration's reach, and out of this agent's folder — `scripts/` is humans-only). See its full rationale above.
3. **Staff and doctors are not new tables beyond `doctor_profile`.** Staff added in wizard step 3 become ordinary `platform.app_user` + `platform.membership` rows at `setup/complete` (role FRONT_DESK/NURSE/COUNSELLOR/PHARMACIST), exactly like any team member added later through SAH-6; no setup-specific staff table is needed. **Resolved**: the tech lead confirmed the mockup's "Counsellor" role is real (mockup wins per CLAUDE.md rule 1) and `docs/api/platform.yaml`'s `Role` enum now includes `COUNSELLOR`. `V2026_11_01_1019__platform_membership_add_counsellor_role.sql` adds `COUNSELLOR` to `ck_membership_role`, forward-only, without touching the SAH-1 migration.
4. **No partitioning.** All four/five tables are one row (or a handful, for `doctor_profile`) per tenant — far under the 50 million row threshold.
5. **Retention**: none deleted. `setup_progress` kept forever as history even after completion (cheap, and useful for support/audit of "what did the owner originally choose").
6. **Backward compatibility**: additive only; no change to 1000-1012. A previous app version that doesn't know these tables exist is unaffected.

## Verification (to run when Flyway is on the classpath)

- With no `app.tenant_id` set, `select` on `setup_progress`, `clinic_profile`, `doctor_profile`, `opd.opd_settings` returns 0 rows; insert fails.
- Tenant A cannot see or write tenant B's row on any of the four RLS tables.
- `platform.tenant_slug`: no tenant setting affects visibility (no RLS); `sahaj_app` can select/insert/update, not delete; inserting a second row with a slug already present fails with 23505 (`uq_tenant_slug_slug`).
- `doctor_profile`: inserting two rows with the same `membership_id` fails with 23505 (`uq_doctor_profile_membership`).
- `opd.opd_settings`: same tenant-isolation shape as the platform tables above, in schema `opd`.

## Validation

`node scripts/validate.mjs db` result: the migration-file lint (file names, every table with a
`tenant_id` column has row level security) passed, and Flyway applied all migrations through
1017 against `sahaj_test` with no SQL error. `mvn verify` then failed on one pre-existing,
unrelated test: `ModularityTests.verifiesModularStructure` — `opd.infrastructure.persistence
.JdbcOpdDefaultsPort` calls `platform.infrastructure.security.TenantPrincipal`/`CurrentTenant`
directly, crossing the module boundary instead of going through `platform`'s exposed `api`
package. That is backend Java code (not in this agent's folder); flagged for java-developer in
the handoff, not fixed here.
