package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.ModuleEntitlementRepository.EntitlementRow;
import com.sahaj.platform.domain.MembershipRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-19 Plan and modules. Any clinic user reads; only the owner switches HRA, asks for IPD or (while
 * the temporary DEC-010 switch is on) starts the 30-day IPD trial. The row is lazy: defaults are
 * served until the first write. Audit carries only the entitlement fields.
 */
@Service
public class ModulesService {

    static final Duration TRIAL_LENGTH = Duration.ofDays(30);
    private static final String ENTITY_TYPE = "MODULE_ENTITLEMENT";

    private final ModuleEntitlementRepository repository;
    private final TenantRepository tenantRepository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final Clock clock;
    private final boolean ownerIpdTrial;

    public ModulesService(ModuleEntitlementRepository repository, TenantRepository tenantRepository,
                           OwnerGuard ownerGuard, AuditLog auditLog, Clock clock,
                           @Value("${sahaj.modules.owner-ipd-trial:false}") boolean ownerIpdTrial) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.clock = clock;
        this.ownerIpdTrial = ownerIpdTrial;
    }

    @Transactional(readOnly = true)
    public ModulesView get(UUID tenantId, String roleName) {
        return view(tenantId, roleName, repository.find(tenantId).orElse(ModuleEntitlements.DEFAULTS));
    }

    @Transactional
    public ModulesView requestIpd(UUID tenantId, UUID actorUserId, String roleName) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.lockForWrite(tenantId, ModuleEntitlements.DEFAULTS, actorUserId);
        EntitlementRow before = repository.find(tenantId).orElse(ModuleEntitlements.DEFAULTS);
        if (!"off".equals(ModuleEntitlements.effectiveIpdState(before, now))) {
            throw ipdNotOff();
        }
        if (before.ipdRequestedAt() != null) {
            return view(tenantId, roleName, before);
        }
        EntitlementRow after = new EntitlementRow(before.ipdState(), before.ipdTrialEndsAt(), before.hraOn(),
                before.bedLimit(), now);
        save(tenantId, actorUserId, "MODULE_IPD_REQUESTED", before, after);
        return view(tenantId, roleName, after);
    }

    @Transactional
    public ModulesView startIpdTrial(UUID tenantId, UUID actorUserId, String roleName) {
        if (!ownerIpdTrial) {
            throw new NotFoundException("The IPD trial is not available");
        }
        ownerGuard.requireOwner(tenantId, actorUserId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.lockForWrite(tenantId, ModuleEntitlements.DEFAULTS, actorUserId);
        EntitlementRow before = repository.find(tenantId).orElse(ModuleEntitlements.DEFAULTS);
        if (!"off".equals(ModuleEntitlements.effectiveIpdState(before, now))) {
            throw ipdNotOff();
        }
        EntitlementRow after = new EntitlementRow("trial", now.plus(TRIAL_LENGTH), before.hraOn(),
                before.bedLimit(), before.ipdRequestedAt());
        save(tenantId, actorUserId, "MODULE_IPD_TRIAL_STARTED", before, after);
        return view(tenantId, roleName, after);
    }

    @Transactional
    public ModulesView setHra(UUID tenantId, UUID actorUserId, String roleName, Boolean on) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (on == null) {
            throw new InvalidInputException("on", "Choose on or off");
        }
        repository.lockForWrite(tenantId, ModuleEntitlements.DEFAULTS, actorUserId);
        EntitlementRow before = repository.find(tenantId).orElse(ModuleEntitlements.DEFAULTS);
        if (before.hraOn() == on) {
            return view(tenantId, roleName, before);
        }
        EntitlementRow after = new EntitlementRow(before.ipdState(), before.ipdTrialEndsAt(), on,
                before.bedLimit(), before.ipdRequestedAt());
        save(tenantId, actorUserId, "MODULE_HRA_CHANGED", before, after);
        return view(tenantId, roleName, after);
    }

    private void save(UUID tenantId, UUID actorUserId, String action, EntitlementRow before, EntitlementRow after) {
        repository.upsert(tenantId, after, actorUserId);
        auditLog.record(action, ENTITY_TYPE, tenantId, snapshot(before), snapshot(after));
    }

    private ModulesView view(UUID tenantId, String roleName, EntitlementRow row) {
        String plan = tenantRepository.findProfile(tenantId)
                .orElseThrow(() -> new IllegalStateException("The signed-in clinic was not found"))
                .plan();
        MembershipRole role = MembershipRole.valueOf(roleName);
        String state = ModuleEntitlements.effectiveIpdState(row, clock.instant());
        boolean requested = row.ipdRequestedAt() != null;
        NavigationPolicy.ModuleFlags flags = new NavigationPolicy.ModuleFlags(row.hraOn(), !"off".equals(state),
                "trial".equals(state) || "on".equals(state));
        return new ModulesView("FREE".equals(plan) ? "FREE" : "PRO",
                new ModulesView.Ipd(state, row.ipdTrialEndsAt(), requested, row.ipdRequestedAt()),
                ownerIpdTrial && "off".equals(state), row.hraOn(),
                new ModulesView.MenuCounts(NavigationPolicy.menuFor(role, "simple", flags).size(),
                        NavigationPolicy.menuFor(role, "advanced", flags).size()));
    }

    private static ConflictException ipdNotOff() {
        return new ConflictException("ipd-not-off", "IPD is already set up",
                "IPD is already on, in trial or read-only for your clinic.");
    }

    private static Map<String, Object> snapshot(EntitlementRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ipdState", row.ipdState());
        values.put("ipdTrialEndsAt", row.ipdTrialEndsAt() == null ? null : row.ipdTrialEndsAt().toString());
        values.put("hraOn", row.hraOn());
        values.put("bedLimit", row.bedLimit());
        values.put("ipdRequestedAt", row.ipdRequestedAt() == null ? null : row.ipdRequestedAt().toString());
        return values;
    }
}
