package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.application.StaffPinHasher;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** SAH-31, ADR-010 D3: missing pepper starts with one WARN; an invalid pepper refuses to start. */
class StaffPinConfigTest {

    private static final String JWT_SECRET = "a-jwt-secret-that-is-at-least-32-characters-long";
    private final JwtProperties jwt = new JwtProperties(JWT_SECRET, 15, 5);
    private final StaffPinConfig config = new StaffPinConfig();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(StaffPinConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static String randomPepper() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    @Test
    void aMissingPepperStartsAndLogsOneWarnWithoutAnyValue() {
        StaffPinHasher hasher = config.staffPinHasher("", jwt);

        assertThat(hasher.isAvailable()).isFalse();
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Staff PIN checks are off: SAHAJ_PIN_PEPPER is not set");
        });
    }

    @Test
    void aValidPepperIsQuietAndAvailable() {
        assertThat(config.staffPinHasher(randomPepper(), jwt).isAvailable()).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void anInvalidPepperStopsTheStart() {
        assertThatThrownBy(() -> config.staffPinHasher("not base64!!", jwt)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.staffPinHasher(randomPepper().substring(0, 20), jwt))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.staffPinHasher(JWT_SECRET, jwt)).isInstanceOf(IllegalStateException.class);
    }
}
