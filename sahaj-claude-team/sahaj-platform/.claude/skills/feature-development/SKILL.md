---
name: feature-development
description: Implement a Sahaj story in Spring Boot or Angular strictly from its API contract and database design, with tests and the validation gate. Use when a developer agent starts a story.
---

# Feature development

## Before writing code
1. Open the mockup screen named in the story (`docs/design/mockup/sahaj-mockup.html`). Read `docs/stories/<ID>.md`, `docs/api/<module>.yaml`, `docs/database/<ID>.md` and any ADR named in the handoff.
2. Confirm you are on `feature/<ID>-<slug>`. If not, ask the tech lead; do not create branches from a dirty tree.
3. List the files you will create or change. All of them must be in your area (see `CLAUDE.md`).

## Build in this order
- **Backend:** DTO records from the contract → domain/entity → repository → application service (rules, transaction, audit event) → controller (`@Valid`, `@PreAuthorize`) → exception mapping. Tests alongside each step.
- **Frontend:** typed models from the contract → `*.api.ts` service (with a contract-shaped mock until the backend is ready) → feature route (lazy) → container component → presentational components → form validation → empty, loading and error states. Tests for service, component logic and guards.

## Definition of done
- Every acceptance criterion is covered by code and at least one test.
- No contract drift: field names, types, status codes and errors match `docs/api/`.
- Role and tenant checks done on the server (backend) or reflected in the UI (frontend).
- No TODOs without a story ID, no commented-out code, no console/System.out logging.
- `node scripts/validate.mjs backend` or `node scripts/validate.mjs frontend` PASSED, output pasted in the handoff.

## If blocked
Contract wrong → hand back to solution-architect. Schema missing → db-engineer. Requirement unclear → business-analyst via the tech lead. Never work around it in someone else's files.
