# DEC-001 · Relax the approval gates to merge-only

Date: 31 Oct 2026
Raised by: tech-lead
Decided by: Ganesh (human)
Status: DECIDED — awaiting the human's config edits to take effect
Affects: every story from SAH-1 onward

## Context

`CLAUDE.md` rule 2 defines three human approval gates per story in `docs/approvals.md`:
`stories`, `contract` and `merge`. `.claude/hooks/guard-ownership.mjs` enforces the first two by
blocking agents whose earlier stage is not approved.

Across the 32-story playbook that is roughly 96 approval stops. During SAH-1 this cost four
round trips before any code was written, and the human asked to "sit back and see the changes"
instead of approving at every stage.

The human first asked the tech-lead to write the approvals itself. The tech-lead declined: filling
in `docs/approvals.md` would be self-approval, it is marked humans-only in `CLAUDE.md`'s ownership
table, and the tech-lead's standing instruction forbids editing it by any means. A pre-filled file
for the human to paste was also declined, because approving a `contract` for an ADR that does not
exist yet signs off on work nobody has seen.

## Options

**A · Batch per story.** Keep all three gates; the human adds `stories` and `contract` together at
the start of each story. Two stops per story, no config change, stories still reviewed before code.

**B · Merge-only gate.** Drop `stories` and `contract`; keep `merge`. One stop per story.
- Pros: the human is interrupted once per story. `merge` is already theirs — agents are barred from
  pushing and merging, so that gate costs nothing extra. Suits a greenfield phase with no real
  patient data (`CLAUDE.md` forbids real patient data anywhere).
- Cons: nothing is reviewed before code is built on it. A wrong requirement or a wrong contract is
  discovered at code review, after the db-engineer and both developers have built on it, so rework
  lands on three agents instead of one. The tech-lead becomes the only check between the analyst's
  output and the developers.

**C · Leave as-is.** Maximum oversight, ~96 stops.

## Decision

**Option B — merge-only.** The human chose this on 31 Oct 2026.

Mitigations the tech-lead will apply, since the pre-code gates are gone:
- Review each agent's handoff against the story and the mockup before starting the next stage, and
  send it back to its owner when it does not match, rather than passing problems downstream.
- Keep escalating per `.claude/agents/tech-lead.md`: anything not in the mockup, clinical, legal,
  billing or pricing ambiguity, architecture changes, destructive database changes, Critical/High
  security findings, or a stage failing validation twice.
- Report the requirement and contract to the human as they are produced, so they can object even
  though no gate forces them to.

## What the human must change for this to take effect

The tech-lead cannot make these edits: `.claude/` and `CLAUDE.md` are humans-only.

1. `.claude/hooks/guard-ownership.mjs` line 39 — replace the `NEED` map with an empty object:
   `const NEED = {}; // DEC-001: merge-only gate, 31 Oct 2026`
   This is the only functionally required change. The branch check, the ownership check and the
   area locks all stay intact.
2. `CLAUDE.md` line 9 — drop "Hooks block an agent whose earlier stage is not approved".
3. `CLAUDE.md` lines 40-41 — remove "**Human approves `stories`**" and "**Human approves `contract`**".
   Leave line 46 ("**Human approves `merge`**") exactly as it is.
4. The tech-lead's own session instructions still say to stop for `stories` and `contract`.
   The human's in-session instruction overrides that for this session; update the saved prompt to
   make it stick.

## What is blocked until then

SAH-1 stage 3. The hook still reads the old `NEED` map, so solution-architect is blocked until
either line 39 is changed or `| SAH-1 | stories | APPROVED | ... |` is added to `docs/approvals.md`.
`docs/approvals.md` currently contains no approval rows at all.
