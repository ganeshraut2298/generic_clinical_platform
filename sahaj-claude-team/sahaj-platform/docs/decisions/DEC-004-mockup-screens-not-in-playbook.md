# DEC-004 · The mockup now shows screens the screen map and playbook do not cover

Date: 31 Oct 2026
Raised by: tech-lead
Status: **NEEDS DECISION**
Blocks: the order of work after SAH-1. Does not block SAH-1 itself.

## Context

The human supplied the full mockup as the key reference "from start to end" and pasted three new
screen-map rows. Verified by the tech-lead:

| Check | Result |
|---|---|
| `docs/design/mockup/sahaj-mockup.html` matches the supplied mockup | **Yes** — 1,131,000 bytes, saved 21:20 today. Every distinctive marker present (`sfMar`, `pcDefault`, `orgDirPage`, `SEED_RAVET`, `ipdSeedHospital`, `axSeedMenu`, `bimpCheck`). |
| `docs/design/screen-map.md` contains the three new rows | **No** — 0 of 3 |
| Screen-map rows today | 28 screens |

`scripts/validate.mjs` checks every requirement and story's `Mockup screen:` line against
`docs/design/screen-map.md`. Until the three rows are added, any story naming them **fails
`validate docs`**, and `CLAUDE.md` rule 1 blocks work on a screen that is not in the map.
`docs/design/` is humans-only, so the tech-lead cannot add them.

## Gap 1 · Three phase-1 screens have no story in the playbook

These are the **first three screens a new clinic ever sees**, and they come before the app shell:

| Mockup screen | What it contains | Playbook story |
|---|---|---|
| Home page (landing: features, pricing, compare, Start free) | Public marketing site: animated hero, how-it-works, workflow builder demo, roles, patient journey, specialties, a cost/feature comparison calculator, 4-tier pricing, trust section, FAQ | **none** |
| Sign up and log in (mobile one-time code) | New clinic / Log in toggle, mobile + 6-digit OTP, no passwords | SAH-3 covers "Sign-in and session" but the mockup's flow is OTP-first, not the demo role switcher the screen map describes |
| Setup wizard (5 steps: clinic, specialty, staff, OPD, look and web address) | Runs once after sign-up: clinic details, specialty template, doctors and staff, OPD questions, brand colour and `<slug>.sahaj.in` web address, then a "creating your clinic" progress screen | **none** |

The playbook's 32 stories start at the scaffold and go straight to the app shell (SAH-4). Nothing
covers the public site, the OTP sign-up, or the onboarding wizard.

## Gap 2 · CORRECTED · "HRA" means two different things

**The tech-lead's first reading of this was wrong and is retracted.** The initial note said "the
mockup now has HRA", which conflated two unrelated modules. Verified counts in the mockup:

| Searched in the mockup | Hits |
|---|---|
| `Health Risk Assessment` | **0** |
| `Health Risk` | **0** |
| `questionnaire` | **0** |
| `risk score` | **0** |
| `Staff, timings and leave (HRA)` | 3 |

So:
- `CLAUDE.md` defines **HRA = Health Risk Assessment**, a clinical module with its own package
  `com.sahaj.hra` and PostgreSQL schema `hra`.
- The mockup uses **HRA to label "Staff, timings and leave"** — shifts, leave approval, attendance
  and holidays. Human resources, not clinical risk.

Consequences: playbook SAH-31 "HRA questionnaires and risk scores" is **still blocked** — those
screens genuinely do not exist in the mockup. And the mockup's staff module has no module of its own
in `CLAUDE.md`'s six (`platform`, `patient`, `opd`, `ipd`, `hra`, `billing`).

This is a module-boundary question and two documents disagreeing, so it is escalated separately as
**DEC-005**. The screen map's HRA row should not be changed until DEC-005 is decided.

## Gap 3 · The admin portal is one story for about 20 screens

Playbook SAH-8 is a single story: "Sahaj admin: tenants and plans". The mockup's admin portal has a
registry-driven sidebar over roughly 20 pages: Overview, Clinics across India (geography drill-down),
Organizations directory (faceted search, saved views, bulk selection), Approvals (two-admin),
Bulk jobs, Support tickets, System health, Audit log, Growth and revenue, Navigation and modules,
Plans and billing, Feature rollout, Templates and master data, Messaging and integrations,
Access and security, Data and compliance, Announcements, Architecture and scale, plus Global and
organization clinical masters (8 masters each) and a settings engine over ~18 setting pages.

One story cannot carry that. It is also the part most likely to exceed "one story" in the
tech-lead's escalation rule.

## Options

**A · Insert the three screens as new Stage A stories, before the app shell (recommended).**
Renumber or append: `SAH-1a Home page`, `SAH-1b Sign up and log in (OTP)`, `SAH-1c Setup wizard`,
placed after SAH-3 (sign-in needs tenancy and auth from SAH-2 and SAH-3) and before SAH-4 (app shell).
- Pros: follows the mockup's own order, which is the user's instruction. A clinic cannot reach the
  console without sign-up and the wizard, so building them early makes every later story testable
  end to end with a real tenant.
- Cons: pushes OPD work out by roughly three stories. The landing page is marketing, not clinical,
  so it delivers no clinical value early.

**B · Build the console first, add the public site and wizard later.**
Keep the playbook order. Sign in with seeded users; add Home page, OTP sign-up and the wizard after
Stage D (OPD) is working.
- Pros: clinical value soonest. The OPD screens are what clinics are paid for.
- Cons: departs from the mockup's "start to end" order the human just asked for. No real onboarding
  path, so every demo needs a hand-seeded tenant.

**C · Split the difference: sign-up and wizard early, landing page last.**
Add `Sign up and log in` and `Setup wizard` into Stage A (they are product, and gate every tenant),
and defer `Home page` to Stage G as a separate marketing story.
- Pros: a real clinic can self-onboard early, which makes SAH-5 onward genuinely testable. The
  landing page has no dependency on anything else and can be built any time.
- Cons: three-way split needs the screen map and playbook edited in two places.

## Recommendation

**Option C.** The sign-up and wizard are load-bearing: every later story needs a tenant that was
created the way the product actually creates one, and the wizard is where specialty, workflow, fees
and brand are first set — all of which SAH-5 to SAH-20 then read. The landing page shares no code
with the console (its own CSS and animation layer in the mockup) and blocks nothing, so it is the
one piece that genuinely can come last.

Separately, **split SAH-8** into at least: admin shell and navigation registry; organizations
directory and clinic detail; plans, entitlements and feature rollout; approvals, jobs and audit;
masters and settings engine. The tech-lead will propose the split once this decision is made.

## What the human must do

`docs/design/screen-map.md` is humans-only. Please add the three rows the mockup needs, and correct
the HRA row:

```
| Home page (landing: features, pricing, compare, Start free) | platform | 1 | Public page before login |
| Sign up and log in (mobile one-time code) | platform | 1 | New clinic / Log in toggle, OTP step |
| Setup wizard (5 steps: clinic, specialty, staff, OPD, look and web address) | platform | 1 | Runs once after sign-up |
```

Do **not** change the HRA row yet — see DEC-005.

## Decision

**Option B — all three early, in mockup order.** Decided by Ganesh, 31 Oct 2026.

Stage A becomes six stories, in the order a real clinic meets them:

| # | Story | Mockup screen | Depends on |
|---|---|---|---|
| SAH-1 | Project scaffold | Foundation (no screen) | — |
| SAH-2 | Tenancy, users, roles and audit | Foundation (no screen) | SAH-1 |
| SAH-3 | Home page (landing) | Home page (landing: features, pricing, compare, Start free) | SAH-1 only — no auth or tenancy needed |
| SAH-4 | Sign up and log in (mobile one-time code) | Sign up and log in (mobile one-time code) | SAH-2 (tenant, user), SAH-3 (entry point) |
| SAH-5 | Setup wizard (5 steps) | Setup wizard (5 steps: clinic, specialty, staff, OPD, look and web address) | SAH-4 |
| SAH-6 | App shell and theme | App shell | SAH-5 |

Everything from the old SAH-5 onward shifts by **+2**. Final count: 34 stories, before any SAH-8
admin split.

### Why renumber instead of adding SAH-33, SAH-34, SAH-35

Not cosmetic. `.claude/hooks/guard-ownership.mjs` reads the story ID from the branch with
`/^(?:feature|fix)\/(SAH-\d+)/`. That pattern captures only digits, so a suffixed ID like `SAH-1a`
or `SAH-2.1` would capture `SAH-1` / `SAH-2` and look up **the wrong story's approvals**. Suffixed
IDs are therefore unsafe, and plain integers in build order are the only correct option. Since no
story beyond SAH-1 has started, nothing has to be renamed.

### Still to settle

`SAH-10` (old SAH-8, "Sahaj admin: tenants and plans") remains one story for roughly 20 admin
screens. The tech-lead will propose a split into separate stories before Stage B starts.
