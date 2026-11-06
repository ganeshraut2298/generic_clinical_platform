# DEC-009 · New mockup version supplied in chat (not on disk)

Status: OPEN (needs the human in the morning)

## Context
On 4 Nov the human attached a newer "Sahaj Clinic Console" HTML mockup in chat and asked for (a) development to continue overnight and (b) the clinical and admin UI/UX to be matched to it afterwards.
The attachment exists only in the chat. It is not in the repository. `docs/design/` is human-owned (CLAUDE.md), so agents and the tech lead cannot add or replace files there, and the file is too large to retype faithfully.
The mockup that IS in the repo (`docs/design/mockup/sahaj-mockup.html`) is the older one.

## What the new mockup visibly adds or changes (from reading it in chat)
- A different shell: one registry-driven sidebar for the clinic console and the admin portal (collapsible, grouped, search box, mode switch Simple / Advanced, "Go to…" palette on Ctrl K, mobile drawer).
- Clinic setup split into Common / OPD / IPD / HRA, plus Plan and modules, Booking rules, Tariff and services (branch and payer rates, pricing rules, rate revisions), Patient card and Roles and access.
- IPD as a real module: bed board (including a 26-ward hospital layout), admissions and deposits, transfer, discharge, emergency triage, patient cards, nursing sheet, medicine orders with safety checks and MAR, early-warning escalation, break-glass access, clinical audit trail.
- HRA add-on: staff, shifts, leave, attendance, holidays.
- Billing counter with care-team orders and pricing rules, plus an owner view with leakage watch.
- Front-desk appointment workspace with a docked Book / Find / To do panel.
- Admin portal: organizations directory, menu management, masters, settings engine, bulk jobs, growth and architecture pages.

## Consequences
- Rule 1 of CLAUDE.md (mockup is the design) means the new mockup should replace the old one in `docs/design/mockup/` before the UI-match pass. Only the human can do that.
- Several of the features above are larger than any story built so far (IPD alone is the nine-story block in the playbook).

## Decision needed
1. Copy the new file to `docs/design/mockup/` (replace, or add as `sahaj-mockup-v2.html`) and update `docs/design/screen-map.md` if screens changed.
2. Confirm the order: finish the remaining OPD stories first, then UI-match pass on built screens, then IPD and HRA stories.

## Done meanwhile (no decision needed)
Overnight work continues on the stories that already exist in both mockups: Revenue and Reports lists, then the UI-match preparation (a written gap list per built screen once the file is on disk).
