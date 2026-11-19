package com.sahaj.pharmacy.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.ContextService;
import com.sahaj.pharmacy.application.SetupSummaryService;
import com.sahaj.pharmacy.application.StaffMappingService;
import com.sahaj.pharmacy.application.StoreService;
import com.sahaj.pharmacy.application.UnitService;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Contract general rule 2 over HTTP (code review F-3, security L1): module off 403, access level 403, then 400 for the body.
 * The controllers do not bind the body before the service has answered its gate, so a body that is broken, unreadable, or
 * carries an unknown field gets the service's 403 (or module-off 403) and never a 400. The services here are mocks that
 * refuse at the gate without reading the body, which is what the real ones do (see BodyAfterGateTest).
 */
@WebMvcTest(controllers = {ContextController.class, UnitSettingsController.class, StoreController.class,
        StaffController.class})
@Import(SecurityConfig.class)
class PharmacyBodyAfterGateApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final List<String> BAD_BODIES = List.of("{", "not json at all", "[1,2,3]",
            "{\"unknownField\":true,\"tenantId\":\"x\"}", "{\"storeId\":\"not-a-uuid\",\"version\":\"abc\"}", "null");

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private ContextService context;
    @MockBean
    private SetupSummaryService summary;
    @MockBean
    private UnitService units;
    @MockBean
    private StoreService stores;
    @MockBean
    private StaffMappingService staff;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    /** Every operation of the pharmacy API that takes a body. */
    private static List<MockHttpServletRequestBuilder> operations() {
        return List.of(put(URL + "/context/working-store"), put(URL + "/unit-settings"), post(URL + "/stores"),
                put(URL + "/stores/" + ID), post(URL + "/stores/" + ID + "/staff"),
                put(URL + "/stores/" + ID + "/staff/" + ID));
    }

    private void refuseEverythingAtTheGate(RuntimeException refusal) {
        when(context.setWorkingStore(any(), any(), any())).thenThrow(refusal);
        when(units.save(any(), any(), any())).thenThrow(refusal);
        when(stores.create(any(), any(), any())).thenThrow(refusal);
        when(stores.update(any(), any(), anyString(), any())).thenThrow(refusal);
        when(staff.map(any(), any(), anyString(), any())).thenThrow(refusal);
        when(staff.change(any(), any(), anyString(), anyString(), any())).thenThrow(refusal);
    }

    @Test
    void aBrokenBodyFromACallerWhoseModuleIsOffGets403ModuleOff() throws Exception {
        refuseEverythingAtTheGate(new ModuleOffException("Pharmacy & Inventory is not switched on for your clinic."));
        for (MockHttpServletRequestBuilder operation : operations()) {
            for (String body : BAD_BODIES) {
                mockMvc.perform(operation.with(as("OWNER")).contentType(JSON).content(body))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/module-off"));
            }
        }
    }

    @Test
    void aBrokenBodyFromAnUnderPrivilegedCallerGets403ForbiddenAndNothingAboutTheBody() throws Exception {
        refuseEverythingAtTheGate(new ForbiddenException("forbidden", "Not allowed", "Only the clinic owner can do this."));
        for (MockHttpServletRequestBuilder operation : operations()) {
            for (String body : BAD_BODIES) {
                mockMvc.perform(operation.with(as("NURSE")).contentType(JSON).content(body))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"))
                        .andExpect(content().string(Matchers.not(Matchers.containsString("unknownField"))))
                        .andExpect(content().string(Matchers.not(Matchers.containsString("Send only the fields"))));
            }
        }
    }

    @Test
    void anEmptyBodyFromARefusedCallerIsAlso403() throws Exception {
        refuseEverythingAtTheGate(new ForbiddenException("forbidden", "Not allowed", "Only the clinic owner can do this."));
        for (MockHttpServletRequestBuilder operation : operations()) {
            mockMvc.perform(operation.with(as("NURSE")).contentType(JSON)).andExpect(status().isForbidden());
        }
    }

    @Test
    void aPathIdThatIsNotAnIdAlsoReachesTheServiceFirst() throws Exception {
        when(stores.update(any(), any(), anyString(), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Only the clinic owner can do this."));
        mockMvc.perform(put(URL + "/stores/not-a-uuid").with(as("NURSE")).contentType(JSON).content("{"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aWrongContentTypeIsStillRefusedAs415BeforeTheService() throws Exception {
        mockMvc.perform(post(URL + "/stores").with(as("OWNER")).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        org.mockito.Mockito.verifyNoInteractions(stores);
    }
}
