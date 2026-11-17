# SAH-77 · Production readiness (profiles, sign-in code hardening, SMS sender, access log, session limits, IPD switch, privacy notice, backup promise)
Module: platform
Mockup screen: Start screen and sign-in
Also uses: Foundation (no screen) for stories 1, 4, 5 (server side), 6 and 8; Clinic setup → Privacy, ABDM and backup (stories 7 and 8); Clinic setup → Team and access, block "Shared computer safety" (story 5); Clinic setup → Plan and modules (story 6, existing screen, no change).
Source: human, DEC-059 answer A plus "Tech lead defaults" (17 Nov 2026); findings of `docs/reviews/GO-LIVE-security.md` (#1, #2, #3, #5, #6, #7, #10, #12, #18, #21, #22) and `docs/reviews/GO-LIVE-opd-open-work.md` (#1, #2). Context: DEC-058 (answer B, real-patient date superseded by DEC-059).

## Problem
Next week Sahaj runs as a supervised pilot on **test data only** (DEC-059 A). The first real patient comes only when the security Blockers are closed. Today the application cannot be run safely outside a developer's machine:
- It starts with profile `dev` by default. `dev` accepts any 6-digit code for any mobile (including new-clinic sign-up), accepts any admin authenticator code, offers the 30-day owner IPD trial, and sets a refresh cookie that is not Secure. "Loopback only" depends on one setting that an environment variable or a same-host proxy can undo.
- No production sign-in exists: the only code sender writes the code to the server log and is limited to dev and test.
- The one-time code limits can be got round: two wrong codes at the same moment may count as one; a new code after 30 seconds resets the count; there is no per-address or per-day cap (SMS pumping and guessing); the code has no stated purpose (sign-in or setting a PIN).
- The access log has no client address, and behind a proxy the app cannot tell the request came over HTTPS, so the HSTS header is not sent.
- A signed-in session lasts 30 days and slides; there is no absolute limit and no idle limit.
- IPD can be switched on only by the owner's 30-day trial, after which it is read-only.
- There is no patient or staff privacy notice, and public pages promise "Daily backups, kept for 30 days" with nothing behind it.

This requirement closes the parts that the business analyst, architect, db-engineer, developers and QA can close without the human's provider choice, and states precisely what waits for the human.

## Who uses it (roles) and in which setting
| Who | Setting | What changes for them |
|---|---|---|
| Sahaj operator (the human and anyone they delegate; not a Sahaj clinic role) | Hosting, deployment, support | Starts the app with an explicit profile; runs the IPD switch runbook; delivers backups; supplies log shipping |
| owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist | Clinic front desk, doctor room, hospital ward, billing counter | Same sign-in screen; stricter limits; sessions end after the absolute or idle limit; some new messages (see open questions) |
| Patient | Front desk, ward | Reads the privacy notice (text only, draft) |
| Attackers / the unauthorised (no role) | Internet | Must not be able to guess codes, pump SMS, or use a dev bypass |

Admin portal (Sahaj team) sign-in is parked (DEC-059 default 1: admin realm stays parked). It is out of scope here except that prod must keep its bypass and realm off.

## In scope / Out of scope
**In scope (8 stories, one per item of the human's list)**
1. Profiles: explicit profile, `prod` profile, dev bypass for loopback callers only.
2. One-time code hardening: atomic failed count, one-use code, durable daily failure count, request caps, `purpose`, exact status and text per situation.
3. Provider-neutral code sender contract for prod (adapter waits for the human).
4. Access log with client address behind the proxy; protocol header so HSTS is sent.
5. Session limits on the server: absolute lifetime and idle timeout.
6. IPD switched on by an audited operator runbook, not by the 30-day trial.
7. Privacy notice: DRAFT text for patients and staff (Appendix A below), and where it may appear.
8. The "daily backups kept for 30 days" promise: operations deliver it, or the human changes the text.

**Out of scope (and who owns it)**
- The SMS provider adapter itself, DLT sender and template registration, provider contract: human first (OQ-77-2, OQ-77-3), then its own story and contract.
- Hosting, TLS certificate, separate prod database, restore drill, log shipping and 180-day retention, NTP, incident contact, monitoring (GO-LIVE-security #4, #6 ops part, #23): architect writes the deployment ADR and runbook, the human provisions.
- Git history of commit 5151b7e and GitHub remote (#11), secret scan in the validate gate (#19), `.env.example` comparison (#24), dependency scan (#25): human or other owners.
- HARD-1 composite foreign keys (#16), HARD-2 rate limits for PIN and writes (#13), Flyway version rule (#17), clinical sign-offs (#9): separate stories or human decisions.
- The look of the sign-in screen, the shell, the lock screen design, the privacy page design: the mockup decides; anything not drawn is an open question.
- Admin portal sign-in and support sessions (parked).
- Content-Security-Policy header (#18, CSP half): needs the front-end build to be checked; OQ-77-16.
- WhatsApp or any message other than the sign-in and PIN-set code (notifications are Later).

## Business rules (numbered)

**Profiles (story 1; security #1, #22)**
- BR-1. The application refuses to start when no profile is set explicitly. There is no default profile. The failure says which profile names are allowed (dev, test, prod) and opens no database connection.
- BR-2. Profile `prod` takes every setting that differs from dev from environment variables, with no default for any secret or address: database URL and passwords, JWT secret (at least 32 bytes), PIN pepper, allowed web origins, trusted proxies. A missing or too weak value stops the start and names the variable, never its value.
- BR-3. Profile `prod` has no one-time-code bypass, no admin authenticator bypass, no owner IPD trial and no dev tools. If any of these is switched on by environment variable under `prod`, the application refuses to start. The admin realm and its bootstrap stay off unless the human explicitly enables them (parked; OQ-77-17).
- BR-4. Profile `prod` sets the refresh cookie as HttpOnly, Secure and SameSite=Strict, and allows cross-origin calls only from an explicit list of origins read from the environment. An empty list means no cross-origin access. A wildcard is refused at start.
- BR-5. Profile `prod` starts only if exactly one real code sender is configured (story 3). The log-only sender cannot be active in `prod`.
- BR-6. In `dev`, the one-time-code bypass (any 6 digits) applies only when the caller's network address, as the server's own connection sees it, is loopback (127.0.0.1 or ::1) **and** the request carries no forwarded-address header. Any other caller gets the normal code path (code generated, stored, sent through the dev sender, wrong codes refused). Changing the bind address by environment variable must not widen the bypass.
- BR-7. Profile `test` behaves as today.

**One-time code hardening (story 2; security #3, #21)**
- BR-8. A wrong code is counted atomically. Several wrong codes at the same moment count as that many wrong codes; the fifth wrong code on a challenge locks the number for 15 minutes (existing rule, SAH-3 AC3).
- BR-9. A code works once. If two requests present the right code at the same moment, exactly one succeeds; the other gets "expired or used".
- BR-10. Wrong codes are also counted per mobile per day across all challenges. Asking for a new code does not reset the day's count. When the day's cap is reached the mobile is locked (cap and lock length: OQ-77-4, proposed 15 wrong codes in 24 hours, locked for 60 minutes).
- BR-11. Code requests are capped per mobile and per client address in a time window (OQ-77-4, proposed 5 per mobile per hour and 30 per client address per hour). A refused request sends no message and does not call the sender. Behind the proxy the client address follows the same trusted-proxy rule as story 4.
- BR-12. The same limits, and the same answers, apply to "Log in" and "New clinic". The answers never show whether a mobile has an account (SAH-3 enumeration rule).
- BR-13. Every challenge records its `purpose`. At least: sign-in (log in and new clinic) and PIN set. A code issued for one purpose never verifies another. Existing unused challenges at upgrade count as sign-in.
- BR-14. Every refusal has one fixed status, one problem code and one text (table in story SAH-77-2). Texts that the mockup or approved contract already has are reused; every other text is NEW and needs the human (OQ-77-6).
- BR-15. The code, the mobile number and the sender's response body are never logged outside the dev log-only sender. Audit rows keep the mobile masked (SAH-3 AC14).
- BR-16. A locked mobile is locked for everyone who uses that mobile, including a ward nurse or doctor. A locked-out clinical user has no unlock path other than waiting (this is the existing SAH-3 behaviour; OQ-77-5 asks whether that is acceptable).

**Code sender (story 3; security #2)**
- BR-17. Sign-in code delivery in `prod` goes through one provider-neutral sender contract: it receives the mobile, the code and the purpose, and answers sent or failed. No provider name, URL, key or library appears outside the adapter.
- BR-18. The provider adapter is built only after the human chooses a provider and completes TRAI DLT sender and template registration. Until then no adapter, provider SDK, provider URL or provider key exists in the repository.
- BR-19. If the sender fails or times out, the user is told the code could not be sent (NEW text, OQ-77-6); no code is stored as usable, or the stored code is invalidated; the attempt still counts against the request caps (BR-11) and not against the wrong-code counts (BR-8, BR-10).
- BR-20. The message carries the code and its validity only. It carries no clinic name, patient detail or link.
- BR-21. Scope: CLAUDE.md lists "notifications (WhatsApp/SMS)" as not in phase 1. The sign-in code is a platform sign-in need (SAH-3 AC7: "Send OTP", "code sent") and DEC-059 tells agents to build the neutral part now, but sending real SMS through an outside provider is a new external service. This requirement treats only the sign-in and PIN-set code as in scope; reminders, WhatsApp, support-session OTP and owner OTP for refunds and export stay Later until the human says otherwise (OQ-77-3).

**Access log and protocol header (story 4; security #6, #18)**
- BR-22. Every access log line carries the client address, with the timestamp (with time zone), method, path without values, status, duration, request id, and tenant id and user id when known.
- BR-23. The client address comes from the forwarded header only when the connection comes from a trusted proxy (configured list); otherwise it is the connection address. A header sent by an untrusted caller is ignored.
- BR-24. The log line never carries a person's name, mobile number, ABHA number, clinical text, token, cookie or code, and no query-string values.
- BR-25. When the trusted proxy says the request came over HTTPS, the application treats it as HTTPS: the strict-transport-security header is sent and redirects and cookies use https. On direct plain HTTP (local dev) no HSTS header is sent.
- BR-26. Retention (180 days, shipping, NTP) is an operations duty (OQ-77-18); the application only has to write the line.

**Session limits (story 5; security #12)**
- BR-27. A session has an absolute lifetime counted from the sign-in code. Rotating the refresh token never extends it. At the limit the user signs in again with a code. Proposed: 12 hours (OQ-77-8).
- BR-28. A session has an idle timeout counted from the last request the user made. After it, no token of that session works. Proposed: 30 minutes (audit range 15 to 30; OQ-77-8, OQ-77-9).
- BR-29. Both limits are checked on the server, for all roles equally, and read from configuration, not hard-coded.
- BR-30. "Log out on all devices", removing a person from the clinic, and a person switched off still end sessions at once or within the existing one-minute rule (SAH-3, SAH-6). These are not weakened.
- BR-31. The idle **screen lock** on ward and shared devices is not designed in the mockup (only its minutes setting is). It is not built here. OQ-77-9.

**IPD switch (story 6; security #7)**
- BR-32. In `prod`, IPD is switched on for a clinic only by an audited operator runbook (a script run as the migration role with the clinic set). The owner's 30-day trial is off. The owner can still ask for IPD (existing request), which the operator sees.
- BR-33. The runbook writes one audit row per use: who ran it, which clinic, when, the reason and a ticket reference. It changes one clinic only, is safe to run twice, and refuses an unknown clinic.
- BR-34. Before the switch the clinic gets 403 `module-off` for IPD; after it, IPD works with the access rules already built (SAH-22 to SAH-38, SAH-76). Nothing in tenancy or module boundaries changes.

**Privacy notice (story 7; security #10, #15)**
- BR-35. The draft notice is text for human and legal review, not legal advice. Anything that is a legal fact (law section, retention period, who is the data fiduciary, grievance officer, languages) is a placeholder or marked "confirm".
- BR-36. The notice may appear in the application only where the mockup shows a place. The mockup shows only the sentence on the sign-in card ("By continuing you agree to the Terms of Use and Privacy Policy. Patient data is stored in India.") and the Privacy screen's consent block. Any new page, link target, staff notice screen or print is NOT in the mockup (OQ-77-13).
- BR-37. The consent version recorded at sign-up (existing `termsVersion`, `privacyVersion`) changes only when the human approves a final text.
- BR-38. The notice must not contradict the mockup's Privacy screen ("Patient records are kept: Until you delete them"; "Encrypted, every night"). Where it must say more (backups keep deleted data for a time; legal retention), that is an open question (OQ-77-12, OQ-77-14).

**Backup promise (story 8; security #5)**
- BR-39. Public pages say "Daily backups, kept for 30 days" (landing trust list and FAQ in the mockup; also in the app's FAQ, trust and data pages). Before the first real patient, operations must deliver it with evidence (encrypted nightly backup in India, 30 days kept, one written-up restore drill), or the human changes the text in `docs/design` and the angular developer follows.
- BR-40. The pill "Last: 2:00 AM today" on the Privacy screen and "Restore drill passed" must show a true value or must not be shown. The mockup shows them; showing a made-up value on a real clinic is a risk (OQ-77-15).

## Patient-safety and privacy points
- Lock-out is a safety risk. A wrong-code attack can lock a doctor or nurse out of a ward device for 15 minutes today, and for longer with the new daily cap (BR-10, BR-16). OQ-77-5 asks the human to accept this or choose a per-(mobile, address) lock. No unlock path exists in the mockup.
- Session limits can end a session in the middle of work. A 12-hour absolute limit can fall in the middle of a night shift; unsaved typing is not kept in browser storage (no patient data in web storage is a project rule). OQ-77-8.
- Emergency care is not blocked by the limits: they apply to sign-in and sessions, not to break-glass or emergency access once signed in. The limits must not change those flows.
- No code, mobile number, name or clinical text in logs (BR-15, BR-24). The mobile number goes to the SMS provider: this is a disclosure to a processor and must be in the notice (Appendix A).
- Client address in the access log is personal data of staff; it is listed in the staff notice (Appendix A).
- Real patients only after the Blockers close (DEC-059). Until then every pilot uses test data; no screen or document may suggest otherwise.
- The deleted-record promise: deleted records stay in backups for up to the backup period. The notice must say so (Appendix A).

## Plan or entitlement (which plan/module unlocks it)
- Stories 1 to 5, 7 and 8 are for every clinic on every plan. No plan unlocks them.
- Story 6 concerns the IPD module entitlement (off, trial, on, read-only). In `prod` the only way to "on" is the operator runbook. Whether IPD is a paid add-on and who authorises it commercially is outside the application (OQ-77-11).

## Mockup coverage (what is drawn, what is not)
| Mockup element (Start screen and sign-in) | Drawn | Covered here |
|---|---|---|
| Toggle "Log in" / "New clinic", heading, +91 mobile field, "Send OTP" | Yes | Unchanged; limits apply to both (BR-12) |
| "Enter a 10-digit mobile number" (toast) | Yes | Story 2 AC (invalid input) |
| "Code sent to +91 …" (toast), "Enter the 6-digit code sent to +91 …", "Verify and continue", "Change number" | Yes | Unchanged |
| "Enter the 6-digit code" (toast) | Yes | Story 2 AC (invalid input) |
| "Resend code in 0:28" countdown | Yes | Story 2: countdown comes from the server value after every request |
| Hint "Mockup: any 6 digits work" / "98220 00001 opens an existing clinic" | Yes (mockup note only) | Not part of any production screen; only in dev (story 1) |
| Wrong code, expired code, locked, too many requests, could not send | No | Existing SAH-3 / contract texts reused; the rest NEW (OQ-77-6) |
| Session ended message and sign-in prompt | No | OQ-77-10 |
| Terms and Privacy line under the card | Yes | Story 7 (text only changes after human approval) |
| Clinic setup → Team and access → "Lock the screen when no one uses it for" 5 to 30 min (owner only), "PIN sign-in", "OTP to the owner's phone for refunds and data export" | Setting drawn, locked screen not drawn | Story 5 server limits only; OQ-77-9 |
| Clinic setup → Privacy, ABDM and backup: consent block, "Patient records are kept: Until you delete them", "Backup: Encrypted, every night, Last: 2:00 AM today", "Servers in India (planned)" | Yes | Stories 7, 8 |
| Landing: "Daily backups, kept for 30 days", FAQ "Where is patient data stored?" | Yes | Story 8 |

Everything not in the right column is **not in the mockup** and goes to the human: see the open questions.

## Open questions for the human
Each has a proposed answer where one is sensible; none is assumed in the stories except where the story says "default".

- **OQ-77-1 (blocks the pilot sign-in).** The `prod` profile starts only with a real code sender (BR-5), and the SMS adapter does not exist yet (BR-18). The dev bypass is now loopback only (BR-6). So how do pilot users sign in next week? Options: (a) the pilot runs on one machine in `dev` and only that machine's browser is used (no tablets); (b) a separate pilot profile with the log-only sender, codes read by the operator from the server log, on a private network, test data only, never exposed to real patients (needs the human's explicit written acceptance because it is a dev feature outside a dev machine); (c) the human chooses the SMS provider early and uses its test sender. Recommended: the human picks; BA does not.
- **OQ-77-2 (human).** SMS provider choice, TRAI DLT sender id and template registration, and the **channel**: the mockup sign-in does not say whether the code arrives by SMS or WhatsApp (the admin support OTP and staff login links in the mockup say WhatsApp). WhatsApp is Later. Confirm SMS only.
- **OQ-77-3 (scope, human).** CLAUDE.md says notifications (WhatsApp/SMS) are not in phase 1, yet sign-in needs a delivered code. Confirm: SMS for the sign-in code and PIN-set code only is a permitted exception; everything else in notifications stays Later (BR-21).
- **OQ-77-4 (human).** Limits. Existing: 5 wrong codes per code lock 15 minutes; resend after 30 seconds; code valid 5 minutes. Proposed new: day cap of 15 wrong codes per mobile, locked 60 minutes; 5 code requests per mobile per hour; 30 per client address per hour (a clinic's staff may share one public address, so the address cap is generous). Confirm or change.
- **OQ-77-5 (human, safety).** A locked mobile locks a doctor or nurse out, and an attacker who knows a mobile can do it on purpose. Accept the per-mobile lock (security #21 "accept"), or lock per (mobile, client address)? Is there any unlock path for a ward user (owner action, support), or only waiting? The mockup draws none.
- **OQ-77-6 (human; texts not in the mockup).** The mockup has only "Enter a 10-digit mobile number", "Code sent to +91 …", "Enter the 6-digit code", "Resend code in 0:28". The approved contract (SAH-3) already has: "That code is not right.", "That code has expired. Request a new one.", "Too many wrong codes. Try again in 15 minutes.", "Please wait before requesting another code.", and "No code was sent to this number." These are NEW to the mockup and need sign-off. Texts that story 2 and 3 add (proposed): daily lock "Too many wrong codes today. Try again in 60 minutes."; request cap "Too many codes requested. Try again in a few minutes."; sender failure "We could not send the code. Try again in a minute."; wrong purpose uses "That code is not right." (no new text).
- **OQ-77-7 (human).** If the SMS provider is down, what should the user do? Proposed: the text in OQ-77-6 and try again; no other channel. Confirm.
- **OQ-77-8 (human, values).** Absolute session lifetime (proposed 12 hours) and idle timeout (proposed 30 minutes; audit range 15 to 30). A 12-hour limit can end a night shift's session; consider 16 hours for ward roles, or a limit per role. Values come from configuration, so the answer can change later without code.
- **OQ-77-9 (not in mockup).** The mockup has the owner setting "Lock the screen when no one uses it for N min" (5 to 30, default 10) but no locked screen and no unlock steps. Needed from the human: is the screen lock built in phase 1 (ward devices), what does the locked screen look like and what unlocks it (own PIN when PIN sign-in is on; the code otherwise?), and is the server idle timeout the same number as this setting or a fixed value? Story 5 builds the server limits only.
- **OQ-77-10 (not in mockup).** What the user sees when the session ends (text, button) and what happens to text typed but not saved (the project keeps no patient data in browser storage).
- **OQ-77-11 (human).** IPD switch: who may run the runbook, what evidence (owner's written request, payment, plan) is needed, the reason and ticket format, and the procedure and data rules for switching IPD off again (it becomes read-only today after the trial).
- **OQ-77-12 (legal, human).** Appendix A is a draft. Needed from counsel or the human: who is the data fiduciary and who the processor (clinic or Sahaj), grievance officer name and contact, retention period for patient records (the mockup says "Until you delete them"), the emergency lawful basis wording, whether the notice must also be in Marathi or Hindi (the mockup consent block has an optional Marathi line), children's data, the DPO question, and a breach notification line.
- **OQ-77-13 (not in mockup).** Where the full notice is shown: the mockup has only the words "Terms of Use and Privacy Policy" on the sign-in card and a one-line consent in Clinic setup. There is no page, link target, print or staff notice. Does the human add a design, or is the notice given on paper at the front desk for now?
- **OQ-77-14 (human).** The mockup's consent line says the clinic may send "visit reminders on WhatsApp", and WhatsApp is Later. Keep the line, or change it until sending exists? Also: when the final notice is approved, must existing users and patients consent again (new consent version)?
- **OQ-77-15 (human, `docs/design` is human-owned).** The backup promise (BR-39): will operations deliver 30 days with a restore drill before the first real patient, or will the human change the text on the landing page, FAQ and trust list? And the mockup's "Last: 2:00 AM today", "Servers in India (planned)" and the admin "Restore drill passed" must be true or removed (BR-40).
- **OQ-77-16 (human, architect).** Is the Content-Security-Policy header (security #18) part of this work? It can break the Angular build if added late; proposed: separate story after the pilot. HSTS max-age value and whether to preload: architect proposes, human confirms.
- **OQ-77-17 (human).** Should `prod` refuse to start when an admin or dev flag is on (BR-3), or just ignore it? Proposed: refuse. Also confirm the human compares the environment list in security #24 with `.env.example`.
- **OQ-77-18 (human/ops).** 180-day retention of the access log is read from security #6 (CERT-In). Confirm the period and where logs are stored; the application only writes them.
- **OQ-77-19 (process).** The human asked for 8 numbered stories. The requirement-analysis skill allows 1 to 6 per file; stories 3, 7 and 8 depend on human or operations action. The tech lead may split the file (for example SAH-77 stories 1 to 6 and a separate ops file) if approval in `docs/approvals.md` is easier that way.

## Traceability: rule to story
| Rules | Story |
|---|---|
| BR-1 to BR-7 | SAH-77-1 |
| BR-8 to BR-16 | SAH-77-2 |
| BR-17 to BR-21 | SAH-77-3 |
| BR-22 to BR-26 | SAH-77-4 |
| BR-27 to BR-31 | SAH-77-5 |
| BR-32 to BR-34 | SAH-77-6 |
| BR-35 to BR-38 | SAH-77-7 |
| BR-39, BR-40 | SAH-77-8 |

## Appendix A · DRAFT privacy notice
(The human asked for the draft in its own file `docs/requirements/SAH-77-privacy-notice-draft.md`. The gate `validate docs` accepts only files named `SAH-<number>.md` in this folder, so the draft is kept here as Appendix A to keep the gate passing. The human may move it.)

> **DRAFT, needs legal review by the human. Not legal advice. Not approved. Do not publish, print or show to patients or staff.**
> Everything in [square brackets] is a placeholder or a point to confirm. Text is plain English, sentence case.

### A1. Patient notice: how [Clinic name] uses your information

**Who we are.** [Clinic name], [address], looks after you. We use Sahaj, a software service, to keep your records. Sahaj is run by [Sahaj legal entity name and address]. [CONFIRM with counsel: which of the two is the data fiduciary and which is the processor.]

**Why we keep your information (purposes).**
- To treat you: your visits, notes, prescriptions, test results, and, if you are admitted, your ward records.
- To run your visit: tokens, appointments, follow-up dates and billing.
- To keep you safe: for example allergy warnings, medicine checks and an audit trail of who opened your record.
- To meet the law: records we must keep, and requests from authorities where the law requires it. [CONFIRM list.]
- [Only if the clinic sends them and you agreed: visit reminders on WhatsApp. WhatsApp sending is not available yet.]

**What we keep.** Your name, age, sex, mobile number, address, ABHA number if you give it, health information you or your doctor give us, bills and payments, and the date and name of the staff member who recorded your consent. We do not keep your fingerprints or photos unless a treatment needs them and we tell you first. [CONFIRM.]

**Who can see it.** Only staff of [Clinic name] whose work needs it, according to their role (for example, front desk see your name and visit, not your clinical notes). Every time a record is opened, the system notes who and when. Sahaj staff do not see your records unless the clinic owner approves a support session. [CONFIRM: current product behaviour; the admin support session is parked.]

**Emergencies.** If you are brought in unwell or unconscious, we will treat you and open or create a record even if we could not ask you first, because of the emergency. [CONFIRM the lawful basis wording with counsel; the DPDP Act 2023 provides a use for responding to a medical emergency.] We will tell you afterwards.

**Who else gets part of it.** Our hosting provider ([name], data centre in India [CONFIRM]) stores it. Our text-message provider ([name, after the human chooses]) receives only the mobile number of staff who sign in, with a code. We do not sell your information. [CONFIRM list of processors.]

**Where it is kept.** On servers in India. [CONFIRM when hosting is live.]

**How long we keep it.** [X years after your last visit. COUNSEL to set. The product today says "Until you delete them".] If you ask us to delete something and the law allows it, it is removed from the system straight away and from backups when the backup is replaced, within [30] days. [CONFIRM: depends on the backup promise, story SAH-77-8.]

**Your choices and rights.** You can ask to see your information, to have a mistake corrected, to have it deleted where the law allows, to withdraw a consent you gave, and to name a person to act for you. Tell the front desk, or write to the grievance officer. [CONFIRM list against the Act.] Asking for a copy of your records needs a code sent to your mobile number.

**Grievance officer.** [Name], [role], [mobile], [email], [office hours]. [HUMAN to fill.] If we do not answer in [timeframe, CONFIRM], you may complain to the Data Protection Board of India. [CONFIRM.]

**Children and people who cannot decide for themselves.** A parent or guardian gives the consent. [CONFIRM with counsel.]

**If something goes wrong.** If your information is lost or seen by someone who should not see it, we will tell you and the authorities as the law requires. [CONFIRM wording and timing.]

**Changes.** We will tell you at the front desk if this notice changes. Version [x.y], dated [date].

*Language:* English. [Marathi / Hindi versions: COUNSEL to say whether required; the mockup has an optional Marathi consent line.]

### A2. Staff notice: how Sahaj and [Clinic name] use your information at work

**Who this is for.** Everyone who signs in: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist.

**What we keep about you and why.**
- Your name, role and mobile number: to sign you in and to give you the right access.
- A one-time code sent to your mobile by text message: to prove it is you. The code is not kept in clear and is not written in our logs.
- Your 4-digit PIN, if your clinic uses PIN sign-in: kept only in a scrambled form; nobody can read it.
- Your device identifier on ward devices, when the clinic uses it: to show which device made an entry. [CONFIRM: "staff device id" is on the register of exceptions, security #15; confirm exactly what is stored.]
- What you do in the system: sign-ins, which records you open, entries you make, with date, time, and the address of the network you used (IP address). This is the audit trail the clinic needs for patient safety, and the law requires logs to be kept for [180 days, CONFIRM, CERT-In].
- Doctor registration number, if you are a doctor.

**Who sees it.** The clinic owner, and Sahaj operations staff for running the service. Your sign-in and activity records are not used to judge your work unless the clinic [CONFIRM policy].

**Emergency ("break-glass") access.** If your role lets you open a record in an emergency, we keep a note of who, when and why. The clinic owner reviews it.

**How long.** Your login details while you work at the clinic; after you leave, your past entries stay under your name for [period; the mockup says past entries stay]. Logs for [180 days, CONFIRM]. [COUNSEL to set.]

**Your rights and who to ask.** As for patients (A1): access, correction, and complaint to [grievance officer, same placeholder].

**Version [x.y], dated [date].**

### A3. What this draft does not decide (all in the open questions)
Fiduciary and processor roles, retention periods, language duties, children, breach timing, whether existing users consent again, and where the notice appears in the app (the mockup has no place for it).
