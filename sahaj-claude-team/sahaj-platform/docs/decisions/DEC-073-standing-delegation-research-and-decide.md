# DEC-073 · Standing delegation: research the open questions and decide

Date: 18 Nov 2026 · Programme: DEC-061 (SAH-78, SAH-79, SAH-80) · Given by: the human (chat, 18 Nov 2026, evening)
Status: IN FORCE until the human withdraws it.

## The instruction
"If any open question or functionality do R&D and take [the decision]." The human wants end-to-end development on this branch without waiting for answers.

## How the tech lead applies it
1. **Open question or missing functionality:** research it (the BRD, the mockups, ADR-028, the existing code and Indian pharmacy practice), choose the option that best fits the mockup, the stories and the law as the tech lead understands it, and record it in a DEC file or the story's open-question list with:
   - the options considered;
   - the reason for the choice;
   - the source it rests on.
2. **Billing, tax (GST), legal, clinical and Schedule X rules** are decided the same way, but each one is also written as a **go-live check** for an accountant, a pharmacist or a lawyer, because the tech lead is not one of them. No decision of this kind is marked "cleared for real use".
3. **Agents still cannot decide for themselves.** They list the open question in their handoff. The tech lead decides, records it and sends it back.
4. **IPD and billing code that is already built** (DEC-072 Q2): still additive only, behind a port, and the IPD session reviews before anyone edits those files.

## What this does NOT change
- No commit, push or merge by agents.
- No edit of human-owned files: CLAUDE.md, .claude/, scripts/, docs/design/, docs/approvals.md, .gitignore, .env.
- No deleting of lock files; no real patient data; no secrets in code.
- A **Critical or High** security finding, and a **destructive** database change (drop, rename, rewrite of existing rows), still stop work and come back to the human.
- A new library, module, or a change to tenancy or auth is decided and recorded with its reasons, not done silently. If the tech lead is not sure of the safety, it waits for the human.

## Blocked
Nothing.
