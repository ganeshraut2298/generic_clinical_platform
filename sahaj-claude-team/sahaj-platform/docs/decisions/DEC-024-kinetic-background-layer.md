# DEC-024 · "Kinetic micro-texture" background layer (new request, not in the mockup)

Status: DECIDED (human, 7 Nov 2026): option 1, mockup first, then a story.

## Context
The human pasted a full spec for an ambient animated background: a fixed `<canvas>` dot grid behind the whole app that splits dots into four coloured micro-nodes near the cursor (90 px radius, damped spring, friction 0.75), new theme tokens (`--bg-base`, `--dot-base`, `--node-a..d`), `.surface-solid` / `.surface-glass` (frosted glass, `backdrop-filter: blur(16px)`) applied to existing panels, `data-surface` on all tables, forms, vitals, cards, dialogs and workspace panels, `data-dot-exclude` on sidebar/header/toolbars, a radial `mask-image`, and a wrapper with `z-index: 10; isolation: isolate` around all app chrome and routed content.

## Why this stops
- **Not in the mockup (CLAUDE.md rule 1).** `docs/design/mockup/sahaj-mockup.html` has a flat background and solid panels; there is no animated layer, no frosted glass, no new colour tokens. Rule 1 says anything not in the mockup, or different from it, goes to a human. The mockup is human-owned (`docs/design/`).
- **Workflow (rule 2).** It needs a story (`stories` approval) before any build; there is no story, screen-map entry or approval line.
- **Size.** It touches the app shell, the global theme and the background of every panel, table, form, card and dialog in all modules (OPD, IPD, billing, HRA, team). The spec says "confined to the shell, theme and the new component", but applying `.surface-*` and `data-surface` "to existing panels" reaches into every feature folder. Likely more than one story.
- **Clinical risk.** Motion and colour behind clinical data: red "alert" nodes (`--node-b`) can be mistaken for clinical alert colour; frosted glass over a moving canvas can lower contrast (WCAG 1.4.3/1.4.11); `backdrop-filter` and a full-screen canvas cost GPU on low-end ward PCs; `isolation`/`z-index` wrapping can change how existing Material overlays, dialogs, snackbars and the sticky sheet footers stack (several e2e layout tests assert these).

## Options
1. **Mockup first, then a story (recommended).** The human adds the layer to the mockup (or writes "approved deviation" in the screen map) and decides the colour of the "alert" node; the business-analyst writes SAH-39 "Ambient background layer" with the spec as acceptance criteria plus guardrails (off by default behind a clinic setting or env flag; no red node; contrast checks; reduced motion; touch off); normal pipeline (architect: shell/stacking ADR; angular-developer; QA incl. the existing layout specs and an axe/contrast check). Pros: follows the rules, safe for clinicians. Cons: needs your mockup work first.
2. **Prototype behind a flag, off by default, shell only.** Build only the component, tokens and shell wiring, with `enabled = false` by default and no change to existing panels; you look at it locally and then decide. Pros: quick to see. Cons: still a mockup deviation without a story; you must accept that explicitly.
3. **Park it.** Add it to the backlog as a later visual-polish item after go-live hardening. Pros: no risk to clinical screens. Cons: no visual change now.

## Recommendation
Option 1. If you want to see it first, option 2 is acceptable only with your explicit go-ahead (it is a design deviation, so DEC-023's "take the recommended default" does not apply).

## What is blocked
Only this request. SAH-35/36/38 are not affected (their final backend gate is running).

## Human answer
Option 1 "Mockup first, then story" (7 Nov 2026). Next: the human updates docs/design/mockup (or marks an approved deviation in docs/design/screen-map.md) and decides the alert-node colour; then the tech lead opens SAH-39 on its own branch and starts the business-analyst. Nothing is built before that.

## Human follow-up (7 Nov 2026, in chat): "only do this change for homepage / entry page"
The human narrowed the request to the public homepage (`frontend/src/app/features/home`, mockup section `h-hero` "Clinic software that works the way your OPD already runs.", the page before sign-in). Tech lead reading: this is the human's instruction to accept the background on the homepage only, as a recorded deviation from the mockup (no edit to docs/design is needed for that; the human may still add it to the mockup later). Clinical screens are NOT changed: no `.surface-*` on panels, no wrapper around the signed-in app, no `data-surface` on clinical tables.
Story: SAH-39 "Homepage ambient background". Branch: `feature/SAH-39-home-kinetic-background`, created from the current branch `feature/SAH-13-appointments-workspace` (main does not contain the homepage yet) once the running backend gate finishes. Still needs the human's `stories` line for SAH-39 in docs/approvals.md before the build. Open for the human: the "alert" red node colour on a marketing page (BA proposes keeping the spec colours since no clinical data is shown; human confirms in the story approval).

## Human go-ahead (7 Nov 2026, in chat): "go ahead for the development"
Recorded as the human's go-ahead to run SAH-39 through the pipeline (BA → architect → angular-developer → QA → reviews). This is NOT an approval line: docs/approvals.md still has no `stories` / `contract` / `merge` line for SAH-39 and only the human can add them (`| SAH-39 | stories | APPROVED | <name> | 2026-11-07 |`, later `contract`, `merge`). Open questions in the story go to the recommended default (DEC-023) unless the human answers.

Human, 7 Nov 2026, in chat: "APPROVED and go ahead for development" (approval of the SAH-39 stories and the build). The matching line in docs/approvals.md must still be added by the human.

Human, 8 Nov 2026, in chat: "go ahead and approved for further development". Recorded as the go-ahead to continue SAH-39 and the next work. It is still NOT a line in docs/approvals.md; the human adds those.
