package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.infrastructure.admin.AdminRateLimits;
import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.infrastructure.security.RequestLogContextFilter;
import com.sahaj.platform.web.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * The second security chain (ADR-003 D8), for {@code /api/v1/admin/**} only and ahead of the clinic
 * chain. Stateless, no CSRF (bearer token plus a {@code SameSite=Strict} cookie scoped to the auth
 * path), and in this order: request log context, network allow-list, per-address rate limits, the
 * admin JWT filter. Four sign-in endpoints are public; everything else needs role PLATFORM_ADMIN.
 * Clinic roles never satisfy it ({@code ROLE_OWNER} is not {@code ROLE_PLATFORM_ADMIN}).
 *
 * <p>The filters are created here and are not beans, so Spring Boot does not also register them
 * with the servlet container.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminRealm
class AdminSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, ProblemJsonWriter problemWriter,
                                                 AdminTokenService tokens, AdminAuthRepository repository,
                                                 AdminNetworkPolicy network, AdminRateLimits limits)
            throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            boolean sessionEnded = request.getAttribute(AdminJwtAuthenticationFilter.SESSION_ENDED_ATTRIBUTE) != null;
            problemWriter.write(response, sessionEnded ? AdminProblems.sessionEnded() : Problems.unauthorized());
        };
        AccessDeniedHandler accessDeniedHandler = (request, response, ex) ->
                problemWriter.write(response, Problems.forbidden());

        http
                .securityMatcher(AdminRequestPaths.BASE + "/**")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AdminRequestPaths.LOGIN).permitAll()
                        .requestMatchers(HttpMethod.POST, AdminRequestPaths.ENROL).permitAll()
                        .requestMatchers(HttpMethod.POST, AdminRequestPaths.VERIFY).permitAll()
                        .requestMatchers(HttpMethod.POST, AdminRequestPaths.REFRESH).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().hasRole("PLATFORM_ADMIN"))
                .addFilterBefore(new RequestLogContextFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new AdminNetworkFilter(network, problemWriter), RequestLogContextFilter.class)
                .addFilterAfter(new AdminRateLimitFilter(limits, problemWriter), AdminNetworkFilter.class)
                .addFilterAfter(new AdminJwtAuthenticationFilter(tokens, repository, problemWriter),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
