package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.application.TransferRepository.NewTransfer;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-26 bed transfer: the check order of AC 3, the owner rule of AC 2, the segment days of AC 4, the lock order,
 * the role re-check, the audit row and the transfer rules (AC 7).
 */
class BedTransferServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    /** 9 Nov 2026, 11:00 AM IST: bill day 3 on the midnight cut, 2 on the 24-hour cut. */
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-09T05:30:00Z");

    private AdmissionRepository admissions;
    private BedRepository beds;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private TransferRepository transfers;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private PatientAccessPort patientAccess;
    private ModuleEntitlementPort entitlements;
    private BedTransferService service;
    private ScopeGuard guard;

    private UUID fromId = LOW;
    private UUID toId = HIGH;
    private boolean moved;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        beds = mock(BedRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        transfers = mock(TransferRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        patientAccess = mock(PatientAccessPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        PatientLookupPort patients = mock(PatientLookupPort.class);
        TeamMemberPort team = mock(TeamMemberPort.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        guard = ScopeStubs.unscoped();
        AdmissionService sheets = new AdmissionService(admissions, beds, wards, settings, ownerGuard, auditLog, team,
                patients, patientAccess, mock(ActorDirectoryPort.class), gate, catalog, charges,
                new IpdBilling(charges, transfers, catalog), mock(DischargeRepository.class),
                mock(EmergencyService.class), mock(VitalsRepository.class), mock(AdmittedCards.class), mock(CardSetups.class), ScopeStubs.unscoped(), ScopeStubs.repository());
        service = new BedTransferService(admissions, beds, wards, settings, transfers, ownerGuard, auditLog,
                patientAccess, gate, catalog, sheets, guard);

        state("on");
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.empty());
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001")));
        when(transfers.insert(eq(TENANT), any())).thenReturn(TRANSFER);
        setBeds("gen", "occ", "pvt", "free");
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    /** The patient is in {@code fromId}; the target is {@code toId}. */
    private void setBeds(String fromCategory, String fromStatus, String toCategory, String toStatus) {
        when(beds.findForUpdate(TENANT, fromId)).thenReturn(Optional.of(
                new BedRow(fromId, WARD, null, "101-A", fromCategory, fromStatus)));
        when(beds.findForUpdate(TENANT, toId)).thenReturn(Optional.of(
                new BedRow(toId, WARD, null, "201-B", toCategory, toStatus)));
        AdmissionRow before = row("ADMITTED", fromId, "101-A", fromCategory);
        AdmissionRow after = row("ADMITTED", toId, "201-B", toCategory);
        // The admission reads as it was until the move points it at the new bed.
        moved = false;
        when(admissions.find(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(moved ? after : before));
        doAnswer(call -> {
            moved = true;
            return null;
        }).when(admissions).updateBed(eq(TENANT), eq(ADMISSION), any());
        when(admissions.findForUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(before));
    }

    private static AdmissionRow row(String status, UUID bedId, String bedCode, String category) {
        return new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, bedId, bedCode, category, "General ward",
                DOCTOR, "planned", "Self", "Chest pain", status, ADMITTED_AT, 1_000_000L);
    }

    private void rules(boolean ownerOnly, String newRateFrom) {
        when(settings.findTransferRules(TENANT)).thenReturn(Optional.of(new TransferRules(ownerOnly, newRateFrom)));
    }

    private NewTransfer savedTransfer() {
        ArgumentCaptor<NewTransfer> saved = ArgumentCaptor.forClass(NewTransfer.class);
        verify(transfers).insert(eq(TENANT), saved.capture());
        return saved.getValue();
    }

    private void verifyNothingWritten() {
        verify(transfers, never()).insert(any(), any());
        verify(admissions, never()).updateBed(any(), any(), any());
        verify(beds, never()).updateStatus(any(), any(), any());
        verify(beds, never()).markOccupied(any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    // ---- the move ----

    @Test
    void aMoveWritesTheTransferTheAdmissionAndBothBedsInOneOrderAndReturnsTheSheet() {
        Detail detail = service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        InOrder order = inOrder(wards, beds, admissions, transfers, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(beds).findForUpdate(TENANT, LOW);
        order.verify(beds).findForUpdate(TENANT, HIGH);
        order.verify(admissions).findForUpdate(TENANT, ADMISSION);
        order.verify(transfers).insert(eq(TENANT), any());
        order.verify(admissions).updateBed(TENANT, ADMISSION, toId);
        order.verify(beds).updateStatus(TENANT, fromId, "clean");
        order.verify(beds).markOccupied(TENANT, toId);
        order.verify(auditLog).record(eq("IPD_BED_TRANSFER"), any(), any(), any(), any());

        NewTransfer saved = savedTransfer();
        assertThat(saved.admissionId()).isEqualTo(ADMISSION);
        assertThat(saved.fromBedId()).isEqualTo(fromId);
        assertThat(saved.toBedId()).isEqualTo(toId);
        assertThat(saved.fromCategoryKey()).isEqualTo("gen");
        assertThat(saved.toCategoryKey()).isEqualTo("pvt");
        assertThat(saved.transferredBy()).isEqualTo(ACTOR);
        assertThat(saved.reason()).isNull();
        assertThat(detail.bedCode()).isEqualTo("201-B");
        assertThat(detail.categoryName()).isEqualTo("Private");
        assertThat(detail.canTransfer()).isTrue();
        assertThat(detail.transferRules()).isEqualTo(new TransferRules(true, "same"));
    }

    @Test
    void bedsAreLockedInIdOrderWhicheverWayThePatientMoves() {
        fromId = HIGH;
        toId = LOW;
        setBeds("gen", "occ", "gen", "free");

        service.transfer(TENANT, ACTOR, "NURSE", ADMISSION, toId, null);

        InOrder order = inOrder(beds, admissions);
        order.verify(beds).findForUpdate(TENANT, LOW);
        order.verify(beds).findForUpdate(TENANT, HIGH);
        order.verify(admissions).findForUpdate(TENANT, ADMISSION);
        verify(beds).updateStatus(TENANT, HIGH, "clean");
        verify(beds).markOccupied(TENANT, LOW);
    }

    @Test
    void ifTheLockedAdmissionIsInAnotherBedThanTheFirstReadNothingIsWritten() {
        UUID elsewhere = UUID.randomUUID();
        when(admissions.findForUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(
                row("ADMITTED", elsewhere, "301-C", "icu")));

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(IllegalStateException.class);

        verifyNothingWritten();
    }

    @Test
    void aBedThatDoesNotExistIsNotFoundAndTheAdmissionBedMissingIsAServerFault() {
        when(beds.findForUpdate(TENANT, toId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(NotFoundException.class);

        setBeds("gen", "occ", "pvt", "free");
        when(beds.findForUpdate(TENANT, fromId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNothingWritten();
    }

    @Test
    void theOptionalReasonIsStoredAsGivenOnlyFromTheFourReasons() {
        for (String reason : new String[] {"Needs closer monitoring", "Moving to a step-down bed",
                "Patient or family request", "Isolation"}) {
            setUp();
            service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, reason);
            assertThat(savedTransfer().reason()).isEqualTo(reason);
        }
    }

    @Test
    void aReasonOutsideTheListOrBlankIsRefusedBeforeTheLock() {
        for (String reason : new String[] {"", " ", "Because", "isolation", "Isolation "}) {
            assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, reason))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("reason"));
        }
        verify(wards, never()).lockTenant(any());
        verifyNothingWritten();
    }

    @Test
    void aMissingTargetBedIdIsRefusedBeforeTheLock() {
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, null, null))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("toBedId"));
        verify(wards, never()).lockTenant(any());
    }

    // ---- check order and messages (AC 3) ----

    @Test
    void anUnknownAdmissionIsNotFoundAndNothingIsWritten() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(NotFoundException.class);
        verify(beds, never()).findForUpdate(any(), any());
        verifyNothingWritten();
    }

    @Test
    void anAdmissionThatIsNotAdmittedIs409NotAdmittedBeforeTheTargetBedIsLookedAt() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("DISCHARGED", fromId, "101-A", "gen")));
        when(beds.findForUpdate(TENANT, toId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("not-admitted");
                    assertThat(e.getMessage()).isEqualTo("This patient is not admitted any more. The patient cannot be moved.");
                });
        verify(beds, never()).findForUpdate(any(), any());
        verifyNothingWritten();
    }

    @Test
    void aTargetBedOfNoClinicOrAnotherClinicIsNotFound() {
        when(beds.findForUpdate(TENANT, toId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(NotFoundException.class);
        verifyNothingWritten();
    }

    @Test
    void movingToTheCurrentBedIs400AndComesBeforeTheFreeCheck() {
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, fromId, null))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("toBedId"));
        verify(beds, never()).findForUpdate(any(), any());
        verifyNothingWritten();
    }

    @Test
    void aBedThatIsNotAvailableIs409BedNotFreeForEveryOtherStatusAndComesBeforeTheOwnerRule() {
        for (String status : new String[] {"occ", "clean", "blk", "res"}) {
            setUp();
            // Private is higher than General and the rule is "Owner approves": the free check still comes first.
            setBeds("gen", "occ", "pvt", status);
            assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "NURSE", ADMISSION, toId, null))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("bed-not-free");
                        assertThat(e.getMessage()).isEqualTo("That bed is no longer free. Pick another.");
                    });
            verifyNothingWritten();
        }
    }

    @Test
    void theSecondOfTwoMovesToTheSameBedGetsBedNotFree() {
        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);
        // The first move left the bed Occupied: a second request now reads it as such.
        when(beds.findForUpdate(TENANT, toId)).thenReturn(Optional.of(new BedRow(toId, WARD, null, "201-B", "pvt", "occ")));
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("ADMITTED", fromId, "101-A", "gen")));

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("bed-not-free"));
    }

    @Test
    void theLockedAdmissionIsCheckedAgainForNotAdmitted() {
        when(admissions.findForUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(row("DISCHARGED", fromId, "101-A", "gen")));

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        verifyNothingWritten();
    }

    // ---- owner rule (AC 2) ----

    @Test
    void ownerApprovesRuleLetsOnlyTheOwnerMoveToAHigherCategory() {
        for (String role : new String[] {"FRONT_DESK", "NURSE"}) {
            assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, role, ADMISSION, toId, null))
                    .isInstanceOfSatisfying(ForbiddenException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("owner-approval-needed");
                        assertThat(e.getMessage())
                                .isEqualTo("Moving to Private needs the owner's approval. Ask the clinic owner.");
                    });
        }
        verifyNothingWritten();

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        verify(ownerGuard).requireOwner(TENANT, ACTOR);
        verify(transfers).insert(eq(TENANT), any());
    }

    @Test
    void theOwnerApprovalMessageNamesTheTargetCategory() {
        setBeds("gen", "occ", "icu", "free");

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "NURSE", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Moving to ICU needs the owner's approval. Ask the clinic owner."));
        setBeds("gen", "occ", "semi", "free");

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "NURSE", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Moving to Semi-private needs the owner's approval. Ask the clinic owner."));
    }

    @Test
    void frontDeskCanDoItRuleLetsAllThreeRolesMoveToAHigherCategory() {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            setUp();
            rules(false, "same");
            service.transfer(TENANT, ACTOR, role, ADMISSION, toId, null);
            verify(transfers).insert(eq(TENANT), any());
            verify(ownerGuard, never()).requireOwner(any(), any());
        }
    }

    @Test
    void theSameOrALowerCategoryIsAlwaysAllowedToTheThreeRolesEvenWhenOwnerApproves() {
        String[][] pairs = {{"pvt", "pvt"}, {"pvt", "gen"}, {"icu", "semi"}, {"gen", "gen"}, {"semi", "gen"}};
        for (String[] pair : pairs) {
            for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
                setUp();
                setBeds(pair[0], "occ", pair[1], "free");
                service.transfer(TENANT, ACTOR, role, ADMISSION, toId, null);
                verify(transfers).insert(eq(TENANT), any());
                verify(ownerGuard, never()).requireOwner(any(), any());
            }
        }
    }

    @Test
    void anOwnerDemotedASecondAgoIsRefusedForAHigherCategoryAndNothingIsWritten() {
        doThrow(new AccessDeniedException("not owner")).when(ownerGuard).requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNothingWritten();
    }

    // ---- segment days (AC 4) ----

    @Test
    void segmentDaysFollowTheExamplesOfAc4ForBothRules() {
        // Admitted day 1, moved on day 3.
        assertThat(BedTransferService.segmentDays(3, 0, "same")).isEqualTo(2);
        assertThat(BedTransferService.segmentDays(3, 0, "next")).isEqualTo(3);
        // Moved on day 1: with "Same day" the whole first day is at the new category.
        assertThat(BedTransferService.segmentDays(1, 0, "same")).isZero();
        assertThat(BedTransferService.segmentDays(1, 0, "next")).isEqualTo(1);
    }

    @Test
    void aSecondTransferTheSameDayAddsNothingMore() {
        // First move on day 3 with "Same day": p = 2. Second move the same day: c = 1, segment 0.
        assertThat(BedTransferService.segmentDays(3, 2, "same")).isZero();
        // "Next day": first move kept 3 days, p = 3; the second move the same day: c = 0, segment 0.
        assertThat(BedTransferService.segmentDays(3, 3, "next")).isZero();
        // A second move a day later (day 4) after a "Same day" first move: c = 2, segment 1.
        assertThat(BedTransferService.segmentDays(4, 2, "same")).isEqualTo(1);
        assertThat(BedTransferService.segmentDays(4, 3, "next")).isEqualTo(1);
    }

    @Test
    void segmentDaysNeverGoBelowZeroWhenEarlierSegmentsExceedTheBillDays() {
        assertThat(BedTransferService.segmentDays(1, 5, "same")).isZero();
        assertThat(BedTransferService.segmentDays(1, 5, "next")).isZero();
        assertThat(BedTransferService.segmentDays(0, 0, "same")).isZero();
    }

    @Test
    void theMidnightCutStoresTwoSegmentDaysForAMoveOnBillDayThreeWithSameDay() {
        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        NewTransfer saved = savedTransfer();
        assertThat(saved.segmentDays()).isEqualTo(2);
        assertThat(saved.newRateFrom()).isEqualTo("same");
    }

    @Test
    void nextDayStoresAllThreeBillDaysOnTheOldBed() {
        rules(true, "next");

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        NewTransfer saved = savedTransfer();
        assertThat(saved.segmentDays()).isEqualTo(3);
        assertThat(saved.newRateFrom()).isEqualTo("next");
    }

    @Test
    void the24HourCutCountsCompletedDaysSoTheSegmentIsOneWithSameDay() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(true, "24h", 30_000L, 50_000L)));

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        // 48 hours since admission: bill days 2, segment 1 with "Same day".
        assertThat(savedTransfer().segmentDays()).isEqualTo(1);
    }

    @Test
    void the24HourCutWithNextDayStoresAllTwoBillDays() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(true, "24h", 30_000L, 50_000L)));
        rules(true, "next");

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        assertThat(savedTransfer().segmentDays()).isEqualTo(2);
    }

    @Test
    void daysAlreadyMovedToEarlierSegmentsAreTakenOffTheBillDays() {
        // A move earlier fixed 2 General days; today is bill day 3 with "Same day": c = 1, segment 0.
        when(transfers.findByAdmission(TENANT, ADMISSION)).thenReturn(List.of(
                new TransferRow(ADMISSION, "gen", 2)));
        setBeds("semi", "occ", "pvt", "free");

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        NewTransfer saved = savedTransfer();
        assertThat(saved.segmentDays()).isZero();
        assertThat(saved.fromCategoryKey()).isEqualTo("semi");
    }

    @Test
    void aMoveOnTheAdmissionDayWithSameDayStoresNoSegmentDays() {
        when(admissions.findForUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionRow(ADMISSION,
                "IP/26-27/0042", PATIENT, fromId, "101-A", "gen", "General ward", DOCTOR, "planned", "Self",
                "Chest pain", "ADMITTED", NOW.minusSeconds(3600), 1_000_000L)));

        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        assertThat(savedTransfer().segmentDays()).isZero();
    }

    // ---- audit and access ----

    @Test
    @SuppressWarnings("unchecked")
    void theAuditRowHasIdsCodesCategoriesSegmentDaysAndRuleValuesButNeverNameOrReason() {
        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, "Isolation");

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_BED_TRANSFER"), eq("IPD_BED_TRANSFER"), eq(TRANSFER), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("admissionId", ADMISSION)
                .containsEntry("fromBedId", fromId).containsEntry("fromBedCode", "101-A")
                .containsEntry("toBedId", toId).containsEntry("toBedCode", "201-B")
                .containsEntry("fromCategoryKey", "gen").containsEntry("toCategoryKey", "pvt")
                .containsEntry("segmentDays", 2).containsEntry("billDays", 3)
                .containsEntry("newRateFrom", "same").containsEntry("upgradeNeedsOwner", true);
        assertThat(after.getValue().keySet()).doesNotContain("reason", "patientName", "name", "mobile");
        assertThat(after.getValue().values().toString())
                .doesNotContain("Isolation", "Test Patient", "9000000001", "Chest pain");
    }

    @Test
    void theSheetAccessIsLoggedOnceForThePatientAndTheUserOnlyOnSuccess() {
        service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null);

        verify(patientAccess).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void theNewTransferRecordHidesTheReasonInToString() {
        NewTransfer transfer = new NewTransfer(ADMISSION, fromId, toId, "gen", "pvt", 2, "same", "Isolation", ACTOR);

        assertThat(transfer.toString()).doesNotContain("Isolation").contains(ADMISSION.toString());
    }

    // ---- roles and the module gate ----

    @Test
    void otherRolesAreRefusedBeforeAnyLookupOrLock() {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR", null}) {
            assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, role, ADMISSION, toId, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(entitlements, wards, admissions, beds, transfers, auditLog);
    }

    @Test
    void moduleOffIs403AndReadonlyIs409BeforeAnyLock() {
        state("off");
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOf(ModuleOffException.class);
        state("readonly");
        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", ADMISSION, toId, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));

        verify(wards, never()).lockTenant(any());
        verifyNothingWritten();
    }

    @Test
    void anotherClinicsAdmissionIsNotFoundBecauseRowLevelSecurityHidesIt() {
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "OWNER", other, toId, null))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- transfer rules (AC 7) ----

    @Test
    void rulesDefaultToOwnerApprovesAndSameDayWhenNeverSaved() {
        assertThat(service.rules(TENANT)).isEqualTo(new TransferRules(true, "same"));
    }

    @Test
    void rulesAreReadableInReadonlyAndRefusedWhenOff() {
        state("readonly");
        assertThat(service.rules(TENANT)).isNotNull();
        state("off");
        assertThatThrownBy(() -> service.rules(TENANT)).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void updateRulesSavesAuditsTheValuesAndReturnsThem() {
        TransferRules saved = service.updateRules(TENANT, ACTOR, false, "next");

        assertThat(saved).isEqualTo(new TransferRules(false, "next"));
        InOrder order = inOrder(ownerGuard, wards, settings);
        order.verify(ownerGuard).requireOwner(TENANT, ACTOR);
        order.verify(wards).lockTenant(TENANT);
        order.verify(settings).upsertTransferRules(eq(TENANT), eq(new TransferRules(false, "next")), eq(ACTOR),
                eq(Instant.parse("2026-11-11T05:30:00.123456Z")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_TRANSFER_RULES_UPDATED"), eq("IPD_CONFIG"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("upgradeNeedsOwner", true).containsEntry("newRateFrom", "same");
        assertThat(after.getValue()).containsEntry("upgradeNeedsOwner", false).containsEntry("newRateFrom", "next");
    }

    @Test
    void updateRulesWithTheSameValuesWritesNothing() {
        rules(false, "next");

        assertThat(service.updateRules(TENANT, ACTOR, false, "next")).isEqualTo(new TransferRules(false, "next"));

        verify(settings, never()).upsertTransferRules(any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateRulesRejectsAMissingChoiceAndAnUnknownRateStartBeforeTheLock() {
        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, null, "same"))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.field()).isEqualTo("upgradeNeedsOwner"));
        for (String bad : new String[] {null, "", "Same", "tomorrow", "SAME"}) {
            assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, true, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("newRateFrom"));
        }
        verify(wards, never()).lockTenant(any());
        verify(settings, never()).upsertTransferRules(any(), any(), any(), any());
    }

    @Test
    void updateRulesNeedsTheOwnerReReadAndIsRefusedInReadonly() {
        doThrow(new AccessDeniedException("not owner")).when(ownerGuard).requireOwner(TENANT, ACTOR);
        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, false, "same"))
                .isInstanceOf(AccessDeniedException.class);

        UUID owner = UUID.randomUUID();
        state("readonly");
        assertThatThrownBy(() -> service.updateRules(TENANT, owner, false, "same"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        state("off");
        assertThatThrownBy(() -> service.updateRules(TENANT, owner, false, "same"))
                .isInstanceOf(ModuleOffException.class);
        verify(settings, never()).upsertTransferRules(any(), any(), any(), any());
    }

    // ---- category order ----

    @Test
    void categoryRankRunsGeneralSemiPrivateIcu() {
        assertThat(BedTransferService.rank("gen")).isLessThan(BedTransferService.rank("semi"));
        assertThat(BedTransferService.rank("semi")).isLessThan(BedTransferService.rank("pvt"));
        assertThat(BedTransferService.rank("pvt")).isLessThan(BedTransferService.rank("icu"));
    }

    // ---- SAH-34 patient scope ----

    @Test
    void outsideScopeMovesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("NURSE"),
                any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "NURSE", ADMISSION, toId, null))
                .isInstanceOf(OutsideScopeException.class);

        verifyNothingWritten();
        verify(beds, never()).findForUpdate(any(), any());
    }

    @Test
    void theOutsideScopeAnswerComesAfterTheNotFoundAndBeforeTheStateChecks() {
        UUID unknown = UUID.randomUUID();
        when(admissions.find(TENANT, unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(TENANT, ACTOR, "NURSE", unknown, toId, null))
                .isInstanceOf(NotFoundException.class);
        verify(guard, never()).requireAdmissionLocked(any(), any(), any(), any(), eq(unknown),
                org.mockito.ArgumentMatchers.anyString());
    }
}
