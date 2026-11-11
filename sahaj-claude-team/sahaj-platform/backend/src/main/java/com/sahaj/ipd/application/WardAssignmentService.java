package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentInput;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentNurse;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentWard;
import com.sahaj.ipd.application.ScopeViews.WardAssignments;
import com.sahaj.ipd.application.ScopeViews.WardScopeInput;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.ipd.application.WardScopeRepository.Assignment;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-34 story 34A, the data part: who the nurses of each ward are, and the clinic switch "Nurses see only their wards"
 * (ADR-017 D7). OWNER only, by the token role and by the membership read again. The nurse-to-ward screen is not in the
 * mockup yet (DEC-031 item 2), so no screen calls these three operations; the endpoints carry {@code x-needs-human}.
 *
 * <p>Every change takes the clinic IPD advisory lock, writes the assignment rows (ended, never deleted; the database stamps
 * the times) and records one audit event of ids only. A nurse's membership is checked through {@link TeamMemberPort}
 * (active members with the role NURSE), because the table has no key to the platform schema. The switch is off for every
 * clinic until an owner turns it on, and cannot be turned on while an active nurse has no ward (OQ-34-21).
 */
@Service
public class WardAssignmentService {

    static final String CHOOSE_NURSES = "Choose nurses from the list.";
    static final String CHOOSE_ON_OFF = "Choose on or off";
    static final String NURSE_WITHOUT_WARD = "Assign a ward to every nurse before you switch this on.";
    static final int MAX_NURSES = 100;

    private final WardScopeRepository repository;
    private final WardRepository wards;
    private final TeamMemberPort team;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final IpdGate gate;

    WardAssignmentService(WardScopeRepository repository, WardRepository wards, TeamMemberPort team,
                          OwnerGuard ownerGuard, AuditLog auditLog, IpdGate gate) {
        this.repository = repository;
        this.wards = wards;
        this.team = team;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.gate = gate;
    }

    /** The switch, every ward with its live nurses, every active NURSE member with her wards. Read-only is allowed. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WardAssignments get(UUID tenantId, UUID actor, String role) {
        requireOwnerToken(role);
        gate.check(tenantId, false);
        ownerGuard.requireOwner(tenantId, actor);
        return view(tenantId);
    }

    /**
     * Sets the whole new set of nurses of one ward. Check order: role (403), module Off (403), body (400), Read-only (409),
     * then under the clinic IPD lock the membership read again (OWNER, 403), the ward (404), the nurses (400). The same
     * set again changes nothing and writes no audit event.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WardAssignments setWard(UUID tenantId, UUID actor, String role, UUID wardId, WardAssignmentInput input) {
        requireOwnerToken(role);
        ModuleEntitlement ent = gate.check(tenantId, false);
        Set<UUID> wanted = membershipIds(input);
        gate.requireWritable(ent);
        wards.lockTenant(tenantId);
        ownerGuard.requireOwner(tenantId, actor);
        wards.findWard(tenantId, wardId).orElseThrow(() -> new NotFoundException("Ward not found"));
        Set<UUID> nurses = new HashSet<>();
        for (TeamMember member : team.findActiveMembers(tenantId)) {
            if (ScopeGuard.NURSE.equals(member.role())) {
                nurses.add(member.membershipId());
            }
        }
        if (!nurses.containsAll(wanted)) {
            throw new InvalidInputException("membershipIds", CHOOSE_NURSES);
        }
        Set<UUID> live = new LinkedHashSet<>();
        for (Assignment a : repository.liveAssignments(tenantId)) {
            if (a.wardId().equals(wardId)) {
                live.add(a.membershipId());
            }
        }
        List<UUID> removed = new ArrayList<>();
        for (UUID membershipId : live) {
            if (!wanted.contains(membershipId)) {
                removed.add(membershipId);
            }
        }
        List<UUID> added = new ArrayList<>();
        for (UUID membershipId : wanted) {
            if (!live.contains(membershipId)) {
                added.add(membershipId);
            }
        }
        for (UUID membershipId : removed) {
            if (!repository.endAssignment(tenantId, wardId, membershipId, actor)) {
                throw new IllegalStateException("A ward assignment was not ended");
            }
        }
        for (UUID membershipId : added) {
            repository.insertAssignment(tenantId, wardId, membershipId, actor);
        }
        if (!removed.isEmpty() || !added.isEmpty()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("wardId", wardId);
            after.put("added", added);
            after.put("removed", removed);
            auditLog.record("IPD_WARD_ASSIGNMENT_CHANGED", "IPD_WARD_ASSIGNMENT", wardId, null, after);
        }
        return view(tenantId);
    }

    /**
     * Switches "Nurses see only their wards" on or off. Check order: role (403), module Off (403), body (400), Read-only
     * (409), then under the clinic IPD lock the membership read again (OWNER), and 409 nurse-without-ward when switching
     * on while an active nurse has no live ward. The same value again writes nothing. Applies at once: every user's next
     * request builds its scope from it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WardAssignments setScope(UUID tenantId, UUID actor, String role, WardScopeInput input) {
        requireOwnerToken(role);
        ModuleEntitlement ent = gate.check(tenantId, false);
        JsonNode on = input == null ? null : input.on();
        if (on == null || !on.isBoolean()) {
            throw new InvalidInputException("on", CHOOSE_ON_OFF);
        }
        gate.requireWritable(ent);
        wards.lockTenant(tenantId);
        ownerGuard.requireOwner(tenantId, actor);
        boolean next = on.booleanValue();
        boolean before = repository.nurseWardScope(tenantId);
        if (next && nursesWithoutWard(tenantId) > 0) {
            throw new ConflictException("nurse-without-ward", "Nurse without a ward", NURSE_WITHOUT_WARD);
        }
        if (next != before) {
            repository.upsertNurseWardScope(tenantId, next, actor, gate.now());
            Map<String, Object> was = new LinkedHashMap<>();
            was.put("nurseWardScope", before);
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("nurseWardScope", next);
            auditLog.record("IPD_WARD_SCOPE_CHANGED", "IPD_CONFIG", null, was, now);
        }
        return view(tenantId);
    }

    // ---- helpers ----

    private static void requireOwnerToken(String role) {
        if (!"OWNER".equals(role)) {
            throw new AccessDeniedException("Only the owner can set the wards of nurses.");
        }
    }

    /** The nurse ids of the body: at most 100, each a UUID, none twice; anything else is the one field message. */
    private static Set<UUID> membershipIds(WardAssignmentInput input) {
        JsonNode array = input == null ? null : input.membershipIds();
        if (array == null || !array.isArray() || array.size() > MAX_NURSES) {
            throw new InvalidInputException("membershipIds", CHOOSE_NURSES);
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new InvalidInputException("membershipIds", CHOOSE_NURSES);
            }
            UUID id;
            try {
                id = UUID.fromString(item.asText());
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("membershipIds", CHOOSE_NURSES);
            }
            if (!ids.add(id)) {
                throw new InvalidInputException("membershipIds", CHOOSE_NURSES);
            }
        }
        return ids;
    }

    private int nursesWithoutWard(UUID tenantId) {
        return view(tenantId).nursesWithoutWard();
    }

    /** The answer of the three operations: the switch, the wards in the Beds setup order, the active nurses by name. */
    private WardAssignments view(UUID tenantId) {
        List<TeamMember> nurses = new ArrayList<>();
        for (TeamMember member : team.findActiveMembers(tenantId)) {
            if (ScopeGuard.NURSE.equals(member.role())) {
                nurses.add(member);
            }
        }
        nurses.sort(Comparator.comparing(TeamMember::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamMember::membershipId));
        Set<UUID> nurseIds = new HashSet<>();
        for (TeamMember nurse : nurses) {
            nurseIds.add(nurse.membershipId());
        }
        Map<UUID, List<UUID>> wardsOfNurse = new HashMap<>();
        Map<UUID, List<UUID>> nursesOfWard = new HashMap<>();
        for (Assignment a : repository.liveAssignments(tenantId)) {
            if (!nurseIds.contains(a.membershipId())) {
                continue;
            }
            wardsOfNurse.computeIfAbsent(a.membershipId(), k -> new ArrayList<>()).add(a.wardId());
            nursesOfWard.computeIfAbsent(a.wardId(), k -> new ArrayList<>()).add(a.membershipId());
        }
        List<WardAssignmentWard> wardViews = new ArrayList<>();
        for (WardRow ward : wards.findWards(tenantId)) {
            wardViews.add(new WardAssignmentWard(ward.id(), ward.name(),
                    List.copyOf(nursesOfWard.getOrDefault(ward.id(), List.of()))));
        }
        List<WardAssignmentNurse> nurseViews = new ArrayList<>();
        int without = 0;
        for (TeamMember nurse : nurses) {
            List<UUID> mine = wardsOfNurse.getOrDefault(nurse.membershipId(), List.of());
            if (mine.isEmpty()) {
                without++;
            }
            nurseViews.add(new WardAssignmentNurse(nurse.membershipId(), nurse.name(), List.copyOf(mine)));
        }
        return new WardAssignments(repository.nurseWardScope(tenantId), List.copyOf(wardViews),
                List.copyOf(nurseViews), without);
    }
}
