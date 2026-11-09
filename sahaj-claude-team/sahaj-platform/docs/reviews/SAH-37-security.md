# SAH-37 security review · IPD patient card setup

Reviewer: security-reviewer, read-only. Date: 9 Nov 2026. The review was done by reading the code, with no builds or tests, while QA was still running.

**Verdict: PASS WITH CONDITIONS.** There are no Critical or High findings. Finding 1 is a policy sign-off for the human before go-live.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Medium | AdmittedCards.java (`FactsPlan.of`, `flags`); AdmissionService.java `list()`; DEC-028 O3 | **This story widens the DEC-021 exception.** Once an owner switches on Diagnosis or a vital, every 60-second reload of the ward list carries, with no record-access row: the diagnosis of every admitted patient (`admission.reason` is free text and can hold sensitive conditions), the 5 latest readings and the recorder's name. It goes to OWNER, DOCTOR and NURSE across all wards, because there is no ward scoping. Mobile goes to OWNER and FRONT_DESK in the same way. The default card sends none of this, and the role gating is correct. The only trace is the request log. | Human (policy); java-developer for (b) | (a) Go-live condition, already in DEC-028 O3: add Diagnosis, the latest vitals and Mobile to the privacy register entry S36-1. (b) FIX: `IPD_CARD_PUBLISHED` also records which sensitive facts were switched on, as field keys only, with no labels, for example `factsOn`. |
| 2 | Low | CardSetupService.java:132, 158-175, 259-272; CardSetupStoredIT `knownGapAStaleEditor...` | **Revision restart hole.** After a Discard, or a Save equal to the live setup, the draft is deleted and the next one starts at revision 1 on the same base version. A stale editor holding revision 1 can overwrite, discard or publish over another owner's new draft. It needs two owners of one clinic acting within minutes, and it gives no extra privilege. It breaks AC12. | java-developer (db-engineer only if a column is needed) | FIX with no schema change if possible: the revision must never repeat for a clinic. The architect updates the contract wording "1 for a new draft" if needed. Turn the IT into the opposite assertion. |
| 3 | Low | CardSetupService.java:122-151; JdbcWardRepository.java:64 | **Draft-write flood.** Each Save takes the clinic-wide advisory lock and writes an audit row. There is no rate limit on signed-in writes. The impact stays inside the owner's own clinic. | java-developer | FIX (cheap): no audit row when the draft is unchanged. The rate limit goes on the hardening backlog. |
| 4 | Low | PatientLookup.java:51; CardFactsIT.java:205-220 | **The mobile switch does nothing today.** `findAllCurrent` builds every summary with a null mobile, so "Mobile on" sends `"mobile": null`. That fails safe. The risk is in the fix: if mobile is simply filled in `findAllCurrent`, every future caller of that method gets mobile numbers. | java-developer (patient module api) | FIX: add a separate, explicit port method that returns mobiles for given ids. Call it only when the plan needs mobile. `findAllCurrent` stays free of mobiles. This is the real fix for QA's failing mobile test. |
| 5 | Low | e2e/sah37-world.ts:229-277; patient-card-setup.spec.ts | The e2e data uses Indian personal names and 10-digit mobile numbers in a valid series. The data is mocked and follows the repo's convention. | qa-engineer | Backlog: use clearly fictional names and numbers that cannot be dialled across all e2e worlds. |
| 6 | Info | ADR-015 D6; DEC-028 O2 | Bill, deposit and payer still reach NURSE and DOCTOR in every row, for the Table view. This was there before SAH-37. | tech lead | Backlog story (already recorded) |
| 7 | Info | AdmissionService `allCardFacts`; card-setup.store.ts | The owner preview (`cardFacts=all`) loads all facts once per page open. It is OWNER only, with the token role and the membership role both checked. It is kept in memory only, and any 403 clears it. | — | None |
| 8 | Info | CardSetup.java:15-25 | The default record `toString` would print labels. Nothing logs it today. | java-developer | FIX (hardening): print keys and versions only |
| 9 | Info | migration :78 | `saved_at` is in the update grant, but the app sets it. | — | None |
| 10 | Info | process | The `SAH-37 | stories` and `contract` lines are missing from docs/approvals.md. The branch and the mixed tree do not match "one story ID per branch". | human | Human lines are needed before the merge. |

## Checked and passing
- **Tenant isolation.**
  - FORCE RLS on both tables; the policies check `platform.current_tenant()`.
  - Column-level grants never include `id`, `tenant_id` or `published_at`.
  - Versions are append-only (triggers); the draft refuses truncate.
  - The tenant comes from the signed-in user, never from the body.
  - The SQL uses named parameters only.
- **Roles.**
  - `@PreAuthorize` on all 4 endpoints.
  - The service re-checks the token role and the membership role.
  - Draft and versions go to OWNER only.
  - `cardFacts=all` is OWNER only.
- **Data minimisation.** A fact is sent only when the role may see its group AND the setup switches it on. The raw JSON is checked per role.
- **Input.**
  - The strict JSON read refuses unknown or wrong-typed properties.
  - The 400 answers never echo typed text.
  - The server rebuilds the canonical form, so duplicate keys cannot differ between the browser and the server.
  - Labels are plain text of at most 40 characters.
  - The frontend has no innerHTML or bypass of sanitising.
- **Logging and audit.** Audit rows hold version numbers and part names only, and there is no logging in the SAH-37 classes.
- **Concurrency.** The clinic advisory lock comes first, the unique key is the backstop, and a double tap on Publish makes one version.
