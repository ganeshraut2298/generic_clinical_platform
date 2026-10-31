# Human approvals (humans edit this file; agents cannot)

The workflow hook reads this table. An agent cannot start its stage until the earlier stage is APPROVED here.
- `stories` approved → solution-architect may start.
- `contract` approved → db-engineer, developers and QA may start.
- `merge` approved → you merge the feature branch into main yourself.

| Story | Stage | Decision | By | Date |
|---|---|---|---|---|
| SAH-0 | stories | APPROVED | Aarti | 2026-10-31 |
| SAH-0 | contract | APPROVED | Aarti | 2026-10-31 |
