# DEC-099 · Human away: standing approval for SAH-90..93 development and the pharmacy IT fixes

Date: 25 Nov 2026 · Status: IN FORCE

## What the human said
In chat on 25 Nov 2026: "i am not here, for all development i approved, so you take your decision and go ahead for development".

## How the tech lead applies it
This builds on DEC-073.
1. Open questions are decided by the tech lead and recorded in the decision files. This covers architecture questions inside the approved design, review findings, and test-side questions. The tech lead does not stop to ask.
2. The pipeline keeps running without a human:
   - SAH-90..93: fix round, QA rerun, security and code re-checks.
   - The pharmacy IT fixes in their own SAH-80 commit, as DEC-098 says.
3. A new Critical or High security finding is fixed in the same way as DEC-098. The tech lead picks the option closest to existing behaviour and the stricter privacy choice, and records it.
4. Clinical, legal, billing or pricing rules that are unclear get the conservative existing behaviour and go on the go-live checklists, as DEC-094 and DEC-073 say.

## What still waits for the human
These are not tech-lead powers.
- The lines in docs/approvals.md.
- Saving the console mockup in docs/design and updating the screen map.
- Making the commits and approving `merge`. Agents and the tech lead never push or merge.
- Repairing sahaj_dev, deleting stray files, and removing lock files.
- Anything destructive to the database, or outside phase 1. These are not expected in this work.
