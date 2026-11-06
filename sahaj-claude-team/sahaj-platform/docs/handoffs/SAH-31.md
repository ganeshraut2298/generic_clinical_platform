# SAH-31 · IPD medicine orders, safety checks and pharmacist check

Branch: feature/SAH-13-appointments-workspace (all IPD work stays on this branch until the human merges).
Mockup screen: EMR sheet; Nursing sheet (context); IPD → Safety (Pharmacist check panel).

## Stage 1 · business-analyst (done earlier): docs/stories/SAH-31.md

## Stage 2 · solution-architect (6 Nov 2026) → db-engineer
Files: docs/architecture/adr/ADR-009-ipd-medicine-orders-safety-checks.md (PROPOSED); docs/api/ipd.yaml (new paths /admissions/{id}/emr, /admissions/{id}/medicine-orders, /medicine-orders/{id}/stop|verify|reject, /pharmacist-checks, /pharmacist-checks/summary; new schemas); docs/api/platform.yaml (menu note for PHARMACIST).
YAML check: all 7 files OK (tech lead ran it).
Key decisions (DEC-014 style, safer direction): lifecycle Pharmacist check → Active | Rejected | Stopped; order/stop OWNER+DOCTOR; verify/reject PHARMACIST+OWNER, ordering person can never verify (DB check); one rule class MedicineSafetyCheck + 22-char checkToken; hard stop/needs-reason are 200 answers with an `outcome`; client-key idempotency; one lock (admission row, for no key update); catalogue tables ipd.drug / ipd.drug_interaction seeded from the mockup (14 medicines, 3 interactions); read-only clinic still allows stop/reject/open EMR; no billing link; new patient.api port PatientClinicalPort; no clinical free text in logs/events.
Deviations from the mockup: 16 listed in ADR-009 and in the architect's handoff (allergy chip red on any keyword, "Allergies not recorded" chip, EMR button on admission sheet, no Results block, blocked-event wording without allergy text, verifier = signed-in user read-only, personal PIN, no "is notified" text, PHARMACIST page, rejected reason shown, absolute order time, paged pharmacist list, changed-findings rule, read-only allowances).
Human decisions: DEC-017 (PIN = personal staff PIN; pharmacist = OWNER + PHARMACIST). Open for go-live: catalogue sign-off (OQ-31-1), release SAH-31 with SAH-32.
Missing approval lines in docs/approvals.md: SAH-31 stories and contract (human).
