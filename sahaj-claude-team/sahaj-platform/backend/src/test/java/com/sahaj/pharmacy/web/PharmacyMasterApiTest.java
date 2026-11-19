package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.ApprovalRulesService;
import com.sahaj.pharmacy.application.NumberingService;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.PrintTemplateService;
import com.sahaj.pharmacy.application.ReasonService;
import com.sahaj.pharmacy.application.VendorService;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
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
 * SAH-78-8, 78-9 and 78-10 over HTTP (docs/api/pharmacy.yaml 0.2.0): status codes, field names, closed bodies, a field left
 * out told apart from a null, 201 or 200 of a vendor create, and the 409 problems that carry {@code current}.
 */
@WebMvcTest(controllers = {VendorController.class, ApprovalRulesController.class, NumberingController.class,
        ReasonController.class, PrintTemplateController.class})
@Import(SecurityConfig.class)
class PharmacyMasterApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID VENDOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "https://sahaj.in/problems/";
    private static final String VENDOR_BODY = "{\"clientKey\":\"00000000-0000-4000-8000-00000000d001\","
            + "\"name\":\"Test Distributors Pvt. Ltd. (fake)\",\"gstin\":\"27AAAAA0000A1Z5\",\"licenceRequired\":true,"
            + "\"licenceNo\":\"TEST-DL-0002\",\"licenceValidTill\":\"2028-03-31\",\"contactName\":\"Test Contact\","
            + "\"contactPhone\":\"9000000001\",\"creditDays\":30,\"returnDaysBeforeExpiry\":90,"
            + "\"returnCreditPercent\":\"100\",\"suppliesToStoreIds\":[]}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private VendorService vendors;
    @MockBean
    private ApprovalRulesService rules;
    @MockBean
    private NumberingService numbering;
    @MockBean
    private ReasonService reasons;
    @MockBean
    private PrintTemplateService templates;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PharmacyMasterViews.Vendor vendor() {
        return new PharmacyMasterViews.Vendor(VENDOR_ID, "Test Distributors Pvt. Ltd. (fake)", "27AAAAA0000A1Z5", true,
                "TEST-DL-0002", LocalDate.of(2028, 3, 31), "VALID", "Test Contact", "9000000001", 30, 90, "100",
                null, null, List.of(new PharmacyMasterViews.StoreName(STORE_ID, "Test Central Store")), 12, "ACTIVE", false, 1L);
    }

    private static PharmacyMasterViews.ApprovalRules approvalRules() {
        return new PharmacyMasterViews.ApprovalRules(2, "CUSTOM", false, Instant.parse("2026-11-17T07:00:00Z"),
                new PharmacyViews.Person(UUID.randomUUID(), "Test Owner One"), 500000L,
                List.of(new PharmacyMasterViews.Rule("PURCHASE_ORDER", List.of(new PharmacyMasterViews.Level(1, "OWNER",
                        new PharmacyMasterViews.Condition("ABOVE_AMOUNT", 5000000L, null, null))), null, null)),
                List.of(new PharmacyMasterViews.PresetInfo("LEAN", List.of("PURCHASE_ORDER"))),
                List.of(new PharmacyMasterViews.FixedRule("NO_SELF_APPROVAL", "Nobody approves their own request.")),
                List.of(new PharmacyMasterViews.StoreWithoutApprover(STORE_ID, "Test OT Store")), List.of());
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(URL + "/vendors")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/vendors").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/vendors/" + VENDOR_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/vendors/" + VENDOR_ID).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/vendors/" + VENDOR_ID + "/switch-off")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/vendors/" + VENDOR_ID + "/switch-on")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/approval-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/approval-rules").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/approval-rules/preset").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/approval-rules/versions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/approval-rules/versions/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/document-series")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/document-series/GRN").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/reason-lists")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons/damage.expired/switch-off")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons/damage.expired/switch-on")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/print-templates")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/print-templates/INVOICE").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(vendors, rules, numbering, reasons, templates);
    }

    // ---- vendors ----

    @Test
    void theVendorListPassesTheRawQueryValuesAndAnswersTheContractShape() throws Exception {
        when(vendors.list(TENANT_ID, USER_ID, "dist", "BLOCKED", null, "1", "10")).thenReturn(
                new PharmacyMasterViews.VendorPage(List.of(vendor()), 31, 1, 10));

        mockMvc.perform(get(URL + "/vendors?q=dist&status=BLOCKED&page=1&size=10").with(as("PHARMACIST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(31)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[0].id").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.items[0].licenceState").value("VALID"))
                .andExpect(jsonPath("$.items[0].licenceValidTill").value("2028-03-31"))
                .andExpect(jsonPath("$.items[0].returnCreditPercent").value("100"))
                .andExpect(jsonPath("$.items[0].suppliesTo[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.items[0].productsCount").value(12))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].blocked").value(false)).andExpect(jsonPath("$.items[0].version").value(1));
    }

    @Test
    void createAnswers201ReadsTheBodyAsTheContractDescribesItAndTellsALeftOutFieldFromANull() throws Exception {
        when(vendors.create(eq(TENANT_ID), eq(USER_ID), any()))
                .thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(vendor(), true)));

        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON)
                        .content(VENDOR_BODY.replace("\"contactName\":\"Test Contact\",", "")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Test Distributors Pvt. Ltd. (fake)"));

        var sent = BodyAnswers.captor();
        verify(vendors).create(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyMasterInputs.VendorCreate body = BodyAnswers.read(sent);
        assertThat(body.clientKey()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-00000000d001"));
        assertThat(body.creditDays()).isEqualByComparingTo("30");
        assertThat(body.licenceRequired()).isTrue();
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.valueOf(body.licenceValidTill()))
                .isEqualTo(LocalDate.of(2028, 3, 31));
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(body.contactName())).isTrue();
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.valueOf(body.returnCreditPercent())).isEqualTo("100");
        assertThat(body.suppliesToStoreIds()).isEmpty();
    }

    @Test
    void theTwoCreditLevelsAreAcceptedOnCreateAndOnUpdateAndTellANullFromALeftOutField() throws Exception {
        when(vendors.create(eq(TENANT_ID), eq(USER_ID), any()))
                .thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(vendor(), true)));
        when(vendors.update(eq(TENANT_ID), eq(USER_ID), eq(VENDOR_ID.toString()), any()))
                .thenAnswer(BodyAnswers.readBody(3, vendor()));
        String levels = "\"damagedCreditPercent\":\"50\",\"expiredCreditPercent\":null,\"suppliesToStoreIds\"";

        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON)
                        .content(VENDOR_BODY.replace("\"suppliesToStoreIds\"", levels)))
                .andExpect(status().isCreated());
        mockMvc.perform(put(URL + "/vendors/" + VENDOR_ID).with(as("OWNER")).contentType(JSON)
                        .content(VENDOR_BODY.replace("\"clientKey\":\"00000000-0000-4000-8000-00000000d001\"", "\"version\":1")
                                .replace("\"suppliesToStoreIds\"", levels)))
                .andExpect(status().isOk());

        var created = BodyAnswers.captor();
        verify(vendors).create(eq(TENANT_ID), eq(USER_ID), created.capture());
        PharmacyMasterInputs.VendorCreate body = BodyAnswers.read(created);
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.valueOf(body.damagedCreditPercent())).isEqualTo("50");
        assertThat(body.expiredCreditPercent()).isNotNull();
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(body.expiredCreditPercent())).isFalse();
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.valueOf(body.expiredCreditPercent())).isNull();

        var updated = BodyAnswers.captor();
        verify(vendors).update(eq(TENANT_ID), eq(USER_ID), eq(VENDOR_ID.toString()), updated.capture());
        PharmacyMasterInputs.VendorUpdate edit = BodyAnswers.read(updated);
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.valueOf(edit.damagedCreditPercent())).isEqualTo("50");
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(edit.expiredCreditPercent())).isFalse();
    }

    @Test
    void createAnswers200ForTheReplayOfAClientKey() throws Exception {
        when(vendors.create(any(), any(), any())).thenReturn(new PharmacyViews.Outcome<>(vendor(), false));

        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON).content(VENDOR_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void createRefusesUnknownFieldsWithoutEchoingThemAndTakesNoBodyAsMalformed() throws Exception {
        when(vendors.create(any(), any(), any()))
                .thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(vendor(), true)));

        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON)
                        .content(VENDOR_BODY.replace("\"creditDays\"", "\"status\":\"ZZZ\",\"creditDays\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("ZZZ"))));
        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNameInUseIsA409ProblemAndAnotherCallersKeyToo() throws Exception {
        when(vendors.create(any(), any(), any())).thenThrow(new ConflictException("vendor-name-in-use",
                "Vendor already in the list", "That vendor is already in the list"));

        mockMvc.perform(post(URL + "/vendors").with(as("OWNER")).contentType(JSON).content(VENDOR_BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "vendor-name-in-use"))
                .andExpect(jsonPath("$.detail").value("That vendor is already in the list"));
    }

    @Test
    void editAnswersSettingsChangedWithTheCurrentVendorAndAForbiddenCallerGets403() throws Exception {
        when(vendors.update(eq(TENANT_ID), eq(USER_ID), eq(VENDOR_ID.toString()), any()))
                .thenThrow(PharmacyProblemException.settingsChanged(vendor()));
        String update = VENDOR_BODY.replace("\"clientKey\":\"00000000-0000-4000-8000-00000000d001\"", "\"version\":0");

        mockMvc.perform(put(URL + "/vendors/" + VENDOR_ID).with(as("OWNER")).contentType(JSON).content(update))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "settings-changed"))
                .andExpect(jsonPath("$.current.id").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.current.version").value(1));

        when(vendors.switchOff(any(), any(), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Only the clinic owner can do this."));
        mockMvc.perform(post(URL + "/vendors/" + VENDOR_ID + "/switch-off").with(as("PHARMACIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void switchOnAnswers200WithTheVendorAndAPharmacistGets403() throws Exception {
        when(vendors.switchOn(TENANT_ID, USER_ID, VENDOR_ID.toString())).thenReturn(vendor());

        mockMvc.perform(post(URL + "/vendors/" + VENDOR_ID + "/switch-on").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.damagedCreditPercent").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        when(vendors.switchOn(any(), any(), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Only the clinic owner can do this."));
        mockMvc.perform(post(URL + "/vendors/" + VENDOR_ID + "/switch-on").with(as("PHARMACIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void oneVendorAnswers200AndAnUnknownOrForeignIdIs404AndABadIdIs400() throws Exception {
        when(vendors.get(TENANT_ID, USER_ID, VENDOR_ID.toString())).thenReturn(vendor());
        mockMvc.perform(get(URL + "/vendors/" + VENDOR_ID).with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").value("9000000001"));

        when(vendors.get(any(), any(), eq("not-a-uuid"))).thenThrow(PharmacyProblemException.badRequest());
        mockMvc.perform(get(URL + "/vendors/not-a-uuid").with(as("OWNER"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"));

        when(vendors.get(any(), any(), eq("00000000-0000-4000-8000-000000000999")))
                .thenThrow(new NotFoundException("This record was not found."));
        mockMvc.perform(get(URL + "/vendors/00000000-0000-4000-8000-000000000999").with(as("OWNER")))
                .andExpect(status().isNotFound());
    }

    // ---- approval rules ----

    @Test
    void theRulesAnswerTheContractShape() throws Exception {
        when(rules.get(TENANT_ID, USER_ID)).thenReturn(approvalRules());

        mockMvc.perform(get(URL + "/approval-rules").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2)).andExpect(jsonPath("$.preset").value("CUSTOM"))
                .andExpect(jsonPath("$.reviewNeeded").value(false))
                .andExpect(jsonPath("$.savedBy.name").value("Test Owner One"))
                .andExpect(jsonPath("$.highValueThresholdPaise").value(500000))
                .andExpect(jsonPath("$.rules[0].transactionType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.rules[0].levels[0].condition.kind").value("ABOVE_AMOUNT"))
                .andExpect(jsonPath("$.rules[0].levels[0].condition.amountPaise").value(5000000))
                .andExpect(jsonPath("$.rules[0].levels[0].condition.percent").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rules[0].levels[0].condition.storeTypes").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.fixedRules[0].key").value("NO_SELF_APPROVAL"))
                .andExpect(jsonPath("$.storesWithoutApprover[0].name").value("Test OT Store"));
    }

    @Test
    void putReadsAllRowsAndAFieldLeftOutIsToldApartFromANull() throws Exception {
        when(rules.save(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, approvalRules()));
        String body = "{\"baseVersionNo\":1,\"highValueThresholdPaise\":null,\"rules\":[{\"transactionType\":\"PURCHASE_ORDER\","
                + "\"levels\":[{\"level\":1,\"approver\":\"OWNER\",\"condition\":{\"kind\":\"ABOVE_PERCENT\",\"percent\":\"5.5\"}}],"
                + "\"escalateAfterHours\":null}]}";

        mockMvc.perform(put(URL + "/approval-rules").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk());

        var sent = BodyAnswers.captor();
        verify(rules).save(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyMasterInputs.RulesSave in = BodyAnswers.read(sent);
        assertThat(in.baseVersionNo()).isEqualTo(1L);
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(in.highValueThresholdPaise())).isFalse();
        PharmacyMasterInputs.RuleIn row = in.rules().get(0);
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(row.escalateAfterHours())).isFalse();
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(row.urgentEscalateAfterMinutes())).isTrue();
        assertThat(row.levels().get(0).condition().kind()).isEqualTo(ConditionKind.ABOVE_PERCENT);
        assertThat(row.levels().get(0).condition().percent()).isEqualTo("5.5");
    }

    @Test
    void aFieldForAFixedRuleIsRefusedAndAnUnknownConditionKindIsAChooseAValidOption() throws Exception {
        when(rules.save(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, approvalRules()));

        mockMvc.perform(put(URL + "/approval-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"baseVersionNo\":0,\"highValueThresholdPaise\":null,\"rules\":[],\"noSelfApproval\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"));
        mockMvc.perform(put(URL + "/approval-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"baseVersionNo\":0,\"highValueThresholdPaise\":null,\"rules\":[{\"transactionType\":"
                                + "\"INDENT\",\"levels\":[{\"level\":1,\"approver\":\"OWNER\",\"condition\":{\"kind\":\"SOMETIMES\"}}]}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a valid option"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("SOMETIMES"))));
    }

    @Test
    void aStaleBaseVersionIsRulesChangedWithTheCurrentRules() throws Exception {
        when(rules.save(any(), any(), any())).thenThrow(PharmacyProblemException.rulesChanged(approvalRules()));

        mockMvc.perform(put(URL + "/approval-rules").with(as("OWNER")).contentType(JSON)
                        .content("{\"baseVersionNo\":0,\"highValueThresholdPaise\":null,\"rules\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "rules-changed"))
                .andExpect(jsonPath("$.current.versionNo").value(2));
    }

    @Test
    void aPresetBodyIsReadAndTheVersionPagesPassTheirRawValues() throws Exception {
        when(rules.applyPreset(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, approvalRules()));
        mockMvc.perform(post(URL + "/approval-rules/preset").with(as("OWNER")).contentType(JSON).content(
                "{\"baseVersionNo\":0,\"preset\":\"LEAN\",\"limits\":[{\"transactionType\":\"PURCHASE_ORDER\","
                        + "\"amountPaise\":5000000,\"percent\":null,\"noAmountCondition\":false}]}"))
                .andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(rules).applyPreset(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyMasterInputs.PresetApply in = BodyAnswers.read(sent);
        assertThat(in.preset().name()).isEqualTo("LEAN");
        assertThat(in.limits().get(0).amountPaise()).isEqualByComparingTo("5000000");
        assertThat(in.limits().get(0).noAmountCondition()).isFalse();

        when(rules.versions(TENANT_ID, USER_ID, "1", "5")).thenReturn(new PharmacyMasterViews.RuleVersionPage(
                List.of(new PharmacyMasterViews.RuleVersion(2, "CUSTOM", Instant.parse("2026-11-17T07:00:00Z"),
                        new PharmacyViews.Person(UUID.randomUUID(), "Test Owner One"))), 6, 1, 5));
        mockMvc.perform(get(URL + "/approval-rules/versions?page=1&size=5").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].versionNo").value(2)).andExpect(jsonPath("$.total").value(6));

        when(rules.version(TENANT_ID, USER_ID, "2")).thenReturn(approvalRules());
        mockMvc.perform(get(URL + "/approval-rules/versions/2").with(as("OWNER"))).andExpect(status().isOk());
        when(rules.version(any(), any(), eq("x"))).thenThrow(PharmacyProblemException.badRequest());
        mockMvc.perform(get(URL + "/approval-rules/versions/x").with(as("OWNER"))).andExpect(status().isBadRequest());
    }

    // ---- numbering ----

    @Test
    void theSeriesListAndPutAnswerTheContractShape() throws Exception {
        PharmacyMasterViews.DocumentSeries po = new PharmacyMasterViews.DocumentSeries("PURCHASE_ORDER", "PO/", true, 5,
                true, "PO/26-27/00001", 14, false, true, 1L);
        when(numbering.list(TENANT_ID, USER_ID)).thenReturn(new PharmacyMasterViews.DocumentSeriesList(List.of(po)));
        mockMvc.perform(get(URL + "/document-series").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].docType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.items[0].restartsEachFinancialYear").value(true))
                .andExpect(jsonPath("$.items[0].example").value("PO/26-27/00001"))
                .andExpect(jsonPath("$.items[0].renderedLength").value(14))
                .andExpect(jsonPath("$.items[0].numbersIssued").value(false)).andExpect(jsonPath("$.items[0].saved").value(true));

        when(numbering.save(eq(TENANT_ID), eq(USER_ID), eq("PURCHASE_ORDER"), any()))
                .thenAnswer(BodyAnswers.readBody(3, po));
        mockMvc.perform(put(URL + "/document-series/PURCHASE_ORDER").with(as("OWNER")).contentType(JSON).content(
                "{\"version\":0,\"prefix\":\"PO/\",\"financialYearPart\":true,\"sequenceLength\":5,"
                        + "\"restartsEachFinancialYear\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("PO/"));
        var sent = BodyAnswers.captor();
        verify(numbering).save(eq(TENANT_ID), eq(USER_ID), eq("PURCHASE_ORDER"), sent.capture());
        PharmacyMasterInputs.SeriesSave in = BodyAnswers.read(sent);
        assertThat(in.sequenceLength()).isEqualByComparingTo("5");
        assertThat(in.restartsEachFinancialYear()).isTrue();

        when(numbering.save(any(), any(), eq("PURCHASE_ORDER"), any())).thenThrow(new ConflictException("prefix-in-use",
                "Prefix already used", "Two document types cannot use the same prefix"));
        mockMvc.perform(put(URL + "/document-series/PURCHASE_ORDER").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "prefix-in-use"));
    }

    // ---- reasons ----

    @Test
    void addAnswers201WithTheListsAndSwitchingAnswers200() throws Exception {
        PharmacyMasterViews.ReasonLists lists = new PharmacyMasterViews.ReasonLists(List.of(
                new PharmacyMasterViews.ReasonListView("damage", "Damage", List.of(
                        new PharmacyMasterViews.Reason("damage.expired", "Expired", true, true, false)))));
        when(reasons.list(TENANT_ID, USER_ID)).thenReturn(lists);
        when(reasons.add(eq(TENANT_ID), eq(USER_ID), eq("damage"), any())).thenAnswer(BodyAnswers.readBody(3, lists));
        when(reasons.switchOff(TENANT_ID, USER_ID, "damage", "damage.expired")).thenReturn(lists);
        when(reasons.switchOn(TENANT_ID, USER_ID, "damage", "damage.expired")).thenReturn(lists);

        mockMvc.perform(get(URL + "/reason-lists").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lists[0].listKey").value("damage"))
                .andExpect(jsonPath("$.lists[0].reasons[0].seeded").value(true));
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons").with(as("OWNER")).contentType(JSON)
                        .content("{\"label\":\"Packaging torn\"}")).andExpect(status().isCreated());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons").with(as("OWNER")).contentType(JSON)
                        .content("{\"label\":\"Packaging torn\",\"active\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons/damage.expired/switch-off").with(as("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons/damage.expired/switch-on").with(as("OWNER")))
                .andExpect(status().isOk());

        when(reasons.switchOff(any(), any(), eq("damage"), eq("damage.cold-chain-break"))).thenThrow(new ConflictException(
                "last-active-reason", "Keep one reason", "Keep at least one damage reason."));
        mockMvc.perform(post(URL + "/reason-lists/damage/reasons/damage.cold-chain-break/switch-off").with(as("OWNER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "last-active-reason"));
    }

    // ---- print templates ----

    @Test
    void theTemplatesAnswerTheContractShapeAndPutReadsTheHeaderLines() throws Exception {
        PharmacyMasterViews.PrintTemplate invoice = new PharmacyMasterViews.PrintTemplate("INVOICE",
                List.of("Test Clinic Baner Pharmacy"), List.of("Test Clinic Baner"), null, "A5", List.of("UNIT_NAME", "GSTIN"),
                false, List.of("GSTIN"), true, 1L);
        when(templates.list(TENANT_ID, USER_ID)).thenReturn(new PharmacyMasterViews.PrintTemplateList(List.of(invoice)));
        when(templates.save(eq(TENANT_ID), eq(USER_ID), eq("INVOICE"), any())).thenAnswer(BodyAnswers.readBody(3, invoice));

        mockMvc.perform(get(URL + "/print-templates").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].docType").value("INVOICE"))
                .andExpect(jsonPath("$.items[0].headerLines[0]").value("Test Clinic Baner Pharmacy"))
                .andExpect(jsonPath("$.items[0].defaultHeaderLines[0]").value("Test Clinic Baner"))
                .andExpect(jsonPath("$.items[0].footerNote").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].printable").value(false)).andExpect(jsonPath("$.items[0].missing[0]").value("GSTIN"));
        mockMvc.perform(put(URL + "/print-templates/INVOICE").with(as("OWNER")).contentType(JSON).content(
                "{\"version\":0,\"headerLines\":[\"Test Road\"],\"footerNote\":null,\"paperSize\":\"A5\"}"))
                .andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(templates).save(eq(TENANT_ID), eq(USER_ID), eq("INVOICE"), sent.capture());
        PharmacyMasterInputs.TemplateSave in = BodyAnswers.read(sent);
        assertThat(in.headerLines()).containsExactly("Test Road");
        assertThat(com.sahaj.pharmacy.application.PharmacyInputs.Sent.missing(in.footerNote())).isFalse();
        assertThat(in.paperSize()).isEqualTo("A5");

        mockMvc.perform(put(URL + "/print-templates/INVOICE").with(as("OWNER")).contentType(JSON).content(
                "{\"version\":0,\"headerLines\":[],\"footerNote\":null,\"paperSize\":\"A5\",\"fixedFields\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
