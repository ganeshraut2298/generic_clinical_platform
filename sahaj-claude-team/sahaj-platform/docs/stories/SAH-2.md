# SAH-2 · Home page
Mockup screen: Home page (landing: features, pricing, compare, Start free)
Module: platform

## 1. Story
As a clinic owner visiting Sahaj, I want to see what it does, what it costs and how it compares with what I use today, so that I can decide to start free or talk to sales.

## Acceptance criteria
1. Given a visitor on a phone, tablet or desktop, when the page loads, then layout, text and plan prices match the mockup and work at 360 px wide.
2. Given the visitor clicks Start Free, then the Sign up screen opens in "New clinic" mode.
3. Given the visitor moves the calculator sliders, then the estimates update like the mockup and are labelled as estimates.
4. Given a sales enquiry with a valid name and 10-digit mobile, when submitted, then it is saved and a thank-you message shows; invalid input shows field errors.
5. Given the sticky header, then it shows the brand, section links (How it works, Workflow, Specialties, Compare, Pricing), Admin portal, Log in and Start Free; section links scroll to their section. Section links hide at 980 px and below.
6. Given the sections, then they appear in mockup order: hero, Clinic setup, Workflow builder, Roles and responsibilities, Patient journey, Specialties, Compare, Pricing, Trust and control, Questions, final call to action, footer.
7. Given the billing period toggle ("Yearly · save 44%" / "Monthly"), then Professional shows ₹1,999 per year or ₹299 per month. Free shows ₹0 for 12 months; Multi-Doctor shows "Per doctor · quote on request"; Custom shows "Let's talk".
8. Given the Compare section, when a "What do you use today?" option is chosen, then the feature table and costs reset to that option; clicking a feature row cycles No, Partly, Yes and shows "Adjusted by you"; more than 2 doctors shows the custom plan message.
9. Given Contact sales or Book a Demo, then the enquiry sheet asks for name, clinic name and city, mobile (+91) and best time to call (Morning, Afternoon, After OPD hours).
10. Given the page, then the footer says patient data is hosted in India and prices exclude GST; the pricing note says prices exclude 18% GST.
11. Given a visitor who prefers reduced motion, then animations do not run and the final state of each scene shows.
12. Given keyboard use, then every control is reachable and has a visible focus ring.

## 3. Technical design
- Frontend: lazy public route `/`, one feature folder `features/home`, one component per section. Plan and calculator data are front-end constants copied from the mockup; server plan limits arrive in SAH-3.
- Rebuild from mockup functions: `pLanding`, `heroStage`, `setupInner`, `wfInner`, `rolesInner`, `journeyInner`, `specInner`, `compareInner`, `calcNums`, `calcOut`, `priceInner`, `trustHTML`. Calculator formulas must match `calcNums`: 3-year cost `(spend + msg) × 36`, Sahaj ₹3,998 up to 2 doctors, otherwise a quote.
- Do not copy mockup HTML, global state or inline script. Character illustrations may be rebuilt as SVG components.
- Backend: table `platform.sales_enquiry` (`id`, `name`, `clinic_city`, `mobile`, `best_time`, `source`, `created_at`). No `tenant_id`, no RLS; `sahaj_app` has INSERT only. Mobile is personal data: never logged.
- Public endpoint limit: 5 per IP per hour, in memory for phase 1. Over the limit returns 429.
- No email in phase 1.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/api/v1/platform/public/sales-enquiries` | public | `{name, clinicCity?, mobile, bestTime?, source}` | 201 `{id}` | 400 field errors, 429 |

## 5. Tasks
### Tasks — Backend
- Migration, endpoint, validation (`name` required, mobile `^[6-9][0-9]{9}$`), rate limit.
- Tests: saved row, field errors, 429, mobile absent from logs.

### Tasks — Frontend
- All sections and interactions above, responsive at 360 px, 620 px, 980 px and desktop.
- Enquiry sheet with field errors and the thank-you message ("Request received. We will call you within one working day.").
- Start Free goes to `/login?mode=signup`; Log in goes to `/login?mode=login`. Until SAH-3 lands these open a stub route.
- Unit tests for the calculator and the price toggle. Playwright smoke: load, click Start Free, submit an enquiry.

## 6. Out of scope, Open questions
Out of scope: analytics, email, the Sahaj admin portal itself.
Open questions:
- Admin portal button: the admin portal is not in Phase 1 stories. Recommended: keep the button, open a plain "Coming soon" page.
- "Or open the demo clinic now": no demo clinic exists in Phase 1. Recommended: hide it until there is one.
- Mockup label is "Start Free"; your text says "Start free". The mockup wins unless you say otherwise.
- The mockup's note about confirming the 15-minute promise in pilot tests is a mockup note. Recommended: omit it on the real page.

Assumptions (human, 1 Nov 2026: "take assumptions and go ahead"; the mockup is the reference for UI and UX):
- Admin portal button stays and opens a plain "Coming soon" page.
- "Or open the demo clinic now" is hidden.
- The label stays "Start Free".
- The 15-minute mockup note is left out.
- Until the enquiry API exists, a failed submit shows an error and never a false thank-you.

## 7. Status log
1 Nov 2026 frontend: home page on `/`, all sections in mockup order; calculator, price toggle, compare and enquiry sheet done with unit tests; `/login` and `/admin` are stubs. Thank-you shows only on a real 201.
1 Nov 2026 frontend: no overflow at 360, 620, 980, 1440 px; screenshots viewed at 360 and 1440 only. Workflow node buttons are 36 px, not 44 px. No real keyboard or reduced-motion run yet.
1 Nov 2026 frontend: `validate frontend` fails at the DEC-002 `--browsers` flag (human edit needed), before reaching build.
1 Nov 2026 backend: enquiry endpoint, validation and rate limit done; unit and slice tests pass. Not yet run against a database or from the page.
