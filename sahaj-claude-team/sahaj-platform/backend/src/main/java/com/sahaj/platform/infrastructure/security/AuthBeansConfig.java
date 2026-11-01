package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.TokenService;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Wiring for SAH-3: the one-time code hash, a cryptographic random source, and the token service. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class AuthBeansConfig {

    /** Hashes the 6-digit code. Salted and slow on purpose: a stolen hash is hard to brute force back. */
    @Bean
    PasswordEncoder otpPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    TokenService tokenService(JwtProperties properties, Clock clock) {
        return new JwtTokenService(properties, clock);
    }
}
