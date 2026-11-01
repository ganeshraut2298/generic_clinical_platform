# DEC-005 · "HRA" names two unrelated modules

Date: 31 Oct 2026
Raised by: tech-lead
Status: **NEEDS DECISION**
Blocks: the module list, the `hra` package and PostgreSQL schema, and stories SAH-32 and SAH-33
(old SAH-30 and SAH-31). Does not block SAH-1 to SAH-6.

## Context

`CLAUDE.md` and the mockup use the same three letters for two different products.

**`CLAUDE.md` · HRA = Health Risk Assessment**, a clinical module:
> "Sahaj is a configurable SaaS for Indian clinics (OPD) and hospitals (IPD), with Health Risk
> Assessment (HRA)."

It is one of the six modules with a hard boundary: package `com.sahaj.hra`, PostgreSQL schema `hra`.
Playbook SAH-31 is "HRA questionnaires and risk scores".

**The mockup · HRA = Staff, timings and leave**, a human-resources add-on: weekly roster, shift
timings, leave types with balances and approval, attendance, holiday pack. Gated by `S.ent.hra`
alongside the OPD and IPD entitlements.

Verified by the tech-lead against `docs/design/mockup/sahaj-mockup.html`:

| Searched | Hits |
|---|---|
| `Health Risk Assessment` | **0** |
| `Health Risk` | **0** |
| `questionnaire` | **0** |
| `risk score` | **0** |
| `Staff, timings and leave (HRA)` | 3 |

Health Risk Assessment is **not in the mockup at all**. `docs/design/screen-map.md` already says so
for its HRA row ("Not yet in the mockup — business analyst to request screens before stories"), and
the playbook marks SAH-31 "blocked until you add HRA screens to the mockup". Both are still correct.

## Why this needs you

Three reasons it cannot be settled by the tech-lead:

1. **It is a module-boundary change.** Whatever is decided changes the six modules, a Java package
   and a PostgreSQL schema — on the escalation list in `.claude/agents/tech-lead.md`.
2. **Two human-owned documents disagree**, and both are humans-only files.
3. **It changes what the product is.** Health Risk Assessment is a clinical offering named in the
   first line of `CLAUDE.md`. Dropping it is a product decision, not a naming tidy-up.

Deciding it late is expensive: by Stage G the `hra` schema, package and RLS policies exist, so
renaming then means a destructive database change.

## Options

**A · Keep HRA clinical; give staff its own module (recommended).**
`hra` stays Health Risk Assessment. Staff, timings and leave becomes a **seventh module**,
`com.sahaj.staff` with schema `staff`. The mockup's "(HRA)" label is a mislabel and gets corrected
to "(Staff add-on)". Screen map gains a `Staff and leave` row pointing at the `staff` module;
SAH-33 stays blocked until Health Risk Assessment screens are drawn.
- Pros: `CLAUDE.md`'s product definition and the existing six-module architecture hold. Each module
  owns a clear domain; staff and attendance data never mixes with clinical risk data, which matters
  for DPDP (staff records are employee data, not patient health data).
- Cons: adds a seventh module, so it needs your approval as an architecture change. Two entitlement
  flags where the mockup has one.

**B · Follow the mockup; drop Health Risk Assessment from phase 1.**
`hra` is renamed to mean the staff module, and Health Risk Assessment leaves phase 1 entirely.
- Pros: exactly matches the mockup, which you asked to treat as the key reference. No seventh
  module, no new entitlement, one less thing to build.
- Cons: contradicts the first line of `CLAUDE.md` and drops a named product pillar. The acronym
  then means Staff everywhere, which will confuse any clinician reading the code or the schema.
  If Health Risk Assessment comes back later it needs a new module anyway.

**C · Reuse the `hra` module for both.**
One module and schema holding staff rosters and clinical risk questionnaires.
- Cons: mixes employee data and patient clinical data in one schema and one RLS boundary. Hard to
  reason about, hard to defend under DPDP, and against `CLAUDE.md`'s "modules with hard boundaries".
  Not recommended.

## Recommendation

**Option A.** The mockup is the authority on screens, flows, labels and business rules — that is
what `CLAUDE.md` rule 1 says — but it is not the authority on module boundaries or schema names,
and here it simply reuses an acronym for an unrelated feature. Keeping staff data in its own module
is also the safer privacy boundary: a receptionist's attendance record and a patient's risk score
should not share a schema.

The cheap part of Option A is that nothing needs building now. The seventh module is only declared;
staff screens arrive at SAH-32 and Health Risk Assessment stays blocked until you add its screens.

## What the human must do after deciding

- `CLAUDE.md`: if Option A, add `staff` to the module list and the ownership notes.
- `docs/design/screen-map.md`: correct the mockup's "(HRA)" label and add the `Staff and leave` row
  against the chosen module.
- `docs/process/playbook.md`: adjust SAH-32 and SAH-33 to match.

## Decision

**HRA means Human Resource management.** Decided by Ganesh, 31 Oct 2026.

This is not Option A, B or C as written above. It settles the question by correcting the premise:
Health Risk Assessment was never what HRA meant in this product.

What follows:

- The `hra` module keeps its name, package `com.sahaj.hra` and schema `hra`, and holds **human
  resource management**: staff, shifts, roster, leave types with balances and approval, attendance
  and holidays — exactly what the mockup's "Staff, timings and leave (HRA)" screen shows.
- **No seventh module.** The six modules in `CLAUDE.md` stand as they are.
- **Health Risk Assessment is not part of Sahaj.** `CLAUDE.md`'s opening line ("with Health Risk
  Assessment (HRA)") is factually wrong and should read Human Resource management.
- The mockup's label was right all along. The tech-lead's DEC-004 "gap 2" and this file's framing
  were both wrong, and both are retracted.

### Effect on the playbook

Two stories collapse into one. In the old numbering, SAH-30 "Staff and leave" and SAH-31 "HRA
questionnaires and risk scores" describe the same module under two names. There is nothing to build
for questionnaires or risk scores.

- One story: **HR management** — staff, shifts and roster, leave types and approval, attendance,
  holidays. Mockup screen: Staff and leave.
- The "blocked until you add HRA screens to the mockup" note is removed. The screens exist.

Net: Stage A gains 2 stories (DEC-004), Stage G loses 1. **33 stories**, before any split of the
admin-portal story.

### Files the human owns and may want to correct

- `CLAUDE.md` — line 3: Health Risk Assessment → Human Resource management.
- `docs/design/screen-map.md` — replace the HRA row with the Staff and leave row; the mockup label
  needs no change.
- `docs/process/playbook.md` — merge the two Stage G stories into one.

None of these block development. The tech-lead treats this decision as the authority from now on.
