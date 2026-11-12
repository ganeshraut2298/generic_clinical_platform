package com.sahaj.opd.application;

import com.sahaj.opd.application.DiagnosisRepository.DiagnosisRow;
import com.sahaj.opd.application.emr.EmrQueryRules;
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
        return repository.search(query, null, LIMIT, 0);
    }

    /**
     * SAH-43-1: an optional specialty (hint first, nothing hidden) and a 0-based page of 20. A specialty that is not
     * one of the four template codes, or a page outside 0 to 500, is a 400 validation. Without both, the answer is
     * the SAH-7 answer.
     */
    @Transactional(readOnly = true)
    public List<DiagnosisRow> search(String query, String specialtyCode, Integer pageNumber) {
        String specialty = EmrQueryRules.specialty(specialtyCode);
        int page = EmrQueryRules.page(pageNumber);
        return repository.search(query, specialty, LIMIT, page * LIMIT);
    }
}
