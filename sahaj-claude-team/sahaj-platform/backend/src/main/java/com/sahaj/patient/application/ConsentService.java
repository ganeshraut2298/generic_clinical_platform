package com.sahaj.patient.application;

import com.sahaj.patient.application.ConsentRepository.ConsentRow;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-17 patient consent (AC3, AC4). Every answer, including a withdrawal, is a new row: the
 * latest row per kind is the current state, and nothing is ever edited. The consent rows are
 * themselves the trail, so they are not copied into the audit log. Staff names are looked up at
 * read time through {@code platform.api}.
 */
@Service
public class ConsentService {

    static final String UNKNOWN_STAFF = "A staff member";

    private final PatientRepository patientRepository;
    private final ConsentRepository consentRepository;
    private final ActorDirectoryPort actorDirectory;

    public ConsentService(PatientRepository patientRepository, ConsentRepository consentRepository,
                           ActorDirectoryPort actorDirectory) {
        this.patientRepository = patientRepository;
        this.consentRepository = consentRepository;
        this.actorDirectory = actorDirectory;
    }

    @Transactional(readOnly = true)
    public ConsentStateView state(UUID tenantId, UUID patientId) {
        requirePatient(tenantId, patientId);
        return stateOf(tenantId, patientId);
    }

    @Transactional
    public ConsentStateView record(UUID tenantId, UUID actorUserId, UUID patientId, ConsentInput input) {
        if (input == null) {
            throw new InvalidInputException("kind", "Choose records or promotional messages");
        }
        input.validate();
        requirePatient(tenantId, patientId);
        consentRepository.insert(patientId, input.kind(), input.given(), actorUserId);
        return stateOf(tenantId, patientId);
    }

    private void requirePatient(UUID tenantId, UUID patientId) {
        patientRepository.findById(tenantId, patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    private ConsentStateView stateOf(UUID tenantId, UUID patientId) {
        List<ConsentRow> latest = consentRepository.findLatestPerKind(tenantId, patientId);
        return new ConsentStateView(entry(tenantId, latest, ConsentInput.RECORDS),
                entry(tenantId, latest, ConsentInput.PROMOTIONAL));
    }

    private ConsentEntryView entry(UUID tenantId, List<ConsentRow> latest, String kind) {
        return latest.stream().filter(row -> row.kind().equals(kind)).findFirst()
                .map(row -> new ConsentEntryView(row.kind(), row.given(), row.recordedAt(),
                        actorDirectory.displayName(tenantId, row.recordedBy()).orElse(UNKNOWN_STAFF)))
                .orElse(null);
    }

    /** Latest answer for one kind with the staff member's name. */
    public record ConsentEntryView(String kind, boolean given, Instant recordedAt, String recordedByName) {
    }

    /** Latest row per kind; null when never recorded. */
    public record ConsentStateView(ConsentEntryView records, ConsentEntryView promotional) {
    }
}
