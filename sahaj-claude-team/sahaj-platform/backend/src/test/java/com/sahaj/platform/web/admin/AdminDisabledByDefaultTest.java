package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.web.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Property {@code sahaj.admin.enabled} is absent (the default): no admin controller, advice or chain
 * exists, and {@code /api/v1/admin/**} is just an unauthenticated path of the clinic chain.
 */
@WebMvcTest(controllers = AdminAuthController.class)
@Import({SecurityConfig.class, AdminSecurityConfig.class, AdminUnavailableConfig.class})
class AdminDisabledByDefaultTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void noAdminBeansExist() {
        assertThat(context.getBeansOfType(AdminAuthController.class)).isEmpty();
        assertThat(context.getBeansOfType(AdminProblemExceptionHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1).containsKey("securityFilterChain");
    }

    @Test
    void adminPathsAreJustUnauthenticatedClinicPaths() throws Exception {
        mockMvc.perform(get("/api/v1/admin/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unauthorized"));
        mockMvc.perform(post("/api/v1/admin/auth/login")).andExpect(status().isUnauthorized());
    }
}
