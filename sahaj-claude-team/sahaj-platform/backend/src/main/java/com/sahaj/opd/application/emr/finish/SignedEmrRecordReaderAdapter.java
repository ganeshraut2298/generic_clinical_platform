package com.sahaj.opd.application.emr.finish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.api.SignedEmrRecordReader;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEC-040 extension point (ADR-025 D16): reads a SIGNED record's stored sections, with the codes the lists already hold
 * (ICD-10 on diagnoses, master keys), for the later FHIR bundle. No caller yet. Clinical data: never logged.
 */
@Component
class SignedEmrRecordReaderAdapter implements SignedEmrRecordReader {

    private final EmrRecordRepository records;
    private final EmrFinishRepository finish;
    private final ObjectMapper mapper;

    SignedEmrRecordReaderAdapter(EmrRecordRepository records, EmrFinishRepository finish, ObjectMapper mapper) {
        this.records = records;
        this.finish = finish;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SignedRecord> read(UUID tenantId, UUID recordId) {
        Optional<RecordRow> found = records.findById(tenantId, recordId).filter(r -> "SIGNED".equals(r.status()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordRow row = found.get();
        UUID signedBy = finish.state(tenantId, recordId).map(EmrFinishRepository.RecordState::signedByMembershipId)
                .orElse(null);
        try {
            JsonNode sections = mapper.readTree(row.sections());
            return Optional.of(new SignedRecord(row.id(), row.encounterType(), row.encounterId(), row.patientId(),
                    row.departmentKey(), row.signedAt(), signedBy, sections));
        } catch (IOException e) {
            throw new IllegalStateException("The signed record could not be read", e);
        }
    }
}
