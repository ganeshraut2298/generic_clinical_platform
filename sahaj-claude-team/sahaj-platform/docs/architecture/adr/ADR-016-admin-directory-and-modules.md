# ADR-016 · Admin directory, clinic detail, modules, plan and activity: function contracts and search privacy

Stories: ADM-2 (2a list, 2b detail), ADM-3 (3a modules, 3b plan, 3c activity log, 3d end of the owner trial)
Mockup screens: Sahaj admin portal: `orgDirPage` (route `clinics`), `aClinic` (header; tabs Plan and billing, Modules, Activity log), `aModules` (`amod`, `amodbeds`), `ADM_KIND.plan` / `aActHTML` / `aago`, `aAuditRows`
Module: platform (admin packages). ipd supplies one read-only count through `platform.api` (`IpdOccupancyPort`), as ADR-003 D6 says.
Status: PROPOSED (9 Nov 2026). Waiting for the human: role `sahaj_admin_definer` (DEC-012) and `ADM-2` / `ADM-3` `contract` approval.
Amends: ADR-003 rev 2, D4 (function list and signatures for ADM-2/3), H14 (the IPD filter is withdrawn), the ADM-2 API table (`GET /clinics` becomes `POST /clinics/search`) and the ADM-3 activity rule (refused rows are stored but not listed).
Related: DEC-012, DEC-023, DEC-029 (defaults and NEEDS HUMAN), `docs/architecture/ADM-2-3-admin-directory.md` (design note and data needed), `docs/api/admin.yaml` 0.4.0.

## Context

ADR-003 rev 2 fixed the frame. The admin request path never binds `app.tenant_id`. Cross-tenant work happens only in `SECURITY DEFINER` functions owned by `sahaj_admin_definer`. Each of these functions runs the session guard first and writes its own `platform_admin_audit` row. Column grants and policies are given `TO sahaj_admin_definer` only. ADR-003 listed the ADM-2/3 functions by name but left four things open:

1. the exact parameters,
2. how a write returns the clinic without writing a second "viewed" row,
3. where the cross-module admitted count sits relative to the audit,
4. how search text stays out of logs when it travels in a URL.

The stories add 28 open questions and 12 contract gaps (DEC-029).

## Decisions

### D1 · Search moves from the query string to a request body: `POST /clinics/search`

The search text may contain digits of an owner's mobile. In a `GET` it would sit in the URL. Every access log (Tomcat, a reverse proxy, a future gateway) and the browser history would then record it. That breaks "no search text in logs" in places the application does not control.

| Option | Verdict |
| --- | --- |
| A · Keep `GET /clinics?q=…` and forbid query strings in our own logs | Covers only the logs we own. A hosting proxy or a browser history still keeps the digits. **Rejected.** |
| **B · `POST /clinics/search` with a JSON body `ClinicSearch` (chosen)** | The text never appears in a URL. The 4 KB admin body cap applies (`RequestBodySizeLimitFilter` runs before the admin token filter). The call is read-only and safe to repeat. It changes no state, so a retry is harmless. |

The Angular list keeps its search, filters and page in a component or service state when the admin goes to the detail and back. It never keeps them in route query parameters. The server logs the path only, never a request body, for every `/api/v1/admin/**` request.

### D2 · Function set for ADM-2/3 (refines ADR-003 D4; full specification in the design note)

| Function | Executable by `sahaj_app` | Guard | Audit row written by this function |
| --- | --- | --- | --- |
| `platform.admin_list_clinics(…, p_ip, p_request_id)` | yes | yes | `ACCESS CLINICS_LISTED` (filters and total, never the text) |
| `platform.admin_get_clinic(p_tenant, p_ip, p_request_id)` | yes | yes | `ACCESS CLINIC_VIEWED`; no row for an unknown id |
| `platform.admin_lock_modules(p_tenant)` | yes | yes | none (helper, see D3) |
| `platform.admin_set_modules(p_tenant, …, p_ip, p_request_id)` | yes | yes | `ACTION MODULES_CHANGED`, one row per changed field (APPLIED), or one REFUSED row |
| `platform.admin_set_plan(p_tenant, p_plan, p_reason, p_ticket, p_ip, p_request_id)` | yes | yes | `ACTION PLAN_CHANGED` |
| `platform.admin_list_clinic_activity(p_tenant, p_limit, p_offset, p_ip, p_request_id)` | yes | yes | `ACCESS CLINIC_ACTIVITY_VIEWED`; no row for an unknown id |
| `platform.admin_clinic_json(p_tenant)` (new, internal) | **no** (EXECUTE revoked from PUBLIC and `sahaj_app`) | called only from the functions above, after their guard | none |
| `ipd.admin_admitted_count(p_tenant)` | yes | yes | none (helper, see D3) |

- **The actor and the time come from the database.** The actor comes from `app.admin_id`, through the guard. The time comes from `now()`. The address and the request id are plain parameters, used only to fill the audit row.
- **Writes return the clinic themselves.** `admin_set_modules` and `admin_set_plan` return the `ClinicDetail` shape through `admin_clinic_json`, so a write never adds a `CLINIC_VIEWED` row. Only the `admittedNow` value is missing. Java adds it from the port.
- **An unknown tenant returns SQL `NULL` and writes no row.** Java answers 404. The guard's 42501 becomes 401 `admin-session-ended`.
- **Labels stay in Java.** One exception: the audit summaries are built in SQL from fixed `CASE` texts taken from the mockup (`aLog`), so the audit does not depend on caller text: "IPD set to On | Off | Trial · 30 days | Read-only", "HRA switched on | off", "Bed limit set to {n}", "Plan changed to Free year | Pro".
- **Search text is escaped in one place.** The function escapes `%`, `_` and `\` before every `ILIKE`. Java passes the trimmed raw text and does not escape it (the story's task list said Java escapes; this replaces that). Text is never concatenated into SQL.

### D3 · Helpers without their own audit row: an explicit exception to "every function audits"

Two functions read cross-tenant data without writing an audit row. Each is always paired with an auditing function in the same `@Transactional` service call.

- `ipd.admin_admitted_count`: if it wrote to `platform.platform_admin_audit`, ipd would write a platform table (a cross-module table access). If a platform function called it in SQL, platform would depend on ipd (a dependency cycle). So it starts with `platform.admin_require_session()` and returns a single integer. The auditing platform function of the same service call (`admin_get_clinic`, `admin_set_modules`) writes the row. ADR-003 D4 already listed it as "none (count only; the calling platform function audits)".
- `platform.admin_lock_modules`: it must run before the count and before `admin_set_modules` (lock → count → set), so it cannot be the auditing call. It returns only the four entitlement values of one clinic. If the change is a no-op, no row is written (AC 2), as the stories want.

Residual risk: someone running SQL as `sahaj_app` (an injection) who binds a forged admin session could call these two helpers without an audit row. They would get one integer count, or the module values of one clinic. This is the same trust class as ADR-003 H3, and the same fix applies (`sahaj_admin_app`).

### D4 · Modules: the order inside one transaction, and the refusal

The service runs, in one `@Transactional` call:

1. `admin_lock_modules`: insert the default row if it is missing, take the row lock, and return the current values.
2. Java computes the effective state (`ModuleEntitlements`). It drops the fields that equal the current values. If nothing is left, it calls `admin_set_modules` with every change null. That call writes nothing and returns the detail. A no-op therefore writes neither a change row nor a `CLINIC_VIEWED` row.
3. If the target is `off` or `readonly`, Java calls `IpdOccupancyPort.admittedNow` (`ipd.admin_admitted_count`). If the count is above 0, Java calls `admin_set_modules` with `p_refusal_code = 'ipd-patients-admitted'` and `p_admitted_now`. The function writes one REFUSED row (summary "IPD set to {label}", outcome REFUSED, `after_json {"admittedNow": n}`) and changes nothing. Java then answers 409. The REFUSED row must stay after the 409, so the service commits the refusal: the refusal is a normal return value, not an exception that rolls back.
4. Otherwise Java calls `admin_set_modules` with the changed fields. The function writes one APPLIED row per field and returns the detail.

`admin_set_modules` re-checks its inputs. The values must be in range. `ipd_trial_ends_at` must be set exactly when the state is `trial`. A refusal code is allowed only with an `off` or `readonly` target and a count of 1 or more. It writes only the fields that are not null. Two admins who change different fields therefore both keep their change: the row lock orders them.

### D5 · The Activity log lists only APPLIED actions (refused rows are stored, not listed)

The mockup writes no row for a refused IPD change (`amod` returns before `aLog`), and the stories' text for a refused row is not in the mockup (DEC-029 NH-8). `admin_list_clinic_activity` returns rows with kind `ACTION`, outcome `APPLIED` and that `target_tenant_id`, newest first. REFUSED rows are still written (ADR-003 D4 rule 4), so the record of refusals is complete. They become visible once the human approves a text. Then the filter in the function changes and the contract gains the text. `ActivityEntry.outcome` stays in the contract with the single value `APPLIED` for now.

### D6 · IPD filter withdrawn (amends ADR-003 H14)

The mockup directory has no IPD filter. `ClinicSearch` has no `ipd` value and `ClinicFacets` has no `ipd` facet (DEC-029, OQ-2-1). `ClinicSummary.ipdState`, `hraOn` and `bedLimit` stay in each item (story AC 17). The UI does not show them.

### D7 · Errors and limits on the admin chain

- `400 bad-request` (shared `BadRequest`): the `{id}` path value is not a UUID. The value is never echoed.
- `400 validation` (`Problem` with `fieldErrors`): a body or parameter that breaks the schema, an unknown field, or `field = mobile` with fewer than 4 or more than 10 digits. The message never repeats the submitted search text.
- `413 payload-too-large` (shared `PayloadTooLarge`): every admin request has the **4 KB** cap, because the size filter runs before the admin token filter.
- `404 not-found`: an unknown clinic id.
- `409 ipd-patients-admitted`: the detail is the mockup sentence and the body carries `admittedNow`.
- `401 admin-session-ended` / `unauthorized`, `403 admin-network-denied` / `forbidden`, `503 admin-unavailable`: as ADM-1 defines them.

### D8 · Audit content (no search text)

- `CLINICS_LISTED` `after_json`: `{"field", "hasQuery": boolean, "status": [], "plan": [], "specialty": [], "cityCount": n, "signedWithinDays", "sort", "page", "size", "total"}`. The city names are left out: they are not secret, but a city list can be long, and the row is kept for 7 years. `q` itself and the mobile digits never reach a parameter that is stored.
- `CLINIC_VIEWED`: `target_tenant_id`. No owner mobile and no name.
- `MODULES_CHANGED`: `before_json` / `after_json` hold only the changed field (`{"ipdState": "off", "ipdTrialEndsAt": null}`, `{"hraOn": true}`, `{"bedLimit": 30}`). The reason is "Module entitlement".
- `PLAN_CHANGED`: `{"plan": "FREE"}` → `{"plan": "PRO"}`, with the reason as typed (trimmed) and the ticket. The reason is never written to an application log.
- `CLINIC_ACTIVITY_VIEWED`: `target_tenant_id`, `after_json {"page", "size"}`.

## Consequences

**Positive.** Search text is never in a URL, a log or the audit. Every endpoint has exactly one auditing function. A write never adds a misleading "viewed" row. The module boundary holds: ipd owns its count, and platform never reads `ipd.*`. The audit summaries do not depend on caller text.

**Negative.** `POST` for a read is less cache-friendly (an admin list is not cached anyway). Two helper functions have no audit row of their own (D3). Refused changes are invisible in the tab until NH-8 is answered. The contract leaves ADM-3b (plan) and the health rule pending human answers (DEC-029 NH-1, NH-4, NH-5).

## Needs human decision

See DEC-029 NH-1 to NH-8. Nothing in this ADR changes tenancy, authentication or module boundaries beyond ADR-003 rev 2 and DEC-012.
