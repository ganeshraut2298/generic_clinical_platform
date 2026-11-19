package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.ActiveReasonService;
import com.sahaj.pharmacy.application.ApprovalService;
import com.sahaj.pharmacy.application.OpeningStockPostService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.pharmacy.application.StockAdjustmentService;
import com.sahaj.pharmacy.application.StockReadService;
import com.sahaj.pharmacy.application.StockViews;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-79 WP-D over HTTP (docs/api/pharmacy.yaml 0.3.0): the paths, the raw text of every path id and query value passed on,
 * the closed bodies, 201 and 200 of an adjustment create, the order precheck then PIN then transaction for a second signer, and a
 * database deadlock answering 503 database-busy.
 */
@WebMvcTest(controllers = {StockController.class, ApprovalController.class, StockAdjustmentController.class})
@Import(SecurityConfig.class)
class PharmacyStockApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String STORE = "00000000-0000-4000-8000-0000000000a1";
    private static final String IMPORT = "00000000-0000-4000-8000-0000000000b1";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000c1";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String KEY = "00000000-0000-4000-8000-00000000d001";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private OpeningStockPostService posting;
    @MockBean
    private StockReadService reads;
    @MockBean
    private ActiveReasonService reasons;
    @MockBean
    private ApprovalService approvals;
    @MockBean
    private StockAdjustmentService adjustments;
    @MockBean
    private ScheduleXSignature signature;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void everyNewOperationNeedsASignedInUser() throws Exception {
        for (String path : new String[] {"/stores/" + STORE + "/stock/overview", "/stores/" + STORE + "/stock/batches",
                "/stores/" + STORE + "/stock/low-stock", "/stores/" + STORE + "/stock/fefo-pick",
                "/stores/" + STORE + "/stock/products/" + PRODUCT, "/stores/" + STORE + "/stock/products/" + PRODUCT + "/states",
                "/stock/products/" + PRODUCT + "/stores", "/stores/" + STORE + "/opening-stock-imports/" + IMPORT + "/posting-preview",
                "/stores/" + STORE + "/stock-adjustments", "/approvals/inbox", "/approvals/" + KEY, "/reasons?lists=damage"}) {
            mockMvc.perform(get(URL + path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(URL + "/stores/" + STORE + "/opening-stock-imports/" + IMPORT + "/post").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/approvals/" + KEY + "/decision").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePostingPreviewAndTheStockReadsPassTheRawTextOfIdsAndQueryValuesToTheirService() throws Exception {
        when(reads.batches(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new StockViews.BatchPage(List.of(), 0, 0, 25));
        when(reads.fefoPick(any(), any(), any(), any(), any())).thenReturn(new StockViews.FefoPick(UUID.fromString(PRODUCT), 5, 3, 2,
                List.of()));

        mockMvc.perform(get(URL + "/stores/" + STORE + "/stock/batches?view=NEAR_EXPIRY&withinDays=60&q=para&page=1&size=10")
                        .with(as("PHARMACIST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.size").value(25));
        verify(reads).batches(eq(TENANT_ID), eq(USER_ID), eq(STORE), eq("NEAR_EXPIRY"), eq("60"), eq("para"), eq("1"), eq("10"));

        mockMvc.perform(get(URL + "/stores/" + STORE + "/stock/fefo-pick?productId=" + PRODUCT + "&quantity=5").with(as("NURSE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shortBy").value(2)).andExpect(jsonPath("$.availableQty").value(3));
        verify(reads).fefoPick(TENANT_ID, USER_ID, STORE, PRODUCT, "5");

        mockMvc.perform(get(URL + "/stores/not-an-id/stock/overview").with(as("NURSE")));
        verify(reads).overview(TENANT_ID, USER_ID, "not-an-id");
    }

    @Test
    void anAllStoresReadIsNotUnderAStorePath() throws Exception {
        when(reads.productAllStores(any(), any(), any())).thenReturn(new StockViews.ProductStockByStore(null, List.of(), null, true));

        mockMvc.perform(get(URL + "/stock/products/" + PRODUCT + "/stores").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.empty").value(true));
        verify(reads).productAllStores(TENANT_ID, USER_ID, PRODUCT);
    }

    @Test
    void thePostReadsItsBodyThroughTheServiceAndAUnknownFieldIsRefusedWithoutEchoingIt() throws Exception {
        when(posting.post(any(), any(), any(), any(), any())).thenAnswer(BodyAnswers.readBody(4,
                (PharmacyViews.OpeningStockImport) null));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/opening-stock-imports/" + IMPORT + "/post").with(as("OWNER"))
                        .contentType(JSON).content("{\"clientKey\":\"" + KEY + "\"}")).andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(posting).post(eq(TENANT_ID), eq(USER_ID), eq(STORE), eq(IMPORT), sent.capture());
        PharmacyInputs.ClientKeyInput body = BodyAnswers.read(sent);
        assertThat(body.clientKey()).isEqualTo(UUID.fromString(KEY));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/opening-stock-imports/" + IMPORT + "/post").with(as("OWNER"))
                        .contentType(JSON).content("{\"clientKey\":\"" + KEY + "\",\"postedBy\":\"ZZZ\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ZZZ"))));
    }

    @Test
    void aDeadlockInsideAServiceAnswers503DatabaseBusyWithARetryHintAndNeverA500() throws Exception {
        when(reads.overview(any(), any(), any())).thenThrow(new DeadlockLoserDataAccessException("Key (name)=(Asha) 9000000001",
                new java.sql.SQLException("deadlock detected", "40P01")));

        mockMvc.perform(get(URL + "/stores/" + STORE + "/stock/overview").with(as("OWNER"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/database-busy"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(5))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Asha"))));
    }

    @Test
    void theReasonsQueryIsPassedAsItWasSent() throws Exception {
        when(reasons.list(any(), any(), any())).thenReturn(new StockViews.ActiveReasons(List.of()));

        mockMvc.perform(get(URL + "/reasons?lists=damage,stock-adjustment").with(as("NURSE"))).andExpect(status().isOk());

        verify(reasons).list(TENANT_ID, USER_ID, "damage,stock-adjustment");
    }

    @Test
    void anAdjustmentWithoutASecondSignerIsCreatedWithoutAnyPinCheck() throws Exception {
        when(adjustments.precheck(any(), any(), any(), any())).thenReturn(null);
        when(adjustments.create(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyViews.Outcome<StockViews.StockAdjustment>(null, true));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/stock-adjustments").with(as("PHARMACIST")).contentType(JSON)
                        .content("{\"clientKey\":\"" + KEY + "\",\"batchId\":\"" + PRODUCT + "\",\"bookQtySeen\":40,"
                                + "\"physicalQty\":38,\"reasonKey\":\"stock-adjustment.count-mismatch\"}"))
                .andExpect(status().isCreated());

        verify(signature, never()).verify(any(), any(), any(), any());
        verify(adjustments).create(eq(TENANT_ID), eq(USER_ID), eq(STORE), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void aReplayOfTheSameKeyAnswers200() throws Exception {
        when(adjustments.create(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyViews.Outcome<StockViews.StockAdjustment>(null, false));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/stock-adjustments").with(as("PHARMACIST")).contentType(JSON)
                .content("{\"clientKey\":\"" + KEY + "\"}")).andExpect(status().isOk());
    }

    @Test
    void theSecondSignersPinIsCheckedAfterThePrecheckAndBeforeTheTransactionAndTheVerifiedSignerIsPassedOn() throws Exception {
        UUID signer = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
        when(adjustments.precheck(any(), any(), any(), any())).thenReturn(new PharmacyInputs.SecondSignerInput(signer, "4321"));
        when(signature.verify(TENANT_ID, USER_ID, signer, "4321")).thenReturn(signer);
        when(adjustments.create(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyViews.Outcome<StockViews.StockAdjustment>(null, true));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/stock-adjustments").with(as("PHARMACIST")).contentType(JSON)
                .content("{\"clientKey\":\"" + KEY + "\",\"secondSigner\":{\"membershipId\":\"" + signer + "\",\"pin\":\"4321\"}}"))
                .andExpect(status().isCreated());

        InOrder order = inOrder(adjustments, signature);
        order.verify(adjustments).precheck(eq(TENANT_ID), eq(USER_ID), eq(STORE), any());
        order.verify(signature).verify(TENANT_ID, USER_ID, signer, "4321");
        order.verify(adjustments).create(eq(TENANT_ID), eq(USER_ID), eq(STORE), any(), eq(signer));
    }

    @Test
    void aWrongSecondSignerStopsBeforeTheTransactionStarts() throws Exception {
        UUID signer = UUID.randomUUID();
        when(adjustments.precheck(any(), any(), any(), any())).thenReturn(new PharmacyInputs.SecondSignerInput(signer, "0000"));
        when(signature.verify(any(), any(), any(), any())).thenThrow(new ForbiddenException("second-signer-invalid", "Second signer not valid",
                "The second signer must be another active pharmacist or owner."));

        mockMvc.perform(post(URL + "/stores/" + STORE + "/stock-adjustments").with(as("PHARMACIST")).contentType(JSON)
                .content("{\"clientKey\":\"" + KEY + "\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/second-signer-invalid"));

        verify(adjustments, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void theDecisionPassesItsBodyAndAProblemFromTheServiceKeepsItsType() throws Exception {
        when(approvals.decide(any(), any(), any(), any())).thenThrow(PharmacyProblemException.alreadyDecided("current"));

        mockMvc.perform(post(URL + "/approvals/" + KEY + "/decision").with(as("NURSE")).contentType(JSON).content("{\"clientKey\":\"" + KEY
                + "\",\"level\":1,\"requestVersion\":0,\"decision\":\"APPROVED\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/already-decided"))
                .andExpect(jsonPath("$.current").value("current"));
        verify(approvals).decide(eq(TENANT_ID), eq(USER_ID), eq(KEY), any());
    }

    @Test
    void theInboxAndOneRequestPassTheirRawValues() throws Exception {
        when(approvals.inbox(any(), any(), any(), any(), any())).thenReturn(new StockViews.InboxPage(List.of(), 0, 0, 20,
                new StockViews.InboxCounts(0, 0, 0, 0), "Owner"));

        mockMvc.perform(get(URL + "/approvals/inbox?type=STOCK&page=0&size=20").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.signedInAs").value("Owner"));
        verify(approvals).inbox(TENANT_ID, USER_ID, "STOCK", "0", "20");

        mockMvc.perform(get(URL + "/approvals/" + KEY).with(as("OWNER")));
        verify(approvals).get(TENANT_ID, USER_ID, KEY);
    }
}
