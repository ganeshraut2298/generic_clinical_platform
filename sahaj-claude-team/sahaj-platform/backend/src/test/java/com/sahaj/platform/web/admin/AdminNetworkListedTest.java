package com.sahaj.platform.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.web.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-003 D2.8 network allow-list (list "10.0.0.0/8", dev profile false). */
@WebMvcTest(controllers = AdminAuthController.class, properties = {"sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret=" + AdminChainTestConfig.ADMIN_SECRET,
        "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==",
        "test.admin.networks=10.0.0.0/8", "test.admin.dev=false"})
@Import({SecurityConfig.class, AdminSecurityConfig.class, AdminChainTestConfig.class})
class AdminNetworkListedTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AdminAuthService service;
    @MockBean
    private AdminAuthRepository repository;

    @Test
    void anAddressOutsideTheListIsDeniedOnEveryRouteIncludingTheSignInOnes() throws Exception {
        mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"x\"}").with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-network-denied"))
                .andExpect(jsonPath("$.title").value("Sign-in works only from approved networks."));
        mockMvc.perform(get("/api/v1/admin/me").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/auth/refresh").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isForbidden());
        verify(service, never()).login(any(), any(), any());
    }

    @Test
    void aForwardedHeaderCannotTalkItsWayIn() throws Exception {
        mockMvc.perform(get("/api/v1/admin/me").header("X-Forwarded-For", "10.1.1.1").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isForbidden());
    }

    @Test
    void anAddressInsideTheListGetsPastTheNetworkCheck() throws Exception {
        mockMvc.perform(get("/api/v1/admin/me").with(request -> {
            request.setRemoteAddr("10.4.5.6");
            return request;
        })).andExpect(status().isUnauthorized());
    }

    @Test
    void theClinicApiIsNotRestrictedByTheAdminList() throws Exception {
        mockMvc.perform(get("/api/v1/platform/team").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isUnauthorized());
    }
}
