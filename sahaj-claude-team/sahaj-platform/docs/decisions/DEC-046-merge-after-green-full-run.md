# DEC-046 · Merge of SAH-29, SAH-34, SAH-37 (and the mixed branch)

Date: 13 Nov 2026. Status: ANSWERED (human, chat).
The human answered "approved" to the tech lead's summary that SAH-29, SAH-34 and SAH-37 wait only for `merge` approval. Recorded as the human's chat `merge` approval for SAH-29, SAH-34 and SAH-37 (not lines in docs/approvals.md; the human adds those).
Constraint found: the three stories have no own branch; they are on `feature/SAH-39-home-kinetic-background` together with SAH-39, 40, 41, 42 and the EMR programme (SAH-43..73), whose database part has never run; 405 EMR/SAH-73 files are uncommitted.
**Answer: merge after a green full run.** Order: (1) the human drops and recreates `sahaj_test`; (2) the tech lead runs `node scripts/validate.mjs all` on the branch and routes every failure to its owner until green; (3) code and security reviews of the EMR programme and SAH-73; (4) the human commits the remaining files and merges the branch into `main` in one go. Nothing untested reaches `main`. SAH-42 still needs its own condition (Bootstrap database ITs with requireDb=true, 0 skipped) before its switch is turned on. Agents never merge or push.
