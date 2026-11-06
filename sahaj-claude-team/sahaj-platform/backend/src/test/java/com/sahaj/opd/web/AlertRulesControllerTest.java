package com.sahaj.opd.web;

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

import com.sahaj.opd.application.AlertRuleRepository.AlertRuleRow;
import com.sahaj.opd.application.AlertRulesService;
import com.sahaj.opd.application.AlertRulesView;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/** SAH-18 endpoints: contract shape, owner-only writes (403), 400/404/409 problems. */
@WebMvcTest(controllers = AlertRulesController.class)
@Import(SecurityConfig.class)
class AlertRulesControllerTest {

    private static final String BASE = "/api/v1/opd/alert-rules";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID RULE_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String ADD = "{\"when\":\"Billing\",\"cond\":\"bill not paid\",\"then\":\"Alert owner\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertRulesService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static AlertRulesView view() {
        return new AlertRulesView(
                List.of(new AlertRuleRow(null, "Vitals", "not done in 10 min", "Alert nurse", true),
                        new AlertRuleRow(RULE_ID, "Billing", "bill not paid", "Alert owner", false)),
                List.of(new AlertRulesView.Suggestion(2, "Consultation", "waiting over 45 min", "Alert owner", "Why")),
                new AlertRulesView.Options(List.of("Token"), List.of("bill not paid"), List.of("Alert owner")));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsWithContractFieldNames() throws Exception {
        when(service.get(TENANT_ID)).thenReturn(view());

        mockMvc.perform(get(BASE).with(as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.rules[0].when").value("Vitals"))
                .andExpect(jsonPath("$.rules[1].id").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.rules[1].then").value("Alert owner"))
                .andExpect(jsonPath("$.rules[1].enabled").value(false))
                .andExpect(jsonPath("$.suggestions[0].index").value(2))
                .andExpect(jsonPath("$.suggestions[0].why").value("Why"))
                .andExpect(jsonPath("$.options.when[0]").value("Token"))
                .andExpect(jsonPath("$.options.cond[0]").value("bill not paid"))
                .andExpect(jsonPath("$.options.then[0]").value("Alert owner"));
    }

    @Test
    void ownerAddsARule() throws Exception {
        when(service.add(TENANT_ID, USER_ID, "Billing", "bill not paid", "Alert owner")).thenReturn(view());

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON).content(ADD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules.length()").value(2));
    }

    @Test
    void ownerSavesTheStarterRulesAddsASuggestionSwitchesAndDeletes() throws Exception {
        when(service.saveStarter(TENANT_ID, USER_ID)).thenReturn(view());
        when(service.addSuggestion(TENANT_ID, USER_ID, 2)).thenReturn(view());
        when(service.setEnabled(TENANT_ID, USER_ID, RULE_ID, true)).thenReturn(view());
        when(service.delete(TENANT_ID, USER_ID, RULE_ID)).thenReturn(view());

        mockMvc.perform(post(BASE + "/starter").with(as("OWNER"))).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/suggestions/2").with(as("OWNER"))).andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/" + RULE_ID).with(as("OWNER")).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/" + RULE_ID).with(as("OWNER"))).andExpect(status().isOk());
    }

    @Test
    void aNonOwnerGets403OnEveryWrite() throws Exception {
        mockMvc.perform(post(BASE).with(as("DOCTOR")).contentType(JSON).content(ADD)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/starter").with(as("NURSE"))).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/suggestions/0").with(as("FRONT_DESK"))).andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/" + RULE_ID).with(as("DOCTOR")).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/" + RULE_ID).with(as("NURSE"))).andExpect(status().isForbidden());
        verify(service, never()).add(any(), any(), any(), any(), any());
        verify(service, never()).delete(any(), any(), any());
    }

    @Test
    void badInputIs400() throws Exception {
        when(service.add(eq(TENANT_ID), eq(USER_ID), any(), any(), any()))
                .thenThrow(new InvalidInputException("when", "Choose what the rule watches"));

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("when"));
    }

    @Test
    void aDuplicateIs409WithThePlainMessage() throws Exception {
        when(service.add(eq(TENANT_ID), eq(USER_ID), any(), any(), any()))
                .thenThrow(new ConflictException("alert-rule-exists", "That rule already exists",
                        "That rule already exists."));

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON).content(ADD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That rule already exists."));
    }

    @Test
    void anUnknownRuleOrSuggestionIs404() throws Exception {
        when(service.delete(TENANT_ID, USER_ID, RULE_ID)).thenThrow(new NotFoundException("x"));
        when(service.addSuggestion(TENANT_ID, USER_ID, 9)).thenThrow(new NotFoundException("x"));

        mockMvc.perform(delete(BASE + "/" + RULE_ID).with(as("OWNER"))).andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/suggestions/9").with(as("OWNER"))).andExpect(status().isNotFound());
    }
}
