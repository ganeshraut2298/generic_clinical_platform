# ADR-010 · Personal staff PIN: storage, rules, lockout, set and reset, and the check used by IPD

Story: SAH-31 (PIN part; the IPD medicine-order parts are ADR-009). Reused by SAH-32 (MAR second check).
Mockup screens: Clinic setup → Team and access, panel "Staff" (`f-staff`: the "PIN set" pill and the "Send PIN link" button, `staffpin`), panel "Shared computer safety" (`f-sec`, "Everyone signs in with their own 4-digit PIN"), Clinic setup → Overview checklist line "Own PIN login for each staff member" (`cfgChecks`); EMR sheet and IPD → Safety, Pharmacist check sheet (`sfver`, `sfverok`: "Verified by", "PIN", "Enter the 4-digit PIN.").
Module: platform (table, rules, endpoints, port). ipd only calls `platform.api.StaffPinPort`.
Status: PROPOSED (6 Nov 2026), with the SAH-31 contract; accepted when the human approves `contract`. The choice of a personal PIN itself is DECIDED (DEC-017 Q1, option B). **Amended 6 Nov 2026 after the build** (DEC-017 "Build-time choices"): the third-lock answer (D5, D11), the readable `pinState` audit key (D10), the `PinCheck.notSetReason` field (D9) and the limiter as built (D13); the contract changes need the human's re-approval at the `contract` gate. **Amended again 6 Nov 2026 after the reviews** (docs/reviews/SAH-31-code.md R1, SAH-31-security.md S5 and S12; DEC-017 "Review decisions"): D13 counts failed PIN checks only and gives set/change its own limit (review fix R1); questions Q8 (S5) and Q9 (S12) added for the human.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Supersedes: ADR-002 D5 bullet "PIN hashed with Argon2id" (see D2) and the never-built columns `app_user.pin_hash`, `pin_set_at`, `pin_failed_count`, `pin_locked_until` of ADR-002 "Data needed". ADR-009 D6 now points here.
Related: DEC-017, ADR-002 D5 and D7, ADR-009 D5 and D6, ADR-008 (BCrypt strength 12 for admin passwords), `docs/api/platform.yaml`, `docs/api/ipd.yaml`

## Context

1. DEC-017 Q1: each person has a personal 4-digit PIN, stored as a BCrypt hash with a server secret `SAHAJ_PIN_PEPPER`; 5 wrong tries lock it for 15 minutes. It replaces the mockup's format-only PIN, which accepts any 4 digits and lets the user pick whose name is recorded.
2. Clinic users sign in with mobile and a one-time code (SAH-3, `OtpService`); there is no password. Sessions are a 15-minute JWT plus a rotated refresh cookie. A shared computer may stay signed in.
3. The mockup shows the PIN in four places: the Team list ("PIN set" pill, or "Send PIN link", which sends a WhatsApp link), the "Shared computer safety" switch (PIN sign-in for everyone; shown as "Coming later" since SAH-6), the setup checklist line (done when that switch is on) and the Verify sheet. It shows no screen where a person types a new PIN, and no reset.
4. WhatsApp and SMS notifications are not in phase 1. The one-time code channel of sign-in (`OtpSender`) is part of the platform and already required wherever sign-in works.
5. The build already has `spring-security-crypto` (BCrypt; admin passwords use strength 12), the JDK `javax.crypto.Mac` (HMAC-SHA256) and an in-house `SlidingWindowRateLimiter`. Argon2id in Spring needs Bouncy Castle, which is not in the build.
6. ADR-009 D5 checks the PIN before the IPD transaction so that a wrong try is counted even when the request fails, and so that no platform row lock is held with the admission lock.

## Decision

### D1 · What the PIN is, and what it is not

- A **confirmation by a person who is already signed in** that they are the one doing a clinical check (SAH-31 verify; SAH-32 second-nurse check). It is checked only against **one named person**: never "find the user whose PIN is 4821". Two people may have the same PIN.
- **One PIN per membership** (person × clinic). A person working in two clinics has two PINs; one clinic's owner can never reset the other's.
- **Not a sign-in.** PIN sign-in on a shared computer (the mockup switch) stays "Coming later". A later sign-in story may reuse this table, but sign-in by PIN is unauthenticated and needs its own review (more exposure than a check behind a session).
- Every role may set a PIN (SAH-32 needs nurses). In SAH-31 only OWNER and PHARMACIST use it.

### D2 · Storage: HMAC with the pepper, then BCrypt strength 12

`pin_hash = BCrypt_12( base64( HMAC-SHA256( pepper, "sahaj-staff-pin-v1|" + tenantId + "|" + membershipId + "|" + pin ) ) )`

- **BCrypt strength 12**, the same as admin passwords (ADR-008, `AdminPasswordHasher`); about 0.25 s per check on one core. A dedicated `StaffPinHasher` class in `platform.application` (not a `PasswordEncoder` bean, so it is never picked up by Spring Security or the OTP encoder by accident).
- **Why the pepper:** 10,000 possible PINs at 0.25 s is about 40 minutes of one core per row, so a copy of the database alone would reveal every PIN. With the HMAC key outside the database, a dump without the key reveals nothing.
- **Why tenant and membership in the HMAC input:** a hash copied from one row to another (someone with write access to the table putting their own hash on a colleague's row) no longer matches. The input is 44 Base64 characters, well under BCrypt's 72-byte limit.
- `pepper_version smallint` (1 in phase 1) is stored per row so a later rotation can be designed; a rotation procedure is not part of this ADR (Q6).
- **No new library:** JDK `Mac` and `spring-security-crypto` BCrypt. **Argon2id (ADR-002 D5) is not used** because it needs Bouncy Castle; this line of ADR-002 is superseded.
- The PIN arrives as a JSON string, is checked for format, used once, and the variable is not kept in any field, cache, log, audit row, exception message or `toString()`.

### D3 · Fail closed when the pepper is missing

- `SAHAJ_PIN_PEPPER`: Base64 of at least 32 random bytes; no default in any profile.
- **Missing or blank:** the application starts (an OPD-only clinic needs no PIN) and logs one WARN line at start-up, "Staff PIN checks are off: SAHAJ_PIN_PEPPER is not set" (never a value). Every PIN operation fails closed: `GET /me/pin` answers `available: false`; `POST /me/pin/code` and `PUT /me/pin` answer 503 `pin-unavailable`; `StaffPinPort.check` answers `UNAVAILABLE`, so a verify answers 503 and nothing is verified. Owner reset stays allowed (clearing a PIN is the safe direction).
- **Present but invalid** (not Base64, fewer than 32 bytes after decoding, or equal to `SAHAJ_JWT_SECRET`): the application refuses to start (a wrong key would make every PIN fail in a way that looks like wrong tries).
- Changing the pepper makes every stored PIN fail; until a rotation design exists, a pepper change means every person sets a new PIN (Q6).
- Tests: integration tests generate a random pepper per run (test property source); no fixed value is committed.

### D4 · The 4-digit rule and weak PINs

**Why 4 digits** (mockup: "own 4-digit PIN", "Enter the 4-digit PIN.", `maxlength="4"`): the PIN is a second confirmation by a person who is already signed in, not a sign-in secret. Guessing is possible only from that person's own session (D1), is bounded by the lockout (D5: at most 15 tries in 24 hours before the PIN is switched off) and every lock is audited. A longer PIN would be a deviation from the mockup without a matching gain under these limits.

**Refused new PINs** (pure class `com.sahaj.platform.domain.PinRules`, unit-tested rule by rule; one message for all, so the screen does not teach the rules one by one):

| # | Rule | Examples |
|---|---|---|
| 1 | not exactly 4 ASCII digits `0-9` | `12a4`, `123`, `١٢٣٤` |
| 2 | all four digits equal | 0000, 7777 |
| 3 | straight run up or down (each digit +1 or each −1, no wrap) | 0123 … 6789, 9876 … 3210 |
| 4 | two repeated pairs ABAB or AABB (A ≠ B) | 1212, 1122 |
| 5 | a year 1900 to 2099 | 1987, 2026 |
| 6 | keypad columns | 2580, 0852 |
| 7 | any 4 consecutive digits of the person's own 10-digit mobile number (7 windows, read on the server from `app_user`, never sent by the client) | mobile 98220 77410 refuses 9822, 8220, 2207, 2077, 0774, 7741, 7410 |

About 400 of the 10,000 PINs are refused. "Same as the current PIN" is **not** a rule: checking it would need a hash comparison that tells the caller whether a guess equals the current PIN.

### D5 · Lockout

- 5 wrong tries in a row → `locked_until = now + 15 minutes`, `failed_count` back to 0, `lock_count + 1`, audit `PLATFORM_PIN_LOCKED`. The 5th wrong try that causes the **first or second** lock within 24 hours answers `LOCKED` (403 `pin-locked`, D11).
- While locked: no hash comparison at all; even the right PIN answers `LOCKED`; the try is not counted.
- A right PIN resets `failed_count` to 0 (not `lock_count`).
- **Third lock within 24 hours** (counted from `lock_window_started_at`) → the PIN is switched off: `pin_hash` null, `not_set_reason = TOO_MANY_LOCKS`, audit `PLATFORM_PIN_CLEARED_AFTER_LOCKS`. **The 5th wrong try that causes this third lock answers `NOT_SET` with reason `TOO_MANY_LOCKS`** (409 `pin-not-set`, "Your PIN was switched off after too many wrong tries. Set a new one, then try again."), never `LOCKED` / 403 `pin-locked`: the PIN is already switched off at that moment, so no lock time applies and the screen must offer "Set my PIN", not a wait. Every later try answers the same until a new PIN is set (amended 6 Nov 2026 to match the build, DEC-017 "Build-time choices"). The person sets a new one with a code to their mobile (D7). This caps guessing at 15 tries a day and is an addition in the safer direction (Q3). `lock_count` restarts at 0 when the window is older than 24 hours at the next lock.
- Setting a new PIN (D7) or an owner reset (D8) clears `failed_count`, `locked_until`, `lock_count` and the window.
- The counter row is read `for update` in a short platform transaction and the BCrypt comparison runs inside it, so one person's tries are serialised and two parallel wrong tries both count.
- Wrong-try message carries the tries left ("That PIN is not right. 2 tries left."). This tells nothing to anyone but the signed-in person, whose own state is shown anyway.

### D6 · Comparison in constant time

- BCrypt `checkpw` compares the full hash without an early return; the HMAC is computed in full for every check. No `String.equals`, `Arrays.equals` or digit-by-digit comparison of PINs or hashes anywhere.
- The order is fixed: rate limit (refused when the failed-check limit of D13 is already reached; a wrong answer is counted after the comparison) → membership → row lock → not set / locked → HMAC + BCrypt. Not-set and locked answers return without a hash; that state is not secret to the signed-in person (`GET /me/pin` shows it), so no dummy hash is needed.

### D7 · Set, change and "forgot my PIN": only the person, with a fresh code to their own mobile

- `POST /api/v1/platform/me/pin/code` sends a one-time code to the signed-in person's own mobile, read on the server (never from the request), through the existing `OtpService.request` (same 30-second resend wait and 15-minute lock after 5 wrong codes as sign-in).
- `PUT /api/v1/platform/me/pin` with `code`, `newPin`, `confirmPin`. The same call sets a first PIN, changes it and replaces a forgotten or locked one. **No current PIN is asked**: the code proves the person has their phone, which is stronger, and avoids a current-PIN oracle.
- **Why a code even for the first PIN:** on a shared computer left signed in, anyone could otherwise set a PIN for a colleague who has none and then verify orders in that colleague's name. This keeps ADR-002 D5's rule "setting or changing one's own PIN requires a fresh one-time code".
- Order (each step stops the request): the set/change rate limit (D13, its own limit); pepper present (503); body format (400, all field errors together); weak-PIN rules and confirm match (400; checked **before** the code so a weak PIN does not use up the code); `OtpService.verify` called **outside** the write transaction (its failed-code count must commit even when this request fails; a wrong or expired code is turned into a 400 field error on `code`, never a 401, which the client would treat as a lost session); then one READ COMMITTED transaction: membership by (tenant, user) ACTIVE, the `staff_pin` row `for update` (or insert), new hash, counters cleared, `not_set_reason` null, `set_at` = database time, audit `PLATFORM_PIN_SET` (first PIN) or `PLATFORM_PIN_CHANGED`. Two first-time saves at once: one inserts, the other hits the primary key and answers 409 `concurrent-change` (retryable).
- A lost answer: the code is used up, so a retry answers 400 "That code has expired. Request a new one."; the client re-reads `GET /me/pin` after a network failure and shows success when `setAt` changed.
- **Accepted:** `platform.otp_challenge` has no purpose column, so a sign-in code sent in the last 5 minutes can also be used here (and the other way round). Both prove the same phone and each code works once (Q5).
- **No owner involvement, no link:** the mockup's "Send PIN link" sends a WhatsApp link, which phase 1 cannot do. Each person sets their own PIN from the screens of D12.

### D8 · Owner reset: clears, never reads or sets

- `POST /api/v1/platform/team/{id}/pin/reset` (membership id): OWNER only, token role and membership re-read. Sets `pin_hash` null, `not_set_reason = RESET_BY_OWNER`, `reset_at` (database), `reset_by`, counters cleared; audit `PLATFORM_PIN_RESET`. The owner never sees, chooses or receives a PIN.
- Allowed for any member of the clinic, including the owner's own row and switched-off members. No PIN set: 200 with the member, nothing written (idempotent).
- Use: a person thinks someone knows their PIN, a person has left, or after a lock. The person sets a new PIN with a code to their mobile (D7).
- **Removing access** (`DELETE /team/{id}`, SAH-6) clears the PIN in the same transaction with `not_set_reason = RESET_BY_OWNER` and the same audit (recommended; small change in `TeamService`).

### D9 · The check during verify: `platform.api.StaffPinPort`

```java
public interface StaffPinPort {
    PinCheck check(UUID tenantId, UUID userId, String pin);
}
// platform.api
public record PinCheck(Outcome outcome, int triesLeft, Instant lockedUntil, long retryAfterSeconds, NotSetReason notSetReason) {
    public enum Outcome { OK, WRONG, LOCKED, NOT_SET, RATE_LIMITED, UNAVAILABLE }
    public enum NotSetReason { NEVER_SET, RESET_BY_OWNER, TOO_MANY_LOCKS }   // as built: picks the pin-not-set text of D11
    /** Returns normally for OK; otherwise throws StaffPinException (platform.api) with the problem type and text of D11. */
    public void requireOk() { ... }
    // toString: outcome and numbers only
}
```

- ipd passes the caller's own `tenantId` and `userId` from `TenantContext` (never a request value). The order of ADR-009 D5 is unchanged: role, module, Read-only, body, plain read of the order and self-verification, **then the PIN**, then the IPD transaction.
- **No platform lock while IPD locks are held:** the implementation runs its own READ COMMITTED transaction (bind tenant, membership by (tenant, user) ACTIVE, `staff_pin` row `for update`, decide, update counters, audit a lock, commit) and returns. It **refuses to run inside an active transaction** (`TransactionSynchronizationManager.isActualTransactionActive()` → `IllegalStateException`, a programming error caught by tests), so it can never be nested in the IPD transaction or hold a second connection. A wrong try is committed before the IPD request fails.
- **`StaffPinException`** (platform.api, mapped by the global `ProblemExceptionHandler`) carries the slug, status, detail and `retryAfterSeconds`, so ipd (SAH-31) and the MAR (SAH-32) answer with the same types and texts, written in one place. ipd calls `staffPinPort.check(...).requireOk()`.
- A right PIN is not a token: if the IPD transaction then fails (for example 409 `order-state`), the next verify asks for the PIN again. A reset committed between the PIN check and the IPD commit (milliseconds) is accepted: the PIN was valid when checked.
- No membership or an INACTIVE membership: `NOT_SET` is never used for this; the ipd role re-read refuses first (403). If reached, the port answers `NOT_SET` (fail closed).
- SAH-32 (second nurse checks a high-alert dose) will check **another** person's PIN with the same port; that story must design who may be named and how guessing from a colleague's session is limited (Q7). SAH-31 never passes another person's id. **Designed in ADR-011 D7 (PROPOSED, 6 Nov 2026):** a new method `checkColleague(tenantId, callerUserId, colleagueMembershipId, requiredRole, pin)` returning `ColleaguePinCheck`, outcome `NOT_ELIGIBLE`, lockout on the colleague's row, a per-caller bucket for failed colleague checks (5 per 15 minutes, 10 per 24 hours), audit `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED`, `checkedByMembershipId` on the lock audits, and colleague texts in `StaffPinException`. `check(...)` is unchanged.

### D10 · Audit and logs (ids and fixed keys only)

`AuditLog.record(action, "STAFF_PIN", membershipId, before, after)` in `platform.audit_event`, in the transaction that makes the change:

| Action | before → after fields | When |
|---|---|---|
| PLATFORM_PIN_SET | null → `{membershipId, pinState: SET}` | first PIN, or a new PIN after a reset or switch-off |
| PLATFORM_PIN_CHANGED | `{pinState: SET or LOCKED}` → `{membershipId, pinState: SET}` | a PIN replaced while one was set |
| PLATFORM_PIN_RESET | `{pinState: SET or LOCKED}` → `{membershipId, pinState: NOT_SET, reason: RESET_BY_OWNER}` | owner reset, access removed |
| PLATFORM_PIN_LOCKED | `{pinState: SET}` → `{membershipId, pinState: LOCKED, lockedUntil, lockCount}` | 5th wrong try |
| PLATFORM_PIN_CLEARED_AFTER_LOCKS | `{pinState: LOCKED}` → `{membershipId, pinState: NOT_SET, reason: TOO_MANY_LOCKS}` | 3rd lock in 24 hours |

- Never in an audit row, problem detail, event or log: the PIN, the hash, the HMAC, the pepper, the code, the mobile number, the person's name. (`AuditLog` also replaces any `pin` key as a second line of defence.)
- **The one pin-named key kept readable** (build, amended 6 Nov 2026): `AuditSanitizer` (platform infrastructure) redacts every key whose normalised name contains "pin". It has exactly one narrow exception: the key `pinState` is kept readable **only** when its value is one of the three fixed labels `SET`, `LOCKED`, `NOT_SET`. Any other value under `pinState`, and every other pin-like key (`pin`, `newPin`, `pinHash`, ...), is still replaced. This lets the history show the PIN state change without ever showing a PIN. A new pin-named audit key needs an ADR change; widening the exception is not allowed.
- Single wrong tries and successful checks are not audited in platform (counted in the row; a success is recorded by the action it allowed, for example `IPD_MED_VERIFIED`).
- Log lines: event key, tenantId, membershipId, requestId, outcome.

### D11 · Problem types and texts (platform-owned, used by every caller)

| Slug | Status | Detail (exact) | When |
|---|---|---|---|
| `wrong-pin` | 403 | "That PIN is not right. <n> tries left." (n = 1: "1 try left.") | wrong PIN, not yet locked |
| `pin-locked` | 403 | "Your PIN is locked after 5 wrong tries. Try again at <h:mm AM/PM>." (IST, `retryAfterSeconds` set) | the 5th wrong try that causes the first or second lock within 24 hours, or any try while locked |
| `pin-not-set` | 409 | "You have not set your PIN yet. Set your PIN, then try again." (never set) / "The clinic owner reset your PIN. Set a new one, then try again." (`RESET_BY_OWNER`) / "Your PIN was switched off after too many wrong tries. Set a new one, then try again." (`TOO_MANY_LOCKS`) | no PIN, by `not_set_reason`; **also the 5th wrong try that causes the third lock within 24 hours** (text `TOO_MANY_LOCKS`, because that try switches the PIN off, D5) |
| `rate-limit` | 429 | "Too many PIN tries in a short time. Try again in a few minutes." (`retryAfterSeconds` set) | D13 |
| `pin-unavailable` | 503 | "PIN checks are not available right now. Try again later." | pepper missing (D3) |

`pin-locked` stays 403 (as in ipd.yaml; 423 is the sign-in code lock `otp-locked`).

### D12 · What the mockup's PIN items do in the build

| Mockup item | Build |
|---|---|
| Team list, "PIN set" pill (`f-staff`) | Shown to OWNER on every row whose PIN is set (`TeamMember.pin.state` SET). |
| Team list, "Send PIN link" button (`staffpin`, WhatsApp) | **Not built** (no notifications in phase 1). The row shows the pill "No PIN yet" instead. A person sets their own PIN (D7). |
| (not in mockup) | OWNER: "Reset PIN" on rows with a PIN set or locked; pill "PIN locked" when locked. Own row (any role who can open the page): "Set my PIN" or "Change my PIN". |
| "Shared computer safety" switch "Everyone signs in with their own 4-digit PIN" (`f-sec`) | Unchanged: "Coming later" (PIN sign-in is not this story). |
| Setup checklist "Own PIN login for each staff member" (done = that switch) | Proposed: a check `pin` "Own PIN for each staff member", why "So medicine checks show who did them", done when every ACTIVE member has a PIN set or locked, Fix link to Team and access → `f-staff`, **optional** (does not change the setup percent of OPD-only clinics) until PIN sign-in exists (Q2). |
| Verify sheet "Verified by" select + "PIN" (`sfver`) | "Verified by" = the signed-in person, read-only (ADR-009); "PIN" checked against that person's own PIN. No PIN or locked: the sheet says so and offers "Set my PIN" (texts in the deviations table). |

### D13 · Rate limiting

**Rule as built after review fix R1** (amended 6 Nov 2026 after the code review, DEC-017 "Review decisions"; the java-developer implements it in the review fix pass). It replaces the earlier "10 PIN operations per 5 minutes, check and set together", under which a pharmacist verifying 11 orders in 5 minutes was refused with 429 and could not release first doses.

| Bucket (each its own `SlidingWindowRateLimiter`, key `tenantId:userId`) | What counts | Limit | Answer |
|---|---|---|---|
| Failed PIN checks (`StaffPinPort.check`) | **Only failed checks**: a check whose hash comparison did not match (WRONG, including the 5th wrong try that causes a lock or the switch-off). Successful checks (OK) are **not** counted. Tries answered LOCKED, NOT_SET or UNAVAILABLE run no hash and are not counted. | **10 per 5 minutes per user** | Checked first, before any hash: when the caller already has 10 failed checks in the window, 429 `rate-limit` / `RATE_LIMITED`. A wrong answer adds one after the comparison. |
| Set or change (`PUT /me/pin`) | Every request that passes authentication | **10 per 5 minutes per user**, its own bucket, not shared with checks | 429 `rate-limit` |
| Code requests (`POST /me/pin/code`) | Every request | **5 per hour per user**, plus the `OtpService` limits (30-second resend wait, 15-minute lock after 5 wrong codes, per mobile) | 429 `rate-limit` |
| Owner resets (`POST /team/{id}/pin/reset`) | Every request by the owner | **30 per hour per owner** | 429 `rate-limit` |

- **Why failed checks only:** the lockout in the database (D5: 5 wrong tries lock for 15 minutes, the third lock in 24 hours switches the PIN off) is the real limit on guessing; the limiter is a backstop and protects CPU. Successful verifies are real work and must never lock out a pharmacist verifying a burst of orders. With the lockout at 5, the failed-check limit is reached only when a lock is cleared inside the window (a new PIN or an owner reset); it stays as a backstop.
- **Residual, accepted (HARD-2):** successful checks are not limited by this limiter. Each costs one BCrypt hash (about 0.25 s); a signed-in person who knows their own PIN could repeat requests that reach the check (for example a verify of an order that is already ACTIVE, whose state is checked only after the PIN, ADR-009 B1). No privilege is gained; the cost is CPU. A per-user platform request limiter (HARD-2, security review S11) covers it.
- **In memory, per application instance** (`InMemoryStaffPinLimits`, platform infrastructure; phase 1 runs one instance). Nothing is persisted; a restart clears the windows (the database lockout of D5 is unaffected). A shared limiter for more than one instance (database or cache backed) is backlog item **HARD-2** (security review S8), not phase 1 of this story.

### D14 · Retention and tenant scope

- `platform.staff_pin`: tenant table, ENABLE and FORCE RLS; one row per membership, kept as long as the membership (never deleted; a reset nulls the hash). The hash is a credential, not personal data in clear; it is never exported.
- History is the append-only `platform.audit_event` (D10); no separate history table.

## Options considered

- **Store: BCrypt 12 + pepper HMAC (chosen)** vs Argon2id (needs Bouncy Castle, a new library; ADR-002 D5 superseded) vs BCrypt without pepper (a database copy reveals every PIN in minutes).
- **Set with a code to the mobile (chosen)** vs set with the session only (a signed-in shared computer lets anyone set a colleague's first PIN) vs "recent sign-in" check (needs a new `auth_time` claim and a refresh-token column, and forces a full sign-out) vs current PIN for a change (an oracle, and no way out of a forgotten PIN).
- **Owner reset clears (chosen)** vs owner sets a temporary PIN (the owner would know a PIN that names someone else) vs no reset (a suspected PIN stays usable).
- **Check in its own platform transaction before the IPD one (chosen, ADR-009 D5)** vs inside the IPD transaction with `REQUIRES_NEW` (two connections, a platform lock held inside the admission lock).
- **Exception in platform.api with the texts (chosen)** vs each caller maps outcomes to texts (ipd and the MAR would drift).
- **Third lock switches the PIN off (chosen, Q3)** vs lockout only (480 guesses a day from a signed-in session) vs growing lock times.

## Consequences

**Positive.** The verify records a person who proved they are present; a database copy alone reveals no PIN; nobody, including the owner, can set or learn another person's PIN; no new library or service; ipd holds no platform lock; one place for every PIN text; SAH-32 reuses the same table and port.

**Negative / accepted.** Two screens the mockup does not show (set and reset sheets); setting a PIN needs a working code channel (the same as sign-in); a missing pepper switches every verify off (503); a pepper change makes everyone set a new PIN; the in-memory limiter is per instance; successful PIN checks are not rate limited (D13, HARD-2); sign-in codes and PIN codes are interchangeable for 5 minutes (Q5, re-raised as Q8); no pepper rotation or compromise runbook yet (Q6, re-raised as Q9); BCrypt 12 adds about 0.25 s to each verify.

## Data needed (for db-engineer; one forward-only migration after the SAH-31 IPD one, for example `V2026_11_28_1100__platform_staff_pin.sql`)

1. **`platform.staff_pin`** (tenant table):
   - `membership_id uuid primary key`; FK `(tenant_id, membership_id) → platform.membership (tenant_id, id)` (`uq_membership_tenant_id` exists).
   - `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`; unique `(tenant_id, membership_id)` (tenant-first index).
   - `pin_hash text null` check `pin_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'`; `pepper_version smallint not null default 1` check `>= 1`.
   - `failed_count smallint not null default 0` check `between 0 and 4`; `locked_until timestamptz null`.
   - `lock_count smallint not null default 0` check `between 0 and 2`; `lock_window_started_at timestamptz null`; check `(lock_count = 0) = (lock_window_started_at is null)`.
   - `set_at timestamptz null` (time of the current PIN); check `(pin_hash is null) = (set_at is null)`.
   - `not_set_reason text null` check in (`RESET_BY_OWNER`, `TOO_MANY_LOCKS`); check `pin_hash is null or not_set_reason is null`.
   - `reset_at timestamptz null`, `reset_by uuid null` (user id), set together.
   - Check: `pin_hash is not null or (failed_count = 0 and locked_until is null and lock_count = 0)`.
   - `created_at timestamptz not null default now()`, `created_by uuid`, `updated_at timestamptz`, `updated_by uuid`, `version bigint not null default 0`.
   - ENABLE and FORCE RLS; policy `tenant_id = platform.current_tenant()` for select, insert, update (no delete policy).
   - Grants to `sahaj_app`: `select`; `insert (membership_id, pin_hash, pepper_version, set_at, created_by)`; `update (pin_hash, pepper_version, failed_count, locked_until, lock_count, lock_window_started_at, set_at, not_set_reason, reset_at, reset_by, updated_at, updated_by, version)`. No delete, no truncate; triggers deny delete and truncate (`platform.deny_audit_mutation()` or the platform equivalent).
   - Comments: "Personal staff PIN (ADR-010). pin_hash = BCrypt of an HMAC with SAHAJ_PIN_PEPPER; never the PIN. Checked only against the named person."
   - Volume: at most one row per membership (about 10 to 300 per clinic). No partitioning.
2. **No change** to `platform.app_user`, `platform.otp_challenge`, `platform.refresh_token` or `platform.audit_event`. The ADR-002 `app_user.pin_*` columns are not created.
3. Retention: kept with the membership; never deleted (D14).

## Instructions

**java-developer (platform):** `PinRules` (domain, D4) with unit tests per rule; `StaffPinHasher` (HMAC + BCrypt 12, pepper from `sahaj.pin.pepper` = `${SAHAJ_PIN_PEPPER:}`, start-up validation of D3); `StaffPinService` (status, send code, set, reset, check; D5 to D8, D13); `StaffPinPort`, `PinCheck`, `StaffPinException` in `platform.api` and the mapping in `ProblemExceptionHandler`; `TeamMember.pin` in the team list; reset on access removal (D8); the checklist line if the human accepts Q2; ITs as `sahaj_app` (cross-tenant, lockout, third lock, parallel wrong tries both counted, no PIN in logs). Replace the ADR-009 fail-closed stub with this implementation.

**java-developer (ipd):** `staffPinPort.check(tenantId, userId, pin).requireOk()` at ADR-009 D5 step 3, outside any transaction.

**angular-developer:** see the frontend list in the SAH-31 handoff.

## Questions for the human

- **Q1 (texts):** the set and reset sheet texts and the Verify sheet PIN states (deviations table in the handoff) are not in the mockup.
- **Q2 (checklist):** add the checklist line as "Own PIN for each staff member" (not "login": PIN sign-in is not built), optional so the setup percent does not change, done when every active member has a PIN.
- **Q3 (security, addition):** the third lock in 24 hours switches the PIN off until the person sets a new one with a code.
- **Q4 (removal of the mockup button):** "Send PIN link" is not built (WhatsApp is not in phase 1); the pill "No PIN yet" replaces it.
- **Q5 (accepted risk):** a sign-in code and a PIN code are interchangeable for their 5 minutes (no purpose column on `otp_challenge`). Re-raised after the security review as Q8.
- **Q6 (operations):** pepper rotation is not designed; changing `SAHAJ_PIN_PEPPER` means everyone sets a new PIN. Re-raised after the security review as Q9.
- **Q7 (SAH-32):** the second-nurse check needs another person's PIN; designed with SAH-32.

### Open after the reviews (6 Nov 2026; for the human, nothing decided)

- **Q8 (security review S5): a `purpose` column on `platform.otp_challenge`.** Today a PIN-set code is also a sign-in code and the other way round; asking for one cancels the other; and the "5 code requests per hour" PIN limit (D13) can be bypassed through the public sign-in code request. The reviewer recommends fixing this before a real SMS sender exists. Choosing (a) **reverses Q5 and the "Accepted" bullet of D7**.
  - (a) Add `purpose` (`SIGN_IN`, `PIN_SET`) to `otp_challenge`; `OtpService.request` and `verify` take the purpose and only a code of the same purpose matches; a request cancels only open codes of the same purpose; separate SMS text per purpose. Needs a forward migration (db-engineer), a change to `OtpService` and its callers (java-developer) and a contract note in `platform.yaml`; no new path or field in the API.
  - (b) Keep the accepted risk until a real SMS sender is built, then do (a) in that story.
  - (c) Keep the accepted risk permanently (both codes prove the same phone; each works once).
- **Q9 (security review S12): pepper rotation and compromise runbook before go-live.** No procedure exists for a routine change of `SAHAJ_PIN_PEPPER` or for a suspected leak, and the start-up check that the pepper differs from `SAHAJ_JWT_SECRET` compares text only (another encoding of the same bytes passes).
  - (a) A runbook only: on a change or a leak, clear every PIN (a reviewed one-off migration as `sahaj_migrator`; the table forces RLS with a tenant policy, so the runbook must say how the migration reaches every tenant's rows, for example per tenant with `app.tenant_id` set or a migrator-only policy, setting `pin_hash` null with `not_set_reason = RESET_BY_OWNER` and an audit row per membership), deploy the new pepper, everyone sets a new PIN with a code (D7).
  - (b) Design a rotation with `pepper_version`: the app holds the old and the new pepper for a period, checks by the row's version and re-hashes with the new pepper at the next successful check; rows not re-hashed by the end are cleared as in (a). Needs an ADR change and a new secret variable.
  - (c) Defer until the first real tenant goes live.
  - With any option: compare the decoded bytes of the two secrets at start-up (small Java change).
