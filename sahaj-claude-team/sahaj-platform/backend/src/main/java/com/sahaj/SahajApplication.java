package com.sahaj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Sahaj modular monolith.
 *
 * <p>The six module packages directly below this class ({@code com.sahaj.platform},
 * {@code com.sahaj.patient}, {@code com.sahaj.opd}, {@code com.sahaj.ipd}, {@code com.sahaj.hra},
 * {@code com.sahaj.billing}) are the Spring Modulith module boundaries (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1). Each module exposes
 * only its {@code api} sub-package to the others; everything else is internal.
 *
 * <p>Per ADR-001 decision D3, this story deliberately has no {@code DataSource} on the classpath,
 * so the context loads with no database connection attempt.
 */
@SpringBootApplication
public class SahajApplication {

    public static void main(String[] args) {
        SpringApplication.run(SahajApplication.class, args);
    }
}
