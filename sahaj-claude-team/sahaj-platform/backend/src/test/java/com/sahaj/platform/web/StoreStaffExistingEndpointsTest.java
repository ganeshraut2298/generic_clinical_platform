package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.billing.application.BillingService;
import com.sahaj.billing.web.BillingController;
import com.sahaj.hra.application.HraService;
import com.sahaj.hra.application.LeaveService;
import com.sahaj.hra.web.HraController;
import com.sahaj.ipd.application.BedBoardService;
import com.sahaj.ipd.web.BedBoardController;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.web.VisitController;
import com.sahaj.platform.application.MeService;
import com.sahaj.platform.application.MeView;
import com.sahaj.platform.application.ModulesService;
import com.sahaj.platform.application.RolePermissionService;
import com.sahaj.platform.application.TeamService;
import com.sahaj.platform.domain.UserPreference;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78 (ADR-028 D4, DEC-061 Answer 2): the Store staff team role gives nothing by itself. A sample of the existing
 * endpoints of every module answers 403 for it, as for any role they do not name, with the real security configuration
 * and role gates; its one open door is the shell's own {@code GET /me}. The complete sweep over every controller is
 * {@link StoreStaffRoleGatesTest}.
 */
@WebMvcTest(controllers = {BedBoardController.class, BillingController.class, HraController.class, VisitController.class,
        TeamController.class, RolePermissionController.class, ModulesController.class, MeController.class})
@Import(SecurityConfig.class)
class StoreStaffExistingEndpointsTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BedBoardService bedBoardService;
    @MockBean
    private BillingService billingService;
    @MockBean
    private HraService hraService;
    @MockBean
    private LeaveService leaveService;
    @MockBean
    private VisitService visitService;
    @MockBean
    private TeamService teamService;
    @MockBean
    private RolePermissionService rolePermissionService;
    @MockBean
    private ModulesService modulesService;
    @MockBean
    private MeService meService;

    private static RequestPostProcessor storeStaff() {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, "STORE_STAFF");
    }

    @Test
    void aStoreStaffSessionGets403OnASampleOfEveryExistingModule() throws Exception {
        String[] reads = {
                "/api/v1/opd/visits/today",
                "/api/v1/billing/counter",
                "/api/v1/billing/today-summary",
                "/api/v1/ipd/board",
                "/api/v1/hra/summary",
                "/api/v1/platform/team",
                "/api/v1/platform/modules",
                "/api/v1/platform/role-permissions"};
        for (String path : reads) {
            mockMvc.perform(get(path).with(storeStaff())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
        }
        mockMvc.perform(post("/api/v1/ipd/beds/" + UUID.randomUUID() + "/ready").with(storeStaff()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(bedBoardService, billingService, hraService, leaveService, visitService, teamService,
                rolePermissionService, modulesService);
    }

    @Test
    void theShellsOwnMeIsTheOneEndpointOpenToItAndItsMenuIsWhatTheServiceSays() throws Exception {
        when(meService.getMe(any(), any(), any())).thenReturn(new MeView(USER_ID, UUID.randomUUID(), "Test Store Staff One",
                "STORE_STAFF", new MeView.Clinic("Test Clinic Baner", "Baner", "teal", null, "FREE", 120, true, 100),
                List.of("pharmacy"), UserPreference.defaults()));

        mockMvc.perform(get("/api/v1/platform/me").with(storeStaff())).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STORE_STAFF"))
                .andExpect(jsonPath("$.menu[0]").value("pharmacy"))
                .andExpect(jsonPath("$.menu.length()").value(1));
    }

    @Test
    void theExistingRolesAreServedAndRefusedExactlyAsBefore() throws Exception {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get("/api/v1/ipd/board").with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, role)))
                    .andExpect(status().isOk());
        }
        for (String role : new String[] {"PHARMACIST", "BILLING", "COUNSELLOR", "VISITING_DOCTOR"}) {
            mockMvc.perform(get("/api/v1/ipd/board").with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, role)))
                    .andExpect(status().isForbidden());
        }
    }
}
