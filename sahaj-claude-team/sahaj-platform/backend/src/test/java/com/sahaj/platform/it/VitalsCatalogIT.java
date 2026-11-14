package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 security review L6: the append-only and guard triggers, and FORCE row level security on the clinical tables,
 * are still switched on in the live database catalog. A migration (V2026_11_24 does it once, on purpose) or a person
 * with the migrator password can switch a trigger off or take away FORCE; nothing else would notice. This reads
 * {@code pg_trigger.tgenabled} ('O' = enabled in the normal session mode) and {@code pg_class.relforcerowsecurity}.
 * Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsCatalogIT extends VitalsSupport {

    private static final String SCHEMAS = "('ipd', 'platform', 'patient', 'opd', 'billing')";

    /** Triggers by "schema.table.trigger=enabled-flag". */
    private List<String> triggers(String whereTriggerName) throws Exception {
        return db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select n.nspname || '.' || cl.relname || '.' || t.tgname || '=' || t.tgenabled::text "
                    + "from pg_trigger t join pg_class cl on cl.oid = t.tgrelid join pg_namespace n on n.oid = cl.relnamespace "
                    + "where not t.tgisinternal and n.nspname in " + SCHEMAS + " and (" + whereTriggerName + ") order by 1");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    @Test
    void everyAppendOnlyAndGuardUpdateTriggerOfTheFiveModuleSchemasIsEnabled() throws Exception {
        List<String> found = triggers("t.tgname ~ '^trg_.+_no_.+$' or t.tgname ~ '^trg_.+_guard_update$'");
        // the migrations create 36 of them today (append-only pairs, escalation and emergency deletes, two guards)
        assertThat(found).as("triggers found by name").hasSizeGreaterThanOrEqualTo(30);
        List<String> notEnabled = new ArrayList<>();
        for (String f : found) {
            if (!f.endsWith("=O")) {
                notEnabled.add(f);
            }
        }
        assertThat(notEnabled).as("append-only or guard trigger not enabled ('D' = disabled, 'R' replica only, 'A' always)")
                .isEmpty();
    }

    @Test
    void theTriggersOfTheClinicalTablesAndTheTwoGuardsAreThereByName() throws Exception {
        List<String> found = triggers("true");
        for (String expected : new String[] {
                "ipd.vitals.trg_vitals_no_update_delete", "ipd.vitals.trg_vitals_no_truncate",
                "ipd.nursing_note.trg_nursing_note_no_update_delete", "ipd.nursing_note.trg_nursing_note_no_truncate",
                "ipd.clinical_event.trg_clinical_event_no_update_delete", "ipd.clinical_event.trg_clinical_event_no_truncate",
                "ipd.ews_escalation.trg_ews_escalation_no_delete", "ipd.ews_escalation.trg_ews_escalation_no_truncate",
                "ipd.ews_escalation.trg_ews_escalation_guard_update",
                "ipd.emergency_case.trg_emergency_case_guard_update", "ipd.emergency_case.trg_emergency_case_no_delete",
                "ipd.emergency_case.trg_emergency_case_no_truncate",
                "ipd.emergency_triage_event.trg_emergency_triage_event_no_update_delete",
                "platform.audit_event.trg_audit_event_no_update_delete",
                // SAH-32: the medicine administration record is append-only too, and its insert guard is enabled
                "ipd.dose_administration.trg_dose_administration_no_update_delete",
                "ipd.dose_administration.trg_dose_administration_no_truncate",
                "ipd.dose_administration.trg_dose_administration_guard_insert",
                // SAH-67-1 (SAH-51-68): the round note table is insert-only
                "ipd.round_note.trg_round_note_no_update_delete", "ipd.round_note.trg_round_note_no_truncate"}) {
            assertThat(found).as("trigger " + expected).contains(expected + "=O");
        }
    }

    @Test
    void noTriggerAtAllInTheFiveModuleSchemasIsDisabledOrReplicaOnly() throws Exception {
        List<String> notEnabled = new ArrayList<>();
        for (String f : triggers("true")) {
            if (!f.endsWith("=O")) {
                notEnabled.add(f);
            }
        }
        assertThat(notEnabled).isEmpty();
    }

    @Test
    void theClinicalTablesStillHaveRowLevelSecurityEnabledAndForced() throws Exception {
        List<String> weak = db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select n.nspname || '.' || cl.relname || ' enabled=' || cl.relrowsecurity::text "
                    + "|| ' forced=' || cl.relforcerowsecurity::text from pg_class cl join pg_namespace n on n.oid = cl.relnamespace "
                    + "where n.nspname = 'ipd' and cl.relname in ('vitals', 'nursing_note', 'clinical_event', 'ews_escalation', "
                    + "'emergency_case', 'emergency_triage_event', 'dose_administration') and not (cl.relrowsecurity and cl.relforcerowsecurity) "
                    + "order by 1");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        assertThat(weak).as("clinical tables without enabled and forced row level security").isEmpty();
        long present = db().<Long>asMigrator(c -> DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n "
                + "on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname in ('vitals', 'nursing_note', "
                + "'clinical_event', 'ews_escalation', 'emergency_case', 'emergency_triage_event', 'dose_administration')"));
        assertThat(present).as("all seven tables exist").isEqualTo(7);
    }

    /**
     * SAH-53 (ADR-020 D4, D6): the starter layout table (V2026_12_08_1000) and the clinic's published department version
     * table (V2026_12_08_1040) cannot be updated, deleted or truncated by anyone, the table owner included, because of
     * these four triggers; switching one off would let a published layout change under the records that point at it.
     * Test plan: docs/test-plans/SAH-53.md.
     */
    @Test
    void theEmrLayoutImmutabilityTriggersAreThereByNameAndEnabled() throws Exception {
        List<String> found = triggers("t.tgname like 'trg\\_emr\\_%'");
        for (String expected : new String[] {
                "opd.emr_starter_layout.trg_emr_starter_layout_immutable", "opd.emr_starter_layout.trg_emr_starter_layout_no_truncate",
                "opd.emr_department_version.trg_emr_department_version_immutable",
                "opd.emr_department_version.trg_emr_department_version_no_truncate"}) {
            assertThat(found).as("trigger " + expected).contains(expected + "=O");
        }
        // every trg_emr_ trigger of the migrations, by name (a partitioned table repeats its trigger on each partition): the layout,
        // form, starter-form and chemotherapy-protocol tables (SAH-53, SAH-48-59, SAH-49-71) and the three phase 5 tables (SAH-51-68)
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String f : found) {
            assertThat(f).as("enabled").endsWith("=O");
            names.add(f.substring(f.lastIndexOf('.') + 1, f.length() - 2));
        }
        assertThat(names).as("no other trg_emr_ trigger appeared unannounced").containsExactlyInAnyOrder(
                "trg_emr_starter_layout_immutable", "trg_emr_starter_layout_no_truncate", "trg_emr_department_version_immutable",
                "trg_emr_department_version_no_truncate", "trg_emr_form_starter_immutable", "trg_emr_form_starter_no_truncate",
                "trg_emr_form_version_immutable", "trg_emr_form_version_no_truncate", "trg_emr_chemo_protocol_immutable",
                "trg_emr_chemo_protocol_no_truncate", "trg_emr_record_signed_guard", "trg_emr_record_addendum_no_update_delete",
                "trg_emr_record_addendum_no_truncate", "trg_emr_admission_request_guard", "trg_emr_admission_request_no_truncate");
        for (String expected : new String[] {"opd.emr_record.trg_emr_record_signed_guard", "opd.emr_record_addendum.trg_emr_record_addendum_no_update_delete",
                "opd.emr_record_addendum.trg_emr_record_addendum_no_truncate", "opd.emr_admission_request.trg_emr_admission_request_guard",
                "opd.emr_admission_request.trg_emr_admission_request_no_truncate"}) {
            assertThat(found).as("SAH-51-68 trigger " + expected).contains(expected + "=O");
        }
        // the function behind them still refuses with the documented SQLSTATE and wording
        String definition = db().asMigrator(c -> DbAccess.text(c, "select pg_get_functiondef('opd.deny_emr_immutable_change()'::regprocedure)"));
        assertThat(definition).contains("insufficient_privilege").contains("is immutable");
    }

    /** SAH-43 / SAH-53: the five clinic EMR tables keep enabled and forced row level security. */
    @Test
    void theFiveEmrClinicTablesHaveRowLevelSecurityEnabledAndForced() throws Exception {
        List<String> weak = db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select n.nspname || '.' || cl.relname || ' enabled=' || cl.relrowsecurity::text "
                    + "|| ' forced=' || cl.relforcerowsecurity::text from pg_class cl join pg_namespace n on n.oid = cl.relnamespace "
                    + "where n.nspname = 'opd' and cl.relname in ('emr_item_usage', 'emr_department', 'emr_department_version', "
                    + "'emr_department_doctor', 'emr_record') and not (cl.relrowsecurity and cl.relforcerowsecurity) order by 1");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        assertThat(weak).as("EMR clinic tables without enabled and forced row level security").isEmpty();
        long present = db().<Long>asMigrator(c -> DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n "
                + "on n.oid = cl.relnamespace where n.nspname = 'opd' and cl.relname in ('emr_item_usage', "
                + "'emr_department', 'emr_department_version', 'emr_department_doctor', 'emr_record')"));
        assertThat(present).as("all five tables exist").isEqualTo(5);
    }
}
