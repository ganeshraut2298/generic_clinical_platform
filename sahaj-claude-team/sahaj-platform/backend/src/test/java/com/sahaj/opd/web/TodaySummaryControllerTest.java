package com.sahaj.opd.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.TodaySummaryService;
import com.sahaj.opd.application.TodaySummaryView;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/** SAH-15 GET /opd/today-summary: contract shape, 401, every clinic role may read. */
@WebMvcTest(controllers = TodaySummaryController.class)
@Import(SecurityConfig.class)
class TodaySummaryControllerTest {

    private static final String URL = "/api/v1/opd/today-summary";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TodaySummaryService todaySummaryService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static TodaySummaryView view() {
        TodaySummaryView.Doctor busy = new TodaySummaryView.Doctor(DOCTOR_ID, "Dr Rao",
                new TodaySummaryView.NowWithDoctor(UUID.randomUUID(), 3, "Asha Rao", 30, "F", "NEW"), List.of(4, 5));
        TodaySummaryView.Doctor free = new TodaySummaryView.Doctor(UUID.randomUUID(), "Dr Iyer", null, List.of());
        TodaySummaryView.Task task = new TodaySummaryView.Task(UUID.randomUUID(), 4, "Ravi Kumar", "VITALS",
                "Dr Rao", 12);
        return new TodaySummaryView(9, 6, 3, 2, 7, 21, 2, 5, 3, List.of(busy, free), List.of(task));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheContractShape() throws Exception {
        when(todaySummaryService.summary(TENANT_ID, "NURSE")).thenReturn(view());

        mockMvc.perform(get(URL).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientsTotal").value(9))
                .andExpect(jsonPath("$.newCount").value(6))
                .andExpect(jsonPath("$.followUpCount").value(3))
                .andExpect(jsonPath("$.waitingCount").value(2))
                .andExpect(jsonPath("$.inOpdCount").value(7))
                .andExpect(jsonPath("$.longestWaitMinutes").value(21))
                .andExpect(jsonPath("$.followUpsDue").value(2))
                .andExpect(jsonPath("$.followUpsReturned").value(5))
                .andExpect(jsonPath("$.followUpsMissed").value(3))
                .andExpect(jsonPath("$.doctors[0].doctorMembershipId").value(DOCTOR_ID.toString()))
                .andExpect(jsonPath("$.doctors[0].doctorName").value("Dr Rao"))
                .andExpect(jsonPath("$.doctors[0].nowWithDoctor.tokenNo").value(3))
                .andExpect(jsonPath("$.doctors[0].nowWithDoctor.patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.doctors[0].nowWithDoctor.age").value(30))
                .andExpect(jsonPath("$.doctors[0].nowWithDoctor.sex").value("F"))
                .andExpect(jsonPath("$.doctors[0].nowWithDoctor.visitType").value("NEW"))
                .andExpect(jsonPath("$.doctors[0].nextTokens[1]").value(5))
                .andExpect(jsonPath("$.doctors[1].nowWithDoctor").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.doctors[1].nextTokens").isEmpty())
                .andExpect(jsonPath("$.myTasks[0].stage").value("VITALS"))
                .andExpect(jsonPath("$.myTasks[0].waitMinutes").value(12))
                .andExpect(jsonPath("$.myTasks[0].doctorName").value("Dr Rao"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST",
            "COUNSELLOR"})
    void everyClinicRoleCanReadAndTheRoleIsPassedToTheService(String role) throws Exception {
        when(todaySummaryService.summary(TENANT_ID, role)).thenReturn(view());

        mockMvc.perform(get(URL).with(as(role))).andExpect(status().isOk());

        verify(todaySummaryService).summary(TENANT_ID, role);
    }
}
