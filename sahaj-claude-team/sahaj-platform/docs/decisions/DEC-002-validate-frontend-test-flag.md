# DEC-002 · `validate.mjs` passes a Karma flag to Angular 22's Vitest runner

Date: 31 Oct 2026
Raised by: tech-lead (on angular-developer's BLOCKED handoff)
Status: **NEEDS DECISION** — blocks SAH-1
Blocks: story SAH-1-5 ("`node scripts/validate.mjs all` passes"), and therefore SAH-1 merge,
and therefore every story in the playbook, since Stage A must merge before anything else starts.

## Context

`scripts/validate.mjs` runs the frontend test leg as:

```js
run('npm', ['--prefix', 'frontend', 'test', '--', '--watch=false', '--browsers=ChromeHeadless']);
```

`--browsers=ChromeHeadless` is a **Karma** flag. `ng new` today installs **Angular 22.2.5**, whose
default `ng test` uses the `@angular/build:unit-test` builder running **Vitest 5.0.5 on jsdom** —
Karma is gone. Vitest has a `--browsers` option too, but it means something different: it runs tests
in a *real* browser and requires one of `@vitest/browser-playwright`,
`@vitest/browser-webdriverio` or `@vitest/browser-preview` to be installed. None is, so the run
aborts:

```
The following packages are required but were not found:
  - The "browsers" option requires either "@vitest/browser-playwright", ...
FAILED: npm --prefix frontend test -- --watch=false --browsers=ChromeHeadless
```

Verified by the tech-lead, not taken on trust:

| Command | Result |
|---|---|
| `node scripts/validate.mjs backend` | **PASSED** |
| `npm --prefix frontend run lint` | **PASSED** — "All files pass linting." |
| `npm --prefix frontend run build` | **PASSED** — `dist/frontend`, 225 KB raw / 60.9 KB transfer |
| `npm --prefix frontend test -- --watch=false` | **PASSED** — 1 file, 2 tests |
| `npm --prefix frontend test -- --watch=false --browsers=ChromeHeadless` | **FAILS** as above |

So the scaffold itself is sound. The intent behind the flag — headless, non-interactive, exits 0 —
is **already satisfied** by the default Vitest/jsdom setup. Only the argument is wrong.

The angular-developer correctly refused to work around it: it did not downgrade Angular, retrofit
Karma, install a browser provider, or edit the validator. `scripts/` is humans-only in `CLAUDE.md`'s
ownership table, and the tech-lead is barred from editing it too, so **neither the owning agent nor
the tech-lead can fix this.** Hence the escalation.

## Options

**A · Drop the flag (recommended).** Change that line to:
```js
run('npm', ['--prefix', 'frontend', 'test', '--', '--watch=false']);
```
- Pros: one-word edit; correct for the builder Angular actually ships; tests already run headless
  via jsdom, so nothing is lost. `--watch=false` is kept, which is the part that matters for a gate.
- Cons: unit tests run in jsdom, not a real browser engine. For component logic this is standard and
  fine. Real-browser behaviour is better covered by qa-engineer's Playwright e2e tests anyway, which
  start at SAH-4 when the first screen exists.

**B · Install a Vitest browser provider.** Add `@vitest/browser-playwright` and keep real-browser
unit testing.
- Pros: unit tests exercise a real Chrome.
- Cons: a new dependency and a Playwright browser download; slower on every validation run;
  duplicates what e2e will cover; `CLAUDE.md` requires a human decision for a new library.

**C · Retrofit Karma.** Force the old `@angular-devkit/build-angular:karma` builder.
- Cons: Karma is deprecated and removed from Angular 22's defaults; swims against the framework;
  most work for least value. Not recommended.

## Recommendation

**Option A.** The flag encodes a Karma-era assumption that no longer holds. The gate's real
requirements — headless and non-interactive — survive the change untouched, and real-browser
coverage belongs in Playwright e2e from SAH-4.

## What the human must do

`scripts/validate.mjs` is humans-only. **Line 28:**

```js
  run('npm', ['--prefix', 'frontend', 'test', '--', '--watch=false', '--browsers=ChromeHeadless']);
```
becomes
```js
  run('npm', ['--prefix', 'frontend', 'test', '--', '--watch=false']);
```

Then the tech-lead re-runs `node scripts/validate.mjs all` and continues SAH-1 into QA and review.

## Decision

**Option A — drop the flag.** Decided by Ganesh, 31 Oct 2026.

Rationale accepted: the flag encodes a Karma-era assumption that no longer holds in Angular 22.
The gate's real requirements, headless and non-interactive, are unaffected — `--watch=false` is
retained and Vitest runs on jsdom with no browser. Real-browser coverage moves to qa-engineer's
Playwright e2e tests from SAH-4, when the first screen exists.

Applied by: the human (tech-lead is barred from `scripts/`). Status after the edit lands:
tech-lead re-runs `validate all`, then SAH-1 proceeds to qa-engineer and the two reviewers.
