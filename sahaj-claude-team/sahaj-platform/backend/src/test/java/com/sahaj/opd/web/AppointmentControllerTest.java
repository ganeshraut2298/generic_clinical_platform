package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.AppointmentCreateInput;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentView;
import com.sahaj.opd.application.BookingRulesInput;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.BookingRulesService;
import com.sahaj.opd.application.DoctorSlotSettingsInput;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsService;
import com.sahaj.opd.application.DoctorSlotSettingsView;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-11 opd appointment endpoints: contract shape, role gates (OWNER/FRONT_DESK write, OWNER-only
 * booking rules), tenant and role from the signed-in user, problem responses.
 */
@WebMvcTest(controllers = {AppointmentController.class, DoctorSlotController.class, BookingRulesController.class})
@Import(SecurityConfig.class)
class AppointmentControllerTest {

    private static final String OPD = "/api/v1/opd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID APPT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppointmentService appointmentService;

    @MockBean
    private DoctorSlotSettingsService doctorSlotSettingsService;

    @MockBean
    private BookingRulesService bookingRulesService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static AppointmentView view(String status) {
        return new AppointmentView(APPT_ID, PATIENT_ID, "Asha Rao", 30, "F", DOCTOR_ID, "Dr Rao",
                LocalDate.of(2026, 11, 1), LocalTime.of(9, 0), status, "NEW", "FRONT_DESK", null, null, null);
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(OPD + "/appointments").param("date", "2026-11-01")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsTheDaysAppointments() throws Exception {
        when(appointmentService.list(TENANT_ID, LocalDate.of(2026, 11, 1), DOCTOR_ID))
                .thenReturn(List.of(view("BOOKED")));

        mockMvc.perform(get(OPD + "/appointments").param("date", "2026-11-01")
                        .param("doctorMembershipId", DOCTOR_ID.toString()).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].apptTime").value("09:00"))
                .andExpect(jsonPath("$[0].status").value("BOOKED"));
    }

    @Test
    void frontDeskBooksAnAppointmentAndGets201() throws Exception {
        when(appointmentService.create(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), any(AppointmentCreateInput.class)))
                .thenReturn(view("BOOKED"));

        mockMvc.perform(post(OPD + "/appointments").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\","
                                + "\"apptDate\":\"2026-11-01\",\"apptTime\":\"09:00\",\"visitType\":\"NEW\","
                                + "\"source\":\"FRONT_DESK\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    void aNurseCannotBookAndGets403() throws Exception {
        mockMvc.perform(post(OPD + "/appointments").with(as("NURSE")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\","
                                + "\"apptDate\":\"2026-11-01\",\"apptTime\":\"09:00\",\"visitType\":\"NEW\","
                                + "\"source\":\"FRONT_DESK\"}"))
                .andExpect(status().isForbidden());
        verify(appointmentService, never()).create(any(), any(), any(), any());
    }

    @Test
    void aClashingBookingIs409() throws Exception {
        when(appointmentService.create(any(), any(), any(), any())).thenThrow(new ConflictException(
                "appointment-slot-taken", "That slot is already booked", "This doctor already has a booked patient."));

        mockMvc.perform(post(OPD + "/appointments").with(as("OWNER")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\","
                                + "\"apptDate\":\"2026-11-01\",\"apptTime\":\"09:00\",\"visitType\":\"NEW\","
                                + "\"source\":\"FRONT_DESK\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerCancelsAnAppointment() throws Exception {
        when(appointmentService.changeStatus(TENANT_ID, USER_ID, "OWNER", APPT_ID, "CANCELLED"))
                .thenReturn(view("CANCELLED"));

        mockMvc.perform(patch(OPD + "/appointments/" + APPT_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void anUnknownAppointmentOnPatchIs404() throws Exception {
        when(appointmentService.changeStatus(any(), any(), any(), any(), any()))
                .thenThrow(new NotFoundException("x"));

        mockMvc.perform(patch(OPD + "/appointments/" + APPT_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"status\":\"NOSHOW\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createTokenReturnsTheArrivedAppointmentWithItsTokenNumber() throws Exception {
        AppointmentView arrived = new AppointmentView(APPT_ID, PATIENT_ID, "Asha Rao", 30, "F", DOCTOR_ID, "Dr Rao",
                LocalDate.of(2026, 11, 1), LocalTime.of(9, 0), "ARRIVED", "NEW", "FRONT_DESK", null,
                UUID.randomUUID(), 9);
        when(appointmentService.createToken(TENANT_ID, USER_ID, "FRONT_DESK", APPT_ID)).thenReturn(arrived);

        mockMvc.perform(post(OPD + "/appointments/" + APPT_ID + "/token").with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.tokenNo").value(9));
    }

    @Test
    void createTokenOnAnAlreadyArrivedAppointmentIs409() throws Exception {
        when(appointmentService.createToken(any(), any(), any(), any()))
                .thenThrow(new ConflictException("appointment-not-bookable", "Not bookable", "Already has a token."));

        mockMvc.perform(post(OPD + "/appointments/" + APPT_ID + "/token").with(as("OWNER")))
                .andExpect(status().isConflict());
    }

    // --- doctor slot settings ---

    @Test
    void anyClinicRoleReadsDoctorSlotSettings() throws Exception {
        DoctorSlotSettingsView settings = new DoctorSlotSettingsView(DOCTOR_ID, 15, List.of(0, 1, 2, 3, 4, 5, 6), 4,
                24, List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));
        when(doctorSlotSettingsService.listAll(TENANT_ID)).thenReturn(List.of(settings));

        mockMvc.perform(get(OPD + "/doctor-slots").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctorMembershipId").value(DOCTOR_ID.toString()))
                .andExpect(jsonPath("$[0].slotMinutes").value(15))
                .andExpect(jsonPath("$[0].sessions[0].startTime").value("09:00"));
    }

    @Test
    void aDoctorCannotChangeSlotSettingsAndGets403() throws Exception {
        mockMvc.perform(put(OPD + "/doctor-slots/" + DOCTOR_ID).with(as("DOCTOR")).contentType(JSON)
                        .content("{\"slotMinutes\":15,\"workingDays\":[1,2,3],\"walkInEveryNth\":0,"
                                + "\"maxOnlinePerDay\":0,\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"13:00\"}]}"))
                .andExpect(status().isForbidden());
        verify(doctorSlotSettingsService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void frontDeskSavesDoctorSlotSettings() throws Exception {
        DoctorSlotSettingsView saved = new DoctorSlotSettingsView(DOCTOR_ID, 20, List.of(1, 2, 3), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));
        when(doctorSlotSettingsService.save(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(DOCTOR_ID),
                any(DoctorSlotSettingsInput.class))).thenReturn(saved);

        mockMvc.perform(put(OPD + "/doctor-slots/" + DOCTOR_ID).with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"slotMinutes\":20,\"workingDays\":[1,2,3],\"walkInEveryNth\":0,"
                                + "\"maxOnlinePerDay\":0,\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"13:00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotMinutes").value(20));
    }

    // --- booking rules ---

    @Test
    void anyClinicRoleReadsBookingRules() throws Exception {
        when(bookingRulesService.get(TENANT_ID)).thenReturn(new BookingRulesRow(1, 2, 15, true, false, false));

        mockMvc.perform(get(OPD + "/booking-rules").with(as("BILLING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeBeforeHours").value(1))
                .andExpect(jsonPath("$.overbook").value(false));
    }

    @Test
    void frontDeskCannotChangeBookingRulesAndGets403() throws Exception {
        mockMvc.perform(put(OPD + "/booking-rules").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"closeBeforeHours\":1,\"remindBeforeHours\":2,\"noShowAfterMinutes\":15,"
                                + "\"followPriority\":true,\"selfBook\":false,\"overbook\":false}"))
                .andExpect(status().isForbidden());
        verify(bookingRulesService, never()).save(any(), any(), any());
    }

    @Test
    void theOwnerChangesBookingRules() throws Exception {
        when(bookingRulesService.save(eq(TENANT_ID), eq(USER_ID), any(BookingRulesInput.class)))
                .thenReturn(new BookingRulesRow(2, 2, 20, false, false, true));

        mockMvc.perform(put(OPD + "/booking-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"closeBeforeHours\":2,\"remindBeforeHours\":2,\"noShowAfterMinutes\":20,"
                                + "\"followPriority\":false,\"selfBook\":false,\"overbook\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overbook").value(true));
    }

    @Test
    void anAccessDeniedFromTheServiceAlsoMapsTo403() throws Exception {
        when(bookingRulesService.save(any(), any(), any())).thenThrow(new AccessDeniedException("no"));

        mockMvc.perform(put(OPD + "/booking-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"closeBeforeHours\":2,\"remindBeforeHours\":2,\"noShowAfterMinutes\":20,"
                                + "\"followPriority\":false,\"selfBook\":false,\"overbook\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInvalidBookingRulesChangeIs400() throws Exception {
        when(bookingRulesService.save(any(), any(), any()))
                .thenThrow(new InvalidInputException("closeBeforeHours", "Enter a close-before window from 0 to 48 hours"));

        mockMvc.perform(put(OPD + "/booking-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"closeBeforeHours\":90,\"remindBeforeHours\":2,\"noShowAfterMinutes\":20,"
                                + "\"followPriority\":false,\"selfBook\":false,\"overbook\":true}"))
                .andExpect(status().isBadRequest());
    }
}
