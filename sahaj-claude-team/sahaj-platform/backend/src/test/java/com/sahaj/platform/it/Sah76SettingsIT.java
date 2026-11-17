package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * SAH-76-6 nursing settings, ward profiles and the starter profiles ({@code GET/PUT /api/v1/ipd/nursing-settings},
 * {@code POST /nursing-profiles}, {@code PUT /nursing-profiles/{key}}, {@code PUT /wards/{id}/nursing-profile}) on the real
 * {@code sahaj_test} database: defaults before the first save, the upsert and its audit row, the starter profiles copied on the
 * first write, the 20-profile cap, the unique name, the key made from the name, the ranges and texts of every field, roles,
 * Read-only, another clinic, the device label, and the CORS answer that lets the browser send {@code X-Sahaj-Device}. What a
 * profile changes (verification, vitals interval, dose windows) is in {@link Sah76SettingsEffectIT}.
 * Test plan: docs/test-plans/SAH-76-dose-settings.md (tests S-01 to S-25).
 */
@OnTestDatabase
@WithPinPepper
class Sah76SettingsIT extends Sah76NursingSupport {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource")
    private CorsConfigurationSource corsSource;

    private static Map<String, Object> settings(Object verification, Object before, Object after, Object stat, Object unack,
                                                Object unackStat, Object undo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medVerificationRequired", verification);
        m.put("doseWindowBeforeMinutes", before);
        m.put("doseWindowAfterMinutes", after);
        m.put("statWindowMinutes", stat);
        m.put("unackFlagMinutes", unack);
        m.put("unackFlagStatMinutes", unackStat);
        m.put("undoSeconds", undo);
        return m;
    }

    private static Map<String, Object> defaults() {
        return settings(true, 60, 60, 15, 30, 5, 10);
    }

    private static Map<String, Object> profile(String name, Object verification, Object vitals, Object before, Object after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("medVerificationRequired", verification);
        m.put("vitalsEveryMinutes", vitals);
        m.put("doseWindowBeforeMinutes", before);
        m.put("doseWindowAfterMinutes", after);
        return m;
    }

    private JsonNode settingsOf(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, SETTINGS, clinic.owner(), null, 200);
    }

    private static JsonNode profileOf(JsonNode answer, String key) {
        for (JsonNode p : answer.get("profiles")) {
            if (key.equals(p.get("key").asText())) {
                return p;
            }
        }
        return null;
    }

    private static JsonNode wardOf(JsonNode answer, String name) {
        for (JsonNode w : answer.get("wards")) {
            if (name.equals(w.get("wardName").asText())) {
                return w;
            }
        }
        return null;
    }

    private JsonNode fieldProblem(org.springframework.test.web.servlet.ResultActions actions, String field, String message) throws Exception {
        JsonNode p = problem(actions, 400);
        assertType(p, "validation");
        assertThat(fieldNames(p)).as(p.toString()).contains(field);
        assertThat(fieldMessage(p, field)).isEqualTo(message);
        return p;
    }

    // ---------------- reading ----------------

    @Test
    void aClinicThatNeverSavedSeesTheDefaultsTheTwoStarterProfilesAndItsWardsAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        long audits = allAudits(clinic);
        long profiles = rows(clinic, "nursing_profile");
        JsonNode a = settingsOf(clinic);
        assertThat(a.get("canEdit").asBoolean()).isTrue();
        assertThat(a.get("settings").get("medVerificationRequired").asBoolean()).isTrue();
        assertThat(a.get("settings").get("doseWindowBeforeMinutes").asInt()).isEqualTo(60);
        assertThat(a.get("settings").get("doseWindowAfterMinutes").asInt()).isEqualTo(60);
        assertThat(a.get("settings").get("statWindowMinutes").asInt()).isEqualTo(15);
        assertThat(a.get("settings").get("unackFlagMinutes").asInt()).isEqualTo(30);
        assertThat(a.get("settings").get("unackFlagStatMinutes").asInt()).isEqualTo(5);
        assertThat(a.get("settings").get("undoSeconds").asInt()).isEqualTo(10);

        assertThat(a.get("profiles")).hasSize(2);
        JsonNode icu = profileOf(a, "icu");
        assertThat(icu.get("name").asText()).isEqualTo("ICU");
        assertThat(icu.get("starter").asBoolean()).isTrue();
        assertThat(icu.get("medVerificationRequired").asBoolean()).isTrue();
        assertThat(icu.get("vitalsEveryMinutes").asInt()).isEqualTo(60);
        assertThat(icu.get("doseWindowBeforeMinutes").isNull()).isTrue();
        assertThat(icu.get("wardCount").asInt()).isZero();
        JsonNode general = profileOf(a, "general-ward");
        assertThat(general.get("name").asText()).isEqualTo("General ward");
        assertThat(general.get("vitalsEveryMinutes").asInt()).isEqualTo(240);

        assertThat(wardOf(a, "General ward")).isNotNull();
        assertThat(wardOf(a, "General ward").get("profileKey").isNull()).isTrue();
        assertThat(a.get("highAlertMedicines")).hasSize(3);
        assertThat(a.get("highAlertMedicines").toString()).contains("Inj. Heparin 5,000 IU").contains("Anticoagulant")
                .contains("Insulin").contains("Concentrated electrolyte");
        assertThat(a.get("timeCriticalMedicines")).as("none are set as shipped").isEmpty();

        assertThat(rows(clinic, "nursing_profile")).as("reading never copies the starters").isEqualTo(profiles);
        assertThat(allAudits(clinic)).isEqualTo(audits);
    }

    @Test
    void ownerDoctorNurseAndPharmacistMayReadButOnlyTheOwnerMayEditAndOtherRolesAreRefused() throws Exception {
        Clinic clinic = hospital();
        assertThat(settingsOf(clinic).get("canEdit").asBoolean()).isTrue();
        for (String role : new String[] {"DOCTOR", "NURSE", "PHARMACIST"}) {
            JsonNode a = ok(HttpMethod.GET, SETTINGS, staff(clinic, role).auth(), null, 200);
            assertThat(a.get("canEdit").asBoolean()).as(role).isFalse();
            assertThat(a.get("profiles")).hasSize(2);
        }
        for (String role : SETTINGS_NON_READERS) {
            JsonNode p = problem(call(HttpMethod.GET, SETTINGS, staff(clinic, role).auth(), null), 403);
            assertType(p, "forbidden");
        }
        problem(call(HttpMethod.GET, SETTINGS, request -> request, null), 401);
        setIpdState(clinic, "readonly");
        assertThat(settingsOf(clinic).get("canEdit").asBoolean()).as("Read-only reads but cannot edit").isFalse();
        setIpdState(clinic, "off");
        assertType(problem(call(HttpMethod.GET, SETTINGS, clinic.owner(), null), 403), "module-off");
        setIpdState(clinic, "on");
        assertThat(settingsOf(clinic).get("canEdit").asBoolean()).isTrue();
    }

    // ---------------- saving the clinic settings ----------------

    @Test
    void theOwnerSavesTheSettingsAndTheSameValuesAgainWriteNothing() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> values = settings(false, 30, 45, 20, 90, 10, 5);
        callWith(HttpMethod.PUT, SETTINGS, clinic.owner(), js(values), "qa76-device-0007", "Mozilla/5.0 Firefox/130.0")
                .andExpect(status().isOk());
        JsonNode saved = settingsOf(clinic);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            assertThat(saved.get("settings").get(e.getKey()).asText()).as(e.getKey()).isEqualTo(String.valueOf(e.getValue()));
        }
        List<String> db = values(clinic, "select med_verification_required::text, dose_window_before_min::text, dose_window_after_min::text, "
                + "stat_window_min::text, unack_flag_min::text, unack_flag_stat_min::text, undo_seconds::text from ipd.config");
        assertThat(db).containsExactly("false", "30", "45", "20", "90", "10", "5");

        List<String[]> audits = audit(clinic, "IPD_NURSING_SETTINGS_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[0]).isEqualTo("IPD_CONFIG");
        assertThat(audits.get(0)[2]).as("before values").isNotNull().contains("60");
        assertThat(audits.get(0)[3]).as("after values").contains("45").contains("false");
        JsonNode after = objectMapper.readTree(audits.get(0)[3]);
        assertThat(after.get("device").get("id").asText()).isEqualTo("qa76-device-0007");
        assertThat(after.get("device").get("agent").asText()).isEqualTo("Firefox");

        long all = allAudits(clinic);
        ok(HttpMethod.PUT, SETTINGS, clinic.owner(), js(values), 200);
        assertThat(allAudits(clinic)).as("the same values: 200 and nothing written").isEqualTo(all);
        ok(HttpMethod.PUT, SETTINGS, clinic.owner(), js(settings(false, 30, 45, 20, 90, 10, 4)), 200);
        assertThat(audit(clinic, "IPD_NURSING_SETTINGS_CHANGED")).hasSize(2);
        assertThat(rows(clinic, "config")).as("one row per clinic").isOne();
    }

    @Test
    void everyNumberHasItsRangeAndAnOutOfRangeValueIsRefusedWithTheContractTextAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Object[][] fields = {{"doseWindowBeforeMinutes", 1, 180}, {"doseWindowAfterMinutes", 1, 180}, {"statWindowMinutes", 1, 120},
                {"unackFlagMinutes", 1, 240}, {"unackFlagStatMinutes", 1, 60}, {"undoSeconds", 1, 10}};
        long audits = allAudits(clinic);
        for (Object[] f : fields) {
            String name = (String) f[0];
            int min = (Integer) f[1];
            int max = (Integer) f[2];
            String text = "Enter a number from " + min + " to " + max + ".";
            for (Object bad : new Object[] {min - 1, max + 1, "30", 1.5, null, true}) {
                Map<String, Object> body = defaults();
                body.put(name, bad);
                fieldProblem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), js(body)), name, text);
            }
        }
        for (Object bad : new Object[] {null, "true", "yes", 1, 0}) {
            Map<String, Object> body = defaults();
            body.put("medVerificationRequired", bad);
            fieldProblem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), js(body)), "medVerificationRequired", ON_OFF);
        }
        Map<String, Object> missing = defaults();
        missing.remove("medVerificationRequired");
        fieldProblem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), js(missing)), "medVerificationRequired", ON_OFF);
        JsonNode all = fieldProblem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), "{}"), "undoSeconds", "Enter a number from 1 to 10.");
        assertThat(fieldNames(all)).as("every error together").hasSize(7);
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(settingsOf(clinic).get("settings").get("undoSeconds").asInt()).isEqualTo(10);
        // the edges are accepted
        ok(HttpMethod.PUT, SETTINGS, clinic.owner(), js(settings(true, 1, 180, 120, 240, 60, 1)), 200);
        ok(HttpMethod.PUT, SETTINGS, clinic.owner(), js(settings(true, 180, 1, 1, 1, 1, 10)), 200);
    }

    @Test
    void onlyTheOwnerMayChangeTheSettingsAndReadOnlyAndOffRefuseTheWrite() throws Exception {
        Clinic clinic = hospital();
        long audits = allAudits(clinic);
        for (String role : SETTINGS_NON_WRITERS) {
            JsonNode p = problem(call(HttpMethod.PUT, SETTINGS, staff(clinic, role).auth(), js(settings(false, 30, 30, 15, 30, 5, 10))),
                    403);
            assertType(p, "forbidden");
            if (role.equals("DOCTOR") || role.equals("NURSE") || role.equals("PHARMACIST")) {
                assertThat(p.get("detail").asText()).isEqualTo(ONLY_OWNER_SETTINGS);
            }
        }
        assertThat(settingsOf(clinic).get("settings").get("medVerificationRequired").asBoolean()).isTrue();
        setIpdState(clinic, "readonly");
        JsonNode ro = problem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), js(defaults())), 409);
        assertType(ro, "ipd-readonly");
        assertThat(ro.get("detail").asText()).isEqualTo(READONLY_TEXT);
        setIpdState(clinic, "off");
        assertType(problem(call(HttpMethod.PUT, SETTINGS, clinic.owner(), js(defaults())), 403), "module-off");
        setIpdState(clinic, "on");
        assertThat(allAudits(clinic)).isEqualTo(audits);
    }

    @Test
    void theSettingsOfOneClinicNeverChangeAnother() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        ok(HttpMethod.PUT, SETTINGS, a.owner(), js(settings(false, 10, 10, 10, 10, 10, 3)), 200);
        JsonNode other = settingsOf(b);
        assertThat(other.get("settings").get("doseWindowBeforeMinutes").asInt()).isEqualTo(60);
        assertThat(other.get("settings").get("undoSeconds").asInt()).isEqualTo(10);
        assertThat(rows(b, "config")).isLessThanOrEqualTo(1);
        assertThat(count(b, "select count(*) from ipd.config where undo_seconds = 3")).isZero();
        ok(HttpMethod.POST, PROFILES, a.owner(), js(profile("Only in A", true, 30, null, null)), 201);
        assertThat(profileOf(settingsOf(b), "only-in-a")).isNull();
        assertThat(rows(b, "nursing_profile")).isZero();
        // a clinic B owner cannot name clinic A's profile or ward
        String wardA = wardIdByName(a, "General ward");
        problem(call(HttpMethod.PUT, wardProfileUrl(wardA), b.owner(), "{\"profileKey\":null}"), 404);
        String wardB = wardIdByName(b, "General ward");
        fieldProblem(call(HttpMethod.PUT, wardProfileUrl(wardB), b.owner(), "{\"profileKey\":\"only-in-a\"}"), "profileKey", CHOOSE_PROFILE);
    }

    // ---------------- profiles ----------------

    @Test
    void theFirstProfileWriteCopiesTheStartersAndTheKeyIsMadeFromTheName() throws Exception {
        Clinic clinic = hospital();
        assertThat(rows(clinic, "nursing_profile")).isZero();
        callWith(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Night ICU", true, 30, 20, null)), "qa76-device-0009", null)
                .andExpect(status().isCreated());
        JsonNode a = settingsOf(clinic);
        assertThat(a.get("profiles")).hasSize(3);
        for (JsonNode p : a.get("profiles")) {
            assertThat(p.get("starter").asBoolean()).as(p.get("key").asText()).isFalse();
        }
        assertThat(rows(clinic, "nursing_profile")).isEqualTo(3);
        JsonNode night = profileOf(a, "night-icu");
        assertThat(night).isNotNull();
        assertThat(night.get("name").asText()).isEqualTo("Night ICU");
        assertThat(night.get("vitalsEveryMinutes").asInt()).isEqualTo(30);
        assertThat(night.get("doseWindowBeforeMinutes").asInt()).isEqualTo(20);
        assertThat(night.get("doseWindowAfterMinutes").isNull()).isTrue();
        assertThat(profileOf(a, "icu").get("vitalsEveryMinutes").asInt()).isEqualTo(60);
        assertThat(profileOf(a, "general-ward").get("vitalsEveryMinutes").asInt()).isEqualTo(240);

        // a name that makes the same key gets a number
        JsonNode b = ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Night ICU!", true, 30, null, null)), 201);
        assertThat(profileOf(b, "night-icu-2")).isNotNull();
        JsonNode c = ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Night ICU?", true, 30, null, null)), 201);
        assertThat(profileOf(c, "night-icu-3")).isNotNull();
        JsonNode accents = ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Soin intensif été", true, 60, null, null)), 201);
        assertThat(profileOf(accents, "soin-intensif-ete")).isNotNull();

        List<String[]> audits = audit(clinic, "IPD_NURSING_PROFILE_SAVED");
        assertThat(audits).hasSize(4);
        JsonNode after = objectMapper.readTree(audits.get(0)[3]);
        assertThat(after.toString()).contains("night-icu");
        assertThat(after.get("device").get("id").asText()).isEqualTo("qa76-device-0009");
        assertThat(allAuditTexts(clinic, "IPD\\_NURSING\\_%").toString()).as("the profile name is in the audit on purpose (code review 8)").contains("Soin intensif");
    }

    @Test
    void aProfileNameIsUniqueIgnoringCaseAndEveryFieldHasItsContractText() throws Exception {
        Clinic clinic = hospital();
        ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Night ICU", true, 30, null, null)), 201);
        for (String name : new String[] {"night icu", "NIGHT ICU", "icu", "General WARD"}) {
            JsonNode p = problem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile(name, true, 60, null, null))), 409);
            assertType(p, "profile-name-taken");
            assertThat(p.get("detail").asText()).isEqualTo(NAME_TAKEN);
        }
        assertThat(rows(clinic, "nursing_profile")).isEqualTo(3);
        for (Object bad : new Object[] {null, "", "   ", "x".repeat(61), "bad\u0007name", "‮evil"}) {
            fieldProblem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile((String) bad, true, 60, null, null))), "name",
                    ENTER_NAME);
        }
        ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("x".repeat(60), true, 60, null, null)), 201);
        for (Object bad : new Object[] {14, 1441, null, "60", 60.5}) {
            fieldProblem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Edge", true, bad, null, null))), "vitalsEveryMinutes",
                    "Enter a number from 15 to 1440.");
        }
        for (Object bad : new Object[] {null, "true", 1}) {
            fieldProblem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Edge", bad, 60, null, null))),
                    "medVerificationRequired", ON_OFF);
        }
        for (Object bad : new Object[] {0, 181, "30"}) {
            fieldProblem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Edge", true, 60, bad, null))),
                    "doseWindowBeforeMinutes", "Enter a number from 1 to 180.");
            fieldProblem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Edge", true, 60, null, bad))),
                    "doseWindowAfterMinutes", "Enter a number from 1 to 180.");
        }
        ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Lowest", true, 15, 1, 1)), 201);
        ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Highest", false, 1440, 180, 180)), 201);
        assertThat(profileOf(settingsOf(clinic), "highest").get("medVerificationRequired").asBoolean()).isFalse();
    }

    @Test
    void aClinicCanHaveTwentyProfilesAndTheTwentyFirstIsRefused() throws Exception {
        Clinic clinic = hospital();
        for (int i = 1; i <= 18; i++) {
            ok(HttpMethod.POST, PROFILES, clinic.owner(), js(profile(String.format("Profile %02d", i), true, 60, null, null)), 201);
        }
        assertThat(rows(clinic, "nursing_profile")).as("two starters and 18 new").isEqualTo(20);
        JsonNode p = problem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("One too many", true, 60, null, null))), 409);
        assertType(p, "profile-limit");
        assertThat(p.get("detail").asText()).isEqualTo(PROFILE_LIMIT);
        assertThat(rows(clinic, "nursing_profile")).isEqualTo(20);
        assertThat(settingsOf(clinic).get("profiles")).hasSize(20);
        // another clinic has its own twenty
        Clinic other = hospital();
        ok(HttpMethod.POST, PROFILES, other.owner(), js(profile("Profile 01", true, 60, null, null)), 201);
        // a change of an existing profile still works at the cap
        ok(HttpMethod.PUT, profileUrl("profile-01"), clinic.owner(), js(profile("Profile 01", false, 90, null, null)), 200);
    }

    @Test
    void changingAProfileWritesOnlyWhenAValueChangedAndStartersAreCopiedFirst() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> change = profile("ICU", false, 120, 30, 45);
        callWith(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(change), "qa76-device-0010", null).andExpect(status().isOk());
        JsonNode a = settingsOf(clinic);
        assertThat(rows(clinic, "nursing_profile")).as("starters copied on the first write").isEqualTo(2);
        JsonNode icu = profileOf(a, "icu");
        assertThat(icu.get("starter").asBoolean()).isFalse();
        assertThat(icu.get("medVerificationRequired").asBoolean()).isFalse();
        assertThat(icu.get("vitalsEveryMinutes").asInt()).isEqualTo(120);
        assertThat(icu.get("doseWindowBeforeMinutes").asInt()).isEqualTo(30);
        assertThat(icu.get("doseWindowAfterMinutes").asInt()).isEqualTo(45);
        assertThat(profileOf(a, "general-ward").get("vitalsEveryMinutes").asInt()).isEqualTo(240);
        List<String[]> audits = audit(clinic, "IPD_NURSING_PROFILE_SAVED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[2]).as("before values").isNotNull();
        assertThat(objectMapper.readTree(audits.get(0)[3]).get("device").get("id").asText()).isEqualTo("qa76-device-0010");

        long all = allAudits(clinic);
        ok(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(change), 200);
        assertThat(allAudits(clinic)).as("the same values: nothing written").isEqualTo(all);
        // the name may change case but not take another profile's name
        ok(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(profile("icu", false, 120, 30, 45)), 200);
        assertType(problem(call(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(profile("general WARD", false, 120, 30, 45))), 409),
                "profile-name-taken");
        problem(call(HttpMethod.PUT, profileUrl("no-such-key"), clinic.owner(), js(change)), 404);
        fieldProblem(call(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(profile("ICU", true, 5, null, null))),
                "vitalsEveryMinutes", "Enter a number from 15 to 1440.");
        for (String role : SETTINGS_NON_WRITERS) {
            assertType(problem(call(HttpMethod.PUT, profileUrl("icu"), staff(clinic, role).auth(), js(change)), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, PROFILES, staff(clinic, role).auth(), js(profile("New one", true, 60, null, null))),
                    403), "forbidden");
        }
        setIpdState(clinic, "readonly");
        assertType(problem(call(HttpMethod.PUT, profileUrl("icu"), clinic.owner(), js(profile("ICU", true, 60, null, null))), 409),
                "ipd-readonly");
        assertType(problem(call(HttpMethod.POST, PROFILES, clinic.owner(), js(profile("Read only one", true, 60, null, null))), 409),
                "ipd-readonly");
        setIpdState(clinic, "on");
        assertThat(rows(clinic, "nursing_profile")).isEqualTo(2);
    }

    // ---------------- the profile of a ward ----------------

    @Test
    void theOwnerChoosesTheProfileOfAWardAndNullGivesBackTheClinicDefaults() throws Exception {
        Clinic clinic = hospital();
        String ward = wardIdByName(clinic, "General ward");
        callWith(HttpMethod.PUT, wardProfileUrl(ward), clinic.owner(), "{\"profileKey\":\"icu\"}", "qa76-device-0011", null)
                .andExpect(status().isOk());
        JsonNode a = settingsOf(clinic);
        assertThat(rows(clinic, "nursing_profile")).as("starters copied first").isEqualTo(2);
        assertThat(wardOf(a, "General ward").get("profileKey").asText()).isEqualTo("icu");
        assertThat(profileOf(a, "icu").get("wardCount").asInt()).isOne();
        assertThat(count(clinic, "select count(*) from ipd.ward where nursing_profile_key = 'icu'")).isOne();
        List<String[]> audits = audit(clinic, "IPD_WARD_PROFILE_CHANGED");
        assertThat(audits).hasSize(1);
        JsonNode after = objectMapper.readTree(audits.get(0)[3]);
        assertThat(after.toString()).contains(ward).contains("icu");
        assertThat(after.get("device").get("id").asText()).isEqualTo("qa76-device-0011");

        long all = allAudits(clinic);
        ok(HttpMethod.PUT, wardProfileUrl(ward), clinic.owner(), "{\"profileKey\":\"icu\"}", 200);
        assertThat(allAudits(clinic)).as("the same profile: nothing written").isEqualTo(all);
        JsonNode back = ok(HttpMethod.PUT, wardProfileUrl(ward), clinic.owner(), "{\"profileKey\":null}", 200);
        assertThat(wardOf(back, "General ward").get("profileKey").isNull()).isTrue();
        assertThat(profileOf(back, "icu").get("wardCount").asInt()).isZero();
        assertThat(audit(clinic, "IPD_WARD_PROFILE_CHANGED")).hasSize(2);
    }

    @Test
    void aWardProfileOfTheListIsRequiredTheWardMustExistAndOnlyTheOwnerMayChooseIt() throws Exception {
        Clinic clinic = hospital();
        String ward = wardIdByName(clinic, "General ward");
        long audits = allAudits(clinic);
        for (String bad : new String[] {"\"no-such-profile\"", "\"BAD KEY\"", "\"\"", "\"x\""}) {
            fieldProblem(call(HttpMethod.PUT, wardProfileUrl(ward), clinic.owner(), "{\"profileKey\":" + bad + "}"), "profileKey",
                    CHOOSE_PROFILE);
        }
        problem(call(HttpMethod.PUT, wardProfileUrl(UUID.randomUUID()), clinic.owner(), "{\"profileKey\":null}"), 404);
        assertType(problem(call(HttpMethod.PUT, IPD + "/wards/not-a-uuid/nursing-profile", clinic.owner(), "{\"profileKey\":null}"), 400),
                "bad-request");
        for (String role : SETTINGS_NON_WRITERS) {
            assertType(problem(call(HttpMethod.PUT, wardProfileUrl(ward), staff(clinic, role).auth(), "{\"profileKey\":\"icu\"}"), 403),
                    "forbidden");
        }
        setIpdState(clinic, "readonly");
        assertType(problem(call(HttpMethod.PUT, wardProfileUrl(ward), clinic.owner(), "{\"profileKey\":\"icu\"}"), 409), "ipd-readonly");
        setIpdState(clinic, "on");
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(count(clinic, "select count(*) from ipd.ward where nursing_profile_key is not null")).isZero();
    }

    // ---------------- CORS ----------------

    @Test
    void theBrowserMaySendTheDeviceHeaderAndNoOtherHeaderIsAddedToTheList() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/ipd/medicine-orders/" + UUID.randomUUID() + "/doses");
        CorsConfiguration config = corsSource.getCorsConfiguration(preflight);
        assertThat(config).isNotNull();
        assertThat(config.getAllowedHeaders()).contains("X-Sahaj-Device", "Authorization", "Content-Type", "Accept");
        assertThat(config.checkHeaders(List.of("X-Sahaj-Device", "Authorization", "Content-Type"))).isNotNull();
        assertThat(config.checkHeaders(List.of("X-Other-Header"))).as("a header that is not on the list is refused").isNull();
        assertThat(config.checkHttpMethod(org.springframework.http.HttpMethod.PUT)).as("PUT is needed for the settings").isNotNull();
        assertThat(config.checkHttpMethod(org.springframework.http.HttpMethod.POST)).isNotNull();
        MockHttpServletRequest settingsPreflight = new MockHttpServletRequest("OPTIONS", "/api/v1/ipd/nursing-settings");
        assertThat(corsSource.getCorsConfiguration(settingsPreflight).getAllowedHeaders()).contains("X-Sahaj-Device");
        // in the test profile no origin is configured, so a real browser origin is refused (no wildcard)
        assertThat(config.checkOrigin("http://evil.example")).isNull();
        assertThat(config.getAllowedOrigins() == null || !config.getAllowedOrigins().contains("*")).isTrue();
        List<String> sent = new ArrayList<>();
        sent.add("X-Sahaj-Device");
        assertThat(config.checkHeaders(sent)).containsExactly("X-Sahaj-Device");
    }
}
