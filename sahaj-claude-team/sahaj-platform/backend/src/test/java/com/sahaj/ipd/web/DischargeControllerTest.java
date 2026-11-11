package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.DischargeService;
import com.sahaj.ipd.application.DischargeViews.DischargeInput;
import com.sahaj.ipd.application.DischargeViews.DischargeRecord;
import com.sahaj.ipd.application.DischargeViews.FinalBill;
import com.sahaj.ipd.application.DischargeViews.OpdFollowUpRule;
import com.sahaj.ipd.application.DischargeViews.Preview;
import com.sahaj.ipd.application.DischargeViews.Settings;
import com.sahaj.ipd.application.DischargeViews.SettingsInput;
import com.sahaj.ipd.application.DischargeViews.Sheet;
import com.sahaj.ipd.application.DischargeViews.SheetBill;
import com.sahaj.ipd.application.DischargeViews.Signature;
import com.sahaj.ipd.application.DischargeViews.SignoffInput;
import com.sahaj.ipd.application.DischargeViews.TemplateOption;
import com.sahaj.ipd.application.DischargeViews.TypeOption;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.application.RunningBill;
import com.sahaj.ipd.application.RunningBill.BillLine;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-27 discharge endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = DischargeController.class)
@Import(SecurityConfig.class)
class DischargeControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String SHEET_URL = URL + "/admissions/" + ADMISSION_ID + "/discharge";
    private static final String SIGNOFF_URL = SHEET_URL + "/signoff";
    private static final String SETTINGS_URL = URL + "/discharge-settings";
    private static final List<TypeOption> TYPES = List.of(new TypeOption("normal", "Normal"),
            new TypeOption("lama", "On request (LAMA)"), new TypeOption("referred", "Referred"),
            new TypeOption("death", "Death"));
    private static final List<TemplateOption> TEMPLATES = List.of(new TemplateOption("general", "General medicine"),
            new TemplateOption("surgical", "Surgical"), new TemplateOption("maternity", "Maternity"),
            new TemplateOption("daycare", "Day-care"));

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DischargeService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Settings settings() {
        return new Settings(null, true, 7, TYPES, TEMPLATES, new OpdFollowUpRule(6, "free"), true);
    }

    private static Sheet sheet() {
        return new Sheet(ADMISSION_ID, "IP/26-27/0042", "Test Patient", "101-A", 3, true, TYPES, TEMPLATES, "normal",
                "general", new Signature("normal", "general", "Dr Signer", Instant.parse("2026-11-11T04:00:00Z")),
                true, false, false, new Preview("Chest pain", 3, "101-A", "Dr Asha Rao", "Dr Asha", null),
                new SheetBill(480_000L, 1_000_000L, -520_000L), 7, LocalDate.of(2026, 11, 18), true, true);
    }

    private static Detail discharged() {
        RunningBill bill = new RunningBill(3, "midnight", "General", new BigDecimal("1.00"),
                List.of(new BillLine("Bed · General", 80_000L, 3, 240_000L)), 480_000L, 1_000_000L, 48, 80);
        Instant at = Instant.parse("2026-11-11T05:30:00Z");
        return new Detail(ADMISSION_ID, "IP/26-27/0042", PATIENT_ID, "Test Patient", 56, "M", "Dr Test", "101-A",
                "General", "General ward", "planned", "Self", 3, 1_000_000L, 1_000_000L, "OK",
                Instant.parse("2026-11-09T05:30:00Z"), "9000000001", "Chest pain", List.of(), false, 480_000L, false,
                bill, false, false, new TransferRules(true, "same"), false, "DISCHARGED", at, false,
                new DischargeRecord("normal", "general", "Dr Signer", at.minusSeconds(60), "Front Desk", at, 7,
                        LocalDate.of(2026, 11, 18)),
                new FinalBill(List.of(new BillLine("Bed · General", 80_000L, 3, 240_000L)), 3, 480_000L, 1_000_000L,
                        -520_000L, "refunded", "Cash", "Front Desk", at), true, false, null, false, null);
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(SETTINGS_URL).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SHEET_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(SIGNOFF_URL).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SHEET_URL).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    // ---- Discharge setup ----

    @Test
    void theFourIpdRolesReadTheSettingsInContractShapeAndOthersAreRefused() throws Exception {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            when(service.settings(TENANT_ID, role)).thenReturn(settings());
            mockMvc.perform(get(SETTINGS_URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.templateKey").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$.approvalChain").value(true))
                    .andExpect(jsonPath("$.followUpDays").value(7))
                    .andExpect(jsonPath("$.types[0].key").value("normal"))
                    .andExpect(jsonPath("$.types[1].label").value("On request (LAMA)"))
                    .andExpect(jsonPath("$.types.length()").value(4))
                    .andExpect(jsonPath("$.templates[3].label").value("Day-care"))
                    .andExpect(jsonPath("$.opdFollowUpRule.days").value(6))
                    .andExpect(jsonPath("$.opdFollowUpRule.fee").value("free"))
                    .andExpect(jsonPath("$.canEdit").value(true));
        }
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(SETTINGS_URL).with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void onlyTheOwnerSavesTheSettingsAndTheBodyReachesTheService() throws Exception {
        when(service.updateSettings(eq(TENANT_ID), eq(USER_ID), any())).thenReturn(settings());
        String body = "{\"templateKey\":\"surgical\",\"approvalChain\":false,\"followUpDays\":10}";

        mockMvc.perform(put(SETTINGS_URL).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk());
        ArgumentCaptor<SettingsInput> sent = ArgumentCaptor.forClass(SettingsInput.class);
        verify(service).updateSettings(eq(TENANT_ID), eq(USER_ID), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new SettingsInput("surgical", false, 10));

        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            mockMvc.perform(put(SETTINGS_URL).with(as(role)).contentType(JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aMissingTemplateIsSentAsNullAndBadSettingsAnswer400WithTheField() throws Exception {
        when(service.updateSettings(eq(TENANT_ID), eq(USER_ID), any()))
                .thenThrow(new InvalidInputException("followUpDays", "Enter 1 to 30 days"));

        mockMvc.perform(put(SETTINGS_URL).with(as("OWNER")).contentType(JSON)
                        .content("{\"approvalChain\":true,\"followUpDays\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("followUpDays"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter 1 to 30 days"));
        ArgumentCaptor<SettingsInput> sent = ArgumentCaptor.forClass(SettingsInput.class);
        verify(service).updateSettings(eq(TENANT_ID), eq(USER_ID), sent.capture());
        assertThat(sent.getValue().templateKey()).isNull();
    }

    @Test
    void settingsAreRefusedWhenIpdIsOffOrReadOnly() throws Exception {
        when(service.settings(TENANT_ID, "OWNER")).thenThrow(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."));
        when(service.updateSettings(eq(TENANT_ID), eq(USER_ID), any())).thenThrow(new ConflictException(
                "ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed."));

        mockMvc.perform(get(SETTINGS_URL).with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
        mockMvc.perform(put(SETTINGS_URL).with(as("OWNER")).contentType(JSON)
                        .content("{\"approvalChain\":true,\"followUpDays\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
    }

    // ---- the sheet ----

    @Test
    void theFourIpdRolesOpenTheSheetInContractShape() throws Exception {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            when(service.sheet(TENANT_ID, USER_ID, role, ADMISSION_ID)).thenReturn(sheet());
            mockMvc.perform(get(SHEET_URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                    .andExpect(jsonPath("$.ipNumber").value("IP/26-27/0042"))
                    .andExpect(jsonPath("$.patientName").value("Test Patient"))
                    .andExpect(jsonPath("$.bedCode").value("101-A"))
                    .andExpect(jsonPath("$.dayNumber").value(3))
                    .andExpect(jsonPath("$.approvalChain").value(true))
                    .andExpect(jsonPath("$.defaultType").value("normal"))
                    .andExpect(jsonPath("$.defaultTemplateKey").value("general"))
                    .andExpect(jsonPath("$.signature.signedByName").value("Dr Signer"))
                    .andExpect(jsonPath("$.canSign").value(true))
                    .andExpect(jsonPath("$.canDischarge").value(false))
                    .andExpect(jsonPath("$.canRefund").value(false))
                    .andExpect(jsonPath("$.preview.diagnosis").value("Chest pain"))
                    .andExpect(jsonPath("$.preview.stayDays").value(3))
                    .andExpect(jsonPath("$.preview.doctorShortName").value("Dr Asha"))
                    .andExpect(jsonPath("$.preview.doctorSpecialty").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$.bill.totalPaise").value(480000))
                    .andExpect(jsonPath("$.bill.depositsPaise").value(1000000))
                    .andExpect(jsonPath("$.bill.balancePaise").value(-520000))
                    .andExpect(jsonPath("$.followUpDays").value(7))
                    .andExpect(jsonPath("$.openEscalation").value(true))
                    .andExpect(jsonPath("$.signoffBeforeEscalation").value(true))
                    .andExpect(jsonPath("$.followUpDate").value("2026-11-18"));
        }
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(SHEET_URL).with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theSheetAnswers404And409NotAdmitted() throws Exception {
        when(service.sheet(TENANT_ID, USER_ID, "OWNER", ADMISSION_ID)).thenThrow(new NotFoundException("Admission not found"));
        mockMvc.perform(get(SHEET_URL).with(as("OWNER"))).andExpect(status().isNotFound());

        org.mockito.Mockito.doThrow(new ConflictException("not-admitted", "Patient is not admitted",
                "This patient is not admitted any more.")).when(service).sheet(TENANT_ID, USER_ID, "OWNER", ADMISSION_ID);
        mockMvc.perform(get(SHEET_URL).with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("not-admitted")));
    }

    // ---- the sign-off ----

    @Test
    void onlyADoctorOrTheOwnerSignsAndTheBodyReachesTheService() throws Exception {
        for (String role : new String[] {"OWNER", "DOCTOR"}) {
            when(service.signoff(eq(TENANT_ID), eq(USER_ID), eq(role), eq(ADMISSION_ID), any())).thenReturn(sheet());
            mockMvc.perform(put(SIGNOFF_URL).with(as(role)).contentType(JSON)
                            .content("{\"type\":\"lama\",\"templateKey\":\"surgical\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.signature.type").value("normal"));
        }
        ArgumentCaptor<SignoffInput> sent = ArgumentCaptor.forClass(SignoffInput.class);
        verify(service).signoff(eq(TENANT_ID), eq(USER_ID), eq("DOCTOR"), eq(ADMISSION_ID), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new SignoffInput("lama", "surgical"));
        for (String role : new String[] {"FRONT_DESK", "NURSE", "VISITING_DOCTOR", "BILLING"}) {
            mockMvc.perform(put(SIGNOFF_URL).with(as(role)).contentType(JSON)
                            .content("{\"type\":\"normal\",\"templateKey\":\"general\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aBadSignOffBodyIs400() throws Exception {
        when(service.signoff(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("type", "Choose a discharge type"));

        mockMvc.perform(put(SIGNOFF_URL).with(as("DOCTOR")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("type"));
    }

    // ---- the discharge ----

    @Test
    void ownerAndFrontDeskDischargeAndTheAnswerIsTheDischargedAdmission() throws Exception {
        for (String role : new String[] {"OWNER", "FRONT_DESK"}) {
            when(service.discharge(eq(TENANT_ID), eq(USER_ID), eq(role), eq(ADMISSION_ID), any()))
                    .thenReturn(discharged());
            mockMvc.perform(post(SHEET_URL).with(as(role)).contentType(JSON)
                            .content("{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":480000,"
                                    + "\"expectedDepositsPaise\":1000000,\"mode\":\"UPI\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCHARGED"))
                    .andExpect(jsonPath("$.dischargedAt").value("2026-11-11T05:30:00Z"))
                    .andExpect(jsonPath("$.canStartDischarge").value(false))
                    .andExpect(jsonPath("$.canCollectDeposit").value(false))
                    .andExpect(jsonPath("$.canAddCharge").value(false))
                    .andExpect(jsonPath("$.canTransfer").value(false))
                    .andExpect(jsonPath("$.goingHomeToday").value(false))
                    .andExpect(jsonPath("$.discharge.type").value("normal"))
                    .andExpect(jsonPath("$.discharge.signedByName").value("Dr Signer"))
                    .andExpect(jsonPath("$.discharge.dischargedByName").value("Front Desk"))
                    .andExpect(jsonPath("$.discharge.followUpDays").value(7))
                    .andExpect(jsonPath("$.discharge.followUpDate").value("2026-11-18"))
                    .andExpect(jsonPath("$.finalBill.totalPaise").value(480000))
                    .andExpect(jsonPath("$.finalBill.balancePaise").value(-520000))
                    .andExpect(jsonPath("$.finalBill.outcome").value("refunded"))
                    .andExpect(jsonPath("$.finalBill.mode").value("Cash"))
                    .andExpect(jsonPath("$.finalBill.settledByName").value("Front Desk"))
                    .andExpect(jsonPath("$.finalBill.lines[0].name").value("Bed · General"))
                    .andExpect(jsonPath("$.finalBill.billDays").value(3));
        }
        ArgumentCaptor<DischargeInput> sent = ArgumentCaptor.forClass(DischargeInput.class);
        verify(service).discharge(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(ADMISSION_ID), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new DischargeInput("normal", "general", 480_000L, 1_000_000L, "UPI"));
    }

    @Test
    void nurseDoctorAndOtherRolesCannotDischarge() throws Exception {
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST"}) {
            mockMvc.perform(post(SHEET_URL).with(as(role)).contentType(JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).discharge(any(), any(), any(), any(), any());
    }

    @Test
    void theDischargeProblemsHaveTheContractStatusAndType() throws Exception {
        String body = "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":480000,"
                + "\"expectedDepositsPaise\":1000000}";
        assertProblem(new NotFoundException("Admission not found"), 404, null, body);
        assertProblem(new ConflictException("not-admitted", "Patient is not admitted", "x"), 409, "not-admitted", body);
        assertProblem(new ConflictException("ipd-readonly", "IPD is read-only", "x"), 409, "ipd-readonly", body);
        assertProblem(new ConflictException("summary-not-signed", "Summary is not signed",
                "The doctor signs the summary first"), 409, "summary-not-signed", body);
        assertProblem(new ConflictException("bill-changed", "The bill changed",
                "The bill changed. Review the final bill and try again."), 409, "bill-changed", body);
        assertProblem(new ForbiddenException("refund-needs-owner", "Refund needs the owner",
                "Only the owner can refund a deposit."), 403, "refund-needs-owner", body);
        assertProblem(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."), 403,
                "module-off", body);
        assertProblem(new InvalidInputException("mode", "Choose UPI, Cash or Card"), 400, "validation", body);
    }

    private void assertProblem(RuntimeException error, int expectedStatus, String type, String body) throws Exception {
        org.mockito.Mockito.reset(service);
        when(service.discharge(any(), any(), any(), any(), any())).thenThrow(error);
        var result = mockMvc.perform(post(SHEET_URL).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().is(expectedStatus));
        if (type != null) {
            result.andExpect(jsonPath("$.type").value(Matchers.endsWith(type)));
        }
    }
}
