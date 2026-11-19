package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DocumentNumbers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** The clock of the module: calendar dates are Asia/Kolkata (IST) dates (docs/api/pharmacy.yaml rule 6). */
@Component
public class PharmacyTime {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Clock clock;

    public PharmacyTime(Clock clock) {
        this.clock = clock;
    }

    /** Today's date in IST. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), IST);
    }

    /** 00:00 IST of today (the start of the IST day, as an instant). */
    public Instant dayStart() {
        return today().atStartOfDay(IST).toInstant();
    }

    /** 1 April 00:00 IST of the current financial year (April to March). */
    public Instant financialYearStart() {
        LocalDate today = today();
        return LocalDate.of(DocumentNumbers.financialYearStart(today), 4, 1).atStartOfDay(IST).toInstant();
    }

    /** The current instant cut to microseconds (what PostgreSQL keeps). */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
