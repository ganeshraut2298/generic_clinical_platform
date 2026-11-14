package com.sahaj.opd.infrastructure.partition;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** DEC-049 item 3: the threshold (under 3 future months) and the rule that a failed call never throws. */
class EmrPartitionUpkeepMonitorTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(EmrPartitionUpkeepMonitor.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void underThreeIsLowThreeOrMoreIsNot() {
        assertThat(EmrPartitionUpkeepMonitor.isLow(0)).isTrue();
        assertThat(EmrPartitionUpkeepMonitor.isLow(1)).isTrue();
        assertThat(EmrPartitionUpkeepMonitor.isLow(2)).isTrue();
        assertThat(EmrPartitionUpkeepMonitor.isLow(3)).isFalse();
        assertThat(EmrPartitionUpkeepMonitor.isLow(12)).isFalse();
    }

    @Test
    void aLowCountLogsOneWarnWithTheCountOnly() {
        assertThat(new EmrPartitionUpkeepMonitor(() -> 2).check()).isTrue();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("fewer than 3").contains("count: 2");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void aHealthyCountLogsNothing() {
        assertThat(new EmrPartitionUpkeepMonitor(() -> 12).check()).isFalse();
        assertThat(new EmrPartitionUpkeepMonitor(() -> 3).check()).isFalse();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void aFailedCallLogsOneWarnWithTheClassOnlyAndDoesNotThrow() {
        EmrPartitionUpkeepMonitor monitor = new EmrPartitionUpkeepMonitor(() -> {
            throw new IllegalStateException("ERROR: detail with value 9123450001");
        });

        assertThat(monitor.check()).isFalse();
        monitor.checkAtStartup();
        monitor.checkDaily();

        assertThat(appender.list).hasSize(3);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("IllegalStateException").doesNotContain("9123450001");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void aMissingCountIsTreatedAsAFailedCheck() {
        assertThat(new EmrPartitionUpkeepMonitor(() -> null).check()).isFalse();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void startupAndDailyEntryPointsBothRunTheCheck() {
        EmrPartitionUpkeepMonitor monitor = new EmrPartitionUpkeepMonitor(() -> 1);

        monitor.checkAtStartup();
        monitor.checkDaily();

        assertThat(appender.list).hasSize(2);
    }
}
