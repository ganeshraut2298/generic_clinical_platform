# DEC-053 · SAH-75 contract (ADR-026, opd.yaml 0.7.0, billing.yaml 0.4.0, ipd.yaml 0.3.2) and its defaults H1–H18

Date: 14 Nov 2026. Status: ANSWERED 14 Nov 2026 (human, chat). Raised by: tech lead from the solution-architect's hand-back (logged in docs/handoffs/SAH-75.md).
Blocked: db-engineer, java-developer, angular-developer for SAH-75 (contract stage).
Checks: YAML parses (opd 104 paths, billing 13, ipd 60); validate docs fails only on the known ADM-2.md.

## Facts
- No new module, library or external service. New tenant table `opd.emr_cancer_journey` (append-only, RLS); new columns on `treatment_course` and `treatment_course_sitting` (no backfill of tenant rows); global seed changes by forward migrations with a new starter layout version (version 1 untouched).
- Changed rule: a department's section set = latest version ∪ current starter ∪ live forms, so published clinics see the new starter sections as hidden rows they can switch on (H13).

## Defaults needing the human (from ADR-026)
- H4 scheme balance chip ("MJPJAY · ₹ 38,000 left"): no data source; hidden; a scheme ledger in billing would be a new story.
- H5 Gynae header husband name and blood group: need new patient personal-data fields; left out.
- H6 Cancer journey form lists (stage groups, intent, setting, line 1–10, board date ≤ 365 days ahead): ours, not clinically reviewed.
- H1 Remove renumbers sittings on screen; printed receipts keep the old number. H2 IPD-billed sitting text. H3 TODAY includes an unlinked OPD sitting planned today. H10 seed proof by static + catalogue ITs and a read-only checksum script the human runs on sahaj_dev. H13 union rule. H14 blank departments start with Vitals required and Procedures shown. H7–H9, H11, H12, H15–H18 minor (see ADR-026 table).

## Recommendation
Approve the contract with all defaults; H4 and H5 stay out (new stories when wanted).

## Answer (human, chat, 14 Nov 2026)
**Contract approved with all defaults** (ADR-026, opd.yaml 0.7.0, billing.yaml 0.4.0, ipd.yaml 0.3.2; H1-H3, H6-H18 as written). **H4 scheme chip: hidden** (scheme ledger is a later billing story). **H5 husband name and blood group: left out** (patient fields are a later story). Recorded as a chat approval, not an approvals.md line.

## Addendum (tech lead, 14 Nov 2026): opd.yaml 0.7.1 follow-up (ADR-026 D12–D14)
Additive gaps found while building, inside the approved DEC-052 scope: D12 `EmrRecordHeader.allergyStatus` (RECORDED / NONE_RECORDED / NOT_RECORDED) from `patient.api` for the "No known allergy" chips; D13 Gynae menstrual (cycle, cycle length, flow incl. With clots, pain) and contraception method tiles (new option `hint` column, global seed only); D14 Oncology card order Side effects, Examination, Labs, ECOG. Defaults H19–H23 applied as written (H19 a none word typed at the front desk also counts; H21 LMP, menarche, Findings/Template/Attachments kept; H22/H23 published departments change at their next publish or reset). H20 is a safety fix, scheduled now: ipd's medicine allergy chip must treat every none word (incl. "No known allergy") as none, using the shared list. Listed for the human's confirmation; no new module, path or permission.
