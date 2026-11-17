# SAH-77 · Stories: Production readiness
Module: platform
Mockup screen: Start screen and sign-in
Also uses: Foundation (no screen) for stories 1, 4, 5 (server side), 6 and 8; Clinic setup → Privacy, ABDM and backup (stories 7, 8); Clinic setup → Team and access, block "Shared computer safety" (story 5).
Requirement: docs/requirements/SAH-77.md (business rules BR-1 to BR-40, open questions OQ-77-1 to OQ-77-19, Appendix A draft privacy notice).
Source: human, DEC-059 answer A and "Tech lead defaults", 17 Nov 2026; docs/reviews/GO-LIVE-security.md.

Reading guide. Next week is a supervised pilot on test data only (DEC-059). The first real patient comes when the security Blockers close. Numbers in brackets such as (proposed) are defaults for the human to confirm (OQ-77-4, OQ-77-8); they come from configuration, not from code. Texts marked NEW are not in the mockup and need the human (OQ-77-6). Roles: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist. "Operator" is the human or someone they delegate; it is not a clinic role. Time and money formats as CLAUDE.md. The work is more than the skill's 1 to 6 stories because the human asked for eight numbered ones (OQ-77-19); each fits one sprint, and story 2 is the largest.

Common rules (each AC assumes them): the clinic is taken from the signed-in user, never from the request body; nothing here logs a code, mobile number, name or clinical text; problem responses follow RFC 9457 as the platform already does.

---

### SAH-77-1 · As the Sahaj operator, I want the application to refuse to start without an explicit profile, and the production profile to have no bypass, so that a real clinic can never run on a profile that accepts any sign-in code
Priority: Must
Screen: Foundation (no screen). Rules BR-1 to BR-7. Security #1, #22.

## Acceptance criteria
Start-up
- Given no profile is set (no environment variable and no argument), when the application starts, then it stops before it listens on a port or opens a database connection, and the message says that a profile is required and that the allowed names are dev, test and prod.
- Given a profile name that is not dev, test or prod, when the application starts, then it stops with the same kind of message.
- Given profile prod and a missing value for the database URL, a database password, the JWT secret or the PIN pepper, when the application starts, then it stops and names the missing variable. The value is never printed.
- Given profile prod and a JWT secret shorter than 32 bytes, when the application starts, then it stops (existing rule, kept).
- Given profile prod, when it starts, then the log shows the active profile name and nothing secret.
Production profile has no bypass
- Given profile prod and the one-time-code bypass setting turned on by an environment variable, when the application starts, then it stops.
- Given profile prod and the admin authenticator bypass, the admin realm or its bootstrap, the owner IPD trial, or dev tools turned on, when the application starts, then it stops (OQ-77-17 default: refuse).
- Given profile prod, when any person asks for a code and then sends the code 000000 or any code that was not sent, then the answer is the normal wrong-code refusal, for log in and for new clinic.
- Given profile prod and no real code sender configured (story 3), when the application starts, then it stops with a message naming the missing sender. The log-only sender is never registered in prod.
Cookie and cross-origin
- Given profile prod, when a user signs in, then the refresh cookie is HttpOnly, Secure and SameSite=Strict.
- Given profile prod and no allowed origins configured, when a browser from another origin sends a cross-origin request, then no cross-origin permission header comes back.
- Given profile prod and a list of allowed origins, when a request comes from an origin on the list, then it is allowed; from any other origin it is not.
- Given profile prod and a wildcard origin configured, when the application starts, then it stops.
Dev bypass only for loopback callers
- Given profile dev with the bypass on, when a request comes from 127.0.0.1 or ::1 with no forwarded-address header, then any 6-digit code is accepted, as today.
- Given profile dev with the bypass on and the server listening on all addresses (bind address changed by environment variable), when a request comes from a different machine (for example a tablet at 192.168.x.x), then the bypass does not apply: a code is generated and stored, the dev log-only sender is used, and a wrong code is refused.
- Given profile dev with the bypass on, when a request arrives from loopback but carries a forwarded-address header (a proxy on the same machine), then the bypass does not apply.
- Given profile dev, when the bypass is refused for a caller, then the response is the same as for any wrong code; it does not say that a bypass exists.
- Given profile test, when the existing test suite runs, then its behaviour is unchanged.
Empty or bad input
- Given an environment variable that is set but empty, when the application starts in prod, then it counts as missing.

## Notes for design
Architect and java-developer: how the loopback check reads the connection address (the same way the admin bypass already does). Pilot sign-in is OQ-77-1; this story does not create a pilot profile.

---

### SAH-77-2 · As any signed-in role (owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist), I want the sign-in code to be safe against guessing and message flooding, so that only I can sign in as myself and nobody can run up the clinic's message bill
Priority: Must
Screen: Start screen and sign-in (mobile field, "Send OTP", code field, "Verify and continue", "Resend code in 0:28"); no layout change. Rules BR-8 to BR-16. Security #3, #21.

## Acceptance criteria
Counting and one-use
- Given a code request for a mobile, when 5 wrong codes arrive at the same moment (parallel requests), then the challenge ends locked, the stored failed count is not lower than 5, and no request after the lock is accepted even with the right code.
- Given a right code, when two verify requests with it arrive at the same moment, then exactly one succeeds and the other is refused as expired or used.
- Given a challenge that was already used, when the same code is sent again, then it is refused as expired or used.
Day count
- Given a mobile with 3 wrong codes on challenge A, when the user asks for a new code after 30 seconds and enters 3 wrong codes on challenge B, then the day count is 6, not 3 (the new code did not reset it).
- Given a mobile whose wrong codes in the last 24 hours reach the day cap (proposed 15), when the user asks for a code or verifies, then the answer is 423 `otp-locked` with the day-lock text and the time left; the right code does not unlock it.
- Given the day lock has ended, when the user asks for a code, then a new code is sent and the day count continues from the failures still inside the 24 hours.
- Given a locked mobile, when a request for a code arrives, then no message is sent.
Request caps
- Given a mobile that has asked for the capped number of codes in the window (proposed 5 per hour), when it asks again, then the answer is 429 `rate-limit` with `retryAfterSeconds`, no code is created and the sender is not called.
- Given one client address that has asked for the capped number of codes in the window (proposed 30 per hour) across different mobiles, when it asks again, then the answer is 429 `rate-limit`, for any mobile.
- Given the application is behind the proxy, when the client address is measured, then it follows the trusted-proxy rule of story 4: an untrusted caller cannot change its address by sending a header.
- Given a request refused by a cap, when the answer is read, then it is identical for a registered and an unregistered mobile and for log in and new clinic (no account is revealed).
- Given a user who asks for a new code earlier than 30 seconds after the last, then the answer is 429 as today with the seconds to wait, and "Resend code in 0:28" counts from the server's value.
Purpose
- Given a code asked for sign-in (log in or new clinic), when it is sent to the PIN-set check, then it is refused with the normal wrong-code answer, and the reverse is also refused.
- Given a challenge without a purpose, when the application stores it, then it is rejected (a purpose is required).
- Given unused challenges that exist when this change is deployed, then they count as sign-in.
Exact answers the user sees
| Situation | Status, problem code | Text | Source |
|---|---|---|---|
| Mobile is not 10 digits | 400 | "Enter a 10-digit mobile number" | Mockup toast |
| Code is not 6 digits | 400 | "Enter the 6-digit code" | Mockup toast |
| Code sent | 202 with `resendAfterSeconds` 30 | "Code sent to +91 …", "Resend code in 0:28" | Mockup |
| Resend before 30 seconds | 429 with `retryAfterSeconds` | "Please wait before requesting another code." | Contract SAH-3 (not in mockup) |
| Wrong code | 401 | "That code is not right." | Contract SAH-3 (not in mockup) |
| Code expired or already used | 401 | "That code has expired. Request a new one." | Contract SAH-3 (not in mockup) |
| No code was sent to this number | 401 | "No code was sent to this number." | Existing (not in mockup) |
| 5 wrong codes on one code | 423 `otp-locked` with `retryAfterSeconds` 900 | "Too many wrong codes. Try again in 15 minutes." | Contract SAH-3 (not in mockup) |
| Day cap of wrong codes | 423 `otp-locked` with `retryAfterSeconds` | "Too many wrong codes today. Try again in 60 minutes." | NEW (OQ-77-6) |
| Request cap per mobile or per address | 429 `rate-limit` with `retryAfterSeconds` | "Too many codes requested. Try again in a few minutes." | NEW (OQ-77-6) |
| Code could not be sent | 503 | "We could not send the code. Try again in a minute." | NEW (OQ-77-6), see story 3 |
- Given any of the answers above, when the body is read, then it contains no code, no hash, no other person's data, and the mobile number only if the existing contract already returns it.
- Given any of the situations above, when the server log and the audit rows are read, then they contain no code and no full mobile number (masked in audit, as SAH-3 AC14).
Roles and tenants
- Given any role in any clinic, when they sign in, then the same limits apply; no role is exempt.
- Given a sign-in code request, when it is handled, then no clinic is selected yet; a code for one mobile never lets the user into another mobile's clinic.
Slow network
- Given the user taps "Send OTP" or "Verify and continue" twice, when the first request is still running, then only one request is sent (button disabled while it runs) and the screen shows the one answer. (Front-end rule; the server also counts a duplicate once per the caps above.)

## Notes for design
Architect: the table is the contract text for `/auth/otp/request` and `/auth/otp/verify`; the new problem codes need entries. DB engineer: the day count must survive restarts and work across several app instances later (no in-memory counter); `purpose` is a new column. The existing locked case for a doctor (security #21) is OQ-77-5.

---

### SAH-77-3 · As the Sahaj operator, I want the sign-in code to go out through a provider-neutral sender that production requires, so that real sign-in works as soon as the human has chosen an SMS provider and registered the sender and template
Priority: Must (the neutral contract); the adapter is BLOCKED on the human (OQ-77-2, OQ-77-3)
Screen: Start screen and sign-in ("Send OTP", "Code sent to +91 …"). Rules BR-17 to BR-21. Security #2.

## Acceptance criteria
Contract
- Given the sender contract, when a code is sent, then it receives the mobile, the code and the purpose, and answers sent or failed; no provider type, key, URL or response body is visible outside the adapter.
- Given profile prod, when no sender other than the log-only one exists, then the application does not start (story 1).
- Given profile dev or test, when a code is asked for, then the log-only or test sender is used; this is the only place a code may be written to a log.
Failure and safety
- Given the provider answers with an error or does not answer within the time limit (set by the architect), when the user asked for a code, then the answer is 503 "We could not send the code. Try again in a minute." (NEW, OQ-77-6), the request is not kept waiting, and no usable code remains for that attempt.
- Given a failed send, when the caps are counted, then the attempt counts against the request caps and does not count as a wrong code.
- Given a failed send, when the server log is read, then it shows the failure reason class and request id, and not the code or the mobile number.
- Given the message is sent, when its text is read, then it carries the code and its validity (5 minutes) and nothing else: no clinic name, no patient detail, no link. (The wording must match the registered template: OQ-77-2.)
- Given one request, when the provider is slow, then the code is sent once; there is no automatic second send.
Not before the human
- Given the human has not chosen a provider, when the repository is read, then it holds no provider adapter, SDK, URL or key.
- Given the human has chosen a provider and registered the template, when the adapter is added, then it is a separate story with its own contract and security review (DEC-059).
Scope
- Given this story, when it is reviewed, then only the sign-in and PIN-set codes are sent; no reminder, WhatsApp, support-session or owner OTP message exists (BR-21, OQ-77-3).
Other roles and tenants
- Given any role, then the sender does not know the clinic; the code message is the same for every clinic.

## Notes for design
Scope tension, stated plainly: CLAUDE.md says notifications (WhatsApp and SMS) are not in phase 1, and the screen map marks the notifications service Later. The sign-in code is not a notification feature, but delivering it by SMS is a new external service. This story builds the neutral contract only. OQ-77-1 (how pilot users sign in meanwhile) must be answered before next week.

---

### SAH-77-4 · As the Sahaj operator, I want every request in the access log to show the real client address and the application to know it is served over HTTPS, so that we can trace incidents and browsers are told to use HTTPS only
Priority: Must
Screen: Foundation (no screen). Rules BR-22 to BR-26. Security #6, #18.

## Acceptance criteria
Access log
- Given a request, when it finishes, then one log line has the timestamp with time zone, method, path without values, status, duration, request id, client address, and tenant id and user id when known.
- Given a request from a trusted proxy with a forwarded-address header, when the line is written, then the client address is the one in the header.
- Given a request from a caller that is not a trusted proxy and that sends a forwarded-address header, when the line is written, then the header is ignored and the connection address is logged.
- Given a request with several forwarded addresses, when the line is written, then the address used is the first one that is not a trusted proxy, counted from the right.
- Given any request, when the line is read, then it holds no name, mobile number, ABHA number, clinical text, token, cookie or code, and no query-string values.
- Given a request that fails with 401, 403, 404, 429 or 500, when the line is written, then it is written too, with the address.
- Given the application has no trusted proxies configured (local dev), when a request arrives, then the connection address is logged.
HTTPS awareness
- Given a request from a trusted proxy with the protocol header saying https, when the answer is built, then it carries the strict-transport-security header, and any redirect uses https.
- Given a request over plain http directly (local dev, no proxy), when the answer is built, then no strict-transport-security header is sent.
- Given a request from an untrusted caller with the protocol header saying https, when the answer is built, then the header is ignored.
- Given profile prod, when the answer is a cookie, then it is Secure (story 1).
Out of scope here
- Given retention (180 days, shipping, NTP), then it is the operator's duty (OQ-77-18); the content-security-policy header is OQ-77-16.

## Notes for design
Architect: the deployment ADR and runbook set the proxy, the trusted-proxy list and the header names; the app only reads them from configuration (already named in the audit: remote-IP header and trusted proxies).

---

### SAH-77-5 · As any signed-in role, I want my session to end after a fixed total time and after a period of no use, so that a phone, tablet or ward computer left open does not give a stranger access to patients
Priority: Must (server limits); the idle screen lock on devices waits for design (OQ-77-9)
Screen: Foundation (no screen) for the server limits. Clinic setup → Team and access, "Shared computer safety", "Lock the screen when no one uses it for" (setting drawn; the locked screen is not drawn). Rules BR-27 to BR-31. Security #12.

## Acceptance criteria
Absolute lifetime
- Given a user who signed in with a code at 8:00 AM IST, when they use the app all day and the token is refreshed again and again, then at the absolute limit (proposed 12 hours; OQ-77-8) the next refresh is refused with 401 `session-expired` and the user must sign in with a new code.
- Given a refresh token that was rotated, when the new one is used, then its end time is still the sign-in time plus the limit, not a new 12 hours.
Idle timeout
- Given a user who made no request for the idle limit (proposed 30 minutes; OQ-77-8), when any request is then made with that session's tokens (access or refresh), then it is refused with 401 `session-expired`.
- Given a user who made a request inside the idle limit, when the next request comes, then it works, and the idle time starts again.
- Given a silent background refresh while the user does nothing, then it does not count as use.
Same for all
- Given any role (owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist) in any clinic, when the limits apply, then they are the same; the values come from configuration.
- Given a wrong-clinic token, then the limits do not change what the clinic can see (tenant rules unchanged).
Existing rules stay
- Given "Log out on all devices", a person switched off, or a removed membership, then the session still ends at once or within the existing one-minute rule.
- Given a user signed in on two devices, when one session ends by a limit, then the other session is not ended by that.
Patient safety
- Given a limit ends a session while a form is open, when the user signs in again, then the server has not saved anything the user did not send; nothing was half-saved by the limit itself.
- Given a session ends, then the limits do not touch the emergency or break-glass flows that need a signed-in user: the user signs in again and uses them as before.
Screen lock and messages (waiting for design)
- Given the mockup has no locked screen and no "session ended" text, when this story ships, then the app shows only what already exists for a 401 from refresh (back to the sign-in screen). The text and the screen lock are OQ-77-9 and OQ-77-10.
- Given the owner opens Team and access, when the "Lock the screen when no one uses it for" setting is shown, then it keeps working as already built; it is not changed here, and whether the server idle timeout follows it is OQ-77-9.

## Notes for design
Architect: where the last-use time is kept and how often it is written (not on every request if that costs too much). Today refresh slides 30 days and has no absolute end.

---

### SAH-77-6 · As the Sahaj operator, I want to switch IPD on for a hospital through an audited runbook, so that a hospital gets IPD without the 30-day owner trial and every switch-on can be traced
Priority: Must
Screen: Foundation (no screen). Clinic setup → Plan and modules is the existing screen where the owner sees IPD; it changes only by hiding the trial offer. Rules BR-32 to BR-34. Security #7.

## Acceptance criteria
Runbook
- Given the operator runs the IPD runbook for one clinic with the migration role and the clinic set, when it finishes, then that clinic's IPD state is on and one audit row records who ran it, which clinic, when, the reason and the ticket reference.
- Given the runbook is run again for the same clinic, then nothing changes and no second row hides the first (safe to run twice; it may add a "no change" row).
- Given a clinic that does not exist, when the runbook runs, then it changes nothing and says so.
- Given the runbook runs for clinic A, when clinic B is read, then B's state is unchanged (other tenants untouched).
- Given the runbook is run without a reason or ticket reference, then it refuses.
- Given the application's own database role (not the migration role), when it tries the runbook, then it is denied.
Application in prod
- Given profile prod, when the owner opens Plan and modules, then the 30-day trial is not offered (`ipdTrialAvailable` is false) and a call to start the trial is refused.
- Given profile prod and IPD off, when the owner asks for IPD, then the request is recorded as today and the operator can see it; IPD stays off.
- Given IPD off, when a doctor, nurse or any other role opens an IPD screen or calls an IPD route, then the answer is 403 `module-off`.
- Given the runbook has set IPD on, when a doctor or nurse opens IPD, then IPD works under the roles and ward scoping already built; front desk and billing keep only what they had.
- Given an owner or any other clinic user, when they try to turn IPD on through the API, then it is refused (role check on the server).
Switch-off and read-only
- Given the human decides how IPD is switched off again (OQ-77-11), then that is a separate runbook; this story does not switch IPD off and does not change the read-only behaviour that exists today.

## Notes for design
DB engineer owns the runbook file (`docs/database/runbook-ipd-on.sql` per DEC-059). Whether IPD on is paid for and who authorises it is outside the application (OQ-77-11). The admin portal stays parked.

---

### SAH-77-7 · As the owner, a patient, and staff, I want a plain-English privacy notice that says what is kept, why, for how long and who to complain to, so that people know what happens to their information before real patient data is used
Priority: Must (the draft is delivered with this requirement; publishing waits for legal review)
Screen: Start screen and sign-in (the line "By continuing you agree to the Terms of Use and Privacy Policy. Patient data is stored in India.") and Clinic setup → Privacy, ABDM and backup (Patient consent block). Rules BR-35 to BR-38. Security #10, #15.

## Acceptance criteria
The draft
- Given the draft in Appendix A of docs/requirements/SAH-77.md, when the human reads it, then it is marked "DRAFT, needs legal review by the human", has a patient notice (A1) and a staff notice (A2), and every legal fact is a placeholder in [square brackets] or marked "confirm".
- Given the patient notice, when it is read, then it states purposes, what is kept, who sees it, the emergency lawful basis (to be confirmed), recipients (hosting, text-message provider), place of storage, retention (placeholder), rights, grievance officer (placeholder) and how to complain.
- Given the staff notice, when it is read, then it states the mobile and role, the one-time code by text message, the PIN stored scrambled, the staff device id (to be confirmed), activity records with the network address, the log retention (to be confirmed), break-glass notes and how long things are kept.
- Given the draft, when it is compared with the mockup's Privacy screen, then it does not contradict "Patient records are kept: Until you delete them" or "Encrypted, every night"; the places where it must say more are listed in OQ-77-12 and OQ-77-14.
- Given the draft, when it is read for tone, then it is plain English, sentence case, with no legal Latin and no promise the product does not keep (for example no WhatsApp sending, no backup claim) unless marked "confirm".
In the app (only after the human approves a final text)
- Given the human approves a final text, when a person signs up, then the sign-in card keeps the sentence and the consent checkbox as in the mockup, and the stored consent record carries the new version.
- Given the mockup has no page, link target, print, or staff screen for the notice, when this story ships, then none is built; the human decides in OQ-77-13 whether the notice is first given on paper at the front desk or a design is added to the mockup.
- Given the human asks that existing users or patients agree again (OQ-77-14), when the new version is published, then that is a separate change request.
Safety and privacy
- Given the draft, when it names a provider or officer, then it does so only by placeholder; no real person's name, mobile or email is in the repository.
- Given any role, then no role is exempt from the staff notice.

## Notes for design
Business analyst: owns the draft only. Not legal advice. Angular developer: do not touch the consent texts until the human has approved a final text and version.

---

### SAH-77-8 · As the owner and the Sahaj operator, I want the public promise "Daily backups, kept for 30 days" to be either true or removed, so that no clinic is told something we do not do
Priority: Must (before the first real patient)
Screen: Foundation (no screen) for the operations work; Clinic setup → Privacy, ABDM and backup, block "Records and backup", and the landing trust list and FAQ ("Daily backups, kept for 30 days"). Rules BR-39, BR-40. Security #5.

## Acceptance criteria
Option 1: operations deliver it
- Given the production database, when one night has passed, then an encrypted backup exists in India, and the operator has written proof (date, size, where it is).
- Given backups are running, when 30 days have passed, then the backups of the last 30 days exist and can be restored; a backup older than the promised period may be removed.
- Given a written restore drill, when the operator restores a backup to a separate database, then the record of the drill shows the date, the backup used, the time it took, and that a sample of clinics and the row-level tenant rule still work after the restore. [QA or the operator verifies the checklist; no patient data is used.]
- Given the human reads the runbook, when they check it against the promise, then it states how long a deleted record stays in the backups, and the privacy notice (story 7) says the same.
Option 2: the human changes the text
- Given the human decides not to deliver 30 days before the first real patient, when they change the text in `docs/design`, then the angular developer updates the landing trust list, FAQ, trust page and data page to the same words, and this story is closed by that change.
Truth of what the screen shows
- Given the Privacy screen shows "Last: 2:00 AM today" or any "restore drill passed" claim, when it is shown to a real clinic, then the value comes from a real record or it is not shown (the mockup draws it; OQ-77-15). Until a real source exists the human decides what is shown.
- Given "Servers in India (planned)" on the Privacy screen, when hosting is live in India, then the human decides the final wording.
Roles
- Given the owner opens the Privacy screen, when the page shows backup information, then it is read-only information; other roles see what they already see (no new role rules).
Gate
- Given the pilot on test data (DEC-059), when the pilot runs, then the promise is not used with a real clinic; this story is a blocker only for the first real patient.

## Notes for design
Architect: the deployment ADR and runbook cover backup, point-in-time recovery, restore drill and retention. The human provisions and runs it. Owner of the evidence: operator; reviewer: security-reviewer.
