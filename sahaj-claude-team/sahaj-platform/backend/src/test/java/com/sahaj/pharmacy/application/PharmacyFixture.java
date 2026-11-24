package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicWebAddressPort;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-memory stand-ins for the repositories and platform ports of the pharmacy module, wired into the real services, so
 * the rules of the services can be tested end to end without a database. They mimic row level security (rows carry their
 * tenant and are only found for the same tenant) but not the database constraints: the services own the rules, and the
 * constraints are tested against PostgreSQL by QA.
 */
final class PharmacyFixture {

    static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
    /** 17 Nov 2026, 11:05 AM IST. */
    static final Instant NOW = Instant.parse("2026-11-17T05:35:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    // ---- people ----

    record Person(UUID userId, TeamMemberPort.TeamMember member) {
        UUID id() {
            return member.membershipId();
        }
    }

    final Map<UUID, Person> people = new LinkedHashMap<>();
    boolean pharmacyOn = true;
    String ipdState = "on";
    String webAddress = "sunrise-baner";
    String clinicName = "Test Clinic Baner";

    Person owner = person("OWNER", "Test Owner One", true);
    Person pharmacist = person("PHARMACIST", "Test Pharmacist One", true);
    Person nurse = person("NURSE", "Test Nurse One", true);
    Person doctor = person("DOCTOR", "Test Doctor One", true);
    Person storeStaff = person("STORE_STAFF", "Test Store Staff One", true);
    Person frontDesk = person("FRONT_DESK", "Test Front Desk One", true);

    Person person(String role, String name, boolean active) {
        UUID userId = UUID.randomUUID();
        Person p = new Person(userId, new TeamMemberPort.TeamMember(UUID.randomUUID(), name, role, active, 0L));
        people.put(p.id(), p);
        return p;
    }

    Person switchOff(Person p) {
        Person off = new Person(p.userId(), new TeamMemberPort.TeamMember(p.id(), p.member().name(), p.member().role(),
                false, 0L));
        people.put(p.id(), off);
        return off;
    }

    Person changeRole(Person p, String role) {
        Person changed = new Person(p.userId(), new TeamMemberPort.TeamMember(p.id(), p.member().name(), role,
                p.member().active(), 0L));
        people.put(p.id(), changed);
        return changed;
    }

    // ---- platform ports ----

    final TeamMemberPort team = new TeamMemberPort() {
        @Override
        public Optional<TeamMember> find(UUID tenantId, UUID membershipId) {
            return tenantId.equals(TENANT) ? Optional.ofNullable(people.get(membershipId)).map(Person::member)
                    : Optional.empty();
        }

        @Override
        public Map<UUID, TeamMember> findAll(UUID tenantId, Collection<UUID> membershipIds) {
            Map<UUID, TeamMember> found = new HashMap<>();
            for (UUID id : membershipIds) {
                find(tenantId, id).ifPresent(m -> found.put(id, m));
            }
            return found;
        }

        @Override
        public List<TeamMember> findActiveDoctors(UUID tenantId) {
            return List.of();
        }

        @Override
        public List<TeamMember> findActiveMembers(UUID tenantId) {
            return people.values().stream().map(Person::member).filter(TeamMember::active)
                    .sorted((a, b) -> Boolean.compare(!"OWNER".equals(a.role()), !"OWNER".equals(b.role())))
                    .collect(Collectors.toList());
        }

        @Override
        public Optional<TeamMember> findActiveByUserId(UUID tenantId, UUID userId) {
            return people.values().stream().filter(p -> p.userId().equals(userId)).map(Person::member)
                    .filter(TeamMember::active).findFirst();
        }

        @Override
        public Optional<String> doctorSpecialty(UUID tenantId, UUID membershipId) {
            return Optional.empty();
        }

        @Override
        public Optional<DoctorDetails> doctorDetails(UUID tenantId, UUID membershipId) {
            return Optional.empty();
        }
    };

    final ModuleEntitlementPort entitlements = new ModuleEntitlementPort() {
        @Override
        public ModuleEntitlement forTenant(UUID tenantId) {
            return new ModuleEntitlement(ipdState, false, 25);
        }

        @Override
        public boolean pharmacyOn(UUID tenantId) {
            return pharmacyOn;
        }
    };

    final ClinicLetterheadPort letterhead = tenantId -> Optional.of(new ClinicLetterheadPort.Letterhead(clinicName, null, null));
    final ClinicWebAddressPort web = tenantId -> Optional.ofNullable(webAddress);

    final List<Audit> audit = new ArrayList<>();

    record Audit(String action, String entityType, UUID entityId, Map<String, Object> before, Map<String, Object> after) {
    }

    final AuditLog auditLog = (action, entityType, entityId, before, after) ->
            audit.add(new Audit(action, entityType, entityId, before, after));

    List<String> auditActions() {
        return audit.stream().map(Audit::action).toList();
    }

    // ---- repositories ----

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final PharmacyTime time = new PharmacyTime(clock);

    final Map<UUID, Unit> units = new HashMap<>();
    final Map<UUID, Held<Store>> storeRows = new LinkedHashMap<>();
    final Map<UUID, Held<StoreStaff>> staffRows = new LinkedHashMap<>();
    final Map<UUID, UUID> preferences = new HashMap<>();
    int vendorCount = 0;
    final List<String> writes = new ArrayList<>();

    record Held<T>(UUID tenantId, T row) {
    }

    final UnitRepository unitRepository = new UnitRepository() {
        @Override
        public Optional<Unit> find(UUID tenantId) {
            return Optional.ofNullable(units.get(tenantId));
        }

        @Override
        public Unit ensureAndLock(UUID tenantId, String code, UUID actorUserId) {
            return units.computeIfAbsent(tenantId, t -> {
                writes.add("unit.insert");
                return new Unit(UUID.randomUUID(), code, null, null, null, IntegrationMode.INTEGRATED, null, 0L);
            });
        }

        @Override
        public boolean updateSettings(UUID tenantId, UUID unitId, long version, String gstin, String drugLicenceNo,
                                      LocalDate till, IntegrationMode mode, UUID actorUserId, SaleSettings sale) {
            Unit unit = units.get(tenantId);
            if (unit == null || !unit.id().equals(unitId) || unit.version() != version) {
                return false;
            }
            writes.add("unit.update");
            units.put(tenantId, new Unit(unit.id(), unit.code(), gstin, drugLicenceNo, till, mode, NOW, version + 1,
                    sale == null ? unit.returnWindowDays() : sale.returnWindowDays(),
                    sale == null ? unit.refundModes() : sale.refundModes(),
                    sale == null ? unit.scheduleXLicenceNo() : sale.scheduleXLicenceNo(),
                    sale == null ? unit.scheduleXLicenceValidTill() : sale.scheduleXLicenceValidTill()));
            return true;
        }
    };

    /** Stores the lock read sees as switched off although the guard's read saw them ACTIVE (the L9 race). */
    final java.util.Set<UUID> inactiveAtLock = new java.util.HashSet<>();

    /** The row locks taken, in order: "store", "product", "product-share", "import", "drafts" (lock order tests). */
    final List<String> locks = new java.util.ArrayList<>();

    /** Which stores have an open (DRAFT or WAITING_APPROVAL) import; the product fixture points it at its import rows. */
    java.util.function.Predicate<UUID> openImportOfStore = id -> false;

    final java.util.Map<UUID, UUID> linkedWardRows = new java.util.HashMap<>();

    final StoreRepository storeRepository = new StoreRepository() {
        @Override
        public boolean hasOpenImport(UUID tenantId, UUID storeId) {
            return openImportOfStore.test(storeId);
        }

        @Override
        public Optional<Store> find(UUID tenantId, UUID id) {
            return Optional.ofNullable(storeRows.get(id)).filter(h -> h.tenantId().equals(tenantId)).map(Held::row);
        }

        @Override
        public Optional<Store> findForUpdate(UUID tenantId, UUID id) {
            locks.add("store");
            // a store switched off between the guard and the lock (DEC-077 L9) is read as INACTIVE here
            return find(tenantId, id).map(s -> inactiveAtLock.contains(id) ? new Store(s.id(), s.unitId(), s.name(),
                    s.code(), s.type(), s.issuesFromStoreId(), s.saleAllowed(), s.consumptionAllowed(), s.hasStock(), false,
                    s.version()) : s);
        }

        @Override
        public List<Store> list(UUID tenantId, boolean includeInactive) {
            return storeRows.values().stream().filter(h -> h.tenantId().equals(tenantId)).map(Held::row)
                    .filter(s -> includeInactive || s.active())
                    .sorted((a, b) -> {
                        int byCentral = Boolean.compare(!a.type().isCentral(), !b.type().isCentral());
                        return byCentral != 0 ? byCentral : a.name().compareToIgnoreCase(b.name());
                    }).collect(Collectors.toList());
        }

        @Override
        public Optional<Store> activeCentral(UUID tenantId) {
            return list(tenantId, false).stream().filter(s -> s.type().isCentral()).findFirst();
        }

        @Override
        public boolean nameInUse(UUID tenantId, String name, UUID except) {
            return list(tenantId, true).stream().anyMatch(s -> s.name().equalsIgnoreCase(name) && !s.id().equals(except));
        }

        @Override
        public boolean codeInUse(UUID tenantId, String code, UUID except) {
            return list(tenantId, true).stream().anyMatch(s -> s.code().equalsIgnoreCase(code) && !s.id().equals(except));
        }

        @Override
        public int countActiveExcept(UUID tenantId, UUID except) {
            return (int) list(tenantId, false).stream().filter(s -> !s.id().equals(except)).count();
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, String name, String code, StoreType type, UUID issuesFrom,
                           boolean sale, boolean consumption, UUID actor) {
            writes.add("store.insert");
            UUID id = UUID.randomUUID();
            storeRows.put(id, new Held<>(tenantId, new Store(id, unitId, name, code, type, issuesFrom, sale,
                    consumption, false, true, 0L)));
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, String name, String code, StoreType type,
                              UUID issuesFrom, boolean sale, boolean consumption, UUID actor) {
            Store s = find(tenantId, id).orElse(null);
            if (s == null || s.version() != version) {
                return false;
            }
            writes.add("store.update");
            storeRows.put(id, new Held<>(tenantId, new Store(id, s.unitId(), name, code, type, issuesFrom, sale,
                    consumption, s.hasStock(), s.active(), version + 1)));
            return true;
        }

        @Override
        public void markHasStock(UUID tenantId, UUID id, UUID actor) {
            writes.add("store.markHasStock");
            Store s = find(tenantId, id).orElseThrow();
            storeRows.put(id, new Held<>(tenantId, new Store(id, s.unitId(), s.name(), s.code(), s.type(),
                    s.issuesFromStoreId(), s.saleAllowed(), s.consumptionAllowed(), true, s.active(), s.version() + 1)));
        }

        @Override
        public java.util.Map<UUID, UUID> linkedWards(UUID tenantId) {
            return new java.util.HashMap<>(linkedWardRows);
        }

        @Override
        public boolean setLinkedWard(UUID tenantId, UUID id, long version, UUID wardId, UUID actor) {
            Store s = find(tenantId, id).orElse(null);
            if (s == null || s.version() != version) {
                return false;
            }
            writes.add("store.linkedWard");
            if (wardId == null) {
                linkedWardRows.remove(id);
            } else {
                linkedWardRows.put(id, wardId);
            }
            storeRows.put(id, new Held<>(tenantId, new Store(id, s.unitId(), s.name(), s.code(), s.type(),
                    s.issuesFromStoreId(), s.saleAllowed(), s.consumptionAllowed(), s.hasStock(), s.active(), version + 1)));
            return true;
        }

        @Override
        public void switchOff(UUID tenantId, UUID id, UUID actor) {
            writes.add("store.switchOff");
            Store s = find(tenantId, id).orElseThrow();
            storeRows.put(id, new Held<>(tenantId, new Store(id, s.unitId(), s.name(), s.code(), s.type(),
                    s.issuesFromStoreId(), s.saleAllowed(), s.consumptionAllowed(), s.hasStock(), false, s.version() + 1)));
        }
    };

    final StoreStaffRepository staffRepository = new StoreStaffRepository() {
        @Override
        public Optional<StoreStaff> find(UUID tenantId, UUID id) {
            return Optional.ofNullable(staffRows.get(id)).filter(h -> h.tenantId().equals(tenantId)).map(Held::row);
        }

        @Override
        public Optional<StoreStaff> findForMember(UUID tenantId, UUID storeId, UUID membershipId) {
            return staffRows.values().stream().filter(h -> h.tenantId().equals(tenantId)).map(Held::row)
                    .filter(r -> r.storeId().equals(storeId) && r.membershipId().equals(membershipId)).findFirst();
        }

        @Override
        public List<StoreStaff> listForStore(UUID tenantId, UUID storeId, boolean includeRemoved) {
            return staffRows.values().stream().filter(h -> h.tenantId().equals(tenantId)).map(Held::row)
                    .filter(r -> r.storeId().equals(storeId) && (includeRemoved || r.active())).toList();
        }

        @Override
        public List<StoreStaff> activeForStores(UUID tenantId, Collection<UUID> storeIds) {
            return staffRows.values().stream().filter(h -> h.tenantId().equals(tenantId)).map(Held::row)
                    .filter(r -> r.active() && storeIds.contains(r.storeId())).toList();
        }

        @Override
        public Optional<StoreStaff> activeWithRole(UUID tenantId, UUID storeId, StoreRole role) {
            return activeForStores(tenantId, List.of(storeId)).stream().filter(r -> r.storeRole() == role).findFirst();
        }

        @Override
        public List<Mapped> activeMappedStores(UUID tenantId, Collection<UUID> membershipIds) {
            List<Mapped> mapped = new ArrayList<>();
            for (Held<StoreStaff> held : staffRows.values()) {
                StoreStaff r = held.row();
                if (!held.tenantId().equals(tenantId) || !r.active() || !membershipIds.contains(r.membershipId())) {
                    continue;
                }
                storeRepository.find(tenantId, r.storeId()).filter(Store::active)
                        .ifPresent(s -> mapped.add(new Mapped(r, s)));
            }
            return mapped;
        }

        @Override
        public boolean hasActiveMapping(UUID tenantId, UUID membershipId) {
            return !activeMappedStores(tenantId, List.of(membershipId)).isEmpty();
        }

        @Override
        public List<UUID> mappedMembersOfActiveStores(UUID tenantId) {
            return activeMappedStores(tenantId, people.keySet()).stream().map(m -> m.staff().membershipId()).distinct()
                    .toList();
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, UUID storeId, UUID membershipId, StoreRole role, LocalDate from,
                           LocalDate to, UUID actor) {
            writes.add("staff.insert");
            UUID id = UUID.randomUUID();
            staffRows.put(id, new Held<>(tenantId, new StoreStaff(id, storeId, membershipId, role, from, to, true, 0L)));
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, StoreRole role, LocalDate from, LocalDate to,
                              boolean active, UUID actor) {
            StoreStaff r = find(tenantId, id).orElse(null);
            if (r == null || r.version() != version) {
                return false;
            }
            writes.add("staff.update");
            staffRows.put(id, new Held<>(tenantId, new StoreStaff(id, r.storeId(), r.membershipId(), role, from, to,
                    active, version + 1)));
            return true;
        }
    };

    final StaffPreferenceRepository preferenceRepository = new StaffPreferenceRepository() {
        @Override
        public Optional<UUID> lastStore(UUID tenantId, UUID membershipId) {
            return Optional.ofNullable(preferences.get(membershipId));
        }

        @Override
        public void save(UUID tenantId, UUID unitId, UUID membershipId, UUID storeId, UUID actor) {
            writes.add("preference.save");
            preferences.put(membershipId, storeId);
        }
    };

    final SetupCountRepository counts = new SetupCountRepository() {
        @Override
        public int activeStores(UUID tenantId) {
            return storeRepository.list(tenantId, false).size();
        }

        @Override
        public int activeVendors(UUID tenantId) {
            return vendorCount;
        }
    };

    // ---- the real services over them ----

    final PharmacyGate gate = new PharmacyGate(entitlements, team, staffRepository, time);
    final UnitService unitService = new UnitService(gate, unitRepository, letterhead, web, entitlements, auditLog, time);
    final StoreAssembler assembler = new StoreAssembler(storeRepository, staffRepository, team, time,
            new NoOpAdmittedPatientsReader());
    final StaffMappingWriter writer = new StaffMappingWriter(staffRepository, auditLog);
    final StoreService storeService = new StoreService(gate, unitService, storeRepository, staffRepository, assembler,
            writer, team, auditLog, time);
    final StaffMappingService staffService = new StaffMappingService(gate, storeRepository, staffRepository, writer, team,
            auditLog, time);
    final RecordingTransactionManager transactions = new RecordingTransactionManager();
    final StoreAccessGuard guard = new StoreAccessGuard(storeRepository);
    final StoreDenialAuditor denialAuditor = new StoreDenialAuditor(auditLog, transactions);
    final ContextService contextService = new ContextService(gate, guard, unitService, unitRepository,
            preferenceRepository, storeRepository);
    final SetupSummaryService summaryService = new SetupSummaryService(gate, counts, staffRepository, team);

    // ---- helpers for the tests ----

    UUID tenant() {
        return TENANT;
    }

    UUID userOf(Person p) {
        return p.userId();
    }

    /** The unit's Central Store, created by the owner. */
    PharmacyViews.Store createCentral() {
        return storeService.create(TENANT, owner.userId(), new PharmacyInputs.StoreCreate("Central Store", "CS",
                StoreType.CENTRAL, null, null, null, null));
    }

    PharmacyViews.Store createStore(String name, String code, StoreType type) {
        return storeService.create(TENANT, owner.userId(), new PharmacyInputs.StoreCreate(name, code, type, null, null,
                null, null));
    }

    PharmacyViews.StaffMapping map(PharmacyViews.Store store, Person p, StoreRole role) {
        return staffService.map(TENANT, owner.userId(), store.id(), new PharmacyInputs.MapStaff(p.id(), role, null, null));
    }

    /** Puts a row straight into the fake store, bypassing the services (for states the services cannot reach). */
    void putStore(Store store) {
        storeRows.put(store.id(), new Held<>(TENANT, store));
    }

    void putStaff(StoreStaff row) {
        staffRows.put(row.id(), new Held<>(TENANT, row));
    }
}
