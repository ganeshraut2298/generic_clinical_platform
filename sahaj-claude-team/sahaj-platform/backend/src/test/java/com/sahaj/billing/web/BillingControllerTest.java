package com.sahaj.billing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.billing.application.BillView;
import com.sahaj.billing.application.BillingService;
import com.sahaj.billing.application.CounterView;
import com.sahaj.billing.application.DayClosingView;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-10 billing endpoints: contract shape, role gate, tenant and role from the signed-in user, problem responses. */
@WebMvcTest(controllers = BillingController.class)
@Import(SecurityConfig.class)
class BillingControllerTest {

    private static final String BILLING = "/api/v1/billing";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID VISIT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BillingService billingService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static BillView bill(boolean canCollect) {
        return new BillView(VISIT_ID, 7, "Asha Rao", 30, "F", "Dr Rao", "NEW", List.of(), 50000, 0, null, 0, 50000,
                0, 50000, List.of(), canCollect, 10);
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BILLING + "/counter")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsTheCounter() throws Exception {
        when(billingService.counter(TENANT_ID)).thenReturn(new CounterView(List.of(), List.of(), List.of()));

        mockMvc.perform(get(BILLING + "/counter").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").isArray())
                .andExpect(jsonPath("$.dues").isArray())
                .andExpect(jsonPath("$.paidToday").isArray());
    }

    @Test
    void anyClinicRoleReadsABillButCannotCollect() throws Exception {
        when(billingService.get(TENANT_ID, "NURSE", VISIT_ID)).thenReturn(bill(false));

        mockMvc.perform(get(BILLING + "/bills/" + VISIT_ID).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.canCollect").value(false));
    }

    @Test
    void anUnknownVisitIs404() throws Exception {
        when(billingService.get(any(), any(), any())).thenThrow(new NotFoundException("Visit not found"));

        mockMvc.perform(get(BILLING + "/bills/" + VISIT_ID).with(as("OWNER"))).andExpect(status().isNotFound());
    }

    @Test
    void ownerOrFrontDeskMayAddAnItem() throws Exception {
        when(billingService.addItem(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), eq(VISIT_ID), any()))
                .thenReturn(bill(true));

        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/items").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"serviceId\":\"" + UUID.randomUUID() + "\",\"qty\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCollect").value(true));
    }

    @Test
    void aNurseCannotAddAnItemAndGets403() throws Exception {
        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/items").with(as("NURSE")).contentType(JSON)
                        .content("{\"serviceId\":\"" + UUID.randomUUID() + "\",\"qty\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBadServiceIdOnAddIs400() throws Exception {
        when(billingService.addItem(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("serviceId", "Choose a service from the list"));

        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/items").with(as("OWNER")).contentType(JSON)
                        .content("{\"serviceId\":\"" + UUID.randomUUID() + "\",\"qty\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerOrFrontDeskMayRemoveAnItem() throws Exception {
        when(billingService.removeItem(TENANT_ID, USER_ID, "OWNER", VISIT_ID, ITEM_ID)).thenReturn(bill(true));

        mockMvc.perform(delete(BILLING + "/bills/" + VISIT_ID + "/items/" + ITEM_ID).with(as("OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void settingADiscountOverTheLimitIs400() throws Exception {
        when(billingService.setDiscount(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("percent", "This discount is above your role's limit"));

        mockMvc.perform(put(BILLING + "/bills/" + VISIT_ID + "/discount").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"percent\":50,\"reason\":\"goodwill\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordingAPaymentReturnsTheUpdatedBill() throws Exception {
        when(billingService.recordPayment(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(VISIT_ID), any()))
                .thenReturn(bill(true));

        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/payments").with(as("OWNER")).contentType(JSON)
                        .content("{\"mode\":\"CASH\",\"amountPaise\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(50000));
    }

    @Test
    void onlyTheOwnerMayRefundAndANonOwnerGets403() throws Exception {
        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/refund").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"amountPaise\":1000,\"mode\":\"CASH\",\"reason\":\"overcharged\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOwnerMayRefund() throws Exception {
        when(billingService.refund(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(VISIT_ID), any()))
                .thenReturn(bill(true));

        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/refund").with(as("OWNER")).contentType(JSON)
                        .content("{\"amountPaise\":1000,\"mode\":\"CASH\",\"reason\":\"overcharged\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void closingTheDayReturns201() throws Exception {
        when(billingService.closeDay(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any()))
                .thenReturn(new DayClosingView(LocalDate.of(2026, 11, 1), 10000, 10000, 0, null, "Priya",
                        Instant.now()));

        mockMvc.perform(post(BILLING + "/day-closing").with(as("OWNER")).contentType(JSON)
                        .content("{\"countedCashPaise\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closedByName").value("Priya"));
    }

    @Test
    void closingAnAlreadyClosedDayIs409() throws Exception {
        when(billingService.closeDay(any(), any(), any(), any()))
                .thenThrow(new com.sahaj.platform.api.ConflictException("day-already-closed",
                        "The day is already closed", "Today's cash has already been closed."));

        mockMvc.perform(post(BILLING + "/day-closing").with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"countedCashPaise\":10000}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aForbiddenRefundFromTheServiceIs403() throws Exception {
        when(billingService.refund(any(), any(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("Only the owner can issue a refund."));

        mockMvc.perform(post(BILLING + "/bills/" + VISIT_ID + "/refund").with(as("OWNER")).contentType(JSON)
                        .content("{\"amountPaise\":1000,\"mode\":\"CASH\",\"reason\":\"overcharged\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void todaySummaryNeedsASignedInUser() throws Exception {
        mockMvc.perform(get(BILLING + "/today-summary")).andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE",
            "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"})
    void everyClinicRoleReadsTheTodaySummaryInTheContractShape(String role) throws Exception {
        when(billingService.todaySummary(TENANT_ID))
                .thenReturn(new com.sahaj.billing.application.BillingTodaySummary(125000, 3));

        mockMvc.perform(get(BILLING + "/today-summary").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedPaise").value(125000))
                .andExpect(jsonPath("$.billsPending").value(3));
    }
}
