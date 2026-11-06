package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-26 the two bed transfer rules ({@code GET} and {@code PUT /transfer-rules}) on the real {@code sahaj_test}
 * database: defaults with no stored row, the lazy row, an unchanged save that does nothing, saves that keep the other
 * settings, who may read and who may change, invalid bodies, Read-only and module Off. Test plan:
 * docs/test-plans/SAH-26.md.
 */
@OnTestDatabase
class TransferRulesIT extends BedTransferSupport {

    private static String body(Object upgradeNeedsOwner, Object newRateFrom) {
        return "{\"upgradeNeedsOwner\":" + upgradeNeedsOwner + ",\"newRateFrom\":"
                + (newRateFrom == null ? "null" : "\"" + newRateFrom + "\"") + "}";
    }

    private JsonNode rules(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200);
    }

    private JsonNode save(Clinic clinic, String body) throws Exception {
        return ok(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), body, 200);
    }

    // ---------------- defaults and the lazy row ----------------

    @Test
    void aClinicThatNeverSavedGetsOwnerApprovesAndSameDayAndNoRowIsCreatedByReading() throws Exception {
        Clinic clinic = hospital();
        for (String role : READERS) {
            JsonNode r = ok(HttpMethod.GET, TRANSFER_RULES, clinic.as(role), null, 200);
            assertThat(r.get("upgradeNeedsOwner").asBoolean()).as(role).isTrue();
            assertThat(r.get("newRateFrom").asText()).as(role).isEqualTo("same");
            assertThat(r.size()).isEqualTo(2);
        }
        assertThat(rows(clinic, "config")).as("reading is not writing").isZero();
        // the sheet carries the same values
        String id = quickAdmit(clinic).get("id").asText();
        JsonNode d = detail(clinic, id);
        assertThat(d.get("transferRules").get("upgradeNeedsOwner").asBoolean()).isTrue();
        assertThat(d.get("transferRules").get("newRateFrom").asText()).isEqualTo("same");
    }

    @Test
    void savingTheValuesTheClinicAlreadyHasIsANoOpWithNoRowAndNoAudit() throws Exception {
        Clinic clinic = hospital();
        JsonNode saved = save(clinic, body(true, "same"));
        assertThat(saved.get("upgradeNeedsOwner").asBoolean()).isTrue();
        assertThat(saved.get("newRateFrom").asText()).isEqualTo("same");
        assertThat(rows(clinic, "config")).as("the defaults need no row").isZero();
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isZero();

        save(clinic, body(false, "same"));
        assertThat(rows(clinic, "config")).isOne();
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isOne();
        String updatedAt = values(clinic, "select updated_at::text from ipd.config").get(0);
        save(clinic, body(false, "same"));
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).as("unchanged save is not audited").isOne();
        assertThat(values(clinic, "select updated_at::text from ipd.config")).containsExactly(updatedAt);
    }

    @Test
    void eachChoiceIsSavedAtOnceAndReadBackByEveryReaderAndOnTheSheet() throws Exception {
        Clinic clinic = hospital();
        boolean[] upgrade = {false, false, true, true};
        String[] rate = {"same", "next", "next", "same"};
        for (int i = 0; i < 4; i++) {
            JsonNode saved = save(clinic, body(upgrade[i], rate[i]));
            assertThat(saved.get("upgradeNeedsOwner").asBoolean()).isEqualTo(upgrade[i]);
            assertThat(saved.get("newRateFrom").asText()).isEqualTo(rate[i]);
            for (String role : READERS) {
                JsonNode r = ok(HttpMethod.GET, TRANSFER_RULES, clinic.as(role), null, 200);
                assertThat(r.get("upgradeNeedsOwner").asBoolean()).as(role).isEqualTo(upgrade[i]);
                assertThat(r.get("newRateFrom").asText()).as(role).isEqualTo(rate[i]);
            }
        }
        assertThat(rows(clinic, "config")).isOne();
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isEqualTo(4);
        assertThat(values(clinic, "select transfer_upgrade_owner_only::text, new_rate_from from ipd.config"))
                .containsExactly("true", "same");
    }

    @Test
    void theLazyConfigRowTakesTheDefaultsOfEveryOtherSettingAndASaveNeverChangesThem() throws Exception {
        Clinic clinic = hospital();
        // no config row yet: the first transfer-rules save creates it with the defaults for the other columns
        save(clinic, body(false, "next"));
        JsonNode layout = layout(clinic);
        assertThat(layout.get("roomLevel").asBoolean()).isTrue();
        assertThat(layout.get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
        assertThat(layout.get("charges").get("nursingPaise").asLong()).isEqualTo(NURSING);
        assertThat(layout.get("charges").get("visitPaise").asLong()).isEqualTo(VISIT);
        assertThat(rulesDefaults(clinic)).as("deposit rules keep their defaults").isEqualTo("80");

        // other settings changed first are kept by a later transfer-rules save
        setCharges(clinic, "24h", 45_000, 70_000);
        ok(HttpMethod.PUT, IPD + "/room-level", clinic.owner(), "{\"on\":false}", 200);
        setAlertPercent(clinic, 65);
        save(clinic, body(true, "same"));
        layout = layout(clinic);
        assertThat(layout.get("roomLevel").asBoolean()).isFalse();
        assertThat(layout.get("charges").get("bedDayCut").asText()).isEqualTo("24h");
        assertThat(layout.get("charges").get("nursingPaise").asLong()).isEqualTo(45_000);
        assertThat(layout.get("charges").get("visitPaise").asLong()).isEqualTo(70_000);
        assertThat(rulesDefaults(clinic)).isEqualTo("65");
        assertThat(rows(clinic, "config")).isOne();
        // and the other way round: saving the other settings keeps the transfer rules
        save(clinic, body(false, "next"));
        setCharges(clinic, "midnight", 30_000, 50_000);
        setAlertPercent(clinic, 70);
        assertThat(rules(clinic).get("upgradeNeedsOwner").asBoolean()).isFalse();
        assertThat(rules(clinic).get("newRateFrom").asText()).isEqualTo("next");
    }

    private String rulesDefaults(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, RULES, clinic.owner(), null, 200).get("alertPercent").asText();
    }

    // ---------------- who may read and who may change ----------------

    @Test
    void fourRolesReadFourRolesAreRefusedAndOnlyTheOwnerChangesAnonymousGets401() throws Exception {
        Clinic clinic = hospital();
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, TRANSFER_RULES, clinic.as(role), null), 403);
        }
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST",
                "COUNSELLOR"}) {
            problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.as(role), body(false, "next")), 403);
        }
        mvc.perform(get(TRANSFER_RULES)).andExpect(status().isUnauthorized());
        mvc.perform(put(TRANSFER_RULES).contentType(MediaType.APPLICATION_JSON).content(body(false, "next")))
                .andExpect(status().isUnauthorized());
        assertThat(rows(clinic, "config")).isZero();
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isZero();
        save(clinic, body(false, "next"));
        assertThat(rules(clinic).get("newRateFrom").asText()).isEqualTo("next");
    }

    // ---------------- invalid bodies ----------------

    @Test
    void invalidBodiesGet400WithAFieldErrorAndNothingIsSaved() throws Exception {
        Clinic clinic = hospital();
        save(clinic, body(false, "next"));
        long audits = audits(clinic, "IPD_TRANSFER_RULES_UPDATED");

        for (String bad : new String[] {"{}", "{\"newRateFrom\":\"same\"}", body("null", "same")}) {
            JsonNode p = problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), bad), 400);
            assertFieldError(p, "upgradeNeedsOwner");
            assertThat(p.get("fieldErrors").get(0).get("message").asText())
                    .isEqualTo("Choose who can move a patient to a higher category");
        }
        for (String bad : new String[] {"{\"upgradeNeedsOwner\":true}", body(true, null), body(true, "later"),
                body(true, ""), body(true, "SAME"), body(true, "Same day"), body(true, " same"), body(true, "same "),
                body(true, "nextday")}) {
            JsonNode p = problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), bad), 400);
            assertFieldError(p, "newRateFrom");
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isEqualTo("Choose Same day or Next day");
        }
        for (String bad : new String[] {"{\"upgradeNeedsOwner\":\"yes\",\"newRateFrom\":\"same\"}",
                "{\"upgradeNeedsOwner\":[true],\"newRateFrom\":\"same\"}", "{\"upgradeNeedsOwner\":true,\"newRateFrom\":5}",
                "not json", "[]", "{"}) {
            problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), bad), 400);
        }
        problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), null), 400);
        problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), "null"), 400);

        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isEqualTo(audits);
        assertThat(rules(clinic).get("upgradeNeedsOwner").asBoolean()).isFalse();
        assertThat(rules(clinic).get("newRateFrom").asText()).isEqualTo("next");
    }

    @Test
    void extraFieldsInTheBodyAreIgnored() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        save(clinic, "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"next\",\"tenantId\":\"" + other.tenantId()
                + "\",\"bedDayCut\":\"24h\",\"nursingPaise\":1}");
        assertThat(rows(other, "config")).isZero();
        assertThat(rules(other).get("newRateFrom").asText()).isEqualTo("same");
        assertThat(layout(clinic).get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
        assertThat(layout(clinic).get("charges").get("nursingPaise").asLong()).isEqualTo(NURSING);
    }

    // ---------------- module state ----------------

    @Test
    void readOnlyIpdStillReadsButRefusesTheChangeBeforeLookingAtTheBody() throws Exception {
        Clinic clinic = hospital();
        save(clinic, body(false, "next"));
        expireTrial(clinic);
        long audits = audits(clinic, "IPD_TRANSFER_RULES_UPDATED");
        for (String bad : new String[] {body(true, "same"), body(false, "next"), "{}", body(true, "later")}) {
            JsonNode p = problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), bad), 409);
            assertType(p, "ipd-readonly");
            assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
        }
        // the other roles are refused as before
        problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.as("NURSE"), body(true, "same")), 403);
        for (String role : READERS) {
            JsonNode r = ok(HttpMethod.GET, TRANSFER_RULES, clinic.as(role), null, 200);
            assertThat(r.get("upgradeNeedsOwner").asBoolean()).isFalse();
            assertThat(r.get("newRateFrom").asText()).isEqualTo("next");
        }
        assertThat(audits(clinic, "IPD_TRANSFER_RULES_UPDATED")).isEqualTo(audits);
    }

    @Test
    void ipdOffBothVerbsAreModuleOffForTheOwnerAndTheReadForEveryReader() throws Exception {
        Clinic clinic = newClinic();
        for (String role : READERS) {
            problem(call(HttpMethod.GET, TRANSFER_RULES, clinic.as(role), null), 403);
        }
        assertType(problem(call(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), body(false, "next")), 403), "module-off");
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
    }

    // ---------------- other clinics ----------------

    @Test
    void oneClinicsRulesNeverShowInAnotherClinic() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        save(a, body(false, "next"));
        assertThat(rules(b).get("upgradeNeedsOwner").asBoolean()).isTrue();
        assertThat(rules(b).get("newRateFrom").asText()).isEqualTo("same");
        save(b, body(true, "next"));
        assertThat(rules(a).get("upgradeNeedsOwner").asBoolean()).isFalse();
        assertThat(rows(a, "config")).isOne();
        assertThat(rows(b, "config")).isOne();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.config"));
        assertThat(unbound).as("no tenant bound").isZero();
        assertThat(audits(b, "IPD_TRANSFER_RULES_UPDATED")).isOne();
    }
}
