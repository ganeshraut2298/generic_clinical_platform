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

import com.sahaj.ipd.application.EmergencyService;
import com.sahaj.ipd.application.EmergencyViews.ActedCase;
import com.sahaj.ipd.application.EmergencyViews.Case;
import com.sahaj.ipd.application.EmergencyViews.CaseInput;
import com.sahaj.ipd.application.EmergencyViews.CaseRef;
import com.sahaj.ipd.application.EmergencyViews.Counts;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.ipd.application.EmergencyViews.LevelInput;
import com.sahaj.ipd.application.EmergencyViews.Queue;
import com.sahaj.ipd.application.EmergencyViews.Registration;
import com.sahaj.ipd.application.EmergencyViews.Settings;
import com.sahaj.ipd.application.EmergencyViews.SettingsInput;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
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

/** SAH-28 emergency endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = EmergencyController.class)
@Import(SecurityConfig.class)
class EmergencyControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");
    private static final String[] READ_ROLES = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};
    private static final String[] OTHER_ROLES = {"BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmergencyService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Case card() {
        return new Case(CASE_ID, "Unknown male, about 40", 40, "M", "Chest pain for 1 hour", true, 1, "Red", "ER-1",
                "WAITING", NOW.minusSeconds(2280), 38, 5, true, null);
    }

    private static Queue queue(ActedCase acted) {
        return new Queue("on", 3,
                List.of(new Level(1, "Red", "Immediate", "crit", 5), new Level(2, "Yellow", "Urgent", "warn", 30),
                        new Level(3, "Green", "Can wait", "good", 120)),
                2, true, 2, 1, new Counts(true, 1, 1), List.of(card()), true, true, true, true, NOW, acted);
    }

    private static String registerBody() {
        return "{\"clientKey\":\"" + KEY + "\",\"identityUnknown\":false,\"name\":\"Ramesh\",\"age\":52,"
                + "\"sex\":\"M\",\"mobile\":\"9876543210\",\"complaint\":\"Chest pain\",\"level\":2}";
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/emergency")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/emergency/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/emergency/cases").contentType(JSON).content(registerBody()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/emergency-settings").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theQueueIsInContractShapeForTheFourReadRolesAndPassesTheRole() throws Exception {
        for (String role : READ_ROLES) {
            when(service.queue(TENANT_ID, role)).thenReturn(queue(null));
            mockMvc.perform(get(URL + "/emergency").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("on"))
                    .andExpect(jsonPath("$.scale").value(3))
                    .andExpect(jsonPath("$.levels[0].name").value("Red"))
                    .andExpect(jsonPath("$.levels[0].tone").value("crit"))
                    .andExpect(jsonPath("$.levels[0].targetMinutes").value(5))
                    .andExpect(jsonPath("$.defaultLevel").value(2))
                    .andExpect(jsonPath("$.baysTotal").value(2))
                    .andExpect(jsonPath("$.baysFree").value(1))
                    .andExpect(jsonPath("$.counts.queue").value(1))
                    .andExpect(jsonPath("$.counts.overTarget").value(1))
                    .andExpect(jsonPath("$.counts.writable").value(true))
                    .andExpect(jsonPath("$.cases[0].id").value(CASE_ID.toString()))
                    .andExpect(jsonPath("$.cases[0].displayName").value("Unknown male, about 40"))
                    .andExpect(jsonPath("$.cases[0].levelName").value("Red"))
                    .andExpect(jsonPath("$.cases[0].bay").value("ER-1"))
                    .andExpect(jsonPath("$.cases[0].waitingMinutes").value(38))
                    .andExpect(jsonPath("$.cases[0].overTarget").value(true))
                    .andExpect(jsonPath("$.cases[0].identityPending").value(true))
                    .andExpect(jsonPath("$.cases[0].mobile").doesNotExist())
                    .andExpect(jsonPath("$.canRegister").value(true))
                    .andExpect(jsonPath("$.canAdmit").value(true))
                    .andExpect(jsonPath("$.asOf").exists());
        }
        for (String role : OTHER_ROLES) {
            mockMvc.perform(get(URL + "/emergency").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theSummaryHoldsCountsOnlyForTheFourReadRoles() throws Exception {
        for (String role : READ_ROLES) {
            when(service.summary(TENANT_ID, role)).thenReturn(new Counts(true, 4, 2));
            mockMvc.perform(get(URL + "/emergency/summary").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.writable").value(true))
                    .andExpect(jsonPath("$.queue").value(4))
                    .andExpect(jsonPath("$.overTarget").value(2))
                    .andExpect(jsonPath("$.cases").doesNotExist());
        }
        for (String role : OTHER_ROLES) {
            mockMvc.perform(get(URL + "/emergency/summary").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void moduleOffAnswers403ModuleOff() throws Exception {
        when(service.queue(any(), any())).thenThrow(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."));

        mockMvc.perform(get(URL + "/emergency").with(as("NURSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void registerAnswers201ForANewCaseAndPassesTheBodyTheRoleAndTheUser() throws Exception {
        when(service.register(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), any()))
                .thenReturn(new Registration(queue(card()), true));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content(registerBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actedCase.id").value(CASE_ID.toString()));

        ArgumentCaptor<CaseInput> sent = ArgumentCaptor.forClass(CaseInput.class);
        verify(service).register(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().clientKey()).isEqualTo(KEY.toString());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().identityUnknown().isBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().identityUnknown().booleanValue()).isFalse();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().age().decimalValue()).isEqualByComparingTo("52");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().level().decimalValue()).isEqualByComparingTo("2");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().sex()).isEqualTo("M");
    }

    @Test
    void registerAnswers200WithTheFirstCaseForARepeatOfTheClientKey() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(new Registration(queue(card()), false));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("OWNER")).contentType(JSON).content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actedCase.id").value(CASE_ID.toString()));
    }

    @Test
    void registerIsForTheFourReadRolesOnly() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(new Registration(queue(card()), true));

        for (String role : READ_ROLES) {
            mockMvc.perform(post(URL + "/emergency/cases").with(as(role)).contentType(JSON).content(registerBody()))
                    .andExpect(status().isCreated());
        }
        for (String role : OTHER_ROLES) {
            mockMvc.perform(post(URL + "/emergency/cases").with(as(role)).contentType(JSON).content(registerBody()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aRepeatOfTheKeyThatAnswersTheReferenceHasIdLevelAndStatusOnly() throws Exception {
        when(service.register(any(), any(), any(), any()))
                .thenReturn(new Registration(queue(new CaseRef(CASE_ID, 1, "RELEASED")), false));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("OWNER")).contentType(JSON).content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actedCase.id").value(CASE_ID.toString()))
                .andExpect(jsonPath("$.actedCase.level").value(1))
                .andExpect(jsonPath("$.actedCase.status").value("RELEASED"))
                .andExpect(jsonPath("$.actedCase.length()").value(3))
                .andExpect(jsonPath("$.actedCase.displayName").doesNotExist())
                .andExpect(jsonPath("$.actedCase.complaint").doesNotExist())
                .andExpect(jsonPath("$.actedCase.mobile").doesNotExist());
    }

    @Test
    void theFullCardIsStillAnsweredWithAllItsFields() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(new Registration(queue(card()), true));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content(registerBody()))
                .andExpect(jsonPath("$.actedCase.displayName").value("Unknown male, about 40"))
                .andExpect(jsonPath("$.actedCase.levelName").value("Red"))
                .andExpect(jsonPath("$.actedCase.mobile").doesNotExist());
    }

    @Test
    void typesThatDoNotFitAreReadAsTheyArriveAndLeftToTheServiceToAnswerWithTheFieldMessage() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(new Registration(queue(card()), true));
        String[] bodies = {
            "{\"clientKey\":\"" + KEY + "\",\"identityUnknown\":\"true\",\"name\":\"A\",\"age\":\"40\","
                    + "\"sex\":\"M\",\"complaint\":\"Fever\",\"level\":\"2\"}",
            "{\"clientKey\":\"" + KEY + "\",\"identityUnknown\":null,\"name\":\"A\",\"age\":null,"
                    + "\"sex\":\"M\",\"complaint\":\"Fever\",\"level\":2}",
            "{\"clientKey\":\"" + KEY + "\",\"name\":\"A\",\"sex\":\"M\",\"complaint\":\"Fever\",\"level\":2}",
            "{\"clientKey\":\"" + KEY + "\",\"identityUnknown\":1,\"age\":true,\"level\":{}}"
        };

        for (String body : bodies) {
            mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content(body))
                    .andExpect(status().isCreated());
        }

        ArgumentCaptor<CaseInput> sent = ArgumentCaptor.forClass(CaseInput.class);
        verify(service, org.mockito.Mockito.times(4)).register(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), sent.capture());
        List<CaseInput> all = sent.getAllValues();
        org.assertj.core.api.Assertions.assertThat(all.get(0).identityUnknown().isTextual()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(0).age().isTextual()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(0).level().isTextual()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(1).identityUnknown().isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(1).age().isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(2).identityUnknown()).isNull();
        org.assertj.core.api.Assertions.assertThat(all.get(3).identityUnknown().isNumber()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(3).age().isBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(all.get(3).level().isObject()).isTrue();
    }

    @Test
    void aLevelSentAsAStringReachesTheServiceInsteadOfBeingConvertedOrRefusedAsUnreadable() throws Exception {
        when(service.retriage(any(), any(), any(), any(), any())).thenReturn(queue(card()));

        mockMvc.perform(put(URL + "/emergency/cases/" + CASE_ID + "/level").with(as("NURSE")).contentType(JSON)
                .content("{\"level\":\"2\"}")).andExpect(status().isOk());

        ArgumentCaptor<LevelInput> sent = ArgumentCaptor.forClass(LevelInput.class);
        verify(service).retriage(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(CASE_ID), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().level().isTextual()).isTrue();
    }

    @Test
    void registerValidationAnswers400WithEveryFieldError() throws Exception {
        when(service.register(any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("name", "Enter a name, or tick \"Name not known yet\""),
                new FieldMessage("complaint", "Enter the main complaint"))));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("validation")))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a name, or tick \"Name not known yet\""))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("complaint"));
    }

    @Test
    void registerWithoutAKeyAnswers400OnClientKey() throws Exception {
        when(service.register(any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("clientKey", "Reload the page and try again."));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("clientKey"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Reload the page and try again."));
    }

    @Test
    void readOnlyAnswers409IpdReadonly() throws Exception {
        when(service.register(any(), any(), any(), any())).thenThrow(
                new ConflictException("ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed."));

        mockMvc.perform(post(URL + "/emergency/cases").with(as("NURSE")).contentType(JSON).content(registerBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
    }

    @Test
    void seenReleaseAndReferAreForOwnerNurseAndDoctorOnly() throws Exception {
        when(service.seen(any(), any(), any(), any())).thenReturn(queue(card()));
        when(service.release(any(), any(), any(), any())).thenReturn(queue(card()));
        when(service.refer(any(), any(), any(), any())).thenReturn(queue(card()));

        for (String role : new String[] {"OWNER", "NURSE", "DOCTOR"}) {
            for (String action : new String[] {"seen", "release", "refer"}) {
                mockMvc.perform(post(URL + "/emergency/cases/" + CASE_ID + "/" + action).with(as(role)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.actedCase.id").value(CASE_ID.toString()));
            }
        }
        for (String role : new String[] {"FRONT_DESK", "BILLING", "VISITING_DOCTOR"}) {
            for (String action : new String[] {"seen", "release", "refer"}) {
                mockMvc.perform(post(URL + "/emergency/cases/" + CASE_ID + "/" + action).with(as(role)))
                        .andExpect(status().isForbidden());
            }
        }
        verify(service).seen(TENANT_ID, USER_ID, "DOCTOR", CASE_ID);
        verify(service).release(TENANT_ID, USER_ID, "NURSE", CASE_ID);
        verify(service).refer(TENANT_ID, USER_ID, "OWNER", CASE_ID);
    }

    @Test
    void aClosedCaseAnswers409CaseClosedAndAnUnknownCase404() throws Exception {
        when(service.release(any(), any(), any(), eq(CASE_ID))).thenThrow(
                new ConflictException("case-closed", "Patient is no longer in the queue",
                        "This patient is no longer in the queue."));
        UUID missing = UUID.randomUUID();
        when(service.release(any(), any(), any(), eq(missing))).thenThrow(new NotFoundException("Emergency case not found"));

        mockMvc.perform(post(URL + "/emergency/cases/" + CASE_ID + "/release").with(as("NURSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("case-closed")))
                .andExpect(jsonPath("$.detail").value("This patient is no longer in the queue."));
        mockMvc.perform(post(URL + "/emergency/cases/" + missing + "/release").with(as("NURSE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedCaseIdAnswers400NotAServerError() throws Exception {
        mockMvc.perform(post(URL + "/emergency/cases/not-a-uuid/seen").with(as("NURSE")))
                .andExpect(status().isBadRequest());
        verify(service, never()).seen(any(), any(), any(), any());
    }

    @Test
    void retriageIsForTheFourReadRolesAndPassesTheLevel() throws Exception {
        when(service.retriage(any(), any(), any(), any(), any())).thenReturn(queue(card()));

        for (String role : READ_ROLES) {
            mockMvc.perform(put(URL + "/emergency/cases/" + CASE_ID + "/level").with(as(role)).contentType(JSON)
                    .content("{\"level\":3}")).andExpect(status().isOk());
        }
        for (String role : OTHER_ROLES) {
            mockMvc.perform(put(URL + "/emergency/cases/" + CASE_ID + "/level").with(as(role)).contentType(JSON)
                    .content("{\"level\":3}")).andExpect(status().isForbidden());
        }
        ArgumentCaptor<LevelInput> sent = ArgumentCaptor.forClass(LevelInput.class);
        verify(service).retriage(eq(TENANT_ID), eq(USER_ID), eq("DOCTOR"), eq(CASE_ID), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().level().decimalValue()).isEqualByComparingTo("3");
    }

    @Test
    void retriageWithABadLevelAnswers400OnLevel() throws Exception {
        when(service.retriage(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("level", "Choose a triage level"));

        mockMvc.perform(put(URL + "/emergency/cases/" + CASE_ID + "/level").with(as("NURSE")).contentType(JSON)
                .content("{\"level\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("level"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a triage level"));
    }

    @Test
    void settingsAreReadByTheFourReadRolesAndWrittenByTheOwnerOnly() throws Exception {
        Settings settings = new Settings(3, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true,
                List.of(new Level(1, "Red", "Immediate", "crit", 5)), true);
        when(service.settings(eq(TENANT_ID), any())).thenReturn(settings);
        when(service.updateSettings(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(settings);
        String body = "{\"scale\":3,\"targets3\":[5,30,120],\"targets5\":[0,10,30,60,120],\"bays\":2,"
                + "\"unknownPatientRegistration\":true}";

        for (String role : READ_ROLES) {
            mockMvc.perform(get(URL + "/emergency-settings").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scale").value(3))
                    .andExpect(jsonPath("$.targets3[1]").value(30))
                    .andExpect(jsonPath("$.targets5[4]").value(120))
                    .andExpect(jsonPath("$.bays").value(2))
                    .andExpect(jsonPath("$.unknownPatientRegistration").value(true))
                    .andExpect(jsonPath("$.levels[0].name").value("Red"))
                    .andExpect(jsonPath("$.canEdit").value(true));
        }
        for (String role : OTHER_ROLES) {
            mockMvc.perform(get(URL + "/emergency-settings").with(as(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(put(URL + "/emergency-settings").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk());
        ArgumentCaptor<SettingsInput> sent = ArgumentCaptor.forClass(SettingsInput.class);
        verify(service).updateSettings(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().targets5()).hasSize(5);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().bays()).isEqualByComparingTo("2");
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            mockMvc.perform(put(URL + "/emergency-settings").with(as(role)).contentType(JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void settingsValidationAnswers400WithFieldErrors() throws Exception {
        when(service.updateSettings(any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("scale", "Choose 3 colours or 5 levels"), new FieldMessage("bays", "Choose 0 to 10 bays"))));

        mockMvc.perform(put(URL + "/emergency-settings").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scale"))
                .andExpect(jsonPath("$.fieldErrors[1].message").value("Choose 0 to 10 bays"));
    }
}
