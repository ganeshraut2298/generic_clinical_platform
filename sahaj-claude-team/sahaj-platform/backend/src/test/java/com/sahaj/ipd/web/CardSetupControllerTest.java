package com.sahaj.ipd.web;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.CardSetupService;
import com.sahaj.ipd.application.CardSetupViews.CardDraft;
import com.sahaj.ipd.application.CardSetupViews.CardLive;
import com.sahaj.ipd.application.CardSetupViews.CardSetupView;
import com.sahaj.ipd.application.CardSetupViews.CardSetupWrite;
import com.sahaj.ipd.application.CardSetupViews.CardVersion;
import com.sahaj.ipd.domain.card.CardCatalogue;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/** SAH-37 card setup endpoints: roles, shapes (draft and versions absent for non-owners), status codes, problem types. */
@WebMvcTest(controllers = CardSetupController.class)
@Import(SecurityConfig.class)
class CardSetupControllerTest {

    private static final String URL = "/api/v1/ipd/card-setup";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-09T05:30:00Z");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CardSetupService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static CardLive live() {
        return new CardLive(1, true, CardSetupDefaults.standard(), null, null);
    }

    private static CardSetupView ownerView(Optional<CardDraft> draft) {
        return new CardSetupView(CardCatalogue.catalogue(), CardSetupDefaults.standard(), CardSetupDefaults.templates(),
                live(), draft, List.of(new CardVersion(1, "Sahaj standard patient card (default)", true, List.of(),
                        null, null, true)),
                true, null, "on");
    }

    private static CardSetupView staffView() {
        return new CardSetupView(CardCatalogue.catalogue(), CardSetupDefaults.standard(), CardSetupDefaults.templates(),
                live(), null, null, false, "ROLE", "on");
    }

    private static String body(Integer base, Long revision) throws Exception {
        return MAPPER.writeValueAsString(new CardSetupWrite(base, revision, CardSetupDefaults.standard()));
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/draft").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL + "/draft?draftRevision=1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/publish").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void theFourListRolesReadAndNoOtherRoleDoes() throws Exception {
        when(service.view(eq(TENANT_ID), eq(USER_ID), any())).thenReturn(staffView());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL).with(as(role))).andExpect(status().isOk());
        }
        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR"}) {
            mockMvc.perform(get(URL).with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theOwnerAnswerHasTheCatalogueTheDraftAndTheVersions() throws Exception {
        when(service.view(TENANT_ID, USER_ID, "OWNER")).thenReturn(ownerView(Optional.empty()));

        String raw = mockMvc.perform(get(URL).with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogue.fields.length()").value(25))
                .andExpect(jsonPath("$.catalogue.sections.length()").value(7))
                .andExpect(jsonPath("$.catalogue.actions.length()").value(5))
                .andExpect(jsonPath("$.catalogue.labelMaxLength").value(40))
                .andExpect(jsonPath("$.catalogue.fields[0].standardSection").doesNotExist())
                .andExpect(jsonPath("$.templates.length()").value(3))
                .andExpect(jsonPath("$.templates[0].key").value("smallClinic"))
                .andExpect(jsonPath("$.standard.layout").value("standard"))
                .andExpect(jsonPath("$.standard.cardsPerRow").value(3))
                .andExpect(jsonPath("$.live.version").value(1))
                .andExpect(jsonPath("$.live.isStandard").value(true))
                .andExpect(jsonPath("$.live.publishedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.versions[0].live").value(true))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.readOnlyReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.state").value("on"))
                .andReturn().getResponse().getContentAsString();

        // the owner without a draft: "draft": null is written
        JsonNode json = MAPPER.readTree(raw);
        org.assertj.core.api.Assertions.assertThat(json.has("draft")).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.get("draft").isNull()).isTrue();
    }

    @Test
    void theOwnersDraftIsInTheAnswerWithItsRevisionAndChangedParts() throws Exception {
        CardDraft draft = new CardDraft(3L, 1, CardSetupDefaults.standard(), AT, "Dr Owner", List.of("layout"));
        when(service.view(TENANT_ID, USER_ID, "OWNER")).thenReturn(ownerView(Optional.of(draft)));

        mockMvc.perform(get(URL).with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.revision").value(3))
                .andExpect(jsonPath("$.draft.baseVersion").value(1))
                .andExpect(jsonPath("$.draft.savedAt").value("2026-11-09T05:30:00Z"))
                .andExpect(jsonPath("$.draft.savedByName").value("Dr Owner"))
                .andExpect(jsonPath("$.draft.changedParts[0]").value("layout"));
    }

    @Test
    void draftAndVersionsAreAbsentNotNullInTheRawJsonOfTheOtherRoles() throws Exception {
        when(service.view(eq(TENANT_ID), eq(USER_ID), any())).thenReturn(staffView());

        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR"}) {
            String raw = mockMvc.perform(get(URL).with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.canEdit").value(false))
                    .andExpect(jsonPath("$.readOnlyReason").value("ROLE"))
                    .andExpect(jsonPath("$.draft").doesNotExist())
                    .andExpect(jsonPath("$.versions").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(raw).doesNotContain("\"draft\"", "\"versions\"");
        }
    }

    @Test
    void onlyTheOwnerMayWrite() throws Exception {
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING", "PHARMACIST"}) {
            mockMvc.perform(put(URL + "/draft").with(as(role)).contentType(JSON).content(body(1, null)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(URL + "/draft?draftRevision=1").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/publish").with(as(role)).contentType(JSON).content(body(1, null)))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).saveDraft(any(), any(), any(), any());
        verify(service, never()).discardDraft(any(), any(), any(), any());
        verify(service, never()).publish(any(), any(), any(), any());
    }

    @Test
    void saveDraftPassesTheBodyAsReceivedWithTheClinicAndPersonOfTheSignedInUser() throws Exception {
        when(service.saveDraft(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(ownerView(Optional.empty()));

        mockMvc.perform(put(URL + "/draft").with(as("OWNER")).contentType(JSON).content(body(1, null)))
                .andExpect(status().isOk());

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).saveDraft(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().get("baseVersion").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().get("setup").get("layout").asText())
                .isEqualTo("standard");
    }

    @Test
    void aTenantIdInTheBodyIsPassedOnToTheStrictReaderAndNeverUsed() throws Exception {
        when(service.saveDraft(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(ownerView(Optional.empty()));
        String withTenant = body(1, null).replaceFirst("\\{", "{\"tenantId\":\"" + UUID.randomUUID() + "\",");

        mockMvc.perform(put(URL + "/draft").with(as("OWNER")).contentType(JSON).content(withTenant))
                .andExpect(status().isOk());

        verify(service).saveDraft(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any());
    }

    @Test
    void discardNeedsTheDraftRevisionQueryParameter() throws Exception {
        when(service.discardDraft(TENANT_ID, USER_ID, "OWNER", 4L)).thenReturn(ownerView(Optional.empty()));

        mockMvc.perform(delete(URL + "/draft?draftRevision=4").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live.version").value(1));
        mockMvc.perform(delete(URL + "/draft").with(as("OWNER"))).andExpect(status().isBadRequest());
        mockMvc.perform(delete(URL + "/draft?draftRevision=abc").with(as("OWNER"))).andExpect(status().isBadRequest());
        verify(service).discardDraft(TENANT_ID, USER_ID, "OWNER", 4L);
    }

    @Test
    void publishAnswersTheNewLiveVersion() throws Exception {
        CardSetupView published = new CardSetupView(CardCatalogue.catalogue(), CardSetupDefaults.standard(),
                CardSetupDefaults.templates(), new CardLive(2, false, CardSetupDefaults.standard(), AT, "Dr Owner"),
                Optional.empty(), List.of(), true, null, "on");
        when(service.publish(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(published);

        mockMvc.perform(post(URL + "/publish").with(as("OWNER")).contentType(JSON).content(body(1, 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live.version").value(2))
                .andExpect(jsonPath("$.live.isStandard").value(false))
                .andExpect(jsonPath("$.live.publishedByName").value("Dr Owner"))
                .andExpect(jsonPath("$.draft").value(Matchers.nullValue()));
    }

    @Test
    void conflictsAreProblemJsonWithTheContractTypesAndMessages() throws Exception {
        when(service.saveDraft(any(), any(), any(), any())).thenThrow(new ConflictException("card-setup-changed",
                "Card setup changed", "The card setup was changed by someone else. Reload to see it."));
        when(service.publish(any(), any(), any(), any())).thenThrow(new ConflictException("card-setup-unchanged",
                "Nothing to publish", "There are no changes to publish."));

        mockMvc.perform(put(URL + "/draft").with(as("OWNER")).contentType(JSON).content(body(1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("card-setup-changed")))
                .andExpect(jsonPath("$.detail").value("The card setup was changed by someone else. Reload to see it."));
        mockMvc.perform(post(URL + "/publish").with(as("OWNER")).contentType(JSON).content(body(1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("card-setup-unchanged")))
                .andExpect(jsonPath("$.detail").value("There are no changes to publish."));
    }

    @Test
    void aValidationFailureIs400WithEveryFieldErrorAndTheFirstMessageAsDetail() throws Exception {
        when(service.publish(any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("setup.sections[5].fields[1].on", "Allergies is a safety field and cannot be hidden."),
                new FieldMessage("setup.sections[5].fields[1].label", "The label of Allergies cannot be changed.")),
                true));

        mockMvc.perform(post(URL + "/publish").with(as("OWNER")).contentType(JSON).content(body(1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("validation")))
                .andExpect(jsonPath("$.detail").value("Allergies is a safety field and cannot be hidden."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("setup.sections[5].fields[1].on"));
    }

    @Test
    void moduleOffIs403ModuleOff() throws Exception {
        when(service.view(any(), any(), any())).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(get(URL).with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void aBodyThatIsNotJsonIs400() throws Exception {
        mockMvc.perform(put(URL + "/draft").with(as("OWNER")).contentType(JSON).content("{nope"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(URL + "/draft").with(as("OWNER")).contentType(JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theStandardSetupInTheAnswerIsTheOneTheServerHolds() throws Exception {
        when(service.view(TENANT_ID, USER_ID, "OWNER")).thenReturn(ownerView(Optional.empty()));

        String raw = mockMvc.perform(get(URL).with(as("OWNER"))).andReturn().getResponse().getContentAsString();

        CardSetup parsed = MAPPER.readerFor(CardSetup.class).readValue(MAPPER.readTree(raw).get("standard"));
        org.assertj.core.api.Assertions.assertThat(parsed).isEqualTo(CardSetupDefaults.standard());
    }
}
