package com.sahaj.ipd.application;

import com.sahaj.ipd.application.PatientScope.Kind;
import com.sahaj.ipd.application.WardScopeRepository.Facts;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * SAH-34 (ADR-017 D2): the only class that decides which admitted patients a caller may reach.
 *
 * <ul>
 *   <li>{@link #scope} builds the {@link PatientScope} once per request, inside the request's transaction, right after the
 *       membership re-read. The inputs are the signed-in user, {@code ipd.config.nurse_ward_scope}, the live ward
 *       assignments and the live grants. If any read fails the request fails (500); it never falls back to ALL.</li>
 *   <li>{@link #visibleAdmissionIds} gives the set a list read filters on (null means every admitted patient).</li>
 *   <li>{@link #requireAdmission} refuses a by-id read; {@link #requireAdmissionLocked} refuses a write, after the
 *       caller has locked the admission (or holds the clinic IPD lock), re-running the grant check on the database clock.</li>
 * </ul>
 *
 * A refusal writes nothing and logs one INFO line: tenant id, request id, route name, user id and admission id. No name,
 * bed, ward or UHID is ever logged. Callers that are not a NURSE or VISITING_DOCTOR (token role and membership role) are
 * scope ALL and cost no scope read.
 */
@Component
class ScopeGuard {

    static final String NURSE = "NURSE";
    static final String VISITING_DOCTOR = "VISITING_DOCTOR";

    private static final Logger log = LoggerFactory.getLogger(ScopeGuard.class);

    private final TeamMemberPort team;
    private final WardScopeRepository repository;

    ScopeGuard(TeamMemberPort team, WardScopeRepository repository) {
        this.team = team;
        this.repository = repository;
    }

    // ---- the scope ----

    /**
     * The caller's scope. {@code memberRole} is the role the endpoint already read from the membership, or null when the
     * endpoint only checks the role in the token: then the membership is read here. An unscoped role with a known
     * membership role costs no read.
     */
    PatientScope scope(UUID tenantId, UUID actor, String tokenRole, String memberRole) {
        return build(tenantId, actor, tokenRole, memberRole, false);
    }

    /** As {@link #scope}, and the membership id is always read (the Admitted list needs it for "mine"). */
    PatientScope scopeWithMembership(UUID tenantId, UUID actor, String tokenRole, String memberRole) {
        return build(tenantId, actor, tokenRole, memberRole, true);
    }

    private PatientScope build(UUID tenantId, UUID actor, String tokenRole, String memberRole,
                               boolean needMembership) {
        boolean narrow = narrow(tokenRole) || narrow(memberRole);
        if (memberRole != null && !narrow && !needMembership) {
            return PatientScope.all(actor, tokenRole, memberRole, null, Set.of());
        }
        try {
            Optional<TeamMember> member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active);
            if (member.isEmpty()) {
                if (narrow) {
                    throw new AccessDeniedException("No active membership");
                }
                return PatientScope.all(actor, tokenRole, memberRole, null, Set.of());
            }
            String role = member.get().role();
            UUID membershipId = member.get().membershipId();
            String effective = effectiveRole(tokenRole, role);
            if (NURSE.equals(effective)) {
                Set<UUID> wards = repository.liveWardIds(tenantId, membershipId);
                if (!repository.nurseWardScope(tenantId)) {
                    return PatientScope.all(actor, tokenRole, effective, membershipId, wards);
                }
                Map<UUID, java.time.Instant> grants = repository.liveGrants(tenantId, membershipId, NURSE);
                return new PatientScope(wards.isEmpty() ? Kind.NONE : Kind.WARDS, actor, tokenRole, effective,
                        membershipId, wards, grants);
            }
            if (VISITING_DOCTOR.equals(effective)) {
                return new PatientScope(Kind.OWN, actor, tokenRole, effective, membershipId, Set.of(),
                        repository.liveGrants(tenantId, membershipId, VISITING_DOCTOR));
            }
            return PatientScope.all(actor, tokenRole, effective, membershipId, Set.of());
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            // AC 6: never fall back to "all". The request fails; ids only in the log.
            log.warn("ipd scope could not be built tenantId={} requestId={}", tenantId, MDC.get("requestId"));
            throw new IllegalStateException("The patient scope could not be built", e);
        }
    }

    private static boolean narrow(String role) {
        return NURSE.equals(role) || VISITING_DOCTOR.equals(role);
    }

    /** NURSE when either role is NURSE, VISITING_DOCTOR when either is, else the membership role. */
    private static String effectiveRole(String tokenRole, String membershipRole) {
        if (NURSE.equals(tokenRole) || NURSE.equals(membershipRole)) {
            return NURSE;
        }
        if (VISITING_DOCTOR.equals(tokenRole) || VISITING_DOCTOR.equals(membershipRole)) {
            return VISITING_DOCTOR;
        }
        return membershipRole;
    }

    // ---- lists ----

    /**
     * The admission ids a set read may include, or null for every admitted patient (the caller is scope ALL). One
     * narrow statement over the admitted set of the clinic; nothing of an out-of-scope row reaches an answer.
     */
    Set<UUID> visibleAdmissionIds(UUID tenantId, PatientScope scope) {
        if (scope.isAll()) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        for (Facts f : repository.findAdmittedFacts(tenantId)) {
            if (scope.allows(f.wardId(), f.doctorMembershipId(), f.admissionId())) {
                ids.add(f.admissionId());
            }
        }
        return ids;
    }

    // ---- by-id reads and writes ----

    /** A by-id read, no lock. A grant counts only while the admission is ADMITTED. */
    void requireAdmission(UUID tenantId, UUID actor, String tokenRole, String memberRole, UUID admissionId,
                          String route) {
        PatientScope scope = scope(tenantId, actor, tokenRole, memberRole);
        require(tenantId, scope, admissionId, route, false);
    }

    /** As {@link #requireAdmission} with the scope already built for this request. */
    void requireAdmission(UUID tenantId, PatientScope scope, UUID admissionId, String route) {
        require(tenantId, scope, admissionId, route, false);
    }

    /**
     * A write, after the admission row is locked (or the clinic IPD lock is held): the bed's ward is read from locked
     * data and a grant is checked again against the database clock, so a transfer, a discharge or an expiry that races
     * the write is decided here.
     */
    void requireAdmissionLocked(UUID tenantId, UUID actor, String tokenRole, String memberRole, UUID admissionId,
                                String route) {
        PatientScope scope = scope(tenantId, actor, tokenRole, memberRole);
        require(tenantId, scope, admissionId, route, true);
    }

    private void require(UUID tenantId, PatientScope scope, UUID admissionId, String route, boolean locked) {
        if (scope.isAll()) {
            return;
        }
        Facts facts = repository.findFacts(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        boolean inScope = scope.ownsPlacement(facts.wardId(), facts.doctorMembershipId());
        if (!inScope) {
            inScope = locked
                    ? facts.admitted() && repository.hasLiveGrant(tenantId, scope.membershipId(), scope.memberRole(),
                            admissionId)
                    : facts.admitted() && scope.grants().containsKey(admissionId);
        }
        if (!inScope) {
            log.info("ipd outside-scope tenantId={} requestId={} route={} userId={} admissionId={}", tenantId,
                    MDC.get("requestId"), route, scope.userId(), admissionId);
            throw new OutsideScopeException();
        }
    }
}
