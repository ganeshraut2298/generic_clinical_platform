package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Delegate;
import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyViews.StoreList;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-3: the stores of a unit. The owner creates, changes and switches off; the Central Store comes first and there is
 * one; every other store issues from it (depth one). Choosing an HOD or delegate maps that person in the same transaction.
 * Every write starts by locking the clinic's unit row, so two writers of the store structure run one after the other and
 * the "one active Central Store" and "Central Store in use" rules cannot be raced.
 */
@Service
public class StoreService {

    static final Pattern CODE = Pattern.compile("^[A-Z0-9-]{1,10}$");
    static final int NAME_MAX = 60;
    static final String RELOAD = "Reload the page and try again.";
    private static final String ENTITY_TYPE = "PHARMACY_STORE";

    private final PharmacyGate gate;
    private final UnitService units;
    private final StoreRepository stores;
    private final StoreStaffRepository staff;
    private final StoreAssembler assembler;
    private final StaffMappingWriter writer;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public StoreService(PharmacyGate gate, UnitService units, StoreRepository stores, StoreStaffRepository staff,
                        StoreAssembler assembler, StaffMappingWriter writer, TeamMemberPort team, AuditLog auditLog,
                        PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.stores = stores;
        this.staff = staff;
        this.assembler = assembler;
        this.writer = writer;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Transactional(readOnly = true)
    StoreList list(UUID tenantId, UUID userId, boolean includeInactive) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return listOf(tenantId, includeInactive);
    }

    /** As above for the raw query value of the request: read after the gate, so a non-viewer gets 403 first. */
    @Transactional(readOnly = true)
    public StoreList list(UUID tenantId, UUID userId, String rawIncludeInactive) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return listOf(tenantId, PharmacyParams.bool("includeInactive", rawIncludeInactive, false));
    }

    private StoreList listOf(UUID tenantId, boolean includeInactive) {
        List<PharmacyViews.Store> items = assembler.all(tenantId, includeInactive);
        UUID central = items.stream().filter(s -> s.type().isCentral() && "ACTIVE".equals(s.status()))
                .map(PharmacyViews.Store::id).findFirst().orElse(null);
        return new StoreList(items, central);
    }

    @Transactional(readOnly = true)
    PharmacyViews.Store get(UUID tenantId, UUID userId, UUID storeId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return assembler.one(tenantId, storeId).orElseThrow(StoreService::notFound);
    }

    /** As above for the raw path id of the request: read after the gate. */
    @Transactional(readOnly = true)
    public PharmacyViews.Store get(UUID tenantId, UUID userId, String rawStoreId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        return assembler.one(tenantId, storeId).orElseThrow(StoreService::notFound);
    }

    /** For tests: the body is already read. */
    PharmacyViews.Store create(UUID tenantId, UUID userId, PharmacyInputs.StoreCreate input) {
        return create(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** The body is read after the gate (module off 403, owner only 403), so a bad body never beats a 403. */
    @Transactional
    public PharmacyViews.Store create(UUID tenantId, UUID userId, PharmacyInputs.Body<PharmacyInputs.StoreCreate> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        PharmacyInputs.StoreCreate input = body.read();
        LocalDate today = time.today();
        Map<UUID, TeamMember> people = people(tenantId, input.hodMembershipId(), input.delegate());
        Checked checked = check(new ArrayList<>(), input.name(), input.code(), input.type(), input.hodMembershipId(),
                input.delegate(), people, today, false, false);

        Unit unit = units.ensureUnit(tenantId, userId);
        Optional<Store> central = stores.activeCentral(tenantId);
        if (checked.type().isCentral() && central.isPresent()) {
            throw centralExists();
        }
        if (!checked.type().isCentral() && central.isEmpty()) {
            throw centralMissing();
        }
        requireFreeNameAndCode(tenantId, checked, null);

        boolean sale = input.saleAllowed() != null ? input.saleAllowed() : checked.type() == StoreType.PHARMACY;
        boolean consumption = input.consumptionAllowed() != null ? input.consumptionAllowed()
                : checked.type() == StoreType.WARD;
        UUID issuesFrom = checked.type().isCentral() ? null : central.orElseThrow().id();
        UUID id = stores.insert(tenantId, unit.id(), checked.name(), checked.code(), checked.type(), issuesFrom, sale,
                consumption, userId);
        auditLog.record("PHARMACY_STORE_CREATED", ENTITY_TYPE, id, null,
                storeValues(checked.name(), checked.code(), checked.type(), sale, consumption, input.hodMembershipId(),
                        input.delegate()));
        Store created = stores.find(tenantId, id).orElseThrow();
        applyPeople(tenantId, userId, unit, created, input.hodMembershipId(), input.delegate());
        return assembler.one(tenantId, id).orElseThrow();
    }

    /** For tests: the body is already read. */
    PharmacyViews.Store update(UUID tenantId, UUID userId, UUID storeId, PharmacyInputs.StoreUpdate input) {
        return update(tenantId, userId, storeId.toString(), PharmacyInputs.Body.of(input));
    }

    /** For tests: the body is already read. */
    PharmacyViews.Store update(UUID tenantId, UUID userId, String rawStoreId, PharmacyInputs.StoreUpdate input) {
        return update(tenantId, userId, rawStoreId, PharmacyInputs.Body.of(input));
    }

    /**
     * The raw path id and the body are read after the gate, in this order: module off 403, owner only 403, then 400 for a
     * path id that is not an id, then 400 for a body that cannot be read, then 404.
     */
    @Transactional
    public PharmacyViews.Store update(UUID tenantId, UUID userId, String rawStoreId,
                                      PharmacyInputs.Body<PharmacyInputs.StoreUpdate> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        return updateIn(tenantId, userId, storeId, body.read());
    }

    private PharmacyViews.Store updateIn(UUID tenantId, UUID userId, UUID storeId, PharmacyInputs.StoreUpdate input) {
        Unit unit = units.ensureUnit(tenantId, userId);
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(StoreService::notFound);
        LocalDate today = time.today();
        StoreStaff currentHod = staff.activeWithRole(tenantId, storeId, StoreRole.APPROVER).orElse(null);
        StoreStaff currentDelegate = staff.activeWithRole(tenantId, storeId, StoreRole.DELEGATE).orElse(null);
        UUID hodId = Sent.valueOf(input.hodMembershipId());
        Delegate delegate = Sent.valueOf(input.delegate());
        Map<UUID, TeamMember> people = people(tenantId, hodId, delegate);
        Checked checked = check(missingFields(input), input.name(), input.code(), input.type(), hodId, delegate, people,
                today, currentHod != null && currentHod.membershipId().equals(hodId),
                sameDelegate(currentDelegate, delegate));

        if (!store.active()) {
            throw new ConflictException("store-inactive", "This store is switched off", "This store is switched off.");
        }
        if (input.version() != store.version()) {
            throw PharmacyProblemException.settingsChanged(assembler.one(tenantId, storeId).orElseThrow());
        }
        boolean typeChanges = checked.type() != store.type();
        boolean codeChanges = !checked.code().equals(store.code());
        if (store.hasStock() && (typeChanges || codeChanges)) {
            List<String> locked = new ArrayList<>();
            if (typeChanges) {
                locked.add(StoreAssembler.LOCKED_TYPE);
            }
            if (codeChanges) {
                locked.add(StoreAssembler.LOCKED_CODE);
            }
            throw PharmacyProblemException.storeHasStockLocked(locked);
        }
        if (codeChanges && stores.hasOpenImport(tenantId, storeId)) {
            // the rows of the file name the store by its code (DEC-071 option A); checked under the store row lock
            throw PharmacyProblemException.openImport();
        }
        UUID issuesFrom = store.issuesFromStoreId();
        if (typeChanges) {
            if (store.type().isCentral()) {
                throw stores.countActiveExcept(tenantId, storeId) > 0 ? centralInUse() : centralMissing();
            }
            if (checked.type().isCentral()) {
                throw centralExists();
            }
        }
        requireFreeNameAndCode(tenantId, checked, storeId);

        boolean sale = input.saleAllowed().value();
        boolean consumption = input.consumptionAllowed().value();
        if (!stores.update(tenantId, storeId, store.version(), checked.name(), checked.code(), checked.type(),
                issuesFrom, sale, consumption, userId)) {
            throw PharmacyProblemException.settingsChanged(assembler.one(tenantId, storeId).orElseThrow());
        }
        Map<String, Object> before = storeValues(store.name(), store.code(), store.type(), store.saleAllowed(),
                store.consumptionAllowed(), currentHod == null ? null : currentHod.membershipId(), currentDelegate);
        Map<String, Object> after = storeValues(checked.name(), checked.code(), checked.type(), sale, consumption,
                hodId, delegate);
        auditLog.record("PHARMACY_STORE_CHANGED", ENTITY_TYPE, storeId, before, after);
        applyPeople(tenantId, userId, unit, store, hodId, delegate);
        return assembler.one(tenantId, storeId).orElseThrow();
    }

    /** Safe to repeat: an INACTIVE store answers 200 and nothing is written. */
    @Transactional
    PharmacyViews.Store switchOff(UUID tenantId, UUID userId, UUID storeId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        return switchOffIn(tenantId, userId, storeId);
    }

    /** As above for the raw path id of the request: read after the gate. */
    @Transactional
    public PharmacyViews.Store switchOff(UUID tenantId, UUID userId, String rawStoreId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        return switchOffIn(tenantId, userId, PharmacyParams.pathId(rawStoreId));
    }

    private PharmacyViews.Store switchOffIn(UUID tenantId, UUID userId, UUID storeId) {
        units.ensureUnit(tenantId, userId);
        Store store = stores.findForUpdate(tenantId, storeId).orElseThrow(StoreService::notFound);
        if (!store.active()) {
            return assembler.one(tenantId, storeId).orElseThrow();
        }
        if (store.hasStock()) {
            throw PharmacyProblemException.storeHasStockSwitchOff();
        }
        if (store.type().isCentral() && stores.countActiveExcept(tenantId, storeId) > 0) {
            throw centralInUse();
        }
        if (stores.hasOpenImport(tenantId, storeId)) {
            // a switched-off store would leave the file unreachable: approve and reject answer 404 for it
            throw PharmacyProblemException.openImport();
        }
        stores.switchOff(tenantId, storeId, userId);
        auditLog.record("PHARMACY_STORE_SWITCHED_OFF", ENTITY_TYPE, storeId, Map.of("status", "ACTIVE"),
                Map.of("status", "INACTIVE"));
        return assembler.one(tenantId, storeId).orElseThrow();
    }

    // ---- HOD and delegate ----

    /**
     * Makes the store's ACTIVE APPROVER and DELEGATE rows what the body says. What goes away is first turned into a plain
     * Store user (kept, audited), then the new ones are mapped, so the one-HOD and one-delegate rules of the database
     * hold at every step. The delegate and the HOD are never the same person (checked before).
     */
    private void applyPeople(UUID tenantId, UUID actor, Unit unit, Store store, UUID hodId, Delegate delegate) {
        StoreStaff hodRow = staff.activeWithRole(tenantId, store.id(), StoreRole.APPROVER).orElse(null);
        StoreStaff delegateRow = staff.activeWithRole(tenantId, store.id(), StoreRole.DELEGATE).orElse(null);
        UUID wantedDelegate = delegate == null ? null : delegate.membershipId();

        boolean hodGoes = hodRow != null && !hodRow.membershipId().equals(hodId);
        if (hodGoes) {
            writer.demote(tenantId, hodRow, actor);
        }
        boolean delegateGoes = delegateRow != null && !delegateRow.membershipId().equals(wantedDelegate);
        if (delegateGoes) {
            writer.demote(tenantId, delegateRow, actor);
        }
        if (hodId != null && (hodRow == null || hodGoes)) {
            writer.assign(tenantId, unit.id(), store.id(), hodId, StoreRole.APPROVER, null, null, actor);
        }
        if (delegate != null && (delegateRow == null || delegateGoes || !sameDates(delegateRow, delegate))) {
            writer.assign(tenantId, unit.id(), store.id(), delegate.membershipId(), StoreRole.DELEGATE, delegate.from(),
                    delegate.to(), actor);
        }
    }

    private static boolean sameDelegate(StoreStaff row, Delegate delegate) {
        return row != null && delegate != null && row.membershipId().equals(delegate.membershipId())
                && sameDates(row, delegate);
    }

    private static boolean sameDates(StoreStaff row, Delegate delegate) {
        return Objects.equals(row.delegateFrom(), delegate.from()) && Objects.equals(row.delegateTo(), delegate.to());
    }

    // ---- checks ----

    private Map<UUID, TeamMember> people(UUID tenantId, UUID hodId, Delegate delegate) {
        Set<UUID> ids = new HashSet<>();
        if (hodId != null) {
            ids.add(hodId);
        }
        if (delegate != null && delegate.membershipId() != null) {
            ids.add(delegate.membershipId());
        }
        return ids.isEmpty() ? Map.of() : team.findAll(tenantId, ids);
    }

    /** The validated, normalised values of a create or a change. */
    private record Checked(String name, String code, StoreType type) {
    }

    /**
     * The fields of a change that the contract requires and the request left out, or sent as null where null is not a
     * value (the two flags). A change carries every field: a missing flag is never read as No and a missing HOD or
     * delegate is never read as none, so a partial change cannot switch off a flag or demote a person.
     */
    private static List<FieldMessage> missingFields(PharmacyInputs.StoreUpdate input) {
        List<FieldMessage> errors = new ArrayList<>();
        if (input.version() == null || input.version() < 0) {
            errors.add(new FieldMessage("version", RELOAD));
        }
        if (Sent.valueOf(input.saleAllowed()) == null) {
            errors.add(new FieldMessage("saleAllowed", UnitService.REQUIRED));
        }
        if (Sent.valueOf(input.consumptionAllowed()) == null) {
            errors.add(new FieldMessage("consumptionAllowed", UnitService.REQUIRED));
        }
        if (Sent.missing(input.hodMembershipId())) {
            errors.add(new FieldMessage("hodMembershipId", UnitService.REQUIRED));
        }
        if (Sent.missing(input.delegate())) {
            errors.add(new FieldMessage("delegate", UnitService.REQUIRED));
        }
        return errors;
    }

    /** All field errors together (400 validation), added to those already found; nothing typed is echoed back. */
    private Checked check(List<FieldMessage> errors, String rawName, String rawCode, StoreType type, UUID hodId,
                          Delegate delegate, Map<UUID, TeamMember> people, LocalDate today, boolean hodUnchanged,
                          boolean delegateUnchanged) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            errors.add(new FieldMessage("name", "Enter the store name"));
        } else if (name.length() > NAME_MAX) {
            errors.add(new FieldMessage("name", "Keep the store name to 60 characters"));
        }
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            errors.add(new FieldMessage("code", "Enter the store code"));
        } else if (!CODE.matcher(code).matches()) {
            errors.add(new FieldMessage("code", "Use up to 10 letters, digits or hyphens"));
        }
        if (type == null) {
            errors.add(new FieldMessage("type", "Choose the store type"));
        }
        if (hodId != null && !hodUnchanged && !mayApprove(people.get(hodId))) {
            errors.add(new FieldMessage("hodMembershipId", DelegateRules.BAD_APPROVER));
        }
        if (delegate != null) {
            if (delegate.membershipId() == null || (!delegateUnchanged && !mayApprove(people.get(delegate.membershipId())))) {
                errors.add(new FieldMessage("delegate.membershipId", DelegateRules.BAD_APPROVER));
            } else if (delegate.membershipId().equals(hodId)) {
                errors.add(new FieldMessage("delegate.membershipId", DelegateRules.SAME_AS_HOD));
            }
            if (delegateUnchanged) {
                // an ended delegation sent back unchanged is not an error: only a changed one is held to today's date
                if (delegate.from() == null) {
                    errors.add(new FieldMessage("delegate.from", "Enter the start date"));
                }
                if (delegate.to() == null) {
                    errors.add(new FieldMessage("delegate.to", "Enter the end date"));
                }
            } else {
                DelegateRules.check(delegate.from(), delegate.to(), today, "delegate.from", "delegate.to", errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(name, code, type);
    }

    private static boolean mayApprove(TeamMember member) {
        return member != null && member.active() && TeamRoles.mayApprove(member.role());
    }

    private void requireFreeNameAndCode(UUID tenantId, Checked checked, UUID exceptStoreId) {
        if (stores.nameInUse(tenantId, checked.name(), exceptStoreId)) {
            throw new ConflictException("store-name-in-use", "Name already used", "That store name is already used");
        }
        if (stores.codeInUse(tenantId, checked.code(), exceptStoreId)) {
            throw new ConflictException("store-code-in-use", "Code already used", "That store code is already used");
        }
    }

    private static Map<String, Object> storeValues(String name, String code, StoreType type, boolean sale,
                                                   boolean consumption, UUID hodId, Delegate delegate) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("code", code);
        values.put("type", type.name());
        values.put("saleAllowed", sale);
        values.put("consumptionAllowed", consumption);
        values.put("hodMembershipId", hodId == null ? null : hodId.toString());
        values.put("delegate", delegate == null ? null : delegate.membershipId() + " "
                + delegate.from() + " to " + delegate.to());
        return values;
    }

    private static Map<String, Object> storeValues(String name, String code, StoreType type, boolean sale,
                                                   boolean consumption, UUID hodId, StoreStaff delegateRow) {
        Delegate delegate = delegateRow == null ? null
                : new Delegate(delegateRow.membershipId(), delegateRow.delegateFrom(), delegateRow.delegateTo());
        return storeValues(name, code, type, sale, consumption, hodId, delegate);
    }

    private static ConflictException centralExists() {
        return new ConflictException("central-store-exists", "Central Store exists",
                "This unit already has a Central Store.");
    }

    private static ConflictException centralMissing() {
        return new ConflictException("central-store-missing", "No Central Store", "Create the Central Store first.");
    }

    private static ConflictException centralInUse() {
        return new ConflictException("central-store-in-use", "Central Store in use",
                "Switch off the other stores first.");
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
