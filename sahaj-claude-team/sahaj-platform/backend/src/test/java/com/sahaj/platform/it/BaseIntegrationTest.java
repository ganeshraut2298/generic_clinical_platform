package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Shared helpers for the integration tests that use MockMvc. */
abstract class BaseIntegrationTest {

    static final String PROBE = "/api/v1/platform/test-probe";
    static final String PROBLEM_JSON = "application/problem+json";
    static final String PROBLEM_BASE = "https://sahaj.in/problems/";

    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected Environment env;

    protected DbAccess db() {
        return new DbAccess(env);
    }

    /** Creates a tenant row (and its audit event) through the probe, as a user of that tenant. */
    protected UUID createTenant(UUID tenantId, UUID userId, String name) throws Exception {
        mvc.perform(post(PROBE + "/tenants")
                        .with(asUser(tenantId, userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + tenantId + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk());
        return tenantId;
    }

    /** A name that is unique per test run and carries no real data. */
    protected static String uniqueName(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    /**
     * A fresh connection address for MockMvc requests. The rate limiter keeps its counters for the
     * life of the Spring context, so each test uses its own address (documentation range TEST-NET-2 and 3).
     */
    protected static RequestPostProcessor fromNewClient() {
        int n = NEXT_CLIENT.getAndIncrement();
        String address = "198.51." + (100 + (n / 250)) + "." + (n % 250 + 1);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    protected static RequestPostProcessor fromClient(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
