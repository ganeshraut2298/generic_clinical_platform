package com.sahaj.pharmacy.infrastructure;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * ADR-029 P15 (SAH-79): the ledger has monthly partitions and no default partition, so a month without a partition makes
 * a posting fail. There is no job: at start-up this check reads the names of the partitions of {@code pharmacy.stock_ledger}
 * (the catalogue only, no row data) and logs one WARN when fewer than {@value #MIN_MONTHS_AHEAD} months remain from the
 * current IST month, the current month included. The warning holds the count and the last covered month, nothing else. It
 * never fails the start: a failed read logs one WARN with the exception class only. Switched off by
 * {@code sahaj.pharmacy.ledger-partition-check.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "sahaj.pharmacy.ledger-partition-check", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class LedgerPartitionCheck {

    /** Fewer months than this from today (the current month counts) is worth a warning. */
    static final int MIN_MONTHS_AHEAD = 6;

    private static final Logger LOG = LoggerFactory.getLogger(LedgerPartitionCheck.class);
    private static final String NAMES_SQL = """
            select c.relname
              from pg_catalog.pg_inherits i
              join pg_catalog.pg_class c on c.oid = i.inhrelid
             where i.inhparent = pg_catalog.to_regclass('pharmacy.stock_ledger')
            """;
    private static final Pattern PARTITION_NAME = Pattern.compile("^stock_ledger_y([0-9]{4})m([0-9]{2})$");
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private final Supplier<List<String>> partitionNames;
    private final Clock clock;

    @Autowired
    public LedgerPartitionCheck(JdbcClient jdbc, Clock clock) {
        this(() -> jdbc.sql(NAMES_SQL).query(String.class).list(), clock);
    }

    LedgerPartitionCheck(Supplier<List<String>> partitionNames, Clock clock) {
        this.partitionNames = partitionNames;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        check();
    }

    /** True when the warning was logged. */
    boolean check() {
        List<String> names;
        try {
            names = partitionNames.get();
        } catch (RuntimeException e) {
            LOG.warn("Could not check the monthly partitions of pharmacy.stock_ledger (cause: {})",
                    e.getClass().getSimpleName());
            return false;
        }
        YearMonth current = YearMonth.now(clock.withZone(IST));
        int ahead = 0;
        YearMonth last = null;
        for (String name : names == null ? List.<String>of() : names) {
            Optional<YearMonth> month = month(name);
            if (month.isEmpty()) {
                continue;
            }
            if (last == null || month.get().isAfter(last)) {
                last = month.get();
            }
            if (!month.get().isBefore(current)) {
                ahead++;
            }
        }
        if (ahead >= MIN_MONTHS_AHEAD) {
            return false;
        }
        LOG.warn("pharmacy.stock_ledger has {} monthly partitions from the current month, fewer than {} (last covered month: {}). "
                + "A forward migration must call pharmacy.create_ledger_partitions before the last month ends.",
                ahead, MIN_MONTHS_AHEAD, last == null ? "none" : last.toString());
        return true;
    }

    static Optional<YearMonth> month(String partitionName) {
        Matcher m = PARTITION_NAME.matcher(partitionName == null ? "" : partitionName);
        if (!m.matches()) {
            return Optional.empty();
        }
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        return Optional.of(YearMonth.of(Integer.parseInt(m.group(1)), month));
    }
}
