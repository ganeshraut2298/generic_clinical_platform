# DEC-044 · EMR phase 4 (ADR-023 specialty screens) and the Today total

Date: 12 Nov 2026. Status: ANSWERED 12 Nov 2026 (human, chat). Raised by: tech lead (ADR-023, opd.yaml 0.5.0; billing open point from the java-developer's phase 3 part 2).

## Answer (human, chat)
1. **ADR-023 H1-H19: all defaults accepted except H8.** (Oncology examination stored in `onc_bsa`; Oncology footer items always listed; tenth chemo send check "line ready for vesicant" for vesicant protocols; SELF_PAY passes the scheme/TPA check; global read-only seed of the 4 drawn chemo protocols; checker confirm step and list as designed; immunisation windows as read by the analyst; Form F only from ANC scan orders; dental plan → course sittings as designed; "Save as visit template" kept; IPD off → no chemo cycle planning and no OT course sitting; dental Child tab under 13; specialty screens on OPD records only; tariff match by exact name; input sanity limits; °F in General Medicine and °C in Oncology as drawn.) Demo sample rates (B-2) are not seeded by migration.
2. **H8 (patient safety): hide the length and head-circumference centile notes** until real WHO tables exist (later story); show the measured values and the weight chart as drawn.
3. **Today page "collected" includes course payments**, so it matches day closing and revenue (changes SAH-15 behaviour; java-developer).
Under the human's pre-approval (DEC-039); not lines in docs/approvals.md.

## Addendum (12 Nov 2026): ADR-023 D13 follow-up (opd.yaml 0.5.1, platform.yaml 0.1.1)
Contract gaps found by the angular-developer were added because the mockup wins (DEC-039): GM "General" systemic line, oncology "Spine and bones", Loperamide take-home, 8 referrals, physio outcome scales (ODI/NDI/WOMAC/LEFS), server-side hidden sections by visit mode, closed reference list names, `secondchecks` menu key. New defaults H20 (outcome scale scores typed), H21 (Form F stays visible in Gynae mode while required — legal duty), H22 (Loperamide uses the prescription's usual new-row defaults): applied by default (DEC-023); the human can change them.
