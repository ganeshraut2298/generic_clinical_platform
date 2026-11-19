package com.sahaj.pharmacy.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one order in which a transaction locks batch rows (ADR-029 P3 step 6, review m-2): the product id as text (the unsigned
 * order PostgreSQL uses for a uuid; {@link UUID#compareTo} is signed and would put {@code ffff...} first), then the batch key
 * (trimmed, upper case), then the expiry date. Batches are unit-wide, so every posting that locks more than one batch (opening
 * stock, a goods receipt, a dispatch and later documents) sorts with this and two of them cannot wait for each other in
 * opposite directions.
 */
public final class BatchLockOrder {

    /** One batch as the lock order sees it. */
    public record Key(UUID productId, String batchKey, LocalDate expiryDate) {
    }

    public static final Comparator<Key> ORDER = Comparator.<Key, String>comparing(k -> k.productId().toString())
            .thenComparing(Key::batchKey).thenComparing(Key::expiryDate);

    private BatchLockOrder() {
    }

    /** The batch key as the database compares it: no surrounding spaces, no difference of case (OQ-79-37). */
    public static String batchKey(String batchNo) {
        return batchNo.trim().toUpperCase(Locale.ROOT);
    }

    public static Key key(UUID productId, String batchNo, LocalDate expiryDate) {
        return new Key(productId, batchKey(batchNo), expiryDate);
    }

    /** A comparator for any row that names a batch. */
    public static <T> Comparator<T> by(Function<T, Key> keyOf) {
        return Comparator.comparing(keyOf, ORDER);
    }
}
