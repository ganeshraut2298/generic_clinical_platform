# Mockup screen map

Phase 1 = build now. Later = shown in the mockup but depends on a deferred service or module; build only the parts marked.

| Mockup screen (how to open) | Module | Phase | Notes |
|---|---|---|---|
| Foundation (no screen) | platform | 1 | Scaffold, tenancy, security, audit: stories with no UI |
| Start screen and sign-in (opening screen of the mockup) | platform | 1 | Choose clinic and role; real login replaces the demo role switcher |
| App shell (sidebar, header, Simple/Advanced, light/dark, language) | platform | 1 | Layout every screen sits in |
| Today (sidebar → Today) | opd | 1 | Day summary per role |
| Queue (sidebar → Queue) | opd | 1 | Token queue, consult flow, Doctor App is the consultation step |
| Appointments (sidebar → Appointments) | opd | 1 | Front-desk workspace, slots, booking rules |
| Patients (sidebar → Patients) | patient | 1 | Registration, search, duplicate merge |
| Billing (sidebar → Billing) | billing | 1 | Billing counter + owner view, payments, refunds, service orders |
| Revenue, Reports (sidebar) | billing / platform | 1 (lists) / Later (dashboards) | Simple operational reports now; dashboards wait for the analytics service |
| Follow-ups (sidebar) | opd | 1 | Lists only; WhatsApp sending is Later |
| IPD → Bed board | ipd | 1 | Wards, beds, status, hospital example with 26 wards |
| IPD → Admitted | ipd | 1 | Uniform patient cards, 2/3/4 per row, stage filter, table view |
| IPD → Emergency | ipd | 1 | Triage queue and target times |
| IPD → Safety | ipd | 1 | EWS escalations, pharmacist check, medicine round, emergency access review, clinical audit |
| Nursing sheet (card → Nursing) | ipd | 1 | Vitals, notes, MAR with wristband and second-nurse check |
| EMR sheet (card → EMR) | ipd | 1 | Medicine orders with allergy, duplicate and interaction checks |
| Transfer, Discharge, Case paper (card actions) | ipd | 1 | |
| Staff and leave (sidebar, Advanced) | platform | 1 | |
| Clinic setup → Overview, Plan and modules, Clinic profile, Team and access | platform | 1 | Entitlements, roles |
| Clinic setup → Specialty template, Case paper and Rx | opd | 1 | |
| Clinic setup → Fees and billing, Tariff and services | billing | 1 | Branch and payer rates, pricing rules |
| Clinic setup → OPD workflow, Appointments and slots, Booking rules, Alerts and escalation | opd | 1 | |
| Clinic setup → Patient card, Roles and access, Wards/rooms/beds, Bed categories, Admission and deposits, Emergency and triage, Discharge | ipd | 1 | Card config with draft/publish, locked safety fields |
| Clinic setup → WhatsApp messages | notifications | Later | Keep consent settings in platform now; sending waits for the notifications service |
| Clinic setup → Privacy, ABDM and backup | platform / integration | 1 (consent, privacy) / Later (ABDM) | ABDM needs the integration gateway |
| Pharmacy, Lab, Tele (sidebar, when enabled) | later modules | Later | |
| Sahaj admin portal (platform admin view) | platform | 1 (tenants, plans) | Support access with owner OTP is Later (needs notifications) |
| HRA | hra | 1 | Not yet in the mockup — business analyst to request screens before stories |
