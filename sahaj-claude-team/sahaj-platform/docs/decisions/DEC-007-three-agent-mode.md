# DEC-007 · Three-agent mode and the login lookup default

Date: 1 Nov 2026. Decided by: Ganesh (pasted rule set, "STRICT PROJECT DEVELOPMENT RULES – 3 AGENT TEAM").

## Mapping (tool constraint)
The Agent tool only offers fixed types: business-analyst, solution-architect, db-engineer, java-developer, angular-developer, qa-engineer, code-reviewer, security-reviewer. There is no single "full-stack developer" type, so:
- **Tech Lead** = this session.
- **Full-Stack Developer** = java-developer (backend/DB-adjacent work) and angular-developer (frontend), run in parallel when the work splits cleanly; db-engineer still writes migrations (ownership hook blocks anyone else from that folder).
- **Test Lead** = qa-engineer, **unit tests only** from now on. No new e2e or security-reviewer work is started unless the human asks; the SAH-1/2 code and security reviews already done stand as-is and their fixes continue to completion.

## Approvals
No more per-story or per-file approval requests. Stories already written (SAH-1 to SAH-7) are assigned directly. Technical defaults are used without asking; only genuine ambiguity, conflict, a destructive DB change, or being truly blocked gets raised, briefly.

## Login lookup default (was pending a "go")
Adopted now under the above: a narrow RLS policy lets a verified user read only their own `membership` rows by `user_id`, bound from `app.user_id` after OTP success and before a tenant is chosen. No general cross-tenant read is added. db-engineer implements this in SAH-3's migrations.

## Still open, not blocking local development
- DEC-003: password in git history. Do not push any branch until rotated and history is cleaned.
- Database credentials not set locally: integration tests still can't run against `sahaj_test`.
