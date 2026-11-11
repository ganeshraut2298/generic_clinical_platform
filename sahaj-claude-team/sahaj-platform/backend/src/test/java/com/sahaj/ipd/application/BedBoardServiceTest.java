package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.BedBoardViews.BedBoard;
import com.sahaj.ipd.application.BedBoardViews.BoardBed;
import com.sahaj.ipd.application.BedBoardViews.BoardRoom;
import com.sahaj.ipd.application.BedBoardViews.BoardWard;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
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

/** SAH-24 bed board read and bed state changes. */
class BedBoardServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WARD1 = UUID.randomUUID();
    private static final UUID WARD2 = UUID.randomUUID();
    private static final UUID WARD3 = UUID.randomUUID();
    private static final UUID ROOM_B = UUID.randomUUID();
    private static final UUID ROOM_A = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID BED_OCC = UUID.randomUUID();
    private static final UUID BED_FREE_B = UUID.randomUUID();
    private static final UUID BED_FREE_A = UUID.randomUUID();
    private static final UUID BED_CLEAN = UUID.randomUUID();
    private static final UUID BED_NOROOM = UUID.randomUUID();
    private static final UUID BED_BLK = UUID.randomUUID();
    private static final UUID BED_RES = UUID.randomUUID();
    private static final UUID BED_W2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");

    private BedRepository beds;
    private WardRepository wards;
    private AdmissionRepository admissions;
    private IpdSettingsRepository settings;
    private ModuleEntitlementPort entitlements;
    private AuditLog auditLog;
    private PatientLookupPort patients;
    private TeamMemberPort team;
    private ChargeRepository charges;
    private DischargeRepository discharges = mock(DischargeRepository.class);
    private BedBoardService service;
    private ScopeGuard guard;

    @BeforeEach
    void setUp() {
        beds = mock(BedRepository.class);
        wards = mock(WardRepository.class);
        admissions = mock(AdmissionRepository.class);
        settings = mock(IpdSettingsRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        auditLog = mock(AuditLog.class);
        patients = mock(PatientLookupPort.class);
        team = mock(TeamMemberPort.class);
        charges = mock(ChargeRepository.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        guard = ScopeStubs.unscoped();
        service = new BedBoardService(beds, wards, admissions, auditLog, patients, team,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), catalog, new IpdBilling(charges, mock(TransferRepository.class), catalog),
                discharges, guard);
        state("on");
        roomLevel(true);
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(wards.findWards(TENANT)).thenReturn(List.of(new WardRow(WARD1, "General ward", "Station 1"),
                new WardRow(WARD2, "ICU", "Station 2"), new WardRow(WARD3, "Empty ward", "Station 3")));
        when(wards.findRooms(TENANT)).thenReturn(List.of(new RoomRow(ROOM_B, WARD1, "Room B"),
                new RoomRow(ROOM_A, WARD1, "Room A")));
        when(beds.findAll(TENANT)).thenReturn(List.of(
                new BedRow(BED_NOROOM, WARD1, null, "100", "gen", "blk"),
                new BedRow(BED_FREE_A, WARD1, ROOM_A, "201", "semi", "free"),
                new BedRow(BED_OCC, WARD1, ROOM_B, "102", "gen", "occ"),
                new BedRow(BED_FREE_B, WARD1, ROOM_B, "101", "gen", "free"),
                new BedRow(BED_CLEAN, WARD1, ROOM_B, "103", "gen", "clean"),
                new BedRow(BED_RES, WARD2, null, "ICU-1", "icu", "res"),
                new BedRow(BED_W2, WARD2, null, "ICU-0", "icu", "free")));
        when(admissions.findAdmitted(TENANT)).thenReturn(List.of(new AdmissionRow(ADMISSION, "IP/26-27/0001",
                PATIENT, BED_OCC, "102", "gen", "General ward", DOCTOR, "planned", "Self", "Chest pain", "ADMITTED",
                Instant.parse("2026-11-10T19:00:00Z"), 0L)));
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(PATIENT,
                new PatientSummary(PATIENT, "Test Patient", 50, "M", "9000000001")));
        when(team.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Anil Kumar Rao", "DOCTOR", true, 0)));
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    private void roomLevel(boolean on) {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(on, "12:00", 0L, 0L)));
    }

    @Test
    void withNoSettingsRowTheBoardUsesTheDefaultRoomsOn() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.empty());

        assertThat(service.board(TENANT, ACTOR, "OWNER").roomLevel()).isTrue();
    }

    @Test
    void boardListsWardsInOrderWithCountsAndRoomGroups() {
        BedBoard board = service.board(TENANT, ACTOR, "NURSE");

        assertThat(board.state()).isEqualTo("on");
        assertThat(board.roomLevel()).isTrue();
        assertThat(board.counts().free()).isEqualTo(3);
        assertThat(board.counts().occ()).isEqualTo(1);
        assertThat(board.counts().clean()).isEqualTo(1);
        assertThat(board.counts().res()).isEqualTo(1);
        assertThat(board.counts().blk()).isEqualTo(1);
        assertThat(board.wards()).extracting(BoardWard::name).containsExactly("General ward", "ICU", "Empty ward");

        BoardWard general = board.wards().get(0);
        assertThat(general.total()).isEqualTo(5);
        assertThat(general.occupied()).isEqualTo(1);
        assertThat(general.hasFreeBed()).isTrue();
        assertThat(general.rooms()).extracting(BoardRoom::name).containsExactly("Room B", "Room A", "");
        assertThat(general.rooms().get(0).beds()).extracting(BoardBed::code).containsExactly("101", "102", "103");
        assertThat(general.rooms().get(2).id()).isNull();
        assertThat(general.firstFreeBedId()).isEqualTo(BED_FREE_B);

        BoardWard empty = board.wards().get(2);
        assertThat(empty.total()).isZero();
        assertThat(empty.hasFreeBed()).isFalse();
        assertThat(empty.firstFreeBedId()).isNull();
    }

    @Test
    void occupiedBedCarriesPatientDayAndDoctorShortName() {
        BoardBed bed = service.board(TENANT, ACTOR, "DOCTOR").wards().get(0).rooms().get(0).beds().get(1);

        assertThat(bed.status()).isEqualTo("occ");
        assertThat(bed.admissionId()).isEqualTo(ADMISSION);
        assertThat(bed.patientName()).isEqualTo("Test Patient");
        assertThat(bed.doctorShortName()).isEqualTo("Anil Kumar");
        // admitted 11 Nov 00:30 IST, now 12 Nov 01:30 IST
        assertThat(bed.dayNumber()).isEqualTo(2);
        assertThat(bed.categoryName()).isEqualTo("General");
        assertThat(bed.toString()).doesNotContain("Test Patient");
    }

    private BoardBed occupiedBed() {
        return service.board(TENANT, ACTOR, "DOCTOR").wards().get(0).rooms().get(0).beds().get(1);
    }

    private static DischargeRepository.SignoffRow signoffAt(String instant) {
        return new DischargeRepository.SignoffRow(UUID.randomUUID(), ADMISSION, "normal", "general", DOCTOR,
                Instant.parse(instant));
    }

    @Test
    void anOccupiedBedIsGoingHomeTodayWhenTheLatestSignOffIsFromTodayIst() {
        // Now is 12 Nov 01:30 IST; 12 Nov 00:30 IST is today, 11 Nov 23:30 IST is yesterday.
        when(discharges.findLatestSignoffsOfAdmitted(TENANT)).thenReturn(Map.of(ADMISSION,
                signoffAt("2026-11-11T19:00:00Z")));
        assertThat(occupiedBed().goingHomeToday()).isTrue();

        when(discharges.findLatestSignoffsOfAdmitted(TENANT)).thenReturn(Map.of(ADMISSION,
                signoffAt("2026-11-11T18:00:00Z")));
        assertThat(occupiedBed().goingHomeToday()).isFalse();

        when(discharges.findLatestSignoffsOfAdmitted(TENANT)).thenReturn(Map.of());
        assertThat(occupiedBed().goingHomeToday()).isFalse();
    }

    @Test
    void aBedThatIsNotOccupiedHasNoGoingHomeFlagAndTheSignOffsAreReadOnceForTheBoard() {
        when(discharges.findLatestSignoffsOfAdmitted(TENANT)).thenReturn(Map.of(ADMISSION,
                signoffAt("2026-11-11T19:00:00Z")));

        BedBoard board = service.board(TENANT, ACTOR, "OWNER");

        assertThat(board.wards().get(0).rooms().get(0).beds()).filteredOn(b -> !"occ".equals(b.status()))
                .allSatisfy(b -> assertThat(b.goingHomeToday()).isNull());
        verify(discharges, org.mockito.Mockito.times(1)).findLatestSignoffsOfAdmitted(TENANT);
    }

    @Test
    void theDayNumberIsTheBillDaysOfTheBedDayCutNotTheCalendarDay() {
        // Admitted 11 Nov 00:30 IST, now 12 Nov 01:30 IST: calendar day 2, but only 25 hours passed, so one 24h period.
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(true, "24h", 0L, 0L)));

        BoardBed bed = service.board(TENANT, ACTOR, "DOCTOR").wards().get(0).rooms().get(0).beds().get(1);

        assertThat(bed.dayNumber()).isEqualTo(1);
    }

    @Test
    void settingsCategoriesAndDepositRulesAreReadOncePerBoard() {
        service.board(TENANT, ACTOR, "OWNER");

        verify(settings, times(1)).findConfig(TENANT);
        verify(settings, times(1)).findCategories(TENANT);
        verify(settings, times(1)).findDepositRules(TENANT);
    }

    @Test
    void anUnknownBedStatusIsAnErrorNotABlockedBed() {
        when(beds.findAll(TENANT)).thenReturn(List.of(new BedRow(BED_FREE_A, WARD1, ROOM_A, "201", "semi", "weird")));

        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "OWNER")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lookupsAreBatchedOncePerRequest() {
        service.board(TENANT, ACTOR, "OWNER");

        verify(patients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(patients, never()).find(any(), any());
        verify(team, times(1)).findAll(eq(TENANT), anyCollection());
        verify(team, never()).find(any(), any());
    }

    @Test
    void noAdmittedPatientsMeansNoLookups() {
        when(admissions.findAdmitted(TENANT)).thenReturn(List.of());

        service.board(TENANT, ACTOR, "OWNER");

        verify(patients, never()).findAll(any(), anyCollection());
        verify(team, never()).findAll(any(), anyCollection());
    }

    @Test
    void roomsOffGivesOneGroupWithEmptyNameHoldingAllBeds() {
        roomLevel(false);

        BoardWard general = service.board(TENANT, ACTOR, "OWNER").wards().get(0);

        assertThat(general.rooms()).hasSize(1);
        assertThat(general.rooms().get(0).name()).isEmpty();
        assertThat(general.rooms().get(0).id()).isNull();
        assertThat(general.rooms().get(0).beds()).hasSize(5);
    }

    @Test
    void canAdmitAndCanChangeBedsFollowRoleAndState() {
        assertThat(service.board(TENANT, ACTOR, "OWNER").canAdmit()).isTrue();
        assertThat(service.board(TENANT, ACTOR, "FRONT_DESK").canChangeBeds()).isTrue();
        assertThat(service.board(TENANT, ACTOR, "NURSE").canAdmit()).isTrue();
        assertThat(service.board(TENANT, ACTOR, "DOCTOR").canAdmit()).isFalse();
        assertThat(service.board(TENANT, ACTOR, "DOCTOR").canChangeBeds()).isFalse();

        state("readonly");
        BedBoard readonly = service.board(TENANT, ACTOR, "OWNER");
        assertThat(readonly.state()).isEqualTo("readonly");
        assertThat(readonly.canAdmit()).isFalse();
        assertThat(readonly.canChangeBeds()).isFalse();
    }

    @Test
    void offStateIsModuleOff() {
        state("off");

        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "OWNER")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, "OWNER", BED_CLEAN)).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void readyFromCleaningMakesBedFreeAfterLockingAndAudits() {
        when(beds.findForUpdate(TENANT, BED_CLEAN)).thenReturn(Optional.of(
                new BedRow(BED_CLEAN, WARD1, ROOM_B, "103", "gen", "clean")));

        BedBoard board = service.markReady(TENANT, ACTOR, "NURSE", BED_CLEAN);

        assertThat(board.wards()).hasSize(3);
        InOrder order = inOrder(wards, beds);
        order.verify(wards).lockTenant(TENANT);
        order.verify(beds).findForUpdate(TENANT, BED_CLEAN);
        order.verify(beds).updateStatus(TENANT, BED_CLEAN, "free");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_BED_READY"), eq("IPD_BED"), eq(BED_CLEAN), isNull(), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("bedId", "bedCode", "from", "to")
                .containsEntry("bedCode", "103").containsEntry("from", "clean").containsEntry("to", "free");
    }

    @Test
    void readyFromBlockedMakesBedFree() {
        when(beds.findForUpdate(TENANT, BED_BLK)).thenReturn(Optional.of(
                new BedRow(BED_BLK, WARD1, null, "100", "gen", "blk")));

        service.markReady(TENANT, ACTOR, "OWNER", BED_BLK);

        verify(beds).updateStatus(TENANT, BED_BLK, "free");
    }

    @Test
    void blockFromCleaningMakesBedBlockedAndAudits() {
        when(beds.findForUpdate(TENANT, BED_CLEAN)).thenReturn(Optional.of(
                new BedRow(BED_CLEAN, WARD1, ROOM_B, "103", "gen", "clean")));

        service.block(TENANT, ACTOR, "FRONT_DESK", BED_CLEAN);

        verify(beds).updateStatus(TENANT, BED_CLEAN, "blk");
        verify(auditLog).record(eq("IPD_BED_BLOCKED"), eq("IPD_BED"), eq(BED_CLEAN), isNull(), anyMap());
    }

    @Test
    void readyRefusesAvailableOccupiedAndReservedWithLabel() {
        String[][] cases = {{"free", "Available"}, {"occ", "Occupied"}, {"res", "Reserved"}};
        for (String[] c : cases) {
            UUID id = UUID.randomUUID();
            when(beds.findForUpdate(TENANT, id)).thenReturn(Optional.of(new BedRow(id, WARD1, null, "X", "gen", c[0])));
            assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, "OWNER", id)).isInstanceOf(ConflictException.class)
                    .hasMessage("This bed cannot be changed from " + c[1] + ".")
                    .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("bed-state");
            verify(beds, never()).updateStatus(eq(TENANT), eq(id), any());
        }
    }

    @Test
    void blockRefusesBlockedWithLabel() {
        UUID id = UUID.randomUUID();
        when(beds.findForUpdate(TENANT, id)).thenReturn(Optional.of(new BedRow(id, WARD1, null, "X", "gen", "blk")));

        assertThatThrownBy(() -> service.block(TENANT, ACTOR, "OWNER", id)).isInstanceOf(ConflictException.class)
                .hasMessage("This bed cannot be changed from Blocked.");
    }

    @Test
    void blockAlsoRefusesAvailableOccupiedReservedAndBlocked() {
        for (String status : new String[] {"free", "occ", "res", "blk"}) {
            UUID id = UUID.randomUUID();
            when(beds.findForUpdate(TENANT, id)).thenReturn(Optional.of(new BedRow(id, WARD1, null, "X", "gen", status)));
            assertThatThrownBy(() -> service.block(TENANT, ACTOR, "OWNER", id)).isInstanceOf(ConflictException.class);
        }
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void unknownBedIs404() {
        UUID id = UUID.randomUUID();
        when(beds.findForUpdate(TENANT, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, "OWNER", id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.block(TENANT, ACTOR, "OWNER", id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRoleThatMayNotChangeBedsIsRefusedBeforeTheGateTheLockAndAnyWrite() {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR", "nobody"}) {
            assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, role, BED_CLEAN))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.block(TENANT, ACTOR, role, BED_CLEAN))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, null, BED_CLEAN))
                .isInstanceOf(AccessDeniedException.class);

        verify(entitlements, never()).forTenant(any());
        verify(wards, never()).lockTenant(any());
        verify(beds, never()).updateStatus(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void readonlyBlocksWritesBeforeAnyLock() {
        state("readonly");

        assertThatThrownBy(() -> service.markReady(TENANT, ACTOR, "OWNER", BED_CLEAN))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).problemType()).isEqualTo("ipd-readonly");
        verify(wards, never()).lockTenant(any());
    }

    @Test
    void shortNameIsFirstTwoWords() {
        assertThat(BedBoardService.shortName("Dr Meera")).isEqualTo("Dr Meera");
        assertThat(BedBoardService.shortName("Meera")).isEqualTo("Meera");
        assertThat(BedBoardService.shortName("  Dr  Meera  Nair ")).isEqualTo("Dr Meera");
    }

    // ---- SAH-25 deposit low flag ----

    private BoardBed occupiedBed(BedBoard board) {
        return board.wards().stream().flatMap(w -> w.rooms().stream()).flatMap(r -> r.beds().stream())
                .filter(b -> "occ".equals(b.status())).findFirst().orElseThrow();
    }

    private void admission(String payer, long deposit) {
        when(admissions.findAdmitted(TENANT)).thenReturn(List.of(new AdmissionRow(ADMISSION, "IP/26-27/0001",
                PATIENT, BED_OCC, "102", "gen", "General ward", DOCTOR, "planned", payer, "Chest pain", "ADMITTED",
                Instant.parse("2026-11-10T19:00:00Z"), deposit)));
    }

    @Test
    void occupiedBedIsFlaggedDepositLowForASelfPatientWhoseBillReachedTheAlertShare() {
        // The board test settings charge no visit or nursing: two bill days of bed 800 = 1,600; 80 percent of 2,000.
        admission("Self", 200_000L);
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "NURSE")).depositLow()).isTrue();

        admission("Self", 200_001L);
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "NURSE")).depositLow()).isFalse();
    }

    @Test
    void depositLowCountsExtraChargesAndNeverFlagsInsurancePatients() {
        admission("Self", 400_000L);
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "OWNER")).depositLow()).isFalse();
        when(charges.findForAdmitted(TENANT)).thenReturn(List.of(
                new ChargeRepository.ChargeRow(ADMISSION, "oxygen", "Oxygen (per day)", 80_000L, 2),
                new ChargeRepository.ChargeRow(ADMISSION, "cbc", "CBC", 30_000L, 1)));
        // 1,600 + 1,600 + 300 = 3,500 of 4,000 is 87.5 percent.
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "OWNER")).depositLow()).isTrue();

        admission("Insurance", 0L);
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "OWNER")).depositLow()).isFalse();
    }

    @Test
    void freeBedsHaveNoDepositLowFlagAndTheChargesAreFetchedOnce() {
        BedBoard board = service.board(TENANT, ACTOR, "OWNER");

        assertThat(board.wards().stream().flatMap(w -> w.rooms().stream()).flatMap(r -> r.beds().stream())
                .filter(b -> !"occ".equals(b.status()))).allMatch(b -> b.depositLow() == null);
        verify(charges, times(1)).findForAdmitted(TENANT);
        verify(charges, never()).findByAdmission(any(), any());
    }

    @Test
    void depositLowUsesTheClinicAlertPercent() {
        admission("Self", 200_001L);
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(new IpdSettingsRepository.DepositRules(
                1_000_000L, 500_000L, 300_000L, 1_500_000L, 0L, 200_000L, 500_000L, 1_000_000L, true, 75, true)));

        // 1,600 x 100 >= 2,000.01 x 75 -> low at 75 percent, not at 80.
        assertThat(occupiedBed(service.board(TENANT, ACTOR, "OWNER")).depositLow()).isTrue();
    }

    // ---- SAH-34 patient scope on the board ----

    private BoardBed occupiedBedAs(String role) {
        for (BoardWard ward : service.board(TENANT, ACTOR, role).wards()) {
            for (BoardRoom room : ward.rooms()) {
                for (BoardBed bed : room.beds()) {
                    if ("occ".equals(bed.status())) {
                        return bed;
                    }
                }
            }
        }
        throw new AssertionError("no occupied bed");
    }

    private PatientScope nurseScope(java.util.Set<UUID> wardIds, java.util.Map<UUID, Instant> grants) {
        return new PatientScope(PatientScope.Kind.WARDS, ACTOR, "NURSE", "NURSE", UUID.randomUUID(), wardIds, grants);
    }

    @Test
    void anOccupiedBedOutsideTheNursesWardsKeepsItsStatusAndLosesEveryPatientField() {
        when(guard.scope(TENANT, ACTOR, "NURSE", null)).thenReturn(nurseScope(java.util.Set.of(WARD2), Map.of()));

        BoardBed bed = occupiedBedAs("NURSE");

        assertThat(bed.status()).isEqualTo("occ");
        assertThat(bed.code()).isEqualTo("102");
        assertThat(bed.outsideScope()).isTrue();
        assertThat(bed.admissionId()).isNull();
        assertThat(bed.patientName()).isNull();
        assertThat(bed.dayNumber()).isNull();
        assertThat(bed.doctorShortName()).isNull();
        assertThat(bed.depositLow()).isNull();
        assertThat(bed.goingHomeToday()).isNull();
        verify(patients, never()).findAll(any(), anyCollection());
        verify(team, never()).findAll(any(), anyCollection());
        assertThat(service.board(TENANT, ACTOR, "NURSE").counts().occ()).isEqualTo(1);
        assertThat(service.board(TENANT, ACTOR, "NURSE").wards().get(0).occupied()).isEqualTo(1);
    }

    @Test
    void aBedInTheNursesWardKeepsThePatientAndHasNoOutsideScopeMark() {
        when(guard.scope(TENANT, ACTOR, "NURSE", null)).thenReturn(nurseScope(java.util.Set.of(WARD1), Map.of()));

        BoardBed bed = occupiedBedAs("NURSE");

        assertThat(bed.patientName()).isEqualTo("Test Patient");
        assertThat(bed.admissionId()).isEqualTo(ADMISSION);
        assertThat(bed.outsideScope()).isNull();
    }

    @Test
    void aLiveEmergencyAccessGrantOpensTheBedOfThatPatient() {
        when(guard.scope(TENANT, ACTOR, "NURSE", null))
                .thenReturn(nurseScope(java.util.Set.of(WARD2), Map.of(ADMISSION, NOW.plusSeconds(600))));

        BoardBed bed = occupiedBedAs("NURSE");

        assertThat(bed.patientName()).isEqualTo("Test Patient");
        assertThat(bed.outsideScope()).isNull();
    }

    @Test
    void ownerAndDoctorSeeEveryBedAndTheMarkIsAbsentFromTheJson() throws Exception {
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK"}) {
            assertThat(occupiedBedAs(role).patientName()).as(role).isEqualTo("Test Patient");
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(occupiedBedAs("OWNER"));
        assertThat(json).doesNotContain("outsideScope");
    }

    @Test
    void theBoardAnsweredByReadyAndBlockIsMaskedToo() {
        when(guard.scope(TENANT, ACTOR, "NURSE", null)).thenReturn(nurseScope(java.util.Set.of(WARD2), Map.of()));
        when(beds.findForUpdate(TENANT, BED_CLEAN)).thenReturn(Optional.of(
                new BedRow(BED_CLEAN, WARD1, ROOM_B, "103", "gen", "clean")));

        BedBoard answer = service.markReady(TENANT, ACTOR, "NURSE", BED_CLEAN);

        BoardBed occupied = answer.wards().get(0).rooms().get(0).beds().stream()
                .filter(b -> "occ".equals(b.status())).findFirst().orElseThrow();
        assertThat(occupied.outsideScope()).isTrue();
        assertThat(occupied.patientName()).isNull();
    }
}
