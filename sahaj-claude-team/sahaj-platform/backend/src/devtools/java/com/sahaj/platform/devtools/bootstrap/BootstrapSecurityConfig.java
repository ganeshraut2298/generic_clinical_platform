package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.infrastructure.security.RequestLogContextFilter;
import com.sahaj.platform.web.admin.AdminDevNotFound;
import com.sahaj.platform.web.admin.AdminNetworkFilter;
import com.sahaj.platform.web.admin.AdminRequestPaths;
import com.sahaj.platform.web.security.RequestBodySizeLimitFilter;
import java.util.Locale;
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
 * The bootstrap chain (ADR-018 D2 "Security chain"): matches everything under {@code /api/v1/admin/bootstrap}
 * (the decoded lookup path, any method, any letter case, so a request the gate lets through can never fall into the
 * admin chain), ahead of the admin chain (which is {@code @Order(1)}; the sample-admin chain is {@code @Order(0)} too and
 * matches a disjoint path, so the order between the two does not matter). Stateless, CSRF off (K5, K6 and the
 * {@code SameSite=Strict} binding cookie replace it), CORS off (no {@code Access-Control-*} header, so no browser can
 * read a response from another site), no form login, no basic, no security headers (so the 404 stays byte-identical),
 * everything permitted, and <b>no token filter</b>: a clinic token, admin token or any cookie other than the binding
 * cookie is never read (BR14).
 *
 * <p>Filters in order: request log context (request id), no-store headers, the admin network allow-list, the 4 KB body
 * cap (an anonymous caller). No access, request or body logging is added on this path.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminBootstrap
class BootstrapSecurityConfig {

    static RequestMatcher bootstrapMatcher() {
        return request -> {
            String path = AdminRequestPaths.lookupPath(request).toLowerCase(Locale.ROOT);
            return path.equals(BootstrapGate.BASE) || path.startsWith(BootstrapGate.BASE + "/");
        };
    }

    @Bean
    @Order(0)
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http, ProblemJsonWriter problemWriter,
                                                     AdminNetworkPolicy network) throws Exception {
        http
                .securityMatcher(bootstrapMatcher())
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
                .addFilterAfter(new BootstrapNoStoreFilter(), RequestLogContextFilter.class)
                .addFilterAfter(new AdminNetworkFilter(network, problemWriter), BootstrapNoStoreFilter.class)
                .addFilterAfter(new RequestBodySizeLimitFilter(problemWriter), AdminNetworkFilter.class);
        return http.build();
    }
}
