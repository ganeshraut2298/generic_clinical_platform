package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** ADR-029 P15: the warning when fewer than six months of ledger partitions remain from today (IST). */
class LedgerPartitionCheckTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(LedgerPartitionCheck.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static Clock at(String utcInstant) {
        return Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
    }

    /** Partition names from year/month for the given number of months. */
    private static List<String> months(int year, int month, int count) {
        List<String> names = new ArrayList<>();
        java.time.YearMonth m = java.time.YearMonth.of(year, month);
        for (int i = 0; i < count; i++) {
            names.add(String.format("stock_ledger_y%04dm%02d", m.getYear(), m.getMonthValue()));
            m = m.plusMonths(1);
        }
        return names;
    }

    @Test
    void fewerThanSixMonthsFromTodayLogsOneWarnWithTheCountAndTheLastMonthOnly() {
        // November 2026 to March 2027 is five months, the current month included
        LedgerPartitionCheck check = new LedgerPartitionCheck(() -> months(2026, 11, 5), at("2026-11-19T10:00:00Z"));

        assertThat(check.check()).isTrue();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("5 monthly partitions").contains("fewer than 6")
                .contains("2027-03");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void sixMonthsFromTodayIsEnoughAndLogsNothing() {
        assertThat(new LedgerPartitionCheck(() -> months(2026, 11, 6), at("2026-11-19T10:00:00Z")).check()).isFalse();
        assertThat(new LedgerPartitionCheck(() -> months(2026, 11, 41), at("2026-11-19T10:00:00Z")).check()).isFalse();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void monthsInThePastDoNotCountAsRemaining() {
        // Nov 2026 to Mar 2030 exist, but in October 2029 only six remain (Oct 2029 to Mar 2030), in November five
        LedgerPartitionCheck october = new LedgerPartitionCheck(() -> months(2026, 11, 41), at("2029-10-15T10:00:00Z"));
        LedgerPartitionCheck november = new LedgerPartitionCheck(() -> months(2026, 11, 41), at("2029-11-15T10:00:00Z"));

        assertThat(october.check()).isFalse();
        assertThat(november.check()).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("5 monthly partitions").contains("2030-03");
    }

    @Test
    void theMonthIsTheIstMonthNotTheUtcMonth() {
        // 31 Oct 2026 20:00 UTC is 1 Nov 2026 01:30 IST: October partitions no longer count
        List<String> names = months(2026, 10, 6);
        assertThat(new LedgerPartitionCheck(() -> names, at("2026-10-31T20:00:00Z")).check()).isTrue();
        assertThat(new LedgerPartitionCheck(() -> names, at("2026-10-31T10:00:00Z")).check()).isFalse();
    }

    @Test
    void noPartitionsAtAllWarnsWithNone() {
        assertThat(new LedgerPartitionCheck(() -> List.<String>of(), at("2026-11-19T10:00:00Z")).check()).isTrue();
        assertThat(appender.list.get(0).getFormattedMessage()).contains("0 monthly partitions").contains("none");
    }

    @Test
    void namesThatAreNotLedgerPartitionsAreIgnored() {
        List<String> names = new ArrayList<>(months(2026, 11, 5));
        names.add("stock_ledger_default");
        names.add("stock_ledger_y2026m13");
        names.add("something_else");
        assertThat(new LedgerPartitionCheck(() -> names, at("2026-11-19T10:00:00Z")).check()).isTrue();
        assertThat(appender.list.get(0).getFormattedMessage()).contains("5 monthly partitions");
    }

    @Test
    void aFailedReadLogsTheExceptionClassOnlyAndNeverThrows() {
        LedgerPartitionCheck check = new LedgerPartitionCheck(() -> {
            throw new IllegalStateException("password=secret");
        }, at("2026-11-19T10:00:00Z"));

        assertThat(check.check()).isFalse();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("IllegalStateException")
                .doesNotContain("secret");
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }
}
