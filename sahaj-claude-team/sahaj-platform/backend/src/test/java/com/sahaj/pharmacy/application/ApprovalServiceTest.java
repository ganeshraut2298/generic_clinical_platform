package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.ApprovalRepository.Decision;
import com.sahaj.pharmacy.application.ApprovalRepository.Level;
import com.sahaj.pharmacy.application.ApprovalRepository.Request;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ApproverResolver;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * SAH-79-2 (ADR-029 P6): the decision endpoint. Order: the key, the request locked, the raiser 403, the level-1 approver on
 * level 2 403, a stranger 404, stale 409, then the body rules; the handler of the type runs in the same transaction, and a
 * refusal there leaves nothing decided.
 */
class ApprovalServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOD = UUID.randomUUID();
    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID SUBJECT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository requests = mock(ApprovalRepository.class);
    private final ApprovalSubjectHandler handler = mock(ApprovalSubjectHandler.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private ApprovalService service;

    private PharmacyCaller as(UUID membership, String role) {
        return new PharmacyCaller(TENANT, USER, membership, "Test Person", role, List.of());
    }

    private Request request(int level, long version) {
        return new Request(REQUEST_ID, UNIT, TransactionType.STOCK_ADJUSTMENT, "STOCK_ADJUSTMENT", SUBJECT_ID, "ADJ-0001",
                STORE_ID, RAISER, NOW, UUID.randomUUID(), 500_000L, false, false, false, null, null, level, NOW, "PENDING", version);
    }

    private static Level level(int no, boolean applies) {
        return new Level(no, ApproverKind.STORE_APPROVER, ConditionKind.ALWAYS, applies, "Store HOD approval");
    }

    private void setUp(PharmacyCaller caller, Request request, List<Level> levels, List<Decision> decisions,
                       Map<UUID, String> approvers) {
        when(handler.subjectType()).thenReturn("STOCK_ADJUSTMENT");
        when(handler.summary(any(), any())).thenReturn(new ApprovalSubjectHandler.Summary("ADJ-0001", "STOCK_ADJUSTMENT", STORE_ID,
                List.of("REVIEW", "REJECT")));
        service = new ApprovalService(gate, engine, requests, List.of(handler), stores, clients, events, reasons, team, audit,
                new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(requests.findForUpdate(TENANT, REQUEST_ID)).thenReturn(Optional.of(request));
        when(requests.find(TENANT, REQUEST_ID)).thenReturn(Optional.of(request));
        when(requests.levels(TENANT, REQUEST_ID)).thenReturn(levels);
        when(requests.decisions(TENANT, REQUEST_ID)).thenReturn(decisions);
        when(engine.approvers(any(), any(), any(), any())).thenReturn(new ApproverResolver.Result(approvers, false, false));
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(requests.versionNo(any(), any())).thenReturn(1);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, "approval-reject.quantity-too-high")).thenReturn(true);
    }

    private static Map<UUID, String> approvers(UUID... ids) {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (UUID id : ids) {
            map.put(id, "HOD");
        }
        return map;
    }

    private PharmacyInputs.ApprovalDecisionInput input(String decision, int level, long version, String reason,
                                                       List<PharmacyInputs.IndentLineDecision> lines) {
        return new PharmacyInputs.ApprovalDecisionInput(KEY, BigDecimal.valueOf(level), BigDecimal.valueOf(version), decision,
                reason, null, lines, null);
    }

    private StockViews.ApprovalRequest decide(PharmacyInputs.ApprovalDecisionInput in) {
        return service.decide(TENANT, USER, REQUEST_ID.toString(), PharmacyInputs.Body.of(in));
    }

    @Test
    void aRequestIsReadableThroughTheDocumentsOwnReadCapabilityOnlyOtherwiseItIs404() {
        UUID other = UUID.randomUUID();
        setUp(as(other, "STORE_STAFF"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        when(handler.canRead(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.get(TENANT, USER, REQUEST_ID.toString())).isInstanceOf(NotFoundException.class);

        when(handler.canRead(any(), any())).thenReturn(true);
        assertThat(service.get(TENANT, USER, REQUEST_ID.toString())).isNotNull();
    }

    @Test
    void aRequestRaisedBeforeAnyRuleWasSavedHasNoVersionRowAndReadsAsVersionZero() {
        Request raisedUnderTheDefault = new Request(REQUEST_ID, UNIT, TransactionType.STOCK_ADJUSTMENT, "STOCK_ADJUSTMENT",
                SUBJECT_ID, "ADJ-0001", STORE_ID, RAISER, NOW, null, 500_000L, false, false, false, null, null, 1, NOW, "PENDING",
                0);
        setUp(as(OWNER, "OWNER"), raisedUnderTheDefault, List.of(level(1, true)), List.of(), approvers(HOD));
        when(handler.canRead(any(), any())).thenReturn(false);

        StockViews.ApprovalRequest read = service.get(TENANT, USER, REQUEST_ID.toString());

        assertThat(read.ruleVersionNo()).isZero();
        verify(requests, never()).versionNo(any(), any());
    }

    @Test
    void theRaiserTheOwnerAndAPastDeciderReadWithoutTheDocumentCapability() {
        when(handler.canRead(any(), any())).thenReturn(false);
        for (PharmacyCaller reader : List.of(as(RAISER, "STORE_STAFF"), as(OWNER, "OWNER"))) {
            setUp(reader, request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));
            assertThat(service.get(TENANT, USER, REQUEST_ID.toString())).isNotNull();
        }
    }

    @Test
    void aTapOfTheSameKeyThatLostTheRaceAnswersAsTheReplayAndDecidesNothingAgain() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(false);
        // the early read and the read after the request lock see nothing; the key is there once the winner has committed
        when(clients.own(TENANT, HOD, KEY)).thenReturn(Optional.empty(), Optional.empty(),
                Optional.of(new ClientRequestRepository.Taken(HOD, "pharmacyDecideApproval", REQUEST_ID)));

        StockViews.ApprovalRequest answered = decide(input("APPROVED", 1, 0, null, null));

        assertThat(answered).isNotNull();
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(handler, never()).onApproved(any());
    }

    @Test
    void theFinalApprovalFinishesTheRequestAndLetsTheDocumentProceedInTheSameTransaction() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        decide(input("APPROVED", 1, 0, null, null));

        org.mockito.InOrder order = Mockito.inOrder(clients, requests, handler, events, audit);
        order.verify(requests).insertDecision(eq(TENANT), eq(UNIT), eq(REQUEST_ID), eq(1), eq("APPROVED"), eq(HOD), eq(RAISER),
                eq("HOD"), any(), any());
        order.verify(requests).finish(TENANT, REQUEST_ID, 0, "APPROVED", HOD);
        order.verify(handler).onApproved(any(ApprovalSubjectHandler.Decision.class));
        order.verify(events).insert(eq(TENANT), eq(UNIT), eq("STOCK_ADJUSTMENT"), eq(SUBJECT_ID), eq("DECIDED"), eq(STORE_ID),
                eq(HOD), eq("HOD"), any(), any(), any(), any());
        order.verify(audit).record(eq("PHARMACY_APPROVAL_DECIDED"), any(), eq(REQUEST_ID), any(), any());
        verify(requests, never()).advanceToLevel(any(), any(), anyLong(), anyInt(), any());
    }

    @Test
    void aLevelOneApprovalOfATwoLevelRequestMovesToLevelTwoAndTheDocumentWaits() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true), level(2, true)), List.of(), approvers(HOD));

        decide(input("APPROVED", 1, 0, null, null));

        verify(requests).advanceToLevel(TENANT, REQUEST_ID, 0, 2, HOD);
        verify(requests, never()).finish(any(), any(), anyLong(), any(), any());
        verify(handler, never()).onApproved(any());
    }

    @Test
    void aLevelTwoThatDidNotApplyIsSkipped() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true), level(2, false)), List.of(), approvers(HOD));

        decide(input("APPROVED", 1, 0, null, null));

        verify(requests).finish(TENANT, REQUEST_ID, 0, "APPROVED", HOD);
        verify(handler).onApproved(any());
    }

    @Test
    void aRejectionNeedsAnActiveReasonFromTheApprovalRejectListAndReturnsTheDocument() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> decide(input("REJECTED", 1, 0, null, null))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message()).containsExactly("reasonKey:Choose a reason"));
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());

        decide(input("REJECTED", 1, 0, "approval-reject.quantity-too-high", null));

        verify(requests).finish(TENANT, REQUEST_ID, 0, "REJECTED", HOD);
        verify(handler).onRejected(any());
        verify(handler, never()).onApproved(any());
    }

    @Test
    void theRaiserCannotApproveTheirOwnRequestEvenAsAnOwnerNobodyIsBypassed() {
        setUp(as(RAISER, "OWNER"), request(1, 0), List.of(level(1, true)), List.of(), approvers(OWNER));

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 0, null, null))).isInstanceOfSatisfying(ForbiddenException.class, e -> {
            assertThat(e.problemType()).isEqualTo("self-approval");
            assertThat(e.getMessage()).isEqualTo("You cannot approve your own request.");
        });
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theRaiserSendingADecisionOnARequestThatIsAlreadyDecidedGets409AlreadyDecidedNotSelfApproval() {
        Request finished = new Request(REQUEST_ID, UNIT, TransactionType.STOCK_ADJUSTMENT, "STOCK_ADJUSTMENT", SUBJECT_ID, "ADJ-0001",
                STORE_ID, RAISER, NOW, UUID.randomUUID(), 500_000L, false, false, false, null, null, 1, NOW, "APPROVED", 2);
        setUp(as(RAISER, "NURSE"), finished, List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 2, null, null))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("already-decided"));
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theLevelOneApproverCannotDecideLevelTwo() {
        Decision first = new Decision(1, "APPROVED", HOD, "HOD", null, null, NOW);
        setUp(as(HOD, "NURSE"), request(2, 1), List.of(level(1, true), level(2, true)), List.of(first), approvers(OWNER));

        assertThatThrownBy(() -> decide(input("APPROVED", 2, 1, null, null))).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Another person must approve the second level."));
    }

    @Test
    void aPersonWhoIsNotAnApproverAnOwnerOrAnEarlierDeciderGets404() {
        setUp(as(UUID.randomUUID(), "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 0, null, null))).isInstanceOf(NotFoundException.class);
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aStaleLevelVersionOrAFinishedRequestIs409AlreadyDecidedWithTheCurrentRequest() {
        setUp(as(HOD, "NURSE"), request(1, 3), List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 2, null, null))).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("already-decided");
            assertThat(e.extensions()).containsKey("current");
        });
        assertThatThrownBy(() -> decide(input("APPROVED", 2, 3, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("already-decided"));
        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void quantitiesAreOnlyForAnIndentAndAHandlerCanRefuseItsOwnBody() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));
        List<PharmacyInputs.IndentLineDecision> lines = List.of(new PharmacyInputs.IndentLineDecision(UUID.randomUUID(),
                BigDecimal.TEN, null, null));

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 0, null, lines))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("indentLines"));
    }

    @Test
    void aBodyThatIsMissingItsKeyLevelVersionOrDecisionIs400() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> service.decide(TENANT, USER, REQUEST_ID.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.ApprovalDecisionInput(null, BigDecimal.valueOf(3), new BigDecimal("1.5"), "MAYBE", null, null,
                        null, null)))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("clientKey", "level", "requestVersion",
                        "decision"));
    }

    @Test
    void aReplayOfTheSameKeyAnswersTheRequestAsItIsAndAKeyOfAnotherCallerOrOperationIs409() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));
        when(clients.own(TENANT, HOD, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(HOD, "pharmacyDecideApproval",
                REQUEST_ID)));

        decide(input("APPROVED", 1, 0, null, null));

        verify(requests, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(handler, never()).onApproved(any());

        when(clients.own(TENANT, HOD, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(HOD, "pharmacyPostOpeningStock",
                REQUEST_ID)));
        assertThatThrownBy(() -> decide(input("APPROVED", 1, 0, null, null))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    @Test
    void aRefusalFromTheHandlerLikeNotEnoughStockPropagatesSoTheWholeDecisionRollsBack() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));
        Mockito.doThrow(PharmacyProblemException.insufficientStock(2, List.of())).when(handler).onApproved(any());

        assertThatThrownBy(() -> decide(input("APPROVED", 1, 0, null, null))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("insufficient-stock"));
        verify(events, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anInboxIsForOwnersHodsAndDelegatesOnly() {
        setUp(as(UUID.randomUUID(), "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        assertThatThrownBy(() -> service.inbox(TENANT, USER, null, null, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theUserIdOfTheDeciderNeverReachesAWriter() {
        setUp(as(HOD, "NURSE"), request(1, 0), List.of(level(1, true)), List.of(), approvers(HOD));

        decide(input("APPROVED", 1, 0, null, null));

        verify(requests).finish(TENANT, REQUEST_ID, 0, "APPROVED", HOD);
        UserIdNeverWritten.assertNone(USER, requests, stores, clients, events, audit);
    }
}
