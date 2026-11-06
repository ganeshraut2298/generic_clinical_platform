package com.sahaj.opd.application;

import com.sahaj.opd.api.VisitBillingPort;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link VisitBillingPort} for {@code billing} (SAH-10) over the same
 * {@link VisitRepository} and {@link TeamMemberPort} {@link VisitService} uses, so there is one
 * source of truth for a visit's doctor name and stage rules.
 */
@Service
class VisitBillingAdapter implements VisitBillingPort {

    private static final Set<String> MOVE_ANY_ROLES = Set.of("OWNER", "FRONT_DESK");

    private final VisitRepository visitRepository;
    private final TeamMemberPort teamMemberPort;
    private final AuditLog auditLog;
    private final Clock clock;

    VisitBillingAdapter(VisitRepository visitRepository, TeamMemberPort teamMemberPort, AuditLog auditLog,
                         Clock clock) {
        this.visitRepository = visitRepository;
        this.teamMemberPort = teamMemberPort;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VisitForBilling> find(UUID tenantId, UUID visitId) {
        return visitRepository.findById(tenantId, visitId).map(row -> toView(tenantId, row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitForBilling> today(UUID tenantId) {
        Instant startOfToday = LocalDate.now(clock.withZone(VisitService.INDIA))
                .atStartOfDay(VisitService.INDIA).toInstant();
        return visitRepository.findSince(tenantId, startOfToday).stream().map(row -> toView(tenantId, row)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitForRevenue> between(UUID tenantId, Instant from, Instant to) {
        return toRevenueViews(tenantId, visitRepository.findBetween(tenantId, from, to));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitForRevenue> findAll(UUID tenantId, Collection<UUID> ids) {
        return toRevenueViews(tenantId, visitRepository.findByIds(tenantId, ids));
    }

    private List<VisitForRevenue> toRevenueViews(UUID tenantId, List<VisitRow> rows) {
        Map<UUID, String> doctorNames = new HashMap<>();
        return rows.stream().map(row -> new VisitForRevenue(row.id(), row.patientId(), row.doctorMembershipId(),
                doctorNames.computeIfAbsent(row.doctorMembershipId(),
                        id -> teamMemberPort.find(tenantId, id).map(TeamMember::name).orElse("Unknown")),
                row.stage(), row.feePaise(), row.createdAt())).toList();
    }

    @Override
    @Transactional
    public void advanceToDone(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId) {
        VisitRow visit = visitRepository.findById(tenantId, visitId)
                .orElseThrow(() -> new NotFoundException("Visit not found"));
        if (!VisitService.BILLING.equals(visit.stage())) {
            throw new InvalidInputException("stage", "This visit is not awaiting payment");
        }
        if (!MOVE_ANY_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the front desk or the owner can close a bill.");
        }
        visitRepository.updateStage(visitId, VisitService.DONE, actorUserId);
        auditLog.record("VISIT_STAGE_MOVED", VisitService.ENTITY_TYPE, visitId,
                Map.of("stage", VisitService.BILLING), Map.of("stage", VisitService.DONE));
    }

    private VisitForBilling toView(UUID tenantId, VisitRow row) {
        TeamMember doctor = teamMemberPort.find(tenantId, row.doctorMembershipId()).orElse(null);
        return new VisitForBilling(row.id(), row.patientId(), doctor == null ? null : doctor.name(), row.visitType(),
                row.stage(), row.tokenNo(), row.feePaise());
    }
}
