# SAH-38 · Hardening 3 (HARD-3): colleague PIN guessing and lockout, and the dose event wording
Mockup screen: Nursing sheet (card → Nursing) — the Medicines tab and the Give sheet (`sfGive`, `sf-wit` "Checked by", `sf-pin` "Their PIN", `sf-id`, `sfgiveok`, `sfLog` "give" and "deny" events). Second screen: Clinic setup → Team and access (the Team page, `f-staff`, rows with the PIN pill).
Module: platform (colleague PIN, notices, Team page) and ipd (Give sheet texts, dose event text)
Source: Tech lead, 7 Nov 2026, parallel lane 3. Security review `docs/reviews/SAH-32-security.md` findings S1, S2, S3 (S4, S5 as context); `docs/architecture/adr/ADR-011-ipd-mar-dose-administration.md` Q14 to Q17; ADR-010 (staff PIN); DEC-017, DEC-018. Backlog name "HARD-3".

## Numbering
The board calls this "HARD-3". `scripts/validate.mjs docs` accepts only files named `SAH-<n>.md` (line 52), so this story uses the next free number, **SAH-38** (SAH-35 is lane 1, SAH-36 is lane 2, SAH-37 is the reserved "patient card setup" candidate on the board). The tech lead confirms the number before the branch is made. The words "HARD-3" are kept in the title so the board and ADR-011 links still find it.

## Problem
SAH-32 lets a nurse record a high-alert medicine (for example insulin, heparin, potassium) only when a second nurse checks it with that nurse's own staff PIN. The security review found that this second check can be defeated or abused, and that one permanent record can be untrue. Three findings block go-live.

1. **S1, quiet guessing.** A nurse who is signed in can try a colleague's 4-digit PIN again and again. The limits on this today (5 wrong tries per 15 minutes and 10 per 24 hours per caller) are kept in memory: a restart or a second server resets them. The colleague's own wrong-try counter goes back to zero each time the colleague uses the PIN rightly, so up to 4 wrong tries between the colleague's own uses never lock the PIN and nobody sees them. Birthdays like 1503 are allowed PINs, so a guesser has a real chance over weeks. A nurse who finds the PIN can record a high-alert dose and name the colleague as the second check although the colleague never saw the patient.
2. **S2, locking out a colleague.** A hostile colleague can lock another nurse's PIN twice a day. With a restart, a second server or a second caller, a third lock switches the PIN off. While this happens the colleague's own allowance for failed checks also fills, so anyone who names that nurse gets "too many tries". Neither the nurse nor the owner is told. On a ward with few nurses this can stop high-alert doses being given.
3. **S3, the record can say something untrue.** The Give sheet box for the patient identity accepts typing, and the identity bar above it shows the patient's UHID. A nurse can type the UHID from the screen without looking at the wristband. The permanent clinical event still says "wristband scanned". The record must not claim a scan that may not have happened.

## Who uses it (roles) and in which setting
- **Nurse** (hospital ward, bedside, tablet or ward computer): gives a high-alert dose and names a second nurse; is the second nurse whose PIN is typed; sees the notice at next sign-in.
- **Owner** (clinic office or phone): sees wrong tries by colleagues and PIN locks on the Team page.
- **Doctor, visiting doctor, pharmacist, front desk, billing**: do not give doses and are not offered as a second nurse (ADR-011 Q3, Q6). They are not changed by this story. A doctor or pharmacist who has a PIN is not a target of colleague checks.
- The clinical event log on the Safety tab (SAH-33) is read by owner, doctor and nurse; it shows the changed event text.

## In scope / Out of scope
In scope (the five items from the tech lead; all are "proposed safer direction, the human decides"):
1. A durable cap on wrong colleague-PIN tries per (caller, colleague) pair over a rolling week. It survives a restart and is the same on every server instance.
2. A notice to the colleague at next sign-in ("N wrong tries of your PIN by colleagues since your last use") and the same information, with who and when, to the owner on the Team page.
3. Wrong tries typed by colleagues never count toward the third-lock switch-off, and do not use up the colleague's own allowance for their own PIN.
4. Product text for a ward when no second PIN works, so the nurse knows what to do and does not share a PIN. The ward procedure itself is the clinical lead's (open question).
5. S3: from now on the permanent dose event says "identity checked against UHID" instead of "wristband scanned". Rows already written keep their text.

Out of scope:
- A rate limiter for the whole platform, a shared (database or cache) limiter for every endpoint, the in-memory limits of sign-in codes and other PIN endpoints, mismatch and MAR-open limits (S5): **HARD-2**. Item 1 here is a narrow durable counter for one purpose, not that infrastructure. The existing in-memory per-caller caps stay as they are until HARD-2.
- Idle screen lock for ward devices (ADR-011 Q17, S7): separate backlog story.
- Hiding the UHID in the identity bar until the box is filled, or storing a scanner-typing-speed hint (ADR-011 Q16 options b and c). Not chosen here; the human may choose them instead of, or in addition to, item 5.
- A "Scan wristband" button or any scanner integration (SAH-32 decision OQ-32-1). Printing wristbands.
- A paper or override second check inside the product. The product never lets a dose be recorded without the second PIN (see open question OQ-38-5).
- Letting OWNER or DOCTOR be the second checker; single-nurse shifts (ADR-011 Q6).
- Sending any message (SMS, WhatsApp, push) to the colleague or owner. Notifications are not in phase 1; notices are shown inside the app only.
- Changing the PIN length, the weak-PIN list, the pepper, the `purpose` column on sign-in codes (ADR-010 Q8, Q9), or the audit table partition plan (ADR-011 Q9).
- Rewriting dose events already stored (the table is append-only).
- Other Medium and Low findings of the review (S4 and S6 are already fixed; S5 is HARD-2; S7 is above).

## Business rules (numbered)
Numbers in brackets are the parameters the human confirms (OQ-38-1).

BR1. **Pair cap.** Wrong colleague-PIN tries are counted per (caller, colleague) pair, for the clinic only, over a rolling 7 days. After **[5]** wrong tries in 7 days for a pair, the caller cannot check with that colleague until older tries fall out of the 7 days. A try refused for this reason is not compared with the PIN and does not count on the colleague's own wrong-try counter.
BR2. **Durable and shared.** The pair count must survive an application restart and must be the same when the application runs on more than one instance. It must not be kept only in memory.
BR3. **What counts.** Only a PIN that was compared and found wrong counts. A right PIN never counts. Tries answered "locked", "not set", "not eligible" or "refused by the cap" are not counted. A right PIN for the pair clears that pair's count (so ordinary nurse pairs are not blocked by old typing mistakes) **[proposal]**.
BR4. **Existing caps stay.** The per-caller caps (5 per 15 minutes, 10 per 24 hours, any colleague) and the colleague's lockout (5 wrong tries in a row lock the PIN for 15 minutes) stay. The pair cap is added on top.
BR5. **Answer to the caller when the pair cap is reached.** Same family as today's "too many tries": the caller is told to choose another nurse. The answer says nothing about the PIN and gives no count. The Give sheet keeps everything the nurse typed except the PIN.
BR6. **Notice to the colleague.** At the colleague's next sign-in, if there are wrong colleague tries on their PIN that they have not yet seen, they see one notice: "N wrong tries of your PIN by colleagues since your last use." "Since your last use" is read as **since the colleague last dismissed this notice** (or since the PIN was set), not since the colleague last typed the PIN, because otherwise a lucky guess by someone else could clear the evidence (OQ-38-3). The notice has one button that dismisses it. The notice does not name the callers (OQ-38-2). It also tells the colleague if their PIN is currently locked, or was switched off, because of wrong tries.
BR7. **Information to the owner.** On the Team page the owner sees, on a nurse's row, how many wrong tries by colleagues there were in the last 7 days and whether the PIN is locked. In the change history of the row the owner sees each period of wrong tries with the date, time and the name of the colleague who tried (names at display time; the store keeps only ids), each lock caused by colleagues' tries, and each time a colleague entered a right PIN after wrong tries by the same colleague in the previous 7 days. Only the owner sees names.
BR8. **Colleague-caused locks do not switch the PIN off.** A lock counts toward the third-lock switch-off (ADR-010 D5) only if every wrong try in the run of five was typed in the person's own signed-in session. If any try in the run came from a colleague check, that lock still locks the PIN for 15 minutes but is not counted. A PIN can then be switched off only by the person's own wrong tries (OQ-38-4).
BR9. **Colleague tries do not use the colleague's own allowance.** Wrong colleague tries on a nurse's PIN do not fill that nurse's own allowance of failed checks (10 per 5 minutes) and therefore do not stop others from naming that nurse because of someone else's guesses. The per-caller caps and the pair cap are the limits for colleague tries.
BR10. **No tries-left count.** Nothing shown to a colleague checker shows how many tries are left (SAH-32 fix S4 stays). The notice and the owner count are shown only to the PIN's owner and the clinic owner, never in the answer to the guesser.
BR11. **Ward text when no second PIN works.** When the second-nurse check cannot be completed because the colleague's PIN is locked, not set, switched off, or the pair cap or per-caller cap is reached, the Give sheet shows one fixed ward line under "Their PIN": do not give the medicine yet, choose another nurse or ask the nurse in charge or the clinic owner, never share a PIN. The product offers no way around the second PIN. What the ward does about a dose that is due is the clinic's written procedure (OQ-38-5).
BR12. **Dose event wording (S3).** For every dose recorded after this change, the permanent "give" event reads "Gave <medicine> (<time> dose) · identity checked against UHID", with " · second check by <name>" after it for a high-alert dose, as today. The wristband mismatch event reads "Stopped at the identity check: entered <text>, expected <UHID> (<medicine>)" instead of "scanned <text>" (OQ-38-6). Events already stored are not changed. Nothing else about the check changes: the same normalised comparison, the same deny entry, the same screen box.
BR13. **Tenant rules.** All counters, notices and history rows are per clinic. A caller can never see or affect another clinic's pair counts. The clinic is taken from the signed-in user, never from the request.
BR14. **Privacy.** Stored counters and audit rows hold ids, counts and times only. They never hold a PIN, a hash, a name, a mobile number or a patient name. Logs never hold them. Names appear only on screen, looked up at display time.
BR15. **Roles.** The notice is for any signed-in staff member who has unseen wrong tries (in practice nurses). The owner view is for OWNER only. Roles that cannot see the Team page (doctor, visiting doctor, nurse, front desk, billing, pharmacist) get no Team data.

## Patient-safety and privacy points
- The second check exists so that two people look at a high-alert dose. A guessed PIN removes the second person. BR1 to BR3 slow guessing from "quietly over weeks" to "a few tries a week per pair", and BR6 to BR7 make guessing visible.
- BR8 and BR9 mean a hostile nurse cannot, by typing wrong PINs, stop high-alert doses on a ward by locking or switching off another nurse. They keep the protection against someone guessing a person's own PIN in that person's own session.
- BR11 reduces the pressure to share PINs when the check does not work. Sharing a PIN defeats the whole control. The product still refuses to record the dose without a second PIN.
- BR12 keeps the permanent record true. Old rows saying "wristband scanned" stay as they are; a reader of the log must be able to tell that older wording did not prove a scan (release note, OQ-38-6).
- DPDP: notices and counters are about staff, not patients; no patient data is added. The dose event text carries the same patient fields as today.
- A notice that tells a nurse their PIN was tried by others is a personal-data disclosure about another staff member only if it names them; BR6 does not.

## Plan or entitlement
- Colleague checks and the Give sheet belong to the IPD module (Trial and On; in Read-only IPD recording a dose is refused, SAH-32 AC11, so no colleague check starts). The Team page and the PIN notice belong to the platform and show in every plan and state, because they are about staff accounts. No new plan flag.

## Open questions for the human
Full text, with a proposed answer for each, is in `docs/stories/SAH-38.md` section "Open questions". The ones that truly need the human (clinical, security or record-keeping decisions) are:
- **OQ-38-1** the numbers of the pair cap (proposed: 5 per pair per rolling 7 days, cleared by a right PIN).
- **OQ-38-4** whether a colleague-caused lock may still lock for 15 minutes but not count toward switch-off, and the rule for mixed runs.
- **OQ-38-5** (clinical) what the ward does when a high-alert dose is due and no second PIN works.
- **OQ-38-6** (record-keeping, go-live blocker) the new permanent event wording, including the mismatch event, and leaving old rows as they are.
- **OQ-38-2** whether the colleague should be told who tried (proposed: no, only the owner).
The others proceed on the proposal.
