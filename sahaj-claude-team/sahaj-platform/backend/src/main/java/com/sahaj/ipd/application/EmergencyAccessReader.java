package com.sahaj.ipd.application;

import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewRow;
import com.sahaj.ipd.application.WardScopeRepository.Grant;
import com.sahaj.ipd.application.WardScopeRepository.UnreviewedMark;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-34 (34F) reads one page of the owner's "Emergency access to review" with the full count. Shared by the Safety board
 * (the first page, in the board's transaction) and {@code GET /emergency-access} (the next pages). Staff and patient names
 * are read when the page is built, never stored; they are on screen only. Writes nothing.
 */
@Component
class EmergencyAccessReader {

    static final int PAGE = 25;
    static final String STAFF_FALLBACK = "Team member";
    static final String PATIENT_FALLBACK = "Patient";
    /** The text of the `bg` entry written when the owner reviews a grant (the trail adds the grantee at display time). */
    static final String REVIEWED_TEXT = "Reviewed emergency access";
    private static final String OPENED_PREFIX = "Emergency access opened · ";

    private final WardScopeRepository repository;
    private final ActorDirectoryPort actors;
    private final PatientLookupPort patients;

    EmergencyAccessReader(WardScopeRepository repository, ActorDirectoryPort actors, PatientLookupPort patients) {
        this.repository = repository;
        this.actors = actors;
        this.patients = patients;
    }

    /** The text of the `bg` entry written when a grant is opened: a fixed text and the reason label, no name. */
    static String openedText(EmergencyAccessReason reason) {
        return OPENED_PREFIX + reason.label();
    }

    /** The page after {@code after} (null for the first), at most {@code limit} rows, oldest first. */
    EmergencyAccessReviewPage page(UUID tenantId, Instant asOf, int limit, GrantCursor.Position after) {
        return page(tenantId, asOf, limit, after, null);
    }

    /**
     * As above with the count the caller already read in the same transaction ({@code mark}, null to read it here), so
     * the board does not count the waiting grants twice.
     */
    EmergencyAccessReviewPage page(UUID tenantId, Instant asOf, int limit, GrantCursor.Position after,
                                   UnreviewedMark mark) {
        int waiting = (mark == null ? repository.unreviewedMark(tenantId) : mark).waiting();
        List<Grant> found = repository.findUnreviewed(tenantId, after == null ? null : after.openedAt(),
                after == null ? null : after.id(), limit + 1);
        boolean more = found.size() > limit;
        List<Grant> page = more ? found.subList(0, limit) : found;
        Set<UUID> users = new HashSet<>();
        Set<UUID> patientIds = new HashSet<>();
        for (Grant grant : page) {
            users.add(grant.userId());
            patientIds.add(grant.patientId());
        }
        Map<UUID, String> staff = users.isEmpty() ? Map.of() : actors.displayNames(tenantId, users);
        Map<UUID, PatientSummary> names = patientIds.isEmpty() ? Map.of() : patients.findAll(tenantId, patientIds);
        List<EmergencyAccessReviewRow> rows = new ArrayList<>();
        for (Grant grant : page) {
            String staffName = staff == null ? null : staff.get(grant.userId());
            PatientSummary patient = names.get(grant.patientId());
            rows.add(new EmergencyAccessReviewRow(grant.id(), grant.admissionId(),
                    staffName == null ? STAFF_FALLBACK : staffName, patient == null ? PATIENT_FALLBACK : patient.name(),
                    grant.bedCode(), grant.reasonKey(), EmergencyAccessReason.labelOf(grant.reasonKey()),
                    grant.openedAt(), grant.expiresAt(), grant.admissionStatus()));
        }
        String next = more && !page.isEmpty()
                ? GrantCursor.encode(page.get(page.size() - 1).openedAt(), page.get(page.size() - 1).id()) : null;
        return new EmergencyAccessReviewPage(asOf, waiting, List.copyOf(rows), next);
    }

}
