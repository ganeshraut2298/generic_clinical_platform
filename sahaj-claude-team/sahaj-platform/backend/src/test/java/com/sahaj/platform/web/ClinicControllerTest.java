package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.ClinicProfileInput;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import com.sahaj.platform.application.ClinicProfileService;
import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import com.sahaj.platform.application.HolidayService;
import com.sahaj.platform.application.SetupOverviewService;
import com.sahaj.platform.application.SetupOverviewService.AuditEventView;
import com.sahaj.platform.application.SetupOverviewService.Check;
import com.sahaj.platform.application.SetupOverviewService.Overview;
import com.sahaj.platform.application.SpecialtyService;
import com.sahaj.platform.application.SpecialtyService.SpecialtyView;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-7 platform endpoints: contract shape, owner-only changes (403 otherwise) and the problem responses. */
@WebMvcTest(controllers = {ClinicController.class, SetupOverviewController.class})
@Import(SecurityConfig.class)
class ClinicControllerTest {

    private static final String BASE = "/api/v1/platform";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOLIDAY_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClinicProfileService profileService;

    @MockBean
    private HolidayService holidayService;

    @MockBean
    private SpecialtyService specialtyService;

    @MockBean
    private SetupOverviewService overviewService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static ProfileRow aProfile() {
        return new ProfileRow("Sai Family Clinic", "श्री साई क्लिनिक", "12 MG Road", "Pune", "411001", "9876543210",
                "9876543210", "https://maps.app.goo.gl/abc", "MH-123", "sai-clinic", "gp", "custom", "#1f5bc8");
    }

    @Test
    void anyClinicRoleReadsTheProfileWithAreaAndTheReadOnlySlug() throws Exception {
        when(profileService.get(TENANT_ID)).thenReturn(aProfile());

        mockMvc.perform(get(BASE + "/clinic/profile").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sai Family Clinic"))
                .andExpect(jsonPath("$.area").value("Pune"))
                .andExpect(jsonPath("$.city").value("Pune"))
                .andExpect(jsonPath("$.slug").value("sai-clinic"))
                .andExpect(jsonPath("$.specialtyCode").value("gp"))
                .andExpect(jsonPath("$.brandHex").value("#1f5bc8"))
                .andExpect(jsonPath("$.nameLocal").value("श्री साई क्लिनिक"));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE + "/clinic/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/setup/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonOwnerGets403OnEveryChangeAndOnTheOwnerOnlyReads() throws Exception {
        String json = MediaType.APPLICATION_JSON_VALUE;
        mockMvc.perform(put(BASE + "/clinic/profile").with(as("DOCTOR")).contentType(json).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/clinic/holidays").with(as("FRONT_DESK")).contentType(json)
                .content("{\"date\":\"2026-11-08\",\"name\":\"Diwali\",\"type\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/clinic/holidays/" + HOLIDAY_ID).with(as("NURSE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/clinic/specialty").with(as("BILLING")).contentType(json).content("{\"code\":\"gp\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/setup/overview").with(as("DOCTOR"))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/audit-events").with(as("FRONT_DESK"))).andExpect(status().isForbidden());

        verify(profileService, never()).save(any(), any(), any());
        verify(holidayService, never()).add(any(), any(), any(), any(), any());
        verify(specialtyService, never()).apply(any(), any(), any());
    }

    @Test
    void theOwnerSavesTheProfileFromTheSignedInClinicOnly() throws Exception {
        when(profileService.save(eq(TENANT_ID), eq(USER_ID), any(ClinicProfileInput.class))).thenReturn(aProfile());

        mockMvc.perform(put(BASE + "/clinic/profile").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sai Family Clinic\",\"slug\":\"hacked\",\"tenantId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sai-clinic"));
        verify(profileService).save(eq(TENANT_ID), eq(USER_ID), any(ClinicProfileInput.class));
    }

    @Test
    void aBrokenFieldIsA400ProblemWithAFieldError() throws Exception {
        when(profileService.save(any(), any(), any()))
                .thenThrow(new InvalidInputException("mapsUrl", "Paste a link that starts with https://"));

        mockMvc.perform(put(BASE + "/clinic/profile").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"mapsUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Paste a link that starts with https://"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("mapsUrl"));
    }

    @Test
    void holidaysListAddAndRemove() throws Exception {
        HolidayRow row = new HolidayRow(HOLIDAY_ID, LocalDate.of(2026, 11, 8), "Diwali", "CLOSED");
        when(holidayService.list(TENANT_ID)).thenReturn(List.of(row));
        when(holidayService.add(TENANT_ID, USER_ID, LocalDate.of(2026, 11, 8), "Diwali", "CLOSED")).thenReturn(row);

        mockMvc.perform(get(BASE + "/clinic/holidays").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(HOLIDAY_ID.toString()))
                .andExpect(jsonPath("$[0].date").value("2026-11-08"))
                .andExpect(jsonPath("$[0].type").value("CLOSED"));
        mockMvc.perform(post(BASE + "/clinic/holidays").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-08\",\"name\":\"Diwali\",\"type\":\"CLOSED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(HOLIDAY_ID.toString()));
        mockMvc.perform(delete(BASE + "/clinic/holidays/" + HOLIDAY_ID).with(as("OWNER")))
                .andExpect(status().isNoContent());
        verify(holidayService).remove(TENANT_ID, USER_ID, HOLIDAY_ID);
    }

    @Test
    void aRepeatedHolidayDateIs409AndAnUnknownHolidayIs404() throws Exception {
        when(holidayService.add(any(), any(), any(), any(), any())).thenThrow(new ConflictException(
                "holiday-date-taken", "That date already has a holiday", "A holiday is already set for that date."));
        mockMvc.perform(post(BASE + "/clinic/holidays").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-08\",\"name\":\"Diwali\",\"type\":\"CLOSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/holiday-date-taken"))
                .andExpect(jsonPath("$.detail").value("A holiday is already set for that date."));

        org.mockito.Mockito.doThrow(new NotFoundException("x")).when(holidayService)
                .remove(TENANT_ID, USER_ID, HOLIDAY_ID);
        mockMvc.perform(delete(BASE + "/clinic/holidays/" + HOLIDAY_ID).with(as("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void specialtiesAreReadableByAnyoneAndApplyingReturnsTheProfile() throws Exception {
        when(specialtyService.list()).thenReturn(List.of(new SpecialtyView("dental", "Dental", List.of("Tooth chart"), 1),
                new SpecialtyView("ayur", "Ayurveda", List.of(), 2)));
        when(specialtyService.apply(TENANT_ID, USER_ID, "dental")).thenReturn(aProfile());

        mockMvc.perform(get(BASE + "/specialties").with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("dental"))
                .andExpect(jsonPath("$[0].features[0]").value("Tooth chart"))
                .andExpect(jsonPath("$[1].phase").value(2));
        mockMvc.perform(put(BASE + "/clinic/specialty").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"dental\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sai Family Clinic"));
    }

    @Test
    void applyingAPhase2TemplateIs400() throws Exception {
        when(specialtyService.apply(any(), any(), eq("ayur")))
                .thenThrow(new InvalidInputException("code", "That template is not available yet"));

        mockMvc.perform(put(BASE + "/clinic/specialty").with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ayur\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
    }

    @Test
    void theOverviewShowsPercentChecklistAndLastChange() throws Exception {
        AuditEventView last = new AuditEventView(UUID.randomUUID(), "Asha", "SERVICE_ADDED", "SERVICE", null,
                Map.of("name", "Dressing"), Instant.parse("2026-11-01T10:00:00Z"));
        when(overviewService.overview(TENANT_ID, USER_ID)).thenReturn(new Overview(83,
                List.of(new Check("profile", "Clinic name, address and phone", "Printed on every case paper", true, false),
                        new Check("holidays", "Holidays for the coming month", "Patients are told", false, false)),
                last));

        mockMvc.perform(get(BASE + "/setup/overview").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(83))
                .andExpect(jsonPath("$.checks[0].key").value("profile"))
                .andExpect(jsonPath("$.checks[0].done").value(true))
                .andExpect(jsonPath("$.checks[1].done").value(false))
                .andExpect(jsonPath("$.lastChange.action").value("SERVICE_ADDED"))
                .andExpect(jsonPath("$.lastChange.actorName").value("Asha"))
                .andExpect(jsonPath("$.lastChange.after.name").value("Dressing"))
                .andExpect(jsonPath("$.lastChange.at").value("2026-11-01T10:00:00Z"));
    }

    @Test
    void auditEventsPassTheLimitAndAnOutOfRangeLimitIs400() throws Exception {
        when(overviewService.auditEvents(TENANT_ID, USER_ID, 5)).thenReturn(List.of());
        when(overviewService.auditEvents(TENANT_ID, USER_ID, 500))
                .thenThrow(new InvalidInputException("limit", "Choose a number from 1 to 100"));

        mockMvc.perform(get(BASE + "/audit-events").param("limit", "5").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get(BASE + "/audit-events").param("limit", "500").with(as("OWNER")))
                .andExpect(status().isBadRequest());
    }
}
