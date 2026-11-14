-- SAH-49, 60 to 66, 69 to 71 (ADR-023 D2, "Data needed" item 8) · Reserve the field key "specialty".
-- A specialty component value is stored in the EMR record under sections.<sectionKey>.values.specialty (ADR-023 D2), so no
-- library field may use that key. Forward-only, additive: one check on the global table opd.emr_section_field.
-- Checked on 12 Nov 2026: none of the 313 seeded fields uses the key (V2026_12_08_1020), so the check is valid for every
-- existing row. A later library migration that adds a field with this key fails, as intended.

alter table opd.emr_section_field
  add constraint ck_emr_section_field_key_not_reserved check (field_key <> 'specialty');

comment on constraint ck_emr_section_field_key_not_reserved on opd.emr_section_field is
  'The field key "specialty" is reserved for the specialty component value of an EMR record section (ADR-023 D2).';
