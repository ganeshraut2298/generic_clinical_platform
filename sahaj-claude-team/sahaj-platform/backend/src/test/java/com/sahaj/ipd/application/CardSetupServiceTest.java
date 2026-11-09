package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.CardSetupRepository.DraftRow;
import com.sahaj.ipd.application.CardSetupRepository.VersionHead;
import com.sahaj.ipd.application.CardSetupRepository.VersionRow;
import com.sahaj.ipd.application.CardSetupViews.CardDraft;
import com.sahaj.ipd.application.CardSetupViews.CardSetupView;
import com.sahaj.ipd.application.CardSetupViews.CardSetupWrite;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-37 (ADR-015 D2, D5, D9): the card setup service. The repository is an in-memory fake that behaves like the two
 * tables (unique version, one draft per clinic); the clinic lock, the owner check, the audit and the IPD state are
 * mocks so the check order and the audit rows can be verified.
 */
class CardSetupServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-09T05:30:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeRepository repository;
    private WardRepository wards;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private TeamMemberPort team;
    private ActorDirectoryPort actors;
    private CardSetupService service;
    private String ipdState = "on";
    private String memberRole = "OWNER";

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        wards = mock(WardRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        team = mock(TeamMemberPort.class);
        actors = mock(ActorDirectoryPort.class);
        ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
        when(entitlements.forTenant(TENANT)).thenAnswer(call -> new ModuleEntitlement(ipdState, false, 25));
        when(team.findActiveByUserId(eq(TENANT), any())).thenAnswer(call -> Optional.of(
                new TeamMember(UUID.randomUUID(), "Member", memberRole, true, 0L)));
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(OWNER, "Dr Owner"));
        service = new CardSetupService(repository, new CardSetups(repository),
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), wards, ownerGuard, auditLog, team,
                actors);
    }

    // ---- fixtures ----

    private static CardSetup standard() {
        return CardSetupDefaults.standard();
    }

    /** The standard setup with the Ward label typed (a change of the part "fields"). */
    private static CardSetup labelled(String label) {
        List<Section> sections = new ArrayList<>();
        for (Section s : standard().sections()) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement f : s.fields()) {
                fields.add(f.key().equals("ward") ? new FieldPlacement("ward", true, label) : f);
            }
            sections.add(new Section(s.key(), s.on(), fields));
        }
        return new CardSetup("standard", 3, sections, standard().actions());
    }

    private static CardSetup compact(CardSetup base) {
        return new CardSetup("compact", base.cardsPerRow(), base.sections(), base.actions());
    }

    private static JsonNode body(Integer baseVersion, Long draftRevision, CardSetup setup) {
        return JSON.valueToTree(new CardSetupWrite(baseVersion, draftRevision, setup));
    }

    private static CardSetup without(CardSetup setup, String fieldKey) {
        List<Section> sections = new ArrayList<>();
        for (Section s : setup.sections()) {
            List<FieldPlacement> fields = new ArrayList<>(s.fields());
            fields.removeIf(f -> f.key().equals(fieldKey));
            sections.add(new Section(s.key(), s.on(), fields));
        }
        return new CardSetup(setup.layout(), setup.cardsPerRow(), sections, setup.actions());
    }

    private CardSetupView save(Integer base, Long revision, CardSetup setup) {
        return service.saveDraft(TENANT, OWNER, "OWNER", body(base, revision, setup));
    }

    private CardSetupView publish(Integer base, Long revision, CardSetup setup) {
        return service.publish(TENANT, OWNER, "OWNER", body(base, revision, setup));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }

    private static void assertChanged(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ConflictException.class)
                .hasMessage("The card setup was changed by someone else. Reload to see it.")
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("card-setup-changed");
    }

    // ---- read ----

    @Test
    void aClinicThatNeverPublishedSeesTheStandardCardAsVersionOneAndTheOwnerGetsEmptyDraftAndOneVersion() {
        CardSetupView view = service.view(TENANT, OWNER, "OWNER");

        assertThat(view.live().version()).isEqualTo(1);
        assertThat(view.live().isStandard()).isTrue();
        assertThat(view.live().setup()).isEqualTo(standard());
        assertThat(view.live().publishedAt()).isNull();
        assertThat(view.live().publishedByName()).isNull();
        assertThat(view.standard()).isEqualTo(standard());
        assertThat(view.templates()).hasSize(3);
        assertThat(view.catalogue().fields()).hasSize(25);
        assertThat(view.draft()).isEqualTo(Optional.empty());
        assertThat(view.versions()).hasSize(1);
        assertThat(view.versions().get(0).version()).isEqualTo(1);
        assertThat(view.versions().get(0).summary()).isEqualTo("Sahaj standard patient card (default)");
        assertThat(view.versions().get(0).live()).isTrue();
        assertThat(view.canEdit()).isTrue();
        assertThat(view.readOnlyReason()).isNull();
        assertThat(view.state()).isEqualTo("on");
        verifyNoInteractions(wards, auditLog);
    }

    @Test
    void theThreeOtherRolesGetTheLiveSetupOnlyAndNoDraftNoVersions() {
        save(1, null, labelled("Wing"));
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR"}) {
            memberRole = role;

            CardSetupView view = service.view(TENANT, UUID.randomUUID(), role);

            assertThat(view.draft()).as(role).isNull();
            assertThat(view.versions()).as(role).isNull();
            assertThat(view.canEdit()).isFalse();
            assertThat(view.readOnlyReason()).isEqualTo("ROLE");
            assertThat(view.live().version()).isEqualTo(1);
            assertThat(view.catalogue()).isNotNull();
        }
    }

    @Test
    void anOwnerTokenWithAMembershipNowNurseGetsTheNonOwnerView() {
        save(1, null, labelled("Wing"));
        memberRole = "NURSE";

        CardSetupView view = service.view(TENANT, OWNER, "OWNER");

        assertThat(view.draft()).isNull();
        assertThat(view.versions()).isNull();
        assertThat(view.canEdit()).isFalse();
    }

    @Test
    void rolesWithoutTheAdmittedListAreRefusedAndModuleOffAnswersModuleOff() {
        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> service.view(TENANT, OWNER, role)).isInstanceOf(AccessDeniedException.class);
        }
        memberRole = "BILLING";
        assertThatThrownBy(() -> service.view(TENANT, OWNER, "OWNER")).isInstanceOf(AccessDeniedException.class);
        memberRole = "OWNER";
        ipdState = "off";
        assertThatThrownBy(() -> service.view(TENANT, OWNER, "OWNER")).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void readOnlyStateStillReadsButOffersNoEdit() {
        ipdState = "readonly";

        CardSetupView view = service.view(TENANT, OWNER, "OWNER");

        assertThat(view.canEdit()).isFalse();
        assertThat(view.readOnlyReason()).isEqualTo("IPD_READONLY");
        assertThat(view.state()).isEqualTo("readonly");
        assertThat(view.draft()).isNotNull();
    }

    @Test
    void aStoredVersionThatLacksALockedFieldIsRepairedOnReadAndNotRewritten() {
        CardSetup stored = without(compact(standard()), "allergies");
        repository.versions.add(new VersionRow(2, CardSetupJson.write(stored), false, List.of("layout"), OWNER, NOW));

        CardSetupView view = service.view(TENANT, OWNER, "NURSE");

        assertThat(view.live().version()).isEqualTo(2);
        assertThat(view.live().setup().layout()).isEqualTo("compact");
        assertThat(view.live().setup().sections().get(5).fields()).extracting(FieldPlacement::key)
                .contains("allergies");
        assertThat(repository.versions.get(0).setupJson()).isEqualTo(CardSetupJson.write(stored));
    }

    @Test
    void aStoredVersionThatCannotBeReadGivesTheStandardSetup() {
        repository.versions.add(new VersionRow(3, "not json", false, List.of(), OWNER, NOW));

        CardSetups.Live live = new CardSetups(repository).live(TENANT);

        assertThat(live.fallback()).isTrue();
        assertThat(live.setup()).isEqualTo(standard());
        assertThat(live.version()).isEqualTo(3);
        assertThat(live.resolved().fallback()).isTrue();
    }

    // ---- save draft ----

    @Test
    void theFirstSaveStoresRevisionOneAgainstTheLiveVersionAndWritesOneHistoryLine() {
        CardSetupView view = save(1, null, labelled("Wing"));

        assertThat(repository.draft.revision()).isEqualTo(1L);
        assertThat(repository.draft.baseVersion()).isEqualTo(1);
        assertThat(repository.draft.savedBy()).isEqualTo(OWNER);
        CardDraft draft = view.draft().get();
        assertThat(draft.revision()).isEqualTo(1L);
        assertThat(draft.baseVersion()).isEqualTo(1);
        assertThat(draft.changedParts()).containsExactly("fields");
        assertThat(draft.savedByName()).isEqualTo("Dr Owner");
        assertThat(draft.savedAt()).isEqualTo(repository.draft.savedAt());
        assertThat(view.live().version()).isEqualTo(1);

        InOrder order = inOrder(wards, auditLog);
        order.verify(wards).lockTenant(TENANT);
        ArgumentCaptor<Map<String, Object>> before = captor();
        ArgumentCaptor<Map<String, Object>> after = captor();
        order.verify(auditLog).record(eq("IPD_CARD_DRAFT_SAVED"), eq("IPD_CONFIG"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("liveVersion", 1);
        assertThat(after.getValue()).containsEntry("liveVersion", 1).containsEntry("draftRevision", 1L)
                .containsEntry("changedParts", List.of("fields"));
    }

    @Test
    void theHistoryLineHoldsNoLabelAndNoPatientData() {
        save(1, null, labelled("Secret wing name"));

        ArgumentCaptor<Map<String, Object>> after = captor();
        verify(auditLog).record(any(), any(), any(), any(), after.capture());
        assertThat(after.getValue().toString()).doesNotContain("Secret").doesNotContain("Wing");
        assertThat(after.getValue().keySet()).containsExactlyInAnyOrder("liveVersion", "draftRevision",
                "changedParts");
    }

    @Test
    void everySaveBumpsTheRevisionAndKeepsOneRow() {
        save(1, null, labelled("Wing"));
        CardSetupView second = save(1, 1L, labelled("Block"));

        assertThat(repository.draft.revision()).isEqualTo(2L);
        assertThat(second.draft().get().revision()).isEqualTo(2L);
        assertThat(repository.draftWrites).isEqualTo(2);
    }

    @Test
    void aSetupEqualToTheLiveOneRemovesTheDraftAndWritesNoHistoryLine() {
        save(1, null, labelled("Wing"));

        CardSetupView view = save(1, 1L, standard());

        assertThat(repository.draft).isNull();
        assertThat(view.draft()).isEqualTo(Optional.empty());
        verify(auditLog).record(eq("IPD_CARD_DRAFT_SAVED"), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(auditLog);
    }

    @Test
    void aLabelTypedAndEmptiedAgainIsNotAChange() {
        CardSetupView view = save(1, null, labelled("   "));

        assertThat(repository.draft).isNull();
        assertThat(view.draft()).isEqualTo(Optional.empty());
        verifyNoInteractions(auditLog);
    }

    @Test
    void aStaleLiveVersionIsRefusedAndNothingIsStored() {
        publish(1, null, labelled("Wing"));

        assertChanged(() -> save(1, null, labelled("Block")));

        assertThat(repository.draft).isNull();
    }

    @Test
    void aStaleDraftRevisionIsRefusedAndTheNewerDraftIsKept() {
        save(1, null, labelled("Wing"));
        save(1, 1L, labelled("Block"));

        assertChanged(() -> save(1, 1L, labelled("Other")));

        assertThat(repository.draft.revision()).isEqualTo(2L);
        assertThat(CardSetupJson.read(repository.draft.setupJson()).get().sections().get(2).fields().get(2).label())
                .isEqualTo("Block");
    }

    @Test
    void aDraftThatAnotherOwnerSavedMeanwhileIsNotOverwrittenByAnEditorThatLoadedNone() {
        save(1, null, labelled("Wing"));

        assertChanged(() -> save(1, null, labelled("Block")));
    }

    @Test
    void anEditorHoldingADraftThatWasDiscardedOrPublishedIsRefused() {
        assertChanged(() -> save(1, 1L, labelled("Block")));
    }

    @Test
    void theBaseVersionOfTheStoredDraftIsComparedToo() {
        // The revision restarts at 1 after a discard, so a draft of an older base version must not match.
        repository.draft = new DraftRow(CardSetupJson.write(labelled("Wing")), 2, 1L, OWNER, NOW);

        assertChanged(() -> save(1, 1L, labelled("Block")));
    }

    @Test
    void aClashOnTheDraftKeyOrTheVersionKeyIsAConflictNeverA500() {
        repository.failDraftInsert = true;
        assertChanged(() -> save(1, null, labelled("Wing")));
        repository.failVersionInsert = true;
        assertChanged(() -> publish(1, null, labelled("Wing")));
    }

    // ---- validation: locked items are checked on save and publish ----

    @Test
    void aSetupWithoutALockedFieldIsRefusedOnSaveAndOnPublishWithNothingStoredOrAudited() {
        for (String locked : new String[] {"allergies", "ews", "uhid", "alerts", "name"}) {
            CardSetup bad = without(standard(), locked);

            assertThatThrownBy(() -> save(1, null, bad)).as("save " + locked).isInstanceOf(InvalidFieldsException.class);
            assertThatThrownBy(() -> publish(1, null, bad)).as("publish " + locked)
                    .isInstanceOf(InvalidFieldsException.class);
        }
        assertThat(repository.draft).isNull();
        assertThat(repository.versions).isEmpty();
        verify(wards, never()).lockTenant(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void aHiddenOrRenamedSafetyFieldIsRefusedWithTheContractMessage() {
        List<Section> sections = new ArrayList<>();
        for (Section s : standard().sections()) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement f : s.fields()) {
                fields.add(f.key().equals("allergies") ? new FieldPlacement("allergies", false, "Pets") : f);
            }
            sections.add(new Section(s.key(), s.on(), fields));
        }

        assertThatThrownBy(() -> save(1, null, new CardSetup("standard", 3, sections, standard().actions())))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.firstMessageAsDetail()).isTrue();
                    assertThat(e.errors()).extracting(f -> f.message()).containsExactly(
                            "Allergies is a safety field and cannot be hidden.",
                            "The label of Allergies cannot be changed.");
                    assertThat(e.errors().get(0).field()).isEqualTo("setup.sections[5].fields[1].on");
                });
    }

    @Test
    void allErrorsOfTheBodyComeTogetherWithTheTokens() {
        CardSetup bad = new CardSetup("wide", 7, standard().sections(), standard().actions());

        assertThatThrownBy(() -> save(0, 0L, bad)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).contains("baseVersion", "draftRevision",
                        "setup.layout", "setup.cardsPerRow"));
    }

    @Test
    void aBodyThatIsNotTheDeclaredShapeIs400WithThePath() {
        JsonNode extra = body(1, null, standard());
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("tenantId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.saveDraft(TENANT, OWNER, "OWNER", extra))
                .isInstanceOf(InvalidFieldsException.class);

        JsonNode missing = body(1, null, standard());
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("setup");
        assertThatThrownBy(() -> service.saveDraft(TENANT, OWNER, "OWNER", missing))
                .isInstanceOf(InvalidFieldsException.class);
        assertThat(repository.draft).isNull();
    }

    // ---- check order ----

    @Test
    void theCheckOrderIsRoleThenModuleThenBodyThenReadOnlyThenTheLock() {
        assertThatThrownBy(() -> service.saveDraft(TENANT, OWNER, "NURSE", body(1, null, standard())))
                .isInstanceOf(AccessDeniedException.class);

        ipdState = "off";
        assertThatThrownBy(() -> save(1, null, without(standard(), "uhid"))).isInstanceOf(ModuleOffException.class);

        ipdState = "readonly";
        assertThatThrownBy(() -> save(1, null, without(standard(), "uhid")))
                .as("a bad body is 400 before the Read-only 409").isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> save(1, null, labelled("Wing"))).isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("ipd-readonly");
        assertThatThrownBy(() -> publish(1, null, labelled("Wing"))).isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("ipd-readonly");
        assertThatThrownBy(() -> service.discardDraft(TENANT, OWNER, "OWNER", 1L))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("ipd-readonly");
        verify(wards, never()).lockTenant(any());
    }

    @Test
    void aDemotedOwnerIsRefusedByTheOwnerGuardBeforeAnythingIsRead() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, OWNER);

        assertThatThrownBy(() -> save(1, null, labelled("Wing"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> publish(1, null, labelled("Wing"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.discardDraft(TENANT, OWNER, "OWNER", 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(repository.draft).isNull();
        verifyNoInteractions(auditLog);
    }

    @Test
    void everyWriteAnswersTrialAsEditable() {
        ipdState = "trial";

        assertThat(save(1, null, labelled("Wing")).canEdit()).isTrue();
    }

    // ---- discard ----

    @Test
    void discardRemovesTheDraftKeepsTheLiveSetupAndWritesNoHistoryLine() {
        save(1, null, labelled("Wing"));

        CardSetupView view = service.discardDraft(TENANT, OWNER, "OWNER", 1L);

        assertThat(repository.draft).isNull();
        assertThat(view.draft()).isEqualTo(Optional.empty());
        assertThat(view.live().version()).isEqualTo(1);
        verify(auditLog, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    void discardWithNoDraftIsAnswered200AndNothingElse() {
        CardSetupView view = service.discardDraft(TENANT, OWNER, "OWNER", 5L);

        assertThat(view.draft()).isEqualTo(Optional.empty());
        verifyNoInteractions(auditLog);
    }

    @Test
    void discardWithAnotherRevisionIsAConflictAndTheNewerDraftIsKept() {
        save(1, null, labelled("Wing"));
        save(1, 1L, labelled("Block"));

        assertChanged(() -> service.discardDraft(TENANT, OWNER, "OWNER", 1L));

        assertThat(repository.draft.revision()).isEqualTo(2L);
    }

    @Test
    void discardNeedsARevision() {
        assertThatThrownBy(() -> service.discardDraft(TENANT, OWNER, "OWNER", null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.discardDraft(TENANT, OWNER, "OWNER", 0L))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---- publish ----

    @Test
    void theFirstPublishIsVersionTwoDeletesTheDraftAndWritesOneHistoryLine() {
        save(1, null, labelled("Wing"));

        CardSetupView view = publish(1, 1L, labelled("Wing"));

        assertThat(repository.versions).hasSize(1);
        VersionRow row = repository.versions.get(0);
        assertThat(row.version()).isEqualTo(2);
        assertThat(row.isStandard()).isFalse();
        assertThat(row.changedParts()).containsExactly("fields");
        assertThat(row.publishedBy()).isEqualTo(OWNER);
        assertThat(repository.draft).isNull();
        assertThat(view.live().version()).isEqualTo(2);
        assertThat(view.live().isStandard()).isFalse();
        assertThat(view.live().publishedByName()).isEqualTo("Dr Owner");
        assertThat(view.draft()).isEqualTo(Optional.empty());
        assertThat(view.versions()).extracting(v -> v.version()).containsExactly(2, 1);
        assertThat(view.versions().get(0).summary()).isEqualTo("Changed: fields");
        assertThat(view.versions().get(0).live()).isTrue();
        assertThat(view.versions().get(1).live()).isFalse();

        ArgumentCaptor<Map<String, Object>> before = captor();
        ArgumentCaptor<Map<String, Object>> after = captor();
        verify(auditLog).record(eq("IPD_CARD_PUBLISHED"), eq("IPD_CONFIG"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("version", 1);
        assertThat(after.getValue()).containsEntry("version", 2).containsEntry("isStandard", false)
                .containsEntry("changedParts", List.of("fields"));
        assertThat(after.getValue().toString()).doesNotContain("Wing");
    }

    @Test
    void publishingWithoutAPriorSaveWorksFromTheEditorsOwnBody() {
        CardSetupView view = publish(1, null, compact(standard()));

        assertThat(view.live().version()).isEqualTo(2);
        assertThat(view.live().setup().layout()).isEqualTo("compact");
        assertThat(repository.versions.get(0).changedParts()).containsExactly("layout");
    }

    @Test
    void aSetupEqualToTheLiveOneIsNotPublished() {
        assertThatThrownBy(() -> publish(1, null, standard())).isInstanceOf(ConflictException.class)
                .hasMessage("There are no changes to publish.")
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("card-setup-unchanged");
        assertThat(repository.versions).isEmpty();
        verifyNoInteractions(auditLog);
    }

    @Test
    void aDoubleTapMakesOneVersion() {
        publish(1, null, labelled("Wing"));

        assertChanged(() -> publish(1, null, labelled("Wing")));

        assertThat(repository.versions).hasSize(1);
    }

    @Test
    void aStaleDraftRevisionBlocksPublish() {
        save(1, null, labelled("Wing"));
        save(1, 1L, labelled("Block"));

        assertChanged(() -> publish(1, 1L, labelled("Wing")));

        assertThat(repository.versions).isEmpty();
        assertThat(repository.draft).isNotNull();
    }

    @Test
    void publishingBackToTheStandardCardIsMarkedAndSummarised() {
        publish(1, null, labelled("Wing"));

        CardSetupView view = publish(2, null, standard());

        assertThat(view.live().version()).isEqualTo(3);
        assertThat(view.live().isStandard()).isTrue();
        assertThat(view.versions().get(0).summary()).isEqualTo("Back to the Sahaj standard card");
        assertThat(view.versions().get(0).isStandard()).isTrue();
        assertThat(view.versions()).extracting(v -> v.version()).containsExactly(3, 2, 1);
        assertThat(repository.versions.get(1).isStandard()).isTrue();
    }

    @Test
    void theChangedPartsNameLayoutAndQuickActionsInWords() {
        List<ActionPlacement> actions = new ArrayList<>(standard().actions());
        actions.set(4, new ActionPlacement("deposit", true));
        CardSetup changed = new CardSetup("compact", 3, standard().sections(), actions);

        CardSetupView view = publish(1, null, changed);

        assertThat(view.versions().get(0).summary()).isEqualTo("Changed: layout, quick actions");
    }

    @Test
    void nonOwnersNeverSeeTheOwnersDraftEvenRightAfterASave() {
        save(1, null, labelled("Wing"));
        memberRole = "NURSE";

        CardSetupView view = service.view(TENANT, UUID.randomUUID(), "NURSE");

        assertThat(view.draft()).isNull();
        assertThat(view.live().setup()).isEqualTo(standard());
    }

    @Test
    void theVersionListHoldsAtMostFiftyRowsAndDropsVersionOneWhenFull() {
        for (int version = 2; version <= 60; version++) {
            repository.versions.add(new VersionRow(version, CardSetupJson.write(standard()), true, List.of(), OWNER,
                    NOW));
        }

        CardSetupView view = service.view(TENANT, OWNER, "OWNER");

        assertThat(view.versions()).hasSize(50);
        assertThat(view.versions().get(0).version()).isEqualTo(60);
        assertThat(view.versions().get(49).version()).isEqualTo(11);
    }

    @Test
    void usersWhoseNameCannotBeFoundAreCalledTeamMemberNeverNull() {
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of());
        publish(1, null, labelled("Wing"));

        CardSetupView view = service.view(TENANT, OWNER, "OWNER");

        assertThat(view.versions().get(0).publishedByName()).isEqualTo("Team member");
        assertThat(view.live().publishedByName()).isEqualTo("Team member");
    }

    // ---- in-memory repository ----

    /** The two tables: a unique version per clinic, at most one draft. */
    private static final class FakeRepository implements CardSetupRepository {
        final List<VersionRow> versions = new ArrayList<>();
        DraftRow draft;
        int draftWrites;
        boolean failDraftInsert;
        boolean failVersionInsert;

        @Override
        public Optional<VersionRow> findLatestVersion(UUID tenantId) {
            return versions.stream().max(Comparator.comparingInt(VersionRow::version));
        }

        @Override
        public List<VersionHead> findVersionHeads(UUID tenantId, int limit) {
            return versions.stream().sorted(Comparator.comparingInt(VersionRow::version).reversed()).limit(limit)
                    .map(v -> new VersionHead(v.version(), v.isStandard(), v.changedParts(), v.publishedBy(),
                            v.publishedAt()))
                    .toList();
        }

        @Override
        public void insertVersion(UUID tenantId, int version, String setupJson, boolean isStandard,
                                  List<String> changedParts, UUID publishedBy) {
            if (failVersionInsert || versions.stream().anyMatch(v -> v.version() == version)) {
                throw new DuplicateKeyException("uq_card_setup_version_tenant_version");
            }
            versions.add(new VersionRow(version, setupJson, isStandard, List.copyOf(changedParts), publishedBy, NOW));
        }

        @Override
        public Optional<DraftRow> findDraft(UUID tenantId) {
            return Optional.ofNullable(draft);
        }

        @Override
        public void insertDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy) {
            if (failDraftInsert || draft != null) {
                throw new DuplicateKeyException("card_setup_draft_pkey");
            }
            draftWrites++;
            draft = new DraftRow(setupJson, baseVersion, revision, savedBy, NOW);
        }

        @Override
        public void updateDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy,
                                Instant savedAt) {
            draftWrites++;
            draft = new DraftRow(setupJson, baseVersion, revision, savedBy, savedAt);
        }

        @Override
        public boolean deleteDraft(UUID tenantId) {
            boolean had = draft != null;
            draft = null;
            return had;
        }
    }
}
