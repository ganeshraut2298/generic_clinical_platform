# DEC-017 · SAH-31 medicine orders: the verification PIN and the pharmacist role

Status: DECIDED (human answered in chat, 6 Nov 2026). Blocks: release of SAH-31 only. Design, database, Java, Angular and tests go on meanwhile; "Verify" answers 409 `pin-not-set` until the PIN is chosen and built.
Design: docs/architecture/adr/ADR-009-ipd-medicine-orders-safety-checks.md (PROPOSED); contract: docs/api/ipd.yaml (YAML check OK, 7 files).

## Context
- The mockup's pharmacist check and second checks accept any 4 digits (format only). A format-only PIN gives no safety: anyone can type 1234 and the audit trail would name a person who did not act (OQ-31-3).
- The mockup shows the Pharmacist check panel to the owner only and has no pharmacist role on the IPD menu (OQ-31-2). The platform already has a PHARMACIST staff role.
- Both change authentication or the menu, so they are the human's to decide (escalation rule).

## Q1 · the PIN
| | Option | Pros | Cons |
|---|---|---|---|
| A | Format-only PIN as the mockup | Matches the mockup, nothing to build | No safety; the audit trail can name the wrong person |
| B | **Personal staff PIN** (recommended): each person sets a 4-digit PIN once; stored as a BCrypt hash plus a server secret (`SAHAJ_PIN_PEPPER` in `.env`); locked 15 minutes after 5 wrong tries | Real accountability; uses code already in the build | New table `platform.staff_pin`, set and reset screens that the mockup does not show, a new secret |
| D | No PIN; the signed-in session is the identity | Simplest, no new secret | Differs from the mockup; a shared tablet left signed in can verify |

## Q2 · pharmacist role
| | Option | Pros | Cons |
|---|---|---|---|
| A | **OWNER and PHARMACIST** can verify or reject; PHARMACIST gets a Safety-only page on the IPD menu (recommended) | A real pharmacist can work; the person who ordered can never verify | Menu change not in the mockup |
| B | Owner only, as the mockup | No change | A clinic without an owner on duty cannot release orders; the owner's own orders can never be verified |

## Other points recorded for the human (all proceed on the safer reading, DEC-014)
Catalogue sign-off (OQ-31-1, blocks go-live), OWNER without a doctor profile may order, VISITING_DOCTOR no access, a hard stop or missing reason is a 200 answer with an outcome, new `order-checks-changed` rule, SAH-31 and SAH-32 recommended to release together, no record-access row for the pharmacist list, no automatic stop at discharge, keyword allergy matching. Full list: ADR-009 "Questions" and the architect's handoff in docs/handoffs/SAH-31.md.

## Decision
Q1 personal staff PIN; Q2 OWNER and PHARMACIST (see the answers below).

### Human answers (chat, 6 Nov 2026)
- Q1: **Personal staff PIN** (option B). Adds `platform.staff_pin`, secret `SAHAJ_PIN_PEPPER` (human adds to `.env` and `.env.example`), set/reset flow, lockout 5 tries / 15 minutes. The mockup shows a "PIN" link in the team list and a setup checklist line "PIN login for each staff member"; the architect designs the set/reset flow from those and lists anything the mockup does not show.
- Q2: **Owner and Pharmacist** (option A): PHARMACIST gets a Safety-only page on the IPD menu; the person who ordered can never verify.

## PIN design (ADR-010, 6 Nov 2026) — items proceeding on the architect's proposal, open for the human's sign-off (DEC-014 rule: safer direction, mockup wins)
- Storage: BCrypt 12 of HMAC-SHA256(pepper, tenant|membership|pin) + `pepper_version`; supersedes ADR-002 D5 "Argon2id" (needs Bouncy Castle, a new library, so not used). No new library or service.
- Missing `SAHAJ_PIN_PEPPER`: the app starts, every PIN set/check answers 503 `pin-unavailable` (fail closed). Invalid pepper (not Base64, under 32 bytes, equal to JWT secret): the app refuses to start.
- Weak PINs refused (equal digits, runs, ABAB/AABB, years 1900–2099, 2580/0852, 4 digits of own mobile). 5 wrong tries lock for 15 minutes; the third lock within 24 h switches the PIN off until reset (architect's addition).
- Setting/changing a PIN needs a one-time code sent to the person's own mobile (existing OtpService); no current PIN asked. Owner can only clear a PIN, never read or set one. Removing someone's access clears it.
- Mockup deviations (texts proposed in ADR-010 D11/D12 and the architect handoff): "Send PIN link" not built (no notifications in phase 1) → "No PIN yet" pill; "PIN locked" pill; OWNER "Reset PIN" with confirm sheet; own-row "Set/Change my PIN"; Set PIN sheet; account-menu "My PIN" for roles without the Team page; checklist line "Own PIN for each staff member" (optional); Verify sheet states (not set / locked / unavailable); change-history labels.
- Accepted risks to acknowledge: sign-in codes and PIN codes are interchangeable for 5 minutes (no purpose column on `otp_challenge`); pepper rotation not designed (a change means everyone sets a new PIN); SAH-32 second-nurse check design (whose PIN, guess limits) left to SAH-32.

## Build-time choices (6 Nov 2026), open for sign-off
- Java (PIN): third lock within 24 h answers 409 `pin-not-set` ("switched off after too many wrong tries"), not `pin-locked`, because the PIN is already off; contract wording to align (architect). Audit key `pinState` kept readable by one narrow exception in `AuditSanitizer` (other pin-like keys stay redacted). Rate limits: 10 PIN operations / 5 min, 5 code requests / hour, 30 owner resets / hour (in memory, per tenant:user; HARD-2 for the platform limiter).
- Angular: texts invented by the developer need sign-off (Set PIN sheet subtitle and button/labels, Reset PIN sheet body, "Stop this medicine?" / "Keep", fallback/error texts, change-history labels, "My PIN" menu item). PIN pill and buttons also on doctor rows. If the server asks for a reason again (changed warnings) the reason resets to "Choose a reason". "Show more orders" collapses to page 1 on reload. After a 409 not-admitted / read-only the EMR sheet re-reads the EMR, which writes one more record-access row. English only (no mr/hi).

## Review decisions (6 Nov 2026; reviews in docs/reviews/SAH-31-code.md and SAH-31-security.md)
Fixed in the review fix pass (safer direction, DEC-014): allergy keyword stems and "unknown/not known/?" = not recorded (S1); allergy/chronic text out of patient audit rows and AuditSanitizer redaction (S2 a,b); record-access row per distinct patient when the pharmacist list is loaded, ADR-009 Q10 re-opened (S3); patient merge never overwrites an allergy with null and the IPD check follows `merged_into_id` (S4); PIN limiter counts failed checks only (R1); PIN autocomplete (S9/R9); notice lines for read-only / not-admitted ordering (R6, new deviation texts: "IPD is read-only. Medicines can be stopped but not added." and "This patient is not admitted any more."); comment-only forward migration for the ipd.drug table comment (R7).
Accepted/noted: EMR reload writes extra record-access rows (R2); `ordered_at` is the transaction start (R8); reflection test helper (R5); shown pages collapse after verify/reject (R10); PIN limits in memory (S8, HARD-2); S7, S11.
NEEDS THE HUMAN (not decided by the tech lead):
- S6 (go-live blocker): may an OWNER who is not a registered doctor place medicine orders? Options: only with a doctor profile and registration number, or a clinic flag "owner is a registered doctor".
- S5: PIN-set codes and sign-in codes are interchangeable (`otp_challenge` has no purpose column). Recommended: add `purpose` before a real SMS sender exists. Reverses ADR-010 Q5.
- S2(c): migration to clear allergy/chronic text already stored in `platform.audit_event` rows (append-only table; destructive on existing rows; no live data yet).
- S12: pepper rotation/compromise runbook before go-live.
- Merge rule for allergies (S4): the tech lead chose "keep any non-null text and combine both"; the human confirms against the mockup "Review and merge".

## Architect's post-review questions (ADR-009 Q15–Q18, ADR-010 Q8–Q9)
- Q17 (tech lead decided, safer direction): the first page carried by verify/reject answers also writes one record-access row per distinct patient (contract line in ipd.yaml `PharmacistActionResult` edited to say so; the Java fix pass was briefed that way).
- Q18 (tech lead position): a combined allergy/chronic text over the field limit is never cut silently; the java-developer reports what the code does and the human confirms.
- Still the human's: Q15 owner-as-prescriber (go-live blocker), Q16 clearing old audit text, ADR-010 Q8 `purpose` on `otp_challenge`, Q9 pepper rotation runbook, merge combine rule against "Review and merge".
- Contract re-approval needed at the `contract` gate for SAH-31: third-lock answer; replay rule (reason compared only if the first order stored one); queue entry for a missing patient; PIN problem-type descriptions; EMR lock lines; `GET /pharmacist-checks` and verify/reject writing record-access rows (no longer read-only); PIN limiter semantics (failed checks only); more allergy keywords and the NOT_RECORDED texts; re-reading `GET /me/pin` after `pin-not-set`; merged-patient allergy read.

## Java review fix pass: build choices to confirm (6 Nov 2026)
- Allergy stems `sulf`/`sulph` also match "sulfur", "sulfite", "ferrous sulphate": a patient with that text gets a hard stop on sulfa drugs (safer direction; clinical sign-off OQ-31-1/4).
- A penicillin brand or abbreviation (Augmentin, PCN, ampicillin, amoxycillin) gives the cephalosporin cross-reaction warning, never a hard stop.
- Merge combine rule as built: empty never overwrites; equal ignoring case keeps one; different texts joined kept-first with "; " and de-duplicated; placeholders ("None known", "Not recorded", "Unknown", "Not known", "?") are dropped when the other side says something real; the result is CUT at 400 characters (kept record's items first). The silent cut is a risk for allergy data: the human decides whether a merge should instead be refused or the field widened (ADR-009 Q18).
- PIN limiter as built: LOCKED and NOT_SET results also count as failed checks (ADR-010 D13 says only WRONG counts; the architect aligns the wording to the build or the human prefers WRONG only). Right PINs never consume allowance.
- The patient access port is per patient (2 queries per distinct patient on a page, up to 100 per list load); a batch method in the patient module is possible later.
- A merged-away admission's `EmrPatient.id` is now the kept patient's id.
