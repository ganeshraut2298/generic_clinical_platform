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
import com.sahaj.ipd.application.BedTransferService;
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
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-26 transfer endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = BedTransferController.class)
@Import(SecurityConfig.class)
class BedTransferControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID TO_BED = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String TRANSFER_URL = URL + "/admissions/" + ADMISSION_ID + "/transfer";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BedTransferService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static String body() {
        return "{\"toBedId\":\"" + TO_BED + "\"}";
    }

    private static Detail detail() {
        RunningBill bill = new RunningBill(3, "midnight", "Private", new BigDecimal("1.50"),
                List.of(new BillLine("Bed · General (before transfer)", 80_000L, 2, 160_000L, true),
                        new BillLine("Bed · Private", 250_000L, 1, 250_000L)),
                410_000L, 1_000_000L, 41, 80);
        return new Detail(ADMISSION_ID, "IP/26-27/0042", PATIENT_ID, "Test Patient", 56, "M", "Dr Test", "201-B",
                "Private", "Private ward", "planned", "Self", 3, 1_000_000L, 1_000_000L, "OK",
                Instant.parse("2026-11-09T05:30:00Z"), null, "Chest pain", List.of(), false, 410_000L, false, bill,
                true, true, new TransferRules(true, "same"), false, "ADMITTED", null, true, null, null, true, false, null, true, null);
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/transfer-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/transfer-rules").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(TRANSFER_URL).contentType(JSON).content(body())).andExpect(status().isUnauthorized());
    }

    @Test
    void theFourIpdRolesReadTheRulesInContractShapeAndOthersAreRefused() throws Exception {
        when(service.rules(TENANT_ID)).thenReturn(new TransferRules(true, "same"));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL + "/transfer-rules").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.upgradeNeedsOwner").value(true))
                    .andExpect(jsonPath("$.newRateFrom").value("same"));
        }
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(URL + "/transfer-rules").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void onlyTheOwnerChangesTheRules() throws Exception {
        when(service.updateRules(TENANT_ID, USER_ID, false, "next")).thenReturn(new TransferRules(false, "next"));
        String rulesBody = "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"next\"}";

        mockMvc.perform(put(URL + "/transfer-rules").with(as("OWNER")).contentType(JSON).content(rulesBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upgradeNeedsOwner").value(false))
                .andExpect(jsonPath("$.newRateFrom").value("next"));
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            mockMvc.perform(put(URL + "/transfer-rules").with(as(role)).contentType(JSON).content(rulesBody))
                    .andExpect(status().isForbidden());
        }
        verify(service).updateRules(TENANT_ID, USER_ID, false, "next");
    }

    @Test
    void rulesProblemsMapToTheirStatusAndType() throws Exception {
        when(service.updateRules(any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("newRateFrom", "Choose Same day or Next day"))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only",
                        "IPD is read-only. Nothing can be changed."))
                .thenThrow(new ModuleOffException("off"));
        when(service.rules(TENANT_ID)).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(put(URL + "/transfer-rules").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newRateFrom"));
        mockMvc.perform(put(URL + "/transfer-rules").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
        mockMvc.perform(put(URL + "/transfer-rules").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
        mockMvc.perform(get(URL + "/transfer-rules").with(as("NURSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void transferAnswers200WithTheSheetAndSegmentMarkedLines() throws Exception {
        when(service.transfer(TENANT_ID, USER_ID, "NURSE", ADMISSION_ID, TO_BED, "Isolation")).thenReturn(detail());

        mockMvc.perform(post(TRANSFER_URL).with(as("NURSE")).contentType(JSON)
                        .content("{\"toBedId\":\"" + TO_BED + "\",\"reason\":\"Isolation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedCode").value("201-B"))
                .andExpect(jsonPath("$.categoryName").value("Private"))
                .andExpect(jsonPath("$.canTransfer").value(true))
                .andExpect(jsonPath("$.transferRules.newRateFrom").value("same"))
                .andExpect(jsonPath("$.bill.lines[0].name").value("Bed · General (before transfer)"))
                .andExpect(jsonPath("$.bill.lines[0].segment").value(true))
                .andExpect(jsonPath("$.bill.lines[1].segment").value(false));
    }

    @Test
    void theReasonIsOptionalAndTheThreeMovingRolesMayMoveWhileOthersMayNot() throws Exception {
        when(service.transfer(eq(TENANT_ID), eq(USER_ID), any(), eq(ADMISSION_ID), eq(TO_BED), eq(null)))
                .thenReturn(detail());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            mockMvc.perform(post(TRANSFER_URL).with(as(role)).contentType(JSON).content(body()))
                    .andExpect(status().isOk());
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(post(TRANSFER_URL).with(as(role)).contentType(JSON).content(body()))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).transfer(any(), any(), eq("DOCTOR"), any(), any(), any());
    }

    @Test
    void theClinicAndThePersonComeFromTheSignInNeverFromTheBody() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        when(service.transfer(any(), any(), any(), any(), any(), any())).thenReturn(detail());

        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON)
                        .content("{\"toBedId\":\"" + TO_BED + "\",\"tenantId\":\"" + otherTenant + "\"}"))
                .andExpect(status().isOk());

        verify(service).transfer(TENANT_ID, USER_ID, "OWNER", ADMISSION_ID, TO_BED, null);
        assertThat(otherTenant).isNotEqualTo(TENANT_ID);
    }

    @Test
    void transferProblemsMapToTheirStatusTypeAndMessage() throws Exception {
        when(service.transfer(any(), any(), any(), any(), any(), any()))
                .thenThrow(new NotFoundException("Admission not found"))
                .thenThrow(new ConflictException("not-admitted", "Patient is not admitted",
                        "This patient is not admitted any more. The patient cannot be moved."))
                .thenThrow(new InvalidInputException("toBedId", "The patient is already in this bed. Pick another."))
                .thenThrow(new ConflictException("bed-not-free", "Bed is not free",
                        "That bed is no longer free. Pick another."))
                .thenThrow(new ForbiddenException("owner-approval-needed", "Owner approval needed",
                        "Moving to Private needs the owner's approval. Ask the clinic owner."))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only",
                        "IPD is read-only. Nothing can be changed."))
                .thenThrow(new ModuleOffException("off"));

        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("not-admitted")));
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("toBedId"));
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bed-not-free")))
                .andExpect(jsonPath("$.detail").value("That bed is no longer free. Pick another."));
        mockMvc.perform(post(TRANSFER_URL).with(as("NURSE")).contentType(JSON).content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("owner-approval-needed")))
                .andExpect(jsonPath("$.detail")
                        .value("Moving to Private needs the owner's approval. Ask the clinic owner."));
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void aBadIdOrBodyIs400() throws Exception {
        mockMvc.perform(post(URL + "/admissions/not-a-uuid/transfer").with(as("OWNER")).contentType(JSON)
                        .content(body()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content("{\"toBedId\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(TRANSFER_URL).with(as("OWNER")).contentType(JSON).content("not json"))
                .andExpect(status().isBadRequest());
        verify(service, never()).transfer(any(), any(), any(), any(), any(), any());
    }
}
