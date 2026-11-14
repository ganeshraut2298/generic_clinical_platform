package com.sahaj.billing.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.billing.application.CourseAccountService;
import com.sahaj.billing.application.CourseAccountViews.Collected;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import com.sahaj.billing.application.CourseAccountViews.CourseReceipt;
import com.sahaj.billing.application.CourseAccountViews.LedgerLine;
import com.sahaj.billing.application.CourseAccountViews.PaymentResult;
import com.sahaj.billing.application.CourseAccountViews.Suggestions;
import com.sahaj.billing.application.CourseProblemException;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
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

/** SAH-58 course account endpoints (docs/api/billing.yaml 0.2.0): roles, 201 and 200, problem types. */
@WebMvcTest(controllers = CourseAccountController.class)
@Import(SecurityConfig.class)
class CourseAccountControllerTest {

    private static final String BASE = "/api/v1/billing/courses";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String KEY = "pay-key-0001";
    private static final String BODY = "{\"type\":\"PART_PAYMENT\",\"amountPaise\":150000,\"mode\":\"UPI\","
            + "\"useCredit\":true,\"againstSittingId\":null}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CourseAccountService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    private static CourseAccount account() {
        return new CourseAccount(COURSE, "TC-0412", "SITTING", 200_000, 150_000, 50_000, 0, 50_000, 0, 50_000, 3, 1, 1,
                null, new Suggestions(50_000L, null), true,
                List.of(new LedgerLine("PAYMENT", LocalDate.parse("2026-11-10"), "ADVANCE", "UPI", "Course advance",
                        150_000, "RC-1")));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE + "/" + COURSE + "/account")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").header("Idempotency-Key", KEY).contentType(JSON)
                .content(BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + COURSE + "/receipts/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void theAccountIsReadByOwnerDoctorsFrontDeskAndBillingOnly() throws Exception {
        when(service.account(any(ClinicUser.class), eq(COURSE))).thenReturn(account());

        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING"}) {
            mockMvc.perform(get(BASE + "/" + COURSE + "/account").with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.courseNo").value("TC-0412"))
                    .andExpect(jsonPath("$.estimatePaise").value(200000))
                    .andExpect(jsonPath("$.creditPaise").value(50000))
                    .andExpect(jsonPath("$.suggestions.partPaymentPaise").value(50000))
                    .andExpect(jsonPath("$.ledger[0].receiptNo").value("RC-1"));
        }
        for (String role : new String[] {"NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(BASE + "/" + COURSE + "/account").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void onlyOwnerAndFrontDeskCollectAndTheBillingRoleCannot() throws Exception {
        for (String role : new String[] {"BILLING", "DOCTOR", "VISITING_DOCTOR", "NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as(role)).header("Idempotency-Key", KEY)
                    .contentType(JSON).content(BODY)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void aNewPaymentIs201AndTheSameKeyAgainIs200() throws Exception {
        PaymentResult result = new PaymentResult("RC-5", account());
        when(service.collect(any(ClinicUser.class), eq(COURSE), eq(KEY), any(CoursePaymentInput.class)))
                .thenReturn(new Collected(true, result), new Collected(false, result));

        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("FRONT_DESK")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo").value("RC-5"))
                .andExpect(jsonPath("$.account.balancePaise").value(50000));
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY)).andExpect(status().isOk());
    }

    @Test
    void theClinicInTheBodyIsIgnoredAndTheUserDecides() throws Exception {
        PaymentResult result = new PaymentResult("RC-5", account());
        when(service.collect(any(ClinicUser.class), eq(COURSE), eq(KEY), any(CoursePaymentInput.class)))
                .thenReturn(new Collected(true, result));

        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY.replace("{", "{\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"clinicId\":\"" + UUID.randomUUID() + "\",")))
                .andExpect(status().isCreated());
    }

    @Test
    void anUnknownFieldInThePaymentBodyIs400ValidationAndNothingIsRecorded() throws Exception {
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY.replace("{", "{\"patientName\":\"Asha Rao\",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("body"))
                .andExpect(content().string(not(containsString("patientName"))))
                .andExpect(content().string(not(containsString("Asha"))));
        verifyNoInteractions(service);
    }

    @Test
    void anUnreadableValueInThePaymentBodyIsStillAFieldProblemAndABrokenBodyIsMalformed() throws Exception {
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY.replace("150000", "\"lots\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amountPaise"));
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"));
        verifyNoInteractions(service);
    }

    @Test
    void theProblemTypesOfTheContractAreSpelledOut() throws Exception {
        when(service.account(any(ClinicUser.class), eq(COURSE))).thenThrow(CourseProblemException.courseNotFound());
        when(service.receipt(any(ClinicUser.class), eq(COURSE), eq(9L))).thenThrow(CourseProblemException.receiptNotFound());
        when(service.collect(any(ClinicUser.class), eq(COURSE), eq(KEY), any(CoursePaymentInput.class)))
                .thenThrow(CourseProblemException.amountOverBalance())
                .thenThrow(new ConflictException("idempotency-conflict", "Request already used", "Used."));

        mockMvc.perform(get(BASE + "/" + COURSE + "/account").with(as("OWNER"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/course-not-found"));
        mockMvc.perform(get(BASE + "/" + COURSE + "/receipts/9").with(as("BILLING"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/receipt-not-found"));
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/amount-over-balance"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amountPaise"));
        mockMvc.perform(post(BASE + "/" + COURSE + "/payments").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/idempotency-conflict"));
    }

    @Test
    void aReceiptIsReadByOwnerFrontDeskAndBillingOnly() throws Exception {
        when(service.receipt(any(ClinicUser.class), eq(COURSE), eq(5L))).thenReturn(new CourseReceipt("RC-5",
                "Sahaj clinic", "1 Main Road", "020 1234", Instant.parse("2026-11-12T06:00:00Z"), "Asha Rao", "UH-1",
                "TC-0412", "Dental treatment course", "PART_PAYMENT", "UPI", "Course balance", 150_000, "Meena",
                50_000));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "BILLING"}) {
            mockMvc.perform(get(BASE + "/" + COURSE + "/receipts/5").with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.receiptNo").value("RC-5"))
                    .andExpect(jsonPath("$.patientName").value("Asha Rao"))
                    .andExpect(jsonPath("$.balanceAfterPaise").value(50000));
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(BASE + "/" + COURSE + "/receipts/5").with(as(role))).andExpect(status().isForbidden());
        }
    }
}
