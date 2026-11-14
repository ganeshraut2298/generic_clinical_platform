package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.TestTenantSecurity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Sweep: SAH-4 setup wizard and re-login, SAH-5 app shell (/me, menu, preferences) on the real database. */
@OnTestDatabase
class SetupAndShellIT extends SweepSupport {

    private static final String SETUP = "/api/v1/platform/setup";
    private static final String ME = "/api/v1/platform/me";

    private static final String STEP1 = """
            {"name":"Sweep clinic","address":"12 MG Road","city":"Pune","pin":"411001","phone":"9876543210","registrationNo":"REG-9"}""";

    private static String slug(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private Session freshOwner() throws Exception {
        return signUp(uniqueMobile());
    }

    private static RequestPostProcessor ownerOf(Session s) {
        return TestTenantSecurity.asUser(s.tenantId(), s.userId(), "OWNER");
    }

    @Test
    void freshSignUpStartsAtStepOneWithPlanLimits() throws Exception {
        Session s = freshOwner();
        JsonNode state = ok(HttpMethod.GET, SETUP, ownerOf(s), null, 200);
        assertThat(state.get("currentStep").asInt()).isEqualTo(1);
        assertThat(state.get("limits").get("doctors").asInt()).isPositive();
        assertThat(state.get("limits").get("users").asInt()).isPositive();
    }

    @Test
    void everyStepSavesAndAdvancesTheCurrentStep() throws Exception {
        Session s = freshOwner();
        RequestPostProcessor o = ownerOf(s);
        assertThat(ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200).get("currentStep").asInt()).isEqualTo(2);
        assertThat(ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"dental\"}", 200).get("currentStep").asInt()).isEqualTo(3);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"Dr A\",\"specialty\":\"Dentist\",\"feeRupees\":0,\"mobile\":\"\"}],\"staff\":[]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"split\",\"queue\":false,\"vitals\":false,\"followUpDays\":30,\"followUpFee\":\"half\"}", 200);
        JsonNode last = ok(HttpMethod.PUT, SETUP + "/steps/5", o,
                "{\"brand\":\"teal\",\"slug\":\"" + slug("sw-") + "\",\"language\":\"hi\"}", 200);
        assertThat(last.get("answers").size()).isGreaterThanOrEqualTo(5);
        JsonNode again = ok(HttpMethod.GET, SETUP, o, null, 200);
        assertThat(again.get("answers").size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void resavingAStepOverwritesTheEarlierAnswers() throws Exception {
        Session s = freshOwner();
        RequestPostProcessor o = ownerOf(s);
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        JsonNode second = ok(HttpMethod.PUT, SETUP + "/steps/1", o,
                STEP1.replace("Sweep clinic", "Renamed clinic"), 200);
        assertThat(second.toString()).contains("Renamed clinic").doesNotContain("Sweep clinic");
    }

    @Test
    void completeWithoutAllStepsIsAProblem() throws Exception {
        Session s = freshOwner();
        RequestPostProcessor o = ownerOf(s);
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        JsonNode p = problem(call(HttpMethod.POST, SETUP + "/complete", o, null), 400);
        assertThat(p.get("type").asText()).endsWith("setup-incomplete");
    }

    @Test
    void completeWithNoProgressAtAllIsAProblem() throws Exception {
        Session s = freshOwner();
        JsonNode p = problem(call(HttpMethod.POST, SETUP + "/complete", ownerOf(s), null), 400);
        assertThat(p.get("type").asText()).endsWith("setup-incomplete");
    }

    @Test
    void completeCreatesTheClinicTeamAndIsRepeatable() throws Exception {
        Clinic clinic = newClinic();
        JsonNode again = ok(HttpMethod.POST, SETUP + "/complete", clinic.owner(), null, 200);
        assertThat(again.get("next").asText()).isEqualTo("APP");
        assertThat(again.get("brand").asText()).isEqualTo("blue");
        JsonNode team = ok(HttpMethod.GET, "/api/v1/platform/team", clinic.owner(), null, 200);
        assertThat(team.findValuesAsText("name")).contains("Dr Asha Rao");
        JsonNode profile = ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", clinic.owner(), null, 200);
        assertThat(profile.get("name").asText()).isEqualTo("Sahaj test clinic");
        assertThat(profile.get("specialtyCode").asText()).isEqualTo("gp");
    }

    @Test
    void invalidStepBodiesAreRejectedWithFieldErrors() throws Exception {
        Session s = freshOwner();
        RequestPostProcessor o = ownerOf(s);
        problem(call(HttpMethod.PUT, SETUP + "/steps/1", o, "{\"city\":\"Pune\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/1", o, "{\"name\":\"X\",\"city\":\"Pune\",\"pin\":\"12\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"cardio\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"D\",\"specialty\":\"x\",\"feeRupees\":-5}],\"staff\":[]}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"D\",\"specialty\":\"x\",\"feeRupees\":5,\"mobile\":\"12345\"}],\"staff\":[]}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":true,\"followUpDays\":31,\"followUpFee\":\"free\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"never\",\"queue\":true,\"vitals\":true,\"followUpDays\":5,\"followUpFee\":\"free\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/5", o, "{\"brand\":\"blue\",\"slug\":\"AB\",\"language\":\"en\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/5", o, "{\"brand\":\"pink\",\"slug\":\"good-slug\",\"language\":\"en\"}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/5", o, "{\"brand\":\"blue\",\"slug\":\"good-slug\",\"language\":\"fr\"}"), 400);
    }

    @Test
    void stepNumberOutsideOneToFiveIsBadRequest() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        problem(call(HttpMethod.PUT, SETUP + "/steps/0", o, "{}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/6", o, "{}"), 400);
        problem(call(HttpMethod.PUT, SETUP + "/steps/abc", o, "{}"), 400);
    }

    @Test
    void malformedJsonIsBadRequestNotServerError() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        problem(call(HttpMethod.PUT, SETUP + "/steps/1", o, "{not json"), 400);
    }

    @Test
    void tooManyPeopleInTheTeamStepIsAPlanLimit() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        StringBuilder staff = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            staff.append(i == 0 ? "" : ",").append("{\"name\":\"S").append(i)
                    .append("\",\"role\":\"NURSE\",\"mobile\":\"").append(uniqueMobile()).append("\"}");
        }
        JsonNode p = problem(call(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[],\"staff\":[" + staff + "]}"), 409);
        assertThat(p.get("type").asText()).endsWith("plan-limit");
    }

    @Test
    void slugAvailabilityReportsFreeTakenReservedAndInvalid() throws Exception {
        Clinic clinic = newClinic();
        String taken = ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", clinic.owner(), null, 200).get("slug").asText();
        RequestPostProcessor o = ownerOf(freshOwner());
        JsonNode free = ok(HttpMethod.GET, SETUP + "/slug-availability?slug=" + slug("free-"), o, null, 200);
        assertThat(free.get("available").asBoolean()).isTrue();
        JsonNode t = ok(HttpMethod.GET, SETUP + "/slug-availability?slug=" + taken, o, null, 200);
        assertThat(t.get("available").asBoolean()).isFalse();
        assertThat(t.get("alternatives").size()).isPositive();
        JsonNode reserved = ok(HttpMethod.GET, SETUP + "/slug-availability?slug=admin", o, null, 200);
        assertThat(reserved.get("available").asBoolean()).isFalse();
        problem(call(HttpMethod.GET, SETUP + "/slug-availability?slug=A", o, null), 400);
        problem(call(HttpMethod.GET, SETUP + "/slug-availability", o, null), 400);
    }

    @Test
    void takingAnotherClinicsSlugAtStepFiveIsRefusedAtCompletion() throws Exception {
        Clinic first = newClinic();
        String taken = ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", first.owner(), null, 200).get("slug").asText();
        RequestPostProcessor o = ownerOf(freshOwner());
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"gp\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o, "{\"doctors\":[],\"staff\":[]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":true,\"followUpDays\":7,\"followUpFee\":\"free\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/5", o, "{\"brand\":\"blue\",\"slug\":\"" + taken + "\",\"language\":\"en\"}", 200);
        JsonNode p = problem(call(HttpMethod.POST, SETUP + "/complete", o, null), 409);
        assertThat(p.get("type").asText()).endsWith("slug-taken");
    }

    @Test
    void noTeamAtAllStillCompletes() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"paeds\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o, "{\"doctors\":[],\"staff\":[]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"after\",\"queue\":true,\"vitals\":true,\"followUpDays\":7,\"followUpFee\":\"full\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/5", o,
                "{\"brand\":\"maroon\",\"slug\":\"" + slug("nt-") + "\",\"language\":\"mr\"}", 200);
        ok(HttpMethod.POST, SETUP + "/complete", o, null, 200);
    }

    @Test
    void wizardWithStaffAndSecondDoctorCreatesRealMemberships() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"homeo\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"Dr One\",\"specialty\":\"Homeopath\",\"feeRupees\":300,\"mobile\":\"\"},"
                        + "{\"name\":\"Dr Two\",\"specialty\":\"Homeopath\",\"feeRupees\":400,\"mobile\":\"" + uniqueMobile() + "\"}],"
                        + "\"staff\":[{\"name\":\"Nina Nurse\",\"role\":\"NURSE\",\"mobile\":\"" + uniqueMobile() + "\"},"
                        + "{\"name\":\"Fred Desk\",\"role\":\"FRONT_DESK\",\"mobile\":\"" + uniqueMobile() + "\"}]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":true,\"followUpDays\":7,\"followUpFee\":\"free\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/5", o,
                "{\"brand\":\"green\",\"slug\":\"" + slug("ws-") + "\",\"language\":\"en\"}", 200);
        ok(HttpMethod.POST, SETUP + "/complete", o, null, 200);
        JsonNode team = ok(HttpMethod.GET, "/api/v1/platform/team", o, null, 200);
        assertThat(team.findValuesAsText("name")).contains("Dr Two", "Nina Nurse", "Fred Desk");
        assertThat(team.findValuesAsText("role")).contains("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");
    }

    /** Step 3 names the first doctor (the owner, SAH-4 AC9); that name must appear on the owner's team entry. */
    @Test
    void wizardFirstDoctorNameIsKeptOnTheOwnersTeamEntry() throws Exception {
        RequestPostProcessor o = ownerOf(freshOwner());
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"gp\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"Dr First\",\"specialty\":\"GP\",\"feeRupees\":200,\"mobile\":\"\"}],\"staff\":[]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":true,\"followUpDays\":7,\"followUpFee\":\"free\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/5", o,
                "{\"brand\":\"blue\",\"slug\":\"" + slug("dn-") + "\",\"language\":\"en\"}", 200);
        ok(HttpMethod.POST, SETUP + "/complete", o, null, 200);
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/team", o, null, 200).findValuesAsText("name")).contains("Dr First");
    }

    /** Finishing setup seeds the chosen specialty's starter services (SAH-4 AC1, AC8). */
    @Test
    void finishingSetupSeedsTheStarterPriceListOfTheChosenSpecialty() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.GET, "/api/v1/opd/services", clinic.owner(), null, 200).size()).isPositive();
    }

    @Test
    void everySetupRouteRefusesNonOwners() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            RequestPostProcessor r = clinic.as(role);
            problem(call(HttpMethod.GET, SETUP, r, null), 403);
            problem(call(HttpMethod.PUT, SETUP + "/steps/1", r, STEP1), 403);
            problem(call(HttpMethod.GET, SETUP + "/slug-availability?slug=abc-def", r, null), 403);
            problem(call(HttpMethod.POST, SETUP + "/complete", r, null), 403);
        }
    }

    @Test
    void unauthenticatedSetupCallIs401() throws Exception {
        mvc.perform(get(SETUP)).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerLogsInAgainAfterSetupAndLandsOnAppWithAWorkingToken() throws Exception {
        String mobile = uniqueMobile();
        Session signUp = signUp(mobile);
        RequestPostProcessor o = ownerOf(signUp);
        ok(HttpMethod.PUT, SETUP + "/steps/1", o, STEP1, 200);
        ok(HttpMethod.PUT, SETUP + "/steps/2", o, "{\"code\":\"gp\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/3", o,
                "{\"doctors\":[{\"name\":\"Dr Owner\",\"specialty\":\"GP\",\"feeRupees\":200,\"mobile\":\"\"}],\"staff\":[]}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/4", o,
                "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":true,\"followUpDays\":7,\"followUpFee\":\"free\"}", 200);
        ok(HttpMethod.PUT, SETUP + "/steps/5", o,
                "{\"brand\":\"blue\",\"slug\":\"" + slug("rl-") + "\",\"language\":\"en\"}", 200);
        ok(HttpMethod.POST, SETUP + "/complete", o, null, 200);

        ageOutLatestChallenge(mobile);
        Session again = login(mobile);
        assertThat(again.next()).isEqualTo("APP");
        assertThat(again.accessToken()).isNotBlank();
        JsonNode me = ok(HttpMethod.GET, ME, bearer(again.accessToken()), null, 200);
        assertThat(me.get("role").asText()).isEqualTo("OWNER");
        assertThat(me.get("clinic").get("setupComplete").asBoolean()).isTrue();
        assertThat(me.get("clinic").get("name").asText()).isEqualTo("Sweep clinic");
        ok(HttpMethod.GET, "/api/v1/platform/setup/overview", bearer(again.accessToken()), null, 200);
    }

    @Test
    void midSetupOwnerLoginStillRoutesToSetupAndResumesAtTheSavedStep() throws Exception {
        String mobile = uniqueMobile();
        Session signUp = signUp(mobile);
        ok(HttpMethod.PUT, SETUP + "/steps/1", ownerOf(signUp), STEP1, 200);
        ageOutLatestChallenge(mobile);
        Session again = login(mobile);
        assertThat(again.next()).isEqualTo("SETUP");
        JsonNode state = ok(HttpMethod.GET, SETUP, bearer(again.accessToken()), null, 200);
        assertThat(state.get("currentStep").asInt()).isEqualTo(2);
        assertThat(state.toString()).contains("Sweep clinic");
    }

    // ---- SAH-5 shell ----

    @Test
    void meReturnsUserClinicMenuAndDefaultPreferences() throws Exception {
        Clinic clinic = newClinic();
        JsonNode me = ok(HttpMethod.GET, ME, clinic.owner(), null, 200);
        assertThat(me.get("userId").asText()).isEqualTo(clinic.ownerUserId().toString());
        assertThat(me.get("membershipId").asText()).isNotBlank();
        assertThat(me.get("role").asText()).isEqualTo("OWNER");
        assertThat(me.get("clinic").get("name").asText()).isEqualTo("Sahaj test clinic");
        assertThat(me.get("clinic").get("area").asText()).isEqualTo("Pune");
        assertThat(me.get("clinic").get("brand").asText()).isEqualTo("blue");
        assertThat(me.get("clinic").get("plan").asText()).isNotBlank();
        assertThat(me.get("menu").toString()).contains("today", "queue", "billing", "config");
        assertThat(me.get("preferences").get("menuMode").asText()).isIn("simple", "advanced");
    }

    @Test
    void meMembershipIdMatchesTheTeamListEntry() throws Exception {
        Clinic clinic = newClinic();
        JsonNode me = ok(HttpMethod.GET, ME, clinic.owner(), null, 200);
        JsonNode team = ok(HttpMethod.GET, "/api/v1/platform/team", clinic.owner(), null, 200);
        assertThat(team.findValuesAsText("id")).contains(me.get("membershipId").asText());
    }

    @Test
    void menuDiffersPerRoleAndNonOwnersNeverGetConfigOrRevenue() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            JsonNode me = ok(HttpMethod.GET, ME, clinic.as(role), null, 200);
            assertThat(me.get("role").asText()).isEqualTo(role);
            String menu = me.get("menu").toString();
            assertThat(menu).as(role).doesNotContain("config").doesNotContain("revenue");
            assertThat(menu).as(role).contains("today");
        }
        assertThat(ok(HttpMethod.GET, ME, clinic.as("BILLING"), null, 200).get("menu").toString()).contains("billing");
        assertThat(ok(HttpMethod.GET, ME, clinic.as("NURSE"), null, 200).get("menu").toString()).doesNotContain("billing");
    }

    @Test
    void realLoginAsAddedDoctorGivesTheDoctorMenuAndOwnerRoutesAre403() throws Exception {
        Clinic clinic = newClinic();
        Member doc = addMember(clinic, "DOCTOR");
        Session s = login(doc.mobile());
        assertThat(s.next()).isEqualTo("APP");
        assertThat(s.role()).isEqualTo("DOCTOR");
        JsonNode me = ok(HttpMethod.GET, ME, bearer(s.accessToken()), null, 200);
        assertThat(me.get("role").asText()).isEqualTo("DOCTOR");
        assertThat(me.get("membershipId").asText()).isEqualTo(doc.membershipId().toString());
        assertThat(me.get("menu").toString()).doesNotContain("config");
        problem(call(HttpMethod.GET, "/api/v1/platform/setup/overview", bearer(s.accessToken()), null), 403);
        problem(call(HttpMethod.POST, "/api/v1/opd/services", bearer(s.accessToken()),
                "{\"name\":\"X\",\"category\":\"Lab\",\"priceRupees\":10}"), 403);
    }

    @Test
    void preferencesPersistAndPartialUpdatesKeepTheRest() throws Exception {
        Clinic clinic = newClinic();
        JsonNode first = ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(),
                "{\"language\":\"mr\",\"theme\":\"dark\",\"menuMode\":\"simple\",\"sidebarCollapsed\":true}", 200);
        assertThat(first.get("preferences").get("language").asText()).isEqualTo("mr");
        assertThat(first.get("preferences").get("theme").asText()).isEqualTo("dark");
        assertThat(first.get("preferences").get("sidebarCollapsed").asBoolean()).isTrue();
        // the simple menu has 6 items, plus Second checks for an owner with doctor details (SAH-66)
        assertThat(first.get("menu").size()).isLessThanOrEqualTo(7);
        JsonNode second = ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"theme\":\"light\"}", 200);
        assertThat(second.get("preferences").get("theme").asText()).isEqualTo("light");
        assertThat(second.get("preferences").get("language").asText()).isEqualTo("mr");
        JsonNode read = ok(HttpMethod.GET, ME, clinic.owner(), null, 200);
        assertThat(read.get("preferences").get("menuMode").asText()).isEqualTo("simple");
        ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{}", 200);
    }

    @Test
    void preferencesAreStoredPerUser() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"theme\":\"dark\"}", 200);
        Clinic other = newClinic();
        assertThat(ok(HttpMethod.GET, ME, other.owner(), null, 200).get("preferences").get("theme").asText())
                .isNotEqualTo("dark");
    }

    @Test
    void invalidPreferencesAre400() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"language\":\"fr\"}"), 400);
        problem(call(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"theme\":\"blue\"}"), 400);
        problem(call(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"menuMode\":\"x\"}"), 400);
        problem(call(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"sidebarCollapsed\":\"maybe\"}"), 400);
    }

    @Test
    void preferencesWorkForEveryRole() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            ok(HttpMethod.PATCH, ME + "/preferences", clinic.as(role), "{\"language\":\"hi\"}", 200);
        }
    }

    @Test
    void meWithoutTokenIs401() throws Exception {
        mvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }
}
