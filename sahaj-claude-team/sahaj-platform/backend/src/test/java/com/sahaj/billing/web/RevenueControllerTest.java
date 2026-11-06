package com.sahaj.billing.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.billing.application.RevenueService;
import com.sahaj.billing.application.RevenueView;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-16 GET /billing/revenue: owner only, contract field names, 400 for a bad range. */
@WebMvcTest(controllers = RevenueController.class)
@Import(SecurityConfig.class)
class RevenueControllerTest {

    private static final String URL = "/api/v1/billing/revenue";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RevenueService revenueService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static RevenueView report() {
        UUID doctor = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 4);
        return new RevenueView("WEEK", 40000, 12, 90000, 3, 30000, 5000, 2000, 1000, 500, 7000,
                List.of(new RevenueView.Day(day, 40000)), List.of(new RevenueView.Mode("CASH", 40000)),
                List.of(new RevenueView.Doctor(doctor, "Dr Rao", 3, 40000)),
                List.of(new RevenueView.Service("Consultation", 90000)),
                List.of(new RevenueView.Due(UUID.randomUUID(), "Asha Rao", "9876543210", "Dr Rao", day, 5000)),
                List.of(new RevenueView.Adjustment("REFUND", "Asha Rao", 500, "Wrong item - CASH - by Meena", day)));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL).param("range", "TODAY")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyRoleExceptTheOwnerGets403() throws Exception {
        for (String role : List.of("DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST",
                "COUNSELLOR")) {
            mockMvc.perform(get(URL).param("range", "TODAY").with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(revenueService);
    }

    @Test
    void theOwnerGetsTheContractShape() throws Exception {
        when(revenueService.report(TENANT_ID, USER_ID, "WEEK")).thenReturn(report());

        mockMvc.perform(get(URL).param("range", "WEEK").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("WEEK"))
                .andExpect(jsonPath("$.collectedPaise").value(40000))
                .andExpect(jsonPath("$.collectedChangePercent").value(12))
                .andExpect(jsonPath("$.billedPaise").value(90000))
                .andExpect(jsonPath("$.patients").value(3))
                .andExpect(jsonPath("$.averageBillPaise").value(30000))
                .andExpect(jsonPath("$.duePaise").value(5000))
                .andExpect(jsonPath("$.atCounterPaise").value(2000))
                .andExpect(jsonPath("$.discountPaise").value(1000))
                .andExpect(jsonPath("$.refundPaise").value(500))
                .andExpect(jsonPath("$.cashInDrawerPaise").value(7000))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-04"))
                .andExpect(jsonPath("$.days[0].collectedPaise").value(40000))
                .andExpect(jsonPath("$.modes[0].mode").value("CASH"))
                .andExpect(jsonPath("$.modes[0].amountPaise").value(40000))
                .andExpect(jsonPath("$.doctors[0].doctorMembershipId").exists())
                .andExpect(jsonPath("$.doctors[0].doctorName").value("Dr Rao"))
                .andExpect(jsonPath("$.doctors[0].patients").value(3))
                .andExpect(jsonPath("$.doctors[0].collectedPaise").value(40000))
                .andExpect(jsonPath("$.topServices[0].name").value("Consultation"))
                .andExpect(jsonPath("$.topServices[0].billedPaise").value(90000))
                .andExpect(jsonPath("$.dues[0].visitId").exists())
                .andExpect(jsonPath("$.dues[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.dues[0].mobile").value("9876543210"))
                .andExpect(jsonPath("$.dues[0].doctorName").value("Dr Rao"))
                .andExpect(jsonPath("$.dues[0].date").value("2026-11-04"))
                .andExpect(jsonPath("$.dues[0].balancePaise").value(5000))
                .andExpect(jsonPath("$.adjustments[0].kind").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.adjustments[0].amountPaise").value(500))
                .andExpect(jsonPath("$.adjustments[0].detail").exists())
                .andExpect(jsonPath("$.adjustments[0].date").value("2026-11-04"));
        verify(revenueService).report(TENANT_ID, USER_ID, "WEEK");
    }

    @Test
    void aNullChangeIsSerialisedAsNull() throws Exception {
        RevenueView base = report();
        when(revenueService.report(TENANT_ID, USER_ID, "TODAY")).thenReturn(new RevenueView("TODAY", 0, null, 0, 0, 0, 0, 0, 0,
                0, 0, base.days(), base.modes(), List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get(URL).param("range", "TODAY").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedChangePercent").value((Object) null));
    }

    @Test
    void aBadRangeIsA400ProblemJson() throws Exception {
        when(revenueService.report(TENANT_ID, USER_ID, "YEAR"))
                .thenThrow(new InvalidInputException("range", "Choose today, last 7 days or last 30 days"));

        mockMvc.perform(get(URL).param("range", "YEAR").with(as("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void aMissingRangeIsA400() throws Exception {
        mockMvc.perform(get(URL).with(as("OWNER"))).andExpect(status().isBadRequest());
    }
}
