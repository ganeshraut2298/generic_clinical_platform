# Security review · SAH-35 (Medicine round on the Safety board)

Reviewer: security-reviewer (read-only), 7 Nov 2026. Saved by the tech lead. Read-only review of `git diff HEAD` plus untracked files. No new runtime libraries.

**Verdict: PASS.** No Critical, High, Medium or Low findings. 4 Info.

## Info
- I35-1: like the Safety board (ADR-012 D5), the round panel shows patient name, bed and medicine to OWNER, DOCTOR and NURSE without an access row. Same privacy point as SAH-36 S36-1 and covered by that go-live condition. Give opens the MAR, which writes the usual access row.
- I35-2: `canGive` and `giveLockReason` only steer the UI. The Give POST is the unchanged SAH-32 path and re-checks NURSE/OWNER, IPD Trial/On, membership and the PIN on the server. The round re-reads the MAR before opening the sheet (`checkGive`).
- I35-3: the `uuid[]` array literal (JdbcMedicineRoundRepository.java:71-82) is built only from `UUID.toString()` and bound as a parameter. Safe.
- I35-4 (accessibility, not security): `autoFocus: false` on the Give sheet (medicine-round.component.ts:123); the sheet places focus itself and the focus trap stays.

## Passed
Who can do what (OWNER, DOCTOR, NURSE read: token, then IPD gate, then re-read membership role; only NURSE/OWNER on both token and membership give, only while IPD is Trial/On; Read-only gives `IPD_READONLY`; IPD Off gives 403 module-off); data (RLS plus `tenant_id` in both statements, 200-row cap on rows while counts and the change token cover every slot); change token (SHA-256 of `dose:<orderId>:<epoch>:<state>` lines, truncated, base64url, no patient name, drug or text); logs (`RoundRow` and `RoundPanel` hide names and drugs in `toString`, the reader logs nothing); Give flow unchanged apart from event wording (SAH-38 D11).

## Done well
The round is one pure function behind the board, the summary and the banner; exactly two statements whatever the number of patients.

## Frontend note (all three stories): PASS
No `innerHTML`, `bypassSecurityTrust*`, `localStorage`/`sessionStorage` or `console` in new or changed code (specs assert storage stays empty); no ids or patient data in URLs; second-nurse PIN field still `type="password"`, `autocomplete="one-time-code"`, cleared after each answer; the notice store is reset on sign-out and shell teardown.
