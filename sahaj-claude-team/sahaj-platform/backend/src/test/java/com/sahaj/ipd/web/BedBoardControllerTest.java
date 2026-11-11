package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.BedBoardService;
import com.sahaj.ipd.application.BedBoardViews.BedBoard;
import com.sahaj.ipd.application.BedBoardViews.BoardBed;
import com.sahaj.ipd.application.BedBoardViews.BoardRoom;
import com.sahaj.ipd.application.BedBoardViews.BoardWard;
import com.sahaj.ipd.application.BedBoardViews.Counts;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/** SAH-24 board endpoints: contract shape, roles and problem types. */
@WebMvcTest(controllers = BedBoardController.class)
@Import(SecurityConfig.class)
class BedBoardControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BED_ID = UUID.randomUUID();
    private static final UUID WARD_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BedBoardService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static BedBoard board() {
        BoardBed bed = new BoardBed(BED_ID, "101-A", "gen", "General", 150_000L, "occ", UUID.randomUUID(),
                "Test Patient", 2, "Dr Test", true, false);
        BoardWard ward = new BoardWard(WARD_ID, "General ward", "Station 1", 1, 1, false, null,
                List.of(new BoardRoom(null, "", List.of(bed))));
        return new BedBoard("on", false, true, true, new Counts(0, 1, 0, 0, 0), List.of(ward));
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/board")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready")).andExpect(status().isUnauthorized());
    }

    @Test
    void boardIsReadByTheFourRolesAndMatchesTheContractShape() throws Exception {
        when(service.board(any(), any(), any())).thenReturn(board());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            mockMvc.perform(get(URL + "/board").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("on"))
                    .andExpect(jsonPath("$.roomLevel").value(false))
                    .andExpect(jsonPath("$.canAdmit").value(true))
                    .andExpect(jsonPath("$.canChangeBeds").value(true))
                    .andExpect(jsonPath("$.counts.occ").value(1))
                    .andExpect(jsonPath("$.wards[0].name").value("General ward"))
                    .andExpect(jsonPath("$.wards[0].hasFreeBed").value(false))
                    .andExpect(jsonPath("$.wards[0].rooms[0].id").doesNotExist())
                    .andExpect(jsonPath("$.wards[0].rooms[0].name").value(""))
                    .andExpect(jsonPath("$.wards[0].rooms[0].beds[0].patientName").value("Test Patient"))
                    .andExpect(jsonPath("$.wards[0].rooms[0].beds[0].dayNumber").value(2))
                    .andExpect(jsonPath("$.wards[0].rooms[0].beds[0].doctorShortName").value("Dr Test"))
                    .andExpect(jsonPath("$.wards[0].rooms[0].beds[0].depositLow").value(true));
        }
    }

    @Test
    void otherRolesAreForbidden() throws Exception {
        mockMvc.perform(get(URL + "/board").with(as("PHARMACIST"))).andExpect(status().isForbidden());
        verify(service, never()).board(any(), any(), any());
    }

    @Test
    void moduleOffIs403() throws Exception {
        when(service.board(any(), any(), any())).thenThrow(new ModuleOffException("off"));

        mockMvc.perform(get(URL + "/board").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void readyAndBlockAreForOwnerFrontDeskNurseOnly() throws Exception {
        when(service.markReady(any(), any(), any(), any())).thenReturn(board());
        when(service.block(any(), any(), any(), any())).thenReturn(board());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.wards[0].id").value(WARD_ID.toString()));
            mockMvc.perform(post(URL + "/beds/" + BED_ID + "/block").with(as(role)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as("DOCTOR"))).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/block").with(as("DOCTOR"))).andExpect(status().isForbidden());
    }

    @Test
    void usesTheSignedInClinicAndRole() throws Exception {
        when(service.markReady(TENANT_ID, USER_ID, "NURSE", BED_ID)).thenReturn(board());

        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as("NURSE"))).andExpect(status().isOk());
        verify(service).markReady(TENANT_ID, USER_ID, "NURSE", BED_ID);
    }

    @Test
    void problemsMapToTheirStatusAndType() throws Exception {
        when(service.markReady(any(), any(), any(), any()))
                .thenThrow(new ConflictException("bed-state", "Bed cannot be changed",
                        "This bed cannot be changed from Occupied."))
                .thenThrow(new NotFoundException("Bed not found"))
                .thenThrow(new ConflictException("ipd-readonly", "IPD is read-only",
                        "IPD is read-only. Nothing can be changed."));

        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bed-state")))
                .andExpect(jsonPath("$.detail").value("This bed cannot be changed from Occupied."));
        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as("OWNER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/beds/" + BED_ID + "/ready").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
    }
}
