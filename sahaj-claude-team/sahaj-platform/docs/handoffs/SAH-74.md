# SAH-74 · Login page motion and background · handoff log

Branch: none yet (uncommitted tree on feature/SAH-39-home-kinetic-background; story branch after the human commits). Decision: DEC-050.

## business-analyst · 13 Nov 2026
DONE. docs/requirements/SAH-74.md (BR1-BR9, OQ-74-1..4 with defaults), docs/stories/SAH-74.md (SAH-74-1..5). Mockup shows the 10-digit error as a toast after Send OTP only (line 4786); stories follow the mockup (OQ-74-2). Amends SAH-39-5 c1/c5 and SAH-39 BR1 for the login page only. Durations 0.4 s / 0.25 s proposed. Needs the human `stories` line. Tech lead: validate docs fails only on ADM-2.md. No API or architecture change: architect stage not needed (no ADR, no contract); angular-developer next.

## angular-developer · 13 Nov 2026
Code and 25 unit tests written (login.page.html/ts/scss/spec; shared KineticBackgroundComponent reused, doc comment only). Entrance 320 ms + 60 ms card delay, starts at 40% opacity; tab highlight 220 ms translateX; background on /login only; reduced motion follows live; error-timing defect fixed (red only after Send OTP / Verify, as the mockup). Invented: durations, frosted left panel (contrast estimate 4.1:1 without it; QA to measure), focus on open (mockup `go`), Send OTP disabled while sending (existing). Gates NOT run: frontend lock was held by a hung agent; tsc --noEmit clean. Tech lead runs lint, ng test, build next.

## angular-developer · gate repair · 14 Nov 2026
DONE. Cause: the SAH-74 login spec's ResizeObserver stub leaked (missing unobserve, callbacks without entries, unstub not in afterEach) and broke later specs in the same worker; one stale aria-pressed assertion (the toggle group is a radio group). Fixed in login.page.spec.ts only. lint 0 errors; ng test 262/262 files, 4,120/4,120 tests; build OK (course.page.scss budget warning, old). validate frontend still fails on the human-owned --browsers=ChromeHeadless flag in scripts/validate.mjs. SAH-74 AC wording (aria-pressed → aria-checked) recorded in DEC-052 addendum for the BA.
