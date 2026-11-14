# DEC-049 · Closing the older open items (DEC-040 set-up, DEC-013 item 14, partition upkeep, SAH-42 human checks)

Date: 13 Nov 2026. Status: ANSWERED 13 Nov 2026 (human, chat). Raised by: tech lead, after the human asked to close "7. Older open items".
Blocked: nothing in development; these items block go-live, the WhatsApp/ABDM sending switch and the SAH-42 bootstrap switch.

## 1. DEC-040 WhatsApp and ABDM set-up
Fact: the in-app parts are built with sending switched off (`needs-whatsapp-service`). The modules `notifications` and `integration` do not exist yet: they need human edits to CLAUDE.md, .claude/rules/postgresql.md, scripts/setup/create-database.mjs and .env.example, plus a Meta WhatsApp Business account, ABDM sandbox registration and a legal opinion.
- (a) Tech lead writes the exact text for each human-owned file in one note; the human pastes it and starts the accounts. Pro: keeps DEC-039 answer 4. Con: accounts and legal opinion take weeks; outside the agents' reach.
- (b) Move WhatsApp sending and ABDM sharing back to "Later" (a new story when the accounts exist); the switched-off parts stay as built. Pro: closes the item now, no half-configured modules. Con: reverses DEC-039 answer 4 for now.
Recommendation: (b) now, (a) when the accounts are ready.

## 2. DEC-013 item 14 · Who may read patient details
Fact: code `PatientRoles.READ` excludes BILLING, PHARMACIST, COUNSELLOR; patient.yaml says any clinic role. One IT is skipped until decided.
- (a) BILLING and PHARMACIST may read (name on bills and dispensing), COUNSELLOR not; patient.yaml corrected to list the roles. 
- (b) Keep the stricter code; correct patient.yaml.
- (c) All clinic roles, as patient.yaml says.
Recommendation: (a).

## 3. Partition upkeep for `opd.emr_record` (first deadline 25 Oct 2028)
- (a) Automate: a Flyway repeatable migration (runs as `sahaj_migrator` on every deploy) that ensures the next months' partitions; plus a check that warns when fewer than 3 future months exist. No human calendar task. db-engineer change, additive.
- (b) Human operations task: a named person runs the SQL monthly.
Recommendation: (a).

## 4. SAH-42 human checks
Not a choice: after `sahaj_test` is recreated the tech lead runs the bootstrap ITs with `-Dsahaj.bootstrap.requireDb=true` (0 skipped). Still only the human can: put the BCrypt hash of the registration PIN in `.env` (never the PIN itself), and do the browser checks M-4 (binding cookie on http://localhost), M-5 (Chrome/Edge do not offer to save the PIN) and C1 (page against the real dev-tools backend). The switch stays off until these pass.

## 5. `D:/tmp_edit.sh`
Checked 13 Nov 2026: the file does not exist. Closed.

## Answer (human, chat, 13 Nov 2026; all recommended options)
1. **WhatsApp sending and ABDM sharing: move back to Later.** A new story when the Meta account, ABDM sandbox registration and legal opinion exist. The switched-off in-app parts stay as built (`needs-whatsapp-service`). The pending human file edits from DEC-040 (CLAUDE.md, postgresql.md, create-database.mjs, .env.example) and the screen-map move to phase 1 are no longer needed now. Partly reverses DEC-039 answer 4 (Marathi/Hindi labels stay in).
2. **Patient read: BILLING and PHARMACIST may read patient details; COUNSELLOR may not.** patient.yaml is corrected to list the roles; PatientRoles.READ gains BILLING and PHARMACIST; date of birth and mobile still follow their own allow-lists (DateOfBirthAccess). Closes DEC-013 item 14.
3. **Partition upkeep: automate.** A Flyway step that runs on every migrate (as sahaj_migrator) ensures the coming months of `opd.emr_record`, plus an app warning when fewer than 3 future months exist. No calendar task.
4. SAH-42 checks: unchanged (after sahaj_test is recreated; PIN hash and browser checks M-4, M-5, C1 by the human).
5. D:/tmp_edit.sh: closed (does not exist).
