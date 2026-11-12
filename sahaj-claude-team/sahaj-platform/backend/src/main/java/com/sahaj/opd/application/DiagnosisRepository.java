package com.sahaj.opd.application;

import java.util.List;

/** Port over the global, read-only {@code opd.diagnosis} ICD-10 list (SAH-7). */
public interface DiagnosisRepository {

    /** Diagnoses whose name or code starts with {@code prefix} (blank means the first ones by name). */
    default List<DiagnosisRow> search(String prefix, int limit) {
        return search(prefix, null, limit, 0);
    }

    /**
     * SAH-43-1: the same search with a page and an optional specialty. Diagnoses whose specialty hint equals
     * {@code specialty} come first; nothing is hidden. Without a specialty and with offset 0 the answer is exactly
     * the SAH-7 answer.
     */
    List<DiagnosisRow> search(String prefix, String specialty, int limit, int offset);

    record DiagnosisRow(String code, String name, String specialty) {
    }
}
