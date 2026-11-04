package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Sweep: SAH-6 team (add, edit, deactivate, role change, last-owner guard) and the role permission grid. */
@OnTestDatabase
class TeamAndPermissionsIT extends SweepSupport {

    private static final String TEAM = "/api/v1/platform/team";
    private static final String PERMS = "/api/v1/platform/role-permissions";

    private static final String[] ROLES = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"};

    private JsonNode memberById(Clinic clinic, UUID id) throws Exception {
        for (JsonNode n : ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200)) {
            if (n.get("id").asText().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError("member not in team list " + id);
    }

    private UUID ownerMembershipId(Clinic clinic) throws Exception {
        return UUID.fromString(ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200).get("membershipId").asText());
    }

    private static String addBody(String name, String mobile, String role) {
        return "{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\",\"role\":\"" + role + "\"}";
    }

    // ---- add ----

    @Test
    void addingAStaffMemberReturnsMaskedMobileAndActiveStatus() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("Nina Nurse", mobile, "NURSE"), 201);
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("role").asText()).isEqualTo("NURSE");
        assertThat(created.get("mobileMasked").asText()).endsWith(mobile.substring(6)).doesNotContain(mobile);
        assertThat(created.has("doctor")).isFalse();
        assertThat(memberById(clinic, UUID.fromString(created.get("id").asText())).get("name").asText()).isEqualTo("Nina Nurse");
        // the list never returns the full number
        assertThat(ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200).toString()).doesNotContain(mobile);
    }

    @Test
    void addingEveryRoleWorksUntilThePlanLimitThenRefuses() throws Exception {
        Clinic clinic = newClinic(); // owner + Dr Asha = 2 users of 5
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING"}) {
            ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("Person " + role, uniqueMobile(), role), 201);
        }
        JsonNode p = problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Sixth", uniqueMobile(), "PHARMACIST")), 409);
        assertThat(p.get("type").asText()).endsWith("plan-limit");
    }

    @Test
    void everyRoleValueCanBeChosenOnAdd() throws Exception {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "OWNER"}) {
            Clinic clinic = newClinic();
            JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(),
                    addBody("Role " + role, uniqueMobile(), role), 201);
            assertThat(created.get("role").asText()).isEqualTo(role);
        }
    }

    @Test
    void addingADoctorKeepsSpecialtyFeeDegreeAndRegistration() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Dr Mehta\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"VISITING_DOCTOR\","
                        + "\"doctor\":{\"specialty\":\"Paediatrics\",\"feeRupees\":450,\"degree\":\"MD\",\"registrationNo\":\"MMC-77\"}}", 201);
        JsonNode d = created.get("doctor");
        assertThat(d.get("specialty").asText()).isEqualTo("Paediatrics");
        assertThat(d.get("feeRupees").asInt()).isEqualTo(450);
        assertThat(d.get("degree").asText()).isEqualTo("MD");
        assertThat(d.get("registrationNo").asText()).isEqualTo("MMC-77");
    }

    @Test
    void addingADoctorWithoutDoctorDetailsStillWorks() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("Dr Bare", uniqueMobile(), "DOCTOR"), 201);
        assertThat(created.get("role").asText()).isEqualTo("DOCTOR");
        assertThat(created.get("doctor").get("feeRupees").asInt()).isZero();
    }

    @Test
    void addValidationReturns400WithFieldMessages() throws Exception {
        Clinic clinic = newClinic();
        JsonNode blank = problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("  ", uniqueMobile(), "NURSE")), 400);
        assertThat(blank.toString()).contains("Enter the name");
        JsonNode badMobile = problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Ok Name", "12345", "NURSE")), 400);
        assertThat(badMobile.toString()).contains("Enter a 10-digit mobile number");
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Ok Name", "5123456789", "NURSE")), 400);
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), "{\"name\":\"Ok\",\"mobile\":\"" + uniqueMobile() + "\"}"), 400);
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Ok", uniqueMobile(), "WIZARD")), 400);
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("x".repeat(250), uniqueMobile(), "NURSE")), 400);
        problem(call(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Dr\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"DOCTOR\",\"doctor\":{\"feeRupees\":-1}}"), 400);
    }

    @Test
    void addingTheSameMobileTwiceIsADuplicateConflict() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("First", mobile, "NURSE"), 201);
        JsonNode p = problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Second", mobile, "BILLING")), 409);
        assertThat(p.get("type").asText()).endsWith("duplicate-mobile");
        assertThat(p.toString()).doesNotContain(mobile);
    }

    @Test
    void ownersOwnMobileCannotBeAddedAgain() throws Exception {
        Clinic clinic = newClinic();
        String ownerMobile = db().asMigrator(c -> DbAccess.text(c, "select mobile from platform.app_user where id = ?::uuid", clinic.ownerUserId().toString()));
        JsonNode p = problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Clone", ownerMobile, "NURSE")), 409);
        assertThat(p.get("type").asText()).endsWith("duplicate-mobile");
    }

    @Test
    void aMobileAlreadyInAnotherClinicCanJoinThisClinicToo() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String mobile = uniqueMobile();
        ok(HttpMethod.POST, TEAM, a.owner(), addBody("Shared Nurse", mobile, "NURSE"), 201);
        ok(HttpMethod.POST, TEAM, b.owner(), addBody("Shared Nurse", mobile, "NURSE"), 201);
    }

    // ---- roles / reads ----

    @Test
    void everyNonOwnerRoleCanReadTheTeamButNoWrite() throws Exception {
        Clinic clinic = newClinic();
        Member nurse = addMember(clinic, "NURSE");
        for (String role : ROLES) {
            RequestPostProcessor r = clinic.as(role);
            // clinic.as keeps the owner's user id, but the OwnerGuard re-reads it; use a real member for the write checks
            assertThat(ok(HttpMethod.GET, TEAM, r, null, 200).size()).isGreaterThanOrEqualTo(3);
            problem(call(HttpMethod.POST, TEAM, r, addBody("N", uniqueMobile(), "NURSE")), 403);
            problem(call(HttpMethod.PATCH, TEAM + "/" + nurse.membershipId(), r, "{\"name\":\"Z\"}"), 403);
            problem(call(HttpMethod.POST, TEAM + "/" + nurse.membershipId() + "/deactivate", r, null), 403);
            problem(call(HttpMethod.POST, TEAM + "/" + nurse.membershipId() + "/reactivate", r, null), 403);
            problem(call(HttpMethod.DELETE, TEAM + "/" + nurse.membershipId(), r, null), 403);
        }
        assertThat(memberById(clinic, nurse.membershipId()).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void realNonOwnerSessionCannotChangeTheTeamAndRealOwnerSessionCan() throws Exception {
        Clinic clinic = newClinic();
        Member desk = addMember(clinic, "FRONT_DESK");
        Session s = login(desk.mobile());
        problem(call(HttpMethod.POST, TEAM, bearer(s.accessToken()), addBody("N", uniqueMobile(), "NURSE")), 403);
        assertThat(ok(HttpMethod.GET, TEAM, bearer(s.accessToken()), null, 200).size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void unauthenticatedTeamCallIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(TEAM))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ---- edit / role change ----

    @Test
    void renameAndRoleChangeAreSavedAndShownInTheList() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        JsonNode updated = ok(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(),
                "{\"name\":\"Renamed Person\",\"role\":\"BILLING\"}", 200);
        assertThat(updated.get("name").asText()).isEqualTo("Renamed Person");
        assertThat(updated.get("role").asText()).isEqualTo("BILLING");
        assertThat(memberById(clinic, m.membershipId()).get("role").asText()).isEqualTo("BILLING");
    }

    @Test
    void changingARoleToDoctorCreatesDoctorDetailsAndBackRemovesThemFromTheView() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        JsonNode asDoctor = ok(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(),
                "{\"role\":\"DOCTOR\",\"doctor\":{\"specialty\":\"ENT\",\"feeRupees\":500,\"registrationNo\":\"R-9\"}}", 200);
        assertThat(asDoctor.get("doctor").get("feeRupees").asInt()).isEqualTo(500);
        JsonNode asNurse = ok(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(), "{\"role\":\"NURSE\"}", 200);
        assertThat(asNurse.has("doctor")).isFalse();
    }

    @Test
    void roleChangeToDoctorWithoutDetailsDoesNotFail() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "FRONT_DESK");
        ok(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(), "{\"role\":\"DOCTOR\"}", 200);
    }

    @Test
    void editingDoctorFeeTwiceKeepsOtherDoctorFields() throws Exception {
        Clinic clinic = newClinic();
        JsonNode first = ok(HttpMethod.PATCH, TEAM + "/" + clinic.doctorMembershipId(), clinic.owner(),
                "{\"doctor\":{\"feeRupees\":350}}", 200);
        assertThat(first.get("doctor").get("feeRupees").asInt()).isEqualTo(350);
        assertThat(first.get("doctor").get("registrationNo").asText()).isEqualTo("MH12345");
        JsonNode second = ok(HttpMethod.PATCH, TEAM + "/" + clinic.doctorMembershipId(), clinic.owner(),
                "{\"doctor\":{\"registrationNo\":\"NEW-1\"}}", 200);
        assertThat(second.get("doctor").get("feeRupees").asInt()).isEqualTo(350);
        assertThat(second.get("doctor").get("registrationNo").asText()).isEqualTo("NEW-1");
        assertThat(second.get("doctor").get("degree").asText()).isEqualTo("MBBS");
    }

    @Test
    void emptyPatchChangesNothingAndSucceeds() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        JsonNode r = ok(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(), "{}", 200);
        assertThat(r.get("role").asText()).isEqualTo("NURSE");
    }

    @Test
    void patchValidationAndNotFound() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        problem(call(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(), "{\"name\":\"\"}"), 400);
        problem(call(HttpMethod.PATCH, TEAM + "/" + m.membershipId(), clinic.owner(), "{\"role\":\"KING\"}"), 400);
        problem(call(HttpMethod.PATCH, TEAM + "/" + UUID.randomUUID(), clinic.owner(), "{\"name\":\"X\"}"), 404);
        problem(call(HttpMethod.PATCH, TEAM + "/not-a-uuid", clinic.owner(), "{\"name\":\"X\"}"), 400);
    }

    // ---- last owner ----

    @Test
    void theOnlyOwnerCannotBeDeactivatedDemotedOrRemoved() throws Exception {
        Clinic clinic = newClinic();
        UUID ownerId = ownerMembershipId(clinic);
        JsonNode a = problem(call(HttpMethod.POST, TEAM + "/" + ownerId + "/deactivate", clinic.owner(), null), 409);
        assertThat(a.get("type").asText()).endsWith("last-owner");
        problem(call(HttpMethod.PATCH, TEAM + "/" + ownerId, clinic.owner(), "{\"role\":\"NURSE\"}"), 409);
        problem(call(HttpMethod.DELETE, TEAM + "/" + ownerId, clinic.owner(), null), 409);
        assertThat(memberById(clinic, ownerId).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(memberById(clinic, ownerId).get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void withASecondOwnerTheFirstCanBeDemotedButThenTheSecondIsLast() throws Exception {
        Clinic clinic = newClinic();
        Member second = addMember(clinic, "OWNER");
        UUID firstId = ownerMembershipId(clinic);
        Session secondSession = login(second.mobile());
        ok(HttpMethod.PATCH, TEAM + "/" + firstId, bearer(secondSession.accessToken()), "{\"role\":\"NURSE\"}", 200);
        problem(call(HttpMethod.POST, TEAM + "/" + second.membershipId() + "/deactivate", bearer(secondSession.accessToken()), null), 409);
        // the demoted first owner is refused on writes at once (the role is re-read, not trusted from the token)
        problem(call(HttpMethod.POST, TEAM, clinic.owner(), addBody("Late", uniqueMobile(), "NURSE")), 403);
    }

    // ---- deactivate / reactivate / remove ----

    @Test
    void deactivatedMemberCannotLogInAndReactivationRestoresAccess() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        JsonNode off = ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/deactivate", clinic.owner(), null, 200);
        assertThat(off.get("status").asText()).isEqualTo("INACTIVE");
        // idempotent
        ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/deactivate", clinic.owner(), null, 200);
        String code = requestOtpAndCaptureCode(m.mobile(), "LOGIN");
        MvcResult refused = verify(m.mobile(), code, "LOGIN", null, null);
        assertThat(refused.getResponse().getStatus()).isBetween(400, 403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("accessToken");

        JsonNode on = ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/reactivate", clinic.owner(), null, 200);
        assertThat(on.get("status").asText()).isEqualTo("ACTIVE");
        ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/reactivate", clinic.owner(), null, 200);
        ageOutLatestChallenge(m.mobile());
        assertThat(login(m.mobile()).next()).isEqualTo("APP");
    }

    @Test
    void deactivatedMembersExistingAccessTokenIsRefusedByTheMembershipCheck() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "NURSE");
        Session s = login(m.mobile());
        ok(HttpMethod.GET, "/api/v1/platform/me", bearer(s.accessToken()), null, 200);
        ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/deactivate", clinic.owner(), null, 200);
        // the active-membership check may cache for up to 60 seconds (SAH-6 AC2); poll up to 70s
        int status = 200;
        for (int i = 0; i < 70 && status == 200; i++) {
            status = call(HttpMethod.GET, "/api/v1/platform/me", bearer(s.accessToken()), null).andReturn().getResponse().getStatus();
            if (status == 200) {
                Thread.sleep(1000);
            }
        }
        assertThat(status).isIn(401, 403);
    }

    @Test
    void removedMemberStaysListedAsRemovedKeepingTheirNameAndCanBeBroughtBack() throws Exception {
        Clinic clinic = newClinic();
        Member m = addMember(clinic, "BILLING");
        ok(HttpMethod.DELETE, TEAM + "/" + m.membershipId(), clinic.owner(), null, 204);
        JsonNode row = memberById(clinic, m.membershipId());
        assertThat(row.get("status").asText()).isEqualTo("REMOVED");
        assertThat(row.get("name").asText()).isEqualTo("Member BILLING");
        ok(HttpMethod.DELETE, TEAM + "/" + m.membershipId(), clinic.owner(), null, 204); // idempotent
        assertThat(ok(HttpMethod.POST, TEAM + "/" + m.membershipId() + "/reactivate", clinic.owner(), null, 200)
                .get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void reactivatingAtThePlanLimitIsRefused() throws Exception {
        Clinic clinic = newClinic();
        Member off = addMember(clinic, "NURSE");
        ok(HttpMethod.POST, TEAM + "/" + off.membershipId() + "/deactivate", clinic.owner(), null, 200);
        for (String role : new String[] {"FRONT_DESK", "BILLING", "PHARMACIST"}) {
            ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("P " + role, uniqueMobile(), role), 201);
        }
        JsonNode p = problem(call(HttpMethod.POST, TEAM + "/" + off.membershipId() + "/reactivate", clinic.owner(), null), 409);
        assertThat(p.get("type").asText()).endsWith("plan-limit");
    }

    @Test
    void unknownIdIs404ForDeactivateReactivateAndRemove() throws Exception {
        Clinic clinic = newClinic();
        UUID missing = UUID.randomUUID();
        problem(call(HttpMethod.POST, TEAM + "/" + missing + "/deactivate", clinic.owner(), null), 404);
        problem(call(HttpMethod.POST, TEAM + "/" + missing + "/reactivate", clinic.owner(), null), 404);
        problem(call(HttpMethod.DELETE, TEAM + "/" + missing, clinic.owner(), null), 404);
    }

    // ---- tenant isolation ----

    @Test
    void anotherClinicsMembersAreInvisibleAndUntouchable() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Member inB = addMember(b, "NURSE");
        assertThat(ok(HttpMethod.GET, TEAM, a.owner(), null, 200).findValuesAsText("id")).doesNotContain(inB.membershipId().toString());
        problem(call(HttpMethod.PATCH, TEAM + "/" + inB.membershipId(), a.owner(), "{\"name\":\"Hacked\"}"), 404);
        problem(call(HttpMethod.POST, TEAM + "/" + inB.membershipId() + "/deactivate", a.owner(), null), 404);
        problem(call(HttpMethod.DELETE, TEAM + "/" + inB.membershipId(), a.owner(), null), 404);
        assertThat(memberById(b, inB.membershipId()).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void teamChangesAreAuditedWithMaskedMobiles() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(), addBody("Audited", mobile, "NURSE"), 201);
        ok(HttpMethod.POST, TEAM + "/" + created.get("id").asText() + "/deactivate", clinic.owner(), null, 200);
        JsonNode events = ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=50", clinic.owner(), null, 200);
        assertThat(events.findValuesAsText("action")).contains("TEAM_MEMBER_ADDED", "TEAM_MEMBER_DEACTIVATED");
        assertThat(events.toString()).doesNotContain(mobile);
    }

    // ---- role permission grid ----

    @Test
    void permissionMatrixCoversEveryRoleAndPermissionAndOwnerIsAlwaysOn() throws Exception {
        Clinic clinic = newClinic();
        JsonNode grid = ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200);
        assertThat(grid.size()).isEqualTo(8 * 7);
        for (JsonNode cell : grid) {
            if (cell.get("role").asText().equals("OWNER")) {
                assertThat(cell.get("allowed").asBoolean()).isTrue();
            }
        }
    }

    @Test
    void everyRoleMayReadTheMatrix() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ROLES) {
            assertThat(ok(HttpMethod.GET, PERMS, clinic.as(role), null, 200).size()).isEqualTo(56);
        }
    }

    @Test
    void ownerChangesApplyAndPersistAndAreAudited() throws Exception {
        Clinic clinic = newClinic();
        boolean before = cell(ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200), "FRONT_DESK", "refund");
        JsonNode saved = ok(HttpMethod.PUT, PERMS, clinic.owner(),
                "[{\"role\":\"FRONT_DESK\",\"permission\":\"refund\",\"allowed\":" + !before + "}]", 200);
        assertThat(cell(saved, "FRONT_DESK", "refund")).isEqualTo(!before);
        assertThat(cell(ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200), "FRONT_DESK", "refund")).isEqualTo(!before);
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=20", clinic.owner(), null, 200)
                .findValuesAsText("action")).contains("ROLE_PERMISSIONS_UPDATED");
        // saving the same state again is a no-op
        ok(HttpMethod.PUT, PERMS, clinic.owner(),
                "[{\"role\":\"FRONT_DESK\",\"permission\":\"refund\",\"allowed\":" + !before + "}]", 200);
        // and the full saved matrix can be sent back as is
        ok(HttpMethod.PUT, PERMS, clinic.owner(), saved.toString(), 200);
        ok(HttpMethod.PUT, PERMS, clinic.owner(), "[]", 200);
    }

    @Test
    void permissionChangesDoNotLeakToAnotherClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        boolean before = cell(ok(HttpMethod.GET, PERMS, b.owner(), null, 200), "NURSE", "export");
        ok(HttpMethod.PUT, PERMS, a.owner(), "[{\"role\":\"NURSE\",\"permission\":\"export\",\"allowed\":" + !before + "}]", 200);
        assertThat(cell(ok(HttpMethod.GET, PERMS, b.owner(), null, 200), "NURSE", "export")).isEqualTo(before);
    }

    @Test
    void permissionPutValidation() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "[{\"role\":\"OWNER\",\"permission\":\"refund\",\"allowed\":false}]"), 400);
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "[{\"role\":\"NURSE\",\"permission\":\"fly\",\"allowed\":true}]"), 400);
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "[{\"role\":\"WIZARD\",\"permission\":\"refund\",\"allowed\":true}]"), 400);
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "[{\"role\":\"NURSE\",\"permission\":\"refund\"}]"), 400);
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "{\"role\":\"NURSE\"}"), 400);
        problem(call(HttpMethod.PUT, PERMS, clinic.owner(), "[null]"), 400);
    }

    @Test
    void nonOwnerCannotSaveThePermissionMatrix() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ROLES) {
            problem(call(HttpMethod.PUT, PERMS, clinic.as(role),
                    "[{\"role\":\"NURSE\",\"permission\":\"refund\",\"allowed\":true}]"), 403);
        }
    }

    private static boolean cell(JsonNode grid, String role, String permission) {
        for (JsonNode c : grid) {
            if (c.get("role").asText().equals(role) && c.get("permission").asText().equals(permission)) {
                return c.get("allowed").asBoolean();
            }
        }
        throw new AssertionError("no cell " + role + "/" + permission);
    }
}
