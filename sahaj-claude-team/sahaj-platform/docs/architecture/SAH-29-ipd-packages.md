# SAH-29 · IPD packages, read-only slice: design note

Story: SAH-29 (`docs/stories/SAH-29.md`, AC1 to AC6). No `docs/requirements/SAH-29.md` exists.
Mockup: Clinic setup → Admission and deposits (`cfgIpAdm`, panel "Packages" under "Bed transfer rules", data `S.ipd.pkgs`); the running bill of the patient sheet (`openIp`, `ipLines`, `a.pkg`); the transfer action (`iptrans`); the discharge default template (`ipdis`). Screen-map row 29.
Module: ipd only (schema `ipd`, package `com.sahaj.ipd`). Contract: `docs/api/ipd.yaml`, tag SAH-29.
Decision input: human decision of 9 Nov 2026 (AskUserQuestion): build only what the mockup shows. OQ-29-1 (create and change packages) and OQ-29-2 (attach a package to an admission) are answered "not in this slice" and stay as future work. DEC-014 (SAH-29 waits for the human because it changes bill rules): that answer is now given for the read-only slice.

**No ADR.** No boundary, module, port, event, library or service changes. One new ipd table, three nullable ipd columns, one new GET, a new branch in the existing `RunningBill` function.

## 1. Facts checked on 9 Nov 2026

- `RunningBill.compute` (`com.sahaj.ipd.application.RunningBill`) is already the one function behind the sheet, the Admitted list, the board, the Deposit low pill and the Deposit alerts tile. The package branch goes inside it, so AC4 holds by construction.
- Bed-transfer segments are built on read from `ipd.bed_transfer.segment_days` (append-only). They are not stored bill lines.
- `ipd.final_bill_line.label` allows 1 to 200 characters. The longest package line is 10 + 80 + 39 = 129 characters, so it fits.
- `ipd.admission` has column-level INSERT and UPDATE grants for `sahaj_app` and a table-level SELECT. New columns can therefore be read by the app but not written by it, with no extra revoke.
- The discharge default template already has "surgical for an admission with a package" (SAH-27; mockup `ipdis`). It only needs the package to exist.
- The mockup has no package concept anywhere else: no field in the Admit sheet, no chip in the Admitted list or the board, no field in the Summary strip. `S.ipd.pkgs` and the two seeded admissions (`Arjun Mhaske`, `Pooja Shelar`) are demo data.

## 2. What is built

### 2.1 Packages panel (AC1)
`GET /api/v1/ipd/packages` returns `PackageList { items: IpdPackage[] }`. Each item has `id`, `name`, `pricePaise` and `includedDays`.
- Roles: OWNER, FRONT_DESK, NURSE, DOCTOR. This is the same check as `GET /admission-rules`, the other read on this page. Any other role gets 403 `forbidden`; module Off gets 403 `module-off`. Allowed in Trial, On and Read-only.
- Only active rows are returned, ordered by `created_at` then `id`. RLS scopes the query to the clinic. Not paged.
- Row rendering is client-side: a chip "N days" or "Day-care" (when 0), the name in bold, the fixed text "Bed, nursing and surgeon included · extra days and exclusions at normal rates", and the price "₹45,000". The fixed text is a client constant and is not sent.
- It writes nothing. No audit row (no change). No record-access row (no patient data).
- Responses: 200, 401, 403. There is no `{id}` path value and no request body, so neither BadRequest nor PayloadTooLarge is listed. The general 413 rule in `info.description` still applies to a body sent anyway, as on every other IPD GET.

### 2.2 Package copy on the admission (AC2)
`AdmissionDetail.package` is `AdmissionPackage { name, pricePaise, includedDays }`, or null. It is read from three new nullable columns on `ipd.admission`. It is a copy, not a link, so changing or switching off an `ipd.package` row never changes an attached bill. All four roles get it (they already see the bill). The UI draws no field from it (AC4: the Summary strip shows no new field). `AdmissionRow` and `AdmittedPatientRow` do not change.

### 2.3 Running bill with a package (AC3, AC4; mockup `ipLines`, quoted exactly)
Input: the admission's package copy (or none). With none, the bill is unchanged (SAH-25/26). With a package, the lines are built in this order and any line with qty 0 is dropped:

| # | Name | unitPaise | qty |
|---|---|---|---|
| 1 | `Package · <name> (bed, nursing and surgeon for <includedDays> days)` | package price | 1 |
| 2 | `Bed · <current category name> beyond the package` | bed charge a day of the current bed's category (no multiplier) | max(0, billDays − max(1, includedDays)) |
| 3… | `<service> · not in package`, one line per service and base price in order of first charge (as SAH-25) | base × current multiplier, half-up to whole paise (`RunningBill.scaled`) | sum of the charge rows |

- A package admission has no "Doctor visits" line, no "Nursing care" line and no segment lines. This holds even if `ipd.bed_transfer` has rows for the admission, including rows written before the package was set in the database. One rule: the package branch never reads transfers.
- `billDays`, `bedDayCut`, `categoryName`, `multiplier`, `paidPaise`, `depositPercent`, `alertPercent` and `depositLow(payer)` are unchanged. So are the heading note of the Running bill panel and the Summary strip. The package price counts from day 1.
- At discharge, `FinalBill.lines` freezes these lines like any others. No change to the discharge code beyond reading the package copy into `RunningBill`.
- Suggested signature change (java-developer decides): add a nullable `PackageCopy(String name, long pricePaise, int includedDays)` argument to `RunningBill.compute`. Every caller (list, board, tiles, sheet, discharge) passes the admission's copy. The batched list and board reads select the three columns in the same admission query, which adds no query.

### 2.4 Transfer and discharge (AC5)
- Transfer: no contract change. Checks, transfer row, bed states and audit `IPD_BED_TRANSFER` stay as SAH-26. The bill hides segments for a package admission (2.3), so the bed difference is not billed (OQ-26-4, as the mockup). Days beyond the package are billed at the bed the patient is in now. `segment_days` is still computed and stored as in SAH-26. It is a fact about the stay, and the bill ignores it.
- Discharge: `DischargeSheet.defaultTemplateKey` gives `surgical` when no template is set in Discharge setup, the admission type is neither maternity nor daycare, and the package copy is not null (mockup `ipdis`). This is already in the contract. Only the description was updated.

### 2.5 Tenancy, roles, audit (AC6)
- `ipd.package` gets `tenant_id`, ENABLE + FORCE RLS and a tenant policy. `sahaj_app` gets SELECT only.
- The package columns on `ipd.admission` get no INSERT or UPDATE grant for `sahaj_app`. The app cannot attach, change or clear a package.
- Another clinic's admission answers 404 as today. Another clinic's packages are never listed (RLS).
- Audit: the app makes no package change in this slice, so it writes no audit row. Changes made in the database are outside the app audit trail (open point P1).
- Logging: package name and price are not patient data. The bill still sits next to patient data, so log only ids and counts, as today.

## 3. Stored shape (for db-engineer)

### `ipd.package` (new, tenant table)
| Column | Type | Rule |
|---|---|---|
| `id` | uuid pk default gen_random_uuid() | |
| `tenant_id` | uuid not null default platform.current_tenant(), fk platform.tenant | RLS |
| `name` | text not null | char_length 1 to 80, `btrim(name) = name`; unique per tenant on `lower(name)` (index `(tenant_id, lower(name))`) |
| `price_paise` | bigint not null | between 0 and 100000000, `price_paise % 100 = 0` (whole rupees, shown as "₹45,000") |
| `included_days` | smallint not null | between 0 and 60 (0 = day-care) |
| `active` | boolean not null default true | never deleted; the list shows active rows only |
| `created_at` | timestamptz not null default clock_timestamp() | list order; `clock_timestamp()` so rows added in one transaction keep their order |
| `created_by` | uuid null | null while packages are set through the database (no app user); required once OQ-29-1 adds a write path |
| `version` | bigint not null default 0 | for the later write endpoints |

- Index `(tenant_id, created_at, id)` for the list. Constraint `uq_package_tenant_id unique (tenant_id, id)` for a later composite FK.
- RLS: ENABLE + FORCE, policy `for select to sahaj_app using (tenant_id = platform.current_tenant())`. Revoke all from `sahaj_app` and `public`, then grant SELECT only. No update or delete.
- Seeds nothing (OQ-29-3). The mockup's three packages are demo data. Tests insert their own rows as the migrator / test fixture, as SAH-24 did for Cleaning.
- `updated_at` and `updated_by` are not added: nothing updates a row in this slice. They come with OQ-29-1.

### `ipd.admission` (additive: three nullable columns, no default, no backfill)
| Column | Type | Rule |
|---|---|---|
| `package_name` | text null | char_length 1 to 80 |
| `package_price_paise` | bigint null | between 0 and 100000000, `% 100 = 0` |
| `package_days` | smallint null | between 0 and 60 |

- Check `ck_admission_package_all_or_none`: the three are all null or all not null.
- No INSERT or UPDATE grant to `sahaj_app` on these columns. The existing table SELECT covers reads.
- Recommended guard (trigger `trg_admission_package_set_once`): once set, the three columns cannot be changed or cleared, and they cannot be set while `status = 'DISCHARGED'`. This protects a bill already shown to a family and a frozen final bill against a careless database edit. It applies to every role, including the one used for hand-set data.
- No reference to `ipd.package`: the copy is the record (AC2). Adding `package_id` is left to OQ-29-2.
- Retention: same as the admission (never deleted). Package rows are never deleted (`active` instead).
- Not destructive: no drop, rename, type change or new NOT NULL on existing rows. Existing admissions read as "no package".

## 4. Open points for the human

| ID | Point | Recommended |
|---|---|---|
| OQ-29-1 | Create and change packages | Not in this slice (human, 9 Nov). Future story: OWNER on the same panel. |
| OQ-29-2 | Attach a package to an admission | Not in this slice (human, 9 Nov). Future story. Until then, package billing can be used only in development and test data. |
| P1 (go-live) | With no app write path, a real clinic could get a package only through a hand edit of its tenant data in the database. That is not audited by the app and touches billing. | No packages for real clinics until OQ-29-1/2 are built. Hand-set packages only in `sahaj_dev` / `sahaj_test`. |
| OQ-29-3 | Empty panel: the migration seeds nothing, so every clinic sees the "Packages" heading with an empty list. The mockup has no empty text. | Show the heading and an empty list (exactly the mockup with no rows) and add no text. An empty-state line such as "No packages yet." would be new text and needs the human. |
| OQ-29-4 | Mockup text "for 0 days" (day-care) in the bill line, and the chip/line "1 days" for 1 day. | Build the mockup text literally (rule: the mockup decides). The BA's proposal "for the day" changes a bill label and needs the human; if chosen, it is a one-line change in the line builder and the client chip. |
| OQ-29-5 | Extra charges on a package admission are re-priced at the current category's multiplier after a transfer (as SAH-26, OQ-26-1). | As the mockup (built). |
| OQ-26-4 | A package admission moved to a dearer bed: the bed difference is not billed. | As the mockup (built). |
| P2 | `active = false` hides a package from the list. The mockup has no inactive state. | Keep the column (the story asks for it, and rows are never deleted). Showing only active rows is the only way to retire a hand-set row without deleting it. |
| P3 | Whole-rupee check on package price (`% 100 = 0`). Not in the mockup; the panel shows rupees only. | Keep it (it matches deposits). |

## 5. Story conflicts (for the business-analyst; I do not edit `docs/stories/SAH-29.md`)
- Scope note "STATUS NEEDS DECISION BEFORE DEVELOPMENT": now decided (human, 9 Nov 2026, read-only slice). Proposed text: "Decided 9 Nov 2026: read-only slice; OQ-29-1 and OQ-29-2 are future work."
- Section 4 rows `POST /packages`, `PUT /packages/{id}` and `POST /admissions/{id}/package`: mark them "future work, not in this slice", or move them to section 6.
- AC6 last sentence (audit of package changes): add "(future work; no app change exists in this slice)".
- Section 3: `created_by` is nullable in this slice (2.5, section 3 above).
