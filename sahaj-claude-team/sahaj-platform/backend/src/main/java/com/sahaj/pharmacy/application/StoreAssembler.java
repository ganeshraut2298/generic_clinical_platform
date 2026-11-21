package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyViews.StoreDelegate;
import com.sahaj.pharmacy.application.PharmacyViews.StorePerson;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Store} answer of docs/api/pharmacy.yaml: the store with its HOD and delegate (derived from the
 * APPROVER and DELEGATE mappings), "No approver set", the staff count and the locked fields. A unit has a handful of
 * stores (one to forty), so every call reads the whole list once and assembles from it: the names of the issuing stores
 * and the mappings of all stores come in two reads, plus one {@code TeamMemberPort} call for the people.
 */
@Component
class StoreAssembler {

    static final String LOCKED_TYPE = "TYPE";
    static final String LOCKED_CODE = "CODE";
    static final String LOCKED_ISSUES_FROM = "ISSUES_FROM";

    private final StoreRepository stores;
    private final StoreStaffRepository staff;
    private final TeamMemberPort team;
    private final PharmacyTime time;
    private final AdmittedPatientsReader wardReader;

    StoreAssembler(StoreRepository stores, StoreStaffRepository staff, TeamMemberPort team, PharmacyTime time,
                   AdmittedPatientsReader wardReader) {
        this.wardReader = wardReader;
        this.stores = stores;
        this.staff = staff;
        this.team = team;
        this.time = time;
    }

    /** Every store of the unit as the contract shows it, the Central Store first. INACTIVE ones only on request. */
    List<PharmacyViews.Store> all(UUID tenantId, boolean includeInactive) {
        List<Store> every = stores.list(tenantId, true);
        List<PharmacyViews.Store> views = assemble(tenantId, every);
        return includeInactive ? views : views.stream().filter(v -> "ACTIVE".equals(v.status())).toList();
    }

    /** One store as the contract shows it (the answer of GET, POST, PUT and switch-off, and {@code current} of a 409). */
    Optional<PharmacyViews.Store> one(UUID tenantId, UUID storeId) {
        return all(tenantId, true).stream().filter(v -> v.id().equals(storeId)).findFirst();
    }

    private List<PharmacyViews.Store> assemble(UUID tenantId, List<Store> every) {
        Map<UUID, Store> byId = every.stream().collect(Collectors.toMap(Store::id, s -> s));
        Map<UUID, List<StoreStaff>> mappings = staff.activeForStores(tenantId, byId.keySet()).stream()
                .collect(Collectors.groupingBy(StoreStaff::storeId));
        Set<UUID> memberIds = new HashSet<>();
        mappings.values().forEach(rows -> rows.forEach(row -> memberIds.add(row.membershipId())));
        Map<UUID, TeamMember> members = memberIds.isEmpty() ? Map.of() : team.findAll(tenantId, memberIds);
        Map<UUID, UUID> linked = stores.linkedWards(tenantId);
        Map<UUID, String> wardNames = linked.isEmpty() ? Map.of() : wardNames(tenantId);
        return every.stream().map(store -> view(store, byId, mappings.getOrDefault(store.id(), List.of()), members,
                        linked.get(store.id()), wardNames))
                .toList();
    }

    /** Ward names come from the IPD reader; while no reader is connected (phase 1) the name is a plain "Linked ward". */
    private Map<UUID, String> wardNames(UUID tenantId) {
        try {
            return wardReader.wards(tenantId).stream()
                    .collect(Collectors.toMap(AdmittedPatientsReader.Ward::wardId, AdmittedPatientsReader.Ward::name, (a, b) -> a));
        } catch (IntegrationOffException off) {
            return Map.of();
        }
    }

    private PharmacyViews.Store view(Store store, Map<UUID, Store> byId, List<StoreStaff> active,
                                     Map<UUID, TeamMember> members, UUID wardId, Map<UUID, String> wardNames) {
        StoreStaff hodRow = active.stream().filter(row -> row.storeRole() == StoreRole.APPROVER).findFirst().orElse(null);
        StoreStaff delegateRow = active.stream().filter(row -> row.storeRole() == StoreRole.DELEGATE).findFirst()
                .orElse(null);
        StorePerson hod = hodRow == null ? null : person(hodRow, members);
        StoreDelegate delegate = delegateRow == null ? null : delegate(delegateRow, members);
        // a switched-off store keeps its mappings as history but they stop counting (docs/api/pharmacy.yaml Store)
        int staffCount = !store.active() ? 0
                : (int) active.stream().filter(row -> canSignIn(members.get(row.membershipId()))).count();
        Store issuesFrom = store.issuesFromStoreId() == null ? null : byId.get(store.issuesFromStoreId());
        List<String> locked = store.hasStock() ? List.of(LOCKED_TYPE, LOCKED_CODE, LOCKED_ISSUES_FROM) : List.of();
        return new PharmacyViews.Store(store.id(), store.name(), store.code(), store.type(),
                store.issuesFromStoreId(), issuesFrom == null ? null : issuesFrom.name(), store.saleAllowed(),
                store.consumptionAllowed(), hod, delegate, store.active() && hod != null && hod.canSignIn() ? "SET" : "NONE", staffCount,
                store.active() ? "ACTIVE" : "INACTIVE", store.hasStock(), locked, store.version(),
                wardId == null ? null : new PharmacyViews.WardRef(wardId, wardNames.getOrDefault(wardId, "Linked ward")));
    }

    private static StorePerson person(StoreStaff row, Map<UUID, TeamMember> members) {
        TeamMember member = members.get(row.membershipId());
        return new StorePerson(row.membershipId(), name(member), label(member), canSignIn(member));
    }

    private StoreDelegate delegate(StoreStaff row, Map<UUID, TeamMember> members) {
        TeamMember member = members.get(row.membershipId());
        return new StoreDelegate(row.membershipId(), name(member), label(member), canSignIn(member),
                row.delegateFrom(), row.delegateTo(), DelegateState.of(row.delegateFrom(), row.delegateTo(), time.today()));
    }

    static boolean canSignIn(TeamMember member) {
        return member != null && member.active();
    }

    static String name(TeamMember member) {
        return member == null ? "Unknown member" : member.name();
    }

    static String label(TeamMember member) {
        return member == null ? "" : RoleLabels.of(member.role());
    }
}
