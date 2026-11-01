# Human approvals (humans edit this file; agents cannot)

Hooks read this table. An agent cannot start its stage until the earlier stage is APPROVED here.
- `stories` approved → solution-architect may start.
- `contract` approved → db-engineer, developers and QA may start.
- `merge` approved → you merge the feature branch into main yourself.

Add one line per decision, for example: `| SAH-1 | stories | APPROVED | Ganesh | 2026-10-31 |`

| Story | Stage | Decision | By | Date |
|---|---|---|---|---|
