package com.sahaj.platform.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.infrastructure.ratelimit.PublicEnquiryLimitProperties;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.sahaj.platform.infrastructure.security.CachedActiveMembershipChecker;
import com.sahaj.platform.infrastructure.security.JwtAuthenticationFilter;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.infrastructure.security.RequestLogContextFilter;
import com.sahaj.platform.web.Problems;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deny by default (SAH-1). Only {@code GET /api/v1/platform/health}, everything under
 * {@code /api/v1/platform/public/} and the error page are open; every other request needs a
 * logged-in user. Login arrives in SAH-3 and plugs its authentication filter into this chain; until
 * then nothing can authenticate in production, so every protected path answers 401.
 *
 * <p>The API is stateless and token based, so there is no session and no cookie-based CSRF surface
 * on these routes. Failures are written as RFC 9457 {@code application/problem+json}.
 *
 * <p>SAH-3 adds the login mechanism: {@code /auth/otp/request}, {@code /auth/otp/verify},
 * {@code /auth/select-clinic} and {@code /auth/refresh} are public (each validates its own
 * one-time code, selection token or refresh cookie); every other {@code /auth/**} route (logout,
 * logout-all) needs a valid access token, read by {@link JwtAuthenticationFilter}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(PublicEnquiryLimitProperties.class)
public class SecurityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProblemJsonWriter problemJsonWriter(ObjectMapper objectMapper) {
        return new ProblemJsonWriter(objectMapper);
    }

    /**
     * There is no username and password login in Sahaj, and the token login arrives in SAH-3. This
     * manager refuses everything, so Spring Boot does not create its default user with a generated
     * password. SAH-3 replaces it.
     */
    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new BadCredentialsException("Password login is not available");
        };
    }

    @Bean
    SlidingWindowRateLimiter enquiryRateLimiter(PublicEnquiryLimitProperties properties, Clock clock) {
        return new SlidingWindowRateLimiter(properties.maxRequests(), properties.window(),
                properties.maxTrackedKeys(), clock);
    }

    @Bean
    EnquiryRateLimitInterceptor enquiryRateLimitInterceptor(SlidingWindowRateLimiter enquiryRateLimiter,
                                                             ProblemJsonWriter problemWriter) {
        return new EnquiryRateLimitInterceptor(enquiryRateLimiter, problemWriter);
    }

    @Bean
    WebMvcConfigurer enquiryRateLimitWebMvcConfigurer(EnquiryRateLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(ObjectProvider<TokenService> tokenService,
                                                      ObjectProvider<CachedActiveMembershipChecker> activeMembershipChecker) {
        return new JwtAuthenticationFilter(tokenService, activeMembershipChecker);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemJsonWriter problemWriter,
                                             JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            problemWriter.write(response, Problems.unauthorized());
        };
        AccessDeniedHandler accessDeniedHandler = (request, response, ex) ->
                problemWriter.write(response, Problems.forbidden());

        http
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform/health").permitAll()
                        .requestMatchers("/api/v1/platform/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/otp/request").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/otp/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/select-clinic").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/refresh").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterAfter(jwtAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new RequestLogContextFilter(), JwtAuthenticationFilter.class)
                .addFilterAfter(new RequestBodySizeLimitFilter(problemWriter), RequestLogContextFilter.class);
        return http.build();
    }
}
