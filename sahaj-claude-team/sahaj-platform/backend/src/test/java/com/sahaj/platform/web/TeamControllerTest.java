package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.RolePermissionRepository.Cell;
import com.sahaj.platform.application.RolePermissionService;
import com.sahaj.platform.application.TeamAddInput;
import com.sahaj.platform.application.TeamConflictException;
import com.sahaj.platform.application.TeamInputInvalidException;
import com.sahaj.platform.application.TeamMemberView;
import com.sahaj.platform.application.TeamService;
import com.sahaj.platform.application.PlanLimitException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** SAH-6: owner-only changes (403 otherwise), contract shape, masked mobile and the 409 problem types. */
@WebMvcTest(controllers = {TeamController.class, RolePermissionController.class})
@Import(SecurityConfig.class)
class TeamControllerTest {

    private static final String TEAM = "/api/v1/platform/team";
    private static final String MATRIX = "/api/v1/platform/role-permissions";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TeamService teamService;

    @MockBean
    private RolePermissionService rolePermissionService;

    private static TeamMemberView aMember() {
        return new TeamMemberView(MEMBER_ID, "Nisha", "••••••7410", MembershipRole.NURSE,
                MembershipStatus.ACTIVE, null, null);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void anyClinicRoleCanReadTheTeamAndSeesOnlyMaskedMobiles() throws Exception {
        when(teamService.list(TENANT_ID)).thenReturn(List.of(aMember()));

        mockMvc.perform(get(TEAM).with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$[0].mobileMasked").value("••••••7410"))
                .andExpect(jsonPath("$[0].mobile").doesNotExist())
                .andExpect(jsonPath("$[0].lastLoginAt").value((Object) null))
                .andExpect(jsonPath("$[0].doctor").doesNotExist());
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(TEAM)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonOwnerGets403OnEveryChange() throws Exception {
        String body = "{\"name\":\"Nisha\",\"mobile\":\"9123456780\",\"role\":\"NURSE\"}";
        mockMvc.perform(post(TEAM).with(as("FRONT_DESK")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(TEAM + "/" + MEMBER_ID).with(as("DOCTOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post(TEAM + "/" + MEMBER_ID + "/deactivate").with(as("NURSE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TEAM + "/" + MEMBER_ID + "/reactivate").with(as("BILLING")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(TEAM + "/" + MEMBER_ID).with(as("PHARMACIST"))).andExpect(status().isForbidden());
        mockMvc.perform(put(MATRIX).with(as("FRONT_DESK")).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
        verify(teamService, never()).add(any(), any(), any());
    }

    @Test
    void theOwnerAddsAPersonAndGets201() throws Exception {
        when(teamService.add(eq(TENANT_ID), eq(USER_ID), any(TeamAddInput.class))).thenReturn(aMember());

        mockMvc.perform(post(TEAM).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nisha\",\"mobile\":\"9123456780\",\"role\":\"NURSE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("NURSE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void addStaffShowsTheMockupMessagesForABadNameAndMobile() throws Exception {
        mockMvc.perform(post(TEAM).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"mobile\":\"12345\",\"role\":\"NURSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')].message").value("Enter the name"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='mobile')].message")
                        .value("Enter a 10-digit mobile number"));
    }

    @Test
    void conflictsUseTheirOwnProblemTypes() throws Exception {
        when(teamService.add(any(), any(), any())).thenThrow(new PlanLimitException("The free plan allows up to 5 users in total."));
        mockMvc.perform(post(TEAM).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nisha\",\"mobile\":\"9123456780\",\"role\":\"NURSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/plan-limit"))
                .andExpect(jsonPath("$.detail").value("The free plan allows up to 5 users in total."));

        when(teamService.deactivate(TENANT_ID, USER_ID, MEMBER_ID))
                .thenThrow(new TeamConflictException(TeamConflictException.Kind.LAST_OWNER));
        mockMvc.perform(post(TEAM + "/" + MEMBER_ID + "/deactivate").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/last-owner"));

        when(teamService.reactivate(TENANT_ID, USER_ID, MEMBER_ID))
                .thenThrow(new TeamConflictException(TeamConflictException.Kind.CONCURRENT_CHANGE));
        mockMvc.perform(post(TEAM + "/" + MEMBER_ID + "/reactivate").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/concurrent-change"));
    }

    @Test
    void duplicateMobileIs409AndAnUnknownMemberIs404() throws Exception {
        when(teamService.add(any(), any(), any()))
                .thenThrow(new TeamConflictException(TeamConflictException.Kind.DUPLICATE_MOBILE));
        mockMvc.perform(post(TEAM).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nisha\",\"mobile\":\"9123456780\",\"role\":\"NURSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/duplicate-mobile"));

        when(teamService.deactivate(TENANT_ID, USER_ID, MEMBER_ID)).thenThrow(new NotFoundException("x"));
        mockMvc.perform(post(TEAM + "/" + MEMBER_ID + "/deactivate").with(as("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingAccessReturns204() throws Exception {
        mockMvc.perform(delete(TEAM + "/" + MEMBER_ID).with(as("OWNER"))).andExpect(status().isNoContent());
        verify(teamService).remove(TENANT_ID, USER_ID, MEMBER_ID);
    }

    @Test
    void anyClinicRoleCanReadTheMatrixButOnlyTheOwnerSavesIt() throws Exception {
        when(rolePermissionService.get()).thenReturn(List.of(new Cell(MembershipRole.NURSE, "token", true)));

        mockMvc.perform(get(MATRIX).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("NURSE"))
                .andExpect(jsonPath("$[0].permission").value("token"))
                .andExpect(jsonPath("$[0].allowed").value(true));
    }

    @Test
    void savingTheMatrixValidatesEveryCell() throws Exception {
        mockMvc.perform(put(MATRIX).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"role\":\"NURSE\",\"permission\":\"fly\",\"allowed\":true}]"))
                .andExpect(status().isBadRequest());

        when(rolePermissionService.save(eq(TENANT_ID), eq(USER_ID), any()))
                .thenThrow(new TeamInputInvalidException("The owner always keeps every permission."));
        mockMvc.perform(put(MATRIX).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"role\":\"OWNER\",\"permission\":\"refund\",\"allowed\":false}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The owner always keeps every permission."));
    }
}
