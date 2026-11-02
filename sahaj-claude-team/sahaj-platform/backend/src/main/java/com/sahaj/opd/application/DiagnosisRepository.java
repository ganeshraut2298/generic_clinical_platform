package com.sahaj.opd.application;

import java.util.List;

/** Port over the global, read-only {@code opd.diagnosis} ICD-10 list (SAH-7). */
public interface DiagnosisRepository {

    /** Diagnoses whose name or code starts with {@code prefix} (blank means the first ones by name). */
    List<DiagnosisRow> search(String prefix, int limit);

    record DiagnosisRow(String code, String name, String specialty) {
    }
}
