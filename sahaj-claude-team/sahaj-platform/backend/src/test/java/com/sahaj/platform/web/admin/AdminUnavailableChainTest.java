package com.sahaj.platform.web.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.web.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The admin portal is switched on but a secret is missing: the app still starts, admin routes answer
 * 503 admin-unavailable, and the clinic API is untouched.
 */
@WebMvcTest(controllers = AdminAuthController.class, properties = {"sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret="})
@Import({SecurityConfig.class, AdminUnavailableConfig.class})
class AdminUnavailableChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyAdminRouteAnswers503() throws Exception {
        mockMvc.perform(get("/api/v1/admin/me")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-unavailable"));
        mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"x\"}"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/v1/admin/auth/refresh")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void theClinicApiStillRequiresAClinicLogin() throws Exception {
        mockMvc.perform(get("/api/v1/platform/team")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unauthorized"));
    }
}
