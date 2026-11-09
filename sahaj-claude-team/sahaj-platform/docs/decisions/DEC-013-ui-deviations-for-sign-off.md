# DEC-013 · UI deviations from the mockup: human sign-off needed

Status: OPEN (does not block development). Source: UI-2 and UI-3 sweeps (Team C), 5 Nov. The mockup is the design, so each item below differs from it or is unclear.

## 1. Page maximum width (clinic and admin)
The mockup `.cs main.page` is max-width 1320 px. Its last override for the admin work area (`.axws`) is fluid (no maximum). The app used 1560 px; UI-2 set 1680 px (`--page-max` in `_shell.scss`); the admin pages use 1320 px.
- A. 1320 px everywhere (exactly the mockup clinic rule; widest side margins on large screens).
- B. 1680 px for the clinic, fluid for the admin (current clinic value; follows the admin override).
- C. Fluid everywhere (no side margins; long lines on very wide screens).
Recommendation: B. One-line change either way.

## 2. Admin collapse button
Mockup: icon-only toggle on the brand row. Approved ADR-003 criterion 12 and its test need the text "Collapse menu". Kept the ADR text. Choose: keep (recommended) or follow the mockup.

## 3. Form field size
Material text fields were made 0.86 rem and 44 px high to match the mockup `.input`. Revert by deleting the token block in `_console.scss` if you prefer Material's default.

## 4. Minimum touch height 44 px
Admin nav items are 44 px high (`.claude/rules/angular.md` touch rule); the mockup items are about 33 px. Kept 44 px.

## 5. Admin sidebar pieces not built
Org switcher, "Search settings" and the "Menu ... edit in Menu Management" footer exist in the mockup but are outside ADM-1.

## 6. Queue board on phones
Below 436 px the board shows one full column per swipe (snap). The mockup keeps 210 px columns with a partial peek. Chosen to avoid a clipped column.

## 7. Team row extras
The role select and "Remove access" on the staff row are not in the mockup row (it shows a PIN pill and a toggle). Already built; laid out cleanly. Remove them or keep.

## 8. Admin sign-in fields
Material outline fields (Angular rules) instead of the mockup's plain label-above inputs.

## 9. SAH-27 discharge sheet (added 5 Nov, after security and code review)
- Signed state: the mockup keeps "Summary checked and signed by <admitting doctor>" in every state. The server lets any DOCTOR or the OWNER sign, so after signing the label reads "Summary checked and signed" and a line "Signed by <real signer> · <date, time IST>" shows the actual signer (security review M2). Before signing the mockup label is unchanged. Confirm or revert.
- Discharge button for roles that cannot complete (DOCTOR, NURSE): disabled with the hint "Front desk or the owner completes the discharge" (the mockup has no role state; AC14 limits completion to OWNER and FRONT_DESK).
- "Going home today" pill and bed flag are exclusive, as in the mockup (they replace the Insurance/Corporate/Deposit pill and the Deposit low flag).
- Opening the discharge sheet writes one record-access row (it shows the patient name and diagnosis).

## 10. SAH-28 emergency (added 5 Nov, frontend report)
- The earlier SAH-23 tile "Beds available" was removed: AC8 and the mockup list four tiles (Beds occupied, Emergency, Deposit alerts, Going home today); the available count stays in the Beds occupied text.
- Setup tip text changed: the mockup says a breached target "moves the card up and alerts the duty doctor". Nothing is sent in phase 1 and the card does not move, so it reads "A card is highlighted when its target time is passed. No message is sent to the duty doctor yet."
- Menu order: the shell groups by NAV_ITEMS, so Emergency sits in the "Daily work" group right before Beds and admissions (after Appointments), not after Follow-ups as the mockup order. The setup overview shows no summary line ("3 colours · 2 bays") because no IPD section has one.
- QA F2: with IPD on and no beds, the page showed "Add your wards and beds to start" instead of the Emergency queue, although the server accepts registrations. Tech lead decision (DEC-014, safer direction): `/app/emergency` shows the queue and registration even with zero beds; the Beds and admissions page keeps its setup message. Confirm or revert.
- Mobile in the quick registration must match ^[6-9][0-9]{9}$ (AC4 says "10 digits"). The Admit hand-off needs an age to register the patient and NURSE/DOCTOR cannot register patients: the user must type the age and a nurse cannot finish for a new patient (ADR-005).

## 11. SAH-30 nursing vitals and early warning score (added 5 Nov, contract ADR-006)
Decided by the tech lead under DEC-014 (proposals followed; mockup wins; safer direction). The human confirms or reverts. Full list: ADR-006 questions Q1-Q15.
- Q1 (privacy): opening the Nursing sheet is written to the patient record-access log (contract and ADR-006 recommend it; story AC13 said not). Safer for DPDP. Mockup does not log nursing.
- Toast on a Medium/High score reads "Escalation raised ..." with the target time even when an already open escalation was only updated; no "duty doctor alerted" (nothing is sent in phase 1).
- Nurse name dropped from the sheet subtitle; no role notice in the Read-only or discharged view; vitalsDue and latestEws are null for FRONT_DESK.
- Vitals are sent as typed strings with plausible-range checks (SpO2 1-100, temp 25.0-45.0, pulse and RR 1-300, systolic 40-300, diastolic 20-200). A degree-Fahrenheit entry is refused, not converted. Systolic not above diastolic is not refused.
- Note: line breaks allowed; control/bidi/zero-width characters refused.
- An escalation still open at discharge, or after a later Low score, stays open (the Safety board story must close it). OQ-30-2: do not use SAH-30 on a live ward without the Safety board story. OQ-30-6 clinical sign-off of the bands and response times remains blocking for go-live.
- Actor columns hold the user id (the IPD convention), not the membership id the story names.

## 12. SAH-28 security review: retention of emergency cases (M1) and session policy (M2)
Source: docs/reviews/SAH-28-security.md. Needs the human; development continues.
- Retention: closed emergency cases keep name, mobile, age and complaint forever. Tech lead is clearing `mobile` when a case closes (safe, small). Still to decide: how long to keep name and complaint (IMC 2002 says at least 3 years for indoor records; medico-legal cases may need longer; ask legal), then pseudonymise; where erasure/correction requests go; whether an owner may correct a mistyped name or mobile on a closed case. Also record the lawful basis (DPDP s.7(d), medical emergency) in the privacy notice.
- Session policy (HARD-2, platform): no idle timeout and no absolute session lifetime; the 60-second ER polling keeps a signed-in browser alive for 30 days at a time. Please give the absolute lifetime (suggest 12 hours) and the idle timeout (suggest 15 to 30 minutes, shorter for the ER desk).
- Rate limit on registrations (L3) goes into HARD-2 with the platform rate limiting; a bulk "closed as entered in error" action needs your decision and a mockup.
- SAH-30 frontend deviations from the mockup (frontend report): (1) subtitle has no assigned nurse ("Bed 202 · last vitals 35 min ago"); (2) Nursing opens from a button in the admission sheet footer, not from a patient card (OQ-30-1); (3) one toast for Medium/High (escalation only), not "Vitals saved" then the escalation toast; (4) the sheet stays open after saving and shows the new score and note (AC 5), the mockup closes it; (5) range messages and the not-admitted text are the contract's own wording; (6) a failure shows as a toast and as an inline notice with a "Check the sheet" button after a lost answer; (7) pulse, SpO2 and RR use numeric input mode (whole numbers only), the mockup uses decimal; (8) plain labelled inputs as in the mockup, Material only for buttons and the dialog.

## 13. SAH-30 review decisions (code and security reviews saved in docs/reviews/SAH-30-*.md)
Decided by the tech lead under DEC-014; the human confirms or reverts.
- Toast on a Medium/High score reads "Escalation raised for <patient> · EWS N · doctor review within 15 min. Tell the doctor now: no alert is sent." (mockup says "duty doctor alerted"). Stays until the Safety board story exists. SAH-30 must not be used on a live ward before the Safety board (OQ-30-2) and the clinical sign-off of the scoring bands and response times (OQ-30-6).
- Prefilled vitals (mockup, OQ-30-8) stay; a save whose five values equal the last reading needs a second tap to confirm, and after a save the Save button stays disabled until the nurse types something. The human may still prefer empty fields with the last values beside them.
- Reading the Nursing sheet (and a replayed save) is written to the patient record-access log (story AC13 said not; the BA amends AC13 once confirmed).
- A role change now applies to vitals saves and sheet reads at once (role re-read from the membership), instead of up to 15 minutes later. The platform-wide fix (cache the role in the 60-second membership check) is HARD-2 and changes auth: human decides.
- A replay of a client key with different values is refused (409 client-key-reused) instead of silently dropped.
- Safety board story must: re-open an acknowledged escalation when the risk rises (L3), handle an escalation still open at discharge (L4), add the missing escalation checks (done in V2026_11_25), take the same admission lock.
- Notes may hold third-party data (a relative's phone number); retention is open (ADR-006 Q10); consider a screen hint "Do not write phone numbers".
- HARD-2 additions: per-user rate limit on clinical writes, global request body size limit, absolute session lifetime and idle timeout, role in the membership check, catalog check that append-only triggers stay enabled, validate-script rule against `disable trigger` / `no force row level security` in migrations (scripts/ is human-owned).

## Item 14 · Who may read the patient list (found by QA, 6 Nov 2026)
`PatientsIT.everyClinicRoleMayReadPatientsAsTheContractSays` is skipped with the note: the code (`PatientRoles.READ`) excludes BILLING, PHARMACIST and COUNSELLOR, but `docs/api/patient.yaml` says any clinic role may read patients. The two documents disagree. The code is the stricter side (less access to patient data). Tech lead position: keep the stricter code for now; the human decides whether those three roles need to read patients (billing and pharmacy probably do, for the patient name on a bill or a dispensing screen) or whether the contract is corrected. Not blocking any story.

## Update, item 1 (page max width), 9 Nov 2026
- **Defect found:** the human reported a width jump when the left rail is expanded or collapsed (screenshot of IPD → Admitted at about 1915 px). The cause was the 1680 px clinic cap (option B), centred in the work area. At 1920 px with the rail collapsed, the page jumped to a 1680 px column with an empty band of about 90 px on each side.
- **Fix (angular-developer, 9 Nov 2026):** the clinic and the admin work areas are now fluid, as the mockup's last rule says (`.axws, .cs main.page { max-width: none !important }`). This is DEC-013 option C, and it deviates from the earlier recommendation B. It follows the mockup, which is the design.
- **Trade-off:** at 2560 px the page is 2312 to 2500 px wide, so lines can get long.
- **Decision for the human:** keep fluid (default applied), or add a cap for ultra-wide screens, for example 2000 px or more. That cap must be centred in the viewport, not in the work area, so the rail toggle does not shift the content.
