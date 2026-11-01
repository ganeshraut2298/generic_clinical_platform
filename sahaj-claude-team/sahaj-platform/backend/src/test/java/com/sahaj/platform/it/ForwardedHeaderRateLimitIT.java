package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Configuration risk: when {@code SAHAJ_REMOTE_IP_HEADER} is not set (the default), a client must not
 * be able to get a new rate-limit allowance by forging {@code X-Forwarded-For} (or its relatives).
 * Real embedded server, real HTTP client, sahaj_test. If an operator sets SAHAJ_REMOTE_IP_HEADER in
 * the environment that runs this test, the first assertion fails on purpose: the check is only valid
 * for the default configuration. Own Spring context, so the counter starts at zero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "it.context=forwarded-header")
@ActiveProfiles("test")
class ForwardedHeaderRateLimitIT {

    private static final String MOBILE = "9456789012";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment env;

    @Test
    void withNoRemoteIpHeaderConfiguredAForgedXForwardedForDoesNotChangeTheRateLimitKey() throws Exception {
        assertThat(env.getProperty("server.tomcat.remoteip.remote-ip-header"))
                .as("SAHAJ_REMOTE_IP_HEADER must be unset for this check").isNullOrEmpty();
        assertThat(env.getProperty("server.tomcat.remoteip.internal-proxies"))
                .as("SAHAJ_TRUSTED_PROXIES must be unset for this check").isNullOrEmpty();
        assertThat(env.getProperty("server.forward-headers-strategy")).as("forwarded headers strategy").isNullOrEmpty();

        RealHttpSupport http = new RealHttpSupport(port);
        DbAccess db = new DbAccess(env);
        String name = "IT forged header " + UUID.randomUUID();

        // Five allowed requests, each claiming a different client address.
        for (int i = 1; i <= 5; i++) {
            HttpResponse<String> ok = http.post(RealHttpSupport.body(name, MOBILE),
                    "X-Forwarded-For", "203.0.113." + i);
            assertThat(ok.statusCode()).as("request " + i).isEqualTo(201);
        }
        // From now on a fresh forged address must not help; try every common forwarding header.
        for (int i = 10; i < 30; i++) {
            HttpResponse<String> refused = http.post(RealHttpSupport.body(name, MOBILE),
                    "X-Forwarded-For", "203.0.113." + i + ", 10.0.0." + i,
                    "Forwarded", "for=203.0.113." + i,
                    "X-Real-IP", "203.0.113." + i,
                    "X-Client-IP", "203.0.113." + i,
                    "X-Forwarded-Host", "attacker.example",
                    "X-Forwarded-Proto", "https");
            assertThat(refused.statusCode()).as("forged address " + i).isEqualTo(429);
        }
        assertThat(http.post(RealHttpSupport.body(name, MOBILE)).statusCode()).as("no header at all").isEqualTo(429);
        // An IPv6 looking value is just as useless.
        assertThat(http.post(RealHttpSupport.body(name, MOBILE), "X-Forwarded-For", "2001:db8::1").statusCode()).isEqualTo(429);

        long saved = db.asMigrator(c -> DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ?", name));
        assertThat(saved).as("rows saved").isEqualTo(5);
    }
}
