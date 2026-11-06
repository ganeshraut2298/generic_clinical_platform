# Code review · SAH-14 to SAH-18 (follow-ups, today dashboard, revenue, privacy and consent, alert rules)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Verdict: **changes required**, no Blocker, 2 Majors.
Passed: module boundaries (no opd→billing import), RLS on all new tables, append-only consent and record_access, roles match stories, SQL text-block concatenation safe, IST date handling, shared BillMath, real-database ITs for every story.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| 1 | Major | JdbcFollowUpRepository COUNTS, TodaySummaryService, today.page.html | "Follow-ups due" tile adds all-time returned count, grows forever | Count "returned today" only; IT with a follow-up returned yesterday |
| 2 | Major | FollowUpService.list, findByStatus | No limit; N+1 patient/doctor lookups; Returned tab grows | Cap (e.g. 200, newest first) with UI note; bulk lookups |
| 3 | Minor | RevenueService | Dues only for visits in range; AC6 reads "all closed bills" | Human to confirm (open decision) |
| 4 | Minor | RevenueService.refundDetail | N+1 displayName | cache |
| 5 | Minor | RevenueService.report | unbounded `in (:ids)` could exceed 32767 params | chunk |
| 6 | Minor | TodaySummaryService | findSince run twice | share rows |
| 7 | Minor | AlertRulesService.materialiseStarter | redundant isSeeded | drop |
| 8 | Minor | AlertRulesService.addSuggestion | when+cond vs unique when+cond+then | comment |
| 9 | Minor | JdbcFollowUpRepository RETURNED | `cast(:today as date) is not null` hack | build SQL per status |
| 10 | Minor | followups.page.html | tablist without full tab pattern | proper pattern |
| 11 | Minor | rules.page.html | every switch labelled "Rule on or off" | rule-specific label |
| 12 | Minor | patient-record, data, rules, today pages | `.subscribe()` without takeUntilDestroyed | add |
| 13 | Minor | patient-record consentText | splits formatted string on ',' | date helper |
| 14 | Minor | today.page.ts | collected money visible to all roles | Human decision |
| 15-18 | Nit | duplicated WHATSAPP text, two date formatters, doctorShortName shapes, long constructor line | tidy |
| 19 | Nit | PatientService.open | every GET of a patient logs access; refetches pollute the owner's list | verify frontend does not refetch after edit/consent |

Not checked: builds, pixel match to mockup, all specs, YAML line by line, registration dialog consent boxes, data page panels.
