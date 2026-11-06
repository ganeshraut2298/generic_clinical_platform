package com.sahaj.platform.web.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AdminDevPathFilter} with the servlet container, always (no condition), ahead of
 * the Spring Security filter chain (ADR-008 D3). The filter is created here and is not a bean itself,
 * so it is registered exactly once.
 */
@Configuration(proxyBeanMethods = false)
class AdminDevPathConfig {

    /** Spring Security's own filter sits at {@code SecurityProperties.DEFAULT_FILTER_ORDER}; lower runs first. */
    static final int ORDER = SecurityProperties.DEFAULT_FILTER_ORDER - 10;

    @Bean
    FilterRegistrationBean<AdminDevPathFilter> adminDevPathFilterRegistration(ObjectProvider<DevEndpointGate> gate) {
        FilterRegistrationBean<AdminDevPathFilter> registration = new FilterRegistrationBean<>(new AdminDevPathFilter(gate));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER);
        return registration;
    }
}
