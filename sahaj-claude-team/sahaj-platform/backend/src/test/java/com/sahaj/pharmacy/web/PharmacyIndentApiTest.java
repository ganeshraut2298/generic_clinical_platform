package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.IndentService;
import com.sahaj.pharmacy.application.IndentViews;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
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

/**
 * SAH-79-9 over HTTP (docs/api/pharmacy.yaml 0.3.4): the paths, the raw text of every path id and query value passed on, the closed
 * bodies, 201 and 200 of a create, and the lazily read body that the service asks for after its gate.
 */
@WebMvcTest(controllers = IndentController.class)
@Import(SecurityConfig.class)
class PharmacyIndentApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String STORE = "00000000-0000-4000-8000-0000000000a1";
    private static final String INDENT = "00000000-0000-4000-8000-0000000000e1";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000c1";
    private static final String KEY = "00000000-0000-4000-8000-00000000f001";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private IndentService indents;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static IndentViews.Indent indent() {
        return new IndentViews.Indent(UUID.fromString(INDENT), null, null, null, "URGENT", "DRAFT",
                new IndentViews.Chips(0, 0, false, 0), null, null, "Acting as Test Nurse · Store user · Test Ward 3B", List.of(), null,
                List.of(), null, List.of(), 0);
    }

    @Test
    void everyIndentOperationNeedsASignedInUser() throws Exception {
        String base = URL + "/stores/" + STORE + "/indents";
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(base + "/" + INDENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(base).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(base + "/" + INDENT).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        for (String action : new String[] {"discard", "submit", "cancel"}) {
            mockMvc.perform(post(base + "/" + INDENT + "/" + action).contentType(JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theListPassesTheRawTextOfTheViewStatusAndPagingToTheService() throws Exception {
        when(indents.list(any(), any(), any(), any(), any(), any(), any())).thenReturn(new IndentViews.Page(List.of(), 0, 0, 20));

        mockMvc.perform(get(URL + "/stores/" + STORE + "/indents?view=TO_DISPATCH&status=APPROVED&page=2&size=5").with(as("NURSE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.size").value(20));
        verify(indents).list(TENANT_ID, USER_ID, STORE, "TO_DISPATCH", "APPROVED", "2", "5");

        mockMvc.perform(get(URL + "/stores/not-an-id/indents/also-not").with(as("NURSE")));
        verify(indents).get(TENANT_ID, USER_ID, "not-an-id", "also-not");
    }

    @Test
    void aCreateAnswers201AndAReplayOfTheSameKeyAnswers200WithTheBodyReadByTheService() throws Exception {
        when(indents.create(any(), any(), any(), any())).thenAnswer(BodyAnswers.readBody(3,
                new PharmacyViews.Outcome<>(indent(), true)));
        String body = "{\"clientKey\":\"" + KEY + "\",\"priority\":\"URGENT\",\"lines\":[{\"productId\":\"" + PRODUCT
                + "\",\"requestedQty\":20}]}";

        mockMvc.perform(post(URL + "/stores/" + STORE + "/indents").with(as("NURSE")).contentType(JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.chips.scheduleXWaiting").value(0));
        var sent = BodyAnswers.captor();
        verify(indents).create(eq(TENANT_ID), eq(USER_ID), eq(STORE), sent.capture());
        PharmacyInputs.IndentCreate in = BodyAnswers.read(sent);
        assertThat(in.priority()).isEqualTo("URGENT");
        assertThat(in.lines()).singleElement().satisfies(l -> {
            assertThat(l.productId()).isEqualTo(UUID.fromString(PRODUCT));
            assertThat(l.requestedQty()).isEqualByComparingTo("20");
        });

        org.mockito.Mockito.doAnswer(BodyAnswers.readBody(3, new PharmacyViews.Outcome<>(indent(), false))).when(indents)
                .create(any(), any(), any(), any());
        mockMvc.perform(post(URL + "/stores/" + STORE + "/indents").with(as("NURSE")).contentType(JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownFieldInAnyIndentBodyIsRefusedAs400WithoutEchoingIt() throws Exception {
        when(indents.create(any(), any(), any(), any())).thenAnswer(BodyAnswers.readBody(3,
                new PharmacyViews.Outcome<>(indent(), true)));
        when(indents.submit(any(), any(), any(), any(), any())).thenAnswer(BodyAnswers.readBody(4, indent()));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/indents").with(as("NURSE")).contentType(JSON)
                        .content("{\"clientKey\":\"" + KEY + "\",\"priority\":\"ROUTINE\",\"lines\":[],\"approvedQty\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("approvedQty"))));
        mockMvc.perform(post(URL + "/stores/" + STORE + "/indents/" + INDENT + "/submit").with(as("NURSE")).contentType(JSON)
                        .content("{\"clientKey\":\"" + KEY + "\",\"version\":1,\"priority\":\"URGENT\"}"))
                .andExpect(status().isBadRequest());
    }
}
