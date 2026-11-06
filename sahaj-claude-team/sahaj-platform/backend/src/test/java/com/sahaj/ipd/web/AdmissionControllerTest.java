package com.sahaj.ipd.web;

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

import com.sahaj.ipd.application.AdmissionInput;
import com.sahaj.ipd.application.AdmissionService;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.BedOption;
import com.sahaj.ipd.application.AdmissionViews.CategoryAdds;
import com.sahaj.ipd.application.AdmissionViews.ChargeService;
import com.sahaj.ipd.application.AdmissionViews.DepositAmounts;
import com.sahaj.ipd.application.AdmissionViews.DepositEntry;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.AdmissionViews.DoctorOption;
import com.sahaj.ipd.application.AdmissionViews.Options;
import com.sahaj.ipd.application.AdmissionViews.Rules;
import com.sahaj.ipd.application.AdmissionViews.RulesInput;
import com.sahaj.ipd.application.AdmissionViews.Summary;
import com.sahaj.ipd.application.EmergencyViews;
import com.sahaj.ipd.application.SafetyViews;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.application.RunningBill;
import com.sahaj.ipd.application.RunningBill.BillLine;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
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

/** SAH-23 admission endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = AdmissionController.class)
@Import(SecurityConfig.class)
class AdmissionControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID BED_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdmissionService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Rules rules() {
        return new Rules(new DepositAmounts(1_000_000L, 500_000L, 300_000L, 1_500_000L),
                new CategoryAdds(0L, 200_000L, 500_000L, 1_000_000L), true, 80, false);
    }

    private static Detail detail() {
        return new Detail(ADMISSION_ID, "IP/26-27/0042", PATIENT_ID, "Test Patient", 56, "M", "Dr Test", "101-A",
                "General", "General ward", "planned", "Self", 3, 1_000_000L, 1_000_000L, "OK",
                Instant.parse("2026-11-09T05:30:00Z"), "9000000001", "Chest pain",
                List.of(new DepositEntry(UUID.randomUUID(), 1_000_000L, "UPI", Instant.parse("2026-11-09T05:30:00Z"),
                        "Front Desk")), true, 330_000L, false, bill(), true, true,
                new TransferRules(true, "same"), false, "ADMITTED", null, true, null, null, true, false, null, true);
    }

    private static RunningBill bill() {
        return new RunningBill(2, "midnight", "General", new BigDecimal("1.00"),
                List.of(new BillLine("Bed · General", 80_000L, 2, 160_000L)), 330_000L, 1_000_000L, 33, 80);
    }

    private static String admitBody() {
        return "{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID
                + "\",\"admissionType\":\"planned\",\"bedId\":\"" + BED_ID + "\",\"reason\":\"Chest pain\","
                + "\"payer\":\"Self\",\"depositPaise\":1000000,\"depositMode\":\"UPI\",\"consentSigned\":true}";
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/admissions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/admissions").contentType(JSON).content(admitBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readersOfRulesAreOwnerFrontDeskNurseDoctorOnly() throws Exception {
        when(service.rules(TENANT_ID)).thenReturn(rules());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL + "/admission-rules").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.depositPaise.planned").value(1_000_000))
                    .andExpect(jsonPath("$.depositPaise.maternity").value(1_500_000))
                    .andExpect(jsonPath("$.categoryAddPaise.icu").value(1_000_000))
                    .andExpect(jsonPath("$.emergencyWithoutDeposit").value(true))
                    .andExpect(jsonPath("$.alertPercent").value(80))
                    .andExpect(jsonPath("$.confirmed").value(false));
        }
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(URL + "/admission-rules").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void onlyTheOwnerChangesAndConfirmsRules() throws Exception {
        when(service.updateRules(eq(TENANT_ID), eq(USER_ID), any())).thenReturn(rules());
        when(service.confirmRules(TENANT_ID, USER_ID)).thenReturn(rules());
        String body = "{\"depositPaise\":{\"planned\":1200000,\"emergency\":500000,\"daycare\":300000,"
                + "\"maternity\":1500000},\"categoryAddPaise\":{\"gen\":0,\"semi\":200000,\"pvt\":500000,"
                + "\"icu\":1000000},\"emergencyWithoutDeposit\":false,\"alertPercent\":90}";

        mockMvc.perform(put(URL + "/admission-rules").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk());
        ArgumentCaptor<RulesInput> sent = ArgumentCaptor.forClass(RulesInput.class);
        verify(service).updateRules(eq(TENANT_ID), eq(USER_ID), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().depositPaise().planned()).isEqualTo(1_200_000L);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().emergencyWithoutDeposit()).isFalse();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().alertPercent()).isEqualTo(90);
        mockMvc.perform(post(URL + "/admission-rules/confirm").with(as("OWNER"))).andExpect(status().isOk());

        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            mockMvc.perform(put(URL + "/admission-rules").with(as(role)).contentType(JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/admission-rules/confirm").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidRulesAnswer400WithFieldError() throws Exception {
        when(service.updateRules(any(), any(), any()))
                .thenThrow(new InvalidInputException("alertPercent", "Choose a deposit alert from 50% to 100%, in steps of 5"));

        mockMvc.perform(put(URL + "/admission-rules").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("alertPercent"));
    }

    @Test
    void optionsAreForOwnerFrontDeskAndNurseInContractShape() throws Exception {
        UUID wardBed = UUID.randomUUID();
        when(service.options(org.mockito.ArgumentMatchers.eq(TENANT_ID), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(new Options(
                List.of(new DoctorOption(DOCTOR_ID, "Dr Test")),
                List.of(new BedOption(wardBed, "101-A", "General ward", "gen", "General", 80_000L)), rules()));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            mockMvc.perform(get(URL + "/admission-options").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.doctors[0].membershipId").value(DOCTOR_ID.toString()))
                    .andExpect(jsonPath("$.doctors[0].name").value("Dr Test"))
                    .andExpect(jsonPath("$.beds[0].wardName").value("General ward"))
                    .andExpect(jsonPath("$.beds[0].categoryName").value("General"))
                    .andExpect(jsonPath("$.beds[0].ratePaise").value(80_000))
                    .andExpect(jsonPath("$.rules.alertPercent").value(80));
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING"}) {
            mockMvc.perform(get(URL + "/admission-options").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void listIsForOwnerFrontDeskNurseDoctorAndPassesRoleAndStatus() throws Exception {
        Summary row = new Summary(ADMISSION_ID, "IP/26-27/0042", PATIENT_ID, "Test Patient", 56, "M", "Dr Test",
                "101-A", "General", "General ward", "planned", "Self", 3, 1_000_000L, 1_000_000L, "OK",
                Instant.parse("2026-11-09T05:30:00Z"), 850_000L, true, false);
        when(service.list(TENANT_ID, USER_ID, "DOCTOR", "ADMITTED")).thenReturn(new AdmissionList(4, 1, 3, false, 1,
                80, 0, 0, new EmergencyViews.Counts(true, 2, 1),
                new SafetyViews.Banner(new SafetyViews.Counts(1, 1, "AAAAAAAAAAAAAAAAAAAAAA", Instant.parse("2026-11-09T06:00:00Z")),
                        List.of(new SafetyViews.BannerItem(UUID.randomUUID(), ADMISSION_ID, "Test Patient", 8))),
                List.of(row)));

        mockMvc.perform(get(URL + "/admissions?status=ADMITTED").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedsTotal").value(4))
                .andExpect(jsonPath("$.bedsOccupied").value(1))
                .andExpect(jsonPath("$.bedsAvailable").value(3))
                .andExpect(jsonPath("$.canAdmit").value(false))
                .andExpect(jsonPath("$.safety.counts.open").value(1))
                .andExpect(jsonPath("$.safety.counts.pastTarget").value(1))
                .andExpect(jsonPath("$.safety.pastTarget[0].ewsScore").value(8))
                .andExpect(jsonPath("$.safety.pastTarget[0].patientName").value("Test Patient"))
                .andExpect(jsonPath("$.items[0].ipNumber").value("IP/26-27/0042"))
                .andExpect(jsonPath("$.items[0].depositState").value("OK"))
                .andExpect(jsonPath("$.safety.counts.open").value(1))
                .andExpect(jsonPath("$.safety.pastTarget[0].ewsScore").value(8))
                .andExpect(jsonPath("$.items[0].dayNumber").value(3))
                .andExpect(jsonPath("$.items[0].runningBillPaise").value(850000))
                .andExpect(jsonPath("$.depositAlerts").value(1))
                .andExpect(jsonPath("$.alertPercent").value(80))
                .andExpect(jsonPath("$.items[0].runningBillPaise").value(850000))
                .andExpect(jsonPath("$.items[0].depositLow").value(true))
                .andExpect(jsonPath("$.depositAlerts").value(1))
                .andExpect(jsonPath("$.alertPercent").value(80))
                .andExpect(jsonPath("$.items[0].reason").doesNotExist());
        mockMvc.perform(get(URL + "/admissions").with(as("BILLING"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/admissions").with(as("VISITING_DOCTOR"))).andExpect(status().isForbidden());
    }

    @Test
    void badStatusFilterAnswers400() throws Exception {
        when(service.list(any(), any(), any(), eq("DISCHARGED")))
                .thenThrow(new InvalidInputException("status", "Only the Admitted list is available"));

        mockMvc.perform(get(URL + "/admissions?status=DISCHARGED").with(as("OWNER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admitAnswers201WithDetailAndOnlyAdmittingRolesMay() throws Exception {
        when(service.admit(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), any())).thenReturn(detail());

        mockMvc.perform(post(URL + "/admissions").with(as("NURSE")).contentType(JSON).content(admitBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.ipNumber").value("IP/26-27/0042"))
                .andExpect(jsonPath("$.mobile").value("9000000001"))
                .andExpect(jsonPath("$.reason").value("Chest pain"))
                .andExpect(jsonPath("$.deposits[0].collectedByName").value("Front Desk"))
                .andExpect(jsonPath("$.canCollectDeposit").value(true))
                .andExpect(jsonPath("$.canTransfer").value(true))
                .andExpect(jsonPath("$.canOpenEmr").value(true))
                .andExpect(jsonPath("$.transferRules.upgradeNeedsOwner").value(true))
                .andExpect(jsonPath("$.transferRules.newRateFrom").value("same"))
                .andExpect(jsonPath("$.bill.lines[0].segment").value(false));
        ArgumentCaptor<AdmissionInput> sent = ArgumentCaptor.forClass(AdmissionInput.class);
        verify(service).admit(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().patientId()).isEqualTo(PATIENT_ID);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().depositPaise()).isEqualTo(1_000_000L);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().consentSigned()).isTrue();

        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(post(URL + "/admissions").with(as(role)).contentType(JSON).content(admitBody()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void admitProblemsMapToTheirStatusAndType() throws Exception {
        when(service.admit(any(), any(), any(), any()))
                .thenThrow(new NotFoundException("Patient not found"))
                .thenThrow(new ConflictException("already-admitted", "Already admitted",
                        "This patient is already admitted."))
                .thenThrow(new ConflictException("bed-not-free", "Bed is not free",
                        "That bed is no longer free. Pick another."))
                .thenThrow(new InvalidInputException("consentSigned", "Record the admission consent first"))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only",
                        "IPD is read-only. Nothing can be changed."))
                .thenThrow(new ModuleOffException("off"));

        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("already-admitted")))
                .andExpect(jsonPath("$.detail").value("This patient is already admitted."));
        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bed-not-free")));
        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Record the admission consent first"));
        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
        mockMvc.perform(post(URL + "/admissions").with(as("OWNER")).contentType(JSON).content(admitBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void detailIsReadByTheFourReadingRolesAndUnknownIdIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(service.detail(eq(TENANT_ID), eq(USER_ID), any(), eq(ADMISSION_ID))).thenReturn(detail());
        when(service.detail(eq(TENANT_ID), eq(USER_ID), any(), eq(unknown))).thenThrow(new NotFoundException("Admission not found"));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patientName").value("Test Patient"));
        }
        mockMvc.perform(get(URL + "/admissions/" + unknown).with(as("OWNER"))).andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID).with(as("BILLING"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/admissions/not-a-uuid").with(as("OWNER"))).andExpect(status().isBadRequest());
    }

    @Test
    void depositsAreForOwnerAndFrontDeskOnly() throws Exception {
        when(service.addDeposit(eq(TENANT_ID), eq(USER_ID), any(), eq(ADMISSION_ID), eq(500_000L), eq("Cash")))
                .thenReturn(detail());

        for (String role : new String[] {"OWNER", "FRONT_DESK"}) {
            mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/deposits").with(as(role)).contentType(JSON)
                            .content("{\"amountPaise\":500000,\"mode\":\"Cash\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ipNumber").value("IP/26-27/0042"));
        }
        for (String role : new String[] {"NURSE", "DOCTOR", "BILLING"}) {
            mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/deposits").with(as(role)).contentType(JSON)
                            .content("{\"amountPaise\":500000,\"mode\":\"Cash\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).addDeposit(any(), any(), eq("NURSE"), any(), any(), any());
    }

    @Test
    void depositProblemsMapToTheirStatus() throws Exception {
        when(service.addDeposit(any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("amountPaise", "Enter whole rupees, at least ₹1 and at most ₹10,00,000"))
                .thenThrow(new NotFoundException("Admission not found"))
                .thenThrow(new ConflictException("not-admitted", "Patient is not admitted", "x"));
        String url = URL + "/admissions/" + ADMISSION_ID + "/deposits";
        String body = "{\"amountPaise\":50,\"mode\":\"Cash\"}";

        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body)).andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body)).andExpect(status().isNotFound());
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void chargeServicesAreReadByTheFourReadingRoles() throws Exception {
        when(service.chargeServices(TENANT_ID))
                .thenReturn(List.of(new ChargeService("visit", "Extra doctor visit", 50_000L)));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL + "/charge-services").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].key").value("visit"))
                    .andExpect(jsonPath("$[0].name").value("Extra doctor visit"))
                    .andExpect(jsonPath("$[0].basePaise").value(50000));
        }
        mockMvc.perform(get(URL + "/charge-services").with(as("BILLING"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/charge-services")).andExpect(status().isUnauthorized());
    }

    @Test
    void chargeServicesInModuleOffIs403() throws Exception {
        when(service.chargeServices(TENANT_ID)).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(get(URL + "/charge-services").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void addingAChargeIsForOwnerFrontDeskAndNurseAndReturnsTheBill() throws Exception {
        when(service.addCharge(eq(TENANT_ID), eq(USER_ID), any(), eq(ADMISSION_ID), eq("cbc"))).thenReturn(detail());
        String url = URL + "/admissions/" + ADMISSION_ID + "/charges";

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            mockMvc.perform(post(url).with(as(role)).contentType(JSON).content("{\"serviceKey\":\"cbc\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ipNumber").value("IP/26-27/0042"))
                    .andExpect(jsonPath("$.runningBillPaise").value(330000))
                    .andExpect(jsonPath("$.depositLow").value(false))
                    .andExpect(jsonPath("$.canAddCharge").value(true))
                    .andExpect(jsonPath("$.bill.billDays").value(2))
                    .andExpect(jsonPath("$.bill.bedDayCut").value("midnight"))
                    .andExpect(jsonPath("$.bill.totalPaise").value(330000))
                    .andExpect(jsonPath("$.bill.paidPaise").value(1000000))
                    .andExpect(jsonPath("$.bill.depositPercent").value(33))
                    .andExpect(jsonPath("$.bill.alertPercent").value(80))
                    .andExpect(jsonPath("$.bill.lines[0].name").value("Bed · General"))
                    .andExpect(jsonPath("$.bill.lines[0].amountPaise").value(160000));
        }
        for (String role : new String[] {"DOCTOR", "BILLING", "VISITING_DOCTOR"}) {
            mockMvc.perform(post(url).with(as(role)).contentType(JSON).content("{\"serviceKey\":\"cbc\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post(url).contentType(JSON).content("{\"serviceKey\":\"cbc\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).addCharge(any(), any(), eq("DOCTOR"), any(), any());
    }

    @Test
    void chargeProblemsMapToTheirStatus() throws Exception {
        when(service.addCharge(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("serviceKey", "Choose a service from the list"))
                .thenThrow(new NotFoundException("Admission not found"))
                .thenThrow(new ConflictException("not-admitted", "Patient is not admitted", "x"))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only", "x"))
                .thenThrow(new ModuleOffException("off"));
        String url = URL + "/admissions/" + ADMISSION_ID + "/charges";
        String body = "{\"serviceKey\":\"nope\"}";

        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("not-admitted")));
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
        mockMvc.perform(post(url).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
