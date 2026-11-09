# SAH-29 · IPD packages (read-only slice)
Module: ipd
Mockup screen: Clinic setup → Admission and deposits — the panel "Packages" (`cfgIpAdm`, `pkgs`) and the package lines of the running bill (`ipLines`, `a.pkg`) in the patient sheet (`openIp`)
Source: tech-lead backlog (IPD part 8); decided by the human on 9 Nov 2026 (DEC-030, `docs/decisions/DEC-030-sah29-read-only-packages.md`)

Decided 9 Nov 2026: read-only slice (DEC-030). Stories: `docs/stories/SAH-29.md` (AC1 to AC6). Design: `docs/architecture/SAH-29-ipd-packages.md`.

## Problem
A hospital owner wants fixed-price packages (for example Appendicectomy, 3 days) so the family knows the price in advance and the running bill matches it. The mockup shows packages in only two places: a read-only list in Clinic setup, and the way the running bill treats an admission that has a package. It has no screen to create, edit or remove a package and none to attach a package to an admission.

## Who uses it (roles) and in which setting
- Hospital ward and front desk, and the owner in Clinic setup.
- Read the package list and an admission's package: owner, front desk, nurse, doctor. Any other role: refused.
- Billing, pharmacist and visiting doctor: no access to the list (403), as for the other Admission and deposits reads.

## In scope / Out of scope
In scope (the read-only slice):
- The Packages panel in Clinic setup → Admission and deposits, under Bed transfer rules: a read-only list of the clinic's active packages.
- The running bill of an admission that has a package, exactly as the mockup `ipLines` builds it, used everywhere the bill is used (patient sheet, Admitted list, Deposit low pill, Deposit alerts tile, board flag, final bill at discharge).
- The admission keeps its own copy of the package name, price and included days.
- The discharge summary template defaults to "Surgical" for a package admission (unless Maternity or Day-care), as SAH-27 already specifies.

Out of scope, future work (each needs a mockup design first):
- OQ-29-1: create, edit and switch off packages.
- OQ-29-2: attach a package to an admission.
- The audit of package changes (no app change exists in this slice).
- Package inclusions and exclusions lists; price by bed category; insurance or corporate rate cards; surgeon fee split; package change after attach; package refund or conversion to a normal bill; packages in the Billing tariff (SAH-18).

Scope note: packages are set by hand in the database in `sahaj_dev` and `sahaj_test` only. SAH-29 is not for a live clinic until create and attach exist (P1).

## Business rules (numbered)
1. The Packages panel lists only active packages of the clinic, in the order they were created. Each row is drawn exactly as the mockup shows; no new text is added, including for an empty list (AC1).
2. An admission keeps its own copy of the package name, price and included days. Later changes to a package never change an attached bill (AC2).
3. With a package, the running bill lines are, in order: the package line (qty 1, at the package price), "Bed · <current category> beyond the package" for bill days beyond max(1, included days) (dropped when 0), then each extra charge named "<service> · not in package", priced as before. No "Doctor visits" line, no "Nursing care" line, no transfer segment lines (AC3).
4. One bill function serves the sheet, the Admitted list, the Deposit low pill, the Deposit alerts tile, the board flag and the final bill. The package price counts from the first day. The heading note of the Running bill panel and the Summary strip do not change (AC4).
5. After a transfer, the bed difference is not billed; days beyond the package use the bed the patient is in now, and extra charges are re-priced at that bed's multiplier, as the mockup does (AC5; OQ-26-4, OQ-29-5).
6. Roles: owner, front desk, nurse, doctor may read; others get 403. Module Off answers 403 `module-off`; Read-only keeps everything readable. Another clinic's data is never visible (404 for an admission, never listed for packages). Money is integer paise; package prices are whole rupees (AC6; P3).
7. The app cannot create, change or clear a package or an admission's package copy in this slice (AC6).

## Patient-safety and privacy points
- The bill is shown to families: a wrong package line is a billing error. The architect recommends that a package copy, once set, cannot be changed or cleared and cannot be set on a discharged admission, to protect a bill already shown and a frozen final bill.
- Package name and price are not patient data. The bill sits next to patient data, so logs hold ids and counts only; never names, mobile numbers, ABHA numbers or clinical notes.
- Package prices are commercial data of the clinic. Nothing is seeded; the mockup examples are demo data, not defaults.
- Hand edits in the database are outside the app audit trail (P1), so no live clinic uses packages in this slice.

## Plan or entitlement (which plan/module unlocks it)
IPD module. The panel and the bill are available when IPD is Trial, On or Read-only; module Off answers 403 `module-off`.

## Open questions for the human
Future work (not in this slice):
- OQ-29-1: who creates and changes packages, and where? Proposed for later: owner, on the same panel.
- OQ-29-2: how is a package attached to an admission, and by whom? Proposed for later: a "Package" select in the Admit sheet and a one-time "Apply package" on the admission sheet for owner and front desk, before any extra charge.

Open points with defaults applied by DEC-030 (reversible):
- P1 (go-live): hand-set packages only in `sahaj_dev` and `sahaj_test`; not for a live clinic until create and attach are built.
- OQ-29-3: empty panel shows the empty list with no new text.
- OQ-29-4 (billing): keep the mockup text literally ("for 0 days" for day-care, "1 days" for one day). A one-line change if the human prefers "for the day".
- OQ-29-5 (billing) and OQ-26-4: re-pricing after a transfer and no bed-difference charge, as the mockup. The human should confirm the billing behaviour before go-live.
- P2: only active packages are listed.
- P3: package prices are whole rupees.
