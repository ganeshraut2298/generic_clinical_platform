# SAH-37 code review · IPD patient card setup

Reviewer: code-reviewer, read-only. Date: 9 Nov 2026. The review was done by reading the code, with no builds or tests, while QA was still running. Only the SAH-37 parts of the shared tree were judged.

**Verdict: APPROVE WITH COMMENTS.** There is no Blocker and no Major finding.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | CardSetupService.java:259-270 (`requireCurrent`); JdbcCardSetupRepository; CardSetupStoredIT.java:317-350 | **Revision restart.** After a discard, the new draft starts again at revision 1, so a stale editor can overwrite another owner's new draft (AC12). The extra `baseVersion` comparison adds nothing, because a draft's base version always equals the live version. The unit test at CardSetupServiceTest:360 covers a state that cannot occur. Same as security finding 2. | java-developer (db-engineer if a sequence is used) | FIX: the draft token never repeats. Remove the dead check. QA flips the IT. |
| 2 | Minor | docs/api/ipd.yaml (card-setup 403s); CardSetupService.java:55-56; AdmissionService.java:517-528 | **The 403 texts never reach the client.** `@PreAuthorize` refuses first, and the shared handler always sends the fixed forbidden text. The contract text is wrong and the constants are dead. | solution-architect (contract), java-developer | FIX: the contract says that the shared forbidden text is used. Delete the dead constants. |
| 3 | Minor | frontend/.../ipcard/card-setup.store.ts:467-469; ipcard.page.html:49 | **A saved draft cannot be removed from the screen.** Save and Discard show only when the editor differs from the live setup. If the owner edits back to equal the live setup, the pill says "No unpublished changes" while the draft still exists on the server and comes back on the next open. | angular-developer | FIX: offer Save draft and Discard draft when there are unsaved edits or a server draft. |
| 4 | Minor | CardSetupRepair.java:130 | On read, repair removes only control characters. Validation also refuses invisible and text-direction characters. A damaged stored row could put a right-to-left override in a label. It is not reachable through the API. | java-developer | FIX: the same TextRules in repair |
| 5 | Minor | CardAccessRule.java:36 | A second copy of the deposit roles (`DEPOSIT`) duplicates `AdmissionService.DEPOSIT_ROLES`. A drift would show a Deposit button that the endpoint refuses. | java-developer / qa-engineer | FIX: one copy, with a parity test |
| 6 | Minor | config/overview/overview.page.ts:41,134 | The Overview calls `GET /card-setup` whenever IPD is available. Roles outside the four list roles would get a swallowed 403 and a warn line each time. | angular-developer | FIX: call it only for FRONT_DESK, NURSE, DOCTOR and OWNER, and use takeUntilDestroyed |
| 7 | Nit | CardSetupJson.java:25 | The strict read still accepts `"on":"true"`, `1` and `"3"`, but the contract says boolean and integer. | java-developer | FIX: turn scalar coercion off |
| 8 | Nit | CardSetupController.java:38,53,59 | The write bodies are `JsonNode`, not `@Valid` DTOs. The reason is in the Javadoc and covered by CardSetupIT. | — | Accept as a documented exception |
| 9 | Nit | CardSetupService.java:293,297 | `Optional<CardDraft> draft = null`, and a pointless copy `safeNames`. | java-developer | FIX (cheap) |
| 10 | Nit | card-setup.model.ts:294; card-setup.logic.ts | The browser keeps a copy of the safety fields and the standard card, and derives the deposit-low flag. | angular-developer | FIX: a spec that compares the copy with the catalogue fixture |
| 11 | Nit | ipd/card/card-actions.component.html | In the preview the buttons stay in the accessibility tree. | angular-developer | FIX: `inert` on the preview card |
| 12 | Nit | AdmittedCards.FactsPlan | In Compact, Temp and RR are sent when switched on but never drawn. | — | Accept. The owner chose those fields. |
| 13 | Nit | CardSetupStoredIT.java:317 | The "known gap" test asserts the defect as green. | qa-engineer | Flip it when #1 is fixed. |

## Confirmed
- **Contract.** The check order, absent versus null, discard with no draft, and the 409 for a publish equal to live all match the contract.
- **Locked safety fields.** They are validated on save and publish and repaired on read. The browser guard is only a guard.
- **Concurrency.** The advisory lock comes first, a duplicate key maps to 409, and a double publish makes one version.
- **Data minimisation.** A fact is sent only when the role's group allows it AND the setup switches it on. The default setup sends nothing new.
- **Decisions.** DEC-027, DEC-028 and the frontend readings F1–F7 are honoured.
- **SAH-36 is unchanged.** The edits to AdmittedCardsIT keep their privacy intent.
- **Boundaries and migration.** Module boundaries are clean, and the migration is forward-only with RLS.
- **Frontend.** All components use OnPush, signals and inject(), and have no leaks. A 403 clears the data (SAH-40).
- **Tests.** No skipped or disabled test in the new tests.

## Open before merge
Re-check the 2 CardFactsIT failures that were open at the time of the review. Security finding 4 explains the empty mobile: it is a product defect, because the patient lookup returns no mobiles.
