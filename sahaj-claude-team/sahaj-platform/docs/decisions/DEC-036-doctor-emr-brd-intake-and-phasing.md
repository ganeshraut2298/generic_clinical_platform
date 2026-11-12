# DEC-036 · Doctor EMR: intake of the business requirements, phase plan and blockers

Date: 11 Nov 2026. Status: ANSWERED 11 Nov 2026 (human, in chat; all three recommended options). Raised by: tech lead.
Input: "Doctor EMR — Business Requirements" (PDF, 15 pages, 11 Nov 2026, author on the PDF: Aarti Pujari) and a mockup link to a claude.ai artifact, both given by the human in chat. Human instruction: split the requirements into phases and start development, "from master configuration, then EMR".
Blocked: everything for the EMR UI (no design in the repo). Nothing else is blocked.

## What was checked in the repo
- `docs/design/mockup/sahaj-mockup.html` (last changed 31 Oct 2026) has **no "Doctor desk" and no "Doctor EMR" screen** (searched). It has the Queue with "Doctor App is the consultation step", a role called Doctor, and Clinic setup → Specialty template and Case paper and Rx. The IPD "EMR sheet (card → EMR)" in `docs/design/screen-map.md` is a different thing (IPD medicine orders, SAH-26). The screen map has no row for the Doctor EMR or Clinic setup → Doctor EMR.
- CLAUDE.md rule 1: the mockup in `docs/design/` is the design and is human-owned. A claude.ai link is not in the repo, so agents cannot treat it as the design until a human saves it in `docs/design/` and adds screen-map rows.
- The BRD says "where the mockup and this BRD differ, this BRD wins"; CLAUDE.md says anything different from the mockup goes to a human. Each difference needs a human sign-off.
- A Doctor role and a doctor entry already exist in the platform and the mockup (`DoctorInput`, roles list). The BRD's open question 1 ("separate role or flag") is therefore mostly answered by what is built; the BA will confirm.
- CLAUDE.md "Not in phase 1": integration gateway (ABDM, HL7, payments), notifications (WhatsApp, SMS), documents service. The BRD's WhatsApp share, ABHA/ABDM share and PDF case paper depend on them. Print from the browser does not.
- Existing stories already cover the Queue, follow-ups, billing and IPD admission; the EMR must reuse them (BRD section 7), not rebuild them.

## Proposed phases (stories SAH-43 onward; the business analyst confirms the split and writes the stories)
Order requested by the human: master configuration first, then the EMR.

| Phase | Story (proposed) | Content (BRD refs) | Depends on |
| --- | --- | --- | --- |
| M1 · Master configuration | SAH-43 EMR masters | Quick-option masters: complaints, diagnoses with ICD-10, favourite medicines (generic names), investigations, advice; usage ranking per doctor (FR-11, section 5 "Quick-option lists"); doctor master fields (name, degree, registration number, specialty) if not already complete | none (backend, no screen); a master-editing screen needs the mockup |
| M2 · Master configuration | SAH-44 Specialty templates as data and clinic overrides | `emr_template`, `emr_template_override`, `emr_settings`; the four templates (General practice, Dental, Paediatrics, Homeopathy); template choice from doctor specialty, fallback clinic specialty; a visit stores its section snapshot (section 5, 6) | SAH-43 |
| M3 · Master configuration | SAH-45 Clinic setup → Doctor EMR page | FR-19, FR-20, FR-21: sections per template, Show and Required switches, reset, screen defaults, audit of setup changes, Owner only | SAH-44; mockup screen |
| E1 · EMR shell | SAH-46 Open EMR from the Queue and the EMR shell | FR-02 (OPD part first), FR-03, FR-06 to FR-10, the finish bar layout (FR-16) | SAH-44, 45; mockup |
| E2 · Section editors | SAH-47 General editors and smart pre-fill | FR-11 (complaints, vitals, signs, systems, diagnosis, advice, chip lists), FR-12, completion rules (section 6) | SAH-46 |
| E3 · Prescription and services | SAH-48 Prescription, procedures and services | Prescription editor (generic names in capitals, load usual Rx, repeat last visit), services chips adding bill lines through the existing billing module (section 7) | SAH-47 |
| E4 · Dental | SAH-49 Tooth chart and treatment plan | Tooth chart FDI, treatment plan and sittings (FR-11 dental rows) | SAH-47 |
| E5 · Right half | SAH-50 Past visits and live case paper | FR-13, FR-14, FR-15, case paper layout (section 6), browser print at A4/A5 | SAH-47, 48 |
| E6 · Finish | SAH-51 Save draft, autosave, Finish and sign | FR-16 to FR-18 for OPD, follow-up creation, workflow move, one signer, addendum instead of edit (section 8), audit | SAH-46 to 50 |
| E7 · Encounters | SAH-52 Visit type, Admit to IPD, IPD round and Emergency variants | FR-04, FR-05, FR-17 for IPD and ER, reading vitals from the IPD nursing chart and ER triage | SAH-51; existing IPD stories |
| Later (not phase 1 of the product) | WhatsApp and ABHA/ABDM share, Marathi and Hindi labels, performance targets, the other templates (Gynae, Physio, Oncology, Surgery), form builder, drug checks | BRD phase 4 and section 9 "out of scope" | integration gateway, notifications, documents service |

BRD delivery phases map onto this: BRD phase 1 = M2, M3, E1; phase 2 = E2, E3, E4, E5; phase 3 = E6, E7 (WhatsApp and ABDM parts stay Later); phase 4 = Later.

## Questions for the human (asked in chat)
1. **Design source.** Save the artifact as an HTML file in `docs/design/mockup/` and add rows for "Doctor desk / EMR" and "Clinic setup → Doctor EMR" to `docs/design/screen-map.md`. Until then, only backend work with no screen can start (SAH-43, SAH-44 data part).
2. **Scope.** Confirm WhatsApp send and ABHA/ABDM share are Later (not built now), print only, and that each place where the BRD differs from the mockup is listed by the BA in its story for your sign-off (BRD "wins" only after you sign each difference).
3. **"Master configuration".** Confirm it means SAH-43 to SAH-45 above.

BRD open questions that go to the BA as open questions (they block only the stories that need them; defaults in brackets): doctor can edit templates for themselves or only the Owner [Owner only, per FR-20]; Emergency alerts to front desk and nurse [no, queue position only]; referral letter separate or a line on the case paper [a line, per FR-05]; ABHA sharing manual or automatic [manual, and Later anyway]; drug master for favourites and generic names [needs the product owner, blocks SAH-48].

## Recommendation
Start now with the BA on SAH-43 and SAH-44 (data, no screen), keep SAH-45 onward waiting for the design files, and use the defaults above for the open questions. Reason: this follows the human's order (masters first), touches no mockup screen, and keeps work moving without breaking rule 1.

## Answer (human, chat, 11 Nov 2026)
1. Design source: **start the masters now; the human adds the design.** BA starts SAH-43 and SAH-44 (data, no screen). The human saves the artifact as an HTML file in docs/design/mockup/ and adds the screen-map rows; SAH-45 onward (every EMR screen and the Clinic setup page) wait for that.
2. Scope: **WhatsApp send and ABHA/ABDM share are Later; print only now.** BRD phase 4 (ABDM share, Marathi and Hindi labels, performance targets) also Later. Each place where the BRD differs from the mockup is listed by the BA in its story for the human's sign-off.
3. Master configuration = **SAH-43 EMR masters, SAH-44 specialty templates as data, SAH-45 Clinic setup → Doctor EMR page**, before any EMR screen.
Open questions with defaults (BA keeps them as open questions): doctor edits templates only if Owner; Emergency alert beyond queue position: no; referral = a line on the case paper; ABHA sharing manual (and Later); drug master for favourites and generic names: needs the product owner, blocks SAH-48.
Recorded as chat answers, not as lines in docs/approvals.md. Process notes: the working tree is dirty and mixed (branch feature/SAH-39-home-kinetic-background), so the tech lead has not created a feature branch yet; the human commits or stashes, then `feature/SAH-43-emr-masters` is created from main. The BA writes only new files in docs/requirements and docs/stories until then.

## Addendum (human, chat, 11 Nov 2026): screens and go-ahead
The human wrote that the EMR screens are in the claude.ai artifact (link repeated in chat); that the EMR configuration part sits inside the Clinic setup → **Specialty template** page, opened by selecting a specialty; and gave "approval for all stories development" with instruction to deploy multiple agents and start development. Recorded as intent, **not as lines in docs/approvals.md**. Facts checked afterwards: the artifact is not in `docs/design/mockup/` and `docs/design/screen-map.md` has no EMR rows, and agents cannot open claude.ai links, so SAH-45 onward (every EMR screen and the Specialty template configuration page) still waits for the human to save the mockup file and add the rows. Only SAH-43 and SAH-44 have stories today. The tech lead starts the solution-architect on the SAH-43 and SAH-44 contract; db-engineer and developers wait for the `contract` approval (the human reviews the contract first), unless the human says otherwise.
Design hint for SAH-45 and SAH-44-2/3: the EMR section switches (Show, Required) and screen defaults appear on the Specialty template page, not on a separate "Doctor EMR" menu entry; this differs from the BRD (FR-19 asks for a new entry "Doctor EMR"). To be confirmed from the mockup file.
