package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ApprovalRepository.Decision;
import com.sahaj.pharmacy.application.ApprovalRepository.Level;
import com.sahaj.pharmacy.application.ApprovalRepository.NewLevel;
import com.sahaj.pharmacy.application.ApprovalRepository.NewRequest;
import com.sahaj.pharmacy.application.ApprovalRepository.Request;
import com.sahaj.pharmacy.application.ApprovalRuleRepository.Version;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.ApproverResolver;
import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The approval engine (SAH-79-2, ADR-029 P6): {@link #plan} and {@link #submit} for the documents' services, and the worked-out
 * "who can decide this now" for the inbox, the request screen and the decision. The rule is read at submit and its levels are
 * copied onto the request, so a later rule change never touches a request (BR-12). If no level applies the document needs no
 * request and proceeds at once (level 0). Escalation and "Needs another approver" are worked out on every read, with no job and
 * no notification (OQ-79-29). Runs inside the caller's transaction.
 */
@Service
public class ApprovalEngine {

    private final ApprovalRuleRepository rules;
    private final ApprovalRepository requests;
    private final StoreRepository stores;
    private final StoreStaffRepository staff;
    private final TeamMemberPort team;
    private final ActiveReasonService reasons;
    private final PharmacyTime time;

    public ApprovalEngine(ApprovalRuleRepository rules, ApprovalRepository requests, StoreRepository stores,
                          StoreStaffRepository staff, TeamMemberPort team, ActiveReasonService reasons, PharmacyTime time) {
        this.rules = rules;
        this.requests = requests;
        this.stores = stores;
        this.staff = staff;
        this.team = team;
        this.reasons = reasons;
        this.time = time;
    }

    /**
     * D-5: before the first save the rules served by {@code GET /approval-rules} are the STANDARD preset (version 0, no limits).
     * The engine applies the same rules, so a purchase order or a stock adjustment is never approved at once only because the
     * owner has not saved yet. It has no row (id null) and none is ever written for it: {@link #submit} stores a null
     * {@code rule_version_id} (migration 1750), which reads as "the served Standard default, version 0".
     */
    static final Version UNSAVED_DEFAULT = new Version(null, 0, RulePreset.STANDARD, null, null, null);

    /**
     * The document as the rule sees it. {@code maxLineValuePaise} is the value of the dearest line (the high-value item test);
     * {@code maxRateRisePercent} is for a receipt rate rise only.
     */
    public record Subject(TransactionType type, String subjectType, UUID subjectId, String subjectNo, UUID ruleStoreId,
                          long amountPaise, boolean hasScheduleX, long maxLineValuePaise, boolean urgent,
                          BigDecimal maxRateRisePercent) {
    }

    /** What submit would write: the version used, each level with its result and text, and the first level that applies. */
    public record Plan(Version version, List<NewLevel> levels, int firstLevel, Integer escalateAfterMinutes,
                       boolean hasHighValueItem) {
    }

    /**
     * Works out the levels that apply to the document, writing nothing. Null means no level applies (no request, level 0, BR-12).
     */
    public Plan plan(UUID tenantId, Subject subject) {
        Version version = rules.current(tenantId).orElse(UNSAVED_DEFAULT);
        List<ApprovalRule> ruleSet = version.id() == null ? ApprovalPresets.defaults() : rules.rules(tenantId, version.id());
        ApprovalRule rule = ruleSet.stream().filter(r -> r.type() == subject.type()).findFirst().orElse(null);
        if (rule == null || rule.levels().isEmpty()) {
            return null;
        }
        StoreType ruleStoreType = stores.find(tenantId, subject.ruleStoreId()).map(s -> s.type()).orElse(null);
        boolean high = version.highValueThresholdPaise() != null && subject.maxLineValuePaise() > version.highValueThresholdPaise();
        ApprovalConditions.Facts facts = new ApprovalConditions.Facts(subject.amountPaise(), subject.hasScheduleX(), high,
                subject.urgent(), subject.maxRateRisePercent(), ruleStoreType);
        List<NewLevel> levels = new ArrayList<>();
        int first = 0;
        for (ApprovalLevel level : rule.levels()) {
            boolean applies = ApprovalConditions.applies(level.condition(), facts);
            ApprovalCondition c = level.condition();
            levels.add(new NewLevel(level.level(), level.approver(), c.kind(), c.amountPaise(), c.percent(),
                    c.storeTypes() == null ? null : c.storeTypes().stream().map(Enum::name).toList(), applies,
                    ApprovalConditions.ruleText(subject.type(), level)));
            if (applies && first == 0) {
                first = level.level();
            }
        }
        if (first == 0) {
            return null;
        }
        Integer escalate = null;
        if (subject.urgent() && rule.urgentEscalateAfterMinutes() != null) {
            escalate = rule.urgentEscalateAfterMinutes();
        } else if (rule.escalateAfterHours() != null) {
            escalate = rule.escalateAfterHours() * 60;
        }
        return new Plan(version, levels, first, escalate, high);
    }

    /** Writes the request and the copies of its levels, in the document's transaction. Returns the request id. */
    public UUID submit(UUID tenantId, UUID unitId, UUID raisedByMembershipId, Subject subject, Plan plan) {
        UUID versionId = plan.version().id(); // null: the served Standard default (version 0) has no stored row (D-5)
        UUID id = requests.insertRequest(tenantId, new NewRequest(unitId, subject.type(), subject.subjectType(),
                subject.subjectId(), subject.subjectNo(), subject.ruleStoreId(), raisedByMembershipId,
                versionId, subject.amountPaise(), subject.hasScheduleX(), plan.hasHighValueItem(), subject.urgent(),
                subject.maxRateRisePercent(), plan.escalateAfterMinutes(), plan.firstLevel()), raisedByMembershipId);
        for (NewLevel level : plan.levels()) {
            requests.insertLevel(tenantId, unitId, id, level, raisedByMembershipId);
        }
        return id;
    }

    // ---- who decides ----

    /** The people who can decide the request now, in the capacity they would decide in. */
    public ApproverResolver.Result approvers(UUID tenantId, Request request, List<Level> levels, List<Decision> decisions) {
        return approvers(tenantId, request, levels, decisions, null);
    }

    /** As above with the owners already loaded (m-8: the inbox loads them once per call); null loads them here. */
    public ApproverResolver.Result approvers(UUID tenantId, Request request, List<Level> levels, List<Decision> decisions,
                                             List<UUID> loadedOwners) {
        if (!request.pending()) {
            return new ApproverResolver.Result(Map.of(), false, false);
        }
        int level = request.currentLevel();
        ApproverKind kind = levels.stream().filter(l -> l.levelNo() == level).map(Level::approver).findFirst()
                .orElse(ApproverKind.OWNER);
        UUID level1Decider = decisions.stream().filter(d -> d.levelNo() == 1 && "APPROVED".equals(d.decision()))
                .map(Decision::decidedBy).findFirst().orElse(null);
        boolean escalated = ApproverResolver.isEscalated(request.escalateAfterMinutes(), request.levelEnteredAt(), time.now());
        UUID hod = null;
        UUID delegate = null;
        if (kind == ApproverKind.STORE_APPROVER) {
            hod = signingMember(tenantId, staff.activeWithRole(tenantId, request.ruleStoreId(), StoreRole.APPROVER).orElse(null));
            delegate = signingMember(tenantId,
                    staff.activeWithRole(tenantId, request.ruleStoreId(), StoreRole.DELEGATE).orElse(null));
        }
        return ApproverResolver.resolve(kind, request.raisedBy(), level1Decider, level, escalated, hod, delegate,
                loadedOwners == null ? owners(tenantId) : loadedOwners);
    }

    /** Every owner who can sign in. */
    public List<UUID> owners(UUID tenantId) {
        return team.findActiveMembers(tenantId).stream().filter(m -> TeamRoles.OWNER.equals(m.role()))
                .map(TeamMember::membershipId).collect(Collectors.toList());
    }

    /**
     * The mapping's member, when the mapping still counts today: the member can sign in, the stored role is still allowed for the
     * team role (else the mapping "needs review"), and a delegate's dates are current.
     */
    private UUID signingMember(UUID tenantId, StoreStaff mapping) {
        if (mapping == null || !mapping.active()) {
            return null;
        }
        Optional<TeamMember> member = team.find(tenantId, mapping.membershipId()).filter(TeamMember::active);
        if (member.isEmpty() || StoreRules.needsReview(member.get().role(), mapping.storeRole())) {
            return null;
        }
        if (mapping.storeRole() == StoreRole.DELEGATE && (mapping.delegateFrom() == null || mapping.delegateTo() == null
                || DelegateState.of(mapping.delegateFrom(), mapping.delegateTo(), time.today()) != DelegateState.CURRENT)) {
            return null;
        }
        return mapping.membershipId();
    }

    // ---- views ----

    /** Why the caller cannot decide now, or null. */
    public String decideLockReason(UUID callerMembershipId, Request request, List<Decision> decisions,
                                   ApproverResolver.Result approvers) {
        if (!request.pending()) {
            return "FINISHED";
        }
        if (callerMembershipId.equals(request.raisedBy())) {
            return "SELF_APPROVAL";
        }
        if (request.currentLevel() == 2 && decisions.stream().anyMatch(d -> d.levelNo() == 1
                && "APPROVED".equals(d.decision()) && d.decidedBy().equals(callerMembershipId))) {
            return "ALREADY_APPROVED_LEVEL_1";
        }
        return approvers.canDecide(callerMembershipId) ? null : "NOT_APPROVER";
    }

    /** The approval of a document as the document's GET embeds it; null when the document has no request (level 0). */
    public StockViews.ApprovalSummary summary(PharmacyCaller caller, String subjectType, UUID subjectId) {
        Request request = requests.findBySubject(caller.tenantId(), subjectType, subjectId).orElse(null);
        if (request == null) {
            return null;
        }
        List<Level> levels = requests.levels(caller.tenantId(), request.id());
        List<Decision> decisions = requests.decisions(caller.tenantId(), request.id());
        ApproverResolver.Result approvers = approvers(caller.tenantId(), request, levels, decisions);
        Level shown = levels.stream().filter(l -> request.pending() ? l.levelNo() == request.currentLevel() : l.applies())
                .reduce((first, second) -> second).orElse(levels.get(0));
        String waitingFor = request.pending() ? (shown.approver() == ApproverKind.OWNER ? "OWNER" : "HOD") : null;
        StockViews.ReasonRef reject = decisions.stream().filter(d -> "REJECTED".equals(d.decision()))
                .map(d -> reasonRef(caller.tenantId(), d.reasonKey(), d.note())).findFirst().orElse(null);
        boolean canDecide = decideLockReason(caller.actorId(), request, decisions, approvers) == null;
        return new StockViews.ApprovalSummary(request.id(), request.version(), request.pending() ? request.currentLevel() : null,
                request.status(), request.currentLevel(), shown.ruleText(),
                waitingFor, approvers.escalated(), approvers.needsAnotherApprover(), canDecide, reject);
    }

    StockViews.ReasonRef reasonRef(UUID tenantId, String reasonKey, String note) {
        if (reasonKey == null) {
            return null;
        }
        return new StockViews.ReasonRef(reasonKey, reasons.labels(tenantId).getOrDefault(reasonKey, reasonKey), note);
    }

    /**
     * "POs above ₹ 50,000 need owner approval": the line of the unit's current rule that has an amount limit, null when the rule
     * has none (or no rule is set). {@code plural} is the document name, for example "POs".
     */
    public String ruleNote(UUID tenantId, TransactionType type, String plural) {
        Version version = rules.current(tenantId).orElse(UNSAVED_DEFAULT);
        List<ApprovalRule> ruleSet = version.id() == null ? ApprovalPresets.defaults() : rules.rules(tenantId, version.id());
        return ruleSet.stream().filter(r -> r.type() == type).findFirst()
                .flatMap(r -> r.levels().stream().filter(l -> l.condition().kind() == ConditionKind.ABOVE_AMOUNT
                        && l.condition().amountPaise() != null).findFirst())
                .map(l -> plural + " above ₹ " + ApprovalConditions.rupees(l.condition().amountPaise()) + " need "
                        + (l.approver() == ApproverKind.OWNER ? "owner" : "HOD") + " approval").orElse(null);
    }

    public Instant now() {
        return time.now();
    }
}
