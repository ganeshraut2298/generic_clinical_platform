# SAH-38 · Hardening 3 (HARD-3): colleague PIN guessing and lockout, and the dose event wording
Mockup screen: Nursing sheet (card → Nursing) — the Medicines tab and the Give sheet (`sfGive`, `sf-wit`, `sf-pin`, `sf-id`, `sfgiveok`, `sfLog`). Second screen: Clinic setup → Team and access (the Team page, `f-staff`).
Module: platform and ipd
Requirement: docs/requirements/SAH-38.md. Source: docs/reviews/SAH-32-security.md S1, S2, S3; ADR-011 Q14 to Q17; ADR-010; DEC-017; DEC-018.
Status: proposed safer direction. The human decides every item marked "Human".

## Mockup position (rule 1)
The mockup shows the Give sheet with "Checked by" and "Their PIN" (any 4 digits), and the event "Gave … · wristband scanned". It shows no counters, notices, limits, lock states or ward text, and its Team page has only the "PIN set" pill and the "Send PIN link" button. **Everything user-visible in this story is NEW (not in the mockup) and needs human sign-off.** The register of NEW texts is in section "NEW texts" below. The existing mockup labels on the Give sheet ("Checked by", "Their PIN", "Scan the wristband or type the MRN", the legend "Every dose needs the wristband scan …") are not changed by this story.

## Stories

### SAH-38-1 · As a nurse who is asked to be a second checker, I want wrong tries at my PIN by colleagues to be limited for a whole week, so that nobody can guess my PIN slowly and record a high-alert dose in my name
Priority: Must

## Acceptance criteria
- Given nurse A has made fewer than the cap of wrong PIN tries for nurse B in the last 7 days, when A names B on the Give sheet of a high-alert medicine and types a wrong PIN, then the answer is "That PIN is not right." with no count of tries left, the dose is not recorded, and the wrong try is added to the (A, B) pair count.
- Given A has made the cap number of wrong tries for B in the last 7 days, when A names B and types any PIN (right or wrong), then the PIN is not compared, nothing is added to B's own wrong-try counter, nothing is recorded, and A is told to choose another nurse (NEW text T1). The nurse's entries (patient identity, reason, chosen nurse) stay on the sheet and the PIN box is cleared.
- Given the cap is reached for the pair (A, B), when A names another nurse C, then the check with C works normally (the cap is per pair).
- Given the cap is reached for (A, B) and B is also named by nurse D, then D is not affected (the cap is per caller).
- Given A made the cap number of wrong tries 8 days ago and none since, when A names B, then the check is allowed (the window is rolling, 7 days).
- Given the application restarted, or a second instance of the application answers the request, when A names B, then the pair count is the same as before the restart or on the other instance (nothing is reset).
- Given A types a right PIN for B, when the dose is recorded, then the (A, B) pair count goes back to zero [proposal, OQ-38-1], and the right try is never counted as a wrong one.
- Given B's PIN is locked, not set or switched off, when A names B, then the existing answers (locked, not set) are given as today, and these tries are not added to the pair count.
- Given A names a person who is not an active nurse of this clinic, or names themselves, or sends a membership of another clinic, then the answer is "Choose a nurse from the list." (400) and nothing is counted. Another clinic's id gives the same answer as an unknown id, and nothing about the other clinic is shown.
- Given the existing per-caller cap (5 wrong per 15 minutes, 10 per 24 hours) is reached, when A names any nurse, then the answer stays the existing "Too many PIN tries in a short time. Try again in a few minutes." and no pair count is added.
- Given two wrong tries by A for B arrive at the same moment (double tap or two devices), then both count, and the cap cannot be passed by sending them in parallel.
- Given A is not a nurse or the owner (for example a doctor, front desk or pharmacist), when A tries a colleague check, then the answer is the existing refusal and nothing is counted.
- Given the PIN service is not available (pepper missing), then the answer stays "PIN checks are not available right now. Try again later." and nothing is counted.
- Given the stored pair counts, then they hold ids, counts and times only: no PIN, hash, name or mobile number, and no log line holds them.
- Given the pair count cannot be read or written (database slow or failing), when A names B, then the check fails closed: the dose is not recorded, the answer is "We could not check the PIN. Try again." (NEW text T7), and nothing is counted as right.

## Notes for design
- Durable store: a small table of counts per (clinic, caller, colleague, time) or a count of the existing `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` audit rows with an `(actor, action, at)` index; the architect and DB engineer choose. Row-level security by clinic as for every table. Old rows can be removed after 7 days.
- The existing in-memory per-caller limiter stays until HARD-2; this story must not depend on it.
- Contract: a new refusal reason on the colleague check (429 or 403; the architect chooses the slug), Problem wording marked NEW.
- Human: OQ-38-1 (numbers).

---

### SAH-38-2 · As a nurse, I want to be told at my next sign-in that colleagues tried my PIN wrongly, and as the owner I want to see it on the Team page, so that a guesser is noticed
Priority: Must

## Acceptance criteria
- Given colleagues made 3 wrong tries at nurse B's PIN since B last dismissed the notice, when B signs in, then B sees one notice "3 wrong tries of your PIN by colleagues since your last use." (NEW T2) with one button "Got it". The notice does not name any colleague.
- Given exactly 1 wrong try, then the text is "1 wrong try of your PIN by a colleague since your last use." (NEW T2).
- Given B taps "Got it", then the notice goes away, does not come back at the next sign-in, and a new notice appears only for wrong tries made after that.
- Given B has no unseen wrong tries, when B signs in, then no notice is shown and sign-in is not slowed.
- Given B's PIN is locked, or was switched off because of wrong tries, when B signs in, then the notice also says so (NEW T3: "Your PIN is locked until 11:05 AM IST." or "Your PIN was switched off. Set a new one.") and offers the existing "Set my PIN" action where the PIN is switched off.
- Given B's own wrong tries (typed at B's own PIN in B's own session), then they are not part of the "by colleagues" number.
- Given the notice cannot be loaded (slow network), when B signs in, then sign-in completes without it, the notice is tried again at the next screen load, and nothing is lost.
- Given the owner opens Clinic setup → Team and access, then each nurse's row shows the number of wrong tries by colleagues in the last 7 days and the pill "PIN locked" when locked (existing), and a row with zero tries shows nothing extra (NEW T4).
- Given the owner opens the change history of a nurse, then it shows, for the last 7 days, the date and time (24 Oct 2026, 11:05 AM IST) and the name of each colleague who made wrong tries and how many (NEW T5), each lock caused by colleagues' tries, and each time a colleague entered the right PIN after wrong tries by the same colleague in the previous 7 days (NEW T5). Names are looked up when the page is shown; a colleague whose access was removed shows as "Former staff member".
- Given the user is not the owner (doctor, visiting doctor, nurse, front desk, billing, pharmacist), when they ask for the Team page data or another person's tries, then the answer is the existing refusal and no counts or names are returned. A nurse sees only their own notice and number, never who tried.
- Given another clinic's nurse id is sent, then the answer is "not found", as for any other clinic's record.
- Given the owner is the person with the tries (the owner's own PIN), then the owner sees the same notice as a nurse; colleague checks target nurses only, so this is normally zero.
- Given a notice or history entry, then the stored data holds ids, counts and times only, and logs hold no names or PINs.
- Given the Team page loads slowly or fails, then the page shows its existing error state and no partial counts are shown as zero.

## Notes for design
- Notice is in-app only (no SMS or WhatsApp in phase 1). Where it appears (sign-in banner, sheet or card on the first screen) is a mockup question: propose a plain banner at the top of the first screen after sign-in with the button "Got it".
- "Since your last use" is read as since last dismissal or since the PIN was set (OQ-38-3).
- Human: OQ-38-2 (names), OQ-38-3 (marker), OQ-38-7 (what else the owner sees).

---

### SAH-38-3 · As a nurse, I want wrong PIN tries by other people to be unable to switch off my PIN or use up my own allowance, so that a hostile colleague cannot stop high-alert doses on my ward
Priority: Must

## Acceptance criteria
- Given a run of five wrong tries at nurse B's PIN in which every try was typed by colleagues, when the fifth is made, then B's PIN is locked for 15 minutes as today, the lock is recorded as caused by colleagues, and the lock does not count toward the third-lock switch-off.
- Given B's PIN has had any number of colleague-caused locks in 24 hours, when another colleague-caused lock happens, then B's PIN is still not switched off and the answer is "locked" (403), not "not set" (409).
- Given a run of five wrong tries in which at least one try was colleague-typed and the rest were B's own, then the lock is treated as colleague-caused and does not count toward the switch-off [proposal, OQ-38-4].
- Given a run of five wrong tries all typed in B's own session, then the lock counts as today and the third such lock in 24 hours switches the PIN off (ADR-010 D5), with the same answers and audit as today.
- Given a mixed day with two own-session locks and any number of colleague-caused locks, then the PIN is switched off only by a third own-session lock.
- Given colleagues make wrong tries at B's PIN, when B uses their own PIN (for example to sign a verification, or as the owner would), then B's own allowance of failed checks (10 per 5 minutes) is not reduced by those tries, and B is not answered "too many tries" because of them.
- Given B's PIN is locked by colleagues' tries, when B tries their own PIN, then the answer is the existing "locked" answer with the time it opens; B can wait or set a new PIN with a code to B's own mobile as today.
- Given the lock caused by colleagues, then the lock audit row records that colleagues caused it (ids only) and the owner's change history shows it (SAH-38-2).
- Given a colleague-caused lock, then the notice (SAH-38-2) is shown to B at next sign-in and the owner's row shows "PIN locked".
- Given an owner reset (existing), then counters of colleague tries, locks and the pair counts of that nurse for the PIN's old life are cleared as the lock counters are today; the history stays.
- Given a clinic where B belongs, then only that clinic's rows are used; another clinic's tries never touch B.

## Notes for design
- Needs the lock to record who typed the tries in the run (own or colleague) without storing a PIN. The architect decides how (two counters, or a flag per run).
- Contract: `pin-locked` and `pin-not-set` meaning for colleague checks does not change; no new status code.
- Human: OQ-38-4.

---

### SAH-38-4 · As a nurse, I want a clear line on the Give sheet when no second PIN works, so that I do not give the medicine and I do not share or borrow a PIN
Priority: Should

## Acceptance criteria
- Given a high-alert medicine on the Give sheet, when the second nurse's PIN is locked, not set, switched off, the pair cap is reached or the per-caller cap is reached, then the sheet shows the existing reason and one extra fixed line (NEW T6): "No second PIN works? Do not give this medicine yet. Choose another nurse, or ask the nurse in charge or the clinic owner. Never share a PIN."
- Given the line is shown, then "Given" still cannot record the high-alert dose without a right second PIN; there is no bypass button, no "give anyway" and no paper option in the product.
- Given a non-high-alert medicine, then this line is never shown.
- Given the nurse chooses another nurse and types that nurse's right PIN, then the dose is recorded as before and the line disappears.
- Given "Record not given" (existing) is still available, then it keeps its existing reasons; no new reason is added by this story (OQ-38-5).
- Given a screen reader, then the line is announced when it appears, in the same way as the existing reason.
- Given a nurse sees the line, then nothing about the other nurse beyond what the reason already says is shown (no count of tries, no names of who tried).
- Given only one nurse is on shift (the select shows only "Choose a nurse"), then the same line is shown in place of an empty select explanation, so the nurse knows to call the nurse in charge or the owner (ADR-011 Q6).
- Given a role that cannot give doses, then the Give sheet does not open (existing) and no line is shown.
- Given the clinic's own written procedure differs (OQ-38-5), then only this one text changes; no logic depends on it.

## Notes for design
- Pure text and placement on the Give sheet; English only, as the rest of IPD.
- The procedure itself (who covers, paper record, when to call the doctor) is clinical and is not decided here.
- Human: OQ-38-5.

---

### SAH-38-5 · As the owner and as a doctor reading the clinical event log, I want a recorded dose to say "identity checked against UHID", so that the permanent record never claims a wristband scan that may not have happened
Priority: Must

## Acceptance criteria
- Given a nurse records a dose after this change, when the dose is saved, then the permanent "give" event on the Safety tab clinical event log reads "Gave <medicine> (<time> dose) · identity checked against UHID" (NEW T8), and for a high-alert dose "· second check by <name>" follows as today.
- Given the identity box does not match the patient's UHID (after the same normalising as today), then the existing deny entry is still written, with the text "Stopped at the identity check: entered <text>, expected <UHID> (<medicine>)" (NEW T9) in place of "scanned". The entered text is shown only if it has the UHID shape, otherwise "text that is not a UHID", as today.
- Given dose events that were written before this change, then they keep their old text unchanged (the table is append-only); no migration, no rewrite.
- Given the log lists old and new events together, then both appear in time order with their own wording.
- Given the Give sheet, then the box, its label, placeholder, the mismatch message shown to the nurse and the comparison are unchanged by this story.
- Given the Medicines tab legend (mockup: "Every dose needs the wristband scan and the patient's name and date of birth …"), then it is unchanged by this story (OQ-38-6).
- Given a role that cannot read the log (front desk, billing, pharmacist, visiting doctor), then they still cannot, and the change adds no new reader.
- Given another clinic, then its events are not visible (unchanged).
- Given the event text, then it carries no patient name, mobile or ABHA number (unchanged); logs never hold it.
- Given an existing test or report that searches for "wristband scanned" in new events, then it is updated by the owner of that code; the human is told in the handoff.

## Notes for design
- A text template change only (`DoseRules`, ADR-011 D9); no schema change.
- Human: OQ-38-6 (go-live blocker; alternatives: entry-mode hint, or hide the UHID until the box is filled, which is a mockup change).

---

## NEW texts (none are in the mockup; human signs off each)
| # | Where | Text |
|---|---|---|
| T1 | Give sheet, under "Their PIN", pair cap reached | "Too many wrong PIN tries for this nurse. Choose another nurse." |
| T2 | Notice at sign-in, plus button | "N wrong tries of your PIN by colleagues since your last use." / "1 wrong try of your PIN by a colleague since your last use." Button: "Got it" |
| T3 | Same notice, PIN state | "Your PIN is locked until 11:05 AM IST." / "Your PIN was switched off. Set a new one." |
| T4 | Team page, nurse row | "N wrong tries by colleagues in 7 days" |
| T5 | Team page, change history lines | "Wrong PIN tries by <name>: N", "PIN locked after wrong tries by colleagues", "Right PIN entered by <name> after wrong tries" |
| T6 | Give sheet, no second PIN works | "No second PIN works? Do not give this medicine yet. Choose another nurse, or ask the nurse in charge or the clinic owner. Never share a PIN." |
| T7 | Give sheet, check cannot be made | "We could not check the PIN. Try again." |
| T8 | Dose "give" event (permanent) | "Gave <medicine> (<time> dose) · identity checked against UHID" |
| T9 | Dose deny event (permanent) | "Stopped at the identity check: entered <text>, expected <UHID> (<medicine>)" |
| T10 | Team page, removed colleague | "Former staff member" |

## Open questions (each with a proposed answer)
Marked "Human" when only the human can decide (clinical, security or record-keeping). Others proceed on the proposal.

- **OQ-38-1 · Cap numbers (Human, security).** How many wrong tries per pair, over what time? Proposal: 5 per pair per rolling 7 days, cleared by a right PIN. With about 9,600 allowed PINs that is about 5 chances in 9,600 per pair per week. A lower number (3) blocks honest nurses who mistype; a higher number helps a guesser. Also: should the per-caller total across all colleagues have a weekly cap too? Proposal: not now; the pair cap, the existing daily caps and the notice are enough, and HARD-2 covers general limits.
- **OQ-38-2 · Who is named (Human, privacy and ward relations).** Does the colleague see who tried? Proposal: no. The nurse sees only a number; the owner sees names. A typing mistake by a friendly colleague should not cause conflict, and the owner can ask.
- **OQ-38-3 · What "since your last use" means.** Proposal: since the nurse last tapped "Got it" (or set the PIN). Not "since the nurse last typed their PIN", because a lucky guess by a colleague would then clear the count, and a rare PIN user would see stale numbers.
- **OQ-38-4 · Colleague-caused locks (Human, security).** Proposal: a colleague-caused lock still locks for 15 minutes (so an attacker cannot make unlimited guesses) but does not count toward the third-lock switch-off; a run with any colleague try counts as colleague-caused. Alternative: colleague tries never lock the PIN and only the caps limit guessing (lets an attacker with many accounts guess without any lock, so not proposed). Side effect: a nurse can be locked for 15 minutes by a hostile colleague a few times a day; the notice and owner view make it visible, and BR9 keeps the nurse's own allowance.
- **OQ-38-5 · Ward procedure when no second PIN works (Human, clinical).** What does a ward do when a high-alert dose is due and no second PIN works: wait for the nurse in charge or owner, call the doctor, use a paper second check recorded in the nursing note? The product never lets the dose be recorded without the second PIN, and the story only shows the line T6. Proposal: no paper bypass in the product; the clinic writes its own procedure; text T6 is the default. The human or clinical lead confirms. Related: single-nurse shifts cannot give high-alert doses (ADR-011 Q6) and the human may want the owner or a doctor as a second checker later.
- **OQ-38-6 · Dose event wording (Human, record-keeping, go-live blocker).** Proposal: "identity checked against UHID" for new give events, and "identity check: entered …" for the mismatch event; old rows unchanged. The event log will show two wordings; the release note should say that rows with "wristband scanned" did not prove a scan. Also: leave the Medicines tab legend and Give sheet labels as in the mockup, or change them to "wristband or UHID"? Proposal: leave for now. Alternatives from the review: store an entry-mode hint (weak evidence), or hide the UHID in the identity bar until the box is filled (mockup change); these can be added later.
- **OQ-38-7 · What the owner sees (proposal).** Counts and names for 7 days on the Team page, plus a line when a colleague enters a right PIN after wrong tries by the same colleague (a likely successful guess). This adds an audit row only in that rare case, not for every right PIN.
- **OQ-38-8 · Can the owner clear a pair cap? (proposal: no).** The cap expires by itself after 7 days and the nurse can choose another nurse; an owner override would be a way around the control.
- **OQ-38-9 · Notice delivery.** In-app only at next sign-in (no SMS or WhatsApp in phase 1). Placement is a screen decision for the architect and angular developer, marked NEW.
- **OQ-38-10 · Number.** Story ID SAH-38 instead of HARD-3 because `scripts/validate.mjs docs` accepts only `SAH-<n>.md`; the tech lead confirms the number is free.
- **OQ-38-11 · Release order.** S1, S2 and S3 are go-live blockers with HARD-2; this story and HARD-2 should both be done before any real ward uses SAH-32.

## Out of scope (repeat)
Rate limiter infrastructure and the in-memory limits of other endpoints (HARD-2); idle screen lock (ADR-011 Q17); hiding the UHID or an entry-mode hint; scanner integration; any message to the colleague or owner; owner or doctor as second checker; rewriting old events; PIN rules, pepper and sign-in code purpose.

## Checks before handoff
- Rules BR1 to BR15 map to criteria: BR1 to BR3, BR5 in story 1; BR6, BR7, BR10, BR13 to BR15 in story 2; BR4 in story 1 (existing caps), BR8 and BR9 in story 3; BR11 in story 4; BR12 in story 5; BR13 and BR14 in each story.
- Roles, other clinic, invalid input, slow network and fail-closed cases are covered in stories 1 and 2.
