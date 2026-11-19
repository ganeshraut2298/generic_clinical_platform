package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Layers 1 and 2 of the access model (ADR-028 D4), checked in this order at the start of every Pharmacy and Inventory
 * call, inside the request's transaction: (1) the clinic's module switch, 403 {@code module-off}; (2) the access level of
 * the operation, 403 {@code forbidden}. The membership is read again through {@code TeamMemberPort} on every call, so a
 * member switched off a moment ago has no access (the filter answers 401 first; this is the second line).
 * Layer 3, the store guard, is {@link StoreAccessGuard}.
 */
@Component
public class PharmacyGate {

    static final String MODULE_OFF = "Pharmacy & Inventory is not switched on for your clinic.";
    static final String NO_ACCESS = "You do not have access to Pharmacy & Inventory. Ask your owner.";
    static final String NOT_OWNER = "Only the clinic owner can do this.";
    static final String NOT_ALLOWED = "You do not have access to this.";

    private final ModuleEntitlementPort entitlements;
    private final TeamMemberPort team;
    private final StoreStaffRepository staff;
    private final PharmacyTime time;

    public PharmacyGate(ModuleEntitlementPort entitlements, TeamMemberPort team, StoreStaffRepository staff,
                        PharmacyTime time) {
        this.entitlements = entitlements;
        this.team = team;
        this.staff = staff;
        this.time = time;
    }

    /** Module switch, then the access level. Returns the caller for the rest of the request. */
    public PharmacyCaller require(UUID tenantId, UUID userId, AccessLevel level) {
        if (!entitlements.pharmacyOn(tenantId)) {
            throw new ModuleOffException(MODULE_OFF);
        }
        PharmacyCaller caller = caller(tenantId, userId);
        if (!caller.meets(level)) {
            throw new ForbiddenException("forbidden", "Not allowed", refusal(caller, level));
        }
        return caller;
    }

    private static String refusal(PharmacyCaller caller, AccessLevel level) {
        if (!caller.meets(AccessLevel.PHARMACY_ACCESS)) {
            return NO_ACCESS;
        }
        return level == AccessLevel.SETUP_EDIT ? NOT_OWNER : NOT_ALLOWED;
    }

    /** The caller with today's mappings. A member who is no longer active is a 401, as everywhere else. */
    PharmacyCaller caller(UUID tenantId, UUID userId) {
        TeamMember member = team.findActiveByUserId(tenantId, userId)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sign in required"));
        LocalDate today = time.today();
        List<PharmacyCaller.Mapping> mappings = staff.activeMappedStores(tenantId, List.of(member.membershipId()))
                .stream()
                .map(mapped -> {
                    StoreRole effective = StoreRules.effectiveRole(member.role(), mapped.staff().storeRole(),
                            mapped.staff().delegateFrom(), mapped.staff().delegateTo(), today);
                    StoreAccess access = StoreRules.access(member.role(), effective,
                            mapped.store().consumptionAllowed());
                    return new PharmacyCaller.Mapping(mapped.store(), mapped.staff(), effective, access);
                })
                .toList();
        return new PharmacyCaller(tenantId, userId, member.membershipId(), member.name(), member.role(), mappings);
    }
}
