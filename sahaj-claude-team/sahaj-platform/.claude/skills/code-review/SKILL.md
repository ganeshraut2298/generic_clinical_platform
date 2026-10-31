---
name: code-review
description: Review a Sahaj story's diff against its story, API contract, database design and project rules, and produce a ranked findings report. Use for code and security review before human approval.
---

# Code review

## Inputs
`git diff main`, `docs/stories/<ID>.md`, `docs/api/<module>.yaml`, `docs/database/<ID>.md`, `docs/test-plans/<ID>.md`, the QA result.

## Checklist
1. **Does it do the story?** Each acceptance criterion maps to code and a passing test.
2. **Contract:** endpoints, fields, status codes and errors match the OpenAPI file exactly.
3. **Boundaries:** no module reads another module's tables or internal classes; no new phase-2 services.
4. **Tenant and role:** server-side checks on every endpoint; RLS on new tables; ids validated against the caller's tenant.
5. **Data:** migrations forward-only and named correctly; no destructive change without expand/contract.
6. **Errors and logging:** problem-details responses; no patient data in logs, URLs or error text.
7. **Tests:** meaningful assertions, negative and tenant cases present, nothing skipped or disabled.
8. **Readability:** clear names, small methods, no dead code, consistent with `.claude/rules/`.
9. **Mockup match:** layout, labels, field order, states and role differences match the mockup screen; colours come from tokens.
10. **Front end:** OnPush, typed forms, accessible labels, loading/empty/error states, no data in storage.

## Report format
```
Verdict: APPROVE | APPROVE WITH COMMENTS | CHANGES REQUIRED   (security: PASS | FAIL)
| # | Severity | File:line | Finding | Fix |
```
Severity: Blocker/Critical, Major/High, Minor/Medium, Nit/Low. Any Blocker, Major, Critical or High → changes required.
