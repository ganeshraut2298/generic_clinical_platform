package com.sahaj.pharmacy.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.sahaj.pharmacy.application.PharmacyViews.StaffMapping;
import com.sahaj.pharmacy.application.PharmacyViews.Store;
import com.sahaj.pharmacy.application.StaffMappingService;
import com.sahaj.pharmacy.application.StoreService;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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
 * SAH-78-3 and 78-4 over HTTP (docs/api/pharmacy.yaml 0.1.0): stores and staff mapping. Status codes, field names, the
 * 400 for a bad path id or query value, closed bodies and the 409 problems that carry extra members.
 */
@WebMvcTest(controllers = {StoreController.class, StaffController.class})
@Import(SecurityConfig.class)
class PharmacyStoreApiTest {

    private static final String URL = "/api/v1/pharmacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-4000-8000-000000000021");
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BAD_REQUEST = "https://sahaj.in/problems/bad-request";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private StoreService stores;
    @MockBean
    private StaffMappingService staff;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Store store() {
        return new Store(STORE_ID, "Test OPD Store", "OPD", StoreType.SUB_STORE,
                UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "Central Store", false, true,
                new PharmacyViews.StorePerson(MEMBER_ID, "Test Doctor One", "Doctor", true),
                new PharmacyViews.StoreDelegate(UUID.randomUUID(), "Test Nurse One", "Nurse", true,
                        LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 30), DelegateState.CURRENT),
                "SET", 5, "ACTIVE", false, List.of(), 4L);
    }

    private static StaffMapping mapping() {
        return new StaffMapping(MAPPING_ID, STORE_ID, MEMBER_ID, "Test Doctor One", "DOCTOR", "Doctor", true,
                StoreRole.APPROVER, StoreRole.APPROVER, null, null, null,
                new StoreAccess(true, true, true, false, true, false, true, false, true),
                List.of(new PharmacyViews.OtherStore(UUID.randomUUID(), "Test Main Pharmacy")), "ACTIVE", false, "ACTIVE", 1L);
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(URL + "/stores")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/stores").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/stores/" + STORE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/stores/" + STORE_ID).contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/switch-off")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/staff-candidates?storeId=" + STORE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/stores/" + STORE_ID + "/staff")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/stores/" + STORE_ID + "/staff/" + MAPPING_ID).contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff/" + MAPPING_ID + "/remove"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(stores, staff);
    }

    // ---- stores ----

    @Test
    void theListIsSmallAndCompleteWithTheCentralStoreId() throws Exception {
        UUID central = UUID.randomUUID();
        when(stores.list(TENANT_ID, USER_ID, (String) null)).thenReturn(new PharmacyViews.StoreList(List.of(store()), central));

        mockMvc.perform(get(URL + "/stores").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.centralStoreId").value(central.toString()))
                .andExpect(jsonPath("$.items[0].id").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Test OPD Store"))
                .andExpect(jsonPath("$.items[0].type").value("SUB_STORE"))
                .andExpect(jsonPath("$.items[0].issuesFromName").value("Central Store"))
                .andExpect(jsonPath("$.items[0].saleAllowed").value(false))
                .andExpect(jsonPath("$.items[0].consumptionAllowed").value(true))
                .andExpect(jsonPath("$.items[0].hod.name").value("Test Doctor One"))
                .andExpect(jsonPath("$.items[0].hod.canSignIn").value(true))
                .andExpect(jsonPath("$.items[0].delegate.state").value("CURRENT"))
                .andExpect(jsonPath("$.items[0].delegate.from").value("2026-11-17"))
                .andExpect(jsonPath("$.items[0].approverState").value("SET"))
                .andExpect(jsonPath("$.items[0].staffCount").value(5))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].hasStock").value(false))
                .andExpect(jsonPath("$.items[0].lockedFields").isEmpty())
                .andExpect(jsonPath("$.items[0].version").value(4));
    }

    @Test
    void includeInactiveGoesToTheServiceAsTextSoTheGateAnswersBeforeAnyValueCheck() throws Exception {
        when(stores.list(TENANT_ID, USER_ID, "true")).thenReturn(new PharmacyViews.StoreList(List.of(), null));
        mockMvc.perform(get(URL + "/stores?includeInactive=true").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.centralStoreId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(URL + "/stores?includeInactive=perhaps").with(as("OWNER"))).andExpect(status().isOk());
        verify(stores).list(TENANT_ID, USER_ID, "perhaps");
    }

    @Test
    void createAnswers201AndPassesTheBodyAsTyped() throws Exception {
        when(stores.create(eq(TENANT_ID), eq(USER_ID), any())).thenAnswer(BodyAnswers.readBody(2, store()));
        String body = "{\"name\":\"Test Ward 3B Store\",\"code\":\"W3B\",\"type\":\"WARD\",\"saleAllowed\":false,"
                + "\"consumptionAllowed\":true,\"hodMembershipId\":\"" + MEMBER_ID + "\",\"delegate\":{\"membershipId\":\""
                + UUID.randomUUID() + "\",\"from\":\"2026-11-17\",\"to\":\"2026-11-30\"}}";

        mockMvc.perform(post(URL + "/stores").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(STORE_ID.toString()));
        var sent = BodyAnswers.captor();
        verify(stores).create(eq(TENANT_ID), eq(USER_ID), sent.capture());
        PharmacyInputs.StoreCreate create = BodyAnswers.read(sent);
        org.assertj.core.api.Assertions.assertThat(create.name()).isEqualTo("Test Ward 3B Store");
        org.assertj.core.api.Assertions.assertThat(create.type()).isEqualTo(StoreType.WARD);
        org.assertj.core.api.Assertions.assertThat(create.hodMembershipId()).isEqualTo(MEMBER_ID);
        org.assertj.core.api.Assertions.assertThat(create.delegate().to()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void createRefusesUnknownFieldsAndUnknownTypes() throws Exception {
        when(stores.create(any(), any(), any())).thenAnswer(BodyAnswers.readBody(2, store()));
        mockMvc.perform(post(URL + "/stores").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"n\",\"code\":\"c\",\"type\":\"WARD\",\"issuesFrom\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        mockMvc.perform(post(URL + "/stores").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"n\",\"code\":\"c\",\"type\":\"KIOSK\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("type"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("KIOSK"))));
    }

    @Test
    void theConflictTypesOfCreateAreTheContractsAndTheMessagesAreFixed() throws Exception {
        for (String[] c : new String[][] {
                {"central-store-exists", "This unit already has a Central Store."},
                {"central-store-missing", "Create the Central Store first."},
                {"store-name-in-use", "That store name is already used"},
                {"store-code-in-use", "That store code is already used"}}) {
            doThrow(new ConflictException(c[0], "Conflict", c[1])).when(stores).create(any(), any(), any());
            mockMvc.perform(post(URL + "/stores").with(as("OWNER")).contentType(JSON)
                            .content("{\"name\":\"n\",\"code\":\"c\",\"type\":\"WARD\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/" + c[0]))
                    .andExpect(jsonPath("$.detail").value(c[1]));
        }
    }

    @Test
    void getOneAndASwitchedOffStoreAnd404() throws Exception {
        when(stores.get(TENANT_ID, USER_ID, STORE_ID.toString())).thenReturn(store());
        mockMvc.perform(get(URL + "/stores/" + STORE_ID).with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OPD"));
        UUID other = UUID.randomUUID();
        when(stores.get(TENANT_ID, USER_ID, other.toString())).thenThrow(new NotFoundException("This record was not found."));
        mockMvc.perform(get(URL + "/stores/" + other).with(as("PHARMACIST"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-found"));
    }

    @Test
    void aPathIdIsPassedToTheServiceAsTextSoTheGateAnswersBeforeTheIdIsChecked() throws Exception {
        when(stores.get(any(), any(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        when(stores.update(any(), any(), anyString(), any())).thenThrow(PharmacyProblemException.badRequest());
        when(stores.switchOff(any(), any(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        when(staff.list(any(), any(), anyString(), any())).thenThrow(PharmacyProblemException.badRequest());
        when(staff.map(any(), any(), anyString(), any())).thenThrow(PharmacyProblemException.badRequest());
        when(staff.change(any(), any(), anyString(), anyString(), any())).thenThrow(PharmacyProblemException.badRequest());
        when(staff.remove(any(), any(), anyString(), anyString())).thenThrow(PharmacyProblemException.badRequest());
        for (var request : List.of(get(URL + "/stores/not-a-uuid"), put(URL + "/stores/not-a-uuid").contentType(JSON).content("{}"),
                post(URL + "/stores/not-a-uuid/switch-off"), get(URL + "/stores/not-a-uuid/staff"),
                post(URL + "/stores/not-a-uuid/staff").contentType(JSON).content("{}"),
                put(URL + "/stores/" + STORE_ID + "/staff/not-a-uuid").contentType(JSON).content("{}"),
                post(URL + "/stores/" + STORE_ID + "/staff/not-a-uuid/remove"))) {
            mockMvc.perform(request.with(as("OWNER"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(BAD_REQUEST))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-uuid"))));
        }
        verify(stores).get(TENANT_ID, USER_ID, "not-a-uuid");
        verify(staff).change(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq("not-a-uuid"), any());
    }

    @Test
    void updateCarriesTheVersionAndAStaleOneAnswers409WithCurrent() throws Exception {
        when(stores.update(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), any())).thenThrow(
                PharmacyProblemException.settingsChanged(store()));
        mockMvc.perform(put(URL + "/stores/" + STORE_ID).with(as("OWNER")).contentType(JSON).content(
                        "{\"version\":3,\"name\":\"Test OPD Store\",\"code\":\"OPD\",\"type\":\"SUB_STORE\","
                                + "\"saleAllowed\":false,\"consumptionAllowed\":true,\"hodMembershipId\":null,\"delegate\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/settings-changed"))
                .andExpect(jsonPath("$.current.id").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.current.version").value(4));
    }

    @Test
    void aStoreWithStockAnswersStoreHasStockWithTheLockedFields() throws Exception {
        when(stores.update(any(), any(), anyString(), any())).thenThrow(PharmacyProblemException.storeHasStockLocked(
                List.of("TYPE", "CODE")));
        mockMvc.perform(put(URL + "/stores/" + STORE_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":3,\"name\":\"n\",\"code\":\"X\",\"type\":\"WARD\",\"saleAllowed\":true,"
                                + "\"consumptionAllowed\":true,\"hodMembershipId\":null,\"delegate\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/store-has-stock"))
                .andExpect(jsonPath("$.detail").value("This store has stock. Type and code cannot change."))
                .andExpect(jsonPath("$.lockedFields[0]").value("TYPE"))
                .andExpect(jsonPath("$.lockedFields[1]").value("CODE"));
    }

    @Test
    void switchOffAnswers200WithTheStoreAndItsConflictsAreTheContractsTypes() throws Exception {
        when(stores.switchOff(TENANT_ID, USER_ID, STORE_ID.toString())).thenReturn(store());
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/switch-off").with(as("OWNER"))).andExpect(status().isOk());

        doThrow(PharmacyProblemException.storeHasStockSwitchOff()).when(stores).switchOff(any(), any(), anyString());
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/switch-off").with(as("OWNER"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/store-has-stock"))
                .andExpect(jsonPath("$.detail").value("This store has stock. Clear it in Inventory first."));
    }

    @Test
    void aNonOwnerWriteIsForbiddenByTheService() throws Exception {
        when(stores.create(any(), any(), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Only the clinic owner can do this."));
        mockMvc.perform(post(URL + "/stores").with(as("STORE_STAFF")).contentType(JSON)
                        .content("{\"name\":\"n\",\"code\":\"c\",\"type\":\"WARD\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
    }

    // ---- staff ----

    @Test
    void theStaffListHasTheMockupColumnsAndDerivedAccess() throws Exception {
        when(staff.list(TENANT_ID, USER_ID, STORE_ID.toString(), null)).thenReturn(new PharmacyViews.StaffMappings(List.of(mapping())));

        mockMvc.perform(get(URL + "/stores/" + STORE_ID + "/staff").with(as("PHARMACIST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(MAPPING_ID.toString()))
                .andExpect(jsonPath("$.items[0].roleLabel").value("Doctor"))
                .andExpect(jsonPath("$.items[0].isHod").value(true))
                .andExpect(jsonPath("$.items[0].storeRole").value("APPROVER"))
                .andExpect(jsonPath("$.items[0].effectiveStoreRole").value("APPROVER"))
                .andExpect(jsonPath("$.items[0].delegateFrom").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].delegateState").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].access.approve").value(true))
                .andExpect(jsonPath("$.items[0].access.receiveScheduleX").value(false))
                .andExpect(jsonPath("$.items[0].otherStores[0].name").value("Test Main Pharmacy"))
                .andExpect(jsonPath("$.items[0].memberState").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].needsReview").value(false))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(1));
    }

    @Test
    void mapAnswers201AndAClientCannotSendTheAccessColumns() throws Exception {
        when(staff.map(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), any())).thenAnswer(BodyAnswers.readBody(3, mapping()));
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff").with(as("OWNER")).contentType(JSON)
                        .content("{\"membershipId\":\"" + MEMBER_ID + "\",\"storeRole\":\"STORE_USER\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(MAPPING_ID.toString()));
        var sent = BodyAnswers.captor();
        verify(staff).map(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), sent.capture());
        org.assertj.core.api.Assertions.assertThat(BodyAnswers.<PharmacyInputs.MapStaff>read(sent))
                .isEqualTo(new PharmacyInputs.MapStaff(MEMBER_ID, StoreRole.STORE_USER, null, null));

        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff").with(as("OWNER")).contentType(JSON)
                        .content("{\"membershipId\":\"" + MEMBER_ID + "\",\"storeRole\":\"STORE_USER\",\"access\":{\"approve\":true}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
    }

    @Test
    void thereIsNoPharmacistStoreRoleSoItIsRefusedAsAnUnknownValue() throws Exception {
        when(staff.map(any(), any(), anyString(), any())).thenAnswer(BodyAnswers.readBody(3, mapping()));
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff").with(as("OWNER")).contentType(JSON)
                        .content("{\"membershipId\":\"" + MEMBER_ID + "\",\"storeRole\":\"PHARMACIST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("storeRole"));
    }

    @Test
    void theMappingConflictsAreTheContractsTypes() throws Exception {
        for (String[] c : new String[][] {
                {"already-mapped", "That person is already in this store"},
                {"approver-exists", "This store already has an approver. Change it on the store."},
                {"store-inactive", "This store is switched off."}}) {
            doThrow(new ConflictException(c[0], "Conflict", c[1])).when(staff).map(any(), any(), anyString(), any());
            mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff").with(as("OWNER")).contentType(JSON)
                            .content("{\"membershipId\":\"" + MEMBER_ID + "\",\"storeRole\":\"STORE_USER\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/" + c[0]))
                    .andExpect(jsonPath("$.detail").value(c[1]));
        }
    }

    @Test
    void changeAndRemoveAnswer200() throws Exception {
        when(staff.change(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq(MAPPING_ID.toString()), any()))
                .thenAnswer(BodyAnswers.readBody(4, mapping()));
        when(staff.remove(TENANT_ID, USER_ID, STORE_ID.toString(), MAPPING_ID.toString())).thenReturn(mapping());

        mockMvc.perform(put(URL + "/stores/" + STORE_ID + "/staff/" + MAPPING_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":2,\"storeRole\":\"DELEGATE\",\"delegateFrom\":\"2026-11-17\",\"delegateTo\":\"2026-11-20\"}"))
                .andExpect(status().isOk());
        var sent = BodyAnswers.captor();
        verify(staff).change(eq(TENANT_ID), eq(USER_ID), eq(STORE_ID.toString()), eq(MAPPING_ID.toString()), sent.capture());
        org.assertj.core.api.Assertions.assertThat(BodyAnswers.<PharmacyInputs.ChangeMapping>read(sent)).isEqualTo(
                new PharmacyInputs.ChangeMapping(2L, StoreRole.DELEGATE, LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20)));
        mockMvc.perform(post(URL + "/stores/" + STORE_ID + "/staff/" + MAPPING_ID + "/remove").with(as("OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void aStaleMappingVersionCarriesTheCurrentMapping() throws Exception {
        when(staff.change(any(), any(), anyString(), anyString(), any())).thenThrow(PharmacyProblemException.settingsChanged(mapping()));
        mockMvc.perform(put(URL + "/stores/" + STORE_ID + "/staff/" + MAPPING_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"version\":0,\"storeRole\":\"STORE_USER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://sahaj.in/problems/settings-changed"))
                .andExpect(jsonPath("$.current.storeRole").value("APPROVER"));
    }

    @Test
    void candidatesPassTheRawStoreIdAndRoleToTheServiceWhichChecksThemAfterTheGate() throws Exception {
        when(staff.candidates(TENANT_ID, USER_ID, STORE_ID.toString(), (String) null)).thenReturn(new PharmacyViews.StaffCandidates(List.of(
                new PharmacyViews.StaffCandidate(MEMBER_ID, "Test Doctor One", "DOCTOR", "Doctor",
                        List.of(StoreRole.STORE_USER, StoreRole.APPROVER), false))));
        mockMvc.perform(get(URL + "/staff-candidates?storeId=" + STORE_ID).with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].allowedStoreRoles[1]").value("APPROVER"))
                .andExpect(jsonPath("$.items[0].alreadyMappedHere").value(false))
                .andExpect(jsonPath("$.items[0].mobile").doesNotExist());

        when(staff.candidates(TENANT_ID, USER_ID, STORE_ID.toString(), "CENTRAL_STORE_USER"))
                .thenReturn(new PharmacyViews.StaffCandidates(List.of()));
        mockMvc.perform(get(URL + "/staff-candidates?storeId=" + STORE_ID + "&storeRole=CENTRAL_STORE_USER").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

        when(staff.candidates(any(), any(), isNull(String.class), isNull(String.class))).thenReturn(new PharmacyViews.StaffCandidates(List.of()));
        mockMvc.perform(get(URL + "/staff-candidates").with(as("OWNER"))).andExpect(status().isOk());
        verify(staff).candidates(TENANT_ID, USER_ID, (String) null, (String) null);
    }

    @Test
    void candidatesWithoutAStoreIdAreAllowedAndAnEmptyOneIsPassedAsIs() throws Exception {
        when(staff.candidates(TENANT_ID, USER_ID, (String) null, "APPROVER")).thenReturn(new PharmacyViews.StaffCandidates(List.of(
                new PharmacyViews.StaffCandidate(MEMBER_ID, "Test Doctor One", "DOCTOR", "Doctor",
                        List.of(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER, StoreRole.APPROVER), false))));
        mockMvc.perform(get(URL + "/staff-candidates?storeRole=APPROVER").with(as("OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].alreadyMappedHere").value(false))
                .andExpect(jsonPath("$.items[0].allowedStoreRoles[1]").value("CENTRAL_STORE_USER"));

        when(staff.candidates(TENANT_ID, USER_ID, "", "DELEGATE")).thenReturn(new PharmacyViews.StaffCandidates(List.of()));
        mockMvc.perform(get(URL + "/staff-candidates?storeId=&storeRole=DELEGATE").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(staff).candidates(TENANT_ID, USER_ID, "", "DELEGATE");
    }
}
