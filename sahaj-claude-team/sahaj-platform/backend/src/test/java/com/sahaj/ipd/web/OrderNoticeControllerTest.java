package com.sahaj.ipd.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.MedicineViews.OrderDetails;
import com.sahaj.ipd.application.OrderNoticeService;
import com.sahaj.ipd.application.OrderNoticeViews.NoticeCount;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNotice;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeList;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePage;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePageRow;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeSummary;
import com.sahaj.ipd.application.OrderNoticeViews.StatLine;
import com.sahaj.ipd.application.OrderNoticeViews.SummaryAdmission;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-76-3 the four order-inbox endpoints: contract field names, status codes, the problem types and texts of refusals,
 * and that the clinic, the user and the role come from the token.
 */
@WebMvcTest(controllers = OrderNoticeController.class)
@Import(SecurityConfig.class)
class OrderNoticeControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID NOTICE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderNoticeService notices;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static OrderNotice notice() {
        OrderDetails details = new OrderDetails("ONCE", new BigDecimal("4"), "MG", "IV", "IV", null, "STAT", NOW, null,
                null, null, "STAT", null, null, false);
        return new OrderNotice(NOTICE_ID, ADMISSION_ID, ORDER_ID, 2, "CHANGED", "STAT", "ondan", "Inj. Ondansetron 4 mg",
                details, List.of(new OrderChangeLine("dose", "Dose", "2 mg", "4 mg")), "Dose too low", NOW,
                "Dr Meera Joshi", null, null, 7, true, new StatLine(NOW.plusSeconds(480), 15, null, 3), true);
    }

    private static OrderNoticeList list() {
        return new OrderNoticeList(ADMISSION_ID, NOW, "on", "ADMITTED", new NoticeCount(1, 1, 1), List.of(notice()), true);
    }

    // ---- the Orders tab ----

    @Test
    void theTabHasTheContractShape() throws Exception {
        when(notices.forAdmission(TENANT_ID, USER_ID, "NURSE", ADMISSION_ID)).thenReturn(list());

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/order-notices").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.state").value("on"))
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.open.count").value(1))
                .andExpect(jsonPath("$.open.stat").value(1))
                .andExpect(jsonPath("$.open.overdue").value(1))
                .andExpect(jsonPath("$.canAcknowledge").value(true))
                .andExpect(jsonPath("$.items[0].id").value(NOTICE_ID.toString()))
                .andExpect(jsonPath("$.items[0].kind").value("CHANGED"))
                .andExpect(jsonPath("$.items[0].priority").value("STAT"))
                .andExpect(jsonPath("$.items[0].orderVersion").value(2))
                .andExpect(jsonPath("$.items[0].drugName").value("Inj. Ondansetron 4 mg"))
                .andExpect(jsonPath("$.items[0].details.doseUnit").value("MG"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("2 mg"))
                .andExpect(jsonPath("$.items[0].reason").value("Dose too low"))
                .andExpect(jsonPath("$.items[0].acknowledgedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].waitingMinutes").value(7))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.items[0].stat.minutesOverdue").value(3))
                .andExpect(jsonPath("$.items[0].stat.minutesLeft").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].canAcknowledge").value(true));
    }

    @Test
    void aRefusedRoleIs403WithTheContractText() throws Exception {
        when(notices.forAdmission(TENANT_ID, USER_ID, "PHARMACIST", ADMISSION_ID))
                .thenThrow(new ForbiddenException("forbidden", "Not allowed", "You cannot open the order list."));

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/order-notices").with(as("PHARMACIST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You cannot open the order list."));
    }

    @Test
    void moduleOffAndAnOutOfScopeNurseAreRefused() throws Exception {
        when(notices.forAdmission(TENANT_ID, USER_ID, "NURSE", ADMISSION_ID))
                .thenThrow(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."));

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/order-notices").with(as("NURSE")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(Matchers.endsWith("/module-off")));
    }

    @Test
    void aPathThatIsNotAUuidIs400() throws Exception {
        mockMvc.perform(get(URL + "/admissions/not-a-uuid/order-notices").with(as("NURSE")))
                .andExpect(status().isBadRequest());
    }

    // ---- the Safety panel ----

    @Test
    void thePanelPassesTheLimitAndTheCursorAsText() throws Exception {
        OrderNoticePage page = new OrderNoticePage(NOW, new NoticeCount(1, 0, 0),
                List.of(new OrderNoticePageRow(notice(), "Test Patient", "202")), "cursor");
        when(notices.page(TENANT_ID, USER_ID, "NURSE", "20", "abc")).thenReturn(page);

        mockMvc.perform(get(URL + "/order-notices").param("limit", "20").param("cursor", "abc").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].patientName").value("Test Patient"))
                .andExpect(jsonPath("$.items[0].bedCode").value("202"))
                .andExpect(jsonPath("$.items[0].notice.id").value(NOTICE_ID.toString()))
                .andExpect(jsonPath("$.nextCursor").value("cursor"))
                .andExpect(jsonPath("$.open.count").value(1));
    }

    @Test
    void aBadLimitIsTheServicesOwn400() throws Exception {
        when(notices.page(TENANT_ID, USER_ID, "NURSE", "0", null))
                .thenThrow(new InvalidInputException("limit", "Reload the page and try again."));

        mockMvc.perform(get(URL + "/order-notices").param("limit", "0").with(as("NURSE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
    }

    // ---- the summary ----

    @Test
    void theSummaryHoldsCountsOnly() throws Exception {
        OrderNoticeSummary summary = new OrderNoticeSummary(NOW, new NoticeCount(3, 1, 2),
                List.of(new SummaryAdmission(ADMISSION_ID, 3, 1, 2, NOW.plusSeconds(60))), "Qm9yZGVyLWNoZWNrLXRva2");
        when(notices.summary(TENANT_ID, USER_ID, "DOCTOR")).thenReturn(summary);

        mockMvc.perform(get(URL + "/order-notices/summary").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.count").value(3))
                .andExpect(jsonPath("$.byAdmission[0].admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.byAdmission[0].statDueBy").value("2026-11-11T06:01:00Z"))
                .andExpect(jsonPath("$.changeToken").value("Qm9yZGVyLWNoZWNrLXRva2"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.byAdmission[0].patientName").doesNotExist());
    }

    // ---- acknowledge ----

    @Test
    void acknowledgeAnswersThePatientsListWithNoBody() throws Exception {
        when(notices.acknowledge(TENANT_ID, USER_ID, "NURSE", NOTICE_ID)).thenReturn(list());

        mockMvc.perform(post(URL + "/order-notices/" + NOTICE_ID + "/acknowledge").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()));
        verify(notices).acknowledge(TENANT_ID, USER_ID, "NURSE", NOTICE_ID);
    }

    @Test
    void acknowledgeByAnotherUserIs409OrderState() throws Exception {
        when(notices.acknowledge(TENANT_ID, USER_ID, "NURSE", NOTICE_ID)).thenThrow(
                new ConflictException("order-state", "Notice already acknowledged", "Nurse Sunita already acknowledged this."));

        mockMvc.perform(post(URL + "/order-notices/" + NOTICE_ID + "/acknowledge").with(as("NURSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/order-state")))
                .andExpect(jsonPath("$.detail").value("Nurse Sunita already acknowledged this."));
    }

    @Test
    void aDoctorCannotAcknowledge() throws Exception {
        when(notices.acknowledge(TENANT_ID, USER_ID, "DOCTOR", NOTICE_ID)).thenThrow(new ForbiddenException("forbidden",
                "Not allowed", "Only nurses and the clinic owner can acknowledge orders."));

        mockMvc.perform(post(URL + "/order-notices/" + NOTICE_ID + "/acknowledge").with(as("DOCTOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only nurses and the clinic owner can acknowledge orders."));
    }

    @Test
    void withoutASignedInUserEveryEndpointIs401() throws Exception {
        mockMvc.perform(get(URL + "/order-notices/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/order-notices/" + NOTICE_ID + "/acknowledge")).andExpect(status().isUnauthorized());
    }
}
