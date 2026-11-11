# DEC-035 · SAH-42 `/admin/register` page: authenticator hand-off and browser PIN saving

Date: 11 Nov 2026. Status: ANSWERED 11 Nov 2026 (human, in chat). Raised by: tech lead, from the page reviews (docs/reviews/SAH-42-page-code.md finding 1, docs/reviews/SAH-42-page-security.md M-1).
Blocked: acceptance of the page (code verdict CHANGES REQUIRED only for question 1; security PASS WITH CONDITIONS, condition C2 for question 2). Nothing else is blocked; the minor fixes run in parallel.

## Question 1 · what happens after the first admin is created
The stories describe the main path as: the page moves to "Set up your authenticator app", shows the key and otpauth link, the owner confirms a code and is signed in (SAH-42-5 criteria 2, 9, 10, 11). ADR-018 D6 says enrolment is "the existing sign-in step". The existing step is private state inside `AdminSignInPage` and cannot receive the pre-auth token from another page, and the angular-developer was told not to invent a new flow. So the page was built with the fallback: on 201 it drops everything and goes to `/admin/sign-in`; the owner signs in once and the existing first-sign-in enrol step runs. The tech lead first called this "allowed by the story". The code reviewer correctly points out that criterion 12 describes only a closed browser, so this is a **new deviation** from the stories you signed off.

| Option | What it is | Pros | Cons |
| --- | --- | --- | --- |
| A | A small root service holds the pre-auth token in memory; `AdminSignInPage` reads it once on init and goes straight to its enrol step | Delivers the story path (no second typing); about 10 lines in the ADM-1 page; token only in memory, read once | Changes the ADM-1 page; passes a token between pages, which ADM-1 was designed to avoid (ADR-003); needs a re-review of the ADM-1 flow |
| B | Extract the enrol and recovery-code steps into a shared component used by both pages | Cleanest result; story path delivered | Biggest change; refactors a reviewed ADM-1 page; more tests and review |
| C | Keep the fallback (accept as built) | No change to ADM-1; secrets dropped early; works today | Email and password typed twice; the owner arrives at an empty sign-in with no explanation; stories 2, 9, 10, 11 reworded by the BA |

**Recommendation: C**, with one short line of text on the sign-in page after a fresh registration if you want it (new wording needs your sign-off). Reason: a one-time, local-only tool; the cost is typing twice; no change to the reviewed admin sign-in flow or ADR-003. Choose A if you want the story path exactly; B only if you also want the ADM-1 page cleaned up.
Smaller points to acknowledge with the answer: (a) the story says "the app's normal page not found screen", but the app has none and an unknown address goes to the home page, so a closed feature looks like the home page (code finding 2); (b) with C, the stories' Step 3 texts stay unused.

## Question 2 · the browser may offer to save the PIN
The PIN field is `type="password"` with `autocomplete="off"`; Chrome, Edge and Firefox ignore that on password fields and may offer to save the PIN after a successful unlock, into the browser's password store (possibly synced). The security reviewer's fix is `autocomplete="one-time-code"` (Chromium does not offer to save those), checked by hand in Chrome and Edge. The story says "no autofill hint", so this is a story wording change.
**Recommendation: accept `one-time-code`** (the PIN is a one-time unlock secret). Cost: the browser may suggest a code from SMS on some phones; irrelevant on a developer machine. Until you decide, type a real PIN into the page only in a private window or a browser profile with password saving off.

## Texts to sign off (not in the story table)
"Unlocking…" and "Creating admin…" (busy labels); "Something went wrong. Try again." for 429, 500, 413, 403 and unknown answers (on unlock a 500 can cost a try; the text does not say so); "That PIN is not right." when the count is missing; route title "Create the first admin · Sahaj"; aria-label "About this page"; both password fields are cleared after a failed create (name and email stay).

## Safe to do now (no decision needed)
Code finding 4/11 and security L-1/L-2 (`takeUntilDestroyed` and a destroy test), code finding 5 (focus after lock), security L-3 (restrict the `BOOTSTRAP_CALL` marker). Started by the angular-developer.

## Answer (human, chat, 11 Nov 2026)
1. After create: **C, keep as built** (the page goes to `/admin/sign-in`; the owner signs in once and the existing first-sign-in enrol step runs). BA rewords stories 2, 9, 10, 11 and the page-not-found wording; architect aligns ADR-018 D6.
2. PIN field: **yes, `autocomplete="one-time-code"`**. BA updates the story wording ("no autofill hint"); angular-developer changes the attribute; QA updates the e2e and the manual check in Chrome and Edge (next to M-4).
Still open for the human: the wording sign-off list above (busy labels, safety-net text, "That PIN is not right.", route title, aria-label, passwords cleared after a failed create) and whether a one-line explanation should appear on the sign-in page after a fresh registration (new wording, not added).
