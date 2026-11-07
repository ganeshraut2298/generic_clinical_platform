# SAH-35 · Medicine round on the Safety board (doses due or overdue across patients)
Module: ipd
Mockup screen: IPD → Safety (the "Safety" tab of Beds and admissions) — the tile "Doses due or overdue", the panel "Medicine round" (`ipSafety`, `doseRow`, `sfDoses`, `sfSlot`, `sfLate`) and the dose part of the tab number (`sfCount`)
Source: Human, "go ahead for further dev", 7 Nov 2026, through the tech lead. Deferred on purpose by SAH-32 (scope note) and SAH-33 (OQ-33-15, DEC-015).

## Problem
A nurse on a ward round can open one patient's Medicines (MAR) tab (SAH-32) and see that patient's doses. Nobody can see, in one place, which doses across all admitted patients are due now or were not given on time. The mockup puts this list on the Safety tab as "Medicine round", with a tile and a number on the tab, so that a late dose is noticed without opening every chart. Without it, SAH-32 records doses well but a missed or late dose is visible only to a nurse who happens to open that patient.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- Nurse (hospital ward, nurses' station and bedside tablet): sees the list, taps Give, records the dose with the bedside checks of SAH-32.
- Owner: sees the list and may give (same as SAH-32; in the mockup the owner is the demo doctor).
- Doctor: proposed read-only (open question OQ-35-1).
- Pharmacist, front desk, billing, visiting doctor: no access to this list.
- Setting: IPD ward, Beds and admissions → Safety tab.

## In scope / Out of scope
In scope:
- Tile "Doses due or overdue" with "N overdue".
- Panel "Medicine round": one row per due or late dose of an Active order of an admitted patient, overdue first, with a Give button that opens the existing Give sheet.
- The overdue-dose part of the number on the Safety tab, and the dose numbers in the counts-only refresh.
- Refresh on the server clock every 60 seconds and after every action.

Out of scope: a new way to record a dose (SAH-32 is reused unchanged, with all its checks); the card flag "Dose overdue" (patient card story, not built); "Emergency access" tile and panel (SAH-34, blocked); a "missed dose" record or follow-up at midnight; PRN and stat doses; any message, sound or call to a nurse or doctor (notifications are Later); ward-wise scoping of the nurse (OQ-33-2); search, filters or printing of the list; reports on late doses.

## Business rules (numbered)
1. The list holds a dose slot of today (IST) when: the order is Active (checked by the pharmacist), the patient is Admitted, no dose record exists for the slot, and the slot is Due or Late by the SAH-32 rule (from 60 minutes before to 60 minutes after the slot time is Due; more than 60 minutes after is Late; "N h late" rounds hours down). Pending, Rejected and Stopped orders, discharged patients, slots before the order started and slots later than 60 minutes ahead are not listed.
2. Order of rows: Late before Due; inside each, the earlier slot time first. Tie-break (not in the mockup): bed code, then medicine name.
3. A row shows the medicine, a High-alert pill when the medicine is high-alert, the patient name, bed code, the slot time, and either "N h late" or "Due now". The empty text is "No doses due right now."
4. The tile "Doses due or overdue" counts the listed doses (Due plus Late) and says "N overdue" (N = Late); the figure is red when N is above 0.
5. The number on the Safety tab is: open escalations (SAH-33) plus, for the owner, orders waiting for the pharmacist (SAH-31), plus Late doses only (as the mockup `sfCount`; Due doses are not counted there). Hidden at 0.
6. Every figure and time is computed by the server on the database clock in Asia/Kolkata; the browser clock is never used. A dose that becomes Due or Late appears or changes on the next refresh without anyone acting.
7. Give opens the Give sheet of SAH-32 for that order and slot. The wristband text, the patient's own name and date of birth confirmation, the second nurse and PIN for high-alert medicines, and every server check stay exactly as in SAH-32. The list is a shortcut to the right dose, never a shortcut around a check.
8. Who gives: NURSE and OWNER (as SAH-32). Who reads the list: OWNER, NURSE, DOCTOR (DOCTOR without a Give button; OQ-35-1). Any other role: no list, API 403.
9. A dose already recorded by someone else is refused (SAH-32 409 `dose-recorded`); two nurses cannot both record the same dose.
10. A nurse sees every admitted patient until wards are assigned (OQ-33-2).
11. IPD Read-only: the list can be read; recording a dose is refused as in SAH-32, so Give is not offered. IPD Off: 403 `module-off`.

## Patient-safety and privacy points
- Choosing a dose from a cross-patient list raises the risk of giving it to the wrong patient. The Give sheet must show the patient identity bar (name, UHID, age, sex, bed, allergy) and keep the wristband and name/date-of-birth steps. No "Scan wristband" shortcut (SAH-32, OQ-32-1).
- A dose that nobody records by midnight leaves the list silently (SAH-32 refuses recording yesterday's slots). This is a clinical gap and is open question OQ-35-2.
- No message reaches anyone in phase 1. The screen must not say a nurse or doctor was alerted.
- The list holds patient name, bed and medicine name: shown on screen to OWNER, DOCTOR, NURSE only; never in logs, never in the counts-only refresh, never in a toast seen by another user.
- Tenant isolation by RLS; another clinic's order or admission answers 404.

## Plan or entitlement (which plan/module unlocks it)
IPD module (Trial, On, Read-only). Off answers 403 `module-off`. No separate plan switch.

## Open questions for the human
See docs/stories/SAH-35.md section 6 (OQ-35-1 to OQ-35-9, each with a proposed safer answer). Those that truly need the human or a doctor: OQ-35-1 (can the doctor see the list), OQ-35-2 (dose nobody records by midnight), OQ-35-3 (tab number counts late doses only), OQ-35-8 (recording in Read-only IPD). The others proceed on the proposal.
