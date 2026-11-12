package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.CasePaperInput;
import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import com.sahaj.opd.application.CasePaperService;
import com.sahaj.opd.application.DiagnosisRepository.DiagnosisRow;
import com.sahaj.opd.application.DiagnosisService;
import com.sahaj.opd.application.FeeSettingsInput;
import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.opd.application.FeeSettingsService;
import com.sahaj.opd.application.MedicineRepository.MedicinePage;
import com.sahaj.opd.application.MedicineRepository.MedicineRow;
import com.sahaj.opd.application.MedicineService;
import com.sahaj.opd.application.ServiceCatalogService;
import com.sahaj.opd.application.ServiceInput;
import com.sahaj.opd.application.ServiceView;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.LinkedHashMap;
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

/** SAH-7 opd endpoints: contract shape, owner-only writes (403 otherwise), tenant from the signed-in user, problem responses. */
@WebMvcTest(controllers = {ServiceController.class, FeeSettingsController.class, MedicineController.class,
        CasePaperController.class})
@Import(SecurityConfig.class)
class OpdControllersTest {

    private static final String OPD = "/api/v1/opd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID MEDICINE_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServiceCatalogService serviceCatalog;

    @MockBean
    private FeeSettingsService feeSettings;

    @MockBean
    private MedicineService medicineService;

    @MockBean
    private DiagnosisService diagnosisService;

    @MockBean
    private CasePaperService casePaper;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(OPD + "/services")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(OPD + "/medicines")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonOwnerGets403OnEveryMasterChange() throws Exception {
        String service = "{\"name\":\"Dressing\",\"category\":\"Procedure\",\"priceRupees\":150}";
        mockMvc.perform(post(OPD + "/services").with(as("FRONT_DESK")).contentType(JSON).content(service))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(OPD + "/services/" + SERVICE_ID).with(as("DOCTOR")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(OPD + "/fee-settings").with(as("BILLING")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(OPD + "/case-paper-settings").with(as("NURSE")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(OPD + "/medicine-favourites/" + MEDICINE_ID).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(OPD + "/medicine-favourites/" + MEDICINE_ID).with(as("PHARMACIST")))
                .andExpect(status().isForbidden());

        verify(serviceCatalog, never()).add(any(), any(), any());
        verify(serviceCatalog, never()).update(any(), any(), any(), any());
        verify(feeSettings, never()).save(any(), any(), any());
        verify(casePaper, never()).save(any(), any(), any());
        verify(medicineService, never()).markFavourite(any(), any(), any());
    }

    @Test
    void anyClinicRoleReadsTheFeeListAndOnlyTheSignedInClinicIsAsked() throws Exception {
        when(serviceCatalog.list(TENANT_ID, null)).thenReturn(List.of(
                new ServiceView(SERVICE_ID, "Dressing", "Procedure", 150, true),
                new ServiceView(UUID.randomUUID(), "Old service", "Lab", 80, false)));

        mockMvc.perform(get(OPD + "/services").with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SERVICE_ID.toString()))
                .andExpect(jsonPath("$[0].priceRupees").value(150))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test
    void theOwnerAddsAServiceAndGets201() throws Exception {
        when(serviceCatalog.add(eq(TENANT_ID), eq(USER_ID), any(ServiceInput.class)))
                .thenReturn(new ServiceView(SERVICE_ID, "Dressing", "Procedure", 150, true));

        mockMvc.perform(post(OPD + "/services").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"Dressing\",\"category\":\"Procedure\",\"priceRupees\":150,"
                                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dressing"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aDuplicateServiceIs409WithTheMockupMessage() throws Exception {
        when(serviceCatalog.add(any(), any(), any())).thenThrow(new ConflictException("duplicate-service",
                "That service is already in the list", "That service is already in the list"));

        mockMvc.perform(post(OPD + "/services").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"dressing\",\"category\":\"Procedure\",\"priceRupees\":150}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/duplicate-service"))
                .andExpect(jsonPath("$.detail").value("That service is already in the list"));
    }

    @Test
    void aZeroPriceOnAddIs400AndAnUnknownServiceIs404() throws Exception {
        when(serviceCatalog.add(any(), any(), any()))
                .thenThrow(new InvalidInputException("priceRupees", "Enter a price above ₹0"));
        mockMvc.perform(post(OPD + "/services").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"Dressing\",\"category\":\"Procedure\",\"priceRupees\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("priceRupees"));

        when(serviceCatalog.update(eq(TENANT_ID), eq(USER_ID), eq(SERVICE_ID), any(ServiceInput.class)))
                .thenThrow(new NotFoundException("x"));
        mockMvc.perform(patch(OPD + "/services/" + SERVICE_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void feeSettingsKeepTheContractShape() throws Exception {
        FeeRow row = new FeeRow("after", 14, "half", Map.of("FRONT_DESK", 10), true);
        when(feeSettings.get(TENANT_ID)).thenReturn(row);
        when(feeSettings.save(eq(TENANT_ID), eq(USER_ID), any(FeeSettingsInput.class))).thenReturn(row);

        mockMvc.perform(get(OPD + "/fee-settings").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeTiming").value("after"))
                .andExpect(jsonPath("$.followUpDays").value(14))
                .andExpect(jsonPath("$.followUpFee").value("half"))
                .andExpect(jsonPath("$.discountLimits.FRONT_DESK").value(10))
                .andExpect(jsonPath("$.upiOnBill").value(true));
        mockMvc.perform(put(OPD + "/fee-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"feeTiming\":\"after\",\"followUpDays\":14,\"followUpFee\":\"half\","
                                + "\"discountLimits\":{\"FRONT_DESK\":10},\"upiOnBill\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUpDays").value(14));
    }

    @Test
    void medicinesComeAsAPageWithTheFavouriteFlag() throws Exception {
        when(medicineService.search(TENANT_ID, "para", true, 1)).thenReturn(new MedicinePage(List.of(
                new MedicineRow(MEDICINE_ID, "Paracetamol 650 mg", "Tablet", "1-0-1", "1 tablet", true)), 41));

        mockMvc.perform(get(OPD + "/medicines").param("q", "para").param("favouritesOnly", "true").param("page", "1")
                        .with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.items[0].id").value(MEDICINE_ID.toString()))
                .andExpect(jsonPath("$.items[0].favourite").value(true))
                .andExpect(jsonPath("$.items[0].usualDose").value("1 tablet"));
    }

    @Test
    void favouritesReturn204AndAnUnknownOrUnmarkedMedicineIs404() throws Exception {
        mockMvc.perform(put(OPD + "/medicine-favourites/" + MEDICINE_ID).with(as("OWNER")))
                .andExpect(status().isNoContent());
        verify(medicineService).markFavourite(TENANT_ID, USER_ID, MEDICINE_ID);

        doThrow(new NotFoundException("x")).when(medicineService).unmarkFavourite(TENANT_ID, USER_ID, MEDICINE_ID);
        mockMvc.perform(delete(OPD + "/medicine-favourites/" + MEDICINE_ID).with(as("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void diagnosesAreSearchable() throws Exception {
        when(diagnosisService.search("j06", null, null)).thenReturn(List.of(
                new DiagnosisRow("J06.9", "Acute upper respiratory infection, unspecified", "gp")));

        mockMvc.perform(get(OPD + "/diagnoses").param("q", "j06").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("J06.9"))
                .andExpect(jsonPath("$[0].specialty").value("gp"));
    }

    @Test
    void diagnosesTakeAnOptionalSpecialtyAndPage() throws Exception {
        when(diagnosisService.search("a", "dental", 2)).thenReturn(List.of(
                new DiagnosisRow("K02.9", "Dental caries, unspecified", "dental")));

        mockMvc.perform(get(OPD + "/diagnoses").param("q", "a").param("specialty", "dental").param("page", "2")
                        .with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("K02.9"));
    }

    @Test
    void diagnosesRefuseASpecialtyOrPageOutOfRangeWithValidation() throws Exception {
        when(diagnosisService.search("a", "ayur", null))
                .thenThrow(new InvalidInputException("specialty", "Choose one of gp, dental, paeds or homeo."));

        mockMvc.perform(get(OPD + "/diagnoses").param("q", "a").param("specialty", "ayur").with(as("DOCTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("specialty"));
    }

    @Test
    void casePaperSettingsKeepTheContractShapeAndRxStaysOn() throws Exception {
        Map<String, Boolean> sections = new LinkedHashMap<>();
        sections.put("rx", true);
        sections.put("history", false);
        PaperRow row = new PaperRow("A5", null, false, false, sections);
        when(casePaper.get(TENANT_ID)).thenReturn(row);
        when(casePaper.save(eq(TENANT_ID), eq(USER_ID), any(CasePaperInput.class))).thenReturn(row);

        mockMvc.perform(get(OPD + "/case-paper-settings").with(as("BILLING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value("A5"))
                .andExpect(jsonPath("$.secondLanguage").value((Object) null))
                .andExpect(jsonPath("$.sections.rx").value(true))
                .andExpect(jsonPath("$.sections.history").value(false));

        when(casePaper.save(any(), any(), any()))
                .thenThrow(new InvalidInputException("sections", "Medicines (Rx) cannot be switched off"));
        mockMvc.perform(put(OPD + "/case-paper-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"size\":\"A5\",\"preprinted\":false,\"caps\":false,\"sections\":{\"rx\":false}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Medicines (Rx) cannot be switched off"));
    }
}
