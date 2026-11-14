# Screens in this design

Design: "EMR Configuration by Department — Smart Clinic"
Live canvas (visual reference): https://claude.ai/artifact/F3ig4YUSukQpdvTNRNJFDr

Each screen has three forms in this folder:

- `screens/NN-Name.png`: full-length screenshot at the designed width. Look at this first.
- `source/Name.dc.html`: the design source. Exact colours, spacing, copy, sample data and behaviour.
- `EMR-Configuration-Smart-Clinic.html`: every screen in one file. Open it in a browser and click through.

## Build order and what each screen is

| # | Screenshot | Source file | What it is | Width | Uses |
|---|---|---|---|---|---|
| 1 | 01-Main.png | Main.dc.html | EMR configurator: department, then sections, then fields. Visit types, required/optional, publish | 1440 | none |
| 2 | 02-AddDepartment.png | AddDepartment.dc.html | Dialog: add a department from a specialty template | 1080 | none |
| 3 | 03-FormBuilder.png | FormBuilder.dc.html | Form builder for clinic-defined forms | 1440 | none |
| 4 | 04-DoctorGeneral.png | DoctorGeneral.dc.html | Doctor screen, General Medicine, step 1 Assessment | 1920 | EmrList, BodyMap, VisitHistory, CasePaper |
| 5 | 05-GeneralTreatment.png | GeneralTreatment.dc.html | Same screen opened at step 2 Treatment | 1920 | DoctorGeneral |
| 6 | 06-DoctorDental.png | DoctorDental.dc.html | Doctor screen, Dental (tooth chart) | 1920 | EmrList, TreatmentCourse, VisitHistory, CasePaper |
| 7 | 07-DoctorGynae.png | DoctorGynae.dc.html | Doctor screen, Obstetrics and Gynaecology | 1920 | EmrList, TreatmentCourse, VisitHistory, CasePaper |
| 8 | 08-DoctorPaeds.png | DoctorPaeds.dc.html | Doctor screen, Paediatrics | 1920 | EmrList, VisitHistory, CasePaper |
| 9 | 09-DoctorPhysio.png | DoctorPhysio.dc.html | Therapist screen, Physiotherapy | 1920 | EmrList, TreatmentCourse, VisitHistory, CasePaper |
| 10 | 10-DoctorSurgery.png | DoctorSurgery.dc.html | Doctor screen, General Surgery | 1920 | EmrList, BodyMap, TreatmentCourse, VisitHistory, CasePaper |
| 11 | 11-DoctorOncology.png | DoctorOncology.dc.html | Medical Oncology, step 1 Assessment to decision | 1920 | EmrList, BodyMap, TreatmentCourse, VisitHistory, CasePaper |
| 12 | 12-OncologyTreatment.png | OncologyTreatment.dc.html | Medical Oncology, step 2 Treatment (order sheet) | 1920 | DoctorOncology |
| 13 | 13-EmrList.png | EmrList.dc.html | Shared: list entry for complaints, diagnosis, tests, services, advice | 900 | none |
| 14 | 14-BodyMap.png | BodyMap.dc.html | Shared: body map with pins (male, female, other) | 1000 | none |
| 15 | 15-TreatmentCourse.png | TreatmentCourse.dc.html | Shared: multi-visit course and payments (OPD and IPD) | 1000 | none |
| 16 | 16-VisitHistory.png | VisitHistory.dc.html | Shared: patient history (OPD and IPD) | 380 | none |
| 17 | 17-CasePaper.png | CasePaper.dc.html | Shared: live case paper | 720 | none |

Build the shared components (13 to 17) first. Every doctor screen is assembled from them.

Layout of every doctor screen: 50/50. EMR entry on the left, past visits and the live case paper on the right.

## How to read a `.dc.html` source file

These files use a small template runtime. Do not ship the runtime. Read the files as a specification.

- `<helmet>`: fonts and page-level CSS.
- `<x-dc> ... </x-dc>`: the markup. All styling is inline, so every colour, size and gap is exact.
- `{{name}}`: a value returned by `renderVals()` in the script block at the bottom.
- `<sc-for list="{{rows}}" as="r">`: repeat for each item.
- `<sc-if value="{{cond}}">`: show only when true.
- `<dc-import name="EmrList" kind="complaints" on-change="{{fn}}">`: another file used as a component, with inputs and outputs.
- `<script type="text/x-dc">`: `class Component extends DCLogic`. `this.state` and `setState` hold screen state, methods are the event handlers, `renderVals()` prepares what the template shows. The arrays at the top of the script are example data, not real data.

## Design tokens (most used values)

- Font: Inter (400, 500, 600, 700). Caveat is used only for the handwriting sample.
- Text: `#0F1C2E` primary, `#3E4C5E` secondary, `#5B6B7F` muted, `#9AA8B8` faint.
- Lines and surfaces: `#E3E8EE` border, `#C9D2DC` strong border, `#FFFFFF` card, `#FAFBFC` and `#F4F6F8` page, `#EEF1F5` chip.
- Status: `#146C43` on `#E3F5EC` (done), `#B42318` on `#FDECEA` (required or error), `#8A4B08` on `#FDF0DC` (warning).
- Department accents: General Medicine `#1D5FA8`, Dental `#0B6E69`, Obstetrics and Gynaecology `#A3366B`, Paediatrics `#B4471F`, Physiotherapy `#6D3FC0`, Surgery `#1E40AF`, Oncology `#9D174D`.
- Radius: 8px controls, 10 to 12px cards, 999px chips. Main buttons are 44px high.

## Note on versions

The source files and screenshots were taken from the design on 12 Nov 2026. Nine screens on the live canvas have small later edits (each file differs in size by under 140 bytes). If a screenshot and the live canvas disagree, the live canvas wins.
