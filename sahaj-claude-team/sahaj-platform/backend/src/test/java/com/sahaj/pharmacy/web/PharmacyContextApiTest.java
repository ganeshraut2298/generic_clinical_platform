package com.sahaj.pharmacy.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.ContextService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.PharmacyViews.CallerView;
import com.sahaj.pharmacy.application.PharmacyViews.Context;
import com.sahaj.pharmacy.application.PharmacyViews.FixedRules;
import com.sahaj.pharmacy.application.PharmacyViews.MyStore;
import com.sahaj.pharmacy.application.PharmacyViews.UnitHeader;
import com.sahaj.pharmacy.application.PharmacyViews.UnitSettings;
import com.sahaj.pharmacy.application.SetupSummaryService;
import com.sahaj.pharmacy.application.UnitService;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-1, 78-2, 78-4 over HTTP (docs/api/pharmacy.yaml 0.1.0): context, working store, setup summary, unit settings.
 * Field names and problem types as the contract; unknown fields in a closed body are 400.
 */
@WebMvcTest(controllers = {ContextController.class, UnitSettingsController.class})
@Import(SecurityConfig.class)
class PharmacyContextApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private ContextService context;
    @MockBean
    private SetupSummaryService summary;
    @MockBean
    private UnitService units;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Context pharmacistContext() {
        MyStore store = new MyStore(STORE_ID, "Test Main Pharmacy", "PHARM", StoreType.PHARMACY, StoreRole.STORE_USER,
                StoreRole.STORE_USER, new StoreAccess(true, false, true, true, false, true, true, true, true),
                new PharmacyViews.IssuesFrom(UUID.fromString("00000000-0000-4000-8000-0000000000c5"), "Test Central Store"));
        return new Context(new UnitHeader("Test Clinic Baner", "TCB", IntegrationMode.INTEGRATED),
                new CallerView(UUID.fromString("00000000-0000-4000-8000-000000000011"), "Test Pharmacist One",
                        "PHARMACIST", "Pharmacist", "VIEW"),
                List.of("SETUP_VIEW", "OPENING_STOCK_UPLOAD"), List.of(store), null, false, FixedRules.ON);
    }

    private static UnitSettings settings() {
        return new UnitSettings("Test Clinic Baner", "TCB", "27AAAAA0000A1Z5", "TEST-DL-0001", LocalDate.of(2028, 3, 31),
                false, IntegrationMode.INTEGRATED, true, true, true, true, 3L, Instant.parse("2026-11-17T06:05:00Z"));
    }

    // ---- GET /context ----

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(URL + "/context")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/setup-summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/unit-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/unit-settings").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/context/working-store").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(context, summary, units);
    }

    @Test
    void theContextHasTheContractFieldNamesAndTheClinicAndPersonComeFromTheSession() throws Exception {
        when(context.get(TENANT_ID, USER_ID)).thenReturn(pharmacistContext());

        mockMvc.perform(get(URL + "/context?tenantId=" + UUID.randomUUID()).with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit.name").value("Test Clinic Baner"))
                .andExpect(jsonPath("$.unit.code").value("TCB"))
                .andExpect(jsonPath("$.unit.integrationMode").value("INTEGRATED"))
                .andExpect(jsonPath("$.caller.role").value("PHARMACIST"))
                .andExpect(jsonPath("$.caller.roleLabel").value("Pharmacist"))
                .andExpect(jsonPath("$.caller.setupAccess").value("VIEW"))
                .andExpect(jsonPath("$.capabilities[0]").value("SETUP_VIEW"))
                .andExpect(jsonPath("$.stores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.stores[0].type").value("PHARMACY"))
                .andExpect(jsonPath("$.stores[0].storeRole").value("STORE_USER"))
                .andExpect(jsonPath("$.stores[0].effectiveStoreRole").value("STORE_USER"))
                .andExpect(jsonPath("$.stores[0].access.receiveScheduleX").value(true))
                .andExpect(jsonPath("$.stores[0].access.approve").value(false))
                .andExpect(jsonPath("$.workingStoreId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.mustPickWorkingStore").value(false))
                .andExpect(jsonPath("$.fixedRules.fefo").value(true))
                .andExpect(jsonPath("$.fixedRules.negativeStockBlocked").value(true));
        verify(context).get(TENANT_ID, USER_ID);
    }

    @Test
    void moduleOffAndNoAccessAre403WithTheirOwnTypes() throws Exception {
        when(context.get(any(), any())).thenThrow(new ModuleOffException("Pharmacy & Inventory is not switched on for your clinic."));
        mockMvc.perform(get(URL + "/context").with(as("OWNER"))).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/module-off"))
                .andExpect(jsonPath("$.detail").value("Pharmacy & Inventory is not switched on for your clinic."));

        doThrow(new ForbiddenException("forbidden", "Not allowed",
                "You do not have access to Pharmacy & Inventory. Ask your owner.")).when(context).get(any(), any());
        mockMvc.perform(get(URL + "/context").with(as("STORE_STAFF"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"))
                .andExpect(jsonPath("$.detail").value("You do not have access to Pharmacy & Inventory. Ask your owner."));
    }

    // ---- PUT /context/working-store ----

    @Test
    void theWorkingStoreIsSavedAndTheNewContextReturned() throws Exception {
        when(context.setWorkingStore(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, pharmacistContext()));

        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON)
                        .content("{\"storeId\":\"" + STORE_ID + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores[0].name").value("Test Main Pharmacy"));
        var sent = BodyAnswers.captor();
        verify(context).setWorkingStore(eq(TENANT_ID), eq(USER_ID), sent.capture());
        org.junit.jupiter.api.Assertions.assertEquals(new PharmacyInputs.WorkingStore(STORE_ID), BodyAnswers.<PharmacyInputs.WorkingStore>read(sent));
    }

    @Test
    void anUnmappedStoreIs404AndABadBodyIs400WithFieldErrorsAndNothingEchoed() throws Exception {
        when(context.setWorkingStore(any(), any(), any())).thenAnswer(invocation -> {
            ((PharmacyInputs.Body<?>) invocation.getArgument(2)).read();
            throw new NotFoundException("This record was not found.");
        });
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON)
                        .content("{\"storeId\":\"" + STORE_ID + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-found"));

        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON)
                        .content("{\"storeId\":\"not-a-secret-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("storeId"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-secret-value"))));

        doThrow(new InvalidFieldsException(List.of(new FieldMessage("storeId", "Choose a store"))))
                .when(context).setWorkingStore(any(), any(), any());
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a store"));
    }

    @Test
    void anUnknownFieldInAClosedBodyIs400WhenTheServiceReadsIt() throws Exception {
        when(context.setWorkingStore(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, pharmacistContext()));
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON)
                        .content("{\"storeId\":\"" + STORE_ID + "\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Send only the fields listed for this request."))
                .andExpect(content().string(Matchers.not(Matchers.containsString("tenantId"))));
    }

    @Test
    void aMissingOrBrokenBodyIsMalformed() throws Exception {
        when(context.setWorkingStore(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, pharmacistContext()));
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"));
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"));
    }

    @Test
    void aBodyOver64KbIs413() throws Exception {
        String big = "{\"storeId\":\"" + STORE_ID + "\",\"pad\":\"" + "x".repeat(70_000) + "\"}";
        mockMvc.perform(put(URL + "/context/working-store").with(as("NURSE")).contentType(JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/payload-too-large"));
    }

    // ---- GET /setup-summary ----

    @Test
    void theSetupSummaryHasThreeCounts() throws Exception {
        when(summary.get(TENANT_ID, USER_ID)).thenReturn(new PharmacyViews.SetupSummary(7, 24, 12));
        mockMvc.perform(get(URL + "/setup-summary").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCount").value(7)).andExpect(jsonPath("$.mappedStaffCount").value(24))
                .andExpect(jsonPath("$.activeVendorCount").value(12));
    }

    // ---- unit settings ----

    @Test
    void unitSettingsHaveTheContractShapeAndFefoAndNegativeStockAreFacts() throws Exception {
        when(units.get(TENANT_ID, USER_ID)).thenReturn(settings());
        mockMvc.perform(get(URL + "/unit-settings").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.unitName").value("Test Clinic Baner"))
                .andExpect(jsonPath("$.unitCode").value("TCB"))
                .andExpect(jsonPath("$.gstin").value("27AAAAA0000A1Z5"))
                .andExpect(jsonPath("$.drugLicenceNo").value("TEST-DL-0001"))
                .andExpect(jsonPath("$.drugLicenceValidTill").value("2028-03-31"))
                .andExpect(jsonPath("$.drugLicenceExpired").value(false))
                .andExpect(jsonPath("$.integrationMode").value("INTEGRATED"))
                .andExpect(jsonPath("$.fefo").value(true))
                .andExpect(jsonPath("$.negativeStockBlocked").value(true))
                .andExpect(jsonPath("$.ipdAvailable").value(true))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-17T06:05:00Z"));
    }

    @Test
    void theSaveBodyIsPassedAsTypedAndTheAnswerIsTheNewSettings() throws Exception {
        when(units.save(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, settings()));
        mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON).content(
                        "{\"version\":3,\"gstin\":\"27AAAAA0000A1Z5\",\"drugLicenceNo\":\"TEST-DL-0001\","
                                + "\"drugLicenceValidTill\":\"2028-03-31\",\"integrationMode\":\"STANDALONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        var sent = BodyAnswers.captor();
        verify(units).save(eq(TENANT_ID), eq(USER_ID), sent.capture());
        org.junit.jupiter.api.Assertions.assertEquals(PharmacyInputs.UnitSettings.of(3L, "27AAAAA0000A1Z5", "TEST-DL-0001",
                LocalDate.of(2028, 3, 31), IntegrationMode.STANDALONE), BodyAnswers.<PharmacyInputs.UnitSettings>read(sent));
    }

    @Test
    void aFieldLeftOutOfTheSaveBodyReachesTheServiceAsAbsentNotAsNull() throws Exception {
        when(units.save(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, settings()));
        mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":3,\"gstin\":null,\"integrationMode\":\"STANDALONE\"}"))
                .andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(units).save(eq(TENANT_ID), eq(USER_ID), sent.capture());
        org.junit.jupiter.api.Assertions.assertEquals(new PharmacyInputs.UnitSettings(3L, PharmacyInputs.Sent.of(null),
                PharmacyInputs.Sent.absent(), PharmacyInputs.Sent.absent(), IntegrationMode.STANDALONE),
                BodyAnswers.<PharmacyInputs.UnitSettings>read(sent));
    }

    @Test
    void fefoNegativeStockUnitNameAndUnitCodeCannotBeSent() throws Exception {
        when(units.save(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, settings()));
        for (String extra : new String[] {"\"fefo\":false", "\"negativeStockBlocked\":false", "\"unitName\":\"x\"",
                "\"unitCode\":\"ABC\""}) {
            mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON)
                            .content("{\"version\":0,\"integrationMode\":\"INTEGRATED\"," + extra + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        }
    }

    @Test
    void anUnknownModeOrADateThatIsNotADateIs400OnTheFieldAndAnythingTypedIsNotEchoed() throws Exception {
        when(units.save(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, settings()));
        mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":0,\"integrationMode\":\"SOMETIMES\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("integrationMode"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("SOMETIMES"))));
        mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":0,\"integrationMode\":\"INTEGRATED\",\"drugLicenceValidTill\":\"31/03/2028\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("drugLicenceValidTill"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a valid date"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("31/03/2028"))));
    }

    @Test
    void aConflictCarriesTheCurrentSettings() throws Exception {
        when(units.save(any(), any(), any())).thenThrow(PharmacyProblemException.settingsChanged(settings()));
        mockMvc.perform(put(URL + "/unit-settings").with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":1,\"integrationMode\":\"INTEGRATED\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/settings-changed"))
                .andExpect(jsonPath("$.current.version").value(3))
                .andExpect(jsonPath("$.current.integrationMode").value("INTEGRATED"));
    }

    @Test
    void aNonOwnerWriteIsTheServicesForbidden() throws Exception {
        when(units.save(any(), any(), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Only the clinic owner can do this."));
        mockMvc.perform(put(URL + "/unit-settings").with(as("PHARMACIST")).contentType(JSON)
                        .content("{\"version\":0,\"integrationMode\":\"INTEGRATED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Only the clinic owner can do this."));
    }
}
