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
                "ipd.dose_administration.trg_dose_administration_guard_insert"}) {
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
     * SAH-43 / SAH-44 (ADR-019 D4): the three template tables cannot be updated, deleted or truncated by anyone, the table
     * owner included, because of these six triggers. They are created in V2026_12_08_1000; switching one off would let a
     * shipped template version change under the visits that refer to it. Test plan: docs/test-plans/SAH-43-44.md.
     */
    @Test
    void theEmrTemplateImmutabilityTriggersAreThereByNameAndEnabled() throws Exception {
        List<String> found = triggers("t.tgname like 'trg\\_emr\\_%'");
        for (String expected : new String[] {
                "opd.emr_template.trg_emr_template_immutable", "opd.emr_template.trg_emr_template_no_truncate",
                "opd.emr_template_section.trg_emr_template_section_immutable",
                "opd.emr_template_section.trg_emr_template_section_no_truncate",
                "opd.emr_template_option.trg_emr_template_option_immutable",
                "opd.emr_template_option.trg_emr_template_option_no_truncate"}) {
            assertThat(found).as("trigger " + expected).contains(expected + "=O");
        }
        assertThat(found).as("no other trg_emr_ trigger appeared unannounced").hasSize(6);
        // the function behind them still refuses with the documented SQLSTATE and wording
        String definition = db().asMigrator(c -> DbAccess.text(c, "select pg_get_functiondef('opd.deny_emr_template_change()'::regprocedure)"));
        assertThat(definition).contains("insufficient_privilege").contains("is immutable");
    }

    /** SAH-43 / SAH-44: the four new clinic tables keep enabled and forced row level security. */
    @Test
    void theFourEmrClinicTablesHaveRowLevelSecurityEnabledAndForced() throws Exception {
        List<String> weak = db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select n.nspname || '.' || cl.relname || ' enabled=' || cl.relrowsecurity::text "
                    + "|| ' forced=' || cl.relforcerowsecurity::text from pg_class cl join pg_namespace n on n.oid = cl.relnamespace "
                    + "where n.nspname = 'opd' and cl.relname in ('emr_template_setup', 'emr_template_override', 'emr_settings', "
                    + "'emr_item_usage') and not (cl.relrowsecurity and cl.relforcerowsecurity) order by 1");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        assertThat(weak).as("EMR clinic tables without enabled and forced row level security").isEmpty();
        long present = db().<Long>asMigrator(c -> DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n "
                + "on n.oid = cl.relnamespace where n.nspname = 'opd' and cl.relname in ('emr_template_setup', "
                + "'emr_template_override', 'emr_settings', 'emr_item_usage')"));
        assertThat(present).as("all four tables exist").isEqualTo(4);
    }
}
