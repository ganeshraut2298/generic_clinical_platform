# DEC-090 · Owner and the sale discount rule: the mockup wins

Date: 21 Nov 2026 · Programme: DEC-061 · Status: DECIDED (CLAUDE.md rule 1).
Context: I asked the architect to treat the owner as not exempt from his own SALE_DISCOUNT rule. Mockup 05 (Compliance, roles and audit), row "Discount above 10%", shows Owner = "Allow". The mockup is the design, so my earlier instruction is withdrawn.
Decision: the OWNER is exempt from the SALE_DISCOUNT rule (read-out cell ALLOW; createBill does not require approval for the owner). The architect corrects item 7 of contract 0.3.7; java changes createBill, the quote and RoleReadoutService; QA tests it.
Also: the H1 register screen (HOD sees names, a legal record for inspection) stays as designed; only the report path masks names (DEC-089 item 3, contract 0.3.7). A pharmacist for these checks means a PHARMACIST member of that store (per-store reading). Security review to confirm at the next round.
