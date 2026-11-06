package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.IpdLayout;
import com.sahaj.ipd.application.IpdService;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-22 ipd endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = IpdController.class)
@Import(SecurityConfig.class)
class IpdControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WARD_ID = UUID.randomUUID();
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID BED_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] NON_OWNERS = {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IpdService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static IpdLayout layout() {
        IpdLayout.Bed bed = new IpdLayout.Bed(BED_ID, "101-A", "gen", "free");
        IpdLayout.Room room = new IpdLayout.Room(ROOM_ID, "Room 101", List.of(bed));
        IpdLayout.Ward ward = new IpdLayout.Ward(WARD_ID, "General ward", "Nursing station 1", 1, List.of(room),
                List.of(bed));
        return new IpdLayout("on", true, 25, 1, List.of(ward),
                List.of(new IpdLayout.Category("gen", "General", 80000, new BigDecimal("1.00"), 1)),
                new IpdLayout.Charges("midnight", 30000, 50000));
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/layout")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleReadsTheLayoutInContractShape() throws Exception {
        when(service.layout(TENANT_ID)).thenReturn(layout());

        for (String role : ALL_ROLES) {
            mockMvc.perform(get(URL + "/layout").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("on"))
                    .andExpect(jsonPath("$.roomLevel").value(true))
                    .andExpect(jsonPath("$.bedLimit").value(25))
                    .andExpect(jsonPath("$.bedCount").value(1))
                    .andExpect(jsonPath("$.wards[0].station").value("Nursing station 1"))
                    .andExpect(jsonPath("$.wards[0].bedCount").value(1))
                    .andExpect(jsonPath("$.wards[0].rooms[0].beds[0].code").value("101-A"))
                    .andExpect(jsonPath("$.wards[0].beds[0].status").value("free"))
                    .andExpect(jsonPath("$.categories[0].name").value("General"))
                    .andExpect(jsonPath("$.categories[0].ratePaise").value(80000))
                    .andExpect(jsonPath("$.categories[0].multiplier").value(1.0))
                    .andExpect(jsonPath("$.charges.nursingPaise").value(30000));
        }
    }

    @Test
    void moduleOffAnswers403WithModuleOffType() throws Exception {
        when(service.layout(TENANT_ID)).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(get(URL + "/layout").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void readonlyWriteAnswers409WithIpdReadonlyType() throws Exception {
        when(service.addWard(any(), any(), any())).thenThrow(new ConflictException("ipd-readonly",
                "IPD is read-only", "IPD is read-only. Nothing can be changed."));

        mockMvc.perform(post(URL + "/wards").with(as("OWNER")).contentType(JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")))
                .andExpect(jsonPath("$.detail").value("IPD is read-only. Nothing can be changed."));
    }

    @Test
    void ownerAddsWardAndRoomWith201() throws Exception {
        when(service.addWard(TENANT_ID, USER_ID, "General ward")).thenReturn(layout());
        when(service.addRoom(TENANT_ID, USER_ID, WARD_ID)).thenReturn(layout());

        mockMvc.perform(post(URL + "/wards").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"General ward\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wards[0].name").value("General ward"));
        mockMvc.perform(post(URL + "/wards/" + WARD_ID + "/rooms").with(as("OWNER")))
                .andExpect(status().isCreated());
    }

    @Test
    void ownerAddsBedWithAndWithoutRoom() throws Exception {
        when(service.addBed(TENANT_ID, USER_ID, WARD_ID, ROOM_ID)).thenReturn(layout());
        when(service.addBed(TENANT_ID, USER_ID, WARD_ID, null)).thenReturn(layout());

        mockMvc.perform(post(URL + "/beds").with(as("OWNER")).contentType(JSON)
                        .content("{\"wardId\":\"" + WARD_ID + "\",\"roomId\":\"" + ROOM_ID + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(URL + "/beds").with(as("OWNER")).contentType(JSON)
                        .content("{\"wardId\":\"" + WARD_ID + "\",\"roomId\":null}"))
                .andExpect(status().isCreated());
    }

    @Test
    void bedLimitAnswers409WithBedLimitType() throws Exception {
        when(service.addBed(any(), any(), any(), any())).thenThrow(new ConflictException("bed-limit",
                "Bed limit reached", "Your plan allows 25 beds. Ask Sahaj to raise the limit."));

        mockMvc.perform(post(URL + "/beds").with(as("OWNER")).contentType(JSON)
                        .content("{\"wardId\":\"" + WARD_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bed-limit")))
                .andExpect(jsonPath("$.detail").value("Your plan allows 25 beds. Ask Sahaj to raise the limit."));
    }

    @Test
    void bedUpdateDeleteAndOccupiedConflict() throws Exception {
        when(service.changeBedCategory(TENANT_ID, USER_ID, BED_ID, "pvt")).thenReturn(layout());
        when(service.removeBed(TENANT_ID, USER_ID, BED_ID)).thenReturn(layout())
                .thenThrow(new ConflictException("bed-occupied", "Bed is occupied", "A patient is in this bed."));

        mockMvc.perform(put(URL + "/beds/" + BED_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"categoryKey\":\"pvt\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(URL + "/beds/" + BED_ID).with(as("OWNER"))).andExpect(status().isOk());
        mockMvc.perform(delete(URL + "/beds/" + BED_ID).with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bed-occupied")));
    }

    @Test
    void settingsEndpointsReturnTheLayout() throws Exception {
        when(service.setRoomLevel(TENANT_ID, USER_ID, false)).thenReturn(layout());
        when(service.updateCategory(TENANT_ID, USER_ID, "gen", 90000L, new BigDecimal("1.25"))).thenReturn(layout());
        when(service.updateCharges(TENANT_ID, USER_ID, "24h", 31000L, 52000L)).thenReturn(layout());

        mockMvc.perform(put(URL + "/room-level").with(as("OWNER")).contentType(JSON).content("{\"on\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(URL + "/categories/gen").with(as("OWNER")).contentType(JSON)
                        .content("{\"ratePaise\":90000,\"multiplier\":1.25}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(URL + "/charges").with(as("OWNER")).contentType(JSON)
                        .content("{\"bedDayCut\":\"24h\",\"nursingPaise\":31000,\"visitPaise\":52000}"))
                .andExpect(status().isOk());
    }

    @Test
    void validationIs400WithFieldErrorAndUnknownIdsAre404() throws Exception {
        when(service.updateCategory(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("multiplier", "Enter a rate from 0.5 to 5, with up to two decimals"))
                .thenThrow(new NotFoundException("Bed category not found"));

        mockMvc.perform(put(URL + "/categories/gen").with(as("OWNER")).contentType(JSON)
                        .content("{\"ratePaise\":90000,\"multiplier\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("multiplier"));
        mockMvc.perform(put(URL + "/categories/vip").with(as("OWNER")).contentType(JSON)
                        .content("{\"ratePaise\":90000,\"multiplier\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherRolesCannotChangeAnything() throws Exception {
        for (String role : NON_OWNERS) {
            mockMvc.perform(post(URL + "/wards").with(as(role)).contentType(JSON).content("{\"name\":\"A\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/wards/" + WARD_ID + "/rooms").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/beds").with(as(role)).contentType(JSON)
                            .content("{\"wardId\":\"" + WARD_ID + "\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/beds/" + BED_ID).with(as(role)).contentType(JSON)
                            .content("{\"categoryKey\":\"gen\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(URL + "/beds/" + BED_ID).with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/room-level").with(as(role)).contentType(JSON).content("{\"on\":true}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/categories/gen").with(as(role)).contentType(JSON)
                            .content("{\"ratePaise\":1000,\"multiplier\":1}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/charges").with(as(role)).contentType(JSON)
                            .content("{\"bedDayCut\":\"midnight\",\"nursingPaise\":1,\"visitPaise\":1}"))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).addWard(any(), any(), any());
        verify(service, never()).removeBed(any(), any(), any());
    }

    @Test
    void ownerGuardRefusalIs403() throws Exception {
        when(service.addWard(any(), any(), any())).thenThrow(new AccessDeniedException("no"));

        mockMvc.perform(post(URL + "/wards").with(as("OWNER")).contentType(JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isForbidden());
    }
}
