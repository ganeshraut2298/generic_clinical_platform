-- SAH-31 (review R7): comment-only migration. No schema, data, grant or policy change.
--
-- V2026_11_28_1000__ipd_medicine_orders.sql is already applied and must not be edited (Flyway checksum). Its header
-- (line 9) and its stored table comments still say ipd.drug and ipd.drug_interaction have "no row level security".
-- That is out of date: section 3b of that migration enabled and forced row level security on both tables
-- (select for sahaj_app using (true), all for sahaj_migrator). This migration restates the two table comments so the
-- catalog tells the truth. The header text of V2026_11_28_1000 is superseded by this file.

comment on table ipd.drug is 'IPD medicine catalogue (SAH-31, ADR-009 D3): the mockup''s 14 demo medicines with their classes, routes and high-alert flags. Global reference data: no tenant_id column. Row level security is enabled and forced, with a non-tenant policy set: select only for sahaj_app (p_drug_select, using true) and all for sahaj_migrator (p_drug_migrator). sahaj_app has no insert, update or delete privilege, so content is changed only by migrations. Demo content, not clinically validated (OQ-31-1, blocking for go-live). Not opd.medicine.';

comment on table ipd.drug_interaction is 'IPD drug interaction rules by class pair (SAH-31, ADR-009 D3). Stored once with class_a < class_b, matched in both directions. Global reference data: no tenant_id column. Row level security is enabled and forced, with a non-tenant policy set: select only for sahaj_app (p_drug_interaction_select, using true) and all for sahaj_migrator (p_drug_interaction_migrator). sahaj_app has no insert, update or delete privilege, so content is changed only by migrations. Demo content from the mockup (OQ-31-1).';
