package com.sahaj.opd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.AppointmentRescheduleInput;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentView;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.DeskReadService;
import com.sahaj.opd.application.DeskViews;
import com.sahaj.opd.application.DeskViews.Board;
import com.sahaj.opd.application.DeskViews.Counters;
import com.sahaj.opd.application.DeskViews.DaySlots;
import com.sahaj.opd.application.DeskViews.Doctors;
import com.sahaj.opd.application.DeskViews.Snapshot;
import com.sahaj.opd.application.DeskViews.ToBook;
import com.sahaj.opd.application.DoctorSlotSettingsInput;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsService;
import com.sahaj.opd.application.DoctorSlotSettingsView;
import com.sahaj.opd.application.BookingRulesService;
import com.sahaj.opd.application.OpdProblemException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-90, SAH-91 (docs/api/opd.yaml 0.9.0): desk reads, reschedule, the per-doctor cut-off body and the problem types. */
@WebMvcTest(controllers = {DeskController.class, AppointmentController.class, DoctorSlotController.class})
@Import(SecurityConfig.class)
class DeskControllerTest {

    private static final String OPD = "/api/v1/opd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID APPT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeskReadService deskReadService;

    @MockBean
    private AppointmentService appointmentService;

    @MockBean
    private DoctorSlotSettingsService doctorSlotSettingsService;

    @MockBean
    private BookingRulesService bookingRulesService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Snapshot snapshot() {
        return new Snapshot(Instant.parse("2026-11-25T04:30:00Z"), LocalDate.of(2026, 11, 25),
                new BookingRulesRow(1, 2, 15, true, false, false), "free", new Counters(1, 2, 3, 4, 5),
                new Doctors(0, List.of()), new Board(LocalDate.of(2026, 11, 25), false, List.of()),
                new ToBook(0, List.of()), null);
    }

    // ---------------------------------------------------------------- reads

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(OPD + "/desk/snapshot")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleReadsTheSnapshotWithNoStoreAndTheTenantFromTheUser() throws Exception {
        when(deskReadService.snapshot(TENANT_ID, "NURSE", null, null, null)).thenReturn(snapshot());

        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.today").value("2026-11-25"))
                .andExpect(jsonPath("$.followUpFee").value("free"))
                .andExpect(jsonPath("$.counters.freeToday").value(5))
                .andExpect(jsonPath("$.bookingRules.closeBeforeHours").value(1))
                .andExpect(jsonPath("$.doctors.total").value(0))
                .andExpect(jsonPath("$.board.truncated").value(false))
                .andExpect(jsonPath("$.followUpsToBook.total").value(0))
                .andExpect(jsonPath("$.selected").doesNotExist());
    }

    @Test
    void theSnapshotPassesTheFinderAndBoardParameters() throws Exception {
        UUID board = UUID.randomUUID();
        when(deskReadService.snapshot(TENANT_ID, "FRONT_DESK", DOCTOR_ID, LocalDate.of(2026, 11, 26), board))
                .thenReturn(snapshot());

        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("FRONT_DESK"))
                        .param("doctorMembershipId", DOCTOR_ID.toString()).param("date", "2026-11-26")
                        .param("boardDoctorMembershipId", board.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void aRoleOutsideTheClinicRolesGets403() throws Exception {
        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("PATIENT"))).andExpect(status().isForbidden());
        verify(deskReadService, never()).snapshot(any(), any(), any(), any(), any());
    }

    @Test
    void aBadDateIs400() throws Exception {
        when(deskReadService.snapshot(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("date", "Choose a day from today to the next 6 days."));

        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("OWNER")).param("doctorMembershipId", DOCTOR_ID.toString())
                        .param("date", "2026-12-30"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("OWNER")).param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherClinicsDoctorIs404() throws Exception {
        when(deskReadService.snapshot(any(), any(), any(), any(), any())).thenThrow(new NotFoundException("x"));

        mockMvc.perform(get(OPD + "/desk/snapshot").with(as("OWNER")).param("doctorMembershipId", DOCTOR_ID.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void daySlotsReturnsTheGroupsAndNeedsADate() throws Exception {
        when(deskReadService.daySlots(TENANT_ID, "FRONT_DESK", DOCTOR_ID, LocalDate.of(2026, 11, 25)))
                .thenReturn(new DaySlots(DOCTOR_ID, LocalDate.of(2026, 11, 25), true, false, 15, 1, 1,
                        List.of(new DeskViews.SlotGroup(DeskViews.GroupLabel.MORNING,
                                List.of(new DeskViews.Slot("10:30", true)))), LocalDate.of(2026, 11, 26)));

        mockMvc.perform(get(OPD + "/desk/doctors/" + DOCTOR_ID + "/day-slots").with(as("FRONT_DESK"))
                        .param("date", "2026-11-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].label").value("MORNING"))
                .andExpect(jsonPath("$.groups[0].slots[0].time").value("10:30"))
                .andExpect(jsonPath("$.groups[0].slots[0].insideCutOff").value(true))
                .andExpect(jsonPath("$.nextFreeDate").value("2026-11-26"))
                .andExpect(jsonPath("$.freeCount").value(1));
        mockMvc.perform(get(OPD + "/desk/doctors/" + DOCTOR_ID + "/day-slots").with(as("FRONT_DESK")))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- reschedule

    private static AppointmentView moved() {
        return new AppointmentView(APPT_ID, UUID.randomUUID(), "Asha Rao", 30, "F", DOCTOR_ID, "Dr Rao",
                LocalDate.of(2026, 11, 25), LocalTime.of(12, 15), "BOOKED", "FOLLOW_UP", "FRONT_DESK", null, null, null, 4L);
    }

    private static final String RESCHEDULE_BODY = "{\"version\":3,\"doctorMembershipId\":\"" + DOCTOR_ID
            + "\",\"apptDate\":\"2026-11-25\",\"apptTime\":\"12:15\",\"tenantId\":\"" + UUID.randomUUID() + "\"}";

    @Test
    void frontDeskReschedulesAndTheTenantInTheBodyIsIgnored() throws Exception {
        when(appointmentService.reschedule(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(APPT_ID),
                any(AppointmentRescheduleInput.class))).thenReturn(moved());

        mockMvc.perform(post(OPD + "/appointments/" + APPT_ID + "/reschedule").with(as("FRONT_DESK")).contentType(JSON)
                        .content(RESCHEDULE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(APPT_ID.toString()))
                .andExpect(jsonPath("$.apptTime").value("12:15"))
                .andExpect(jsonPath("$.version").value(4));

        ArgumentCaptor<AppointmentRescheduleInput> input = ArgumentCaptor.forClass(AppointmentRescheduleInput.class);
        verify(appointmentService).reschedule(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(APPT_ID), input.capture());
        assertThat(input.getValue().version()).isEqualTo(3L);
        assertThat(input.getValue().doctorMembershipId()).isEqualTo(DOCTOR_ID);
        assertThat(input.getValue().apptTime()).isEqualTo("12:15");
    }

    @Test
    void aNurseCannotRescheduleAndGets403() throws Exception {
        mockMvc.perform(post(OPD + "/appointments/" + APPT_ID + "/reschedule").with(as("NURSE")).contentType(JSON)
                        .content(RESCHEDULE_BODY))
                .andExpect(status().isForbidden());
        verify(appointmentService, never()).reschedule(any(), any(), any(), any(), any());
    }

    @Test
    void rescheduleProblemTypesAreTheContractsSlugsWithTheirExtraFields() throws Exception {
        UUID saved = UUID.randomUUID();
        when(appointmentService.create(any(), any(), any(), any())).thenThrow(OpdProblemException.appointmentDuplicate(saved));
        when(appointmentService.reschedule(any(), any(), any(), any(), any()))
                .thenThrow(OpdProblemException.appointmentChanged());

        mockMvc.perform(post(OPD + "/appointments").with(as("OWNER")).contentType(JSON)
                        .content("{\"patientId\":\"" + UUID.randomUUID() + "\",\"doctorMembershipId\":\"" + DOCTOR_ID
                                + "\",\"apptDate\":\"2026-11-25\",\"apptTime\":\"12:00\",\"visitType\":\"NEW\","
                                + "\"source\":\"FRONT_DESK\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/appointment-duplicate"))
                .andExpect(jsonPath("$.appointmentId").value(saved.toString()))
                .andExpect(jsonPath("$.detail").value("This patient is already booked in this slot."));
        mockMvc.perform(post(OPD + "/appointments/" + APPT_ID + "/reschedule").with(as("OWNER")).contentType(JSON)
                        .content(RESCHEDULE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/appointment-changed"));
    }

    @Test
    void bookingClosedCarriesTheCutOffHours() throws Exception {
        when(appointmentService.create(any(), any(), any(), any())).thenThrow(OpdProblemException.bookingClosed(2));

        mockMvc.perform(post(OPD + "/appointments").with(as("OWNER")).contentType(JSON)
                        .content("{\"patientId\":\"" + UUID.randomUUID() + "\",\"doctorMembershipId\":\"" + DOCTOR_ID
                                + "\",\"apptDate\":\"2026-11-25\",\"apptTime\":\"12:00\",\"visitType\":\"NEW\","
                                + "\"source\":\"FRONT_DESK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/booking-closed"))
                .andExpect(jsonPath("$.closeBeforeHours").value(2))
                .andExpect(jsonPath("$.detail").value("Booking closes 2 hours before the slot."));
    }

    // ---------------------------------------------------------------- per-doctor cut-off

    private static final String SLOT_BODY_START = "{\"slotMinutes\":15,\"workingDays\":[1,2,3],\"walkInEveryNth\":0,"
            + "\"maxOnlinePerDay\":0,\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"13:00\"}]";

    private DoctorSlotSettingsInput savedInput(String body) throws Exception {
        when(doctorSlotSettingsService.save(any(), any(), any(), any(), any())).thenReturn(new DoctorSlotSettingsView(
                DOCTOR_ID, 15, List.of(1, 2, 3), 0, 0, List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))),
                4));
        mockMvc.perform(put(OPD + "/doctor-slots/" + DOCTOR_ID).with(as("FRONT_DESK")).contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeBeforeHours").value(4));
        ArgumentCaptor<DoctorSlotSettingsInput> captor = ArgumentCaptor.forClass(DoctorSlotSettingsInput.class);
        verify(doctorSlotSettingsService).save(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(DOCTOR_ID),
                captor.capture());
        return captor.getValue();
    }

    @Test
    void aBodyWithoutCloseBeforeHoursKeepsTheStoredValue() throws Exception {
        DoctorSlotSettingsInput input = savedInput(SLOT_BODY_START + "}");

        assertThat(input.closeBeforeSent()).isFalse();
    }

    @Test
    void aNullCloseBeforeHoursClearsIt() throws Exception {
        DoctorSlotSettingsInput input = savedInput(SLOT_BODY_START + ",\"closeBeforeHours\":null}");

        assertThat(input.closeBeforeSent()).isTrue();
        assertThat(input.closeBeforeHours()).isNull();
    }

    @Test
    void aNumberSetsCloseBeforeHoursAndADecimalIsPassedThroughForTheServiceToRefuse() throws Exception {
        DoctorSlotSettingsInput input = savedInput(SLOT_BODY_START + ",\"closeBeforeHours\":4}");

        assertThat(input.closeBeforeSent()).isTrue();
        assertThat(input.closeBeforeHours().intValue()).isEqualTo(4);
    }

    @Test
    void theSettingsAnswerAlwaysCarriesCloseBeforeHoursEvenWhenNull() throws Exception {
        when(doctorSlotSettingsService.listAll(TENANT_ID)).thenReturn(List.of(new DoctorSlotSettingsView(DOCTOR_ID, 15,
                List.of(1), 0, 0, List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))), null)));

        mockMvc.perform(get(OPD + "/doctor-slots").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].closeBeforeHours").hasJsonPath());
    }
}
