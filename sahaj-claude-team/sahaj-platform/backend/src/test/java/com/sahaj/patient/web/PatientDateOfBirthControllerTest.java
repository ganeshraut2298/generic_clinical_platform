package com.sahaj.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.application.DateOfBirthInput;
import com.sahaj.patient.application.MergeOutcome;
import com.sahaj.patient.application.PatientInput;
import com.sahaj.patient.application.PatientService;
import com.sahaj.patient.application.PatientSummaryPage;
import com.sahaj.patient.application.PatientSummaryView;
import com.sahaj.patient.application.PatientUpdateInput;
import com.sahaj.patient.application.PatientView;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * SAH-73 (ADR-024 D3, D5): the date of birth on the patient endpoints: the three states of the property, text that is not a
 * date becoming a field error (never a Jackson message), the date present only for the five allowed roles and absent (not
 * null) for every other, and never in a list or family row.
 */
@WebMvcTest(controllers = PatientController.class)
@Import(SecurityConfig.class)
class PatientDateOfBirthControllerTest {

    private static final String BASE = "/api/v1/patient/patients";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final LocalDate BORN = LocalDate.of(2025, 6, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String CREATE_BASE = "\"name\":\"Asha Rao\",\"sex\":\"F\",\"mobile\":\"9123450001\"";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PatientService patientService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PatientView datedView() {
        return new PatientView(PATIENT_ID, "PT-00001", "Asha Rao", 1, "F", "9123450001", null, "mr", null, null, false,
                Instant.now(), null, BORN, PatientAge.fromDate(BORN, TODAY));
    }

    private static PatientView undatedView() {
        return new PatientView(PATIENT_ID, "PT-00001", "Asha Rao", 34, "F", "9123450001", null, "mr", null, null,
                false, Instant.now(), null, null, PatientAge.undated(34, TODAY));
    }

    private PatientInput createdWith(String body) throws Exception {
        ArgumentCaptor<PatientInput> captor = ArgumentCaptor.forClass(PatientInput.class);
        when(patientService.create(eq(TENANT_ID), eq(USER_ID), captor.capture())).thenReturn(datedView());
        mockMvc.perform(post(BASE).with(as("FRONT_DESK")).contentType(JSON).content(body))
                .andExpect(status().isCreated());
        return captor.getValue();
    }

    private PatientUpdateInput patchedWith(String body) throws Exception {
        ArgumentCaptor<PatientUpdateInput> captor = ArgumentCaptor.forClass(PatientUpdateInput.class);
        when(patientService.update(eq(TENANT_ID), eq(USER_ID), eq(PATIENT_ID), captor.capture()))
                .thenReturn(datedView());
        mockMvc.perform(patch(BASE + "/" + PATIENT_ID).with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk());
        return captor.getValue();
    }

    // ---- the three states and the unreadable one ----

    @Test
    void theCreateBodyReadsADateAMissingDateAndAnExplicitNullAsThreeDifferentThings() throws Exception {
        PatientInput withDate = createdWith("{" + CREATE_BASE + ",\"dateOfBirth\":\"2025-06-10\"}");
        assertThat(withDate.dateOfBirth().state()).isEqualTo(DateOfBirthInput.State.DATE);
        assertThat(withDate.dateOfBirth().date()).isEqualTo(BORN);
        assertThat(withDate.age()).isNull();

        PatientInput without = createdWith("{" + CREATE_BASE + ",\"age\":34}");
        assertThat(without.dateOfBirth().state()).isEqualTo(DateOfBirthInput.State.ABSENT);
        assertThat(without.age()).isEqualTo(34);

        PatientInput nulled = createdWith("{" + CREATE_BASE + ",\"age\":34,\"dateOfBirth\":null}");
        assertThat(nulled.dateOfBirth().state()).isEqualTo(DateOfBirthInput.State.NULL);
    }

    @Test
    void thePatchReadsAMissingDateAsUnchangedADateAsSetAndAnExplicitNullAsClear() throws Exception {
        assertThat(patchedWith("{\"chronic\":\"Asthma\"}").dateOfBirth().state())
                .isEqualTo(DateOfBirthInput.State.ABSENT);
        PatientUpdateInput set = patchedWith("{\"dateOfBirth\":\"2025-06-10\"}");
        assertThat(set.dateOfBirth().state()).isEqualTo(DateOfBirthInput.State.DATE);
        assertThat(set.dateOfBirth().date()).isEqualTo(BORN);
        PatientUpdateInput clear = patchedWith("{\"dateOfBirth\":null,\"age\":7}");
        assertThat(clear.dateOfBirth().state()).isEqualTo(DateOfBirthInput.State.NULL);
        assertThat(clear.age()).isEqualTo(7);
    }

    @Test
    void textThatIsNotADateADateThatDoesNotExistAndAnythingThatIsNotAStringAreInvalidNotAJacksonError()
            throws Exception {
        for (String bad : new String[] {"\"13/11/2026\"", "\"2026-02-31\"", "\"abc\"", "\"2026-2-3\"", "\"\"",
                "\"2026-11-13T10:00:00Z\"", "\"+12345-01-01\"", "20260101", "true", "[2026,1,2]", "{\"y\":2026}"}) {
            assertThat(createdWith("{" + CREATE_BASE + ",\"age\":3,\"dateOfBirth\":" + bad + "}")
                    .dateOfBirth().state()).as(bad).isEqualTo(DateOfBirthInput.State.INVALID);
            assertThat(patchedWith("{\"dateOfBirth\":" + bad + "}").dateOfBirth().state()).as(bad)
                    .isEqualTo(DateOfBirthInput.State.INVALID);
        }
    }

    @Test
    void anInvalidDateBecomesTheFieldErrorWithTheContractMessageAndNeverEchoesTheValue() throws Exception {
        when(patientService.create(any(), any(), any())).thenThrow(
                new InvalidInputException("dateOfBirth", "Enter a valid date of birth."));

        mockMvc.perform(post(BASE).with(as("FRONT_DESK")).contentType(JSON)
                        .content("{" + CREATE_BASE + ",\"age\":3,\"dateOfBirth\":\"2026-02-31\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dateOfBirth"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a valid date of birth."))
                .andExpect(content().string(Matchers.not(Matchers.containsString("2026-02-31"))));
    }

    // ---- who sees the date ----

    @Test
    void theAllowedRolesGetTheDateAndTheAgeDetailOnTheRecord() throws Exception {
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(datedView());

        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK"}) {
            mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dateOfBirth").value("2025-06-10"))
                    .andExpect(jsonPath("$.age").value(1))
                    .andExpect(jsonPath("$.ageDetail.unit").value("MONTHS"))
                    .andExpect(jsonPath("$.ageDetail.value").value(17))
                    .andExpect(jsonPath("$.ageDetail.fromDateOfBirth").value(true));
        }
    }

    @Test
    void anUndatedPatientHasAnExplicitNullDateForAnAllowedRole() throws Exception {
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(undatedView());

        mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateOfBirth").value((Object) null))
                .andExpect(content().string(Matchers.containsString("\"dateOfBirth\":null")))
                .andExpect(jsonPath("$.ageDetail.unit").value("YEARS"))
                .andExpect(jsonPath("$.ageDetail.fromDateOfBirth").value(false));
    }

    @Test
    void theDateIsAbsentNotNullForEveryRoleOutsideTheAllowList() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR", "CUSTOM_RECEPTION", null}) {
            for (PatientView view : List.of(datedView(), undatedView())) {
                JsonNode json = mapper.valueToTree(PatientResponse.from(view, role));
                assertThat(json.has("dateOfBirth")).as("property absent for " + role).isFalse();
                assertThat(json.has("age")).isTrue();
                assertThat(json.has("ageDetail")).isTrue();
                assertThat(json.toString()).doesNotContain("2025-06-10");
            }
        }
        JsonNode allowed = mapper.valueToTree(PatientResponse.from(undatedView(), "OWNER"));
        assertThat(allowed.has("dateOfBirth")).isTrue();
        assertThat(allowed.get("dateOfBirth").isNull()).isTrue();
    }

    @Test
    void theRoleOnTheAnswerComesFromTheSignedInUserForEveryEndpointThatAnswersAPatient() throws Exception {
        when(patientService.create(eq(TENANT_ID), eq(USER_ID), any(PatientInput.class))).thenReturn(datedView());
        when(patientService.update(eq(TENANT_ID), eq(USER_ID), eq(PATIENT_ID), any(PatientUpdateInput.class)))
                .thenReturn(datedView());

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON).content("{" + CREATE_BASE + ",\"age\":1}"))
                .andExpect(jsonPath("$.dateOfBirth").value("2025-06-10"));
        mockMvc.perform(patch(BASE + "/" + PATIENT_ID).with(as("FRONT_DESK")).contentType(JSON).content("{}"))
                .andExpect(jsonPath("$.dateOfBirth").value("2025-06-10"));
    }

    // ---- lists, family, merge ----

    @Test
    void aListAndAFamilyRowNeverCarryTheDateOfBirth() throws Exception {
        PatientSummaryView child = new PatientSummaryView(UUID.randomUUID(), "PT-00002", "Ramesh Rao", 1, "M",
                "9123450001", null, null, PatientAge.fromDate(BORN, TODAY));
        when(patientService.search(eq(TENANT_ID), any(), eq("kids"), eq(0)))
                .thenReturn(new PatientSummaryPage(List.of(child), 1));
        when(patientService.family(TENANT_ID, PATIENT_ID)).thenReturn(List.of(child));

        String list = mockMvc.perform(get(BASE + "?filter=kids").with(as("DOCTOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ageDetail.value").value(17))
                .andExpect(jsonPath("$.items[0].ageDetail.unit").value("MONTHS"))
                .andExpect(jsonPath("$.items[0].age").value(1))
                .andReturn().getResponse().getContentAsString();
        String family = mockMvc.perform(get(BASE + "/" + PATIENT_ID + "/family").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].ageDetail.unit").value("MONTHS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(list).doesNotContain("dateOfBirth").doesNotContain("2025-06-10");
        assertThat(family).doesNotContain("dateOfBirth").doesNotContain("2025-06-10");
        // the age detail is only value, unit and fromDateOfBirth: no "as of" day or day count to recover a date from
        assertThat(list + family).doesNotContain("asOf").doesNotContain("totalDays").doesNotContain("totalMonths");
    }

    @Test
    void theMergeAnswerCarriesTheDatesDifferedNoticeAndTheDateForTheAllowedRole() throws Exception {
        UUID keepId = UUID.randomUUID();
        UUID mergeId = UUID.randomUUID();
        when(patientService.mergeWithNotice(TENANT_ID, USER_ID, keepId, mergeId))
                .thenReturn(new MergeOutcome(datedView(), true));

        mockMvc.perform(post(BASE + "/merge").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + mergeId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datesOfBirthDiffered").value(true))
                .andExpect(jsonPath("$.dateOfBirth").value("2025-06-10"));
    }

    @Test
    void thePlainPatientAnswersNeverSendTheMergeNotice() throws Exception {
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(datedView());

        mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as("NURSE")))
                .andExpect(jsonPath("$.datesOfBirthDiffered").doesNotExist());
    }

    @Test
    void theRequestAndResponseObjectsNeverPrintTheirContents() {
        assertThat(new PatientCreateRequest("Asha", 3, null, "F", "9123450001", null, null, null, null, null, null)
                .toString()).doesNotContain("Asha").doesNotContain("9123450001");
        assertThat(new PatientUpdateRequest("Asha", 3, null, null, null, null, null, null, null).toString())
                .doesNotContain("Asha");
        assertThat(PatientResponse.from(datedView(), "OWNER").toString()).doesNotContain("Asha")
                .doesNotContain("2025").doesNotContain("9123450001");
    }
}
