package com.sahaj.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-31: the state, tries left and 24-hour window are read from the database clock carried by the row. */
class StaffPinRowTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");
    private static final String HASH = "$2a$12$" + "a".repeat(53);

    private static StaffPinRow row(String hash, int failed, Instant lockedUntil, int lockCount, Instant window) {
        return new StaffPinRow(UUID.randomUUID(), hash, 1, failed, lockedUntil, lockCount, window,
                hash == null ? null : NOW.minusSeconds(100), null, null, 3L, NOW);
    }

    @Test
    void noHashIsNotSetWithNoTriesLeft() {
        StaffPinRow row = row(null, 0, null, 0, null);
        assertThat(row.state()).isEqualTo(PinState.NOT_SET);
        assertThat(row.hasPin()).isFalse();
        assertThat(row.triesLeft()).isNull();
    }

    @Test
    void aHashWithNoLockIsSetAndTriesLeftIsFiveMinusFailed() {
        assertThat(row(HASH, 0, null, 0, null).triesLeft()).isEqualTo(5);
        assertThat(row(HASH, 3, null, 0, null).triesLeft()).isEqualTo(2);
        assertThat(row(HASH, 4, null, 0, null).state()).isEqualTo(PinState.SET);
    }

    @Test
    void lockedMeansLockedUntilIsAfterTheDatabaseNow() {
        assertThat(row(HASH, 0, NOW.plusSeconds(1), 1, NOW).state()).isEqualTo(PinState.LOCKED);
        assertThat(row(HASH, 0, NOW.plusSeconds(1), 1, NOW).triesLeft()).isZero();
        assertThat(row(HASH, 0, NOW, 1, NOW).state()).as("the lock ends at the moment").isEqualTo(PinState.SET);
        assertThat(row(HASH, 0, NOW.minusSeconds(1), 1, NOW).state()).isEqualTo(PinState.SET);
    }

    @Test
    void theWindowIsNewWhenThereWereNoLocksOrTheWindowIs24HoursOld() {
        assertThat(row(HASH, 4, null, 0, null).lockWindowIsNew()).isTrue();
        assertThat(row(HASH, 4, null, 1, NOW.minusSeconds(24 * 3600)).lockWindowIsNew()).isTrue();
        assertThat(row(HASH, 4, null, 2, NOW.minusSeconds(24 * 3600 - 1)).lockWindowIsNew()).isFalse();
        assertThat(row(HASH, 4, null, 1, NOW.minusSeconds(60)).lockWindowIsNew()).isFalse();
    }

    @Test
    void theSah31ShapeHasNeitherColleagueFlag() {
        StaffPinRow row = row(HASH, 2, null, 0, null);

        assertThat(row.runHasColleagueTry()).isFalse();
        assertThat(row.lockByColleagues()).isFalse();
    }

    @Test
    void theColleagueFlagsAreCarriedAndPrintedButTheHashNever() {
        StaffPinRow row = new StaffPinRow(UUID.randomUUID(), HASH, 1, 2, null, 0, null, NOW.minusSeconds(100), null,
                null, true, true, 3L, NOW);

        assertThat(row.runHasColleagueTry()).isTrue();
        assertThat(row.lockByColleagues()).isTrue();
        assertThat(row.toString()).contains("runHasColleagueTry=true", "lockByColleagues=true")
                .doesNotContain("$2a$").doesNotContain(HASH);
    }

    @Test
    void toStringNeverShowsTheHash() {
        assertThat(row(HASH, 2, null, 0, null).toString()).doesNotContain("$2a$").doesNotContain(HASH);
    }
}
