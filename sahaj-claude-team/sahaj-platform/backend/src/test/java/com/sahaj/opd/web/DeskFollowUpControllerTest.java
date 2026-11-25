package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.AppointmentView;
import com.sahaj.opd.application.DeskFollowUpService;
import com.sahaj.opd.application.DeskFollowUpService.Result;
import com.sahaj.opd.application.DeskReadService;
import com.sahaj.opd.application.DeskViews.TokenPanel;
import com.sahaj.opd.application.OpdProblemException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-92-2 token panel read and SAH-93 follow-up writes: shape, role gates, tenant from the user, problems. */
@WebMvcTest(controllers = {DeskController.class, DeskFollowUpController.class})
@Import(SecurityConfig.class)
class DeskFollowUpControllerTest {

    private static final String OPD = "/api/v1/opd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID VISIT_ID = UUID.randomUUID();
    private static final UUID FOLLOW_UP_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BOOK_BODY = "{\"intervalKey\":\"D7\",\"apptDate\":\"2026-12-02\",\"apptTime\":\"10:30\","
            + "\"tenantId\":\"" + UUID.randomUUID() + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeskReadService deskReadService;

    @MockBean
    private DeskFollowUpService followUpService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Result booked() {
        AppointmentView appointment = new AppointmentView(UUID.randomUUID(), UUID.randomUUID(), "Asha Rao", 30, "F",
                DOCTOR_ID, "Dr Rao", LocalDate.of(2026, 12, 2), LocalTime.of(10, 30), "BOOKED", "FOLLOW_UP",
                "FRONT_DESK", null, null, null, 0);
        return new Result(FOLLOW_UP_ID, "BOOKED", LocalDate.of(2026, 12, 2), appointment);
    }

    // ---------------------------------------------------------------- token panel

    @Test
    void anyClinicRoleReadsTheTokenPanelWithNoStore() throws Exception {
        when(deskReadService.tokenPanel(TENANT_ID, VISIT_ID)).thenReturn(new TokenPanel(VISIT_ID, 8,
                LocalDate.of(2026, 11, 25), "Asha Rao", DOCTOR_ID, "Dr Rao", null, "WAITING",
                Instant.parse("2026-11-25T04:30:00Z"), 0, 0, 15));

        mockMvc.perform(get(OPD + "/desk/visits/" + VISIT_ID + "/token-panel").with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenNo").value(8))
                .andExpect(jsonPath("$.patientsAhead").value(0))
                .andExpect(jsonPath("$.room").isEmpty())
                .andExpect(jsonPath("$.tokenDate").value("2026-11-25"));
    }

    @Test
    void aTokenPanelOfAnotherClinicIs404() throws Exception {
        when(deskReadService.tokenPanel(TENANT_ID, VISIT_ID)).thenThrow(new NotFoundException("Visit not found"));

        mockMvc.perform(get(OPD + "/desk/visits/" + VISIT_ID + "/token-panel").with(as("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSignedOutVisitorCannotReadTheTokenPanel() throws Exception {
        mockMvc.perform(get(OPD + "/desk/visits/" + VISIT_ID + "/token-panel")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- follow-up writes

    @Test
    void frontDeskBooksTheFollowUpOfAVisitAndTheTenantComesFromTheUser() throws Exception {
        when(followUpService.bookForVisit(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(VISIT_ID), any()))
                .thenReturn(booked());

        mockMvc.perform(post(OPD + "/visits/" + VISIT_ID + "/follow-up/appointment").with(as("FRONT_DESK"))
                        .contentType(JSON).content(BOOK_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.dueDate").value("2026-12-02"))
                .andExpect(jsonPath("$.appointment.apptTime").value("10:30"));
    }

    @Test
    void aNurseCannotBookOrMarkNotNeeded() throws Exception {
        mockMvc.perform(post(OPD + "/visits/" + VISIT_ID + "/follow-up/appointment").with(as("NURSE"))
                        .contentType(JSON).content(BOOK_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(OPD + "/visits/" + VISIT_ID + "/follow-up/not-needed").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(OPD + "/follow-ups/" + FOLLOW_UP_ID + "/appointment").with(as("BILLING"))
                        .contentType(JSON).content("{\"doctorMembershipId\":\"" + DOCTOR_ID
                                + "\",\"apptDate\":\"2026-12-02\",\"apptTime\":\"10:30\"}"))
                .andExpect(status().isForbidden());
        verify(followUpService, never()).bookForVisit(any(), any(), any(), any(), any());
        verify(followUpService, never()).markNotNeeded(any(), any(), any(), any());
    }

    @Test
    void notNeededAnswers200WithNoAppointment() throws Exception {
        when(followUpService.markNotNeeded(TENANT_ID, USER_ID, "OWNER", VISIT_ID))
                .thenReturn(new Result(FOLLOW_UP_ID, "NOT_NEEDED", LocalDate.of(2026, 11, 25), null));

        mockMvc.perform(post(OPD + "/visits/" + VISIT_ID + "/follow-up/not-needed").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_NEEDED"))
                .andExpect(jsonPath("$.appointment").isEmpty());
    }

    @Test
    void aVisitNotDoneIsTheVisitNotDoneProblem() throws Exception {
        when(followUpService.bookForVisit(any(), any(), any(), any(), any()))
                .thenThrow(OpdProblemException.visitNotDone());

        mockMvc.perform(post(OPD + "/visits/" + VISIT_ID + "/follow-up/appointment").with(as("OWNER"))
                        .contentType(JSON).content(BOOK_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/visit-not-done"));
    }

    @Test
    void theIdleListBookingAnswers201AndAClosedFollowUpIsTheClosedProblem() throws Exception {
        when(followUpService.bookFromList(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(FOLLOW_UP_ID), any()))
                .thenReturn(booked()).thenThrow(OpdProblemException.followUpClosed());
        String body = "{\"doctorMembershipId\":\"" + DOCTOR_ID + "\",\"apptDate\":\"2026-12-02\",\"apptTime\":\"10:30\","
                + "\"visitType\":\"NEW\"}";

        mockMvc.perform(post(OPD + "/follow-ups/" + FOLLOW_UP_ID + "/appointment").with(as("OWNER"))
                        .contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followUpId").value(FOLLOW_UP_ID.toString()));
        mockMvc.perform(post(OPD + "/follow-ups/" + FOLLOW_UP_ID + "/appointment").with(as("OWNER"))
                        .contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/follow-up-closed"));
    }
}
