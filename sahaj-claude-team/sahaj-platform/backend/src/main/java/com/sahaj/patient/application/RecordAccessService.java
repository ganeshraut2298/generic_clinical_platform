package com.sahaj.patient.application;

import com.sahaj.patient.application.RecordAccessRepository.AccessRow;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-17 "Who opened which record" (AC8). Owner only. The log holds ids and the time; the staff
 * and patient names are resolved here at read time. A person who no longer resolves gets a
 * placeholder rather than failing the list.
 */
@Service
public class RecordAccessService {

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 50;
    static final String UNKNOWN_STAFF = "A staff member";
    static final String UNKNOWN_PATIENT = "A patient";

    private final RecordAccessRepository accessRepository;
    private final PatientRepository patientRepository;
    private final ActorDirectoryPort actorDirectory;
    private final OwnerGuard ownerGuard;

    public RecordAccessService(RecordAccessRepository accessRepository, PatientRepository patientRepository,
                                ActorDirectoryPort actorDirectory, OwnerGuard ownerGuard) {
        this.accessRepository = accessRepository;
        this.patientRepository = patientRepository;
        this.actorDirectory = actorDirectory;
        this.ownerGuard = ownerGuard;
    }

    @Transactional(readOnly = true)
    public List<RecordAccessView> recent(UUID tenantId, UUID actorUserId, Integer limit) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        int safeLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (safeLimit < 1 || safeLimit > MAX_LIMIT) {
            throw new InvalidInputException("limit", "Choose a number from 1 to 50");
        }
        Map<UUID, String> userNames = new HashMap<>();
        Map<UUID, String> patientNames = new HashMap<>();
        return accessRepository.findRecent(tenantId, safeLimit).stream()
                .map(row -> toView(tenantId, row, userNames, patientNames))
                .toList();
    }

    private RecordAccessView toView(UUID tenantId, AccessRow row, Map<UUID, String> userNames,
                                     Map<UUID, String> patientNames) {
        String userName = userNames.computeIfAbsent(row.userId(),
                id -> actorDirectory.displayName(tenantId, id).orElse(UNKNOWN_STAFF));
        String patientName = patientNames.computeIfAbsent(row.patientId(),
                id -> patientRepository.findById(tenantId, id).map(PatientRow::name).orElse(UNKNOWN_PATIENT));
        return new RecordAccessView(row.at(), userName, row.patientId(), patientName);
    }

    /** One log line with names resolved. */
    public record RecordAccessView(Instant at, String userName, UUID patientId, String patientName) {
    }
}
