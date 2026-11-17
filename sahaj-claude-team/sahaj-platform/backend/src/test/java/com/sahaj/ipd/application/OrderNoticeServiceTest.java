package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.OrderNoticeRepository.Cursor;
import com.sahaj.ipd.application.OrderNoticeRepository.Created;
import com.sahaj.ipd.application.OrderNoticeRepository.NoticeRow;
import com.sahaj.ipd.application.OrderNoticeRepository.Stats;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNotice;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeList;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePage;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeSummary;
import com.sahaj.ipd.application.OrderNotices.OpenCount;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-3 (ADR-027 D5): the order inbox. The port writes inside the caller's transaction; the Orders tab, the Safety
 * panel and the summary are built from the notice rows, the order timelines and the clock of the answer (waiting and STAT
 * lines are read-time numbers); acknowledging takes the one admission lock and is idempotent for the same user; the
 * scope guard filters lists and refuses by-id actions; the summary writes nothing; no clinical text reaches an audit row.
 */
class OrderNoticeServiceTest extends MedicineTestBase {

    private static final UUID NOTICE = UUID.randomUUID();

    private OrderNoticeRepository repository;
    private OrderNoticeService service;
    private OrderRow order;

    @BeforeEach
    void setUp() {
        repository = mock(OrderNoticeRepository.class);
        service = new OrderNoticeService(repository, fake, admissions, fake, patients, patientAccess, actors, team,
                ipdAudit, gate, guard, nursingSettings, catalogue);
        memberRoles.put(NURSE, "NURSE");
        order = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
    }

    private NoticeRow row(UUID id, String kind, String priority, Instant createdAt, UUID ackBy, Instant ackAt,
                          int version, boolean liveDose, String orderStatus, String stopReason) {
        return new NoticeRow(id, ADMISSION, order.id(), version, kind, priority, DOCTOR, createdAt, ackBy, ackAt,
                ackBy == null ? null : "TAP", "pcm", orderStatus, version, stopReason, liveDose, PATIENT, "202",
                "ADMITTED");
    }

    private NoticeRow open(UUID id, String kind, int minutesAgo) {
        return row(id, kind, "ROUTINE", fake.now.minusSeconds(minutesAgo * 60L), null, null, 1, false, "ACTIVE", null);
    }

    private void versions(MedicineOrderVersion... list) {
        fake.versionRows.put(order.id(), new ArrayList<>(List.of(list)));
    }

    private MedicineOrderVersion v1(Kind kind, OrderPriority priority) {
        return new MedicineOrderVersion(order.id(), 1, kind, kind == Kind.SCHEDULED ? "od" : null, new BigDecimal("1"),
                "MG", "ORAL", fake.now.minusSeconds(120), null, null, priority, null, null, null, DOCTOR,
                fake.now.minusSeconds(120), null, Verification.NOT_REQUIRED, null, null);
    }

    private MedicineOrderVersion v2() {
        return new MedicineOrderVersion(order.id(), 2, Kind.SCHEDULED, "bd", new BigDecimal("2"), "MG", "ORAL",
                fake.now.minusSeconds(60), null, null, OrderPriority.ROUTINE, null, null, "Dose too low", DOCTOR,
                fake.now.minusSeconds(60), null, Verification.NOT_REQUIRED, null, null);
    }

    private OrderNoticeList forAdmission(String role, UUID who) {
        return service.forAdmission(TENANT, who, role, ADMISSION);
    }

    // ---- the port ----

    @Test
    void theNoticeIsWrittenWithTheKindPriorityAndVersion() {
        when(repository.insert(any(), any(), any(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(new Created(NOTICE, fake.now));

        service.record(TENANT, ADMISSION, OrderRef.medicine(order.id(), 2), NoticeKind.CHANGED, OrderPriority.URGENT,
                DOCTOR);

        verify(repository).insert(TENANT, ADMISSION, order.id(), 2, "CHANGED", "URGENT", DOCTOR);
    }

    @Test
    void aDoseAcknowledgesEveryOpenNoticeOfTheOrder() {
        when(repository.acknowledgeByOrder(TENANT, order.id(), NURSE)).thenReturn(2);

        assertThat(service.acknowledgeByDose(TENANT, order.id(), NURSE)).isEqualTo(2);
    }

    @Test
    void theBadgeNumbersUseTheClinicLimitsAtTheClockOfTheAnswer() {
        UUID other = UUID.randomUUID();
        when(repository.stats(eq(TENANT), anyCollection(), any(), any())).thenReturn(
                Map.of(ADMISSION, new Stats(3, 1, 2, null)));

        Map<UUID, OpenCount> counts = service.openStats(TENANT, List.of(ADMISSION, other), fake.now);

        assertThat(counts).containsOnlyKeys(ADMISSION);
        assertThat(counts.get(ADMISSION)).isEqualTo(new OpenCount(3, 1, 2));
        verify(repository).stats(TENANT, List.of(ADMISSION, other), fake.now.minusSeconds(31 * 60),
                fake.now.minusSeconds(6 * 60));
    }

    @Test
    void theOpenCountsAreTheRepositoryCounts() {
        when(repository.countOpen(TENANT, List.of(ADMISSION))).thenReturn(Map.of(ADMISSION, 4));

        assertThat(service.openCounts(TENANT, List.of(ADMISSION))).containsEntry(ADMISSION, 4);
    }

    // ---- the Orders tab ----

    @Test
    void theTabShowsTheOpenNoticesWithTheWaitingMinutesAndTheOverdueFlag() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        UUID second = UUID.randomUUID();
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(open(NOTICE, "NEW", 31),
                open(second, "HELD", 10)));

        OrderNoticeList list = forAdmission("NURSE", NURSE);

        assertThat(list.open().count()).isEqualTo(2);
        assertThat(list.open().stat()).isZero();
        assertThat(list.open().overdue()).isEqualTo(1);
        assertThat(list.status()).isEqualTo("ADMITTED");
        assertThat(list.state()).isEqualTo("on");
        assertThat(list.canAcknowledge()).isTrue();
        OrderNotice first = list.items().get(0);
        assertThat(first.waitingMinutes()).isEqualTo(31);
        assertThat(first.overdue()).isTrue();
        assertThat(first.canAcknowledge()).isTrue();
        assertThat(first.drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(first.createdByName()).isEqualTo("Dr Meera Joshi");
        assertThat(list.items().get(1).overdue()).isFalse();
        verify(patientAccess).recordOpened(TENANT, PATIENT, NURSE);
    }

    @Test
    void aDoctorReadsTheTabButCannotAcknowledge() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(open(NOTICE, "NEW", 3)));

        OrderNoticeList list = forAdmission("DOCTOR", DOCTOR);

        assertThat(list.canAcknowledge()).isFalse();
        assertThat(list.items().get(0).canAcknowledge()).isFalse();
    }

    @Test
    void thePharmacistAndOtherRolesAreRefusedWithTheContractText() {
        for (String role : new String[] {"PHARMACIST", "FRONT_DESK", "BILLING"}) {
            assertThatThrownBy(() -> forAdmission(role, PHARMACIST)).isInstanceOf(ForbiddenException.class)
                    .hasMessage("You cannot open the order list.");
        }
        verifyNoInteractions(repository, patientAccess);
    }

    @Test
    void aChangedNoticeShowsTheLinesAndTheDoctorsReason() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE), v2());
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(row(NOTICE, "CHANGED", "ROUTINE",
                fake.now.minusSeconds(60), null, null, 2, false, "ACTIVE", null)));

        OrderNotice notice = forAdmission("NURSE", NURSE).items().get(0);

        assertThat(notice.changes()).extracting(l -> l.field()).containsExactly("dose", "frequency");
        assertThat(notice.reason()).isEqualTo("Dose too low");
        assertThat(notice.details().doseAmount()).isEqualByComparingTo("2");
        assertThat(notice.orderVersion()).isEqualTo(2);
        assertThat(notice.toString()).doesNotContain("too low");
    }

    @Test
    void aHeldNoticeShowsTheHoldReasonAndAStoppedNoticeTheStopReason() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        fake.holdRows.put(order.id(), new ArrayList<>(List.of(new OrderHoldInterval(fake.now.minusSeconds(30),
                DOCTOR, "Procedure at noon", null, null))));
        UUID stopped = UUID.randomUUID();
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(
                row(NOTICE, "HELD", "ROUTINE", fake.now.minusSeconds(29), null, null, 1, false, "ON_HOLD", null),
                row(stopped, "STOPPED", "ROUTINE", fake.now.minusSeconds(5), null, null, 1, false, "STOPPED",
                        "Rash after the first dose")));

        OrderNoticeList list = forAdmission("DOCTOR", DOCTOR);

        assertThat(list.items().get(0).reason()).isEqualTo("Procedure at noon");
        assertThat(list.items().get(1).reason()).isEqualTo("Rash after the first dose");
        assertThat(list.items().get(0).changes()).isEmpty();
    }

    @Test
    void anAcknowledgedNoticeShowsWhoAndWhenAndNoWaitingMinutes() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        Instant at = fake.now.minusSeconds(30);
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(row(NOTICE, "NEW", "ROUTINE",
                fake.now.minusSeconds(3600), DOCTOR, at, 1, false, "ACTIVE", null)));

        OrderNotice notice = forAdmission("NURSE", NURSE).items().get(0);

        assertThat(notice.acknowledgedAt()).isEqualTo(at);
        assertThat(notice.acknowledgedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(notice.waitingMinutes()).isNull();
        assertThat(notice.overdue()).isFalse();
        assertThat(notice.canAcknowledge()).isFalse();
    }

    @Test
    void aStatNoticeCarriesTheCountdownUntilADoseIsRecordedOrTheOrderIsStopped() {
        MedicineOrderVersion stat = new MedicineOrderVersion(order.id(), 1, Kind.ONCE, null, new BigDecimal("4"), "MG",
                "IV", fake.now.minusSeconds(5 * 60 + 20), null, null, OrderPriority.STAT, null, null, null, DOCTOR,
                fake.now.minusSeconds(5 * 60 + 20), null, Verification.NOT_REQUIRED, null, null);
        versions(stat);
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(
                row(NOTICE, "NEW", "STAT", fake.now.minusSeconds(361), null, null, 1, false, "ACTIVE", null)));

        OrderNotice notice = forAdmission("NURSE", NURSE).items().get(0);

        assertThat(notice.stat()).isNotNull();
        assertThat(notice.stat().windowMinutes()).isEqualTo(15);
        assertThat(notice.stat().minutesLeft()).isEqualTo(9);
        assertThat(notice.stat().minutesOverdue()).isNull();
        assertThat(notice.overdue()).isTrue();

        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(
                row(NOTICE, "NEW", "STAT", fake.now.minusSeconds(361), null, null, 1, true, "ACTIVE", null)));
        assertThat(forAdmission("NURSE", NURSE).items().get(0).stat()).isNull();

        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(
                row(NOTICE, "NEW", "STAT", fake.now.minusSeconds(361), null, null, 1, false, "STOPPED", "x stop")));
        assertThat(forAdmission("NURSE", NURSE).items().get(0).stat()).isNull();
    }

    @Test
    void aStatLineIsOverdueOncePastTheWindow() {
        MedicineOrderVersion stat = new MedicineOrderVersion(order.id(), 1, Kind.ONCE, null, new BigDecimal("4"), "MG",
                "IV", fake.now.minusSeconds(20 * 60), null, null, OrderPriority.STAT, null, null, null, DOCTOR,
                fake.now.minusSeconds(20 * 60), null, Verification.NOT_REQUIRED, null, null);
        versions(stat);
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(
                row(NOTICE, "NEW", "STAT", fake.now.minusSeconds(20 * 60), null, null, 1, false, "ACTIVE", null)));

        OrderNotice notice = forAdmission("NURSE", NURSE).items().get(0);

        assertThat(notice.stat().minutesLeft()).isNull();
        assertThat(notice.stat().minutesOverdue()).isEqualTo(5);
    }

    @Test
    void aDischargedAdmissionAndReadOnlyIpdListButCannotAcknowledge() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of(open(NOTICE, "NEW", 3)));
        admission = admissionRow("DISCHARGED");
        assertThat(forAdmission("NURSE", NURSE).canAcknowledge()).isFalse();

        admission = admissionRow("ADMITTED");
        ipdState("readonly");
        OrderNoticeList list = forAdmission("NURSE", NURSE);
        assertThat(list.state()).isEqualTo("readonly");
        assertThat(list.canAcknowledge()).isFalse();
        assertThat(list.items().get(0).canAcknowledge()).isFalse();
    }

    @Test
    void theTabForAnotherClinicsAdmissionIs404AndAnOutOfScopeNurseIsRefusedBeforeTheRecordAccess() {
        UUID elsewhere = UUID.randomUUID();
        assertThatThrownBy(() -> service.forAdmission(TENANT, NURSE, "NURSE", elsewhere))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(patientAccess);
        doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(NURSE), eq("NURSE"), any(),
                eq(ADMISSION), anyString());

        assertThatThrownBy(() -> forAdmission("NURSE", NURSE)).isInstanceOf(OutsideScopeException.class);
    }

    @Test
    void listOutsideScopeWritesNoRecordAccess() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(NURSE), eq("NURSE"), any(),
                eq(ADMISSION), anyString());

        assertThatThrownBy(() -> forAdmission("NURSE", NURSE)).isInstanceOf(OutsideScopeException.class);

        verifyNoInteractions(patientAccess, repository);
    }

    // ---- acknowledge ----

    private NoticeRow notice(UUID ackBy) {
        return new NoticeRow(NOTICE, ADMISSION, order.id(), 1, "NEW", "ROUTINE", DOCTOR, fake.now.minusSeconds(120),
                ackBy, ackBy == null ? null : fake.now, ackBy == null ? null : "TAP", "pcm", "ACTIVE", 1, null, false,
                PATIENT, "202", "ADMITTED");
    }

    @Test
    void acknowledgeSetsTheAcknowledgementAndAuditsIdsOnly() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        when(repository.findById(TENANT, NOTICE)).thenReturn(Optional.of(notice(null)));
        when(repository.acknowledge(TENANT, NOTICE, NURSE)).thenReturn(true);
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of());

        OrderNoticeList list = service.acknowledge(TENANT, NURSE, "NURSE", NOTICE);

        verify(repository).acknowledge(TENANT, NOTICE, NURSE);
        verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ORDER_NOTICE_ACKNOWLEDGED"), anyString(), eq(NOTICE), isNull(), after.capture());
        assertThat(after.getValue()).containsEntry("noticeId", NOTICE).containsEntry("orderId", order.id())
                .containsEntry("orderVersion", 1).containsEntry("kind", "NEW").containsKey("device");
        assertThat(list.admissionId()).isEqualTo(ADMISSION);
        verifyNoInteractions(events);
    }

    @Test
    void aRepeatBySameUserAnswers200AndWritesNothing() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        when(repository.findById(TENANT, NOTICE)).thenReturn(Optional.of(notice(NURSE)));
        when(repository.findForAdmission(TENANT, ADMISSION, 100)).thenReturn(List.of());

        service.acknowledge(TENANT, NURSE, "NURSE", NOTICE);

        verify(repository, never()).acknowledge(any(), any(), any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void aNoticeAcknowledgedByAnotherNurseIs409WithHerName() {
        UUID other = UUID.randomUUID();
        when(actors.displayName(TENANT, other)).thenReturn(Optional.of("Nurse Sunita"));
        when(repository.findById(TENANT, NOTICE)).thenReturn(Optional.of(notice(other)));

        assertThatThrownBy(() -> service.acknowledge(TENANT, NURSE, "NURSE", NOTICE))
                .isInstanceOf(ConflictException.class).hasMessage("Nurse Sunita already acknowledged this.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));

        verify(repository, never()).acknowledge(any(), any(), any());
    }

    @Test
    void onlyNursesAndTheOwnerAcknowledgeAndReadOnlyRefusesFirst() {
        assertThatThrownBy(() -> service.acknowledge(TENANT, DOCTOR, "DOCTOR", NOTICE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only nurses and the clinic owner can acknowledge orders.");
        ipdState("readonly");
        assertThatThrownBy(() -> service.acknowledge(TENANT, NURSE, "NURSE", NOTICE))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));

        verifyNoInteractions(repository, admissions);
    }

    @Test
    void anUnknownNoticeIs404AndADischargedAdmissionIs409() {
        assertThatThrownBy(() -> service.acknowledge(TENANT, NURSE, "NURSE", NOTICE))
                .isInstanceOf(NotFoundException.class);

        when(repository.findById(TENANT, NOTICE)).thenReturn(Optional.of(notice(null)));
        admission = admissionRow("DISCHARGED");
        assertThatThrownBy(() -> service.acknowledge(TENANT, NURSE, "NURSE", NOTICE))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("not-admitted"));
        verify(repository, never()).acknowledge(any(), any(), any());
    }

    @Test
    void acknowledgeOutsideScopeWritesNothing() {
        when(repository.findById(TENANT, NOTICE)).thenReturn(Optional.of(notice(null)));
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(NURSE), eq("NURSE"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.acknowledge(TENANT, NURSE, "NURSE", NOTICE))
                .isInstanceOf(OutsideScopeException.class);

        verify(repository, never()).acknowledge(any(), any(), any());
        verifyNoInteractions(auditLog, patientAccess);
    }

    // ---- the Safety panel ----

    @Test
    void thePanelListsOpenNoticesOfTheCallersScopeAndRecordsOneAccessPerPatient() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        when(guard.visibleAdmissionIds(any(), any())).thenReturn(Set.of(ADMISSION));
        when(repository.findOpenPage(eq(TENANT), eq(Set.of(ADMISSION)), isNull(), eq(51))).thenReturn(
                List.of(open(NOTICE, "NEW", 40), open(UUID.randomUUID(), "HELD", 2)));
        when(repository.stats(eq(TENANT), eq(Set.of(ADMISSION)), any(), any()))
                .thenReturn(Map.of(ADMISSION, new Stats(2, 0, 1, null)));

        OrderNoticePage page = service.page(TENANT, NURSE, "NURSE", null, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).patientName()).isEqualTo("Test Patient");
        assertThat(page.items().get(0).bedCode()).isEqualTo("202");
        assertThat(page.open().count()).isEqualTo(2);
        assertThat(page.open().overdue()).isEqualTo(1);
        assertThat(page.nextCursor()).isNull();
        verify(patientAccess).recordOpened(TENANT, PATIENT, NURSE);
    }

    @Test
    void aNurseWithNoPatientsInScopeGetsAnEmptyPageWithoutAQuery() {
        when(guard.visibleAdmissionIds(any(), any())).thenReturn(Set.of());

        OrderNoticePage page = service.page(TENANT, NURSE, "NURSE", null, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.open().count()).isZero();
        verify(repository, never()).findOpenPage(any(), any(), any(), anyInt());
        verifyNoInteractions(patientAccess);
    }

    @Test
    void theNextPageStartsAfterTheCursorAndTheCursorRoundTrips() {
        versions(v1(Kind.SCHEDULED, OrderPriority.ROUTINE));
        List<NoticeRow> rows = new ArrayList<>(List.of(open(NOTICE, "NEW", 40), open(UUID.randomUUID(), "NEW", 30)));
        when(repository.findOpenPage(eq(TENANT), isNull(), isNull(), eq(2))).thenReturn(rows);
        when(repository.stats(eq(TENANT), isNull(), any(), any())).thenReturn(Map.of());

        OrderNoticePage first = service.page(TENANT, NURSE, "NURSE", "1", null);

        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).hasSize(34);
        Cursor decoded = OrderNoticeService.decodeCursor(first.nextCursor());
        assertThat(decoded).isEqualTo(rows.get(0).cursor());
        assertThat(OrderNoticeService.encodeCursor(decoded)).isEqualTo(first.nextCursor());
    }

    @Test
    void aBadCursorOrLimitIs400AndTheRolesAreNurseAndOwnerOnly() {
        assertThatThrownBy(() -> service.page(TENANT, NURSE, "NURSE", "0", null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.page(TENANT, NURSE, "NURSE", null, "nope"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.page(TENANT, DOCTOR, "DOCTOR", null, null)).isInstanceOf(ForbiddenException.class);
        assertThat(OrderNoticeService.decodeCursor(null)).isNull();
        assertThat(OrderNoticeService.decodeCursor("BwAA" + "A".repeat(30))).isNull();
    }

    // ---- the summary ----

    @Test
    void theSummaryHoldsCountsOnlyAndWritesNothing() {
        Instant slot = fake.now.minusSeconds(5 * 60 + 20);
        when(repository.stats(eq(TENANT), isNull(), any(), any())).thenReturn(Map.of(
                ADMISSION, new Stats(3, 1, 2, slot)));
        String digest = "0123456789abcdef0123456789abcdef";
        when(repository.openDigest(eq(TENANT), isNull())).thenReturn(digest);

        OrderNoticeSummary summary = service.summary(TENANT, DOCTOR, "DOCTOR");

        assertThat(summary.total().count()).isEqualTo(3);
        assertThat(summary.total().stat()).isEqualTo(1);
        assertThat(summary.total().overdue()).isEqualTo(2);
        assertThat(summary.byAdmission()).hasSize(1);
        assertThat(summary.byAdmission().get(0).admissionId()).isEqualTo(ADMISSION);
        assertThat(summary.byAdmission().get(0).statDueBy())
                .isEqualTo(slot.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(15 * 60));
        assertThat(summary.changeToken()).hasSize(22).isEqualTo(OrderNoticeService.changeToken(digest));
        verify(repository, never()).findOpenPage(any(), any(), any(), anyInt());
        verifyNoInteractions(patientAccess, auditLog, events);
    }

    @Test
    void theChangeTokenFollowsTheDatabaseDigestOfTheOpenSetAndHoldsNoId() {
        String one = "0123456789abcdef0123456789abcdef";
        String two = "fedcba9876543210fedcba9876543210";

        assertThat(OrderNoticeService.changeToken(one)).isEqualTo(OrderNoticeService.changeToken(one));
        assertThat(OrderNoticeService.changeToken(one)).isNotEqualTo(OrderNoticeService.changeToken(two));
        assertThat(OrderNoticeService.changeToken(OrderNoticeRepository.EMPTY_DIGEST)).hasSize(22)
                .matches("^[A-Za-z0-9_-]{22}$");
    }

    @Test
    void theEmptyDigestIsTheMd5OfTheEmptyTextWhichIsWhatTheDatabaseAnswersForNoOpenNotice() throws Exception {
        byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(new byte[0]);

        assertThat(OrderNoticeRepository.EMPTY_DIGEST).isEqualTo(new java.math.BigInteger(1, md5).toString(16));
    }

    @Test
    void theSummaryIsForOwnerNurseAndDoctorOnlyAndFiltersByScope() {
        assertThatThrownBy(() -> service.summary(TENANT, PHARMACIST, "PHARMACIST")).isInstanceOf(ForbiddenException.class);
        when(guard.visibleAdmissionIds(any(), any())).thenReturn(Set.of());

        OrderNoticeSummary summary = service.summary(TENANT, NURSE, "NURSE");

        assertThat(summary.byAdmission()).isEmpty();
        assertThat(summary.total().count()).isZero();
        verify(repository, never()).stats(any(), any(), any(), any());
        verify(repository, never()).openDigest(any(), any());
        assertThat(summary.changeToken()).isEqualTo(OrderNoticeService.changeToken(OrderNoticeRepository.EMPTY_DIGEST));
    }
}
