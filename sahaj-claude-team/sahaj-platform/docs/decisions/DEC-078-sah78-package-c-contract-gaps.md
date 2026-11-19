# DEC-078 · SAH-78 package C: contract gaps found by the angular-developer

Date: 18 Nov 2026 · Story: SAH-78 (78-8 vendors, 78-9 approval rules, 78-10 numbering, reasons and print templates) · Authority: DEC-073
Status: DECIDED (auto). The architect writes them into pharmacy.yaml 0.2.1 (additive or wording only), and java and angular follow.

## Decisions
1. **Amount limit conflict (`PUT /approval-rules`).**
   - The text says 9,99,99,999 rupees and "99999999900 paise". 9,99,99,999 rupees is 9,999,999,900 paise, so the schema maximum is ten times the text.
   - The limit is **₹ 9,99,99,999 (9,999,999,900 paise)**. The story and the client already use it, and the smaller limit is the safer one. The architect fixes the schema maximum.
2. **`GRN_RATE_RISE` limit unit:** a **percentage**, like `SALE_DISCOUNT`. The conditions are Always or Above a percentage only. State it in the contract.
3. **Preset limits:** the server is the source of truth. Each preset in `GET /approval-rules` (or the preset response) lists the transaction types that need a limit (`limitTypes`). The client stops guessing. The architect adds the field. Strict means a limit for every type that has an amount; the list says which.
4. **Locked series (78-10):** once any number of the current financial year has been issued, the series cannot be changed (409 `series-locked`, "Numbers have already been issued this year."). While no number has been issued in the current financial year, the prefix and format can be changed. The new prefix therefore takes effect from the first number of a new financial year. This avoids a field for "from next year", and keeps each series consecutive. Contract, java and angular follow.
5. **Two vendor credit tiers (DEC-075):** not part of package C. They belong to vendor returns in SAH-79 WP-E, which needs them. The db-engineer adds the two columns in a WP-E migration (14xx), and the contract adds the fields then.
   - **Amended 19 Nov 2026:** the tiers were built in SAH-78 instead (contract 0.2.1, migration V2026_12_17_1290, the vendor sheet "Credit for damaged on receipt" and "Credit after expiry"). SAH-79-8 only reads them. DEC-079 deviation 7 is the same.
   - **A return line that is both damaged on receipt and expired starts from the lower of the two levels** (the vendor owes no more than its own policy; go-live check G-18, accountant).
   - The mockup chip "Quarantine · damaged" means damaged on receipt.
6. **Fixed reason:** `fixed: boolean` is added to the reason schema (additive), so the client does not identify `receipt-reject.short` by key.
7. **Vendor switch-on:** added, `POST /vendors/{id}/switch-on` (SETUP_EDIT, additive). The vendor name is unique, so a switched-off vendor could not otherwise be re-added or reused.
8. **Accepted as they are:**
   - `rules-changed` is handled by re-reading the rules;
   - reasons are addressed by `reasonKey`;
   - the rule versions endpoints have no screen yet;
   - the `linkedWard` and capability-split touch-ups belong to SAH-79 (WP-D and WP-G).
9. **The role matrix of mockup 05** (a read-out of what the engine enforces, DEC-074 OQ-80-38): not built in this release. It is a USP chip in the mockup and carries no contract data. It can be added with SAH-80.

## Work
- Architect: pharmacy.yaml 0.2.1 for items 1, 2, 3, 4, 6 and 7. The architect's locks free about 00:00.
- Java (package C backend, running now) builds to the contract as it is today. Items 1 to 4, 6 and 7 are a follow-up in the hardening round (DEC-077).
- Angular: the follow-up for the same items in the hardening round.
- QA: the Strict and Standard preset checks and the `limits.<TYPE>` 400 path.

## Blocked
Nothing.
