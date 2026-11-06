# DEC-018 · SAH-32 MAR (giving a dose): points proceeding on the architect's proposal

Status: PROCEEDING under DEC-014 (mockup wins; safer direction), open for the human's sign-off. Nothing here blocks the build. Design: docs/architecture/adr/ADR-011-ipd-mar-dose-administration.md (PROPOSED); contract: docs/api/ipd.yaml (YAML check OK, 7 files).

## Safety-relevant choices (human please confirm)
1. **"Scan wristband" button is NOT built** (OQ-32-1). In the mockup the button copies the patient's own UHID into the box, so the identity check would pass by itself. The box is typed or filled by a keyboard-type scanner; the server compares it with the UHID after normalising (case, spaces). How wristbands are made and read in phase 1 is the human's.
2. **Second-nurse check for high-alert medicines uses the named colleague's own staff PIN** (mockup accepts any 4 digits). Only an active NURSE can be named, never the caller; wrong tries count on the colleague's own lockout; a caller may cause at most 5 failed colleague checks per 15 minutes and 10 per 24 hours, so one person can lock a colleague at most twice a day and can never switch their PIN off alone; every wrong colleague try is audited. Consequence: on a single-nurse shift a high-alert dose cannot be given (only NURSE can be the second checker). Residual guessing risk accepted.
3. **Who may give**: NURSE and OWNER (OQ-32-3). The mockup text "Only nurses and doctors can give medicines." becomes "Only nurses and the clinic owner can give medicines." (DOCTOR is refused).
4. **Read-only clinic refuses recording a dose** (story); stop of an order stays allowed (SAH-31). Human: should recording stay allowed?
5. **Opening the MAR writes a record-access row** (OQ-30-7; the story AC9 says it should not): the same rule as the Nursing sheet; this is the safer direction.
6. **No correction of a wrong dose record** (OQ-32-5): append-only; a note and the doctor.
7. **No "missed dose" record at midnight** and yesterday's slots cannot be recorded after midnight (OQ-32-2). On the day an order is verified, slots earlier than the verification time minus 60 minutes show "Before the order started" and cannot be recorded.
8. **Late = more than 60 whole minutes after the slot** ("N h late", hours rounded down; the mockup's "N min late" form never occurs).
9. Deny entry shows the scanned text only when it has the UHID shape; extra audit `IPD_DOSE_WRISTBAND_MISMATCH`.
10. New texts not in the mockup: "IPD is read-only. Doses cannot be recorded.", "This patient is not admitted any more.", "Before the order started", "Not recorded", "Waiting for pharmacist", problem texts for `dose-before-start`, `dose-not-due`, order-state, client-key-reused, "Choose a nurse from the list.", colleague PIN texts.
11. SAH-31 and SAH-32 are to be released together (SAH-31 toasts "It is now on the MAR." only become true with the MAR).
12. `platform.audit_event` grows by about 150,000 rows a day at 100 hospitals and has no partition plan yet (ADR-011 Q9); the dose table about 55 million rows a year, partition monthly by `slot_at` later.
13. Approval lines missing for SAH-30, SAH-31, SAH-32 in docs/approvals.md (human).

## Platform colleague-PIN build notes (6 Nov 2026)
- The limit arithmetic holds from a clean state: a caller is stopped at 10 wrong colleague tries in 24 h, which gives at most 2 locks and never a switch-off alone. Weak points: (1) limits are in memory (restart or several instances resets them; HARD-2, or count the caller's `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` audit rows, which needs an actor+action index); (2) "never alone" means from a clean state: a colleague with 2 locks in the window and 4 failed tries of their own can be switched off by one wrong try by anyone (inherent to ADR-010); (3) the limiter uses the application clock, the lock window the database clock.
- `platform.api` carries the ipd field name `secondNurseId` for NOT_ELIGIBLE (400, "Choose a nurse from the list."): minor layering point for the architect.
- An existing AuditSanitizer bug was fixed: a whole-string UUID whose last group has 10+ digits was masked as a mobile number (about 1 id in 100); embedded 10-digit runs in other text are still masked.
- One caller's colleague checks are serialised by an in-memory striped lock so a parallel burst cannot pass the limit before the first failure is counted.

## Build-time choices for the MAR (6 Nov 2026), open for sign-off
- The GET MAR 403 text (not in the contract) is "You cannot open the medicine round." (`DoseRules.MAR_HIDDEN`).
- The deny-entry scan regex `^[A-Z]{2,6}-[0-9]{5,34}$` can reach 41 characters; ADR-011 D4 says 40: the code follows the regex (architect to align).
- Replay of a high-alert dose compares the second nurse by display name because `TeamMemberPort.TeamMember` has no user id (two nurses with identical names would look the same on a replay); platform to add `userId` to `TeamMember` later.
- Angular also closes and reloads on 409 `ipd-readonly` and on a 400 on `slotAt` (a retry cannot succeed); 429 and 503 `pin-unavailable` show under "Their PIN"; slot chips are 44 px (mockup smaller); invented texts: "We could not load the medicines. Try again.", "Loading the medicines", "We could not record the dose. What you entered is kept, except the PIN. Try again."
- When no second nurse is available (`secondNurses` empty) the select shows only "Choose a nurse"; a single-nurse shift cannot give a high-alert dose (ADR-011 Q6).
- The Nursing sheet closes with `didSave` for vitals only; a recorded dose does not refresh the admission sheet underneath.

## Browser fix pass notes (6 Nov 2026)
- Mockup says the MAR sheet is "wide" (max-width 760 px). The Nursing sheet still opens at 640 px; the sticky footer from the mockup is built, the width is not. Human to confirm 640 px is acceptable, or a small follow-up widens it.
- On phones (620 px or less) the Give sheet's toasts appear at the top so they never cover Given / Record not given. The mockup does not show toast position.
- The medicines list on the MAR tab is the fallback focus target after a closing 409 when the slot chip is no longer a button.

## Review decisions (6 Nov 2026; docs/reviews/SAH-32-code.md and SAH-32-security.md)
Verdicts: code APPROVE WITH COMMENTS (no Blocker/Major); security PASS (no Critical/High).
Fixed in the review fix pass (safer direction, DEC-014): S4 colleague wrong-PIN answer shows no "tries left"; S6/R2 UHID-shaped deny scan bounded to what a UHID can be and to 40 characters total, contract aligned; I6 wristband maxLength 200 (input and contract); R3 GET /mar 403 text in the contract; R6 scan text selected after a mismatch; R7 MAR and doseCount read only PENDING/ACTIVE orders; R10 tab aria-controls; R11 toast no longer announced a third time; R13 unused method; R14 MAX_TRIES constant; R16 and R8 notes in ADR-011.
Accepted/noted: R1 (replay compares second nurse by display name), R5 (striped monitor; add to HARD-2), R8 (mismatch volume, audit_event partition plan), R12 (stroked "Record not given", small slot text), R15, S5 (HARD-2), S7 (idle lock backlog).
NEEDS THE HUMAN:
- S3 (go-live blocker): the identity box can be typed from the UHID shown on the Give sheet while the permanent event says "wristband scanned". Recommended: event text "identity checked against UHID". Alternatives: entry-mode hint, or hide the UHID until entered (mockup change).
- S1 and S2 (go-live blockers with HARD-2): colleague PIN can be guessed slowly (in-memory limits, no notice to the colleague); a colleague can lock or switch off another nurse's PIN. Proposed follow-up story "HARD-3 colleague PIN guessing": durable per-pair weekly cap, notice to the colleague, colleague-caused locks not counting toward switch-off, ward procedure when no second PIN works.
- R4 / DEC-018 item 5: MAR open writes a record-access row (story AC9 says no). R9: Medicines sheet width 640 vs mockup 760 px.
- Approval lines: SAH-30/31/32 `stories` and `contract`.
