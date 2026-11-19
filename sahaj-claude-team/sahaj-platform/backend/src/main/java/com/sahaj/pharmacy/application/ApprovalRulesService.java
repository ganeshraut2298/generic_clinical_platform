package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ApprovalRuleRepository.Version;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.PresetApply;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.RulesSave;
import com.sahaj.pharmacy.application.PharmacyMasterViews.ApprovalRules;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-9: who approves what (docs/api/pharmacy.yaml 0.2.0, ADR-028 D7). The page stores configuration only; enforcement comes
 * with each transaction in SAH-79. Every save writes a new immutable version (rules and levels are never updated) and moves the
 * unit's pointer to it, all in one transaction, so a rule is never half saved. Before the first save the rules are the
 * STANDARD preset with no limits, version 0, "review needed". Sahaj ships no rupee or percentage limit.
 */
@Service
public class ApprovalRulesService {

    static final List<PharmacyMasterViews.FixedRule> FIXED_RULES = List.of(
            new PharmacyMasterViews.FixedRule("NO_SELF_APPROVAL", "Nobody approves their own request."),
            new PharmacyMasterViews.FixedRule("SCHEDULE_X_TWO_SIGNATURES",
                    "Schedule X needs a pharmacist and a second signature."),
            new PharmacyMasterViews.FixedRule("LEDGER_APPEND_ONLY",
                    "The stock ledger cannot be edited; corrections are reversing lines."));
    /** DEC-078 item 3: the server says which types need a limit in each preset, so the client does not guess. */
    static final List<PharmacyMasterViews.PresetInfo> PRESETS = List.of(RulePreset.LEAN, RulePreset.STANDARD, RulePreset.STRICT)
            .stream().map(p -> new PharmacyMasterViews.PresetInfo(p.name(), ApprovalPresets.limitTypes(p).stream()
                    .map(Enum::name).toList())).toList();
    private static final String ENTITY_TYPE = "PHARMACY_APPROVAL_RULES";
    private static final String FORMER_MEMBER = "A former team member";

    private final PharmacyGate gate;
    private final UnitService units;
    private final ApprovalRuleRepository rulesRepository;
    private final StoreRepository stores;
    private final StoreStaffRepository staff;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public ApprovalRulesService(PharmacyGate gate, UnitService units, ApprovalRuleRepository rulesRepository,
                                StoreRepository stores, StoreStaffRepository staff, TeamMemberPort team, AuditLog auditLog,
                                PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.rulesRepository = rulesRepository;
        this.stores = stores;
        this.staff = staff;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    /** The current rules, or the defaults (version 0) before the first save. Writes nothing. */
    @Transactional(readOnly = true)
    public ApprovalRules get(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return currentView(tenantId);
    }

    @Transactional(readOnly = true)
    public PharmacyMasterViews.RuleVersionPage versions(UUID tenantId, UUID userId, String rawPage, String rawSize) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        ApprovalRuleRepository.VersionSlice slice = rulesRepository.versions(tenantId, size, page * size);
        Map<UUID, TeamMember> people = team.findAll(tenantId,
                slice.items().stream().map(Version::createdBy).collect(Collectors.toSet()));
        List<PharmacyMasterViews.RuleVersion> items = slice.items().stream()
                .map(v -> new PharmacyMasterViews.RuleVersion(v.versionNo(), v.preset().name(), v.createdAt(),
                        person(people, v.createdBy())))
                .toList();
        return new PharmacyMasterViews.RuleVersionPage(items, slice.total(), page, size);
    }

    /** One saved version. A versionNo that is not a whole number is 400 bad-request; one that was never saved is 404. */
    @Transactional(readOnly = true)
    public ApprovalRules version(UUID tenantId, UUID userId, String rawVersionNo) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        int versionNo;
        try {
            versionNo = Integer.parseInt(rawVersionNo);
        } catch (NumberFormatException notANumber) {
            throw PharmacyProblemException.badRequest();
        }
        Version version = rulesRepository.version(tenantId, versionNo)
                .orElseThrow(() -> new NotFoundException("This record was not found."));
        return view(tenantId, version, rulesRepository.rules(tenantId, version.id()));
    }

    // ---- PUT /approval-rules ----

    /** For tests: the body is already read. */
    ApprovalRules save(UUID tenantId, UUID userId, RulesSave input) {
        return save(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** All nine rows in one save as a new version with preset CUSTOM; rules identical to the current version write nothing. */
    @Transactional
    public ApprovalRules save(UUID tenantId, UUID userId, PharmacyInputs.Body<RulesSave> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        ApprovalRuleChecks.Rules checked = ApprovalRuleChecks.rules(body.read());
        return write(tenantId, userId, caller, checked.baseVersionNo(), RulePreset.CUSTOM,
                checked.highValueThresholdPaise(), checked.rules(), "PHARMACY_APPROVAL_RULES_SAVED", false);
    }

    // ---- POST /approval-rules/preset ----

    /** For tests: the body is already read. */
    ApprovalRules applyPreset(UUID tenantId, UUID userId, PresetApply input) {
        return applyPreset(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** Replaces all rows with a preset and the owner's limits; the high-value amount stays as it is. */
    @Transactional
    public ApprovalRules applyPreset(UUID tenantId, UUID userId, PharmacyInputs.Body<PresetApply> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        ApprovalRuleChecks.Preset checked = ApprovalRuleChecks.preset(body.read());
        Version current = rulesRepository.current(tenantId).orElse(null);
        return write(tenantId, userId, caller, checked.baseVersionNo(), checked.preset(),
                current == null ? null : current.highValueThresholdPaise(),
                ApprovalPresets.build(checked.preset(), checked.limits()), "PHARMACY_APPROVAL_PRESET_APPLIED", true);
    }

    // ---- the write ----

    /**
     * The one write path. {@code unchangedMeansSamePreset}: a hand save writes nothing when the rules and the high-value
     * amount equal the current version; applying a preset also needs the same preset name to count as unchanged.
     */
    private ApprovalRules write(UUID tenantId, UUID userId, PharmacyCaller caller, long baseVersionNo, RulePreset preset,
                                Long highValue, List<ApprovalRule> rules, String action, boolean unchangedMeansSamePreset) {
        Unit unit = units.ensureUnit(tenantId, userId);
        Version current = rulesRepository.current(tenantId).orElse(null);
        int currentNo = current == null ? 0 : current.versionNo();
        if (baseVersionNo != currentNo) {
            throw PharmacyProblemException.rulesChanged(currentView(tenantId));
        }
        List<ApprovalRule> currentRules = current == null ? ApprovalPresets.defaults()
                : rulesRepository.rules(tenantId, current.id());
        if (current != null && currentRules.equals(rules) && java.util.Objects.equals(current.highValueThresholdPaise(), highValue)
                && (!unchangedMeansSamePreset || current.preset() == preset)) {
            return view(tenantId, current, currentRules);
        }
        UUID membershipId = caller.membershipId();
        int nextNo = currentNo + 1;
        UUID versionId = rulesRepository.insertVersion(tenantId, unit.id(), nextNo, preset, highValue, membershipId);
        for (ApprovalRule rule : rules) {
            UUID ruleId = rulesRepository.insertRule(tenantId, unit.id(), versionId, rule.type(),
                    rule.escalateAfterHours(), rule.urgentEscalateAfterMinutes(), membershipId);
            for (ApprovalLevel level : rule.levels()) {
                rulesRepository.insertLevel(tenantId, unit.id(), ruleId, level, membershipId);
            }
        }
        if (current == null) {
            rulesRepository.insertSet(tenantId, unit.id(), versionId, membershipId);
        } else {
            rulesRepository.updateSet(tenantId, unit.id(), versionId, membershipId);
        }
        auditLog.record(action, ENTITY_TYPE, unit.id(),
                audit(currentNo, current == null ? RulePreset.STANDARD : current.preset(),
                        current == null ? null : current.highValueThresholdPaise(), currentRules),
                audit(nextNo, preset, highValue, rules));
        Version saved = rulesRepository.current(tenantId).orElseThrow();
        return view(tenantId, saved, rulesRepository.rules(tenantId, saved.id()));
    }

    /** Version numbers, the preset and one line per transaction type (fixed text, no typed text). */
    private static Map<String, Object> audit(int versionNo, RulePreset preset, Long highValue, List<ApprovalRule> rules) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("versionNo", versionNo);
        map.put("preset", preset.name());
        map.put("highValueThresholdPaise", highValue);
        for (ApprovalRule rule : rules) {
            map.put(rule.type().name(), rule.describe());
        }
        return map;
    }

    // ---- views ----

    private ApprovalRules currentView(UUID tenantId) {
        Version current = rulesRepository.current(tenantId).orElse(null);
        if (current == null) {
            return view(tenantId, null, ApprovalPresets.defaults());
        }
        return view(tenantId, current, rulesRepository.rules(tenantId, current.id()));
    }

    /** {@code version} is null for the defaults (version 0, review needed). */
    private ApprovalRules view(UUID tenantId, Version version, List<ApprovalRule> rules) {
        List<PharmacyMasterViews.Rule> ruleViews = rules.stream().map(ApprovalRulesService::ruleView).toList();
        boolean storeApprover = rules.stream().flatMap(r -> r.levels().stream())
                .anyMatch(l -> l.approver() == ApproverKind.STORE_APPROVER);
        List<Store> activeStores = stores.list(tenantId, false);
        List<StoreStaff> mappings = activeStores.isEmpty() ? List.of()
                : staff.activeForStores(tenantId, activeStores.stream().map(Store::id).toList());
        LocalDate today = time.today();
        Set<UUID> memberIds = new HashSet<>();
        mappings.stream().filter(m -> m.storeRole() == StoreRole.APPROVER || m.storeRole() == StoreRole.DELEGATE)
                .forEach(m -> memberIds.add(m.membershipId()));
        if (version != null) {
            memberIds.add(version.createdBy());
        }
        Map<UUID, TeamMember> people = memberIds.isEmpty() ? Map.of() : team.findAll(tenantId, memberIds);

        List<PharmacyMasterViews.StoreWithoutApprover> without = new ArrayList<>();
        if (storeApprover) {
            for (Store store : activeStores) {
                boolean hasHod = mappings.stream().anyMatch(m -> m.storeId().equals(store.id())
                        && m.storeRole() == StoreRole.APPROVER && isActive(people, m.membershipId()));
                if (!hasHod) {
                    without.add(new PharmacyMasterViews.StoreWithoutApprover(store.id(), store.name()));
                }
            }
        }
        List<PharmacyMasterViews.CurrentDelegate> delegates = new ArrayList<>();
        for (Store store : activeStores) {
            for (StoreStaff m : mappings) {
                if (m.storeId().equals(store.id()) && m.storeRole() == StoreRole.DELEGATE && m.delegateFrom() != null
                        && m.delegateTo() != null
                        && DelegateState.of(m.delegateFrom(), m.delegateTo(), today) == DelegateState.CURRENT
                        && isActive(people, m.membershipId())) {
                    delegates.add(new PharmacyMasterViews.CurrentDelegate(store.id(), store.name(), m.membershipId(),
                            people.get(m.membershipId()).name(), m.delegateTo()));
                }
            }
        }
        return new ApprovalRules(version == null ? 0 : version.versionNo(),
                version == null ? RulePreset.STANDARD.name() : version.preset().name(), version == null,
                version == null ? null : version.createdAt(), version == null ? null : person(people, version.createdBy()),
                version == null ? null : version.highValueThresholdPaise(), ruleViews, PRESETS, FIXED_RULES, without, delegates);
    }

    private static boolean isActive(Map<UUID, TeamMember> people, UUID membershipId) {
        TeamMember member = people.get(membershipId);
        return member != null && member.active();
    }

    private static PharmacyViews.Person person(Map<UUID, TeamMember> people, UUID membershipId) {
        TeamMember member = people.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? FORMER_MEMBER : member.name());
    }

    private static PharmacyMasterViews.Rule ruleView(ApprovalRule rule) {
        List<PharmacyMasterViews.Level> levels = rule.levels().stream()
                .map(l -> new PharmacyMasterViews.Level(l.level(), l.approver().name(), new PharmacyMasterViews.Condition(
                        l.condition().kind().name(), l.condition().amountPaise(),
                        ProductAssembler.percent(l.condition().percent()),
                        l.condition().storeTypes() == null ? null
                                : l.condition().storeTypes().stream().map(Enum::name).toList())))
                .toList();
        return new PharmacyMasterViews.Rule(rule.type().name(), levels, rule.escalateAfterHours(),
                rule.urgentEscalateAfterMinutes());
    }
}
