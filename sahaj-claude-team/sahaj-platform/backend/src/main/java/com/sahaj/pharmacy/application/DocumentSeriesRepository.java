package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DocType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port over {@code pharmacy.document_series} (one row per document type once saved) and a read of {@code pharmacy.document_counter}. */
public interface DocumentSeriesRepository {

    record Row(UUID id, DocType docType, String prefix, boolean financialYearPart, int sequenceLength,
               boolean restartsEachFinancialYear, long version) {
    }

    /** The saved series of the unit; a type with no row has not been saved yet (the service serves its defaults). */
    List<Row> list(UUID tenantId);

    Optional<Row> find(UUID tenantId, DocType docType);

    /** The same row, locked {@code FOR UPDATE} for the rest of the transaction (the series lock of DEC-078 item 4). */
    Optional<Row> findForUpdate(UUID tenantId, DocType docType);

    /**
     * True when a number of this type was issued at or after {@code since}: a counter row of the type whose
     * {@code coalesce(last_issued_at, updated_at)} is not before it (the start of the current financial year, 1 April 00:00 IST).
     */
    boolean issuedSince(UUID tenantId, DocType docType, Instant since);

    /**
     * The types of which a number was issued at or after {@code since} (the start of the current financial year): the same
     * check as {@link #issuedSince(UUID, DocType, Instant)}, for every type at once. It serves {@code numbersIssued}.
     */
    Set<DocType> issuedSince(UUID tenantId, Instant since);

    UUID insert(UUID tenantId, UUID unitId, DocType docType, String prefix, boolean financialYearPart, int sequenceLength,
                boolean restartsEachFinancialYear, UUID actorUserId);

    /** Saves the series when its version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, String prefix, boolean financialYearPart, int sequenceLength,
                   boolean restartsEachFinancialYear, UUID actorUserId);
}
