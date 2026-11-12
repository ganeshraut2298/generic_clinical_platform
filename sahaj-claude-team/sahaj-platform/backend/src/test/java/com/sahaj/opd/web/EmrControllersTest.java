package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.emr.EmrDoctorContextService;
import com.sahaj.opd.application.emr.EmrMasterService;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrSettingsService;
import com.sahaj.opd.application.emr.EmrTemplateService;
import com.sahaj.opd.application.emr.EmrViews;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
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

/**
 * SAH-43 and SAH-44 endpoints (docs/api/opd.yaml 0.2.0): contract shape, 401 and 403, the closed request bodies, the
 * 403 on writes to the read-only global paths, and the problem types.
 */
@WebMvcTest(controllers = {EmrMasterController.class, EmrDoctorController.class, EmrTemplateController.class,
        EmrSettingsController.class, EmrReadOnlyPathsController.class})
@Import(SecurityConfig.class)
class EmrControllersTest {

    private static final String EMR = "/api/v1/opd/emr";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String[] CLINIC_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK",
            "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String[] NOT_OWNER = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmrMasterService masters;

    @MockBean
    private EmrDoctorContextService doctorContext;

    @MockBean
    private EmrTemplateService templates;

    @MockBean
    private EmrSettingsService settings;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    private static EmrViews.TemplateView templateView() {
        EmrViews.SectionView section = new EmrViews.SectionView("chief_complaints", "Chief complaints", "ASSESSMENT", 1,
                "COMPLAINTS", null, new EmrViews.SectionOptions("MASTER", "complaints"), 59, List.of(), true, true,
                true, true, false);
        EmrViews.SectionView chips = new EmrViews.SectionView("history_allergies", "History & allergies", "ASSESSMENT",
                2, "CHIP_LIST", null, new EmrViews.SectionOptions("TEMPLATE", null), 2,
                List.of(new EmrViews.TemplateOption("none", "None", "NORMAL", true)), false, false, true, false, true);
        return new EmrViews.TemplateView("gp", "General practice", 1, true, 3, true, 2, 1, List.of(section, chips));
    }

    // ---- 401 ----

    @Test
    void aSignedOutVisitorGets401OnEveryEmrOperation() throws Exception {
        mockMvc.perform(get(EMR + "/masters/complaints")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/quick-options/diagnoses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/templates")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/templates/gp")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMR + "/templates/gp/sections/vitals/override").contentType(JSON)
                .content("{\"shown\":false,\"required\":false,\"revision\":0}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EMR + "/templates/gp/reset").contentType(JSON).content("{\"revision\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMR + "/settings").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EMR + "/masters/complaints").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- masters ----

    @Test
    void everyClinicRoleSearchesAMasterAndTheClinicIsNotTakenFromTheRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(masters.search("complaints", "fev", "gp", 1)).thenReturn(new EmrViews.MasterPage(
                List.of(new EmrViews.MasterItem(id, "Fever", null)), 21, 1, 20));

        for (String role : CLINIC_ROLES) {
            mockMvc.perform(get(EMR + "/masters/complaints").param("q", "fev").param("specialty", "gp")
                            .param("page", "1").param("tenantId", UUID.randomUUID().toString()).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.items[0].name").value("Fever"))
                    .andExpect(jsonPath("$.items[0].specialtyHint").value((Object) null))
                    .andExpect(jsonPath("$.total").value(21))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(20));
        }
    }

    @Test
    void aSearchWithNoMatchIs200WithAnEmptyList() throws Exception {
        when(masters.search("advice", null, null, null)).thenReturn(new EmrViews.MasterPage(List.of(), 0, 0, 20));

        mockMvc.perform(get(EMR + "/masters/advice").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anUnknownMasterIsBadRequestAndATooLongTextIsValidation() throws Exception {
        when(masters.search("diagnoses", null, null, null)).thenThrow(EmrProblemException.badRequest("Search x."));
        when(masters.search("complaints", "long", null, null))
                .thenThrow(new InvalidInputException("q", "Search text must be 100 characters or fewer."));

        mockMvc.perform(get(EMR + "/masters/diagnoses").with(as("DOCTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"));
        mockMvc.perform(get(EMR + "/masters/complaints").param("q", "long").with(as("DOCTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Search text must be 100 characters or fewer."));
    }

    // ---- writes to the read-only global paths ----

    @Test
    void everyWriteMethodOnTheGlobalPathsIs403ForEveryRoleIncludingTheOwner() throws Exception {
        String[] paths = {EMR + "/masters/complaints", EMR + "/masters", EMR + "/quick-options/diagnoses",
                EMR + "/templates", EMR + "/templates/gp"};
        for (String role : CLINIC_ROLES) {
            for (String path : paths) {
                for (var builder : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                        post(path), put(path), patch(path), delete(path)}) {
                    mockMvc.perform(builder.with(as(role)).contentType(JSON).content("{\"name\":\"x\"}"))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
                }
            }
        }
        verifyNoInteractions(masters, templates);
    }

    // ---- quick options ----

    @Test
    void aDoctorReadsTheirOwnRankingAndNoDoctorIdIsReadFromTheRequest() throws Exception {
        Instant used = Instant.parse("2026-11-10T06:05:00Z");
        when(masters.quickOptions(any(ClinicUser.class), eq("diagnoses"), eq(5), eq("gp"))).thenReturn(
                new EmrViews.QuickOptionList("diagnoses", List.of(
                        new EmrViews.QuickOption("J06.9", "Acute upper respiratory infection, unspecified", "J06.9",
                                null, null, 5, used),
                        new EmrViews.QuickOption("A09", "Infectious gastroenteritis", "A09", null, null, 0, null))));

        mockMvc.perform(get(EMR + "/quick-options/diagnoses").param("limit", "5").param("specialty", "gp")
                        .param("doctorMembershipId", UUID.randomUUID().toString()).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.master").value("diagnoses"))
                .andExpect(jsonPath("$.items[0].itemKey").value("J06.9"))
                .andExpect(jsonPath("$.items[0].icd10Code").value("J06.9"))
                .andExpect(jsonPath("$.items[0].form").value((Object) null))
                .andExpect(jsonPath("$.items[0].favourite").value((Object) null))
                .andExpect(jsonPath("$.items[0].useCount").value(5))
                .andExpect(jsonPath("$.items[0].lastUsedAt").value("2026-11-10T06:05:00Z"))
                .andExpect(jsonPath("$.items[1].lastUsedAt").value((Object) null));

        ArgumentCaptorHolder.assertCalledWithTheSignedInUser(masters, USER, TENANT);
    }

    @Test
    void theOtherRolesGet403OnQuickOptionsAndTheServiceIsNotCalled() throws Exception {
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(EMR + "/quick-options/complaints").with(as(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
        }
        verify(masters, never()).quickOptions(any(), any(), any(), any());
    }

    @Test
    void anOwnerWithoutDoctorDetailsGets403WithTheReason() throws Exception {
        when(masters.quickOptions(any(ClinicUser.class), eq("complaints"), any(), any())).thenThrow(
                new ForbiddenException("doctor-details-missing", "Doctor details are missing",
                        "Add your doctor details in Team and access to write consultations."));

        mockMvc.perform(get(EMR + "/quick-options/complaints").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/doctor-details-missing"))
                .andExpect(jsonPath("$.detail").value(
                        "Add your doctor details in Team and access to write consultations."));
    }

    // ---- /emr/me ----

    @Test
    void theDoctorReadsTheirOwnDetailsWithTheMissingListAndTheTemplateChoice() throws Exception {
        UUID membership = UUID.randomUUID();
        when(doctorContext.me(any(ClinicUser.class))).thenReturn(new EmrViews.DoctorContext(membership,
                "Dr Test Person", "DOCTOR", "General Physician", "MBBS, MD", null, List.of("REGISTRATION_NO"),
                new EmrViews.TemplateChoice("gp", 1, "General practice", "DOCTOR_SPECIALTY")));

        mockMvc.perform(get(EMR + "/me").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.role").value("DOCTOR"))
                .andExpect(jsonPath("$.specialty").value("General Physician"))
                .andExpect(jsonPath("$.registrationNo").value((Object) null))
                .andExpect(jsonPath("$.missing[0]").value("REGISTRATION_NO"))
                .andExpect(jsonPath("$.template.templateKey").value("gp"))
                .andExpect(jsonPath("$.template.templateVersion").value(1))
                .andExpect(jsonPath("$.template.templateName").value("General practice"))
                .andExpect(jsonPath("$.template.choiceSource").value("DOCTOR_SPECIALTY"));
    }

    @Test
    void theOtherRolesGet403OnMe() throws Exception {
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(EMR + "/me").with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(doctorContext);
    }

    // ---- templates ----

    @Test
    void everyClinicRoleReadsTheTemplateList() throws Exception {
        when(templates.list(any(ClinicUser.class))).thenReturn(List.of(
                new EmrViews.TemplateSummary("gp", "General practice", 1, true, 11, 11, 6, false)));

        for (String role : CLINIC_ROLES) {
            mockMvc.perform(get(EMR + "/templates").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].templateKey").value("gp"))
                    .andExpect(jsonPath("$[0].clinicSpecialty").value(true))
                    .andExpect(jsonPath("$[0].sectionCount").value(11))
                    .andExpect(jsonPath("$[0].sectionsOn").value(11))
                    .andExpect(jsonPath("$[0].requiredOn").value(6))
                    .andExpect(jsonPath("$[0].hasOverrides").value(false));
        }
    }

    @Test
    void theTemplateViewKeepsTheContractFieldNames() throws Exception {
        when(templates.get(any(ClinicUser.class), eq("gp"))).thenReturn(templateView());

        mockMvc.perform(get(EMR + "/templates/gp").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.sectionsOn").value(2))
                .andExpect(jsonPath("$.sections[0].key").value("chief_complaints"))
                .andExpect(jsonPath("$.sections[0].iconKey").value((Object) null))
                .andExpect(jsonPath("$.sections[0].options.source").value("MASTER"))
                .andExpect(jsonPath("$.sections[0].options.master").value("complaints"))
                .andExpect(jsonPath("$.sections[0].quickOptionCount").value(59))
                .andExpect(jsonPath("$.sections[0].templateRequired").value(true))
                .andExpect(jsonPath("$.sections[0].overridden").value(false))
                .andExpect(jsonPath("$.sections[1].hideable").value(false))
                .andExpect(jsonPath("$.sections[1].templateOptions[0].key").value("none"))
                .andExpect(jsonPath("$.sections[1].templateOptions[0].isNone").value(true))
                .andExpect(jsonPath("$.sections[1].templateOptions[0].style").value("NORMAL"));
    }

    @Test
    void aTemplateWithNoDataIs404AndAMalformedKeyIs400() throws Exception {
        when(templates.get(any(ClinicUser.class), eq("ayur"))).thenThrow(EmrProblemException.templateNotFound());
        when(templates.get(any(ClinicUser.class), eq("GP"))).thenThrow(EmrProblemException.badRequest("Check it."));

        mockMvc.perform(get(EMR + "/templates/ayur").with(as("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/template-not-found"))
                .andExpect(jsonPath("$.detail").value("There is no EMR template for this specialty yet."));
        mockMvc.perform(get(EMR + "/templates/GP").with(as("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"));
    }

    // ---- override and reset ----

    private static final String OVERRIDE = EMR + "/templates/gp/sections/vitals/override";

    @Test
    void theOwnerSetsAnOverrideAndGetsTheTemplateViewBack() throws Exception {
        when(templates.setOverride(any(ClinicUser.class), eq("gp"), eq("vitals"), eq(false), eq(false), eq(3L)))
                .thenReturn(templateView());

        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":false,\"required\":false,\"revision\":3,\"tenantId\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":false,\"required\":false,\"revision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateKey").value("gp"));
    }

    @Test
    void everyOtherRoleGets403OnOverrideResetAndSettingsSaveAndNothingIsCalled() throws Exception {
        for (String role : NOT_OWNER) {
            mockMvc.perform(put(OVERRIDE).with(as(role)).contentType(JSON)
                            .content("{\"shown\":false,\"required\":false,\"revision\":0}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(EMR + "/templates/gp/reset").with(as(role)).contentType(JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(EMR + "/settings").with(as(role)).contentType(JSON)
                            .content("{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":0}"))
                    .andExpect(status().isForbidden());
        }
        verify(templates, never()).setOverride(any(), any(), any(), any(), any(), any());
        verify(templates, never()).reset(any(), any(), any());
        verify(settings, never()).save(any(), any(), any(), any());
    }

    @Test
    void aLabelAPartOrAnyOtherFieldInTheOverrideBodyIs400Validation() throws Exception {
        for (String body : new String[] {
                "{\"shown\":true,\"required\":true,\"revision\":0,\"label\":\"Renamed\"}",
                "{\"shown\":true,\"required\":true,\"revision\":0,\"part\":\"TREATMENT\"}",
                "{\"shown\":true,\"required\":true,\"revision\":0,\"tenantId\":\"" + UUID.randomUUID() + "\"}"}) {
            mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
        }
        verify(templates, never()).setOverride(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theOverrideRuleErrorsKeepTheirOwnProblemTypes() throws Exception {
        when(templates.setOverride(any(), eq("gp"), eq("vitals"), eq(false), eq(true), any()))
                .thenThrow(EmrProblemException.hiddenSectionRequired());
        when(templates.setOverride(any(), eq("gp"), eq("prescription"), eq(false), eq(false), any()))
                .thenThrow(EmrProblemException.sectionNotHideable("Prescription"));
        when(templates.setOverride(any(), eq("gp"), eq("nothing"), any(), any(), any()))
                .thenThrow(EmrProblemException.sectionNotFound());
        when(templates.setOverride(any(), eq("gp"), eq("local_exam"), any(), any(), any()))
                .thenThrow(new ConflictException("emr-setup-changed", "Someone else changed this template",
                        "Someone else changed this template. Reload and try again."));

        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":false,\"required\":true,\"revision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/hidden-section-required"))
                .andExpect(jsonPath("$.detail").value("A hidden section cannot be required."));
        mockMvc.perform(put(EMR + "/templates/gp/sections/prescription/override").with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":false,\"required\":false,\"revision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/section-not-hideable"))
                .andExpect(jsonPath("$.detail").value("Prescription cannot be hidden."));
        mockMvc.perform(put(EMR + "/templates/gp/sections/nothing/override").with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":true,\"required\":true,\"revision\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/section-not-found"));
        mockMvc.perform(put(EMR + "/templates/gp/sections/local_exam/override").with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":true,\"required\":true,\"revision\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/emr-setup-changed"));
    }

    @Test
    void anUnreadableValueInTheOverrideBodyIsAValidationErrorOnItsField() throws Exception {
        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON)
                        .content("{\"shown\":\"maybe\",\"required\":true,\"revision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("shown"));
        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"));
    }

    @Test
    void theOwnerResetsATemplateAndAnExtraFieldIsRefused() throws Exception {
        when(templates.reset(any(ClinicUser.class), eq("gp"), eq(3L))).thenReturn(templateView());

        mockMvc.perform(post(EMR + "/templates/gp/reset").with(as("OWNER")).contentType(JSON)
                        .content("{\"revision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));
        mockMvc.perform(post(EMR + "/templates/gp/reset").with(as("OWNER")).contentType(JSON)
                        .content("{\"revision\":3,\"label\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
    }

    @Test
    void aBodyOver64KbIs413() throws Exception {
        String big = "{\"shown\":true,\"required\":true,\"revision\":0,\"x\":\"" + "a".repeat(70_000) + "\"}";

        mockMvc.perform(put(OVERRIDE).with(as("OWNER")).contentType(JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/payload-too-large"));
    }

    // ---- settings ----

    @Test
    void everyClinicRoleReadsTheSettings() throws Exception {
        when(settings.get(any(ClinicUser.class))).thenReturn(
                new EmrViews.Settings("COLLAPSED", "CASE_PAPER", "ASSESSMENT", 0, false));

        for (String role : CLINIC_ROLES) {
            mockMvc.perform(get(EMR + "/settings").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sectionPanel").value("COLLAPSED"))
                    .andExpect(jsonPath("$.rightPanelFirst").value("CASE_PAPER"))
                    .andExpect(jsonPath("$.defaultPart").value("ASSESSMENT"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.canEdit").value(false));
        }
    }

    @Test
    void theOwnerSavesTheSettingsAndDefaultPartIsRefused() throws Exception {
        when(settings.save(any(ClinicUser.class), eq("OPEN"), eq("CASE_PAPER"), eq(0L)))
                .thenReturn(new EmrViews.Settings("OPEN", "CASE_PAPER", "ASSESSMENT", 1, true));

        mockMvc.perform(put(EMR + "/settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionPanel").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(put(EMR + "/settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":0,"
                                + "\"defaultPart\":\"TREATMENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
    }

    @Test
    void aStaleSettingsVersionIs409() throws Exception {
        when(settings.save(any(ClinicUser.class), any(), any(), eq(0L))).thenThrow(new ConflictException(
                "emr-setup-changed", "Someone else changed these settings",
                "Someone else changed this setting. Reload and try again."));

        mockMvc.perform(put(EMR + "/settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/emr-setup-changed"));
    }

    /** Checks the clinic and user handed to a service are the signed-in user's, never a request value. */
    private static final class ArgumentCaptorHolder {
        static void assertCalledWithTheSignedInUser(EmrMasterService service, UUID userId, UUID tenantId) {
            org.mockito.ArgumentCaptor<ClinicUser> captor = org.mockito.ArgumentCaptor.forClass(ClinicUser.class);
            verify(service).quickOptions(captor.capture(), eq("diagnoses"), eq(5), eq("gp"));
            org.assertj.core.api.Assertions.assertThat(captor.getValue().userId()).isEqualTo(userId);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
        }
    }
}
