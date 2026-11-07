package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.ColleagueTriesHistoryView;
import com.sahaj.platform.application.ColleagueTriesNoticeView;
import com.sahaj.platform.application.ColleagueTriesService;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
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

/** SAH-38: the three colleague-tries endpoints, their shapes and problem types as in docs/api/platform.yaml. */
@WebMvcTest(controllers = ColleagueTriesController.class)
@Import(SecurityConfig.class)
class ColleagueTriesControllerTest {

    private static final String NOTICE = "/api/v1/platform/me/pin/colleague-tries";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.fromString("5e2f8a1b-9c3d-4e7f-8a6b-1c2d3e4f5a6b");
    private static final UUID BY_ID = UUID.fromString("7a1c2e3f-4b5d-4c6e-8f70-81a2b3c4d5e6");
    private static final String HISTORY = "/api/v1/platform/team/" + MEMBER_ID + "/pin/colleague-tries";
    private static final String[] ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
        "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ColleagueTriesService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void theNoticeHasEveryFiveFieldsAndKeepsTheMicrosecondsOfTheNewestTry() throws Exception {
        when(service.notice(TENANT_ID, USER_ID)).thenReturn(new ColleagueTriesNoticeView(3,
                Instant.parse("2026-11-07T05:35:12.123456Z"), PinState.SET, null, null));

        mockMvc.perform(get(NOTICE).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unseenCount").value(3))
                .andExpect(jsonPath("$.latestAt").value("2026-11-07T05:35:12.123456Z"))
                .andExpect(jsonPath("$.state").value("SET"))
                .andExpect(jsonPath("$.lockedUntil").value((Object) null))
                .andExpect(jsonPath("$.notSetReason").value((Object) null))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"lockedUntil\":null")));
    }

    @Test
    void aNoneNoticeKeepsEveryKeyWithNulls() throws Exception {
        when(service.notice(TENANT_ID, USER_ID)).thenReturn(new ColleagueTriesNoticeView(0, null,
                PinState.NOT_SET, null, "TOO_MANY_LOCKS"));

        mockMvc.perform(get(NOTICE).with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unseenCount").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"latestAt\":null")))
                .andExpect(jsonPath("$.state").value("NOT_SET"))
                .andExpect(jsonPath("$.notSetReason").value("TOO_MANY_LOCKS"));
    }

    @Test
    void everyClinicRoleMayReadAndDismissItsOwnNoticeButASignedOutVisitorGets401() throws Exception {
        when(service.notice(TENANT_ID, USER_ID)).thenReturn(new ColleagueTriesNoticeView(0, null, PinState.SET,
                null, null));
        for (String role : ROLES) {
            mockMvc.perform(get(NOTICE).with(as(role))).andExpect(status().isOk());
            mockMvc.perform(post(NOTICE + "/seen").with(as(role)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"seenThrough\":\"2026-11-07T05:35:12.123456Z\"}")).andExpect(status().isNoContent());
        }
        mockMvc.perform(get(NOTICE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(NOTICE + "/seen").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }

    @Test
    void theTenantAndThePersonComeFromTheSessionAndTheTimeFromTheBody() throws Exception {
        mockMvc.perform(post(NOTICE + "/seen").with(as("NURSE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenThrough\":\"2026-11-07T05:35:12.123456Z\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).markSeen(TENANT_ID, USER_ID, "2026-11-07T05:35:12.123456Z");
    }

    @Test
    void aBadTimeIs400ValidationOnSeenThrough() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidFieldsException(
                List.of(new FieldMessage("seenThrough", "Reload the page and try again."))))
                .when(service).markSeen(TENANT_ID, USER_ID, "nope");

        mockMvc.perform(post(NOTICE + "/seen").with(as("NURSE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenThrough\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("seenThrough"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Reload the page and try again."));
    }

    @Test
    void aMissingBodyIsNotAccepted() throws Exception {
        mockMvc.perform(post(NOTICE + "/seen").with(as("NURSE")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
        verify(service, never()).markSeen(any(), any(), any());
    }

    @Test
    void theHistoryHasEveryFieldOfEveryEntryAndNullsWhereNotApplicable() throws Exception {
        when(service.history(TENANT_ID, USER_ID, MEMBER_ID)).thenReturn(new ColleagueTriesHistoryView(MEMBER_ID,
                Instant.parse("2026-11-07T06:00:00Z"), 6, false, List.of(
                        new ColleagueTriesHistoryView.Entry("LOCKED", Instant.parse("2026-11-07T05:35:12Z"), null, null,
                                null),
                        new ColleagueTriesHistoryView.Entry("WRONG_TRIES", Instant.parse("2026-11-07T05:35:12Z"), BY_ID,
                                "Sunita Pawar", 5))));

        mockMvc.perform(get(HISTORY).with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.asOf").value("2026-11-07T06:00:00Z"))
                .andExpect(jsonPath("$.wrongTries7d").value(6))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.entries[0].kind").value("LOCKED"))
                .andExpect(jsonPath("$.entries[0].byMembershipId").value((Object) null))
                .andExpect(jsonPath("$.entries[0].byName").value((Object) null))
                .andExpect(jsonPath("$.entries[0].count").value((Object) null))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"byName\":null")))
                .andExpect(jsonPath("$.entries[1].kind").value("WRONG_TRIES"))
                .andExpect(jsonPath("$.entries[1].byMembershipId").value(BY_ID.toString()))
                .andExpect(jsonPath("$.entries[1].byName").value("Sunita Pawar"))
                .andExpect(jsonPath("$.entries[1].count").value(5));
    }

    @Test
    void aNonOwnerGetsTheContractsForbiddenTextFromTheService() throws Exception {
        when(service.history(TENANT_ID, USER_ID, MEMBER_ID)).thenThrow(
                new ForbiddenException("forbidden", "Not allowed", "Only the clinic owner can do this."));

        mockMvc.perform(get(HISTORY).with(as("NURSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"))
                .andExpect(jsonPath("$.detail").value("Only the clinic owner can do this."));
    }

    @Test
    void anUnknownMemberIs404AndANonUuidPathIs400() throws Exception {
        when(service.history(TENANT_ID, USER_ID, MEMBER_ID)).thenThrow(new NotFoundException("Team member not found"));

        mockMvc.perform(get(HISTORY).with(as("OWNER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/platform/team/not-an-id/pin/colleague-tries").with(as("OWNER")))
                .andExpect(status().is4xxClientError());
    }
}
