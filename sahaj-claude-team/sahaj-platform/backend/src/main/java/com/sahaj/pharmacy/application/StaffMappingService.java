package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyViews.OtherStore;
import com.sahaj.pharmacy.application.PharmacyViews.StaffCandidate;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-4: mapping team members to stores with a store role. The owner maps, changes and removes; Pharmacist and Central
 * Store users read. The access columns are derived here on every read ({@code StoreRules}) and never stored or accepted
 * from a client. A store role that does not fit the member's team role is refused when mapping and shows as "needs
 * review" later (it then counts as Store user). Every write locks the store row first, so the one-HOD and one-delegate
 * rules are checked against a stable picture.
 */
@Service
public class StaffMappingService {

    static final String BAD_MEMBER = "Choose an active team member";
    static final String BAD_ROLE = "This person cannot have this store role";
    static final String CENTRAL_ONLY = "Central Store user is only for the Central Store";
    private static final String ENTITY_TYPE = StaffMappingWriter.ENTITY_TYPE;

    private final PharmacyGate gate;
    private final StoreRepository stores;
    private final StoreStaffRepository staff;
    private final StaffMappingWriter writer;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public StaffMappingService(PharmacyGate gate, StoreRepository stores, StoreStaffRepository staff,
                               StaffMappingWriter writer, TeamMemberPort team, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.stores = stores;
        this.staff = staff;
        this.writer = writer;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    /** HOD first, then delegate, then by name; REMOVED rows (history) after the active ones, on request only. */
    @Transactional(readOnly = true)
    PharmacyViews.StaffMappings list(UUID tenantId, UUID userId, UUID storeId, boolean includeRemoved) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return listOf(tenantId, storeId, includeRemoved);
    }

    /** As above for the raw path id and query value of the request: both are read after the gate has answered. */
    @Transactional(readOnly = true)
    public PharmacyViews.StaffMappings list(UUID tenantId, UUID userId, String rawStoreId, String rawIncludeRemoved) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        return listOf(tenantId, storeId, PharmacyParams.bool("includeRemoved", rawIncludeRemoved, false));
    }

    private PharmacyViews.StaffMappings listOf(UUID tenantId, UUID storeId, boolean includeRemoved) {
        Store store = stores.find(tenantId, storeId).orElseThrow(StaffMappingService::notFound);
        List<StoreStaff> rows = staff.listForStore(tenantId, storeId, includeRemoved);
        Context context = context(tenantId, store, rows);
        List<PharmacyViews.StaffMapping> items = rows.stream().map(row -> view(row, store, context))
                .sorted(Comparator.comparing((PharmacyViews.StaffMapping v) -> "REMOVED".equals(v.status()))
                        .thenComparing(v -> v.isHod() ? 0 : v.storeRole() == StoreRole.DELEGATE ? 1 : 2)
                        .thenComparing(v -> v.name().toLowerCase(Locale.ROOT)))
                .toList();
        return new PharmacyViews.StaffMappings(items);
    }

    /**
     * Active team members with the store roles each may take. With a store, CENTRAL_STORE_USER is offered only at the
     * Central Store; without one ({@code storeId} null, contract 0.1.1) the list is by team role only and nothing is
     * marked as already mapped. {@code storeRole} narrows the list to members allowed that role. Never a mobile number.
     */
    @Transactional(readOnly = true)
    PharmacyViews.StaffCandidates candidates(UUID tenantId, UUID userId, UUID storeId, StoreRole storeRole) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        return candidatesOf(tenantId, storeId, storeRole);
    }

    /** As above for the raw query values of the request: read after the gate, so a non-owner gets 403 first. */
    @Transactional(readOnly = true)
    public PharmacyViews.StaffCandidates candidates(UUID tenantId, UUID userId, String rawStoreId, String rawStoreRole) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.optionalUuid("storeId", rawStoreId, "Choose a store");
        return candidatesOf(tenantId, storeId, PharmacyParams.optionalStoreRole("storeRole", rawStoreRole));
    }

    private PharmacyViews.StaffCandidates candidatesOf(UUID tenantId, UUID storeId, StoreRole storeRole) {
        Store store = storeId == null ? null
                : stores.find(tenantId, storeId).orElseThrow(StaffMappingService::notFound);
        Set<UUID> mappedHere = store == null ? Set.of()
                : staff.listForStore(tenantId, storeId, false).stream().map(StoreStaff::membershipId)
                        .collect(Collectors.toSet());
        List<StaffCandidate> items = new ArrayList<>();
        for (TeamMember member : team.findActiveMembers(tenantId)) {
            Set<StoreRole> allowed = EnumSet.copyOf(TeamRoles.allowedStoreRoles(member.role()));
            if (store != null && !store.type().isCentral()) {
                allowed.remove(StoreRole.CENTRAL_STORE_USER);
            }
            if (storeRole != null && !allowed.contains(storeRole)) {
                continue;
            }
            items.add(new StaffCandidate(member.membershipId(), member.name(), member.role(),
                    RoleLabels.of(member.role()), List.copyOf(allowed.stream().sorted().toList()),
                    mappedHere.contains(member.membershipId())));
        }
        return new PharmacyViews.StaffCandidates(items);
    }

    // ---- writes ----

    /** For tests: the body is already read. */
    PharmacyViews.StaffMapping map(UUID tenantId, UUID userId, UUID storeId, PharmacyInputs.MapStaff input) {
        return map(tenantId, userId, storeId.toString(), PharmacyInputs.Body.of(input));
    }

    /** For tests: the body is already read. */
    PharmacyViews.StaffMapping map(UUID tenantId, UUID userId, String rawStoreId, PharmacyInputs.MapStaff input) {
        return map(tenantId, userId, rawStoreId, PharmacyInputs.Body.of(input));
    }

    /**
     * The raw path id and the body are read after the gate, in this order: module off 403, owner only 403, then 400 for a
     * path id that is not an id, then 400 for a body that cannot be read, then 404.
     */
    @Transactional
    public PharmacyViews.StaffMapping map(UUID tenantId, UUID userId, String rawStoreId,
                                          PharmacyInputs.Body<PharmacyInputs.MapStaff> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        return mapIn(tenantId, userId, storeId, body.read());
    }

    private PharmacyViews.StaffMapping mapIn(UUID tenantId, UUID userId, UUID storeId, PharmacyInputs.MapStaff input) {
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(StaffMappingService::notFound);
        LocalDate today = time.today();
        List<FieldMessage> errors = new ArrayList<>();
        TeamMember member = input.membershipId() == null ? null : team.find(tenantId, input.membershipId()).orElse(null);
        if (member == null || !member.active()) {
            errors.add(new FieldMessage("membershipId", BAD_MEMBER));
        }
        checkRole(input.storeRole(), member, store, "storeRole", errors);
        StoreStaff hod = staff.activeWithRole(tenantId, storeId, StoreRole.APPROVER).orElse(null);
        if (input.storeRole() == StoreRole.DELEGATE) {
            DelegateRules.check(input.delegateFrom(), input.delegateTo(), today, "delegateFrom", "delegateTo", errors);
            if (hod != null && hod.membershipId().equals(input.membershipId())) {
                errors.add(new FieldMessage("membershipId", DelegateRules.SAME_AS_HOD));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        if (!store.active()) {
            throw new ConflictException("store-inactive", "This store is switched off", "This store is switched off.");
        }
        StoreStaff existing = staff.findForMember(tenantId, storeId, input.membershipId()).orElse(null);
        if (existing != null && existing.active()) {
            throw new ConflictException("already-mapped", "Already in this store", "That person is already in this store");
        }
        requireFreeSeat(tenantId, storeId, input.storeRole(), null);

        StoreStaff saved = writer.assign(tenantId, store.unitId(), storeId, input.membershipId(), input.storeRole(),
                input.delegateFrom(), input.delegateTo(), userId);
        return viewOne(tenantId, saved, store);
    }

    /** For tests: the body is already read. */
    PharmacyViews.StaffMapping change(UUID tenantId, UUID userId, UUID storeId, UUID mappingId,
                                      PharmacyInputs.ChangeMapping input) {
        return change(tenantId, userId, storeId.toString(), mappingId.toString(), PharmacyInputs.Body.of(input));
    }

    /** For tests: the body is already read. */
    PharmacyViews.StaffMapping change(UUID tenantId, UUID userId, String rawStoreId, String rawMappingId,
                                      PharmacyInputs.ChangeMapping input) {
        return change(tenantId, userId, rawStoreId, rawMappingId, PharmacyInputs.Body.of(input));
    }

    /** The raw path ids and the body are read after the gate (module off 403, owner only 403), then 400, then 404. */
    @Transactional
    public PharmacyViews.StaffMapping change(UUID tenantId, UUID userId, String rawStoreId, String rawMappingId,
                                             PharmacyInputs.Body<PharmacyInputs.ChangeMapping> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID mappingId = PharmacyParams.pathId(rawMappingId);
        return changeIn(tenantId, userId, storeId, mappingId, body.read());
    }

    private PharmacyViews.StaffMapping changeIn(UUID tenantId, UUID userId, UUID storeId, UUID mappingId,
                                                PharmacyInputs.ChangeMapping input) {
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(StaffMappingService::notFound);
        StoreStaff row = mapping(tenantId, storeId, mappingId);
        if (!row.active()) {
            throw notFound();
        }
        LocalDate today = time.today();
        List<FieldMessage> errors = new ArrayList<>();
        if (input.version() == null || input.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        TeamMember member = team.find(tenantId, row.membershipId()).orElse(null);
        if (member == null || !member.active()) {
            // a missing or switched-off member never skips the role check (security L2): 400, as when mapping
            errors.add(new FieldMessage("membershipId", BAD_MEMBER));
        }
        checkRole(input.storeRole(), member, store, "storeRole", errors);
        if (input.storeRole() == StoreRole.DELEGATE) {
            boolean unchanged = row.storeRole() == StoreRole.DELEGATE
                    && Objects.equals(row.delegateTo(), input.delegateTo())
                    && Objects.equals(row.delegateFrom(), input.delegateFrom());
            if (unchanged) {
                if (input.delegateFrom() == null || input.delegateTo() == null) {
                    errors.add(new FieldMessage("delegateTo", "Enter the end date"));
                }
            } else {
                DelegateRules.check(input.delegateFrom(), input.delegateTo(), today, "delegateFrom", "delegateTo", errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        if (input.version() != row.version()) {
            throw PharmacyProblemException.settingsChanged(viewOne(tenantId, row, store));
        }
        requireFreeSeat(tenantId, storeId, input.storeRole(), row.id());
        LocalDate from = input.storeRole() == StoreRole.DELEGATE ? input.delegateFrom() : null;
        LocalDate to = input.storeRole() == StoreRole.DELEGATE ? input.delegateTo() : null;
        if (row.storeRole() == input.storeRole() && Objects.equals(row.delegateFrom(), from)
                && Objects.equals(row.delegateTo(), to)) {
            return viewOne(tenantId, row, store);
        }
        if (!staff.update(tenantId, row.id(), row.version(), input.storeRole(), from, to, true, userId)) {
            throw PharmacyProblemException.settingsChanged(viewOne(tenantId, mapping(tenantId, storeId, mappingId), store));
        }
        auditLog.record("PHARMACY_STAFF_MAPPING_CHANGED", ENTITY_TYPE, row.id(), StaffMappingWriter.values(row),
                StaffMappingWriter.values(storeId, row.membershipId(), input.storeRole(), from, to, "ACTIVE"));
        return viewOne(tenantId, mapping(tenantId, storeId, mappingId), store);
    }

    /** Safe to repeat: an already REMOVED row answers 200 and nothing is written. The row stays as history. */
    @Transactional
    PharmacyViews.StaffMapping remove(UUID tenantId, UUID userId, UUID storeId, UUID mappingId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        return removeIn(tenantId, userId, storeId, mappingId);
    }

    /** As above for the raw path ids of the request. */
    @Transactional
    public PharmacyViews.StaffMapping remove(UUID tenantId, UUID userId, String rawStoreId, String rawMappingId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID mappingId = PharmacyParams.pathId(rawMappingId);
        return removeIn(tenantId, userId, storeId, mappingId);
    }

    private PharmacyViews.StaffMapping removeIn(UUID tenantId, UUID userId, UUID storeId, UUID mappingId) {
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(StaffMappingService::notFound);
        StoreStaff row = mapping(tenantId, storeId, mappingId);
        if (!row.active()) {
            return viewOne(tenantId, row, store);
        }
        if (!staff.update(tenantId, row.id(), row.version(), row.storeRole(), row.delegateFrom(), row.delegateTo(), false,
                userId)) {
            throw new IllegalStateException("A store mapping changed under the store lock");
        }
        Map<String, Object> after = new LinkedHashMap<>(StaffMappingWriter.values(row));
        after.put("status", "REMOVED");
        after.put("removedBy", userId.toString());
        auditLog.record("PHARMACY_STAFF_REMOVED", ENTITY_TYPE, row.id(), StaffMappingWriter.values(row), after);
        return viewOne(tenantId, mapping(tenantId, storeId, mappingId), store);
    }

    // ---- checks ----

    /** The store role must be set, allowed for the member's team role, and CENTRAL_STORE_USER only at the Central Store. */
    private static void checkRole(StoreRole storeRole, TeamMember member, Store store, String field,
                                  List<FieldMessage> errors) {
        if (storeRole == null) {
            errors.add(new FieldMessage(field, "Choose a store role"));
        } else if (member != null && !TeamRoles.allowedStoreRoles(member.role()).contains(storeRole)) {
            errors.add(new FieldMessage(field, BAD_ROLE));
        } else if (storeRole == StoreRole.CENTRAL_STORE_USER && !store.type().isCentral()) {
            errors.add(new FieldMessage(field, CENTRAL_ONLY));
        }
    }

    /** At most one ACTIVE HOD and one ACTIVE delegate per store. {@code exceptRowId} is the row being changed. */
    private void requireFreeSeat(UUID tenantId, UUID storeId, StoreRole role, UUID exceptRowId) {
        if (role != StoreRole.APPROVER && role != StoreRole.DELEGATE) {
            return;
        }
        staff.activeWithRole(tenantId, storeId, role).filter(other -> !other.id().equals(exceptRowId)).ifPresent(other -> {
            throw new ConflictException("approver-exists", "Already set", role == StoreRole.APPROVER
                    ? "This store already has an approver. Change it on the store."
                    : "This store already has a delegate. Change it on the store.");
        });
    }

    private StoreStaff mapping(UUID tenantId, UUID storeId, UUID mappingId) {
        return staff.find(tenantId, mappingId).filter(row -> row.storeId().equals(storeId))
                .orElseThrow(StaffMappingService::notFound);
    }

    // ---- views ----

    /** The people and "other stores" needed to show a set of rows of one store. */
    private record Context(Map<UUID, TeamMember> members, Map<UUID, List<OtherStore>> otherStores) {
    }

    private Context context(UUID tenantId, Store store, List<StoreStaff> rows) {
        Set<UUID> ids = rows.stream().map(StoreStaff::membershipId).collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return new Context(Map.of(), Map.of());
        }
        Map<UUID, TeamMember> members = team.findAll(tenantId, ids);
        Map<UUID, List<OtherStore>> others = staff.activeMappedStores(tenantId, ids).stream()
                .filter(m -> !m.store().id().equals(store.id()))
                .sorted(Comparator.comparing(m -> m.store().name().toLowerCase(Locale.ROOT)))
                .collect(Collectors.groupingBy(m -> m.staff().membershipId(),
                        Collectors.mapping(m -> new OtherStore(m.store().id(), m.store().name()), Collectors.toList())));
        return new Context(members, others);
    }

    private PharmacyViews.StaffMapping viewOne(UUID tenantId, StoreStaff row, Store store) {
        return view(row, store, context(tenantId, store, List.of(row)));
    }

    private PharmacyViews.StaffMapping view(StoreStaff row, Store store, Context context) {
        TeamMember member = context.members().get(row.membershipId());
        boolean canSignIn = member != null && member.active();
        String role = member == null ? "" : member.role();
        boolean needsReview = member != null && StoreRules.needsReview(role, row.storeRole());
        LocalDate today = time.today();
        StoreRole effective = member == null ? StoreRole.STORE_USER
                : StoreRules.effectiveRole(role, row.storeRole(), row.delegateFrom(), row.delegateTo(), today);
        StoreAccess access = !row.active() || !canSignIn ? StoreAccess.NONE
                : StoreRules.access(role, effective, store.consumptionAllowed());
        DelegateState state = row.storeRole() == StoreRole.DELEGATE
                ? DelegateState.of(row.delegateFrom(), row.delegateTo(), today) : null;
        return new PharmacyViews.StaffMapping(row.id(), row.storeId(), row.membershipId(),
                member == null ? "Unknown member" : member.name(), role, member == null ? "" : RoleLabels.of(role),
                row.active() && effective == StoreRole.APPROVER, row.storeRole(), effective, row.delegateFrom(),
                row.delegateTo(), state, access, context.otherStores().getOrDefault(row.membershipId(), List.of()),
                canSignIn ? "ACTIVE" : "CANNOT_SIGN_IN", needsReview, row.active() ? "ACTIVE" : "REMOVED",
                row.version());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
