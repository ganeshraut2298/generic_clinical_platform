package com.sahaj.platform.application;

import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.List;
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

    /** SAH-11: the doctors shown as Appointments columns and in doctor slot settings. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<TeamMember> findActiveDoctors(UUID tenantId) {
        return teamRepository.findAll(tenantId).stream()
                .filter(row -> row.status() == MembershipStatus.ACTIVE && DOCTOR_ROLES.contains(row.role()))
                .map(TeamMemberLookup::toTeamMember)
                .toList();
    }

    private static TeamMember toTeamMember(TeamRow row) {
        return new TeamMember(row.id(), row.name(), row.role().name(), row.status() == MembershipStatus.ACTIVE,
                row.feePaise());
    }
}
