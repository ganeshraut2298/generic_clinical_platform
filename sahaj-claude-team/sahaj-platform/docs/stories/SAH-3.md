# SAH-3 · Sign up and log in with mobile one-time code
Mockup screen: Sign up and log in (mobile one-time code)
Module: platform

## 1. Story
As a clinic owner or staff member, I want to sign up or log in with my mobile and a one-time code, so that I have no password to remember and only I can get into my clinic.

## Acceptance criteria
1. Given a new mobile number in "New clinic", when I enter the right code, then my account and clinic are created and the Setup wizard opens.
2. Given a registered number in "Log in", when I enter the right code, then I land on Today for my role.
3. Given 5 wrong codes, then the number is locked for 15 minutes with a clear message.
4. Given a number not registered, when I choose Log in, then I am told to ask the clinic owner to add me or to sign up a new clinic.
5. Given a staff member whose access was removed, when they try to log in, then access is refused.
6. Codes never appear in API responses, browser storage or URLs.
7. Given the screen, then it has the toggle "Log in" / "New clinic"; heading "Log in to your clinic" or "Create your clinic account"; a +91 mobile field; "Send OTP"; then "Enter the 6-digit code sent to +91 …", "Verify and continue", "Change number" and "Resend code in 0:28" counting down from 30 seconds.
8. Given an invalid mobile, then "Enter a 10-digit mobile number" shows; given a short code, "Enter the 6-digit code" shows.
9. Given a code older than 5 minutes, then it is refused and I can request a new one.
10. Given a number that belongs to more than one clinic, when I log in, then I choose the clinic, then the app opens.
11. Given a number that already has a clinic in "New clinic", then I am logged in instead, with the message "This number already has a clinic. Logging you in."
12. Given sign-up, then Terms and Privacy consent is recorded with time and version.
13. Given a new clinic, then it has the Free plan ending 12 months from creation.
14. Given a sign-in, sign-up or logout, then an audit event is written with the mobile masked.
15. Given "Log out on all devices", then every refresh token for the user stops working.

## 3. Technical design
- Tokens (human decision, 1 Nov 2026): Spring Security JWT behind `TokenService`, so Keycloak can replace it. Access token 15 minutes, held in memory in the browser. Refresh token opaque, stored hashed, rotated on use, sent as an `HttpOnly; Secure; SameSite=Strict` cookie.
- Each authenticated request also checks that the membership is ACTIVE (short cache of at most 60 seconds). This meets SAH-6's "ended within one minute".
- JWT claims: `sub` user, `tid` tenant, `role`. Role and tenant are never read from the request body.
- Tables, schema `platform`:
  - `otp_challenge`: `id`, `mobile`, `code_hash`, `expires_at`, `failed_count`, `locked_until`, `last_sent_at`, `consumed_at`. No tenant.
  - `refresh_token`: `id`, `user_id`, `tenant_id`, `token_hash`, `expires_at`, `revoked_at`.
  - `consent_record`: `id`, `user_id`, `terms_version`, `privacy_version`, `at`, `source_ip`.
- `OtpSender` interface. `LogOtpSender` runs in the `dev` profile only and writes the code to the server log. No other profile registers a sender in Phase 1, so start-up fails outside `dev` and `test` rather than silently dropping codes. This is the one permitted logging of a code.
- Code is 6 random digits from `SecureRandom`, stored hashed, compared in constant time. Resend after 30 seconds returns 429 with `retryAfter`.
- Enumeration: `request` always returns 202 for a valid number and sends a code in either mode. The unregistered result appears only after a correct code.
- `verify` runs before a tenant exists, so tenant creation and membership lookup use a narrow privileged service. Record that design in code comments; `sahaj_app` stays non-bypass. Backend-dev must flag any need for a third database role instead of adding one.
- Frontend: route `/login?mode=`, access token in a signal in memory, silent refresh on load, functional auth guard, HTTP interceptor. Nothing in localStorage except non-sensitive preferences.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/auth/otp/request` | public | `{mobile, mode}` | 202 `{resendAfterSeconds}` | 400, 423 locked, 429 |
| POST | `/auth/otp/verify` | public | `{mobile, code, mode, consent?}` | 200 `AuthResult` | 400, 401 wrong or expired, 403 removed, 423 locked |
| POST | `/auth/select-clinic` | selection token | `{selectionToken, tenantId}` | 200 `AuthResult` | 401, 403 |
| POST | `/auth/refresh` | cookie | none | 200 `AuthResult` | 401 |
| POST | `/auth/logout` | any | none | 204 | 401 |
| POST | `/auth/logout-all` | any | none | 204 | 401 |

All paths are under `/api/v1/platform`. `AuthResult.next` is `SETUP`, `APP`, `CHOOSE_CLINIC` or `NOT_REGISTERED`.

## 5. Tasks
### Tasks — Backend
- Migrations; OTP request and verify with limits; sign-up transaction (user, tenant, owner membership, consent, audit); token issue, rotation and revoke; clinic selection.
- Tests: every criterion above; code never in any response; tenant from token only; lock timing; rotation reuse rejected; deactivated membership refused.

### Tasks — Frontend
- Login screen as in mockup `pLogin`, including the side panel, its three ticks and the consent line.
- Clinic chooser, field errors, lock message, countdown, routing by `next`.
- Auth service, guard, interceptor with one retry through refresh.
- Unit tests. Playwright smoke using a test-only way to read the code from the backend test profile, never the API response.

## 6. Out of scope, Open questions
Out of scope: real SMS or WhatsApp, PIN sign-in, admin portal sign-in with authenticator code.
Open questions:
- **Mockup differs from your requirement.** In the mockup, an unregistered number on Log in says "No clinic on this number yet. Let's set one up." and opens setup. Your criterion 4 says to tell them to ask the owner or sign up. By rule the mockup wins, but your version stops staff creating a stray clinic. Recommended: keep your version and update the mockup.
- The clinic chooser (criterion 10) is not in the mockup. Recommended: a simple list in the same card.
- The lock message is not in the mockup. Proposed: "Too many wrong codes. Try again in 15 minutes."

Decided by the human, 1 Nov 2026: dev logger for codes; placeholder Terms and Privacy text, version `v0.1`, stored on each consent record (real legal text needed before real clinics sign up).

## 7. Status log
