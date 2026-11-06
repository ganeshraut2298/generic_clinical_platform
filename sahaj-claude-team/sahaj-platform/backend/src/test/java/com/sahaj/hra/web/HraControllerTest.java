package com.sahaj.hra.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.hra.application.AttendanceView;
import com.sahaj.hra.application.HraService;
import com.sahaj.hra.application.LeaveService;
import com.sahaj.hra.application.LeaveInput;
import com.sahaj.hra.application.LeaveView;
import com.sahaj.hra.application.RosterCellInput;
import com.sahaj.hra.application.RosterView;
import com.sahaj.hra.application.StaffSummaryView;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-20 hra endpoints: contract shape, roles, and the 400, 403 module-off and 404 problems. */
@WebMvcTest(controllers = HraController.class)
@Import(SecurityConfig.class)
class HraControllerTest {

    private static final String URL = "/api/v1/hra";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CELL_BODY = "{\"membershipId\":\"" + MEMBER_ID
            + "\",\"date\":\"2026-11-05\",\"shift\":\"E\"}";
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HraService service;

    @MockBean
    private LeaveService leaveService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static RosterView roster() {
        List<RosterView.Cell> cells = List.of(new RosterView.Cell(TODAY, "SHIFT", "M"));
        return new RosterView(List.of(TODAY), List.of(new RosterView.ShiftInfo("M", "Morning", "08:00 – 14:00")),
                true, Instant.parse("2026-11-04T10:00:00Z"),
                List.of(new RosterView.Row(MEMBER_ID, "Meera", "Nurse", false, cells)));
    }

    private static AttendanceView attendance(boolean canMark) {
        return new AttendanceView(TODAY, 15, canMark,
                List.of(new AttendanceView.Row(MEMBER_ID, "Meera", "Nurse", "in", "08:52", false)));
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/roster")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/attendance")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleReadsSummaryRosterAndAttendance() throws Exception {
        for (String role : ALL_ROLES) {
            when(service.summary(TENANT_ID, role)).thenReturn(new StaffSummaryView(5, 2, 3, 3, 1, 2));
            when(service.roster(TENANT_ID)).thenReturn(roster());
            when(service.attendance(TENANT_ID, role)).thenReturn(attendance(false));

            mockMvc.perform(get(URL + "/summary").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.staff").value(5))
                    .andExpect(jsonPath("$.doctors").value(2))
                    .andExpect(jsonPath("$.others").value(3))
                    .andExpect(jsonPath("$.presentToday").value(3))
                    .andExpect(jsonPath("$.lateToday").value(1));
            mockMvc.perform(get(URL + "/roster").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days[0]").value("2026-11-05"))
                    .andExpect(jsonPath("$.shifts[0].key").value("M"))
                    .andExpect(jsonPath("$.published").value(true))
                    .andExpect(jsonPath("$.publishedAt").exists())
                    .andExpect(jsonPath("$.rows[0].membershipId").value(MEMBER_ID.toString()))
                    .andExpect(jsonPath("$.rows[0].roleLabel").value("Nurse"))
                    .andExpect(jsonPath("$.rows[0].doctor").value(false))
                    .andExpect(jsonPath("$.rows[0].cells[0].kind").value("SHIFT"))
                    .andExpect(jsonPath("$.rows[0].cells[0].shift").value("M"));
            mockMvc.perform(get(URL + "/attendance").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value("2026-11-05"))
                    .andExpect(jsonPath("$.lateAfterMinutes").value(15))
                    .andExpect(jsonPath("$.canMark").value(false))
                    .andExpect(jsonPath("$.rows[0].status").value("in"))
                    .andExpect(jsonPath("$.rows[0].markedTime").value("08:52"));
        }
    }

    @Test
    void moduleOffAnswers403WithTheModuleOffProblemType() throws Exception {
        when(service.roster(TENANT_ID)).thenThrow(new ModuleOffException("Staff, timings and leave is an add-on."));

        mockMvc.perform(get(URL + "/roster").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void ownerSetsACell() throws Exception {
        when(service.setCell(TENANT_ID, USER_ID, new RosterCellInput(MEMBER_ID, TODAY, "E"))).thenReturn(roster());

        mockMvc.perform(put(URL + "/roster/cells").with(as("OWNER")).contentType(JSON).content(CELL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[0].shift").value("M"));
    }

    @Test
    void otherRolesCannotEditTheRosterOrPublish() throws Exception {
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            mockMvc.perform(put(URL + "/roster/cells").with(as(role)).contentType(JSON).content(CELL_BODY))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/roster/publish").with(as(role))).andExpect(status().isForbidden());
        }
        verify(service, never()).setCell(any(), any(), any());
        verify(service, never()).publish(any(), any());
    }

    @Test
    void aBadCellIs400WithAFieldErrorAndAnUnknownMemberIs404() throws Exception {
        when(service.setCell(any(), any(), any()))
                .thenThrow(new InvalidInputException("shift", "Choose Morning, Evening, Night or Off"))
                .thenThrow(new NotFoundException("Team member not found"));

        mockMvc.perform(put(URL + "/roster/cells").with(as("OWNER")).contentType(JSON).content(CELL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("shift"));
        mockMvc.perform(put(URL + "/roster/cells").with(as("OWNER")).contentType(JSON).content(CELL_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerPublishes() throws Exception {
        when(service.publish(TENANT_ID, USER_ID)).thenReturn(roster());

        mockMvc.perform(post(URL + "/roster/publish").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));
    }

    @Test
    void ownerAndFrontDeskMarkAttendanceOthersAre403() throws Exception {
        for (String role : new String[] {"OWNER", "FRONT_DESK"}) {
            when(service.mark(TENANT_ID, USER_ID, role, MEMBER_ID, "late")).thenReturn(attendance(true));
            mockMvc.perform(put(URL + "/attendance/" + MEMBER_ID).with(as(role)).contentType(JSON)
                            .content("{\"status\":\"late\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canMark").value(true));
        }
        for (String role : new String[] {"NURSE", "DOCTOR", "BILLING", "COUNSELLOR"}) {
            mockMvc.perform(put(URL + "/attendance/" + MEMBER_ID).with(as(role)).contentType(JSON)
                            .content("{\"status\":\"in\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aBadStatusIs400AndAnUnknownMemberIs404() throws Exception {
        when(service.mark(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("status", "Choose Present, Late or Absent"))
                .thenThrow(new NotFoundException("Team member not found"));

        mockMvc.perform(put(URL + "/attendance/" + MEMBER_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"status\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(URL + "/attendance/" + MEMBER_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"status\":\"in\"}"))
                .andExpect(status().isNotFound());
    }

    private static LeaveView leaveView() {
        return new LeaveView(List.of(new LeaveView.TypeInfo("CL", "Casual leave", 8)),
                List.of(new LeaveView.Request(UUID.randomUUID(), MEMBER_ID, "Meera", "Nurse", "CL", "Casual leave",
                        TODAY, TODAY, 1, null, "pending", Instant.parse("2026-11-04T10:00:00Z"), null, null, 0)),
                List.of(new LeaveView.Balance(MEMBER_ID, "Meera", new LeaveView.Remaining(8, 7, 15))),
                List.of(new LeaveView.Applicant(MEMBER_ID, "Meera")), 1, false);
    }

    @Test
    void everyClinicRoleReadsLeave() throws Exception {
        for (String role : ALL_ROLES) {
            when(leaveService.view(TENANT_ID, USER_ID, role)).thenReturn(leaveView());

            mockMvc.perform(get(URL + "/leave").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.types[0].days").value(8))
                    .andExpect(jsonPath("$.requests[0].status").value("pending"))
                    .andExpect(jsonPath("$.balances[0].remaining.CL").value(8))
                    .andExpect(jsonPath("$.balances[0].remaining.EL").value(15))
                    .andExpect(jsonPath("$.applicants[0].name").value("Meera"));
        }
        mockMvc.perform(get(URL + "/leave")).andExpect(status().isUnauthorized());
    }

    @Test
    void applyingForLeaveAnswers201AndPassesTheBody() throws Exception {
        LeaveInput input = new LeaveInput(null, "CL", TODAY, TODAY.plusDays(1), "fever");
        when(leaveService.create(TENANT_ID, USER_ID, "NURSE", input)).thenReturn(leaveView());

        mockMvc.perform(post(URL + "/leave").with(as("NURSE")).contentType(JSON)
                        .content("{\"type\":\"CL\",\"from\":\"2026-11-05\",\"to\":\"2026-11-06\",\"reason\":\"fever\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingCount").value(1));
    }

    @Test
    void leaveProblemsMapToTheContractStatuses() throws Exception {
        when(leaveService.create(any(), any(), any(), any()))
                .thenThrow(new ConflictException("leave-overlap", "Leave already exists",
                        "That person already has leave on some of these days."))
                .thenThrow(new AccessDeniedException("no"))
                .thenThrow(new NotFoundException("Team member not found"))
                .thenThrow(new InvalidInputException("to", "The last day cannot be before the first day"));
        String body = "{\"type\":\"CL\",\"from\":\"2026-11-05\",\"to\":\"2026-11-06\"}";

        mockMvc.perform(post(URL + "/leave").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That person already has leave on some of these days."));
        mockMvc.perform(post(URL + "/leave").with(as("NURSE")).contentType(JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/leave").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/leave").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheOwnerApprovesOrDeclines() throws Exception {
        UUID id = UUID.randomUUID();
        when(leaveService.approve(TENANT_ID, USER_ID, "OWNER", id)).thenReturn(leaveView());
        when(leaveService.decline(TENANT_ID, USER_ID, "OWNER", id)).thenReturn(leaveView());

        mockMvc.perform(post(URL + "/leave/" + id + "/approve").with(as("OWNER"))).andExpect(status().isOk());
        mockMvc.perform(post(URL + "/leave/" + id + "/decline").with(as("OWNER"))).andExpect(status().isOk());
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            mockMvc.perform(post(URL + "/leave/" + id + "/approve").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/leave/" + id + "/decline").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void anAlreadyDecidedRequestIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(leaveService.approve(any(), any(), any(), any())).thenThrow(new ConflictException("leave-already-decided",
                "Already decided", "This request was already decided."));

        mockMvc.perform(post(URL + "/leave/" + id + "/approve").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This request was already decided."));
    }
}
