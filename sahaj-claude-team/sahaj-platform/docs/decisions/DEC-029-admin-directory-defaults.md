# DEC-029 · ADM-2 and ADM-3: open questions and contract gaps, defaults applied

Date: 9 Nov 2026. Status: DEFAULTS APPLIED (DEC-023), except the items marked **NEEDS HUMAN**. The human can reverse any default.
Source: `docs/stories/ADM-2.md` (OQ-2-1 to OQ-2-14, gaps G2-1 to G2-7), `docs/stories/ADM-3.md` (OQ-3-1 to OQ-3-14, gaps G3-1 to G3-5).
Human instruction (AskUserQuestion, 9 Nov 2026): start the design now with the Recommended answers. Nothing is built until the human creates the role `sahaj_admin_definer` and approves `contract`.
Design: `docs/architecture/adr/ADR-016-admin-directory-and-modules.md`, `docs/architecture/ADM-2-3-admin-directory.md`. Contract: `docs/api/admin.yaml` 0.4.0.

How the items were sorted:
- The stories write each question with a "Default", which is the Recommended option. A default is applied when it follows the mockup, or only leaves out a mockup element that ADR-003 H13 or H14 already lists, which DEC-012 accepted ("H4–H14 stay at the architect defaults").
- An item is **NEEDS HUMAN** when the default needs a clinical, legal, billing or pricing rule, or adds a behaviour or text that the mockup does not have or draws differently. For these items the contract carries the interim behaviour named below, marked `x-needs-human`. The item is not counted as decided.

## ADM-2 open questions

| # | Question | Choice | Tag |
|---|---|---|---|
| OQ-2-1 | IPD filter (brief, not mockup) | **Not built.** Removed from the contract: no `ipd` search value and no `ipd` facet. `ClinicSummary.ipdState` stays (AC 17). This follows the mockup and supersedes the part of ADR-003 H14 that said "the brief's IPD filter is added". | default applied (DEC-023), reversible |
| OQ-2-2 | Health filter | Not built. There is no `health` facet or filter. Health is in the detail only. (H14) | default applied (DEC-023), reversible |
| OQ-2-3 | State | City only everywhere. The label **City or state** is kept as drawn. `field=city` matches the city only. (H14) | default applied (DEC-023), reversible |
| OQ-2-4 | Code | The slug is used as the code, and is null ("—") before setup finishes. The header repeats the word as drawn. A separate clinic code would need a data change and a new story. (H14) | default applied (DEC-023), reversible |
| OQ-2-5 | Date format | "24 Oct 2026", as the CLAUDE.md UI rule requires. Frontend only. | default applied (DEC-023), reversible |
| OQ-2-6 | Audit volume | One `ACCESS CLINICS_LISTED` row for every list call (ADR-003 D7). Never the search text. | default applied (DEC-023), reversible |
| OQ-2-7 | Plan text | "Free year" or "Pro" only. "· day N of 365" is not shown, because the free-year length is a pricing setting (see NH-5). | default applied (DEC-023), reversible |
| OQ-2-8 | Health rule | **NEEDS HUMAN (NH-1).** Interim in the contract: the ADR-003 rev 2 derivation. Suspended → Inactive (no sign-in in 7 days) → Needs help (setup wizard not finished) → Healthy. There is no "Payment due". This replacement rule is not the mockup rule (setup under 60% or fewer than 25 visits in 7 days). | NEEDS HUMAN |
| OQ-2-9 | Lead text "…act on them in bulk." | Shown as drawn. (H13) | default applied (DEC-023), reversible |
| OQ-2-10 | Texts not in the mockup (load error, not found) | **NEEDS HUMAN (NH-2).** Frontend only. The contract gives 404 `not-found` and 400 `bad-request`. The proposed texts are in the story. | NEEDS HUMAN |
| OQ-2-11 | Default tab | Plan and billing opens first. Usage is out of scope. (H13) | default applied (DEC-023), reversible |
| OQ-2-12 | WhatsApp row | Hidden. (H13; notifications are not in phase 1) | default applied (DEC-023), reversible |
| OQ-2-13 | Two mockup lines that do not match the product | (a) "Calls to /api/v1/ipd/* return 403 MODULE_NOT_ENABLED" is shown as drawn. It is static text and the API keeps `module-off`. (b) "Read-only for 90 days, then hidden" is shown as drawn because it is mockup text, but the rule behind it is **NEEDS HUMAN (NH-3)**. | (a) default applied (DEC-023), reversible; (b) NEEDS HUMAN |
| OQ-2-14 | Who asked for IPD | Not shown. The contract keeps `ClinicModules.ipdRequestedAt` for later use. There is no list marker or filter for it. | default applied (DEC-023), reversible |

## ADM-3 open questions

| # | Question | Choice | Tag |
|---|---|---|---|
| OQ-3-1 | Extend or start a trial | Trial = now + 30 days. Choosing Trial when the trial has ended starts a new 30 days. A running trial cannot be extended. There is no date picker. (H9, as the mockup draws) Extension terms are not chosen: they would be a commercial rule and need a mockup design first. | default applied (DEC-023), reversible |
| OQ-3-2 | Wording with "Trial · 30 days" | Exactly as the mockup: the state label in lower case in the toast and the refusal. The audit says "IPD set to Trial · 30 days". | default applied (DEC-023), reversible |
| OQ-3-3 | Plan sheet differences | (b) The sheet first selects the plan the clinic does not have now (as the mockup). (d) The button name "Change plan or price" is kept as drawn. These two defaults are applied. **(a) Plan choices Free year · Pro instead of Pro monthly · Pro yearly · Custom, and whether Pro → Free year is allowed; (c) the notice "starts at the next renewal" while phase 1 applies the change at once; (e) no second-admin approval: NEEDS HUMAN (NH-4).** Interim in the contract: FREE and PRO, applied at once, both directions, no approval (ADR-003 H4), marked `x-needs-human`. | (b), (d) default applied (DEC-023), reversible; (a), (c), (e) NEEDS HUMAN |
| OQ-3-4 | What a plan changes | **NEEDS HUMAN (NH-5).** Interim: the plan is a label only. No feature, limit, price or end date depends on it, and `plan_ends_at` is not touched. | NEEDS HUMAN |
| OQ-3-5 | (patient safety) What counts as "admitted" | **NEEDS HUMAN (NH-6).** Interim: `ipd.admission.status = 'ADMITTED'` (the ADR-003 D4 column grant). Emergency cases still waiting and incomplete discharges are not counted. The safer reading would also count waiting emergency cases. That needs a clinician's answer and a new grant on the emergency table. | NEEDS HUMAN |
| OQ-3-6 | (patient safety) Admit and switch-off race | **NEEDS HUMAN (NH-7).** The human must accept the race until the IPD admit transaction reads the entitlement `FOR SHARE`, or block ADM-3a until that change lands. The contract is the same either way. | NEEDS HUMAN |
| OQ-3-7 | Bed limit below existing beds | Allowed. Existing beds stay and new beds are refused. The mockup stepper has no guard. (H10) | default applied (DEC-023), reversible |
| OQ-3-8 | How fast the clinic sees it | On its next request. Nothing is pushed and no one is notified (notifications are not in phase 1). The toast text is as drawn. | default applied (DEC-023), reversible |
| OQ-3-9 | (a) Clinic's own Activity log; (b) clearing the IPD request | (a) No: admin audit only (H11). (b) The request time is kept as history (the mockup never clears it). | default applied (DEC-023), reversible |
| OQ-3-10 | Texts not in the mockup | **NEEDS HUMAN (NH-8)**: the change error text, the ticket message, the text of a refused row, and the activity load error. Interim in the contract: **refused rows are stored but not listed** in the Activity log tab. This matches the mockup, which logs no refusal. The ticket field error carries a placeholder message until the human approves one. | NEEDS HUMAN |
| OQ-3-11 | Date and time in the Activity log | "Today 11:05 AM IST", or "24 Oct 2026, 11:05 AM IST" (the project format). Frontend only. | default applied (DEC-023), reversible |
| OQ-3-12 | Test profile and running trials | `owner-ipd-trial` is off in dev and stays on in test until QA moves its tests (H8). Trials that owners already started keep running to their end. Cancelling them would be a destructive change. | default applied (DEC-023), reversible |
| OQ-3-13 | (clinical, legal) Read-only for 90 days, then hidden | **NEEDS HUMAN (NH-3).** Interim: no timer is built and nothing is hidden. Read-only keeps records readable. The text stays as drawn. | NEEDS HUMAN |
| OQ-3-14 | Owner pill words | The wording built in SAH-19 stays. ADM-3 changes no owner text. | default applied (DEC-023), reversible |

## Contract gaps

| # | Gap | Choice | Tag |
|---|---|---|---|
| G2-1 | No state | None (OQ-2-3). | default applied (DEC-023), reversible |
| G2-2 | Health not in the list or facets | Not added (OQ-2-2). | default applied (DEC-023), reversible |
| G2-3 | IPD facet and parameter not in the mockup | Removed (OQ-2-1). | default applied (DEC-023), reversible |
| G2-4 | Plan text with the free-year day | No field (OQ-2-7). | default applied (DEC-023), reversible |
| G2-5 | Code = slug | As is (OQ-2-4). | default applied (DEC-023), reversible |
| G2-6 | All fields must also match the owner's name | Stated in `ClinicSearch.q`. Every word must be found in the name, code (slug), city or owner name, OR at least 4 typed digits must be inside the owner's mobile. This is the mockup's `odMatch`. | default applied (DEC-023), reversible |
| G2-7 | No "asked for IPD" marker in the list | None (OQ-2-14). | default applied (DEC-023), reversible |
| G3-1 | Trial extension or custom end | None (OQ-3-1). | default applied (DEC-023), reversible |
| G3-2 | Text of a refused row in the activity list | Refused rows are not listed until NH-8 is answered. | NEEDS HUMAN (NH-8) |
| G3-3 | `ActivityEntry` has no clinic name or code | The screen uses the clinic it is already showing. No field is added. | default applied (DEC-023), reversible |
| G3-4 | Plan choices FREE/PRO versus the mockup | NH-4. | NEEDS HUMAN |
| G3-5 | Fixed reason "Module entitlement" | Kept, as the mockup `aLog` writes it. | default applied (DEC-023), reversible |

## NEEDS HUMAN (not decided; the contract carries the interim marked `x-needs-human`)

| # | Item | Why it is a hard stop | Interim in the contract | Blocks |
|---|---|---|---|---|
| NH-1 | Health rule (OQ-2-8) | Different from the mockup rule | ADR-003 rev 2 derivation, no "Payment due" | Nothing structural. The health pill text may change. |
| NH-2 | Admin directory texts not in the mockup (OQ-2-10) | Not in the mockup | None. These are frontend texts. | The frontend error, retry and not-found states |
| NH-3 | "Read-only for 90 days, then hidden" (OQ-2-13 b, OQ-3-13) | Clinical and legal record-keeping rule | No timer, nothing hidden, text as drawn | Go-live: the text promises a rule the product does not keep |
| NH-4 | Plan choices, Pro → Free year, the "next renewal" notice, second-admin approval (OQ-3-3 a, c, e; G3-4) | Plan names, billing timing and an approval control are commercial and security rules, and they differ from the mockup | FREE/PRO, at once, both directions, no approval | Building ADM-3b |
| NH-5 | What a plan changes, and the free-year end date (OQ-3-4, OQ-2-7) | Pricing rule | Label only. `plan_ends_at` is not touched. | Building ADM-3b |
| NH-6 | (patient safety) What counts as "admitted" (OQ-3-5) | Clinical rule | `ipd.admission.status = 'ADMITTED'` | Go-live of ADM-3a (a clinician signs off) |
| NH-7 | (patient safety) Admit and switch-off race (OQ-3-6) | Clinical risk acceptance | Row lock in the admin transaction. The IPD `FOR SHARE` read is requested from the IPD developers. | ADM-3a go-live, or its build if the human chooses "block" |
| NH-8 | Change error text, ticket message, refused-row text, activity load error (OQ-3-10, G3-2) | Not in the mockup | Refused rows are not listed. The ticket error message is a placeholder. | The frontend error states. Listing refused rows. |

## Human answer
(none yet; the defaults above are applied under DEC-023)
