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

import com.sahaj.pharmacy.application.IndentRepository.Line;
import com.sahaj.pharmacy.application.IndentRepository.NewLine;
import com.sahaj.pharmacy.application.IndentRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-9 and 79-10: the indent rules over mocks. The order of the steps, the draft that has no number, the level 0 and waiting
 * paths of submit, who may touch which indent, the approval handler with the approved quantities, and that the actor stored is
 * the membership id and never the user id.
 */
class IndentServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PRODUCT_2 = UUID.randomUUID();
    private static final UUID INDENT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID LINE_2 = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String REDUCED = "approval-reject.quantity-too-high";
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final IndentRepository indents = mock(IndentRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final IndentFlow flow = mock(IndentFlow.class);
    private final IndentService service = new IndentService(gate, guard, stores, products, indents, stock, engine, approvals,
            series, numbers, clients, events, reasons, new DocumentViews(events, stores, team, engine), team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), flow, new BlindCount(mock(StockAuditRepository.class)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of());
    private Store ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, CENTRAL, false, true, true, true, 0);
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false,
            true, true, 0);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(WARD), any())).thenAnswer(i -> ward);
        when(guard.require(any(), eq(CENTRAL), any())).thenReturn(central);
        when(guard.refuse(any(), any(), any())).thenReturn(new StoreAccessDeniedException(ME, StoreCapability.RAISE_INDENT, WARD));
        when(stores.find(TENANT, WARD)).thenAnswer(i -> Optional.of(ward));
        when(stores.find(TENANT, CENTRAL)).thenReturn(Optional.of(central));
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product((UUID) i.getArgument(1), "NONE")));
        when(products.findAll(eq(TENANT), any())).thenAnswer(i -> {
            java.util.Map<UUID, ProductRepository.Full> byId = new java.util.HashMap<>();
            ((java.util.Collection<UUID>) i.getArgument(1)).forEach(id -> byId.put(id, product(id, "NONE")));
            return byId;
        });
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, REDUCED)).thenReturn(true);
        when(numbers.next(TENANT, DocType.INDENT)).thenReturn(12L);
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("DRAFT", 1, ME)));
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("DRAFT", 1, ME)));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(LINE, PRODUCT, 20, null), line(LINE_2, PRODUCT_2, 10, null)));
        when(indents.updateDraft(any(), any(), anyLong(), any(), any())).thenReturn(true);
        when(indents.submit(any(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(true);
        when(indents.cancel(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        when(indents.latestPurchaseRate(eq(TENANT), any())).thenReturn(Optional.of(new BigDecimal("2.5000")));
    }

    private static ProductRepository.Full product(UUID id, String schedule) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test IV Fluid (fake)", null, false,
                "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static Row row(String status, long version, UUID raiser) {
        boolean draft = "DRAFT".equals(status);
        return new Row(INDENT, UNIT, WARD, CENTRAL, draft ? null : 12L, draft ? null : "IND-0012", "URGENT", status, null,
                draft ? null : NOW, null, null, NOW, raiser, version);
    }

    private static Line line(UUID id, UUID product, int requested, Integer approved) {
        return new Line(id, product, 1, requested, approved, null, 0, 0, 0, 0, 0, null, null, null, null);
    }

    private PharmacyInputs.IndentCreate create(String priority, PharmacyInputs.IndentLineInput... lines) {
        return new PharmacyInputs.IndentCreate(KEY, priority, List.of(lines));
    }

    private static PharmacyInputs.IndentLineInput input(UUID product, String qty) {
        return new PharmacyInputs.IndentLineInput(product, new BigDecimal(qty));
    }

    private PharmacyViews.Outcome<IndentViews.Indent> doCreate(PharmacyInputs.IndentCreate in) {
        return service.create(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in));
    }

    private ApprovalSubjectHandler.Decision decision(String decision, List<PharmacyInputs.IndentLineDecision> lines) {
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(INDENT);
        when(request.ruleStoreId()).thenReturn(WARD);
        PharmacyInputs.ApprovalDecisionInput in = new PharmacyInputs.ApprovalDecisionInput(KEY, BigDecimal.ONE, BigDecimal.ZERO,
                decision, null, null, lines, null);
        PharmacyCaller hod = new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test HOD", "NURSE", List.of());
        return new ApprovalSubjectHandler.Decision(TENANT, hod, request, "HOD", in);
    }

    private static PharmacyInputs.IndentLineDecision change(UUID line, String qty, String reason, String note) {
        return new PharmacyInputs.IndentLineDecision(line, new BigDecimal(qty), reason, note);
    }

    // ---- create ----

    @Test
    void aDraftIsWrittenWithNoNumberOneLinePerProductAndTheMembershipAsActor() {
        when(indents.findById(eq(TENANT), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0, ME)));

        PharmacyViews.Outcome<IndentViews.Indent> out = doCreate(create("URGENT", input(PRODUCT, "20"), input(PRODUCT_2, "10")));

        assertThat(out.created()).isTrue();
        InOrder order = Mockito.inOrder(clients, indents, events, audit);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateIndent"), any());
        order.verify(indents).insert(eq(TENANT), eq(UNIT), any(), eq(WARD), eq(CENTRAL), eq("URGENT"), eq(ME));
        ArgumentCaptor<NewLine> lines = ArgumentCaptor.forClass(NewLine.class);
        order.verify(indents, Mockito.times(2)).insertLine(eq(TENANT), eq(UNIT), any(), lines.capture(), eq(ME));
        assertThat(lines.getAllValues()).extracting(NewLine::requestedQty).containsExactly(20, 10);
        assertThat(lines.getAllValues()).extracting(NewLine::sort).containsExactly(1, 2);
        order.verify(events).insert(eq(TENANT), eq(UNIT), eq("INDENT"), any(), eq("CREATED"), eq(WARD), eq(ME), any(), any(), any(),
                any(), any());
        order.verify(audit).record(eq("PHARMACY_INDENT_CREATED"), any(), any(), any(), any());
        verify(numbers, never()).next(any(), any());
        UserIdNeverWritten.assertNone(USER, indents, events, audit);
    }

    @Test
    void theCentralStoreAndAStoreWithNoSupplyingStoreCannotRaiseAnIndent() {
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Owner", "OWNER", List.of());
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.RAISE_INDENT))).thenReturn(central);

        assertThatThrownBy(() -> service.create(TENANT, USER, CENTRAL.toString(),
                PharmacyInputs.Body.of(create("ROUTINE", input(PRODUCT, "1")))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("doc-state");
                    assertThat(e.getMessage()).isEqualTo("The Central Store buys by purchase order.");
                });

        ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, null, false, true, true, true, 0);
        assertThatThrownBy(() -> doCreate(create("ROUTINE", input(PRODUCT, "1"))))
                .isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("This store has no supplying store. Ask your owner."));
        verify(indents, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void everyFieldErrorIsReportedTogetherAndNothingIsWritten() {
        PharmacyInputs.IndentCreate bad = new PharmacyInputs.IndentCreate(null, "SOON", List.of(input(PRODUCT, "20"),
                input(PRODUCT, "5"), input(PRODUCT_2, "0"), input(UUID.randomUUID(), "2.5"), input(null, "3")));

        assertThatThrownBy(() -> doCreate(bad)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                        "clientKey:" + UnitService.RELOAD, "priority:Choose routine or urgent",
                        "lines[1].productId:Already on this indent",
                        "lines[2].requestedQty:Enter a whole number from 1 to 999999",
                        "lines[3].requestedQty:Enter a whole number from 1 to 999999",
                        "lines[4].productId:Choose an active product"));
        assertThatThrownBy(() -> doCreate(create("ROUTINE"))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message()).containsExactly("lines:Add 1 to 200 items"));
        verify(indents, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theSameKeyAnswersTheFirstIndentAgainAndWritesNothing() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyCreateIndent", INDENT)));

        PharmacyViews.Outcome<IndentViews.Indent> out = doCreate(create("ROUTINE", input(PRODUCT, "1")));

        assertThat(out.created()).isFalse();
        verify(indents, never()).insert(any(), any(), any(), any(), any(), any(), any());
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "other", INDENT)));
        assertThatThrownBy(() -> doCreate(create("ROUTINE", input(PRODUCT, "1")))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    // ---- update, discard ----

    @Test
    void anEditKeepsTheLinesOfTheSameProductAddsNewOnesAndRemovesTheRest() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(LINE, PRODUCT, 20, null)));

        service.update(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(new PharmacyInputs.IndentUpdate(
                BigDecimal.ONE, "ROUTINE", List.of(input(PRODUCT_2, "7")))));

        verify(indents).removeLine(TENANT, LINE, ME);
        verify(indents).insertLine(eq(TENANT), eq(UNIT), eq(INDENT), any(), eq(ME));
        verify(indents).updateDraft(TENANT, INDENT, 1L, "ROUTINE", ME);
        verify(events).insert(eq(TENANT), eq(UNIT), eq("INDENT"), eq(INDENT), eq("CHANGED"), eq(WARD), eq(ME), any(), any(), any(),
                any(), any());
    }

    @Test
    void aSubmittedIndentCannotBeEditedAndAStaleVersionIs409Changed() {
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("WAITING_APPROVAL", 3, ME)));
        PharmacyInputs.IndentUpdate stale = new PharmacyInputs.IndentUpdate(new BigDecimal("3"), "ROUTINE", List.of(input(PRODUCT, "1")));

        assertThatThrownBy(() -> service.update(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(stale)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("doc-state");
                    assertThat(e.getMessage()).isEqualTo("A submitted indent cannot be edited.");
                });
        PharmacyInputs.IndentUpdate old = new PharmacyInputs.IndentUpdate(BigDecimal.ONE, "ROUTINE", List.of(input(PRODUCT, "1")));
        assertThatThrownBy(() -> service.update(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(old)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(indents, never()).updateDraft(any(), any(), anyLong(), any(), any());
    }

    @Test
    void aDraftOfAnotherPersonIs404ForEveryWriteAndNothingIsTouched() {
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("DRAFT", 1, UUID.randomUUID())));

        assertThatThrownBy(() -> service.discard(TENANT, USER, WARD.toString(), INDENT.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionInput(BigDecimal.ONE)))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.submit(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, BigDecimal.ONE)))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(TENANT, USER, WARD.toString(), INDENT.toString())).isInstanceOf(NotFoundException.class);
        verify(indents, never()).setStatus(any(), any(), anyLong(), any(), any());
        verify(indents, never()).findForUpdateById(any(), any());
    }

    @Test
    void aDiscardMarksTheDraftDiscardedAndKeepsItsNoNumber() {
        service.discard(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionInput(BigDecimal.ONE)));

        verify(indents).setStatus(TENANT, INDENT, 1L, "DISCARDED", ME);
        verify(numbers, never()).next(any(), any());
        verify(audit).record(eq("PHARMACY_INDENT_DISCARDED"), any(), eq(INDENT), any(), any());
    }

    // ---- submit ----

    private void submit() {
        service.submit(TENANT, USER, WARD.toString(), INDENT.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, BigDecimal.ONE)));
    }

    @Test
    void atLevelZeroTheApprovedQuantityIsTheRequestedOneWrittenBeforeTheHeaderLeavesDraft() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        submit();

        InOrder order = Mockito.inOrder(clients, numbers, indents, events);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacySubmitIndent"), eq(INDENT));
        order.verify(numbers).next(TENANT, DocType.INDENT);
        order.verify(indents).setApproved(TENANT, LINE, 20, null, ME);
        order.verify(indents).setApproved(TENANT, LINE_2, 10, null, ME);
        order.verify(indents).submit(TENANT, INDENT, 1L, "APPROVED", 12L, "IND-0012", ME);
        order.verify(events).insert(eq(TENANT), eq(UNIT), eq("INDENT"), eq(INDENT), eq("SUBMITTED"), eq(WARD), eq(ME), any(), any(),
                any(), any(), any());
        verify(engine, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void whenARuleAppliesTheIndentWaitsForApprovalWithTheUrgentFlagAndTheValueAtTheLatestRate() {
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        UUID requestId = UUID.randomUUID();
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(requestId);

        submit();

        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.INDENT);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(WARD);
        assertThat(subject.getValue().urgent()).isTrue();
        // 20 x 2.5000 + 10 x 2.5000 = 75.00; the dearest line is 50.00
        assertThat(subject.getValue().amountPaise()).isEqualTo(7_500L);
        assertThat(subject.getValue().maxLineValuePaise()).isEqualTo(5_000L);
        verify(indents).submit(TENANT, INDENT, 1L, "WAITING_APPROVAL", 12L, "IND-0012", ME);
        verify(indents).attachRequest(TENANT, INDENT, requestId, ME);
        verify(indents, never()).setApproved(any(), any(), anyInt(), any(), any());
        UserIdNeverWritten.assertNone(USER, indents, events, audit);
    }

    @Test
    void aScheduleXLineMakesTheRuleSeeScheduleX() {
        when(products.find(TENANT, PRODUCT)).thenReturn(Optional.of(product(PRODUCT, "X")));
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        submit();

        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().hasScheduleX()).isTrue();
    }

    @Test
    void onlyADraftCanBeSubmitted() {
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("APPROVED", 1, ME)));

        assertThatThrownBy(this::submit).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verify(numbers, never()).next(any(), any());
    }

    // ---- cancel ----

    private PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> cancelBody() {
        return PharmacyInputs.Body.of(new PharmacyInputs.VersionReasonInput(BigDecimal.ONE, REDUCED, null));
    }

    @Test
    void theRaiserCancelsABeforeDispatchIndentAndTheWaitingRequestIsWithdrawn() {
        UUID requestId = UUID.randomUUID();
        Row waiting = new Row(INDENT, UNIT, WARD, CENTRAL, 12L, "IND-0012", "ROUTINE", "WAITING_APPROVAL", requestId, NOW, null,
                null, NOW, ME, 1);
        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(waiting));
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(waiting));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.id()).thenReturn(requestId);
        when(request.pending()).thenReturn(true);
        when(request.version()).thenReturn(4L);
        when(approvals.findForUpdate(TENANT, requestId)).thenReturn(Optional.of(request));

        service.cancel(TENANT, USER, WARD.toString(), INDENT.toString(), cancelBody());

        InOrder order = Mockito.inOrder(approvals, indents);
        order.verify(approvals).findForUpdate(TENANT, requestId);
        order.verify(indents).findForUpdateById(TENANT, INDENT);
        order.verify(approvals).finish(TENANT, requestId, 4L, "WITHDRAWN", ME);
        order.verify(indents).cancel(TENANT, INDENT, 1L, REDUCED, null, ME);
    }

    @Test
    void anIndentWithAnyDispatchCannotBeCancelledAndAnotherPersonAtTheRequestingStoreIs403() {
        Row approved = row("APPROVED", 1, ME);
        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(approved));
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(approved));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(new Line(LINE, PRODUCT, 1, 20, 20, null, 5, 0, 0, 0, 0, "BACK_ORDER",
                null, null, null)));

        assertThatThrownBy(() -> service.cancel(TENANT, USER, WARD.toString(), INDENT.toString(), cancelBody()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));

        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(row("APPROVED", 1, UUID.randomUUID())));
        assertThatThrownBy(() -> service.cancel(TENANT, USER, WARD.toString(), INDENT.toString(), cancelBody()))
                .isInstanceOf(StoreAccessDeniedException.class);
        verify(indents, never()).cancel(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void aCentralStoreUserCancelsAtTheIssuingStoreButNotFromAnotherStorePath() {
        Row approved = row("APPROVED", 1, UUID.randomUUID());
        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(approved));
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(approved));

        service.cancel(TENANT, USER, CENTRAL.toString(), INDENT.toString(), cancelBody());

        verify(guard).require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE));
        verify(indents).cancel(TENANT, INDENT, 1L, REDUCED, null, ME);
        UUID other = UUID.randomUUID();
        Store elsewhere = new Store(other, UNIT, "Test Other Central", "CS2", StoreType.CENTRAL, null, false, false, true, true, 0);
        when(stores.find(TENANT, other)).thenReturn(Optional.of(elsewhere));
        when(guard.require(any(), eq(other), any())).thenReturn(elsewhere);
        assertThatThrownBy(() -> service.cancel(TENANT, USER, other.toString(), INDENT.toString(), cancelBody()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- lists and reads ----

    @Test
    void theStoreGuardAnswersBeforeAQueryValueIsParsedAndToDispatchNeedsPurchaseAtTheCentralStore() {
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RECEIVE))).thenThrow(new NotFoundException("This record was not found."));

        assertThatThrownBy(() -> service.list(TENANT, USER, WARD.toString(), "RAISED", "NOT_A_STATUS", "abc", "-5"))
                .isInstanceOf(NotFoundException.class);

        when(indents.listToDispatch(any(), any(), any(), anyInt(), anyInt())).thenReturn(new IndentRepository.Slice(List.of(), 0));
        service.list(TENANT, USER, CENTRAL.toString(), "TO_DISPATCH", null, null, null);
        verify(guard).require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE));
        verify(indents).listToDispatch(TENANT, CENTRAL, null, 20, 0);
        assertThatThrownBy(() -> service.list(TENANT, USER, CENTRAL.toString(), "SOMETHING", null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theRaisedListShowsTheViewersOwnDraftsOnlyAndStatusFilterIsPassedOn() {
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RECEIVE))).thenReturn(ward);
        when(indents.listRaised(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new IndentRepository.Slice(List.of(
                new IndentRepository.ListRow(INDENT, "IND-0012", WARD, CENTRAL, 2, "URGENT", "APPROVED", ME, NOW.minusSeconds(3_600))), 1));

        IndentViews.Page page = service.list(TENANT, USER, WARD.toString(), null, "APPROVED", null, null);

        verify(indents).listRaised(TENANT, WARD, ME, "APPROVED", 20, 0);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.number()).isEqualTo("IND-0012");
            assertThat(item.itemCount()).isEqualTo(2);
            assertThat(item.ageMinutes()).isEqualTo(60);
        });
        assertThat(page.total()).isEqualTo(1);
    }

    // ---- the approval handler (79-10) ----

    @Test
    void anIncreaseAboveTheRequestedQuantityNeedsTheLastLevelOrAnOwner() {
        List<ApprovalRepository.Level> twoLevels = List.of(
                new ApprovalRepository.Level(1, com.sahaj.pharmacy.domain.ApproverKind.STORE_APPROVER,
                        com.sahaj.pharmacy.domain.ConditionKind.ALWAYS, true, "Store HOD approval"),
                new ApprovalRepository.Level(2, com.sahaj.pharmacy.domain.ApproverKind.OWNER,
                        com.sahaj.pharmacy.domain.ConditionKind.ALWAYS, true, "Owner approval"));
        when(approvals.levels(any(), any())).thenReturn(twoLevels);
        ApprovalSubjectHandler.Decision atLevelOne = decision("APPROVED",
                List.of(change(LINE, "24", null, null), change(LINE_2, "10", null, null)));
        when(atLevelOne.request().currentLevel()).thenReturn(1);

        assertThatThrownBy(() -> service.validateDecision(atLevelOne)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message())
                        .containsExactly("indentLines:This increase needs the next approver."));

        // a reduction at level 1 is fine
        service.validateDecision(decision("APPROVED", List.of(change(LINE, "15", REDUCED, null), change(LINE_2, "10", null, null))));

        // the last level may raise it
        ApprovalSubjectHandler.Decision atLevelTwo = decision("APPROVED",
                List.of(change(LINE, "24", null, null), change(LINE_2, "10", null, null)));
        when(atLevelTwo.request().currentLevel()).thenReturn(2);
        service.validateDecision(atLevelTwo);

        // an owner may raise it at level 1
        ApprovalSubjectHandler.Decision owner = decision("APPROVED",
                List.of(change(LINE, "24", null, null), change(LINE_2, "10", null, null)));
        when(owner.request().currentLevel()).thenReturn(1);
        ApprovalSubjectHandler.Decision asOwner = new ApprovalSubjectHandler.Decision(TENANT,
                new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Owner", "OWNER", List.of()),
                owner.request(), "OWNER", owner.input());
        service.validateDecision(asOwner);
    }

    @Test
    void anApprovalWithNoQuantitiesNeedsNothingMoreAndAFullListMayChangeQuantities() {
        service.validateDecision(decision("APPROVED", null));
        service.validateDecision(decision("APPROVED", List.of(change(LINE, "24", null, null), change(LINE_2, "10", null, null))));
        service.validateDecision(decision("REJECTED", List.of(change(LINE, "0", null, null))));
    }

    @Test
    void aReducedLineNeedsARemarkAndOtherNeedsANoteEveryLineIsListedOnceAndAllZeroIsRefused() {
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "15", null, null),
                change(LINE_2, "10", null, null))))).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("indentLines[0].remarkReasonKey:Choose a reason"));

        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, "approval-reject.other")).thenReturn(true);
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "15", "approval-reject.other", "ab"),
                change(LINE_2, "10", null, null))))).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).containsExactly("indentLines[0].remarkNote"));

        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "20", null, null)))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("indentLines:List every line once"));
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "20", null, null),
                change(LINE, "20", null, null))))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(UUID.randomUUID(), "20", null, null),
                change(LINE_2, "10", null, null))))).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(f -> f.field()).contains("indentLines[0].indentLineId"));
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "2.5", REDUCED, null),
                change(LINE_2, "10", null, null))))).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(f -> f.field()).contains("indentLines[0].approvedQty"));
        assertThatThrownBy(() -> service.validateDecision(decision("APPROVED", List.of(change(LINE, "0", REDUCED, null),
                change(LINE_2, "0", REDUCED, null))))).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("indentLines:" + IndentService.ALL_ZERO));
    }

    @Test
    void theFinalApprovalSavesTheChosenQuantitiesFillsTheRestWithTheRequestedAndMakesTheIndentApproved() {
        Row waiting = row("WAITING_APPROVAL", 2, ME);
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(waiting));
        ApprovalSubjectHandler.Decision d = decision("APPROVED", List.of(change(LINE, "24", null, null)));

        service.onApproved(d);

        UUID approver = d.approver().actorId();
        verify(indents).setApproved(TENANT, LINE, 24, null, approver);
        verify(indents).setApproved(TENANT, LINE_2, 10, null, approver);
        verify(indents).setStatusLocked(TENANT, INDENT, "WAITING_APPROVAL", "APPROVED", approver);
        // the history says 20 -> 24 by the approver
        verify(events).insert(eq(TENANT), eq(UNIT), eq("INDENT"), eq(INDENT), eq("LINE_CHANGED"), eq(WARD), eq(approver), eq("HOD"),
                eq(LINE), eq(20), eq(24), eq(null));
        UserIdNeverWritten.assertNone(d.approver().userId(), indents, events, audit);
    }

    @Test
    void aReductionIsSavedWithItsRemarkAndAFirstLevelApprovalSavesQuantitiesWithoutChangingTheStatus() {
        Row waiting = row("WAITING_APPROVAL", 2, ME);
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(waiting));
        ApprovalSubjectHandler.Decision d = decision("APPROVED", List.of(change(LINE, "15", REDUCED, null),
                change(LINE_2, "10", null, null)));

        service.onLevelApproved(d);

        UUID approver = d.approver().actorId();
        verify(indents).setApproved(TENANT, LINE, 15, REDUCED, approver);
        verify(indents).setApproved(TENANT, LINE_2, 10, null, approver);
        verify(indents, never()).setStatusLocked(any(), any(), any(), any(), any());
        verify(events).insert(eq(TENANT), eq(UNIT), eq("INDENT"), eq(INDENT), eq("LINE_CHANGED"), eq(WARD), eq(approver), eq("HOD"),
                eq(LINE), eq(20), eq(15), eq(REDUCED));
    }

    @Test
    void aRejectionMakesTheIndentRejectedMovesNoStockAndAnApprovalOfAnIndentThatIsNotWaitingChangesNothing() {
        Row waiting = row("WAITING_APPROVAL", 2, ME);
        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(waiting));
        ApprovalSubjectHandler.Decision d = decision("REJECTED", null);

        service.onRejected(d);

        verify(indents).setStatusLocked(TENANT, INDENT, "WAITING_APPROVAL", "REJECTED", d.approver().actorId());
        verify(stock, never()).insertLedger(any(), any(), any());

        when(indents.findForUpdateById(TENANT, INDENT)).thenReturn(Optional.of(row("CANCELLED", 3, ME)));
        service.onApproved(decision("APPROVED", null));
        verify(indents, never()).setApproved(any(), any(), anyInt(), any(), any());
        verify(indents, Mockito.times(1)).setStatusLocked(any(), any(), any(), any(), any());
    }

    @Test
    void theApprovalRequestIsReadableThroughTheRequestingStoreOrTheIssuingStoresPurchaseCapability() {
        PharmacyCaller mapped = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of(
                new PharmacyCaller.Mapping(ward, null, com.sahaj.pharmacy.domain.StoreRole.STORE_USER,
                        com.sahaj.pharmacy.domain.StoreRules.access("NURSE", com.sahaj.pharmacy.domain.StoreRole.STORE_USER, true))));
        PharmacyCaller csUser = new PharmacyCaller(TENANT, USER, ME, "Test CS User", "STORE_STAFF", List.of(
                new PharmacyCaller.Mapping(central, null, com.sahaj.pharmacy.domain.StoreRole.CENTRAL_STORE_USER,
                        com.sahaj.pharmacy.domain.StoreRules.access("STORE_STAFF",
                                com.sahaj.pharmacy.domain.StoreRole.CENTRAL_STORE_USER, false))));
        PharmacyCaller stranger = new PharmacyCaller(TENANT, USER, ME, "Test Other", "NURSE", List.of());
        ApprovalRepository.Request request = decision("APPROVED", null).request();
        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(row("WAITING_APPROVAL", 2, ME)));

        assertThat(service.canRead(mapped, request)).isTrue();
        assertThat(service.canRead(csUser, request)).isTrue();
        assertThat(service.canRead(stranger, request)).isFalse();
    }

    @Test
    void theSummaryNamesTheNumberBothStoresAndTheItemCountAndIsAnIndentChip() {
        when(indents.findById(TENANT, INDENT)).thenReturn(Optional.of(row("WAITING_APPROVAL", 2, ME)));
        ApprovalRepository.Request request = decision("APPROVED", null).request();

        ApprovalSubjectHandler.Summary summary = service.summary(TENANT, request);

        assertThat(summary.what()).isEqualTo("IND-0012 · Test Ward 3B → Test Central Store · 2 items · Urgent");
        assertThat(summary.chip()).isEqualTo("INDENT");
        assertThat(summary.subjectStoreId()).isEqualTo(WARD);
        assertThat(service.subjectType()).isEqualTo("INDENT");
    }
}
