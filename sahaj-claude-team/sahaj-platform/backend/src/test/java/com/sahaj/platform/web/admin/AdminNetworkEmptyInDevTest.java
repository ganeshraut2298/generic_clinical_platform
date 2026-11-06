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

/** ADR-003 D2.8 network allow-list (list "", dev profile true). */
@WebMvcTest(controllers = AdminAuthController.class, properties = {"sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret=" + AdminChainTestConfig.ADMIN_SECRET,
        "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==",
        "test.admin.networks=", "test.admin.dev=true"})
@Import({SecurityConfig.class, AdminSecurityConfig.class, AdminChainTestConfig.class})
class AdminNetworkEmptyInDevTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AdminAuthService service;
    @MockBean
    private AdminAuthRepository repository;

    @Test
    void anEmptyListIsAllowedInDev() throws Exception {
        mockMvc.perform(get("/api/v1/admin/me")).andExpect(status().isUnauthorized());
    }
}
