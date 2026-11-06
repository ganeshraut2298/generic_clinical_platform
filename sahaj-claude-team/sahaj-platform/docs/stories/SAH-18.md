# SAH-18 · Alerts and escalation rules
Mockup screen: Clinic setup → Alerts and escalation (`cfgRules`, key `rules`)
Module: opd

## 1. Story
As the clinic owner, I want to write simple "if this happens, then do that" rules for the patient flow, so that delays, unpaid bills and missed follow-ups are caught the way my clinic wants.

Scope note (honest): in this phase the page stores and manages the rules. The engine that checks them at every step change and raises alerts is NOT built yet. The page says so: "Rules are saved now. Automatic checking starts in a later release." The mockup's "Ran N times this week" line is therefore not shown.

## Acceptance criteria
1. Given the owner opens Clinic setup → Alerts and escalation, then two panels show: **Your rules** (with "N on" pill) and **Suggested for Indian OPDs**. Non-owners see it read-only (no delete, switch, add).
2. Given a clinic that never saved rules, then it sees the five starter rules of the mockup: (1) Vitals · not done in 10 min → Alert nurse (on); (2) Case paper print · bill not paid → Block print (on); (3) Discount · above role limit → Needs owner approval (on); (4) Follow-up · missed by 2 days → Send WhatsApp reminder (on); (5) Consultation · waiting over 30 min → Alert owner (off). Reading does not write; the first change saves the starter rules plus that change.
3. Given the rule builder, then "If" offers Token, Vitals, Consultation, Billing, Case paper print, Discount, Follow-up; "Is" offers: not done in 10 min, waiting over 30 min, waiting over 45 min, bill not paid, above role limit, missed by 2 days, unpaid at day closing; "Then" offers: Alert nurse, Alert owner, Flag red to the doctor, Block print, Needs owner approval, Send WhatsApp reminder, Move up in the queue. Add saves a new rule, switched on, with toast "Rule added and switched on". An identical rule (same if, is, then) is refused with 409 "That rule already exists."
4. Given a rule, the owner can switch it on or off and delete it.
5. Given the Suggested panel, then the five suggestions of the mockup are listed (Vitals BP 160/100 or above, or SpO2 below 94% → Flag red to the doctor; Token patient is 75+ or pregnant → Move up in the queue; Consultation waiting over 45 min → Send WhatsApp "doctor delayed" message; Billing unpaid at day closing → Alert owner; Follow-up after the free window → Charge full fee, tell front desk) each with its reason; one already added (same if and is) is hidden; when all are added it shows "All suggested rules are added." Add creates the rule, on. Suggestion conditions and actions may be outside the builder lists; that is allowed only through suggestions.
6. Given any add, switch or delete, then it is audited (before and after).
7. Only OWNER may write (403 otherwise); all clinic roles may read. Tenant isolation (RLS) applies.

## 3. Technical design
- `opd.alert_rule`: `id`, `tenant_id`, `when_step text`, `cond text`, `then_action text`, `enabled boolean`, `created_at`, `created_by`. Unique `(tenant_id, when_step, cond, then_action)`. FORCE RLS. Text columns <= 120 chars. The API validates against the builder lists or the suggestions list, never arbitrary text.
- Starter rules are materialised on the first write when the tenant has no rows and no marker (`opd.alert_rule_state(tenant_id pk, seeded_at)`), so deleting all rules does not bring the starter rules back.
- Suggestions are a static list in code; GET returns only those the clinic has not added.

## 4. API (`/api/v1/opd`, contract `docs/api/opd.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/alert-rules` | any clinic role | none | `AlertRules` {rules, suggestions, options} | 401 |
| POST | `/alert-rules` | OWNER | `AlertRuleInput` | `AlertRules` | 400, 403, 409 |
| POST | `/alert-rules/suggestions/{index}` | OWNER | none | `AlertRules` | 403, 404, 409 |
| PUT | `/alert-rules/{id}` | OWNER | `{enabled}` | `AlertRules` | 400, 403, 404 |
| POST | `/alert-rules/starter` | OWNER | none | `AlertRules` (saves starter rules once, idempotent) | 403 |
| DELETE | `/alert-rules/{id}` | OWNER | none | `AlertRules` | 403, 404 |

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); service, controller, audit; unit tests. Real-DB tests by qa-engineer.
### Tasks — Frontend
Page for key `rules` (`built: true`), per mockup; read-only for non-owners; unit tests (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: the rule engine (checking, alerts on Today and task lists, blocking print, WhatsApp), "Ran N times" counts, free-text rules, editing a rule's text.
Open questions: none blocking; proceeding per DEC-008.

## 7. Status log
