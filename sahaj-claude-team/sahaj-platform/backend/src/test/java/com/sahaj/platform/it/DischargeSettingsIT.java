package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-27 Clinic setup, Discharge ({@code GET} and {@code PUT /ipd/discharge-settings}) on the real {@code sahaj_test}
 * database: defaults and the lazy row, saving, an unchanged save, owner only, Read-only, module Off, invalid bodies,
 * the OPD follow-up rule shown beside it, and tenant isolation. Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeSettingsIT extends DischargeSupport {

    private static final String CONFIG_SQL = "select coalesce(discharge_template_key, 'null'), discharge_chain::text, "
            + "followup_days::text from ipd.config";

    @Test
    void aClinicThatNeverSavedGetsNoTemplateTheChainOnAndSevenDaysAndReadingCreatesNoRow() throws Exception {
        Clinic clinic = hospital();
        long rows = rows(clinic, "config");
        for (String role : READERS) {
            JsonNode s = ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as(role), null, 200);
            assertThat(s.get("templateKey").isNull()).as(role).isTrue();
            assertThat(s.get("approvalChain").asBoolean()).as(role).isTrue();
            assertThat(s.get("followUpDays").asInt()).as(role).isEqualTo(7);
            assertThat(sheetOrder(s.get("types"))).isEqualTo("normal,lama,referred,death");
            assertThat(sheetOrder(s.get("templates"))).isEqualTo("general,surgical,maternity,daycare");
            assertThat(s.get("types").get(1).get("label").asText()).isEqualTo("On request (LAMA)");
            assertThat(s.get("templates").get(3).get("label").asText()).isEqualTo("Day-care");
            assertThat(s.get("canEdit").asBoolean()).as(role + " canEdit").isEqualTo("OWNER".equals(role));
        }
        assertThat(rows(clinic, "config")).isEqualTo(rows);
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as(role), null), 403);
        }
    }

    @Test
    void theOwnerSavesEachSettingAtOnceTheRowKeepsTheOtherRulesAndTheValuesComeBack() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "next");                                       // the transfer rules are in the same row
        setAlertPercent(clinic, 65);
        JsonNode first = putSettings(clinic, "surgical", true, 7);
        assertThat(first.get("templateKey").asText()).isEqualTo("surgical");
        assertThat(first.get("canEdit").asBoolean()).isTrue();
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("surgical", "true", "7");
        putSettings(clinic, "surgical", false, 7);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("surgical", "false", "7");
        putSettings(clinic, "surgical", false, 30);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("surgical", "false", "30");
        putSettings(clinic, "maternity", false, 1);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("maternity", "false", "1");
        // a missing or null template stores none
        assertThat(ok(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), "{\"approvalChain\":true,\"followUpDays\":7}", 200)
                .get("templateKey").isNull()).isTrue();
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("null", "true", "7");
        putSettings(clinic, "daycare", true, 14);
        putSettings(clinic, null, true, 14);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("null", "true", "14");

        // one row, and the other settings of the row were not touched
        assertThat(rows(clinic, "config")).isOne();
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200).get("newRateFrom").asText()).isEqualTo("next");
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200).get("upgradeNeedsOwner").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.GET, RULES, clinic.owner(), null, 200).get("alertPercent").asInt()).isEqualTo(65);
        // saving the other pages does not reset Discharge setup
        setRules(clinic, true, "same");
        setAlertPercent(clinic, 70);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("null", "true", "14");
        JsonNode read = ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as("NURSE"), null, 200);
        assertThat(read.get("followUpDays").asInt()).isEqualTo(14);
        assertThat(read.get("canEdit").asBoolean()).isFalse();
    }

    @Test
    void aFirstSaveCreatesTheRowWithTheDefaultsOfEverythingElse() throws Exception {
        Clinic clinic = hospital();
        long rows = rows(clinic, "config");
        putSettings(clinic, "daycare", true, 7);
        assertThat(rows(clinic, "config")).isEqualTo(Math.max(rows, 1));
        JsonNode rules = ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200);
        assertThat(rules.get("upgradeNeedsOwner").asBoolean()).isTrue();
        assertThat(rules.get("newRateFrom").asText()).isEqualTo("same");
    }

    @Test
    void anUnchangedSaveWritesNoAuditRowAndAChangeWritesOneWithBeforeAndAfter() throws Exception {
        Clinic clinic = hospital();
        putSettings(clinic, null, true, 7);
        putSettings(clinic, null, true, 7);
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).as("equal to the defaults: nothing changed").isZero();
        putSettings(clinic, "general", true, 7);
        putSettings(clinic, "general", true, 7);
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isOne();
        putSettings(clinic, "general", true, 8);
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isEqualTo(2);
        var last = audit(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED").get(1);
        assertThat(objectMapper.readTree(last[2])).isEqualTo(objectMapper.readTree(
                "{\"templateKey\":\"general\",\"approvalChain\":true,\"followUpDays\":7}"));
        assertThat(objectMapper.readTree(last[3])).isEqualTo(objectMapper.readTree(
                "{\"templateKey\":\"general\",\"approvalChain\":true,\"followUpDays\":8}"));
        assertThat(last[4]).isEqualTo(clinic.ownerUserId().toString());
    }

    @Test
    void onlyTheOwnerChangesTheSetupEveryOtherRoleGets403AndTheValuesStay() throws Exception {
        Clinic clinic = hospital();
        putSettings(clinic, "surgical", false, 11);
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.as(role), settingsBody("general", true, 3)), 403);
            // the role is checked before the body
            problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.as(role), "{}"), 403);
        }
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("surgical", "false", "11");
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isOne();
    }

    @Test
    void readOnlyIpdRefusesTheChangeAfterTheBodyIsCheckedAndStillShowsTheValuesWithCanEditFalse() throws Exception {
        Clinic clinic = hospital();
        putSettings(clinic, "maternity", true, 5);
        expireTrial(clinic);
        JsonNode p = problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), settingsBody("general", true, 3)), 409);
        assertType(p, "ipd-readonly");
        assertThat(p.get("detail").asText()).isEqualTo(READ_ONLY);
        // an invalid body is still a 400 first
        assertFieldError(problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), settingsBody("general", true, 99)), 400),
                "followUpDays");
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("maternity", "true", "5");
        JsonNode s = ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.owner(), null, 200);
        assertThat(s.get("canEdit").asBoolean()).isFalse();
        assertThat(s.get("templateKey").asText()).isEqualTo("maternity");
    }

    @Test
    void ipdOffGetAndPutAre403ModuleOffAndNoRowIsCreated() throws Exception {
        Clinic clinic = newClinic();
        for (String role : READERS) {
            assertType(problem(call(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as(role), null), 403), "module-off");
        }
        assertType(problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), settingsBody("general", true, 7)), 403),
                "module-off");
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
    }

    @Test
    void invalidBodiesGet400OnTheFieldWithItsMessageAndNothingIsSaved() throws Exception {
        Clinic clinic = hospital();
        putSettings(clinic, "general", true, 9);
        String[][] bad = {
                {"{\"templateKey\":\"Surgical\",\"approvalChain\":true,\"followUpDays\":7}", "templateKey", "Choose a template from the list"},
                {"{\"templateKey\":\"xyz\",\"approvalChain\":true,\"followUpDays\":7}", "templateKey", "Choose a template from the list"},
                {"{\"templateKey\":\"\",\"approvalChain\":true,\"followUpDays\":7}", "templateKey", "Choose a template from the list"},
                {"{\"followUpDays\":7}", "approvalChain", "Choose on or off"},
                {"{\"approvalChain\":null,\"followUpDays\":7}", "approvalChain", "Choose on or off"},
                {"{\"approvalChain\":true}", "followUpDays", "Enter 1 to 30 days"},
                {"{\"approvalChain\":true,\"followUpDays\":null}", "followUpDays", "Enter 1 to 30 days"},
                {"{\"approvalChain\":true,\"followUpDays\":0}", "followUpDays", "Enter 1 to 30 days"},
                {"{\"approvalChain\":true,\"followUpDays\":-3}", "followUpDays", "Enter 1 to 30 days"},
                {"{\"approvalChain\":true,\"followUpDays\":31}", "followUpDays", "Enter 1 to 30 days"},
                {"{\"approvalChain\":true,\"followUpDays\":1000000}", "followUpDays", "Enter 1 to 30 days"}};
        for (String[] b : bad) {
            JsonNode p = problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), b[0]), 400);
            assertFieldError(p, b[1]);
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).as(b[0]).isEqualTo(b[2]);
        }
        for (String malformed : new String[] {"{", "[]", "\"x\"", "{\"approvalChain\":\"yes\",\"followUpDays\":7}",
                "{\"approvalChain\":true,\"followUpDays\":\"abc\"}"}) {
            problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), malformed), 400);
        }
        problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), null), 400);
        problem(call(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), "null"), 400);
        assertThat(values(clinic, CONFIG_SQL)).containsExactly("general", "true", "9");
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isOne();
        // the ends of the range are accepted
        assertThat(putSettings(clinic, null, true, 1).get("followUpDays").asInt()).isOne();
        assertThat(putSettings(clinic, null, true, 30).get("followUpDays").asInt()).isEqualTo(30);
    }

    @Test
    void theOpdFollowUpRuleShownBesideTheDaysIsTheOpdFeeSettingAndIsNotWritableHere() throws Exception {
        Clinic clinic = hospital();
        JsonNode defaults = ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.owner(), null, 200);
        assertThat(defaults.get("opdFollowUpRule").get("days").asInt()).isEqualTo(7);
        assertThat(defaults.get("opdFollowUpRule").get("fee").asText()).isEqualTo("free");
        setFees(clinic, 3, "half");
        JsonNode half = ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as("FRONT_DESK"), null, 200);
        assertThat(half.get("opdFollowUpRule").get("days").asInt()).isEqualTo(3);
        assertThat(half.get("opdFollowUpRule").get("fee").asText()).isEqualTo("half");
        setFees(clinic, 6, "full");
        JsonNode full = putSettings(clinic, null, true, 12);
        assertThat(full.get("opdFollowUpRule").get("days").asInt()).isEqualTo(6);
        assertThat(full.get("opdFollowUpRule").get("fee").asText()).isEqualTo("full");
        // a body that tries to write the OPD rule changes nothing there
        ok(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(),
                "{\"approvalChain\":true,\"followUpDays\":12,\"opdFollowUpRule\":{\"days\":1,\"fee\":\"free\"}}", 200);
        assertThat(ok(HttpMethod.GET, FEES, clinic.owner(), null, 200).get("followUpDays").asInt()).isEqualTo(6);
    }

    @Test
    void anotherClinicsSetupIsInvisibleAndUnchangedByThisClinicsSave() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        putSettings(a, "surgical", false, 21);
        putSettings(b, "maternity", true, 2);
        assertThat(values(a, CONFIG_SQL)).containsExactly("surgical", "false", "21");
        assertThat(values(b, CONFIG_SQL)).containsExactly("maternity", "true", "2");
        assertThat(ok(HttpMethod.GET, DISCHARGE_SETTINGS, a.owner(), null, 200).get("followUpDays").asInt()).isEqualTo(21);
        assertThat(ok(HttpMethod.GET, DISCHARGE_SETTINGS, b.owner(), null, 200).get("followUpDays").asInt()).isEqualTo(2);
        long audits = audits(b, "IPD_DISCHARGE_SETTINGS_UPDATED");
        assertThat(audits).isOne();
        // a body cannot name another clinic
        ok(HttpMethod.PUT, DISCHARGE_SETTINGS, b.owner(),
                "{\"templateKey\":\"general\",\"approvalChain\":true,\"followUpDays\":4,\"tenantId\":\"" + a.tenantId() + "\"}", 200);
        assertThat(values(a, CONFIG_SQL)).containsExactly("surgical", "false", "21");
        assertThat(values(b, CONFIG_SQL)).containsExactly("general", "true", "4");
        assertThat(UUID.fromString(audit(b, "IPD_DISCHARGE_SETTINGS_UPDATED").get(1)[4])).isEqualTo(b.ownerUserId());
    }
}
