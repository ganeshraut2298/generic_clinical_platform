# DEC-072 · SAH-79 and SAH-80: go-ahead on recommended answers, IPD and billing held back

Date: 18 Nov 2026 · Programme: DEC-061 · Answered by: the human (chat, 18 Nov 2026, evening)
Status: ANSWERED.

## Context
The human asked for end-to-end development of Pharmacy & Inventory on the same branch (`feature/SAH-39-home-kinetic-background`), with no merge step, using multiple agents. SAH-78 packages A and B were in QA and review. SAH-79 had 16 open questions, 15 of them billing, GST, legal or clinical rules. SAH-80 needed stories, and part of it changes built IPD behaviour and billing.

## Questions and answers
1. **SAH-79 open questions.**
   - Answer: **build on the recommended answers** of docs/requirements/SAH-79.md, as DEC-062 did for SAH-78.
   - This answer is also the human's approval of the SAH-79 stories (chat sign-off, as DEC-064; hooks run merge-only, DEC-001).
   - Every billing, GST, legal and pharmacy-practice rule stays a go-live check for an accountant, a pharmacist and a lawyer (G-13, G-14 and the SAH-79 go-live list). Nothing here is cleared for real billing or a real Schedule X register.
2. **SAH-80 touch points on built behaviour** (the IPD running-bill charge and nursing chart for patient-wise consumption, OQ-79-23; pharmacy sales posting into the billing module).
   - Answer: **build the pharmacy side first.** The IPD charge and the billing posting sit behind a small port with a no-op implementation. Each change to IPD or billing code is listed for the human and reviewed by the IPD session before anyone edits it.

## What this means for the agents
- SAH-79 starts: architect (contract) → db-engineer → java ∥ angular → QA → reviews, in the work packages WP-D to WP-H of the stories.
- SAH-80: the business analyst writes the stories first. Every touch point and every billing, tax, legal or clinical rule is an OPEN question with a recommended answer, and the human is asked once the list is ready.
- Standing rules unchanged:
  - no merge, no commit or push by agents;
  - no edit of human-owned files (CLAUDE.md, .claude/, scripts/, docs/design/, docs/approvals.md, .gitignore, .env);
  - no new library, tenancy or auth change without a decision;
  - no destructive database change;
  - a Critical or High security finding stops the story.
- Everything goes on this branch. The human merges when ready; there is no per-package merge ask.

## Blocked
Nothing.
