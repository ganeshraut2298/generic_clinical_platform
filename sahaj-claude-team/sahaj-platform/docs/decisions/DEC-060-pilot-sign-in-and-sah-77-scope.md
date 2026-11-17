# DEC-060 · Pilot sign-in next week and SAH-77 scope

Date: 17 Nov 2026. Status: ANSWERED (A, A, A). Raised by: tech lead from the SAH-77 business-analyst hand-back (OQ-77-1, -3, -5, -8).
Blocked: SAH-77 architect stage; how pilot users sign in next week.

## 1. Pilot sign-in (OQ-77-1)
SAH-77-1 makes the dev code bypass loopback-only, and no real SMS sender exists until the human picks a provider and DLT is registered. Pilot tablets and other machines therefore cannot sign in.
- **A (recommended): pilot profile.** A `pilot` profile with the log sender: the operator reads codes from the server log; private network or VPN only; test data only; prod refuses this sender. Pro: real devices, real flow. Con: an operator must relay codes.
- **B: one machine.** dev profile on one loopback machine only. Pro: nothing to build. Con: no tablets, no multi-user ward test.
- **C: provider test sender** if the human picks a provider this week. Pro: real SMS. Con: depends on the provider account and DLT.

## 2. Phase-1 exception for the sign-in code sender (OQ-77-3)
CLAUDE.md puts notifications (SMS, WhatsApp) out of phase 1. Sign-in cannot work in production without sending a code.
- **A (recommended):** allow one exception: an SMS sender for sign-in and PIN-set codes only, behind a provider adapter; all other notifications stay Later. Pro: makes production login possible. Con: new external service (adapter, contract, security review).
- **B:** no exception; production waits for the notifications module.

## 3. Lock and session values (OQ-77-4, -5, -8)
Proposed: 15 wrong codes per mobile per day → 60 min lock; 5 code requests per mobile per hour; 30 per client IP per hour; session 12 h absolute, 30 min idle. Risk (OQ-77-5): anyone can lock a ward doctor out on purpose; the mockup has no unlock path.
- **A (recommended):** accept these values as configuration, lock per (mobile, IP) instead of per mobile alone, and set the absolute session lifetime to 14 h so a night shift is not cut.
- **B:** accept the proposals as written.

## Answer
**Human, 17 Nov 2026: 1 A, 2 A, 3 A.** (1) Pilot profile with the log sender, private network or VPN, test data only, refused in prod. (2) Phase-1 exception: SMS sender for sign-in and PIN-set codes only, behind a provider adapter; all other notifications stay Later. (3) Values as configuration: 15 wrong codes per day → 60 min lock per (mobile, IP); 5 requests per mobile per hour; 30 per IP per hour; session 14 h absolute, 30 min idle.
