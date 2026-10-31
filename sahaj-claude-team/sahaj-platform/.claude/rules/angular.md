---
paths:
  - "frontend/**"
---

# Angular rules

- Standalone components only, `ChangeDetectionStrategy.OnPush`, signals for local state, `inject()` over constructor injection.
- Folder per module, matching the backend: `src/app/features/<module>/<feature>/`. Shared UI in `src/app/shared/`, app-wide services in `src/app/core/`.
- Match the mockup screen named in the story (`docs/design/mockup/sahaj-mockup.html`): layout, order of fields, labels, states and role differences. Open it in a browser to check.
- Angular Material for all controls, themed from `docs/design/tokens.md`. Use the Sahaj theme tokens; never hard-code colours or fonts.
- Typed reactive forms (`FormGroup<...>`); validators that match the API contract limits.
- HTTP only in `*.api.ts` services that return typed models from the contract. Components never call `HttpClient`.
- Lazy-load every feature route. Route guards check role and entitlement; the backend still enforces.
- Accessibility: every control has a label, focus is visible, colour is never the only signal, touch targets at least 44 px for tablet use.
- Text: plain English, sentence case, Indian formats (₹ 1,25,000; 24 Oct 2026; 11:05 AM).
- No patient data in `localStorage`, URLs or console logs.
- Tests: Jasmine/Karma or Jest (whichever the scaffold uses) for components, services and guards; Playwright e2e lives in `frontend/e2e/` (QA only).
- Before handoff: `node scripts/validate.mjs frontend`.
