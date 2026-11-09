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
import com.sahaj.ipd.application.AdmissionViews.AdmittedCardAbilities;
import com.sahaj.ipd.application.AdmissionViews.AdmittedClinicalFlags;
import com.sahaj.ipd.application.AdmissionViews.AdmittedPatientRow;
import com.sahaj.ipd.application.AdmissionViews.CardAllergy;
import com.sahaj.ipd.application.AdmissionViews.CardEscalationFlag;
import com.sahaj.ipd.application.AdmissionViews.WardOption;
import com.sahaj.ipd.application.VitalsViews.EwsSummary;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.ipd.domain.card.ResolvedCardSetup;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
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
import java.util.Optional;
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
    private static final UUID WARD_ID = UUID.randomUUID();
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
        when(service.list(TENANT_ID, USER_ID, "DOCTOR", "ADMITTED", null)).thenReturn(new AdmissionList(4, 1, 3, false, 1,
                80, 0, 0, new EmergencyViews.Counts(true, 2, 1),
                new SafetyViews.Banner(new SafetyViews.Counts(1, 1, 2, 1, "AAAAAAAAAAAAAAAAAAAAAA", Instant.parse("2026-11-09T06:00:00Z")),
                        List.of(new SafetyViews.BannerItem(UUID.randomUUID(), ADMISSION_ID, "Test Patient", 8))),
                Instant.parse("2026-11-09T06:00:00Z"), List.of(new WardOption(WARD_ID, "General ward")),
                abilities(true, true, false, true, false, false, "patient", "clinical", "vitals"), STANDARD,
                List.of(AdmittedPatientRow.of(row, "UH-0001", WARD_ID, "Room 1", flags()))));

        mockMvc.perform(get(URL + "/admissions?status=ADMITTED").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedsTotal").value(4))
                .andExpect(jsonPath("$.bedsOccupied").value(1))
                .andExpect(jsonPath("$.bedsAvailable").value(3))
                .andExpect(jsonPath("$.canAdmit").value(false))
                .andExpect(jsonPath("$.safety.counts.open").value(1))
                .andExpect(jsonPath("$.safety.counts.pastTarget").value(1))
                .andExpect(jsonPath("$.safety.counts.dosesDue").value(2))
                .andExpect(jsonPath("$.safety.counts.dosesLate").value(1))
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

    private static AdmittedClinicalFlags flags() {
        return new AdmittedClinicalFlags(new CardEscalationFlag(EscalationState.Status.OPEN, 12, 15, false),
                new EwsSummary(8, Risk.HIGH, Instant.parse("2026-11-09T05:00:00Z")), true,
                new CardAllergy(AllergyStatus.RECORDED, "Penicillin"));
    }

    private static Summary summary() {
        return new Summary(ADMISSION_ID, "IP/26-27/0042", PATIENT_ID, "Test Patient", 56, "M", "Dr Test", "101-A",
                "General", "General ward", "planned", "Self", 3, 1_000_000L, 1_000_000L, "OK",
                Instant.parse("2026-11-09T05:30:00Z"), 850_000L, true, false);
    }

    private AdmissionList cardList(AdmittedClinicalFlags clinical, AdmittedCardAbilities abilities) {
        return new AdmissionList(4, 1, 3, true, 1, 80, 0, 0, new EmergencyViews.Counts(true, 0, 0), null,
                Instant.parse("2026-11-09T06:00:00Z"), List.of(new WardOption(WARD_ID, "General ward")), abilities, STANDARD,
                List.of(AdmittedPatientRow.of(summary(), null, WARD_ID, null, clinical)));
    }

    private static final ResolvedCardSetup STANDARD = ResolvedCardSetup.of(1, true, false, CardSetupDefaults.standard());

    private static AdmittedCardAbilities abilities(boolean nursing, boolean emr, boolean transfer, boolean discharge,
                                                   boolean deposit, boolean depositLowFlag, String... groups) {
        return new AdmittedCardAbilities(nursing, emr, transfer, discharge, deposit, depositLowFlag, List.of(groups));
    }

    @Test
    void theCardFactsAreInTheJsonForAClinicalRoleAndNullMeansNoneExists() throws Exception {
        AdmittedClinicalFlags none = new AdmittedClinicalFlags(null, null, false,
                new CardAllergy(AllergyStatus.NONE_KNOWN, null));
        when(service.list(TENANT_ID, USER_ID, "NURSE", null, null)).thenReturn(
                cardList(none, abilities(true, true, true, false, false, false, "patient", "clinical", "vitals")));

        String body = mockMvc.perform(get(URL + "/admissions").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-09T06:00:00Z"))
                .andExpect(jsonPath("$.wards[0].id").value(WARD_ID.toString()))
                .andExpect(jsonPath("$.wards[0].name").value("General ward"))
                .andExpect(jsonPath("$.card.nursing").value(true))
                .andExpect(jsonPath("$.card.discharge").value(false))
                .andExpect(jsonPath("$.card.depositLowFlag").value(false))
                .andExpect(jsonPath("$.items[0].uhid").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].wardId").value(WARD_ID.toString()))
                .andExpect(jsonPath("$.items[0].roomName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].clinical.escalation").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].clinical.latestEws").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].clinical.vitalsDue").value(false))
                .andExpect(jsonPath("$.items[0].clinical.allergy.status").value("NONE_KNOWN"))
                .andReturn().getResponse().getContentAsString();

        // The null of a fact that does not exist is written; the allergy text is absent unless RECORDED.
        org.assertj.core.api.Assertions.assertThat(body).contains("\"escalation\":null", "\"latestEws\":null")
                .doesNotContain("\"text\"");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"uhid\":null", "\"roomName\":null");
    }

    @Test
    void theRecordedAllergyTextAndTheEscalationMinutesAreInTheJson() throws Exception {
        when(service.list(TENANT_ID, USER_ID, "DOCTOR", null, null)).thenReturn(
                cardList(flags(), abilities(true, true, false, true, false, false, "patient", "clinical", "vitals")));

        mockMvc.perform(get(URL + "/admissions").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clinical.escalation.status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].clinical.escalation.waitingMinutes").value(12))
                .andExpect(jsonPath("$.items[0].clinical.escalation.targetMinutes").value(15))
                .andExpect(jsonPath("$.items[0].clinical.escalation.pastTarget").value(false))
                .andExpect(jsonPath("$.items[0].clinical.latestEws.score").value(8))
                .andExpect(jsonPath("$.items[0].clinical.latestEws.risk").value("HIGH"))
                .andExpect(jsonPath("$.items[0].clinical.vitalsDue").value(true))
                .andExpect(jsonPath("$.items[0].clinical.allergy.status").value("RECORDED"))
                .andExpect(jsonPath("$.items[0].clinical.allergy.text").value("Penicillin"));
    }

    /** AC34: for FRONT_DESK the property is absent from the raw JSON, not null and not an empty object. */
    @Test
    void theClinicalPropertyIsAbsentFromTheRawJsonForFrontDesk() throws Exception {
        when(service.list(TENANT_ID, USER_ID, "FRONT_DESK", null, null)).thenReturn(
                cardList(null, abilities(false, false, true, false, true, true, "patient", "contact", "billing")));

        String body = mockMvc.perform(get(URL + "/admissions").with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clinical").doesNotExist())
                .andExpect(jsonPath("$.items[0].ipNumber").value("IP/26-27/0042"))
                .andExpect(jsonPath("$.card.transfer").value(true))
                .andExpect(jsonPath("$.card.depositLowFlag").value(true))
                .andReturn().getResponse().getContentAsString();

        // SAH-37: the answer also holds cardSetup and card.visibleGroups, which name the data group "clinical"; the
        // rows themselves carry none of the clinical, vitals, diagnosis or mobile properties.
        String items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("items").toString();
        org.assertj.core.api.Assertions.assertThat(items).doesNotContain("clinical", "escalation", "latestEws",
                "vitalsDue", "allergy", "diagnosis", "latestVitals", "mobile");
    }

    @Test
    void badStatusFilterAnswers400() throws Exception {
        when(service.list(any(), any(), any(), eq("DISCHARGED"), any()))
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

    // ---- SAH-37 ----

    private AdmissionList listOf(AdmittedPatientRow row, AdmittedCardAbilities abilities) {
        return new AdmissionList(4, 1, 3, true, 1, 80, 0, 0, new EmergencyViews.Counts(true, 0, 0), null,
                Instant.parse("2026-11-09T06:00:00Z"), List.of(new WardOption(WARD_ID, "General ward")), abilities,
                STANDARD, List.of(row));
    }

    @Test
    void cardFactsIsPassedToTheServiceAndOnlyTheFirstTwoValuesAreTheContracts() throws Exception {
        when(service.list(TENANT_ID, USER_ID, "OWNER", null, "all")).thenReturn(
                cardList(flags(), abilities(true, true, true, true, true, true, "patient", "contact", "clinical",
                        "vitals", "billing")));

        mockMvc.perform(get(URL + "/admissions?cardFacts=all").with(as("OWNER"))).andExpect(status().isOk());

        verify(service).list(TENANT_ID, USER_ID, "OWNER", null, "all");
    }

    @Test
    void aWrongCardFactsValueAndANonOwnerPreviewAreRefusedByTheService() throws Exception {
        when(service.list(any(), any(), eq("NURSE"), any(), eq("all")))
                .thenThrow(new org.springframework.security.access.AccessDeniedException(
                        "Only the clinic owner can preview the card setup."));
        when(service.list(any(), any(), eq("OWNER"), any(), eq("everything")))
                .thenThrow(new InvalidInputException("cardFacts", "Use cardFacts=setup or cardFacts=all"));

        mockMvc.perform(get(URL + "/admissions?cardFacts=all").with(as("NURSE"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/admissions?cardFacts=everything").with(as("OWNER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theNewCardFactsAreAbsentWhenNotSentNullWhenNoneExistsAndTheValueWhenSent() throws Exception {
        AdmittedClinicalFlags none = new AdmittedClinicalFlags(null, null, true,
                new CardAllergy(AllergyStatus.NOT_RECORDED, null));
        AdmittedClinicalFlags full = new AdmittedClinicalFlags(null, null, true,
                new CardAllergy(AllergyStatus.NOT_RECORDED, null), "Chest pain", Optional.empty());
        AdmittedClinicalFlags withTiles = new AdmittedClinicalFlags(null, null, true,
                new CardAllergy(AllergyStatus.NOT_RECORDED, null), "Chest pain",
                Optional.of(new com.sahaj.ipd.application.AdmissionViews.CardLatestVitals(Instant.parse("2026-11-09T05:25:00Z"), "Sr. Anjali Pawar",
                        new com.sahaj.ipd.application.AdmissionViews.CardBpReading(85, 60, com.sahaj.ipd.domain.card.VitalMarks.Mark.CRITICAL),
                        new com.sahaj.ipd.application.AdmissionViews.CardReading(new BigDecimal("95"), com.sahaj.ipd.domain.card.VitalMarks.Mark.CHECK),
                        new com.sahaj.ipd.application.AdmissionViews.CardReading(new BigDecimal("96"), null),
                        new com.sahaj.ipd.application.AdmissionViews.CardReading(new BigDecimal("37.0"), null), null)));
        AdmittedCardAbilities abilities = abilities(true, true, true, false, false, false, "patient", "clinical",
                "vitals");

        when(service.list(TENANT_ID, USER_ID, "NURSE", null, null)).thenReturn(
                listOf(AdmittedPatientRow.of(summary(), null, WARD_ID, null, none), abilities));
        String absent = mockMvc.perform(get(URL + "/admissions").with(as("NURSE"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(absent).doesNotContain("diagnosis", "latestVitals", "mobile");

        when(service.list(TENANT_ID, USER_ID, "NURSE", null, null)).thenReturn(
                listOf(AdmittedPatientRow.of(summary(), null, WARD_ID, null, full, Optional.empty()), abilities));
        String nulls = mockMvc.perform(get(URL + "/admissions").with(as("NURSE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clinical.diagnosis").value("Chest pain"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].mobile").value(Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(nulls).contains("\"latestVitals\":null", "\"mobile\":null");

        when(service.list(TENANT_ID, USER_ID, "NURSE", null, null)).thenReturn(
                listOf(AdmittedPatientRow.of(summary(), null, WARD_ID, null, withTiles, Optional.of("9876543210")),
                        abilities));
        mockMvc.perform(get(URL + "/admissions").with(as("NURSE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].mobile").value("9876543210"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.recordedAt").value("2026-11-09T05:25:00Z"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.recordedByName").value("Sr. Anjali Pawar"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.bp.systolic").value(85))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.bp.diastolic").value(60))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.bp.mark").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.pulse.value").value(95))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.pulse.mark").value("CHECK"))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.spo2.value").value(96))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.spo2.mark").doesNotExist())
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.temp.value").value(37.0))
                .andExpect(jsonPath("$.items[0].clinical.latestVitals.rr").doesNotExist());
    }

    @Test
    void thePublishedSetupAndTheCardAbilitiesAreInTheAnswer() throws Exception {
        when(service.list(TENANT_ID, USER_ID, "FRONT_DESK", null, null)).thenReturn(listOf(
                AdmittedPatientRow.of(summary(), null, WARD_ID, null, null),
                abilities(false, false, true, false, true, true, "patient", "contact", "billing")));

        mockMvc.perform(get(URL + "/admissions").with(as("FRONT_DESK"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.cardSetup.version").value(1))
                .andExpect(jsonPath("$.cardSetup.isStandard").value(true))
                .andExpect(jsonPath("$.cardSetup.fallback").value(false))
                .andExpect(jsonPath("$.cardSetup.layout").value("standard"))
                .andExpect(jsonPath("$.cardSetup.cardsPerRow").value(3))
                .andExpect(jsonPath("$.cardSetup.sections[0].key").value("header"))
                .andExpect(jsonPath("$.cardSetup.sections[0].name").value("Card header"))
                .andExpect(jsonPath("$.cardSetup.sections[0].fields[0].key").value("name"))
                .andExpect(jsonPath("$.cardSetup.sections[0].fields[0].label").value("Patient name"))
                .andExpect(jsonPath("$.cardSetup.sections[0].fields[0].group").value("patient"))
                .andExpect(jsonPath("$.cardSetup.sections[0].fields[0].safety").value(false))
                .andExpect(jsonPath("$.cardSetup.actions[0]").value("nursing"))
                .andExpect(jsonPath("$.card.deposit").value(true))
                .andExpect(jsonPath("$.card.visibleGroups[1]").value("contact"));
    }
}
