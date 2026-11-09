# SAH-40 · A refused refresh clears clinical data on the Emergency queue and the Pharmacist check
Module: ipd
Mockup screen: IPD → Emergency (queue; mockup `ipEr`) and IPD → Safety (panel "Pharmacist check" and tile "Pharmacist checks"; mockup `ipSafety`, `pendRow`). The mockup shows neither screen in a refused state (see OQ-40-1); the refusal is the pattern already built for IPD → Admitted and IPD → Safety by audit fix G-2.
Source: Human through the tech lead, 8 Nov 2026. Follow-up of `docs/reviews/SAH-35-36-38-audit-security.md` finding 5 (Low) and of audit fix G-2 in `docs/reviews/QA_REPORT_SAH-35-36-38.md`.

## Problem
Both screens refresh themselves in the background (the Emergency queue every 60 seconds; the pharmacist list when a 60-second counts poll says it changed). When the server answers 403 to such a refresh, the user has lost the right to see the data: the role was changed, the clinic membership was removed, or the IPD module was switched off. Today:
- **Emergency queue** (`emergency.component.ts`, `refresh()`): any error only shows "Could not refresh the queue. Last updated ... IST" and keeps every patient name and complaint on screen.
- **Pharmacist check** (`pharmacist.store.ts`, `load()` after the first read): any error keeps the rows (patient names, UHID, allergy text) with a "Could not refresh" notice. Worse, the 60-second counts poll (`pollSummary()`) ignores a 403 that is not `module-off`, so when the role is removed nothing even tries to reload the list and the names stay on a screen that is left open, with no notice at all. On `module-off` the page shows its lock screen, but the names stay in the browser's memory.

On a shared nurses' station or pharmacy screen this keeps health information in front of someone who may no longer see it, and tells the person it is only a connection problem. Audit fix G-2 already made the Admitted list and the Safety board clear their data on 403; these two screens were outside that fix.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- **Emergency queue** (read by owner, front desk, nurse, doctor per `docs/api/ipd.yaml` `GET /emergency`): casualty desk, nurses' station, doctor's room. The screen is often left open all day.
- **Pharmacist check** (read by pharmacist and owner per `GET /pharmacist-checks`): the pharmacy counter (pharmacist sees this panel only) and the owner's Safety tab.
- Anyone whose role is changed, whose membership is removed, or whose clinic loses the IPD module while the screen is open (the person who changes this is the owner, in another session).

## In scope / Out of scope
In scope (frontend behaviour only; no API change):
1. Emergency queue: a background reload that gets 403 clears the queue and shows the refusal (or the IPD lock page for `module-off`). Network errors and 5xx keep the stale queue with the existing notice.
2. Pharmacist check: the same for the list reload, for "Show more orders", and for the counts poll. Also drop the held names when `module-off` locks the page.
3. Whatever sits on the page and is derived from the cleared data (queue counts in the tile and tab, the pharmacist waiting number) must not keep showing the old values as if still valid.

Out of scope:
- Any API, contract, database or role change. The 403 problem types (`forbidden`, `module-off`) already exist on `GET /emergency`, `GET /emergency/summary`, `GET /pharmacist-checks` and `GET /pharmacist-checks/summary` (confirmed in `docs/api/ipd.yaml`, lines 494 to 530 and 1150 to 1203).
- The sign-in expiry (401): handled by the app-wide interceptor.
- The counts-only menu badge for Emergency (`GET /emergency/summary`): it carries no names (OQ-40-6).
- New wording, new screens, and the lock page itself (reused as it is).
- Admitted list and Safety board: done in G-2; unchanged.
- A refusal that comes from a user action (verify, reject, register, seen, release, refer, admit) is handled as today (toast or sheet message); only the reload that follows is covered (story 1, AC 6). Clarified 9 Nov 2026 (review fix pass): a refusal on a background read (including the read that follows an action) closes any open sheet and dismisses success toasts, so rule 2 (clear first) holds for sheets and toasts as well; the action's own 403 message still shows first (Verify/Reject sheet message, Emergency toast). This is no new wording.

## Business rules (numbered)
1. **403 on a background read is a refusal, not a connection problem.** A 403 of any problem type (`forbidden`, `module-off`) on a reload of the Emergency queue or of the pharmacist list (or its counts poll) means the user may no longer see this data.
2. **Clear first.** On a refusal the patient data of that screen leaves the screen and the page's memory (names, complaints, UHID, ages, allergy text, reasons). It is not only hidden.
3. **Say what happened.** On `forbidden` the screen shows the same notice as the first load of that screen, with **Try again** (G-2 pattern: the server's message, or the screen's own load-error text). On `module-off` the existing IPD lock page shows and the whole page is locked.
4. **Stale data only for transient failures.** A network error, timeout or 5xx keeps the last data with the existing "Could not refresh" notice. Nothing else changes for these.
5. **No stale number beside a refusal.** Counts derived from the cleared data are cleared too; they are not left at their old value, and "0" is not shown as if the list were empty.
6. **Recovery.** After a refusal, **Try again** reads the screen as a first load. If the user has access again, the screen returns normally; if not, the refusal shows again.
7. **No new rules or wording.** The texts are the ones already in the product (OQ-40-1).
8. **Tenant and role** come from the server as today; nothing in this story reads or sends a clinic or role from the browser.

## Patient-safety and privacy points
- The Emergency queue shows names, ages and complaints of people in casualty; the pharmacist list shows names, UHID, age, sex and allergy text. Showing them after access is withdrawn is a privacy failure (DPDP) on shared screens.
- The pharmacist list is a safety queue (orders waiting before the first dose). When it is cleared on a refusal, the screen must not look like "No orders waiting." That text means nothing is waiting and would be a false all-clear. The refusal notice shows instead (rule 5).
- Clearing on a transient failure would be wrong the other way: a ward with a flaky network must keep the last queue (clearly marked as old). Rule 4 keeps it.
- No patient name, UHID, complaint or allergy text is written to logs, URLs or browser storage by this change.
- Nothing says anyone was alerted; the screen only reflects the state.

## Plan or entitlement (which plan/module unlocks it)
IPD module (Trial, On, Read-only). Off: the existing lock page ("In-patient care (IPD) is not on for your clinic"). No plan change.

## Open questions for the human
Full text and recommended defaults are in `docs/stories/SAH-40.md` section 3 (OQ-40-1 to OQ-40-6). None blocks the architect: there is no API change. Development proceeds on the recommended defaults unless the human answers otherwise.
