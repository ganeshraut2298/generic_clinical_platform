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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ProductSearchService;
import com.sahaj.pharmacy.application.ProductService;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.math.BigDecimal;
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
 * SAH-78-5 and 78-6 over HTTP (docs/api/pharmacy.yaml 0.1.1): the product master. Status codes, field names, closed bodies,
 * the 201 or 200 of create and adopt, and the 400 and 409 problems that carry extra members.
 */
@WebMvcTest(controllers = ProductController.class)
@Import(SecurityConfig.class)
class PharmacyProductApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    private static final UUID CATALOGUE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "https://sahaj.in/problems/";
    private static final String OWN_BODY = "{\"clientKey\":\"00000000-0000-4000-8000-00000000b001\",\"name\":\"Test dressing kit (OT)\","
            + "\"salt\":\"Dressing kit\",\"strength\":\"1 kit\",\"form\":\"kit\",\"purchaseUnit\":\"Kit\",\"issueUnit\":\"kit\","
            + "\"conversion\":1,\"manufacturer\":null,\"madeInHouse\":true,\"hsn\":\"3005\",\"gstRate\":\"12\","
            + "\"schedule\":\"NONE\",\"barcode\":null,\"ignoreDuplicates\":false}";
    private static final String LOCAL_BODY = "{\"version\":2,\"storeSettings\":[{\"storeId\":\"" + STORE_ID
            + "\",\"reorderLevel\":200,\"rack\":\"A-3\"}],\"preferredVendorId\":null,\"alias\":\"Ceftri 1g\",\"barcode\":null,"
            + "\"returnable\":true,\"active\":true}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private ProductSearchService search;
    @MockBean
    private ProductService products;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PharmacyViews.ProductDetail detail(boolean own) {
        return new PharmacyViews.ProductDetail(own ? "OWN" : "GLOBAL", own ? null : CATALOGUE_ID, PRODUCT_ID,
                own ? "U-TCB-0001" : "G-000101", "Test product", own ? null : "Sample Labs (fake)", own, "Salt", "1 g", "injection",
                "Box of 10", "vial", 10, "3004", "12", "H", false, own ? "ACTIVE" : "ADOPTED", !own, "Ceftri 1g",
                List.of("8900000000017"), new PharmacyViews.ProductLocalSettings(
                        List.of(new PharmacyViews.StoreSetting(STORE_ID, "Test Central Store", 200, "A-3")),
                        new PharmacyViews.PreferredVendor(UUID.randomUUID(), "Test Vendor One", true), "Ceftri 1g", null,
                        true, true), false, false, 2L);
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(URL + "/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/products").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/products/duplicates?salt=a&strength=b&form=c")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/product-options")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/catalogue-products/" + CATALOGUE_ID + "/adopt")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/products/" + PRODUCT_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(search, products);
    }

    // ---- list, one, options, duplicates ----

    @Test
    void theListPassesTheRawQueryValuesToTheServiceAndAnswersTheContractShape() throws Exception {
        PharmacyViews.ProductRow row = new PharmacyViews.ProductRow("GLOBAL", CATALOGUE_ID, PRODUCT_ID, "G-000101",
                "Sample Ceftriaxone 1 g injection", "Sample Labs (fake)", false, "Ceftriaxone", "1 g", "injection",
                "Box of 10", "vial", 10, "3004", "12", "H", false, "ADOPTED", true, null);
        when(search.search(TENANT_ID, USER_ID, "cef", "GLOBAL", "1", "10")).thenReturn(
                new PharmacyViews.ProductPage(List.of(row), 31, 1, 10));

        mockMvc.perform(get(URL + "/products?q=cef&source=GLOBAL&page=1&size=10").with(as("PHARMACIST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(31)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.items[0].source").value("GLOBAL"))
                .andExpect(jsonPath("$.items[0].catalogueProductId").value(CATALOGUE_ID.toString()))
                .andExpect(jsonPath("$.items[0].gstRate").value("12")).andExpect(jsonPath("$.items[0].sample").value(true))
                .andExpect(jsonPath("$.items[0].alias").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].inUnit").value("ADOPTED"));
    }

    @Test
    void aBadQueryValueIsAValidationProblemFromTheService() throws Exception {
        when(search.search(any(), any(), any(), eq("MINE"), any(), any())).thenThrow(new com.sahaj.platform.api.InvalidFieldsException(
                List.of(new com.sahaj.platform.api.InvalidFieldsException.FieldMessage("source", "Choose a valid option"))));
        mockMvc.perform(get(URL + "/products?source=MINE").with(as("OWNER"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("source"));
    }

    @Test
    void oneProductShowsTheLocalSettingsWithTheVendorReviewFlag() throws Exception {
        when(search.get(TENANT_ID, USER_ID, PRODUCT_ID.toString())).thenReturn(detail(false));
        mockMvc.perform(get(URL + "/products/" + PRODUCT_ID).with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.barcodes[0]").value("8900000000017"))
                .andExpect(jsonPath("$.localSettings.storeSettings[0].storeName").value("Test Central Store"))
                .andExpect(jsonPath("$.localSettings.storeSettings[0].reorderLevel").value(200))
                .andExpect(jsonPath("$.localSettings.preferredVendor.needsReview").value(true))
                .andExpect(jsonPath("$.localSettings.barcode").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.unitsLocked").value(false)).andExpect(jsonPath("$.hasStock").value(false))
                .andExpect(jsonPath("$.version").value(2));
        when(search.get(any(), any(), eq("not-a-uuid"))).thenThrow(PharmacyProblemException.badRequest());
        mockMvc.perform(get(URL + "/products/not-a-uuid").with(as("OWNER"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-uuid"))));
        UUID other = UUID.randomUUID();
        when(search.get(TENANT_ID, USER_ID, other.toString())).thenThrow(new NotFoundException("This record was not found."));
        mockMvc.perform(get(URL + "/products/" + other).with(as("OWNER"))).andExpect(status().isNotFound());
    }

    @Test
    void theLiteralPathsAreNotTakenForAProductId() throws Exception {
        when(search.duplicates(TENANT_ID, USER_ID, "Ceftriaxone", "1 g", "injection", null))
                .thenReturn(new PharmacyViews.Duplicates(List.of(new PharmacyViews.DuplicateCandidate("GLOBAL", CATALOGUE_ID,
                        null, "G-000101", "Sample Ceftriaxone 1 g injection", "Sample Labs (fake)", false, "NOT_ADOPTED"))));
        mockMvc.perform(get(URL + "/products/duplicates").param("salt", "Ceftriaxone").param("strength", "1 g")
                        .param("form", "injection").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].code").value("G-000101"))
                .andExpect(jsonPath("$.candidates[0].inUnit").value("NOT_ADOPTED"))
                .andExpect(jsonPath("$.candidates[0].productId").value(Matchers.nullValue()));
        verifyNoUnexpectedGet();
        when(search.options(TENANT_ID, USER_ID)).thenReturn(new PharmacyViews.ProductOptions(List.of("Box"), List.of("tablet")));
        mockMvc.perform(get(URL + "/product-options").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0]").value("Box")).andExpect(jsonPath("$.forms[0]").value("tablet"));
    }

    private void verifyNoUnexpectedGet() {
        org.mockito.Mockito.verify(search, org.mockito.Mockito.never()).get(any(), any(), anyString());
    }

    // ---- adopt ----

    @Test
    void adoptAnswers201WhenCreatedAnd200WhenAlreadyAdopted() throws Exception {
        when(products.adopt(TENANT_ID, USER_ID, CATALOGUE_ID.toString()))
                .thenReturn(new PharmacyViews.Outcome<>(detail(false), true));
        mockMvc.perform(post(URL + "/catalogue-products/" + CATALOGUE_ID + "/adopt").with(as("OWNER")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("GLOBAL"))
                .andExpect(jsonPath("$.inUnit").value("ADOPTED"));
        when(products.adopt(TENANT_ID, USER_ID, CATALOGUE_ID.toString()))
                .thenReturn(new PharmacyViews.Outcome<>(detail(false), false));
        mockMvc.perform(post(URL + "/catalogue-products/" + CATALOGUE_ID + "/adopt").with(as("OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void adoptRefusalsAreTheContractsProblems() throws Exception {
        when(products.adopt(any(), any(), eq("not-a-uuid"))).thenThrow(PharmacyProblemException.badRequest());
        mockMvc.perform(post(URL + "/catalogue-products/not-a-uuid/adopt").with(as("OWNER"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"));
        when(products.adopt(any(), any(), eq(CATALOGUE_ID.toString()))).thenThrow(new ConflictException("product-withdrawn",
                "Product withdrawn", "This product was withdrawn from the catalogue."));
        mockMvc.perform(post(URL + "/catalogue-products/" + CATALOGUE_ID + "/adopt").with(as("OWNER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "product-withdrawn"))
                .andExpect(jsonPath("$.detail").value("This product was withdrawn from the catalogue."));
        when(products.adopt(any(), any(), eq(CATALOGUE_ID.toString()))).thenThrow(new ForbiddenException("forbidden",
                "Not allowed", "Only the clinic owner can do this."));
        mockMvc.perform(post(URL + "/catalogue-products/" + CATALOGUE_ID + "/adopt").with(as("PHARMACIST")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
    }

    // ---- create and edit ----

    @Test
    void createAnswers201AndReadsTheBodyAsTheContractDescribesIt() throws Exception {
        when(products.create(eq(TENANT_ID), eq(USER_ID), any()))
                .thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(detail(true), true)));

        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content(OWN_BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("U-TCB-0001"))
                .andExpect(jsonPath("$.source").value("OWN"));

        var sent = BodyAnswers.captor();
        verify(products).create(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyInputs.OwnProduct body = BodyAnswers.read(sent);
        assertThat(body.clientKey()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-00000000b001"));
        assertThat(body.conversion()).isEqualByComparingTo("1");
        assertThat(body.madeInHouse()).isTrue();
        assertThat(PharmacyInputs.Sent.missing(body.manufacturer())).isFalse();
        assertThat(PharmacyInputs.Sent.valueOf(body.manufacturer())).isNull();
        assertThat(PharmacyInputs.Sent.valueOf(body.schedule())).isEqualTo(ProductSchedule.NONE);
        assertThat(body.gstRate()).isEqualTo("12");
    }

    @Test
    void createAnswers200ForTheReplayOfAClientKey() throws Exception {
        when(products.create(any(), any(), any())).thenReturn(new PharmacyViews.Outcome<>(detail(true), false));
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content(OWN_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aFieldLeftOutIsToldApartFromANullAndADecimalConversionIsReadAsADecimal() throws Exception {
        when(products.create(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(detail(true), true)));
        String noManufacturer = OWN_BODY.replace("\"manufacturer\":null,", "").replace("\"conversion\":1,", "\"conversion\":2.5,");
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content(noManufacturer))
                .andExpect(status().isCreated());
        var sent = BodyAnswers.captor();
        verify(products).create(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyInputs.OwnProduct body = BodyAnswers.read(sent);
        assertThat(PharmacyInputs.Sent.missing(body.manufacturer())).isTrue();
        assertThat(body.conversion()).isEqualTo(new BigDecimal("2.5"));
    }

    @Test
    void createRefusesUnknownFieldsAndAnUnknownScheduleWithoutEchoingThem() throws Exception {
        when(products.create(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, new PharmacyViews.Outcome<>(detail(true), true)));
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON)
                        .content(OWN_BODY.replace("\"madeInHouse\"", "\"unitCode\":\"ZZZ\",\"madeInHouse\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("ZZZ"))));
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON)
                        .content(OWN_BODY.replace("\"NONE\"", "\"Z9\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("schedule"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Z9"))));
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePossibleDuplicateProblemCarriesTheCandidates() throws Exception {
        PharmacyViews.DuplicateCandidate candidate = new PharmacyViews.DuplicateCandidate("GLOBAL", CATALOGUE_ID, null,
                "G-000101", "Sample Ceftriaxone 1 g injection", "Sample Labs (fake)", false, "NOT_ADOPTED");
        when(products.create(any(), any(), any())).thenThrow(PharmacyProblemException.possibleDuplicate(List.of(candidate)));
        mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content(OWN_BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "possible-duplicate"))
                .andExpect(jsonPath("$.candidates[0].code").value("G-000101"))
                .andExpect(jsonPath("$.candidates[0].manufacturer").value("Sample Labs (fake)"))
                .andExpect(jsonPath("$.candidates[0].inUnit").value("NOT_ADOPTED"));
    }

    @Test
    void theConflictTypesOfCreateAreTheContractsAndTheMessagesAreFixed() throws Exception {
        for (String[] c : new String[][] {{"barcode-in-use", "That barcode is already used by Test product"},
                {"client-key-reused", "This request was already sent by someone else. Reload the page and try again."}}) {
            org.mockito.Mockito.doThrow(new ConflictException(c[0], "Conflict", c[1])).when(products).create(any(), any(), any());
            mockMvc.perform(post(URL + "/products").with(as("OWNER")).contentType(JSON).content(OWN_BODY))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + c[0]))
                    .andExpect(jsonPath("$.detail").value(c[1]));
        }
    }

    @Test
    void editingAnAdoptedProductAnswers400GlobalFieldsReadOnly() throws Exception {
        when(products.update(eq(TENANT_ID), eq(USER_ID), eq(PRODUCT_ID.toString()), any()))
                .thenThrow(PharmacyProblemException.globalFieldsReadOnly());
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID).with(as("OWNER")).contentType(JSON)
                        .content(OWN_BODY.replace("\"clientKey\":\"00000000-0000-4000-8000-00000000b001\",", "\"version\":0,")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "global-fields-read-only"))
                .andExpect(jsonPath("$.detail").value("Global fields cannot be changed. Use Local settings."));
    }

    @Test
    void anEditCarriesTheVersionAndAClientKeyIsNotPartOfIt() throws Exception {
        when(products.update(eq(TENANT_ID), eq(USER_ID), eq(PRODUCT_ID.toString()), any()))
                .thenAnswer(BodyAnswers.readBody(3, detail(true)));
        String edit = OWN_BODY.replace("\"clientKey\":\"00000000-0000-4000-8000-00000000b001\",", "\"version\":4,");
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID).with(as("OWNER")).contentType(JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("U-TCB-0001"));
        var sent = BodyAnswers.captor();
        verify(products).update(eq(TENANT_ID), eq(USER_ID), eq(PRODUCT_ID.toString()), sent.capture());
        PharmacyInputs.OwnProductChange body = BodyAnswers.read(sent);
        assertThat(body.version()).isEqualTo(4L);
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID).with(as("OWNER")).contentType(JSON).content(OWN_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStaleProductVersionAnswers409SettingsChangedWithTheCurrentProduct() throws Exception {
        when(products.update(any(), any(), anyString(), any())).thenThrow(PharmacyProblemException.settingsChanged(detail(true)));
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID).with(as("OWNER")).contentType(JSON)
                        .content(OWN_BODY.replace("\"clientKey\":\"00000000-0000-4000-8000-00000000b001\",", "\"version\":0,")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "settings-changed"))
                .andExpect(jsonPath("$.current.code").value("U-TCB-0001")).andExpect(jsonPath("$.current.version").value(2));
    }

    // ---- local settings ----

    @Test
    void localSettingsReadTheBodyAsTheContractDescribesIt() throws Exception {
        when(products.saveLocalSettings(eq(TENANT_ID), eq(USER_ID), eq(PRODUCT_ID.toString()), any()))
                .thenAnswer(BodyAnswers.readBody(3, detail(false)));

        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").with(as("OWNER")).contentType(JSON)
                        .content(LOCAL_BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.localSettings.alias").value("Ceftri 1g"));

        var sent = BodyAnswers.captor();
        verify(products).saveLocalSettings(eq(TENANT_ID), eq(USER_ID), eq(PRODUCT_ID.toString()), sent.capture());
        PharmacyInputs.LocalSettings body = BodyAnswers.read(sent);
        assertThat(body.version()).isEqualTo(2L);
        assertThat(body.storeSettings()).hasSize(1);
        assertThat(body.storeSettings().get(0).storeId()).isEqualTo(STORE_ID);
        assertThat(PharmacyInputs.Sent.valueOf(body.storeSettings().get(0).reorderLevel())).isEqualByComparingTo("200");
        assertThat(PharmacyInputs.Sent.valueOf(body.storeSettings().get(0).rack())).isEqualTo("A-3");
        assertThat(PharmacyInputs.Sent.missing(body.preferredVendorId())).isFalse();
        assertThat(PharmacyInputs.Sent.valueOf(body.preferredVendorId())).isNull();
        assertThat(body.active()).isTrue();
    }

    @Test
    void aGlobalFieldOrAnUnknownFieldInTheLocalSettingsIsRefusedAndAMissingOneIsTold() throws Exception {
        when(products.saveLocalSettings(any(), any(), anyString(), any())).thenAnswer(BodyAnswers.readBody(3, detail(false)));
        for (String bad : new String[] {LOCAL_BODY.replace("\"alias\"", "\"name\":\"New name\",\"alias\""),
                LOCAL_BODY.replace("\"rack\":\"A-3\"", "\"rack\":\"A-3\",\"shelf\":1"),
                LOCAL_BODY.replace("\"version\":2", "\"version\":\"two\"")}) {
            mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").with(as("OWNER")).contentType(JSON)
                            .content(bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "validation"))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("New name"))));
        }
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").with(as("OWNER")).contentType(JSON)
                        .content(LOCAL_BODY.replace("\"alias\":\"Ceftri 1g\",", ""))).andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(products, org.mockito.Mockito.atLeastOnce()).saveLocalSettings(any(), any(), anyString(), sent.capture());
        PharmacyInputs.LocalSettings body = BodyAnswers.read(sent);
        assertThat(PharmacyInputs.Sent.missing(body.alias())).isTrue();
    }

    @Test
    void theLocalSettingsConflictsAreTheContractsTypes() throws Exception {
        for (String[] c : new String[][] {{"barcode-in-use", "That barcode is already used by Test product"},
                {"product-has-stock", "This product has stock. Clear it in Inventory first."}}) {
            org.mockito.Mockito.doThrow(new ConflictException(c[0], "Conflict", c[1])).when(products)
                    .saveLocalSettings(any(), any(), anyString(), any());
            mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").with(as("OWNER")).contentType(JSON)
                            .content(LOCAL_BODY)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(PROBLEM + c[0])).andExpect(jsonPath("$.detail").value(c[1]));
        }
        org.mockito.Mockito.doThrow(PharmacyProblemException.settingsChanged(detail(false))).when(products)
                .saveLocalSettings(any(), any(), anyString(), any());
        mockMvc.perform(put(URL + "/products/" + PRODUCT_ID + "/local-settings").with(as("OWNER")).contentType(JSON)
                        .content(LOCAL_BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "settings-changed"))
                .andExpect(jsonPath("$.current.localSettings.alias").value("Ceftri 1g"));
    }

    @Test
    void aPathIdIsPassedToTheServiceAsTextSoTheGateAnswersBeforeTheIdIsChecked() throws Exception {
        when(products.update(any(), any(), eq("not-a-uuid"), any())).thenThrow(PharmacyProblemException.badRequest());
        when(products.saveLocalSettings(any(), any(), eq("not-a-uuid"), any())).thenThrow(PharmacyProblemException.badRequest());
        mockMvc.perform(put(URL + "/products/not-a-uuid").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"));
        mockMvc.perform(put(URL + "/products/not-a-uuid/local-settings").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEM + "bad-request"));
    }
}
