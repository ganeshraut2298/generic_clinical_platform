# DEC-087 · SAH-80: round-off ties and the capability layer

Date: 20 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). Go-live check G-22 for an accountant.

1. **Round off:** ADR-030 S4 says "round half up" and also range -50 to +49 paise (database check `ck_bill_round_off`). They conflict only on an exact half rupee. Decision: keep the database range; an exact .50 rounds down (net 10.50 is billed 10.00, round off -50). The architect corrects the ADR wording; the accountant confirms at go-live.
2. **Capability layer:** already designed in ADR-030 (CASHIER store role for team roles BILLING and STORE_STAFF at an active sale-allowed non-Central store; `sell`, `sellScheduled`, `substitute`, `overrideBlock` and the other sale capabilities derived by StoreCapabilities; `sale-not-allowed`; WP-I includes it). Java builds it first in the next run, changing StoreAccess and its callers/tests as needed.
