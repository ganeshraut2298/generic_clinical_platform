---
name: requirement-analysis
description: Turn a raw Sahaj requirement into a requirement note and INVEST user stories with Given/When/Then acceptance criteria. Use when a new feature, change request or bug report arrives.
---

# Requirement analysis

Output two files for story ID `<ID>` (format `SAH-123`):

## 1. `docs/requirements/<ID>.md`

```markdown
# <ID> · <title>
Module: platform | patient | opd | ipd | hra | billing
Mockup screen: <name from docs/design/screen-map.md, or 'not in mockup — needs design'>
Source: <who asked, date>
## Problem
## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
## In scope / Out of scope
## Business rules (numbered)
## Patient-safety and privacy points
## Plan or entitlement (which plan/module unlocks it)
## Open questions for the human
```

## 2. `docs/stories/<ID>.md`

One file per requirement, 1–6 stories. Each story:

```markdown
### <ID>-<n> · As a <role>, I want <goal>, so that <benefit>
Priority: Must | Should | Could
## Acceptance criteria
- Given <context>, when <action>, then <result>.
- (include: wrong role, other tenant, invalid input, empty state, offline/slow network where relevant)
## Notes for design
```

## Checks before handoff
- Every field, action, state and role difference on the mockup screen is covered by a story, or listed as out of scope.
- Every rule in the requirement maps to at least one acceptance criterion.
- Each story fits in one sprint; split if not.
- Roles use Sahaj names: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist.
- Clinical, legal or pricing rules you are unsure about are open questions, not assumptions.
- Run `node scripts/validate.mjs docs`.
