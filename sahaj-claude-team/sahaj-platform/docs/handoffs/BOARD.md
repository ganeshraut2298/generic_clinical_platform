# Story board (tech-lead keeps this current)

All Phase 1 work is on `feature/SAH-2-home-page`. Approvals come as commands in chat, not from `docs/approvals.md` (DEC-006). Three-agent mode (DEC-007): tech-lead, dev (java-developer/angular-developer), test (qa-engineer, unit tests only). Goal: finish development for a client showcase.

| Story | Title | State | Owner | Branch | Blocker |
|---|---|---|---|---|---|
| SAH-1 | Project foundation | In development (approved in chat, DEC-006) | db-engineer, java-developer, then qa-engineer | shared checkout on `feature/SAH-2-home-page` (DEC-006) | DEC-003 clean-up |
| SAH-2 | Home page | Built, in QA | qa-engineer | `feature/SAH-2-home-page` (from scaffold 9dc8f04) | DB passwords for integration tests; Playwright smoke; DEC-002 edit; screen-map row |
| SAH-3 | Sign up and log in with mobile one-time code | Brief ready | backend-dev, frontend-dev | not created | SAH-1 merge; screen-map row |
| SAH-4 | Clinic setup wizard | Brief ready | backend-dev, frontend-dev | not created | SAH-3; screen-map row |
| SAH-5 | App shell | Brief ready | frontend-dev, backend-dev | not created | SAH-3 |
| SAH-6 | Team and access (user management) | Brief ready | backend-dev, frontend-dev | not created | SAH-3, SAH-5 |
| SAH-7 | Clinic masters | Brief ready | backend-dev, frontend-dev | not created | SAH-5, SAH-6 |
