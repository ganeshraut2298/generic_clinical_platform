package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.ScopeViews.WardAssignmentNurse;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentWard;
import com.sahaj.ipd.application.ScopeViews.WardAssignments;
import com.sahaj.ipd.application.WardAssignmentService;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-34 story 34A, the data part: ward-assignment endpoints (owner only). */
@WebMvcTest(controllers = WardAssignmentController.class)
@Import(SecurityConfig.class)
class WardAssignmentControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WardAssignmentService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static WardAssignments view() {
        return new WardAssignments(true, List.of(new WardAssignmentWard(WARD, "General ward", List.of(NURSE))),
                List.of(new WardAssignmentNurse(NURSE, "Asha", List.of(WARD))), 0);
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/ward-assignments")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyOperationIsForTheOwnerOnly() throws Exception {
        for (String role : new String[] {"NURSE", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST"}) {
            mockMvc.perform(get(URL + "/ward-assignments").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/ward-assignments/" + WARD).with(as(role)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"membershipIds\":[]}")).andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/ward-scope").with(as(role)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"on\":true}")).andExpect(status().isForbidden());
        }
        verify(service, never()).get(any(), any(), any());
        verify(service, never()).setWard(any(), any(), any(), any(), any());
        verify(service, never()).setScope(any(), any(), any(), any());
    }

    @Test
    void getAnswersTheContractShape() throws Exception {
        when(service.get(TENANT_ID, USER_ID, "OWNER")).thenReturn(view());

        mockMvc.perform(get(URL + "/ward-assignments").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wardScope").value(true))
                .andExpect(jsonPath("$.wards[0].wardName").value("General ward"))
                .andExpect(jsonPath("$.wards[0].nurseMembershipIds[0]").value(NURSE.toString()))
                .andExpect(jsonPath("$.nurses[0].name").value("Asha"))
                .andExpect(jsonPath("$.nurses[0].wardIds[0]").value(WARD.toString()))
                .andExpect(jsonPath("$.nursesWithoutWard").value(0));
    }

    @Test
    void setWardPassesTheBodyAndMapsItsProblems() throws Exception {
        when(service.setWard(any(), any(), any(), any(), any())).thenReturn(view())
                .thenThrow(new InvalidInputException("membershipIds", "Choose nurses from the list."))
                .thenThrow(new NotFoundException("Ward not found"))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only",
                        "IPD is read-only. Nothing can be changed."));
        String body = "{\"membershipIds\":[\"" + NURSE + "\"]}";

        mockMvc.perform(put(URL + "/ward-assignments/" + WARD).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        mockMvc.perform(put(URL + "/ward-assignments/" + WARD).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("membershipIds"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Choose nurses from the list."));
        mockMvc.perform(put(URL + "/ward-assignments/" + WARD).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound());
        mockMvc.perform(put(URL + "/ward-assignments/" + WARD).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
    }

    @Test
    void aWardIdThatIsNotAUuidIs400() throws Exception {
        mockMvc.perform(put(URL + "/ward-assignments/not-a-uuid").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipIds\":[]}")).andExpect(status().isBadRequest());
        verify(service, never()).setWard(any(), any(), any(), any(), any());
    }

    @Test
    void setScopeAnswersTheViewAndTheNurseWithoutWardConflict() throws Exception {
        when(service.setScope(any(), any(), any(), any())).thenReturn(view())
                .thenThrow(new ConflictException("nurse-without-ward", "Nurse without a ward",
                        "Assign a ward to every nurse before you switch this on."));

        mockMvc.perform(put(URL + "/ward-scope").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"on\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.wardScope").value(true));
        mockMvc.perform(put(URL + "/ward-scope").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"on\":true}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("nurse-without-ward")))
                .andExpect(jsonPath("$.detail").value("Assign a ward to every nurse before you switch this on."));
    }
}
