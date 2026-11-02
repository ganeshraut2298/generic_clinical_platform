package com.sahaj.platform.application;

import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC4: only an active owner may change clinic masters. The membership is read again from
 * the database, not taken from the access token, so an owner demoted a moment ago is refused at
 * once (the same rule as {@link TeamService}).
 */
@Service
public class ClinicAccess implements OwnerGuard {

    private final TeamRepository teamRepository;

    public ClinicAccess(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwner(UUID tenantId, UUID userId) {
        boolean owner = teamRepository.findByUser(tenantId, userId)
                .map(row -> row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!owner) {
            throw new AccessDeniedException("Only the clinic owner can change this.");
        }
    }
}
