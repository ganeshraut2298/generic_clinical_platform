# SAH-89 · Onboarding, header mobile search, doctor fee, owner as doctor

Branch: work started on feature/SAH-39-home-kinetic-background (a full mvn verify was running; move to feature/SAH-89-onboarding after that run if the human wants).

## 22 Nov — business-analyst
docs/requirements/SAH-89.md (31 rules, 11 open questions) and docs/stories/SAH-89.md (stories 89-1..89-9, order 89-2, 89-1, 89-3, 89-6, 89-4, 89-5, 89-7, 89-8, 89-9). validate docs: PASSED.
Escalations in DEC-096; human chose every recommended option (typed address + map link, logo in DB, Second checks moved into Queue, owner-as-doctor and fee rules, BA defaults elsewhere).
Next: human approves `stories`; then solution-architect (contract additions: structured address, doctor fee on Add person, logo endpoint, colour in setup, patient lookup by mobile without number in URL, server refuses clinic APIs before setup completes; owner doctor functions; Second checks in Queue).

## 22 Nov — stories approved in chat; architect launched
Human approved SAH-89 stories in chat (line not yet in docs/approvals.md). Architect launched: ADR if needed (logo storage, owner-as-doctor), contract additions per story, db/java/angular task list.

## 22 Nov — solution-architect (PROPOSED until contract approval)
ADR-031 (D1-D11: setup guard 403 setup-required, six idempotent completion steps, signup token, typed address + Maps link, logo in bytea re-encoded to PNG and SVG dropped, colour, owner `sees_patients` + TeamMemberPort.actsAsDoctor, fee 0-50,000 with plan doctor limit, header search POST body, second-checks count, validation).
Contracts: platform.yaml 0.3.0, patient.yaml 0.3.0 (POST /patients/lookup-by-mobile), opd.yaml 0.8.0 (chemo-checks/count, registration-number-missing), billing.yaml 0.4.2 (description only). All six contracts parse (SnakeYAML, tech lead). validate docs: PASSED.
DB task list: platform.clinic_logo, platform.setup_completion_step, setup_progress.completion_run_started_at, clinic_profile.state, doctor_profile.sees_patients (+2 timestamps, fee <= 5,000,000 paise NOT VALID then VALIDATE), otp_challenge signup token columns, audit actions, check patient (tenant_id, mobile) index.
Open for human: registration-number gate for all doctors?; visiting doctors count in plan limit?; COUNSELLOR Patients menu; fees visible to all roles on GET /team. Branch: SAH-89 still on the SAH-39 branch.
