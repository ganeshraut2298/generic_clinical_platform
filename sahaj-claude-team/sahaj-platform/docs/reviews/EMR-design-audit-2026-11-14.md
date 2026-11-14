# EMR design conformance audit · 14 Nov 2026

Requested by the human: "ensure as per this screen work has been done or not", against the design package `docs/design/emr-config/emr-config-design-handoff/emr-config/` (SCREENS.md, 17 screenshots, 17 `.dc.html` sources). The package's `EMR-Configuration-Smart-Clinic.html` is byte-identical to `docs/design/mockup/EMR-Configuration-Smart-Clinic.html`, the mockup the EMR programme was built from.
Method: three read-only code reviewers, static (code + screenshot + source); nothing was run. Accepted deviations (DEC-039, 041, 042, 043, 044, 048, 049, 051, repo conventions) are not counted as gaps. Saved by the tech lead.

## Part 1 · Screens 01, 02, 03, 13–17 (configurator, form builder, shared components)

| Screen | Verdict |
|---|---|
| 01 Main (EMR configurator) | Minor gaps |
| 02 AddDepartment | Minor gaps (most differences accepted) |
| 03 FormBuilder | Minor gaps |
| 13 EmrList | Minor gaps |
| 14 BodyMap | Minor gaps |
| 15 TreatmentCourse | **Major gaps** (course page reachable only from the dental plan) |
| 16 VisitHistory | Minor gaps (package screenshot shows the design tool's menu, not the component; judged from source) |
| 17 CasePaper | Minor gaps |

Overall: labels, copy, field order, value lists and rules come from the design source nearly word for word; the seed migrations (V2026_12_08_1020/1030) reproduce the 15 departments, section lists and field arrays.

### 01 Main — built at `/app/config/specialty` (`config/emr-config/**`); backend EmrDepartmentService, EmrLayoutRules, EmrLayouts
Matches: header and subtitle, chip states, Save draft / Publish / Reset with Undo, tabs and the four visit types, department column, part headers and counts, section rows (up/down, tags, move between parts, Required/Optional, Show), section settings (five switches, fields list, who can fill), toasts. Added on purpose: owner-only edit, leave guard, optimistic locking, hide-lock for Allergies and PCPNDT; Required cannot be on for a hidden section (BR45-10).
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G1-1 | Minor | Part header repeats the number ("1 · Assessment · Dental") | Main.dc.html:108,504 | sections-column.component.html:6 |
| G1-2 | Minor | Preview does not draw the tooth chart (or ANC); shows stale placeholder "The special editor for this section comes in a later step." | Main.dc.html:169-185,526-533 | doctor-preview.component.html:12-14, .ts:13 |
| G1-3 | Minor | No "Open full doctor screen →" link in Preview | Main.dc.html:155-157,395 | doctor-preview.component.html:30-40 |
| G1-4 | Minor | Preview cards never span two columns (design: dental_chart, rx, anc, rom, vitals, tx_plan, refraction, growth, immunization) | Main.dc.html:396,520 | doctor-preview.component.scss |
| G1-5 | Minor | Extra "Missing" pill on required preview cards | Main.dc.html:164-168 | doctor-preview.component.html:8-10 |
| G1-6 | Minor | "+ Add field" button and field drag handle absent (story says not this phase; no DEC) | Main.dc.html:225,230 | section-settings.component.html:36-51 |
| G1-6b | Minor | "Base layout for this department" caption missing; visit-type buttons change nothing yet the note reads "Dental · Follow-up" | Main.dc.html:96,594 | emr-config.component.html:111-120; section-settings.component.html:70-73 |
| G1-7 | Minor | No breadcrumb; "Clinic" select is plain text; extra "Clinic setup" title | Main.dc.html:23,28-30 | emr-config.component.html:2-11 |
| G1-8 | Minor | Extra "v<n>" under department status | Main.dc.html:52-59 | department-column.component.html:40 |
| G1-9 | Minor | Count pill style; "*" before Required | Main.dc.html:112,132 | sections-column.component.html:9,72-74 |
| G1-10 | Minor | Save draft / Publish disabled while loading or saving (design always clickable, answers with a toast) | Main.dc.html:33-34 | emr-config.component.html:29,38 |
| G1-11 | Info | Column widths 260/1fr/300 inside the Clinic setup shell; middle column narrow at 1440 — runtime check | Main.dc.html:41,70,202 | emr-config.component.scss:36-41,101-114 |

### 02 AddDepartment — routed page `/app/config/specialty/add-department` (DEC-041-7)
Matches: title, three steps, Start blank / Copy tiles, 12 template cards, Template preview with "Turned off by default…", footer with print-template checkbox, Cancel, Next.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G2-1 | Minor | No template preselected (design opens on Physiotherapy) | AddDepartment.dc.html:67-68,159-165 | add-department.page.html:121-123 |
| G2-2 | Minor | Preview names lack the design's decorations ("Pain assessment (VAS / NPRS)") | AddDepartment.dc.html:150 | add-department.logic.ts:31-33 |
| G2-3 | Minor | Copy tile disabled until a saved department exists (DEC-041-6; reason text invented) | AddDepartment.dc.html:50-53 | add-department.page.html:55-70 |
| G2-4 | Info | Steps 2 and 3 not drawn in the design; built | — | add-department.page.html:146-237 |

### 03 FormBuilder — `/app/config/forms` and `/app/config/forms/:formKey` (`config/forms/**`); backend EmrFormService, 6 starters
Matches: header, breadcrumb, modes, palette groups and order, 6-column canvas, tool bar with Undo, field and form settings, Preview and A4/A5 print. Extras: Approve, Send back, Retire.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G3-1 | Minor | Meta line omits "· filled by doctor, nurse" | FormBuilder.dc.html:621 | form-builder.store.ts:101-113 |
| G3-2 | Minor | "Tooth chart" field type still "Later" though the dental chart now exists (DEC-043-12 reason stale) | FormBuilder.dc.html:157,330 | form-builder.logic.ts:100-107 |
| G3-3 | Minor | "Standard code" placeholder drops ", for reports and ABDM" (consistent with DEC-049, unrecorded) | FormBuilder.dc.html:297 | field-settings.component.html:188 |
| G3-4 | Minor | Save draft disabled when nothing changed; Preview save disabled (DEC-042 H9) | FormBuilder.dc.html:265,288 | form-builder.page.html:43,206 |
| G3-5 | Minor | Invented hint texts (Teleconsult, WhatsApp link) | FormBuilder.dc.html:577-580 | form-settings.component.html:232,244 |
| G3-6 | Info | "Medical director" mapped to "owner" (OQ-45-13) | FormBuilder.dc.html:581 | form-settings.component.html:262 |

### 13 EmrList — `emr/components/emr-list.component.*`
Matches: title, count, Chips/Table (DEC-043-1), add panel, chips with code and attribute, table columns, "Your most used", Undo, per-kind attributes.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G13-1 | Minor | Count reads "2 active" (design "2") | EmrList.dc.html:259 | emr-list.logic.ts:142-146 |
| G13-2 | Minor | Chip attribute is a native select (design: tap-to-cycle pill) | EmrList.dc.html:68 | emr-list.component.html:109-118 |
| G13-3 | Minor | Diagnosis code as an extra table column | EmrList.dc.html:90,102 | emr-list.component.html:139-141,190-192 |
| G13-4 | Minor | Department accent colour not applied (all `--pri`) | EmrList.dc.html props | emr-list.component.scss |
| G13-5 | Minor | Star vs bolt icon; no tab icons; added print-language, "New to list", price (SAH-56/57) | EmrList.dc.html:34-36,112 | emr-list.component.html:8-27,228 |

### 14 BodyMap — `emr/components/body-map.component.*`
Matches: tabs, skin tones, front/back/turn, zoom, pin form lists and defaults, MLC auto-flag and toast, findings register, idle hint; keyboard extras.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G14-1 | Minor | MLC banner omits "Photos are kept with the record." (constant unused) | BodyMap.dc.html ~134 | body-map.logic.ts:50-52; body-map.component.ts:83 |
| G14-2 | Minor | Register lowercases the finding | BodyMap.dc.html:306 | body-map.component.html:227 |
| G14-3 | Info | Silhouette vs screenshot — runtime check | 14-BodyMap.png | body-geometry.ts |

### 15 TreatmentCourse — `/app/courses/:courseId` (`emr/course/**`); backend /emr/courses, /billing/courses
Matches: title, chips, tabs, four stat tiles, step strip, sitting cards incl. IPD, Table tab, Payments tab (types, advance first, ₹ 500 steps, modes, receipts), legend; money from the server.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G15-1 | **Major** | Course page reachable only from the Dental plan; no entry from Gynae (pregnancy), Physio (package), Surgery, Oncology (chemo cycles), billing or the patient page, though the backend returns courseId/pregnancyCourseId. DEC-043-11 deferred entries to phase 4, which only did dental. | SCREENS.md rows 6,7,9,10,11 | dental-plan.component.ts:149-154 (only link) |
| G15-2 | Accepted (DEC-043-11) | No Mark done / Patient did not come / Rebook / Remove; Today card has no primary action | TreatmentCourse.dc.html:78-85 | course.page.html:111-124 |
| G15-3 | Minor | Missed/rebook states not in the model (only Planned/Today/Done) | TreatmentCourse.dc.html:78-85 | course.logic.ts STATUS_WORD |
| G15-4 | Minor | Header sub-line wording ("estimate signed by patient") depends on the note | TreatmentCourse.dc.html:130 | course.page.html:17-19 |
| G15-5 | Minor | Added "Courses of this patient" switcher and account error banner | — | course.page.html:31-46 |

### 16 VisitHistory — `emr/history/past-visits-panel.component.*` (screenshot unusable)
Matches: heading and counts, key facts, All/OPD/IPD, search, visit list, details, results, actions, empty states.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G16-1 | Minor | Medicines heading always "Medicines" (design: "Discharge medicines", "Take-home medicines") | VisitHistory.dc.html:171 | past-visits-panel.component.html:114 |
| G16-2 | Minor | No timeline dot and line (card with coloured border) | VisitHistory.dc.html:168 | past-visits-panel.component.scss:109-115 |
| G16-3 | Minor | Abnormal results add "· Abnormal" text | VisitHistory.dc.html:167 | past-visits-panel.component.html:107 |
| G16-4 | Info | Details start closed (DEC-043-7); load more and states added | — | past-visits-panel.component.html:57-70,166-170 |

### 17 CasePaper — `emr/paper/case-paper-panel.component.*`
Matches: tool bar (progress, Live, A4/A5, three languages, Hide empty, Share off per DEC-049, Print), letterhead, doctor and patient strips, 10 headings in three languages, medicines grid in capitals, footer text, signature; live update; addendum and allergy-alert extras.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G17-1 | Minor | "8 of 10 filled" plus an added "Live case paper" heading (design "8 of 10 sections filled", no heading) | CasePaper.dc.html:131 | case-paper-panel.component.html:4,6 |
| G17-2 | Minor | Progress bar never turns green at ≥ 7 sections | CasePaper.dc.html:132 | case-paper-panel.component.scss:43-52 |
| G17-3 | Minor | No "Sent to printer…" confirmation | CasePaper.dc.html:141 | case-paper-panel.component.ts:144-153 |
| G17-4 | Minor | Department accent not applied | CasePaper.dc.html props | case-paper-panel.component.scss |
| G17-5 | Info | Marathi/Hindi headings unreviewed; Caveat sample not needed | CasePaper.dc.html:55 | case-paper.logic.ts:20-27 |

### Cross-cutting
- **G-X1 (Major, runtime check):** SCREENS.md says every doctor screen is 50/50 (EMR left, history + case paper right). Built: `record/emr-record.page.scss:10` is `220px | 1fr | minmax(320px, 400px)` — a 220 px step nav plus a right column capped at 400 px; the case paper (designed 720 px) gets about 400 px at 1920. Not covered by any DEC.
- **G-X2 (Minor):** department accent colours used only for badges; components use the app theme though the server sends the department colour.
- **G-X3 (Info):** configurator nested in Clinic setup → Specialty template (DEC-039); no direct "EMR configuration" menu entry.

Runtime checks needed: Main at 1440/1280; doctor screen at 1920 (G-X1); body-map silhouette; any course entry besides dental (G15-1); form builder drag and drop.

## Part 2 · Screens 04–07 (General Medicine, Dental, Obstetrics and Gynaecology)

| Screen | Verdict |
|---|---|
| 04 DoctorGeneral (step 1) | **Major gaps** |
| 05 GeneralTreatment (step 2) | Minor gaps |
| 06 DoctorDental | **Major gaps** (+ known 500 bug) |
| 07 DoctorGynae | **Major gaps** |

**Configuration rule: mostly met.** The page renders `record.layout.sections` (name, part, order, required, who can fill); hidden sections never reach the page; nav, badges, "Still needed" and Done come from the layout. Fixed in code per profile: editor anchors, finish labels/titles, Quick tools (gm only), mode hiding (`SpecialtyProfiles.java:80`), GM summary rows (`finish.logic.ts:~146`). One configuration for all visit types (accepted).

### 04 DoctorGeneral — `/app/emr/:recordId` (`record/**`, `specialty/gm/**`, shared components)
Matches: header strip, two part tabs with badges and exact sub-labels, left nav, recap and Continue bar, right tabs (Past visits / Case paper / Quick tools), sticky footer, Vitals Today/History/Trend, general signs, systemic exam texts, "Check before completing" dialog with exact rows and buttons, Quick tools templates and BP/Sugar trend.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| G1 | **Major** | 50/50 layout not built (right panel ≤ 400 px) — runtime check at 1920 | DoctorGeneral.dc.html:50,65,322 | emr-record.page.scss:10 |
| G2 | **Major, needs human** | "Local examination · body map" missing: the GM starter has no BODY_MAP section, so the dialog's "Local findings" is always None (Main.dc.html's GM list also lacks it — the design contradicts itself) | DoctorGeneral.dc.html:225-227 | V2026_12_08_1030:42-61 |
| G3 | **Major** | General examination required in the design; starter has `vitals` optional (counts and footer differ) | :473-475 | V2026_12_08_1030:43 |
| G4 | **Major, runtime check** | "Prescription print format" (`rxformat`) is shown and required in every starter, its Header renders as a free-text box and must be filled — a doctor probably cannot complete a visit without typing into it; not on any drawn screen | — | V2026_12_08_1030:61,85; V2026_12_08_1020:181 |
| G5 | Minor (accepted OQ-45-27) | "Speak" reads "Voice"; default mode Type not Write | — | gm-editors.ts:108-110 |
| G6 | Minor | BP one stepper and record only when all six filled with a "Ready: …" summary; built allows partial entries, seven steppers | — | gm-editors.ts:145,233 |
| G7 | Minor | Card titles "Vitals", "General & systemic examination" (library names) vs design's three cards | — | config-driven |
| G8 | Minor | Trend tab: six mini charts vs built BP/Sugar chart | :195-208 | gm-editors.ts:195 |
| G9 | Minor | Nav badge counts all sections, not required ones | — | emr-record.page.html:75 |
| G10 | Minor | Continue bar and recap wording | — | emr-record.page.html:139; .ts:180 |
| G11 | Minor | Extra card chrome (chevron, pill, "Can be filled by") from SAH-47-3 | — | section-card.component.html |
| G12 | Accepted | ABHA (DEC-049), Back (DEC-041) | — | emr-header.component.html |

### 05 GeneralTreatment — same page, Treatment part
Matches: recap, Diagnosis/Investigations lists, prescription table and controls, allergy alert, follow-up chips, advice and print language, procedures empty text, finish.
- T1 Minor: "Other procedures & services" hidden by default in the GM starter (Main says off; DoctorGeneral shows it).
- T2 Minor: one "Advice | Follow-up" card vs two sections in a different order.
- T3 Accepted: SMS reminder text (OQ-45-27).
- T4: see G4 (rxformat).

### 06 DoctorDental — tooth chart, plan, estimate, done card, lab work, finish dialog
Matches: chart / 3D / tooth detail views and hints, conditions, surfaces, presets, plan table and status flow, "Complete & send to billing", "Send to billing?" dialog, services lines, estimate total.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| D1 | **Major (bug)** | Sign returns 500 when the plan has a course and open rows: option key `opd_sitting` lowercase vs check `^[A-Z]…` (reproduced by EmrDentalSendToBillingIT) | DoctorDental.dc.html:394 | DentalFinishContributor.java:249 |
| D2 | **Major, needs human** | Six drawn Assessment cards not built and in no story: "Medical history and alerts" (review button, alert banners: penicillin, tobacco, bleeding risk), "Dental history and habits", "Pain", chip-style extra-/intra-oral, "Radiographs and photos"; generic library sections render instead | :50-97 | V2026_12_08_1030:62-85 |
| D3 | **Major** | Treatment course card not embedded (button + link to the course page only) | :318-321 | dental-plan.component.ts ~145-156 |
| D4 | Minor | No left nav or parts drawn on Dental; shared frame used | — | — |
| D5 | Minor | Chart comes after the prescription in the seed order | — | V2026_12_08_1030:62-85 |
| D6 | Minor | Per-tooth history placeholder | — | tooth-chart.component.ts:254-255 |
| D7 | Minor | Tooth detail three-column layout | — | — |
| D8 | Minor | Footer: Print estimate, "n procedure(s) done today will be billed", allergy blocker text | — | dental-plan.component.ts:138; case-paper.logic.ts:328 |
| D9 | Minor | Header sub-line "RCT on 36 in progress, sitting 2 of 3" | — | — |
| D10 | Accepted | Shared prescription editor (SAH-48) | — | — |
| D11 | Trivial | 3D slider ranges | — | tooth-chart.component.ts |

### 07 DoctorGynae — ANC check, Form F, gynae exam, mode switch, finish dialog
Matches: mode switch and hint, GA/EDD/obstetric score/days, timeline, scan tiles, six steppers and notes, alerts and orders, Due now, Form F, supplements, ANC visits table, gynae exam, dialog lines.
| # | Sev | Finding | Design | Code |
|---|---|---|---|---|
| O1 | **Major** | "Pregnancy care · ANC course" card not built; an ANC course cannot be started from the screen | DoctorGynae.dc.html:157-159 | obgyn-editors (none) |
| O2 | **Major** (layout) | Quick tools tab (Due now, Form F, Supplements, Quick orders) absent for O&G; cards sit in the ANC section | :166-203,234-241 | specialty-registry.ts:70 |
| O3 | **Major** | Gynae mode requires menstrual history; starter has it optional | — | V2026_12_08_1030:100 |
| O4 | Minor | Menstrual chip rows and contraception tiles; built generic fields | — | V2026_12_08_1020:238 |
| O5 | Minor | Footer "Print ANC card" / "Print Rx" and warning status line not built | — | — |
| O6 | Minor | No parts or left nav drawn; shared frame used | — | — |
| O7 | Minor | Risk tags, husband name and blood group in the header | — | obgyn-editors.ts ~44-52 |
| O8 | Minor | Dialog shows section rows before the three bullets | — | — |

## Part 3 · Screens 08–12 (Paediatrics, Physiotherapy, Surgery, Oncology)

| Screen | Verdict |
|---|---|
| 08 Paediatrics | Minor gaps |
| 09 Physiotherapy | **Major gaps** |
| 10 General Surgery | **Major gaps** |
| 11 Oncology step 1 | **Major gaps** |
| 12 Oncology step 2 (order sheet) | Minor gaps (course panel shared Major) |

Configuration: met with a limit — editors, mode hide-lists, footer labels and finish texts are fixed per starter key (`SpecialtyProfiles.java:22-35,84-86`; `specialty.logic.ts:379-420`; `specialty-registry.ts:67-96`); a clinic-made blank department gets no specialty editors; default required flags come from the Main tables, not the doctor screens.

Cross-cutting X1 (Major, 50/50, = G-X1/G1); X2 section nav on every profile (only General draws it); X3 per-profile step subtitles and "n of m done" badges, Paeds/Physio drawn without steps; X4 Quick tools for Paeds and Physio missing; X5 profile-specific ready footers missing; X6 Paeds WhatsApp checkbox wording; X7 library card titles vs screen copy.

### 08 Paediatrics — Minor gaps
P1 (Minor, **needs human**, clinical): "Mark all due as given" also marks the overdue vaccine in the design ("4 vaccines recorded"); built marks due-today only (SAH-62-2 by the analyst) — `DoctorPaeds.dc.html:290` vs `paeds-editors.ts:303-306`. P2 footer summary and footer print button; P3 dose "Give" panel styling; P4 growth and milestones side by side; P5 feeding in Quick tools; P6 vaccine chip position.

### 09 Physiotherapy — Major gaps
P-1 (**Major**) embedded course/package panel (Visits/Table/Payments, totals, S1–S10, session actions) not on the screen, no link to the course page (`DoctorPhysio.dc.html:157-159`; `physio-editors.ts:720-724`). P-2 (**Major**) pain site body figure → text input (`:83-96`; `physio-editors.ts:64-66`). Minor: P-3 trend sparkline, P-4 strength chips in ROM card, P-5 Quick tools location, P-6 session note and report placement, P-7 10-segment package bar, P-8 Complete enabled-but-blocked (runtime), P-9 editable ROM Left.

### 10 General Surgery — Major gaps
G-S1 (**Major**) "Local examination · body map" missing (`DoctorSurgery.dc.html:77`). G-S2 (**Major**) "Surgery episode" course panel not embedded (`:135`). G-S3 (**Major, needs human**) design requires complaints, pre-op work-up, plan/OT booking, consent ("Still needed: complaints, diagnosis, OT booking"); starter requires complaints, diagnosis, rx, rxformat (`V2026_12_08_1030:338-363`). Minor: G-S4 pre-op work-up own card, G-S5 end bar and recap, G-S6 header chips, G-S7 pre-auth "send" vs "mark sent", G-S8 OT date chips.

### 11 Oncology step 1 — Major gaps
O-1 (**Major**) cancer-journey snapshot band and watch chips (doxorubicin 180/450 mg/m², MJPJAY balance) not built (`DoctorOncology.dc.html:34-35,41-49`). O-2 (**Major**) exam Body map tab missing (`:149-151`; `onco-assessment.ts:184`). Minor: O-3 vitals inside exam card, O-4 order with ECOG first and no sticky steps bar, O-5 "Other complaints (optional)" vs required chief complaints, O-6 lesion trend and quick-add, O-7 lab trend, O-8 badges/subtitles.

### 12 Oncology step 2 — Minor gaps (order sheet matches closely)
O2-1 (**Major**, shared) chemo course panel not embedded (`OncologyTreatment.dc.html:344`). Minor: O2-2 bed and intent sub-line, O2-3 stage chips (runtime), O2-4 calendar language chips and "printed in" note, O2-5 take-home row styling, O2-6 footer wording, O2-7 extra needed controls.

## Summary for the human (tech lead)

| # | Screen | Verdict |
|---|---|---|
| 01 | Main (configurator) | Minor gaps |
| 02 | AddDepartment | Minor gaps |
| 03 | FormBuilder | Minor gaps |
| 04 | DoctorGeneral | Major gaps |
| 05 | GeneralTreatment | Minor gaps |
| 06 | DoctorDental | Major gaps + 500 bug |
| 07 | DoctorGynae | Major gaps |
| 08 | DoctorPaeds | Minor gaps |
| 09 | DoctorPhysio | Major gaps |
| 10 | DoctorSurgery | Major gaps |
| 11 | DoctorOncology | Major gaps |
| 12 | OncologyTreatment | Minor gaps |
| 13 | EmrList | Minor gaps |
| 14 | BodyMap | Minor gaps |
| 15 | TreatmentCourse | Major gaps (entry points) |
| 16 | VisitHistory | Minor gaps (screenshot to re-export) |
| 17 | CasePaper | Minor gaps |

All 17 screens are built; none is missing. The Major gaps group into a few themes:
1. **Bug:** Dental sign 500 (D1) — one-token fix.
2. **Possible blocker:** required "Prescription print format" header on every department (G4) — runtime check.
3. **Layout:** 50/50 doctor screen not built (right column ≤ 400 px); section nav/steps/Quick tools per profile differ (G-X1, X2–X4, O2).
4. **Treatment course not embedded** in Dental, Gynae (ANC), Physio, Surgery, Oncology; only Dental can open the course page (G15-1, D3, O1, P-1, G-S2, O2-1).
5. **Body map missing** where drawn: General local exam, Surgery, Oncology, Physio pain site (G2, G-S1, O-2, P-2).
6. **Default required flags** differ between the Main mockup tables and the doctor screens (G3, O3, G-S3, O-5).
7. **Not built, not in any story:** Dental assessment cards incl. Medical history and alerts (D2); Oncology journey strip and watch chips (O-1).
8. **Clinical decision:** "Mark all due as given" including overdue vaccines (P1).
