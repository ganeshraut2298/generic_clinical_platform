# DEC-023 · Standing rule: no answer from the human means the recommended option

Status: DECIDED (human, chat, 7 Nov 2026): "if no ack from my end then u can select recommended by default".

Rule: when a decision question (AskUserQuestion or a DEC file) gets no answer, the tech lead takes the option marked Recommended, records it in the DEC file as "default applied (DEC-023)", and continues. The human can reverse any such default later.
Limits (unchanged): this is not an approval. The tech lead still cannot edit docs/approvals.md, claim a `stories`/`contract`/`merge` approval, commit, push or merge. Hard stops stay stops: anything outside phase 1, a destructive change on existing rows, a mockup difference with no safer reading, a Critical/High finding. Clinical rules still need a clinician's sign-off before a real ward uses them (recorded as go-live conditions).

## Applied 7 Nov 2026 - DEC-020 (SAH-38 / HARD-3)
All five recommendations taken: OQ-38-1 cap 5 wrong tries per caller+colleague pair per rolling 7 days, durable, fails closed; OQ-38-4 colleague-caused locks lock 15 min but never count toward switch-off; OQ-38-5 ward line only ("Do not give this medicine yet ... Never share a PIN"), no bypass, clinical procedure is the clinic's (go-live condition); OQ-38-6 new give events say "identity checked against UHID" (old rows unchanged), mismatch event "Stopped at the identity check: entered X, expected Y"; OQ-38-2 colleague sees counts only, owner sees names and times.
