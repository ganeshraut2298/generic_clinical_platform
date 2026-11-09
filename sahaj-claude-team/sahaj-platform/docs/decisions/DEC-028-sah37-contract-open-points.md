# DEC-028 · SAH-37 contract: open points O1–O7

Date: 9 Nov 2026. Status: DEFAULTS APPLIED (DEC-023). The human can reverse any of them.
Source: docs/architecture/SAH-37-patient-card-setup.md section 9; ADR-015.
These points do not change the stored shape, so the db-engineer can start.

| # | Point | Default applied (Recommended) |
|---|---|---|
| O1 (safety) | Early warning is locked "always on", so the default card shows an Early warning line next to Allergies. DEC-027 named only Allergies. No new data is sent. | Accept. |
| O2 (privacy) | NURSE and DOCTOR still receive bill, deposit and payer in each list row, because the existing Table view uses them. Rule 11 says "not sent". | A follow-up story (data minimisation of the Table view). No change in SAH-37. |
| O3 (privacy) | When an owner switches on Diagnosis or vitals, the ward list carries them with no record-access row. | Covered by the DEC-021 exception. **Go-live condition:** add Diagnosis and the latest vitals to the privacy register entry (S36-1). |
| O4 | Switching Mobile on sends it to OWNER and FRONT_DESK, and the owner's preview loads all mobiles. This amends ADR-013 D15. | Accept. It is the owner's explicit choice, limited to roles that already read mobiles. |
| O5 | "Last recorded" shows the recorder's staff name, as the mockup does. | Accept (staff name, not patient data). |
| O6 | The Hospital template keeps vitals and Diagnosis off under DEC-027. The mockup's Hospital card has them. | Keep them off (DEC-027 rule 6). |
| O7 | Discard and Reset are not written to the change history, as in the mockup. | Accept. |

Go-live conditions, carried over:
- A clinician signs off the default card and the tile marks (DEC-027).
- The privacy register entry is updated (O3).

## Human answer
(none yet; defaults applied under DEC-023)

## Addendum, frontend readings (9 Nov 2026, defaults applied under DEC-023)
| # | Reading | Default |
|---|---|---|
| F1 | AC55 limits Compact vitals to BP, SpO₂ and Pulse, but AC57 says Early warning is always shown. | Compact shows the three plus a small Early warning mark. Safer, because Early warning is locked (mockup Compact drops it). |
| F2 | With no vitals, the card shows "No vitals recorded yet", plus "Last recorded: Not recorded" when that field is on. | As AC34 says. |
| F3 | The tablet preview frame is 300 px, as in the mockup, with a 768 px note. | Accept. |
| F4 | The non-owner preview uses the Example patient only. `cardFacts=all` is OWNER only (ADR-015 D6), but the mockup lists real patients for every role. | Accept: data minimisation. |
| F5 | A client-side fallback card is used only when cardSetup is missing. There is also a client safety guard; the server is the real protection. | Accept. |
| F7 | A failed load (network or 5xx) shows fixed text. A 403 shows the server's message. | Accept. |

## Addendum, draft revision design (9 Nov 2026, default applied under DEC-023)
- **Question (from the java-developer):** how should the draft revision never repeat for a clinic?
  - **A.** A clock-based opaque token: `max(microseconds since 1970, previous revision + 1)`. No schema change.
  - **B.** A persistent per-clinic counter, which needs a migration.
- **Decision: A.** It is the Recommended option because it closes the stale-draft overwrite (AC12) with no schema change. It fits the contract text "opaque, never repeats". The code and security re-checks accepted it as closed.
- **Consequences:**
  - QA changes the 14 revision tests so they read the revision from the previous answer.
  - QA flips the known-gap test to a 409.
  - The value is about 1.79e15, below 2^53, so a browser number holds it exactly.
  - The server clock must not run backwards by more than the gap between a discard and the next save.
