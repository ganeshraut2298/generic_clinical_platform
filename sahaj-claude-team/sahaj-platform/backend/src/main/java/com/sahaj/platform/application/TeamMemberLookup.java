package com.sahaj.platform.application;

import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link TeamMemberPort} over {@link TeamRepository} (SAH-9), the same source {@code TeamService} reads. */
@Service
public class TeamMemberLookup implements TeamMemberPort {

    private static final Set<MembershipRole> DOCTOR_ROLES = Set.of(MembershipRole.DOCTOR,
            MembershipRole.VISITING_DOCTOR);

    private final TeamRepository teamRepository;

    public TeamMemberLookup(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<TeamMember> find(UUID tenantId, UUID membershipId) {
        return teamRepository.findById(tenantId, membershipId).map(TeamMemberLookup::toTeamMember);
    }

    /** SAH-24: one roster read for the whole bed board. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, TeamMember> findAll(UUID tenantId, Collection<UUID> membershipIds) {
        Map<UUID, TeamMember> result = new HashMap<>();
        if (membershipIds.isEmpty()) {
            return result;
        }
        for (TeamRow row : teamRepository.findAll(tenantId)) {
            if (membershipIds.contains(row.id())) {
                result.put(row.id(), toTeamMember(row));
            }
        }
        return result;
    }

    /** SAH-11: the doctors shown as Appointments columns and in doctor slot settings. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<TeamMember> findActiveDoctors(UUID tenantId) {
        return teamRepository.findAll(tenantId).stream()
                .filter(row -> row.status() == MembershipStatus.ACTIVE && DOCTOR_ROLES.contains(row.role()))
                .map(TeamMemberLookup::toTeamMember)
                .toList();
    }

    /** SAH-20: the staff roster and attendance sheet list every active member. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<TeamMember> findActiveMembers(UUID tenantId) {
        return teamRepository.findAll(tenantId).stream()
                .filter(row -> row.status() == MembershipStatus.ACTIVE)
                .map(TeamMemberLookup::toTeamMember)
                .toList();
    }

    /**
     * SAH-21: leave is requested for oneself unless the owner names someone else. SAH-33 R8: one single-row query
     * (the unique key tenant and user), not the whole team: it is called again by every guarded IPD call.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<TeamMember> findActiveByUserId(UUID tenantId, UUID userId) {
        return teamRepository.findActiveByUser(tenantId, userId).map(TeamMemberLookup::toTeamMember);
    }

    /** SAH-27: the specialty shown in the discharge summary preview. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<String> doctorSpecialty(UUID tenantId, UUID membershipId) {
        return teamRepository.findById(tenantId, membershipId)
                .filter(row -> DOCTOR_ROLES.contains(row.role()) && row.hasDoctorProfile())
                .map(TeamRow::specialty)
                .map(String::strip)
                .filter(specialty -> !specialty.isEmpty());
    }

    /** SAH-43: name, role and status with the doctor details; empty when the member has no doctor profile row. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<DoctorDetails> doctorDetails(UUID tenantId, UUID membershipId) {
        return teamRepository.findById(tenantId, membershipId)
                .filter(TeamRow::hasDoctorProfile)
                .map(row -> new DoctorDetails(row.id(), row.name(), row.role().name(), row.status().name(),
                        blankToNull(row.specialty()), blankToNull(row.degree()), blankToNull(row.registrationNo())));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static TeamMember toTeamMember(TeamRow row) {
        return new TeamMember(row.id(), row.name(), row.role().name(), row.status() == MembershipStatus.ACTIVE,
                row.feePaise());
    }
}
