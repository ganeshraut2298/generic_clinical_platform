package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.FollowUpService;
import com.sahaj.opd.application.FollowUpView;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-14 GET /opd/follow-ups: contract shape, any clinic role may read, 400 on a bad or missing status. */
@WebMvcTest(controllers = FollowUpController.class)
@Import(SecurityConfig.class)
class FollowUpControllerTest {

    private static final String URL = "/api/v1/opd/follow-ups";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FollowUpService followUpService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static FollowUpView view() {
        FollowUpView.Item item = new FollowUpView.Item(UUID.randomUUID(), UUID.randomUUID(), "Asha Rao",
                "9000000001", UUID.randomUUID(), "Dr Rao", LocalDate.of(2026, 11, 1), "Review after treatment",
                "MISSED", 3, UUID.randomUUID(), null, null);
        return new FollowUpView(new FollowUpView.Counts(1, 2, 3, 4), List.of(item));
    }

    @Test
    void returnsTheContractShape() throws Exception {
        when(followUpService.list(TENANT_ID, "FRONT_DESK", "MISSED")).thenReturn(view());

        mockMvc.perform(get(URL).param("status", "MISSED").with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.due").value(1))
                .andExpect(jsonPath("$.counts.missed").value(2))
                .andExpect(jsonPath("$.counts.upcoming").value(3))
                .andExpect(jsonPath("$.counts.returned").value(4))
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].patientId").exists())
                .andExpect(jsonPath("$.items[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.items[0].mobile").value("9000000001"))
                .andExpect(jsonPath("$.items[0].doctorMembershipId").exists())
                .andExpect(jsonPath("$.items[0].doctorName").value("Dr Rao"))
                .andExpect(jsonPath("$.items[0].dueDate").value("2026-11-01"))
                .andExpect(jsonPath("$.items[0].reason").value("Review after treatment"))
                .andExpect(jsonPath("$.items[0].status").value("MISSED"))
                .andExpect(jsonPath("$.items[0].daysLate").value(3))
                .andExpect(jsonPath("$.items[0].sourceVisitId").exists())
                .andExpect(jsonPath("$.items[0].returnedVisitId").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST",
            "COUNSELLOR"})
    void everyClinicRoleCanRead(String role) throws Exception {
        when(followUpService.list(eq(TENANT_ID), eq(role), any())).thenReturn(view());

        mockMvc.perform(get(URL).param("status", "DUE").with(as(role))).andExpect(status().isOk());
        verify(followUpService).list(TENANT_ID, role, "DUE");
    }

    @Test
    void aBadStatusIs400() throws Exception {
        when(followUpService.list(any(), any(), eq("SOON")))
                .thenThrow(new InvalidInputException("status", "Choose DUE, MISSED, UPCOMING or RETURNED"));

        mockMvc.perform(get(URL).param("status", "SOON").with(as("OWNER"))).andExpect(status().isBadRequest());
    }

    @Test
    void aMissingStatusIs400() throws Exception {
        when(followUpService.list(any(), any(), eq(null)))
                .thenThrow(new InvalidInputException("status", "Choose DUE, MISSED, UPCOMING or RETURNED"));

        mockMvc.perform(get(URL).with(as("OWNER"))).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get(URL).param("status", "DUE")).andExpect(status().isUnauthorized());
    }
}
