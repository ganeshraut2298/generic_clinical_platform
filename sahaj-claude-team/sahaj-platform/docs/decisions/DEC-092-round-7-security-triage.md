# DEC-092 · Round 7 security triage

Date: 21 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). Report: docs/reviews/SAH-80-security.md. No Critical or High: no hard stop.
1. **M5:** as DEC-091 item 2, and also bound to the raising seller and the held bill (java, architect, db).
2. **M6:** java fixes (signer and canceller in the Schedule X reversal, signer on ledger lines, SECOND_SIGNATURE document event); QA tests. Must be fixed before go-live.
3. **L22, L23, L24, L27:** java.
4. **L25:** explicit CASHIER case: read stock plus the sale columns only (java; architect documents; RoleReadout follows).
5. **L26:** go-live checklist item: the owner must set a SALE_DISCOUNT limit before selling; the role read-out shows "No limit set" when absent (java). A default is not seeded (owner's pricing decision).
6. **I27:** the owner never handles Schedule X; a Schedule X bill is cancelled by a pharmacist (fails closed stays); architect corrects OQ-80-37 wording. **I28, I29:** java. **I30:** go-live checklist (DPDP consent notice for saved customers).
