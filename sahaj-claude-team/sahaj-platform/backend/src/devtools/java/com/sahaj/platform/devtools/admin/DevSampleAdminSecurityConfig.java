package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.infrastructure.security.RequestLogContextFilter;
import com.sahaj.platform.web.admin.AdminDevNotFound;
import com.sahaj.platform.web.admin.AdminNetworkFilter;
import com.sahaj.platform.web.admin.AdminRequestPaths;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The dev-only chain (ADR-008 D4): {@code @Order(0)}, ahead of the admin chain, matching exactly
 * {@code POST /api/v1/admin/dev/sample-admin}. Stateless, CSRF off (no cookie is read or set), CORS off
 * (no {@code Access-Control-*} header, so no browser can read a response), no form login, no basic, no
 * security headers (so the 404 stays byte-identical to the one written by main code), everything
 * permitted, and no token filter: a bearer token or cookie sent with the request is never parsed.
 * Filters in order: request log context, the admin network allow-list, the dev rate limit.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDevSampleAdmin
class DevSampleAdminSecurityConfig {

    static final int MAX_CALLS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    static RequestMatcher endpointMatcher() {
        return request -> "POST".equals(request.getMethod())
                && DevSampleAdminGate.PATH.equals(AdminRequestPaths.lookupPath(request));
    }

    @Bean
    @Order(0)
    SecurityFilterChain devSampleAdminSecurityFilterChain(HttpSecurity http, ProblemJsonWriter problemWriter,
                                                          AdminNetworkPolicy network, Clock clock) throws Exception {
        // Not a bean, so it does not compete with the enquiry limiter bean.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(MAX_CALLS, WINDOW, 10, clock);
        http
                .securityMatcher(endpointMatcher())
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) -> AdminDevNotFound.write(response))
                        .accessDeniedHandler((request, response, ex) -> AdminDevNotFound.write(response)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new RequestLogContextFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new AdminNetworkFilter(network, problemWriter), RequestLogContextFilter.class)
                .addFilterAfter(new DevSampleAdminRateLimitFilter(limiter, problemWriter), AdminNetworkFilter.class);
        return http.build();
    }
}
