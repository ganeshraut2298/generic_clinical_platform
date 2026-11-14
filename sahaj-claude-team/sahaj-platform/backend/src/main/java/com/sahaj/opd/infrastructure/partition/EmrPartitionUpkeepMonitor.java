package com.sahaj.opd.infrastructure.partition;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DEC-049 item 3 (SAH-53, "Partition upkeep"): warns when {@code opd.emr_record} has fewer than
 * {@value #MIN_FUTURE_MONTHS} monthly partitions after the current month. The Flyway callback
 * {@code afterMigrate__opd_emr_record_partition_upkeep.sql} adds the months on every start; this check covers an
 * application that stays up for many months without a restart. It reads one integer through
 * {@code opd.emr_record_future_partition_count()} (no table, no row data), once when the application is ready and once
 * a day. It never fails the start and never throws out of the scheduler: a failed call logs one WARN with the exception
 * class only (the message of a database error can quote a value). Switched off by
 * {@code sahaj.opd.partition-check.enabled=false}, which tests without a database set.
 */
@Component
@ConditionalOnProperty(prefix = "sahaj.opd.partition-check", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EmrPartitionUpkeepMonitor {

    /** Fewer future months than this is worth a warning. */
    static final int MIN_FUTURE_MONTHS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(EmrPartitionUpkeepMonitor.class);
    private static final String COUNT_SQL = "select opd.emr_record_future_partition_count()";

    private final Supplier<Integer> futureMonths;

    @Autowired
    public EmrPartitionUpkeepMonitor(JdbcClient jdbc) {
        this(() -> jdbc.sql(COUNT_SQL).query(Integer.class).single());
    }

    EmrPartitionUpkeepMonitor(Supplier<Integer> futureMonths) {
        this.futureMonths = futureMonths;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        check();
    }

    @Scheduled(cron = "${sahaj.opd.partition-check.cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void checkDaily() {
        check();
    }

    /** True when the count was read and is under the threshold (the warning was logged). */
    boolean check() {
        Integer count;
        try {
            count = futureMonths.get();
        } catch (RuntimeException e) {
            LOG.warn("Could not check the future monthly partitions of opd.emr_record (cause: {})",
                    e.getClass().getSimpleName());
            return false;
        }
        if (count == null) {
            LOG.warn("Could not check the future monthly partitions of opd.emr_record (cause: no count returned)");
            return false;
        }
        if (isLow(count)) {
            LOG.warn("opd.emr_record has fewer than {} future monthly partitions (count: {}). Deploy or restart the "
                    + "application so the partition upkeep can add months.", MIN_FUTURE_MONTHS, count);
            return true;
        }
        return false;
    }

    static boolean isLow(int count) {
        return count < MIN_FUTURE_MONTHS;
    }
}
