package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.SahajApplication;
import com.sahaj.platform.application.StaffPinHasher;
import com.sahaj.platform.infrastructure.security.JwtProperties;
import com.sahaj.platform.infrastructure.security.StaffPinConfig;
import com.sahaj.platform.support.LogCapture;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * SAH-31 ADR-010 D3 on start-up. A missing or blank pepper starts the application with one WARN line (no value) and the
 * hasher off; a pepper that is present but invalid (not Base64, under 32 bytes, equal to the access token secret) makes
 * the application refuse to start, and the message never contains the value. The real wiring ({@link StaffPinConfig}
 * with {@code @Value}) is started in a small context for every case; the whole application is started on the test
 * database for the cases that matter most. Test plan: docs/test-plans/SAH-31.md.
 */
class StaffPinStartupIT {

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static final String JWT = random(48);

    private ApplicationContextRunner runner(String pepper) {
        return new ApplicationContextRunner().withUserConfiguration(StaffPinConfig.class)
                .withBean(JwtProperties.class, () -> new JwtProperties(JWT, 15, 5))
                .withPropertyValues("sahaj.pin.pepper=" + pepper);
    }

    private static Throwable rootOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    @Test
    void aMissingOrBlankPepperStartsTheContextWithTheHasherOffAndOneWarnLineWithoutAValue() {
        for (String blank : new String[] {"", "   "}) {
            try (LogCapture logs = new LogCapture()) {
                runner(blank).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StaffPinHasher.class).isAvailable()).isFalse();
                });
                long warns = logs.events().stream().filter(e -> e.getLevel().toString().equals("WARN"))
                        .filter(e -> e.getFormattedMessage().equals(
                                "Staff PIN checks are off: SAHAJ_PIN_PEPPER is not set")).count();
                assertThat(warns).isOne();
            }
        }
    }

    @Test
    void aValidPepperStartsTheContextWithTheHasherOnAndNothingInTheLog() {
        String pepper = random(32);
        try (LogCapture logs = new LogCapture()) {
            runner(pepper).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(StaffPinHasher.class).isAvailable()).isTrue();
                assertThat(context.getBean(StaffPinHasher.class).toString()).doesNotContain(pepper);
            });
            assertThat(logs.allText()).doesNotContain(pepper).doesNotContain("Staff PIN checks are off");
        }
        // longer than 32 bytes is fine too
        runner(random(64)).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anInvalidPepperMakesTheContextRefuseToStartWithAMessageThatNeverHoldsTheValue() {
        String[][] cases = {{"this is !! not base64 ###", "not valid Base64"}, {random(16), "too short"},
                {JWT, "must not be the same as SAHAJ_JWT_SECRET"}};
        for (String[] c : cases) {
            runner(c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = rootOf(context.getStartupFailure());
                assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining(c[1])
                        .hasMessageContaining("SAHAJ_PIN_PEPPER");
                assertThat(root.getMessage()).doesNotContain(c[0]);
            });
        }
    }

    @Test
    void aPepperWhoseDecodedBytesAreTheTokenSecretTextIsAlsoRefused() {
        String secretText = "x".repeat(40);
        String pepper = Base64.getEncoder().encodeToString(secretText.getBytes(StandardCharsets.UTF_8));
        new ApplicationContextRunner().withUserConfiguration(StaffPinConfig.class)
                .withBean(JwtProperties.class, () -> new JwtProperties(secretText, 15, 5))
                .withPropertyValues("sahaj.pin.pepper=" + pepper).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theWholeApplicationRefusesToStartOnTheTestDatabaseWithAnInvalidPepper() {
        String[][] cases = {{"!!not-base64!!", "not valid Base64"}, {random(8), "too short"}};
        for (String[] c : cases) {
            assertThatThrownBy(() -> {
                try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SahajApplication.class)
                        .profiles("test").run("--server.port=0", "--sahaj.pin.pepper=" + c[0])) {
                    context.getId();
                }
            }).satisfies(e -> {
                Throwable root = rootOf(e);
                assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining(c[1])
                        .hasMessageContaining("SAHAJ_PIN_PEPPER");
                assertThat(String.valueOf(e.getMessage())).doesNotContain(c[0]);
            });
        }
    }

    @Test
    void theWholeApplicationRefusesToStartWhenThePepperEqualsTheTokenSecret() {
        String secret = random(48);
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SahajApplication.class)
                    .profiles("test").run("--server.port=0", "--sahaj.jwt.secret=" + secret,
                            "--sahaj.pin.pepper=" + secret)) {
                context.getId();
            }
        }).satisfies(e -> assertThat(rootOf(e)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be the same as SAHAJ_JWT_SECRET"));
    }
}
