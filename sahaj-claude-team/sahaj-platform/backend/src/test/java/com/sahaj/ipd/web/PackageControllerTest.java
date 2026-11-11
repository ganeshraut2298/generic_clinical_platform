package com.sahaj.ipd.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.PackageService;
import com.sahaj.ipd.application.PackageViews.IpdPackage;
import com.sahaj.ipd.application.PackageViews.PackageList;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-29 GET /packages: contract shape, roles, problem types, and no write verb. */
@WebMvcTest(controllers = PackageController.class)
@Import(SecurityConfig.class)
class PackageControllerTest {

    private static final String URL = "/api/v1/ipd/packages";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PACKAGE_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PackageService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void theFourReadingRolesGetTheListInTheContractShape() throws Exception {
        when(service.list(TENANT_ID)).thenReturn(new PackageList(List.of(
                new IpdPackage(PACKAGE_ID, "Appendicectomy", 4_500_000L, 3),
                new IpdPackage(UUID.randomUUID(), "Cataract surgery", 2_500_000L, 0))));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].id").value(PACKAGE_ID.toString()))
                    .andExpect(jsonPath("$.items[0].name").value("Appendicectomy"))
                    .andExpect(jsonPath("$.items[0].pricePaise").value(4_500_000))
                    .andExpect(jsonPath("$.items[0].includedDays").value(3))
                    .andExpect(jsonPath("$.items[1].includedDays").value(0));
        }
    }

    @Test
    void aClinicWithNoPackageGetsAnEmptyItemsArray() throws Exception {
        when(service.list(TENANT_ID)).thenReturn(new PackageList(List.of()));

        mockMvc.perform(get(URL).with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void everyOtherRoleGets403() throws Exception {
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(URL).with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void moduleOffAnswers403ModuleOff() throws Exception {
        when(service.list(TENANT_ID)).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(get(URL).with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void thereIsNoEndpointToCreateChangeOrRemoveAPackage() throws Exception {
        String body = "{\"name\":\"X\",\"pricePaise\":100,\"includedDays\":1}";

        mockMvc.perform(post(URL).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL + "/" + PACKAGE_ID).with(as("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound());
        mockMvc.perform(delete(URL + "/" + PACKAGE_ID).with(as("OWNER"))).andExpect(status().isNotFound());
    }
}
