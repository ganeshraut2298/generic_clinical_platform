package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** m-2: one order for locking batches, the same whichever service builds the list. */
class BatchLockOrderTest {

    private static final UUID LOW = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID HIGH = UUID.fromString("ffffffff-0000-0000-0000-000000000001");

    private static List<BatchLockOrder.Key> keys() {
        return List.of(BatchLockOrder.key(HIGH, "b1", LocalDate.of(2028, 1, 31)),
                BatchLockOrder.key(LOW, "AB1", LocalDate.of(2028, 1, 31)),
                BatchLockOrder.key(LOW, "AB", LocalDate.of(2028, 6, 30)),
                BatchLockOrder.key(LOW, "AB", LocalDate.of(2027, 6, 30)),
                BatchLockOrder.key(HIGH, "A1", LocalDate.of(2028, 1, 31)));
    }

    @Test
    void theProductIsComparedAsTextSoAnIdStartingWithFComesAfterOneStartingWithOne() {
        // UUID.compareTo is signed and would put the ffff id first
        assertThat(HIGH.compareTo(LOW)).isNegative();
        assertThat(BatchLockOrder.ORDER.compare(BatchLockOrder.key(HIGH, "A", LocalDate.MAX),
                BatchLockOrder.key(LOW, "A", LocalDate.MAX))).isPositive();
    }

    @Test
    void thenTheBatchKeyIgnoringCaseAndSpacesThenTheExpiry() {
        List<BatchLockOrder.Key> sorted = new ArrayList<>(keys());
        sorted.sort(BatchLockOrder.ORDER);

        assertThat(sorted).extracting(k -> k.productId().toString().substring(0, 2) + ":" + k.batchKey() + ":" + k.expiryDate())
                .containsExactly("10:AB:2027-06-30", "10:AB:2028-06-30", "10:AB1:2028-01-31", "ff:A1:2028-01-31",
                        "ff:B1:2028-01-31");
        assertThat(BatchLockOrder.key(LOW, "  ab1 ", LocalDate.MIN)).isEqualTo(BatchLockOrder.key(LOW, "AB1", LocalDate.MIN));
    }

    /** Two lists of the same batches, built in different orders by different services, sort the same. */
    @Test
    void twoListsOfTheSameBatchesSortTheSame() {
        List<BatchLockOrder.Key> first = new ArrayList<>(keys());
        List<BatchLockOrder.Key> second = new ArrayList<>(keys());
        Collections.shuffle(first, new Random(1));
        Collections.shuffle(second, new Random(99));

        first.sort(BatchLockOrder.ORDER);
        second.sort(BatchLockOrder.ORDER);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void theByHelperSortsRowsThatNameABatch() {
        record Row(UUID product, String batchNo, LocalDate expiry) {
        }
        List<Row> rows = new ArrayList<>(List.of(new Row(HIGH, "x", LocalDate.of(2028, 1, 1)),
                new Row(LOW, "y", LocalDate.of(2028, 1, 1)), new Row(LOW, " X ", LocalDate.of(2028, 1, 1))));

        rows.sort(BatchLockOrder.by(r -> BatchLockOrder.key(r.product(), r.batchNo(), r.expiry())));

        assertThat(rows).extracting(Row::batchNo).containsExactly(" X ", "y", "x");
    }
}
