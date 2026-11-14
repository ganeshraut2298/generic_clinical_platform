-- SAH-59 / DEC-051 Q1 (QA triage M3) · Correct the score rules of a shipped starter form: key "type" -> "kind".
-- V2026_12_10_1020 seeded the diabetic_foot starter with two score rules written {"type": "NUMBER_BELOW" | "CHOSEN_COUNT_BELOW", ...}.
-- The strict form model (EmrFormModel.ScoreRule(kind, fieldKey, threshold, points)) and docs/api/opd.yaml name that key "kind",
-- so "use this starter" and every read of the starter failed. V2026_12_10_1020 is already applied and is NOT edited (forward-only).
-- Scope: opd.emr_form_starter (global reference data, no clinic column, no row level security), row starter_key = 'diabetic_foot'
-- (the only starter with score rules; gcs and morse have "rules": []). Only the nested key inside fields[].score.rules[] changes;
-- every other key, field order and row is untouched. Idempotent: a rule that already has "kind" is left as it is, a second run changes nothing.
-- No clinic or patient data is touched. A form a clinic already copied from the starter is clinic data and is not rewritten here.
--
-- The starter table is immutable by trigger (trg_emr_form_starter_immutable, opd.deny_emr_immutable_change). A shipped starter is never
-- changed by the application or by hand. A seed correction by migration follows the project's precedent (V2026_11_24_1000): inside this one
-- migration transaction the trigger is disabled, the correction is made, and the trigger is enabled again. sahaj_migrator owns the table.
-- If anything fails the whole transaction rolls back, so the trigger is never left disabled.

set local lock_timeout = '30s';

alter table opd.emr_form_starter disable trigger trg_emr_form_starter_immutable;

update opd.emr_form_starter s
   set definition = jsonb_set(
         s.definition,
         '{fields}',
         (select jsonb_agg(
                   case
                     when jsonb_typeof(f.field -> 'score' -> 'rules') = 'array' then
                       jsonb_set(
                         f.field,
                         '{score,rules}',
                         coalesce(
                           (select jsonb_agg(
                                     case
                                       when jsonb_typeof(r.rule) = 'object' and jsonb_exists(r.rule, 'type') and not jsonb_exists(r.rule, 'kind')
                                         then (r.rule - 'type') || jsonb_build_object('kind', r.rule -> 'type')
                                       else r.rule
                                     end
                                     order by r.pos)
                              from jsonb_array_elements(f.field -> 'score' -> 'rules') with ordinality as r(rule, pos)),
                           '[]'::jsonb))
                     else f.field
                   end
                   order by f.pos)
            from jsonb_array_elements(s.definition -> 'fields') with ordinality as f(field, pos)))
 where s.starter_key = 'diabetic_foot'
   and exists (select 1
                 from jsonb_array_elements(s.definition -> 'fields') as f(field)
                 cross join lateral jsonb_array_elements(
                        case when jsonb_typeof(f.field -> 'score' -> 'rules') = 'array'
                             then f.field -> 'score' -> 'rules' else '[]'::jsonb end) as r(rule)
                where jsonb_typeof(r.rule) = 'object' and jsonb_exists(r.rule, 'type') and not jsonb_exists(r.rule, 'kind'));

alter table opd.emr_form_starter enable trigger trg_emr_form_starter_immutable;

-- Check inside the transaction: no starter has a score rule with "type" or without "kind", and the trigger is enabled again.
do $$
begin
  if exists (select 1
               from opd.emr_form_starter s
               cross join lateral jsonb_array_elements(s.definition -> 'fields') as f(field)
               cross join lateral jsonb_array_elements(
                      case when jsonb_typeof(f.field -> 'score' -> 'rules') = 'array'
                           then f.field -> 'score' -> 'rules' else '[]'::jsonb end) as r(rule)
              where jsonb_exists(r.rule, 'type') or not jsonb_exists(r.rule, 'kind')) then
    raise exception 'emr_form_starter still has a score rule without "kind" (SAH-59 / DEC-051 Q1)';
  end if;
  if not exists (select 1 from pg_trigger
                  where tgrelid = 'opd.emr_form_starter'::regclass
                    and tgname = 'trg_emr_form_starter_immutable' and tgenabled = 'O') then
    raise exception 'trg_emr_form_starter_immutable is not enabled again (SAH-59 / DEC-051 Q1)';
  end if;
end
$$;
