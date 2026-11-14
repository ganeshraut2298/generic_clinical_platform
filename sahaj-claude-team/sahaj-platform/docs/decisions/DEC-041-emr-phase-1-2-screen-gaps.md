# DEC-041 · EMR phases 1-2 screens: mockup gaps and invented texts (SAH-45, 46, 47, 52, 54)

Date: 12 Nov 2026. Status: ANSWERED 12 Nov 2026 (human, chat). Raised by: tech lead, from the angular-developer's hand-back (logged in docs/handoffs/SAH-43.md).
Rule 1 (CLAUDE.md): anything not in the mockup or different from it goes to the human. Built as described below; default if unanswered (DEC-023): keep as built.

## Gaps and choices
1. Doctor-screen top bar ("Queue (n waiting)", Appointments, Patients): not built; only a Back button (SAH-47 intro vs SAH-47-1 criterion conflict).
2. Visit-type caption "Starts from the New consult layout — changes here apply only to …" left out (one configuration for all visit types, OQ-45-18).
3. Add department wizard: added a required "Department name" field (mockup has none); initials and colour from server defaults.
4. Wizard step 2 for Start blank shows a note instead of the section columns (no blank-layout endpoint).
5. Wizard step 3: "No doctor selected. You can add doctors later." (no screen exists to add later).
6. Copy source: only saved departments (Draft or Live) can be copied; disabled reason text invented.
7. Wizard is a routed page, not the mockup's modal.
8. Template preview lists the starter's Specialty sections (mockup counts "n specialty sections").
9. Invented texts: special-editor placeholder; wizard success toast; "Signing a visit is not available yet."; three read-only banners; IPD/ER draft 409 text; "Department chosen by the clinic default"; chip "Draft saved, not published".
10. Case paper subtitle fixed at "0 of 10 filled · live" until SAH-55.
11. Queue default filter stays "Everyone" (SAH-46-1 says own patients by default); the button follows the own-patient rule.
12. IPD Admitted table view has no Open EMR button (cards only).
13. Behaviour choices: unsaved count across opened departments; client-side "Nothing new to save"; Reset origin kept until next save; live Done rules while typing; invalid values block Save draft; department switch saves the draft first; Admit to IPD opens the existing sheet without pre-fill; vitals flags by field key; Back button behaviour.

## Answer (human, chat, 12 Nov 2026)
1. **Keep all 13 as built** (wording can still change later).
2. **Top bar: no** — the EMR stays inside the app shell with a Back button; the mockup's doctor-screen top bar is not built. The BA aligns SAH-47-1 with this.
