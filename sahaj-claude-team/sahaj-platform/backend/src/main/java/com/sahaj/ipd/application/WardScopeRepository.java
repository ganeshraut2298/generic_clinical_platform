package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-34 (ADR-017): the stored side of ward scoping and break-glass emergency access. {@code ipd.ward_assignment},
 * {@code ipd.emergency_access_grant}, the switch {@code ipd.config.nurse_ward_scope} and the placement of admitted
 * patients. Every method is scoped to the tenant (and row-level security). The database owns the times of a grant and of
 * an ended assignment: no statement sends them. Liveness of a grant is always {@code expires_at > clock_timestamp()}
 * evaluated in SQL, never against an application clock. Ids only; no name is stored or read here.
 */
public interface WardScopeRepository {

    /** The switch "Nurses see only their wards"; false when the clinic has no {@code ipd.config} row. */
    boolean nurseWardScope(UUID tenantId);

    /** Creates the lazy config row when needed and writes the switch only (other columns keep their values). */
    void upsertNurseWardScope(UUID tenantId, boolean on, UUID actorUserId, Instant now);

    /** The live wards of one membership (index only). */
    Set<UUID> liveWardIds(UUID tenantId, UUID membershipId);

    /**
     * The live grants of a membership and role on ADMITTED admissions: admission id to {@code expires_at}. Live is
     * {@code expires_at > clock_timestamp()} at the time of the statement.
     */
    Map<UUID, Instant> liveGrants(UUID tenantId, UUID membershipId, String role);

    /** True when the membership holds a live grant on this one ADMITTED admission now (re-run under the lock). */
    boolean hasLiveGrant(UUID tenantId, UUID membershipId, String role, UUID admissionId);

    /** The placement facts of one admission (any status), or empty when missing or another clinic's. */
    Optional<Facts> findFacts(UUID tenantId, UUID admissionId);

    /** The placement facts of every ADMITTED admission of the clinic. Unordered. */
    List<Facts> findAdmittedFacts(UUID tenantId);

    /** The database clock now ({@code clock_timestamp()}). */
    Instant databaseNow();

    // ---- ward assignments (34A data part) ----

    /** Every live assignment of the clinic. */
    List<Assignment> liveAssignments(UUID tenantId);

    /** Inserts one live assignment (the database stamps the time). */
    void insertAssignment(UUID tenantId, UUID wardId, UUID membershipId, UUID actorUserId);

    /** Ends the live assignment (the database stamps the time); true when one row was ended. */
    boolean endAssignment(UUID tenantId, UUID wardId, UUID membershipId, UUID actorUserId);

    // ---- break-glass grants (34E, 34F) ----

    /** Inserts one grant; the database trigger stamps {@code opened_at} and {@code expires_at}, returned here. */
    Stamped insertGrant(UUID tenantId, UUID admissionId, UUID bedId, UUID userId, UUID membershipId, String role,
                      String reasonKey);

    Optional<Grant> findGrant(UUID tenantId, UUID grantId);

    /**
     * The guarded review: {@code update ... set reviewed_by = :me, version = version + 1 where id = :id and
     * reviewed_at is null}. Returns the time the database stamped, or empty when the grant was already reviewed.
     */
    Optional<Instant> markReviewed(UUID tenantId, UUID grantId, UUID reviewerUserId);

    /** Every unreviewed grant of the clinic (the full count). */
    int countUnreviewed(UUID tenantId);

    /**
     * The owner's count and change-token mark from one statement: how many grants wait for a review and the newest of
     * them ({@code order by opened_at desc, id desc limit 1}). {@code newestId} is null when none waits.
     */
    UnreviewedMark unreviewedMark(UUID tenantId);

    /** How many grants wait for a review and the newest one (null when {@code waiting} is 0). */
    record UnreviewedMark(int waiting, UUID newestId) {
    }

    /**
     * One page of unreviewed grants, oldest {@code opened_at} first, then id; strictly after the cursor when it is set.
     * At most {@code limit} rows.
     */
    List<Grant> findUnreviewed(UUID tenantId, Instant afterOpenedAt, UUID afterId, int limit);

    /** The grantee user ids of grants, for the audit trail ("Reviewed emergency access by ..."). */
    Map<UUID, UUID> granteeOf(UUID tenantId, Collection<UUID> grantIds);

    /**
     * Where an admission is and who admitted it. {@code wardId} is the ward of the admission's bed (the last bed for a
     * DISCHARGED admission).
     */
    record Facts(UUID admissionId, UUID patientId, UUID bedId, String bedCode, UUID wardId, UUID doctorMembershipId,
                 String status) {

        public boolean admitted() {
            return "ADMITTED".equals(status);
        }

        @Override
        public String toString() {
            return "Facts[admissionId=" + admissionId + ", status=" + status + "]";
        }
    }

    record Assignment(UUID wardId, UUID membershipId) {
    }

    /** The id and the two times the database stamped on a new grant. */
    record Stamped(UUID id, Instant openedAt, Instant expiresAt) {
    }

    /** A grant as stored. Ids and fixed keys only. */
    record Grant(UUID id, UUID admissionId, UUID patientId, UUID bedId, String bedCode, UUID userId, UUID membershipId, String role,
                 String reasonKey, Instant openedAt, Instant expiresAt, Instant reviewedAt, String admissionStatus) {

        public boolean reviewed() {
            return reviewedAt != null;
        }

        @Override
        public String toString() {
            return "Grant[id=" + id + ", admissionId=" + admissionId + "]";
        }
    }
}
