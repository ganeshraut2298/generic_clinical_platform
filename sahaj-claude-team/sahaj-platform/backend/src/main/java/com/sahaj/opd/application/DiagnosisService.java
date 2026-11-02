package com.sahaj.opd.application;

import com.sahaj.opd.application.DiagnosisRepository.DiagnosisRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SAH-7 AC5: search of the global, read-only ICD-10 list. The same for every clinic; no clinic can change it. */
@Service
public class DiagnosisService {

    static final int LIMIT = 20;

    private final DiagnosisRepository repository;

    public DiagnosisService(DiagnosisRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DiagnosisRow> search(String query) {
        return repository.search(query, LIMIT);
    }
}
