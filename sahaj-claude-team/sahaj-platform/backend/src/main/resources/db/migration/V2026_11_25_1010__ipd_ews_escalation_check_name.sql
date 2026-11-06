-- SAH-30 fix pass, follow-up to V2026_11_25_1000: rename the score/risk consistency check so it sorts after
-- ck_ews_escalation_score and ck_ews_escalation_risk_target. PostgreSQL evaluates check constraints in name order, so a
-- bad range (16, -1) or a risk/target mismatch is still reported by its own constraint (the app maps by constraint name).
-- Same expression; the table is small, the rename is metadata only.
alter table ipd.ews_escalation rename constraint ck_ews_escalation_risk_score to ck_ews_escalation_score_risk;
