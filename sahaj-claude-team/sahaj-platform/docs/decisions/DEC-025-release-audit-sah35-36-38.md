# DEC-025 · Release audit ("audit, fix, prove") for SAH-35, SAH-36, SAH-38

Status: DECIDED (human, 8 Nov 2026, AskUserQuestion).

## Context
The human pasted a "Senior Technical Lead + QA Lead" audit template (phases 0-5: discover and validation matrix; validation in FE/BE/DB; UI at 1920/1366/1024/768/375 px and 125% zoom; unit, integration and e2e tests; regression and a QA_REPORT with READY / NOT READY). The template had placeholders and asked for agent commits and Testcontainers.

## Decisions
1. Story under review: the SAH-35, SAH-36, SAH-38 bundle (Medicine round on the Safety board; Admitted patient cards; colleague PIN hardening).
2. Project rules stay: agents fix and test in their own folders; nobody but the human commits; the tech lead proposes commit messages (`fix(validation): …`, `fix(ui): …`, `test(unit): …`, `test(integration): …`, `docs(qa): …`); no Testcontainers (the real `sahaj_test` database stays); no new library without the human.
3. Template points mapped to existing rules: "mockup and acceptance criteria conflict → stop and list" = CLAUDE.md rule 1; "never weaken a test", "never edit an applied migration" already apply; the report goes to `docs/reviews/QA_REPORT_SAH-35-36-38.md` (tech lead's folder). Error body: the project already uses RFC 9457 problem details (`type`, `title`, `detail`, field errors) in the approved contracts; the template's `{timestamp, status, errorCode, …}` shape is NOT adopted (it would change approved contracts); the audit checks that every error is consistent and leaks nothing.

## Plan
Phase 0 + gap list (code-reviewer, read-only) → Phase 1/2 fixes (java-developer, angular-developer; db-engineer for any constraint) → Phase 3/4 tests (developers' unit tests, qa-engineer ITs and e2e at the five widths and 125% zoom) → Phase 5 (one `validate backend`, frontend lint/test/build, whole e2e folder) → QA_REPORT with READY / NOT READY.

## Result (8 Nov 2026)
The audit is complete; see docs/reviews/QA_REPORT_SAH-35-36-38.md. It is technically green, with the verdict NOT READY until the human adds the approval lines and makes the listed decisions. Human, 8 Nov 2026, in chat: "go ahead and approved for further development". Recorded as the go-ahead to continue; it is not an approvals.md line.
