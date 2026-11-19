package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.OpeningStockService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.StoreAccessDeniedException;
import com.sahaj.pharmacy.application.StoreDenialAuditor;
import com.sahaj.pharmacy.domain.OpeningStockCsv;
import com.sahaj.pharmacy.domain.RejectReason;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
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

/**
 * SAH-78-7 over HTTP (docs/api/pharmacy.yaml 0.1.1): the opening-stock import. Status codes, the CSV template, closed
 * bodies, path ids read as text, and the 403, 409 and 422 problems with their extra members.
 */
@WebMvcTest(controllers = OpeningStockController.class)
@Import(SecurityConfig.class)
class PharmacyOpeningStockApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID IMPORT_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "https://sahaj.in/problems/";
    private static final String IMPORTS = URL + "/stores/" + STORE_ID + "/opening-stock-imports";
    private static final String START = "{\"clientKey\":\"00000000-0000-4000-8000-00000000c001\",\"columns\":[\"store code\","
            + "\"product code\",\"batch\",\"expiry date\",\"MRP\",\"purchase rate\",\"quantity\"],\"rowCount\":412}";
    private static final String ROWS = "{\"rows\":[{\"rowNo\":1,\"storeCode\":\"CENTRAL\",\"productCode\":\"G-000101\","
            + "\"batch\":\"TESTB001\",\"expiryDate\":\"31-12-2027\",\"mrp\":\"45.50\",\"purchaseRate\":\"30.25\","
            + "\"quantity\":\"100\"}]}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private OpeningStockService service;
    @MockBean
    private StoreDenialAuditor denialAuditor;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PharmacyViews.OpeningStockImport waiting() {
        return new PharmacyViews.OpeningStockImport(IMPORT_ID, STORE_ID, "Test Central Store", "WAITING_APPROVAL", 412, 412,
                412, 18450, 125000000L, 0, 2, new PharmacyViews.Person(MEMBER_ID, "Test Pharmacist One"),
                Instant.parse("2026-11-17T05:30:00Z"), Instant.parse("2026-11-17T05:41:00Z"), null, null, null, null, null,
                true, null, false, 3L);
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(URL + "/opening-stock/template")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/opening-stock-imports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(IMPORTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(IMPORTS).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(IMPORTS + "/" + IMPORT_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(IMPORTS + "/" + IMPORT_ID + "/lines")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/lines").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/submit")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/approve")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/reject").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ---- template and lists ----

    @Test
    void theTemplateIsAnAttachedUtf8CsvWhateverTheAcceptHeaderSays() throws Exception {
        when(service.template(TENANT_ID, USER_ID)).thenReturn(OpeningStockCsv.TEMPLATE);
        for (String accept : new String[] {"*/*", "text/csv", "application/json"}) {
            mockMvc.perform(get(URL + "/opening-stock/template").with(as("PHARMACIST")).accept(accept))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", Matchers.startsWith("text/csv")))
                    .andExpect(header().string("Content-Type", Matchers.containsStringIgnoringCase("charset=UTF-8")))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"sahaj-opening-stock-template.csv\""))
                    .andExpect(content().string(OpeningStockCsv.TEMPLATE));
        }
    }

    @Test
    void aRefusedTemplateRequestStaysAProblemEvenWhenTheClientAsksForCsv() throws Exception {
        when(service.template(any(), any())).thenThrow(new ModuleOffException("Pharmacy & Inventory is not switched on for your clinic."));
        mockMvc.perform(get(URL + "/opening-stock/template").with(as("OWNER")).accept("text/csv"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "module-off"));
    }

    @Test
    void theListsPassTheRawQueryValuesToTheService() throws Exception {
        PharmacyViews.ImportPage page = new PharmacyViews.ImportPage(List.of(waiting()), 1, 0, 20);
        when(service.listAll(TENANT_ID, USER_ID, "WAITING_APPROVAL", "0", "20")).thenReturn(page);
        mockMvc.perform(get(URL + "/opening-stock-imports?status=WAITING_APPROVAL&page=0&size=20").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(IMPORT_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("WAITING_APPROVAL"));

        when(service.listForStore(TENANT_ID, USER_ID, STORE_ID.toString(), "2", "5")).thenReturn(page);
        mockMvc.perform(get(IMPORTS + "?page=2&size=5").with(as("PHARMACIST"))).andExpect(status().isOk());
    }

    @Test
    void oneImportHasTheReviewTotalsAndTheFixedShape() throws Exception {
        when(service.get(TENANT_ID, USER_ID, STORE_ID.toString(), IMPORT_ID.toString())).thenReturn(waiting());
        mockMvc.perform(get(IMPORTS + "/" + IMPORT_ID).with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Test Central Store")).andExpect(jsonPath("$.rowCount").value(412))
                .andExpect(jsonPath("$.receivedRows").value(412)).andExpect(jsonPath("$.lineCount").value(412))
                .andExpect(jsonPath("$.totalQuantity").value(18450)).andExpect(jsonPath("$.totalValuePaise").value(125000000))
                .andExpect(jsonPath("$.errorCount").value(0)).andExpect(jsonPath("$.warningCount").value(2))
                .andExpect(jsonPath("$.uploadedBy.membershipId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.uploadedBy.name").value("Test Pharmacist One"))
                .andExpect(jsonPath("$.decidedBy").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rejectReasonKey").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.replacedByImportId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.canApprove").value(true)).andExpect(jsonPath("$.approveLockReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.posted").value(false)).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void theLinesCarryTheirIssuesAndThePagingIsPassedAsText() throws Exception {
        PharmacyViews.OpeningStockLinePage page = new PharmacyViews.OpeningStockLinePage(List.of(
                new PharmacyViews.OpeningStockLine(17, "CENTRAL", "G-000101", null, null, "TESTB001", null, "45.50", "30.25", 100,
                        302500L, List.of(new PharmacyViews.RowIssue(17, "expiryDate", "expiry-past", "ERROR",
                                "Expiry date must be in the future")))), 1, 0, 50);
        when(service.lines(TENANT_ID, USER_ID, STORE_ID.toString(), IMPORT_ID.toString(), "true", null, "50")).thenReturn(page);
        mockMvc.perform(get(IMPORTS + "/" + IMPORT_ID + "/lines?withIssues=true&size=50").with(as("PHARMACIST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].rowNo").value(17))
                .andExpect(jsonPath("$.items[0].productId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].expiryDate").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].mrp").value("45.50")).andExpect(jsonPath("$.items[0].valuePaise").value(302500))
                .andExpect(jsonPath("$.items[0].issues[0].code").value("expiry-past"))
                .andExpect(jsonPath("$.items[0].issues[0].message").value("Expiry date must be in the future"));
    }

    // ---- start ----

    @Test
    void startAnswers201AndReadsTheHeaderAndTheRowCount() throws Exception {
        when(service.start(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), any()))
                .thenAnswer(BodyAnswers.readBody(3, new PharmacyViews.Outcome<>(waiting(), true)));
        mockMvc.perform(post(IMPORTS).with(as("PHARMACIST")).contentType(JSON).content(START))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(IMPORT_ID.toString()));

        var sent = BodyAnswers.captor();
        verify(service).start(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), sent.capture());
        PharmacyInputs.StartImport body = BodyAnswers.read(sent);
        assertThat(body.clientKey()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-00000000c001"));
        assertThat(body.columns()).isEqualTo(OpeningStockCsv.HEADER);
        assertThat(body.rowCount()).isEqualByComparingTo("412");
    }

    @Test
    void startAnswers200ForTheReplayOfAClientKeyAndRefusesUnknownFields() throws Exception {
        when(service.start(any(), any(), anyString(), any()))
                .thenAnswer(BodyAnswers.readBody(3, new PharmacyViews.Outcome<>(waiting(), false)));
        mockMvc.perform(post(IMPORTS).with(as("OWNER")).contentType(JSON).content(START)).andExpect(status().isOk());
        mockMvc.perform(post(IMPORTS).with(as("OWNER")).contentType(JSON)
                        .content(START.replace("\"rowCount\"", "\"fileName\":\"stock.csv\",\"rowCount\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("stock.csv"))));
    }

    @Test
    void theConflictsOfStartAndSubmitAreTheContractsTypes() throws Exception {
        for (String[] c : new String[][] {
                {"opening-stock-already-approved", "Opening stock was already approved for this store."},
                {"import-waiting", "An import for this store is waiting for approval by Test Pharmacist One."},
                {"client-key-reused", "This request was already sent by someone else. Reload the page and try again."}}) {
            org.mockito.Mockito.doThrow(new ConflictException(c[0], "Conflict", c[1])).when(service)
                    .start(any(), any(), anyString(), any());
            mockMvc.perform(post(IMPORTS).with(as("PHARMACIST")).contentType(JSON).content(START))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + c[0]))
                    .andExpect(jsonPath("$.detail").value(c[1]));
        }
        for (String[] c : new String[][] {{"import-state", "This import can no longer be changed."},
                {"import-incomplete", "Some rows did not arrive. Upload the file again."}}) {
            org.mockito.Mockito.doThrow(new ConflictException(c[0], "Conflict", c[1])).when(service)
                    .submit(any(), any(), anyString(), anyString());
            mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/submit").with(as("PHARMACIST")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + c[0]))
                    .andExpect(jsonPath("$.detail").value(c[1]));
        }
    }

    // ---- rows ----

    @Test
    void rowsAreReadAsTextExactlyAsSentAndARowNumberIsADecimalForTheServiceToCheck() throws Exception {
        when(service.addLines(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(BodyAnswers.readBody(4, new PharmacyViews.LinesResult(1, 412, List.of())));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/lines").with(as("PHARMACIST")).contentType(JSON).content(ROWS))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receivedRows").value(1))
                .andExpect(jsonPath("$.rowCount").value(412)).andExpect(jsonPath("$.issues").isEmpty());

        var sent = BodyAnswers.captor();
        verify(service).addLines(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq(IMPORT_ID.toString()), sent.capture());
        PharmacyInputs.ImportLines body = BodyAnswers.read(sent);
        assertThat(body.rows()).hasSize(1);
        PharmacyInputs.ImportRow row = body.rows().get(0);
        assertThat(row.rowNo()).isEqualByComparingTo("1");
        assertThat(row.storeCode()).isEqualTo("CENTRAL");
        assertThat(row.expiryDate()).isEqualTo("31-12-2027");
        assertThat(row.mrp()).isEqualTo("45.50");
        assertThat(row.quantity()).isEqualTo("100");
    }

    @Test
    void aRowWithAnUnknownFieldIsRefusedAndNoCellIsEchoed() throws Exception {
        when(service.addLines(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(BodyAnswers.readBody(4, new PharmacyViews.LinesResult(1, 412, List.of())));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/lines").with(as("PHARMACIST")).contentType(JSON)
                        .content(ROWS.replace("\"batch\"", "\"secret\":\"CONFIDENTIAL-1\",\"batch\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("CONFIDENTIAL-1"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("TESTB001"))));
    }

    // ---- submit, approve, reject ----

    @Test
    void aFileWithProblemsAnswers422WithTheErrorCountAndTheFirstRowIssues() throws Exception {
        when(service.submit(TENANT_ID, USER_ID, STORE_ID.toString(), IMPORT_ID.toString())).thenThrow(
                new PharmacyProblemException.NotAccepted(3, List.of(new PharmacyViews.RowIssue(17, "expiryDate", "expiry-past",
                        "ERROR", "Expiry date must be in the future"))));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/submit").with(as("PHARMACIST")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM + "opening-stock-invalid"))
                .andExpect(jsonPath("$.title").value("File not accepted"))
                .andExpect(jsonPath("$.detail").value("3 rows have problems. Fix them and upload the file again."))
                .andExpect(jsonPath("$.errorCount").value(3)).andExpect(jsonPath("$.rowIssues[0].rowNo").value(17))
                .andExpect(jsonPath("$.rowIssues[0].field").value("expiryDate"))
                .andExpect(jsonPath("$.rowIssues[0].severity").value("ERROR"));
    }

    @Test
    void submitAnswers200WithTheWaitingImport() throws Exception {
        when(service.submit(TENANT_ID, USER_ID, STORE_ID.toString(), IMPORT_ID.toString())).thenReturn(waiting());
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/submit").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));
    }

    @Test
    void anApprovalByTheUploaderAnswers403SelfApprovalAndAnotherOwnerGets200() throws Exception {
        when(service.approve(TENANT_ID, USER_ID, STORE_ID.toString(), IMPORT_ID.toString())).thenThrow(
                new ForbiddenException("self-approval", "Not allowed", "Another owner must approve this import."));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/approve").with(as("OWNER"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "self-approval"))
                .andExpect(jsonPath("$.detail").value("Another owner must approve this import."));

        UUID other = UUID.randomUUID();
        when(service.approve(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq(other.toString())))
                .thenReturn(waiting());
        mockMvc.perform(post(IMPORTS + "/" + other + "/approve").with(as("OWNER"))).andExpect(status().isOk());
    }

    @Test
    void rejectReadsTheReasonAndAnUnknownReasonIsAValidationProblem() throws Exception {
        when(service.reject(any(), any(), anyString(), anyString(), any())).thenAnswer(BodyAnswers.readBody(4, waiting()));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/reject").with(as("OWNER")).contentType(JSON)
                        .content("{\"reasonKey\":\"WRONG_QUANTITIES\",\"note\":null}")).andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(service).reject(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq(IMPORT_ID.toString()), sent.capture());
        PharmacyInputs.RejectImport body = BodyAnswers.read(sent);
        assertThat(body.reasonKey()).isEqualTo(RejectReason.WRONG_QUANTITIES);
        assertThat(body.note()).isNull();

        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/reject").with(as("OWNER")).contentType(JSON)
                        .content("{\"reasonKey\":\"BORED\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reasonKey"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("BORED"))));
        mockMvc.perform(post(IMPORTS + "/" + IMPORT_ID + "/reject").with(as("OWNER")).contentType(JSON)
                        .content("{\"reasonKey\":\"OTHER\",\"note\":\"Wrong batches\",\"extra\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"));
    }

    // ---- store guard answers ----

    @Test
    void theStoreGuardAnswersAre404AndTheAuditedStorePermissionDenied() throws Exception {
        when(service.get(any(), any(), anyString(), anyString())).thenThrow(new NotFoundException("This record was not found."));
        mockMvc.perform(get(IMPORTS + "/" + IMPORT_ID).with(as("PHARMACIST"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM + "not-found"));
        StoreAccessDeniedException denied = new StoreAccessDeniedException(MEMBER_ID, StoreCapability.UPLOAD_OPENING_STOCK, STORE_ID);
        when(service.start(any(), any(), anyString(), any())).thenThrow(denied);
        mockMvc.perform(post(IMPORTS).with(as("NURSE")).contentType(JSON).content(START)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "store-permission-denied"))
                .andExpect(jsonPath("$.detail").value("You cannot do this in this store."));
        // the handler writes the denial audit, after the service call has ended (N-1)
        verify(denialAuditor).record(denied);
    }

    @Test
    void everyPathIdIsPassedToTheServiceAsTextSoTheGateAnswersBeforeTheIdIsChecked() throws Exception {
        when(service.get(any(), any(), anyString(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        when(service.lines(any(), any(), anyString(), anyString(), any(), any(), any())).thenThrow(PharmacyProblemException.badRequest());
        when(service.submit(any(), any(), anyString(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        when(service.approve(any(), any(), anyString(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        when(service.listForStore(any(), any(), anyString(), any(), any())).thenThrow(PharmacyProblemException.badRequest());
        for (var request : List.of(get(URL + "/stores/not-a-uuid/opening-stock-imports"),
                get(IMPORTS + "/not-a-uuid"), get(IMPORTS + "/not-a-uuid/lines"), post(IMPORTS + "/not-a-uuid/submit"),
                post(URL + "/stores/not-a-uuid/opening-stock-imports/" + IMPORT_ID + "/approve"))) {
            mockMvc.perform(request.with(as("OWNER"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-uuid"))));
        }
    }
}
