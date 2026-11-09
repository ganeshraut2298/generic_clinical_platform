package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.CardSetupRepository.DraftRow;
import com.sahaj.ipd.application.CardSetupRepository.VersionHead;
import com.sahaj.ipd.application.CardSetupViews.CardDraft;
import com.sahaj.ipd.application.CardSetupViews.CardLive;
import com.sahaj.ipd.application.CardSetupViews.CardSetupView;
import com.sahaj.ipd.application.CardSetupViews.CardSetupWrite;
import com.sahaj.ipd.application.CardSetupViews.CardVersion;
import com.sahaj.ipd.domain.card.CardAccessRule;
import com.sahaj.ipd.domain.card.CardCatalogue;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetupCanonical;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.ipd.domain.card.CardSetupValidator;
import com.sahaj.ipd.domain.card.CardSetupValidator.Problem;
import com.sahaj.ipd.domain.card.TextRules;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-37 (ADR-015) the patient card setup of Clinic setup: the live setup with its versions, and at most one draft per
 * clinic. Reading is open to the four Admitted-list roles (live setup only); the draft, the versions and every write are
 * the owner's. Check order of a write: role (403 forbidden), module Off (403 module-off), body (400 validation, all
 * errors together, nothing stored), IPD Read-only (409 ipd-readonly), then under the clinic IPD advisory lock the
 * concurrency tokens (409 card-setup-changed). A setup is never trusted from the browser: the locked safety items are
 * validated here on every save and publish, and repaired on read (ADR-015 D3).
 *
 * <p>The audit rows (IPD_CARD_DRAFT_SAVED, IPD_CARD_PUBLISHED) hold version numbers and the names of the changed parts
 * only, never a label and never patient data. Nothing here logs the setup or a label.
 */
@Service
public class CardSetupService {

    static final String FORBIDDEN_READ = "You cannot open the card setup.";
    static final String FORBIDDEN_WRITE = "Only the clinic owner can change the patient card.";
    static final int MAX_VERSIONS = 50;
    static final String V1_SUMMARY = "Sahaj standard patient card (default)";
    static final String BACK_TO_STANDARD = "Back to the Sahaj standard card";
    private static final String RELOAD = "Reload the page and try again.";
    private static final String TEAM_MEMBER = "Team member";
    private static final Set<String> OWNER_ONLY = Set.of("OWNER");

    /** The IPD free-text rule of V12 and the trim, from {@link IpdText}. */
    static final TextRules TEXT = new TextRules() {
        @Override
        public boolean hasControlOrBidi(String text) {
            return IpdText.hasControlOrBidi(text);
        }

        @Override
        public String strip(String text) {
            return IpdText.strip(text);
        }
    };

    private final CardSetupRepository repository;
    private final CardSetups setups;
    private final IpdGate gate;
    private final WardRepository wards;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final TeamMemberPort team;
    private final ActorDirectoryPort actors;

    CardSetupService(CardSetupRepository repository, CardSetups setups, IpdGate gate, WardRepository wards,
                     OwnerGuard ownerGuard, AuditLog auditLog, TeamMemberPort team, ActorDirectoryPort actors) {
        this.repository = repository;
        this.setups = setups;
        this.gate = gate;
        this.wards = wards;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.team = team;
        this.actors = actors;
    }

    // ---- read ----

    /**
     * The catalogue, the standard setup, the templates and the live setup for the four list roles; the draft and the
     * versions for the owner only (token role and membership role both OWNER), absent for everyone else. Allowed in
     * Trial, On and Read-only. Writes nothing.
     */
    @Transactional(readOnly = true)
    public CardSetupView view(UUID tenantId, UUID actor, String role) {
        SafetySupport.requireRole(role, CardAccessRule.LIST_ROLES, FORBIDDEN_READ);
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, CardAccessRule.LIST_ROLES,
                FORBIDDEN_READ);
        boolean owner = "OWNER".equals(role) && "OWNER".equals(memberRole);
        return build(tenantId, ent, owner);
    }

    // ---- write ----

    /**
     * Save draft. A setup equal to the live setup removes any draft and writes no history line (AC8); otherwise the
     * draft is stored with the next revision and one change-history line is written in the same transaction.
     */
    @Transactional
    public CardSetupView saveDraft(UUID tenantId, UUID actor, String role, JsonNode body) {
        Write write = begin(tenantId, actor, role, body);
        Optional<DraftRow> draft = repository.findDraft(tenantId);
        CardSetups.Live live = setups.live(tenantId);
        requireCurrent(write.request, live, draft);
        if (CardSetupCanonical.equal(write.setup, live.setup())) {
            repository.deleteDraft(tenantId);
            return build(tenantId, write.ent, true);
        }
        List<String> parts = CardSetupCanonical.changedParts(live.setup(), write.setup);
        long revision = draft.map(d -> d.revision() + 1).orElse(1L);
        String json = CardSetupJson.write(write.setup);
        try {
            if (draft.isPresent()) {
                repository.updateDraft(tenantId, json, live.version(), revision, actor, gate.now());
            } else {
                repository.insertDraft(tenantId, json, live.version(), revision, actor);
            }
        } catch (DuplicateKeyException e) {
            throw changed();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("liveVersion", live.version());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("liveVersion", live.version());
        after.put("draftRevision", revision);
        after.put("changedParts", parts);
        auditLog.record("IPD_CARD_DRAFT_SAVED", "IPD_CONFIG", null, before, after);
        return build(tenantId, write.ent, true);
    }

    /**
     * Discard draft. No draft: nothing to do (the outcome the owner wanted). A draft with another revision is not
     * discarded (409 card-setup-changed). No history line (OQ-37-9).
     */
    @Transactional
    public CardSetupView discardDraft(UUID tenantId, UUID actor, String role, Long draftRevision) {
        SafetySupport.requireRole(role, OWNER_ONLY, FORBIDDEN_WRITE);
        ownerGuard.requireOwner(tenantId, actor);
        ModuleEntitlement ent = gate.check(tenantId, false);
        if (draftRevision == null || draftRevision < 1) {
            throw new InvalidInputException("draftRevision", RELOAD);
        }
        gate.requireWritable(ent);
        wards.lockTenant(tenantId);
        Optional<DraftRow> draft = repository.findDraft(tenantId);
        if (draft.isPresent()) {
            if (draft.get().revision() != draftRevision) {
                throw changed();
            }
            repository.deleteDraft(tenantId);
        }
        return build(tenantId, ent, true);
    }

    /**
     * Publish the setup of the body (the editor's draft, saved or not) as the next version and delete the draft. Equal
     * to the live setup: 409 card-setup-unchanged. A double tap makes one version: the second request carries the old
     * {@code baseVersion} and is refused, and the unique key {@code (clinic, version)} is the backstop.
     */
    @Transactional
    public CardSetupView publish(UUID tenantId, UUID actor, String role, JsonNode body) {
        Write write = begin(tenantId, actor, role, body);
        Optional<DraftRow> draft = repository.findDraft(tenantId);
        CardSetups.Live live = setups.live(tenantId);
        requireCurrent(write.request, live, draft);
        if (CardSetupCanonical.equal(write.setup, live.setup())) {
            throw new ConflictException("card-setup-unchanged", "Nothing to publish",
                    "There are no changes to publish.");
        }
        int version = live.version() + 1;
        List<String> parts = CardSetupCanonical.changedParts(live.setup(), write.setup);
        boolean standard = CardSetupCanonical.equal(write.setup, CardSetupDefaults.standard());
        try {
            repository.insertVersion(tenantId, version, CardSetupJson.write(write.setup), standard, parts, actor);
        } catch (DuplicateKeyException e) {
            throw changed();
        }
        repository.deleteDraft(tenantId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("version", live.version());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", version);
        after.put("changedParts", parts);
        after.put("isStandard", standard);
        auditLog.record("IPD_CARD_PUBLISHED", "IPD_CONFIG", null, before, after);
        return build(tenantId, write.ent, true);
    }

    // ---- helpers ----

    /** A write that passed role, module, body and Read-only checks and holds the clinic lock. */
    private static final class Write {
        private final ModuleEntitlement ent;
        private final CardSetupWrite request;
        private final CardSetup setup;

        private Write(ModuleEntitlement ent, CardSetupWrite request, CardSetup setup) {
            this.ent = ent;
            this.request = request;
            this.setup = setup;
        }
    }

    /** Role, module, body (400 with every error), Read-only (409), then the clinic lock. */
    private Write begin(UUID tenantId, UUID actor, String role, JsonNode body) {
        SafetySupport.requireRole(role, OWNER_ONLY, FORBIDDEN_WRITE);
        ownerGuard.requireOwner(tenantId, actor);
        ModuleEntitlement ent = gate.check(tenantId, false);
        CardSetupWrite request = CardSetupJson.readRequest(body);
        List<FieldMessage> errors = new ArrayList<>();
        if (request.baseVersion() == null || request.baseVersion() < 1) {
            errors.add(new FieldMessage("baseVersion", RELOAD));
        }
        if (request.draftRevision() != null && request.draftRevision() < 1) {
            errors.add(new FieldMessage("draftRevision", RELOAD));
        }
        if (request.setup() == null) {
            errors.add(new FieldMessage("setup", "This value is missing."));
        } else {
            for (Problem problem : CardSetupValidator.validate(request.setup(), TEXT)) {
                errors.add(new FieldMessage(problem.path(), problem.message()));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        gate.requireWritable(ent);
        wards.lockTenant(tenantId);
        return new Write(ent, request, CardSetupCanonical.canonical(request.setup(), TEXT));
    }

    /**
     * 409 card-setup-changed when the live version is not the one the editor loaded, or a draft exists whose revision
     * (or base version: the revision restarts at 1 after a discard) is not the editor's, or no draft exists while the
     * editor holds one.
     */
    private static void requireCurrent(CardSetupWrite request, CardSetups.Live live, Optional<DraftRow> draft) {
        if (live.version() != request.baseVersion()) {
            throw changed();
        }
        if (draft.isPresent()) {
            DraftRow row = draft.get();
            if (request.draftRevision() == null || row.revision() != request.draftRevision()
                    || row.baseVersion() != request.baseVersion()) {
                throw changed();
            }
        } else if (request.draftRevision() != null) {
            throw changed();
        }
    }

    static ConflictException changed() {
        return new ConflictException("card-setup-changed", "Card setup changed",
                "The card setup was changed by someone else. Reload to see it.");
    }

    /** The answer of every operation, read in the caller's transaction after any write. */
    private CardSetupView build(UUID tenantId, ModuleEntitlement ent, boolean owner) {
        CardSetups.Live live = setups.live(tenantId);
        String state = ent.ipdState();
        boolean readonly = "readonly".equals(state);
        Optional<DraftRow> draftRow = owner ? repository.findDraft(tenantId) : Optional.empty();
        List<VersionHead> heads = owner ? repository.findVersionHeads(tenantId, MAX_VERSIONS) : List.of();
        Set<UUID> ids = new HashSet<>();
        if (live.publishedBy() != null) {
            ids.add(live.publishedBy());
        }
        draftRow.ifPresent(d -> ids.add(d.savedBy()));
        heads.forEach(h -> ids.add(h.publishedBy()));
        Map<UUID, String> names = ids.isEmpty() ? Map.of() : actors.displayNames(tenantId, ids);
        Map<UUID, String> safeNames = new HashMap<>(names);

        CardLive cardLive = new CardLive(live.version(), live.isStandard(), live.setup(), live.publishedAt(),
                live.publishedBy() == null ? null : name(safeNames, live.publishedBy()));
        Optional<CardDraft> draft = null;
        List<CardVersion> versions = null;
        if (owner) {
            draft = draftRow.map(row -> {
                CardSetup setup = CardSetups.draftSetup(row);
                return new CardDraft(row.revision(), row.baseVersion(), setup, row.savedAt(),
                        name(safeNames, row.savedBy()), CardSetupCanonical.changedParts(live.setup(), setup));
            });
            versions = versions(heads, safeNames);
        }
        boolean canEdit = owner && !readonly;
        String reason = canEdit ? null : owner ? "IPD_READONLY" : "ROLE";
        return new CardSetupView(CardCatalogue.catalogue(), CardSetupDefaults.standard(),
                CardSetupDefaults.templates(), cardLive, draft, versions, canEdit, reason, state);
    }

    private static List<CardVersion> versions(List<VersionHead> heads, Map<UUID, String> names) {
        List<CardVersion> list = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            VersionHead head = heads.get(i);
            list.add(new CardVersion(head.version(), summary(head), head.isStandard(), head.changedParts(),
                    head.publishedAt(), name(names, head.publishedBy()), i == 0));
        }
        if (heads.size() < MAX_VERSIONS) {
            list.add(new CardVersion(1, V1_SUMMARY, true, List.of(), null, null, heads.isEmpty()));
        }
        return List.copyOf(list);
    }

    private static String summary(VersionHead head) {
        if (head.isStandard()) {
            return BACK_TO_STANDARD;
        }
        return "Changed: " + words(head.changedParts());
    }

    /** The words of the changed parts: layout, sections, fields, quick actions. */
    static String words(List<String> parts) {
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            words.add(CardSetupCanonical.QUICK_ACTIONS.equals(part) ? "quick actions" : part);
        }
        return String.join(", ", words);
    }

    private static String name(Map<UUID, String> names, UUID userId) {
        String found = names.get(userId);
        return found == null ? TEAM_MEMBER : found;
    }
}
