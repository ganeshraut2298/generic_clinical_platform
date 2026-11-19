package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79 WP-D: FEFO (P4), the money and margin rules (P2), and the stock capabilities (P8). Free of I/O. */
class StockRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Fefo.Source source(UUID id, LocalDate expiry, Instant received, int qty) {
        return new Fefo.Source(id, expiry, received, qty);
    }

    // ---- FEFO ----

    @Test
    void theEarliestExpiryIsPickedFirstThenTheEarliestReceivedThenTheBatchId() {
        UUID a = UUID.fromString("00000000-0000-4000-8000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-4000-8000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-4000-8000-00000000000c");
        UUID d = UUID.fromString("00000000-0000-4000-8000-00000000000d");
        List<Fefo.Source> rows = List.of(source(d, TODAY.plusDays(90), T0, 5), source(c, TODAY.plusDays(30), T0.plusSeconds(5), 5),
                source(b, TODAY.plusDays(30), T0, 5), source(a, TODAY.plusDays(30), T0, 5));

        assertThat(Fefo.order(rows, TODAY)).extracting(Fefo.Source::batchId).containsExactly(a, b, c, d);
    }

    @Test
    void aQuantityLargerThanTheFirstBatchIsSplitAndAnExpiredOrEmptyBatchIsNeverPicked() {
        UUID expired = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<Fefo.Source> rows = List.of(source(expired, TODAY.minusDays(1), T0, 50), source(empty, TODAY.plusDays(5), T0, 0),
                source(first, TODAY.plusDays(10), T0, 6), source(second, TODAY.plusDays(40), T0, 10));

        Fefo.Result result = Fefo.pick(rows, TODAY, 9);

        assertThat(result.picks()).containsExactly(new Fefo.Pick(first, 6), new Fefo.Pick(second, 3));
        assertThat(result.available()).isEqualTo(16);
        assertThat(result.shortBy()).isZero();
    }

    @Test
    void aBatchThatExpiresTodayIsStillUsableAndAShortfallIsReported() {
        UUID today = UUID.randomUUID();

        Fefo.Result result = Fefo.pick(List.of(source(today, TODAY, T0, 4)), TODAY, 10);

        assertThat(result.picks()).containsExactly(new Fefo.Pick(today, 4));
        assertThat(result.available()).isEqualTo(4);
        assertThat(result.shortBy()).isEqualTo(6);
        assertThat(Fefo.pick(List.of(), TODAY, 3).picks()).isEmpty();
    }

    // ---- money ----

    @Test
    void stockValueIsQuantityTimesTheRateRoundedHalfUpToThePaisa() {
        assertThat(StockMath.valuePaise(10, new BigDecimal("45.5000"))).isEqualTo(45_500L);
        assertThat(StockMath.valuePaise(3, new BigDecimal("0.1250"))).as("37.5 paise").isEqualTo(38L);
        assertThat(StockMath.valuePaise(1, new BigDecimal("0.0049"))).isZero();
        assertThat(StockMath.valuePaise(5, null)).as("no rate counts 0").isZero();
    }

    @Test
    void marginIsMrpMinusLandedOverMrpWithOneDecimalAndNullWithoutARate() {
        assertThat(StockMath.marginPercent(new BigDecimal("100.00"), new BigDecimal("87.5000"))).isEqualTo("12.5");
        assertThat(StockMath.marginPercent(new BigDecimal("50.00"), new BigDecimal("55.0000"))).as("a loss").isEqualTo("-10.0");
        assertThat(StockMath.marginPercent(new BigDecimal("50.00"), null)).isNull();
        assertThat(StockMath.marginPercent(BigDecimal.ZERO, BigDecimal.ONE)).isNull();
    }

    // ---- capabilities (ADR-029 P8) ----

    @Test
    void readStockIsAnyActiveMappingAndTheOwnerAndNothingForAPersonWhoCannotSignIn() {
        for (StoreRole role : StoreRole.values()) {
            assertThat(StoreRules.access("NURSE", role, false).readStock()).as(role.name()).isTrue();
        }
        assertThat(StoreRules.access("OWNER", StoreRole.APPROVER, false).readStock()).isTrue();
        assertThat(StoreAccess.NONE.readStock()).isFalse();
        assertThat(StoreAccess.NONE.has(StoreCapability.ADJUST_STOCK)).isFalse();
    }

    @Test
    void postingOpeningStockIsTheSameAsUploadingIt() {
        for (String team : List.of("OWNER", "PHARMACIST", "NURSE", "STORE_STAFF")) {
            for (StoreRole role : StoreRole.values()) {
                StoreAccess access = StoreRules.access(team, role, false);
                assertThat(access.has(StoreCapability.POST_OPENING_STOCK)).as(team + " " + role)
                        .isEqualTo(access.has(StoreCapability.UPLOAD_OPENING_STOCK));
            }
        }
    }

    @Test
    void adjustStockIsHodDelegatePharmacistMemberCentralStoreUserAndOwner() {
        assertThat(StoreRules.access("NURSE", StoreRole.APPROVER, false).adjustStock()).isTrue();
        assertThat(StoreRules.access("NURSE", StoreRole.DELEGATE, false).adjustStock()).isTrue();
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.CENTRAL_STORE_USER, false).adjustStock()).isTrue();
        assertThat(StoreRules.access("OWNER", StoreRole.APPROVER, false).adjustStock()).isTrue();
        assertThat(StoreRules.access("PHARMACIST", StoreRole.STORE_USER, false).adjustStock()).isTrue();
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.STORE_USER, false).adjustStock()).isFalse();
        assertThat(StoreRules.access("NURSE", StoreRole.STORE_USER, false).adjustStock()).isFalse();
    }
}
