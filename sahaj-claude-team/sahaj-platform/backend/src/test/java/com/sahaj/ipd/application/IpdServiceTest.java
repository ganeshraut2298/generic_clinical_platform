package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class IpdServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-11T05:30:00.123456Z");

    private WardRepository wards;
    private BedRepository beds;
    private IpdSettingsRepository settings;
    private ModuleEntitlementPort entitlements;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private IpdService service;
    private List<WardRow> wardRows;
    private List<RoomRow> roomRows;
    private List<BedRow> bedRows;

    @BeforeEach
    void setUp() {
        wards = mock(WardRepository.class);
        beds = mock(BedRepository.class);
        settings = mock(IpdSettingsRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new IpdService(wards, beds, settings, ownerGuard, auditLog,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), new IpdCatalog(settings));
        wardRows = new ArrayList<>();
        roomRows = new ArrayList<>();
        bedRows = new ArrayList<>();
        when(wards.findWards(TENANT)).thenAnswer(i -> wardRows);
        when(wards.findRooms(TENANT)).thenAnswer(i -> roomRows);
        when(beds.findAll(TENANT)).thenAnswer(i -> bedRows);
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(settings.findConfig(TENANT)).thenReturn(Optional.empty());
        state("on", 25);
    }

    private void state(String ipdState, int limit) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, limit));
    }

    private WardRow ward(String name) {
        WardRow w = new WardRow(UUID.randomUUID(), name, "Nursing station 1");
        wardRows.add(w);
        when(wards.findWard(TENANT, w.id())).thenReturn(Optional.of(w));
        return w;
    }

    private RoomRow room(WardRow ward, String name) {
        RoomRow r = new RoomRow(UUID.randomUUID(), ward.id(), name);
        roomRows.add(r);
        when(wards.findRoom(TENANT, r.id())).thenReturn(Optional.of(r));
        return r;
    }

    private BedRow bed(WardRow ward, RoomRow room, String code, String category, String status) {
        BedRow b = new BedRow(UUID.randomUUID(), ward.id(), room == null ? null : room.id(), code, category, status);
        bedRows.add(b);
        when(beds.find(TENANT, b.id())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void layoutSynthesizesDefaultCategoriesAndConfig() {
        IpdLayout layout = service.layout(TENANT);

        assertThat(layout.state()).isEqualTo("on");
        assertThat(layout.roomLevel()).isTrue();
        assertThat(layout.bedLimit()).isEqualTo(25);
        assertThat(layout.charges()).isEqualTo(new IpdLayout.Charges("midnight", 30000, 50000));
        assertThat(layout.categories()).extracting(IpdLayout.Category::key).containsExactly("gen", "semi", "pvt", "icu");
        assertThat(layout.categories()).extracting(IpdLayout.Category::ratePaise)
                .containsExactly(80000L, 150000L, 250000L, 500000L);
        assertThat(layout.categories()).extracting(c -> c.multiplier().toPlainString())
                .containsExactly("1.00", "1.25", "1.50", "2.00");
        assertThat(layout.categories()).extracting(IpdLayout.Category::name)
                .containsExactly("General", "Semi-private", "Private", "ICU");
    }

    @Test
    void layoutNestsRoomsAndFlatBedsWithCounts() {
        WardRow w = ward("General ward");
        RoomRow r = room(w, "Room 101");
        bed(w, r, "101-A", "gen", "free");
        bed(w, null, "GW-B", "icu", "free");

        IpdLayout layout = service.layout(TENANT);

        IpdLayout.Ward view = layout.wards().get(0);
        assertThat(view.bedCount()).isEqualTo(2);
        assertThat(view.beds()).hasSize(2);
        assertThat(view.rooms().get(0).beds()).extracting(IpdLayout.Bed::code).containsExactly("101-A");
        assertThat(layout.bedCount()).isEqualTo(2);
        assertThat(layout.categories().get(0).beds()).isEqualTo(1);
        assertThat(layout.categories().get(3).beds()).isEqualTo(1);
    }

    @Test
    void offStateAnswersModuleOffOnReadAndWrite() {
        state("off", 25);

        assertThatThrownBy(() -> service.layout(TENANT)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "A")).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void readonlyAllowsReadAndRejectsEveryWrite() {
        state("readonly", 25);
        UUID id = UUID.randomUUID();

        assertThat(service.layout(TENANT).state()).isEqualTo("readonly");
        List<Runnable> writes = List.of(
                () -> service.addWard(TENANT, ACTOR, "A"),
                () -> service.addRoom(TENANT, ACTOR, id),
                () -> service.addBed(TENANT, ACTOR, id, null),
                () -> service.changeBedCategory(TENANT, ACTOR, id, "gen"),
                () -> service.removeBed(TENANT, ACTOR, id),
                () -> service.setRoomLevel(TENANT, ACTOR, false),
                () -> service.updateCategory(TENANT, ACTOR, "gen", 1000L, BigDecimal.ONE),
                () -> service.updateCharges(TENANT, ACTOR, "midnight", 1L, 1L));
        for (Runnable write : writes) {
            assertThatThrownBy(write::run).isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.problemType()).isEqualTo("ipd-readonly");
                assertThat(e.getMessage()).isEqualTo("IPD is read-only. Nothing can be changed.");
            });
        }
        verify(wards, never()).lockTenant(any());
    }

    @Test
    void trialStateCanWrite() {
        state("trial", 25);

        service.addWard(TENANT, ACTOR, "General ward");

        verify(wards).insertWard(eq(TENANT), any(), eq("General ward"), eq(ACTOR), eq(NOW_MICROS));
    }

    @Test
    void nonOwnerIsRefusedBeforeAnythingChanges() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "A")).isInstanceOf(AccessDeniedException.class);
        verify(wards, never()).insertWard(any(), any(), anyString(), any(), any());
    }

    @Test
    void addWardWithRoomsOnNamesFirstRoomFromWardCountAndLocksTenant() {
        ward("Existing ward");
        // the repository mock does not add rows, so emulate the inserted ward
        org.mockito.Mockito.doAnswer(i -> {
            wardRows.add(new WardRow(i.getArgument(1), i.getArgument(2), "Nursing station 1"));
            return null;
        }).when(wards).insertWard(eq(TENANT), any(), anyString(), any(), any());

        service.addWard(TENANT, ACTOR, "  Female ward  ");

        verify(wards).lockTenant(TENANT);
        verify(wards).insertRoom(eq(TENANT), any(), any(), eq("Room 201"), eq(ACTOR), eq(NOW_MICROS));
        verify(auditLog).record(eq("IPD_WARD_ADDED"), eq("IPD_WARD"), any(), eq(null), any());
        verify(auditLog).record(eq("IPD_ROOM_ADDED"), eq("IPD_ROOM"), any(), eq(null), any());
    }

    @Test
    void addWardWithRoomsOffCreatesNoRoom() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(false, "midnight", 30000, 50000)));

        service.addWard(TENANT, ACTOR, "Ward");

        verify(wards, never()).insertRoom(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void addWardRejectsControlAndTextDirectionCharacters() {
        for (String bad : new String[] {"Ward\nB", "Wa\u0000rd","Ward\u0085B", "ICU \u202Egnp", "A\u2067B", "A\u200EB",
                "A\tB", "A\u007FB"}) {
            assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("name"));
        }
        verify(wards, never()).insertWard(any(), any(), any(), any(), any());
        service.addWard(TENANT, ACTOR, "Maternity \u0915\u0930 ward-2 (east)");
        verify(wards).insertWard(eq(TENANT), any(), eq("Maternity \u0915\u0930 ward-2 (east)"), eq(ACTOR), any());
    }

    @Test
    void addWardChecksTheRawTextBeforeTrimmingSoAStrayControlAtAnEndIsRefused() {
        for (String bad : new String[] {"Ward B\n", "\tWard B", "Ward B\u0000", " \u0085Ward B"}) {
            assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("name"));
        }
        verify(wards, never()).insertWard(any(), any(), any(), any(), any());
        service.addWard(TENANT, ACTOR, "  Ward B  ");
        verify(wards).insertWard(eq(TENANT), any(), eq("Ward B"), eq(ACTOR), any());
    }

    @Test
    void addWardAcceptsDevanagariConjunctsWithAJoinerAfterAVirama() {
        String withZwj = "\u0915\u094D\u200D\u0937";
        String withZwnj = "\u0915\u094D\u200C\u0937";

        service.addWard(TENANT, ACTOR, withZwj);
        service.addWard(TENANT, ACTOR, withZwnj);

        verify(wards).insertWard(eq(TENANT), any(), eq(withZwj), eq(ACTOR), any());
        verify(wards).insertWard(eq(TENANT), any(), eq(withZwnj), eq(ACTOR), any());
    }

    @Test
    void addWardRefusesJoinersThatAreFirstLastDoubledOrNotAfterAVirama() {
        for (String bad : new String[] {"IC\u200DU", "IC\u200CU", "\u200D\u0915", "\u0915\u094D\u200D",
                "\u0915\u094D\u200D\u200D\u0937", "\u0915\u094D\u200C\u200D\u0937", "\u0915\u200D\u0937"}) {
            assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("name");
                        assertThat(e.getMessage())
                                .isEqualTo("Ward name cannot contain control or text-direction characters");
                    });
        }
        verify(wards, never()).insertWard(any(), any(), any(), any(), any());
    }

    @Test
    void addWardStoresAndComparesTheNfcName() {
        when(wards.wardNameExists(TENANT, "Caf\u00E9 ward")).thenReturn(true);

        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "Cafe\u0301 ward"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ward-exists"));
        service.addWard(TENANT, ACTOR, "Ward\u212B");

        verify(wards).wardNameExists(TENANT, "Caf\u00E9 ward");
        verify(wards).insertWard(eq(TENANT), any(), eq("Ward\u00C5"), eq(ACTOR), any());
    }

    @Test
    void addWardRejectsEmptyLongAndDuplicateNames() {
        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "  "))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("name");
                    assertThat(e.getMessage()).isEqualTo("Type a ward name first");
                });
        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "x".repeat(81)))
                .isInstanceOf(InvalidInputException.class);
        when(wards.wardNameExists(TENANT, "Dup")).thenReturn(true);
        assertThatThrownBy(() -> service.addWard(TENANT, ACTOR, "Dup"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ward-exists"));
    }

    @Test
    void addRoomUsesHighestNumberPlusOne() {
        WardRow w = ward("A");
        room(w, "Room 101");
        room(w, "Room 105");

        service.addRoom(TENANT, ACTOR, w.id());

        verify(wards).insertRoom(eq(TENANT), any(), eq(w.id()), eq("Room 106"), eq(ACTOR), any());
        verify(auditLog).record(eq("IPD_ROOM_ADDED"), eq("IPD_ROOM"), any(), eq(null), any());
    }

    @Test
    void addRoomWithoutNumberedRoomsUsesWardPosition() {
        ward("A");
        WardRow second = ward("B");
        room(second, "B ward");

        service.addRoom(TENANT, ACTOR, second.id());

        verify(wards).insertRoom(eq(TENANT), any(), eq(second.id()), eq("Room 201"), eq(ACTOR), any());
    }

    @Test
    void addRoomUnknownWardIsNotFound() {
        assertThatThrownBy(() -> service.addRoom(TENANT, ACTOR, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addBedInRoomUsesRoomNumberAndFirstFreeLetter() {
        WardRow w = ward("General ward");
        RoomRow r = room(w, "Room 101");
        bed(w, r, "101-A", "gen", "free");
        bed(w, r, "101-B", "gen", "free");

        service.addBed(TENANT, ACTOR, w.id(), r.id());

        verify(beds).insert(eq(TENANT), any(), eq(w.id()), eq(r.id()), eq("101-C"), eq("gen"), eq(ACTOR),
                eq(NOW_MICROS));
        verify(auditLog).record(eq("IPD_BED_ADDED"), eq("IPD_BED"), any(), eq(null), any());
    }

    @Test
    void letterIsFreeAcrossTheWholeClinic() {
        WardRow a = ward("A ward");
        RoomRow ra = room(a, "Room 101");
        WardRow b = ward("B ward");
        RoomRow rb = room(b, "Room 201");
        bed(a, ra, "101-A", "gen", "free");

        service.addBed(TENANT, ACTOR, b.id(), rb.id());

        verify(beds).insert(eq(TENANT), any(), eq(b.id()), eq(rb.id()), eq("201-B"), any(), any(), any());
    }

    @Test
    void roomLevelOnWithoutRoomIdUsesWardsFirstRoom() {
        WardRow w = ward("ICU");
        RoomRow first = room(w, "Room 101");
        room(w, "Room 102");

        service.addBed(TENANT, ACTOR, w.id(), null);

        verify(beds).insert(eq(TENANT), any(), eq(w.id()), eq(first.id()), eq("101-A"), eq("icu"), any(), any());
    }

    @Test
    void roomLevelOnWithNoRoomCreatesOneNamedAsTheWard() {
        WardRow w = ward("Private ward");

        service.addBed(TENANT, ACTOR, w.id(), null);

        verify(wards).insertRoom(eq(TENANT), any(), eq(w.id()), eq("Private ward"), eq(ACTOR), any());
        verify(beds).insert(eq(TENANT), any(), eq(w.id()), any(), eq("P-A"), eq("pvt"), any(), any());
    }

    @Test
    void roomLevelOffWithoutRoomKeepsRoomNullAndUsesWardCapitals() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(false, "midnight", 30000, 50000)));
        WardRow w = ward("General ward · Female");

        service.addBed(TENANT, ACTOR, w.id(), null);

        verify(beds).insert(eq(TENANT), any(), eq(w.id()), eq(null), eq("GF-A"), eq("gen"), any(), any());
        verify(wards, never()).insertRoom(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void roomLevelOffWithRoomIdKeepsTheRoom() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(false, "midnight", 30000, 50000)));
        WardRow w = ward("ward");
        RoomRow r = room(w, "Room 101");

        service.addBed(TENANT, ACTOR, w.id(), r.id());

        verify(beds).insert(eq(TENANT), any(), eq(w.id()), eq(r.id()), eq("101-A"), any(), any(), any());
    }

    @Test
    void bedWithoutCapitalsFallsBackToB() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(false, "midnight", 30000, 50000)));
        WardRow w = ward("ward");

        service.addBed(TENANT, ACTOR, w.id(), null);

        verify(beds).insert(eq(TENANT), any(), eq(w.id()), eq(null), eq("B-A"), any(), any(), any());
    }

    @Test
    void bedLimitIsEnforcedWithTheExactMessage() {
        state("on", 2);
        WardRow w = ward("A");
        RoomRow r = room(w, "Room 101");
        bed(w, r, "101-A", "gen", "free");
        bed(w, r, "101-B", "gen", "free");

        assertThatThrownBy(() -> service.addBed(TENANT, ACTOR, w.id(), r.id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("bed-limit");
                    assertThat(e.getMessage()).isEqualTo("Your plan allows 2 beds. Ask Sahaj to raise the limit.");
                });
        verify(beds, never()).insert(any(), any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void addBedUnknownWardOrForeignRoomIsNotFoundAndMissingWardIsInvalid() {
        WardRow w = ward("A");
        WardRow other = ward("B");
        RoomRow foreign = room(other, "Room 201");

        assertThatThrownBy(() -> service.addBed(TENANT, ACTOR, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addBed(TENANT, ACTOR, w.id(), foreign.id()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addBed(TENANT, ACTOR, null, null)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void lettersFallBackToTwoLettersWhenAlphabetIsUsed() {
        List<BedRow> used = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            used.add(new BedRow(UUID.randomUUID(), UUID.randomUUID(), null, "X-" + c, "gen", "free"));
        }

        assertThat(IpdService.freeLetters(used)).isEqualTo("AA");
        assertThat(IpdService.freeLetters(List.of())).isEqualTo("A");
    }

    @Test
    void helpersDeriveCapitalsCategoriesAndNumbers() {
        assertThat(IpdService.capitals("Intensive Care Unit Extra")).isEqualTo("ICU");
        assertThat(IpdService.capitals("lower")).isEqualTo("B");
        assertThat(IpdService.defaultCategory("Cardiac ICU")).isEqualTo("icu");
        assertThat(IpdService.defaultCategory("PRIVATE wing")).isEqualTo("pvt");
        assertThat(IpdService.defaultCategory("Male")).isEqualTo("gen");
        assertThat(IpdService.firstNumber("Room 305")).isEqualTo(305);
        assertThat(IpdService.firstNumber("Annex")).isEqualTo(-1);
    }

    @Test
    void changeBedCategoryUpdatesAndAudits() {
        WardRow w = ward("A");
        BedRow b = bed(w, null, "A-A", "gen", "free");

        service.changeBedCategory(TENANT, ACTOR, b.id(), "pvt");

        verify(beds).updateCategory(TENANT, b.id(), "pvt");
        verify(auditLog).record(eq("IPD_BED_CATEGORY_CHANGED"), eq("IPD_BED"), eq(b.id()), any(), any());
    }

    @Test
    void changeBedCategoryRejectsUnknownKeyAndMissingBed() {
        assertThatThrownBy(() -> service.changeBedCategory(TENANT, ACTOR, UUID.randomUUID(), "vip"))
                .isInstanceOf(InvalidInputException.class);
        when(beds.find(eq(TENANT), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeBedCategory(TENANT, ACTOR, UUID.randomUUID(), "gen"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeBedDeletesFreeBedAndAudits() {
        WardRow w = ward("A");
        BedRow b = bed(w, null, "A-A", "gen", "free");

        service.removeBed(TENANT, ACTOR, b.id());

        verify(beds).delete(TENANT, b.id());
        verify(auditLog).record(eq("IPD_BED_REMOVED"), eq("IPD_BED"), eq(b.id()), any(), eq(null));
    }

    @Test
    void removeOccupiedBedIsConflict() {
        WardRow w = ward("A");
        BedRow b = bed(w, null, "A-A", "gen", "occ");

        assertThatThrownBy(() -> service.removeBed(TENANT, ACTOR, b.id()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("bed-occupied"));
        verify(beds, never()).delete(any(), any());
    }

    @Test
    void removeBedWithAdmissionHistoryIsConflictBedHasHistory() {
        WardRow w = ward("A");
        BedRow b = bed(w, null, "A-A", "gen", "free");
        when(beds.hasAdmissionHistory(TENANT, b.id())).thenReturn(true);

        assertThatThrownBy(() -> service.removeBed(TENANT, ACTOR, b.id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("bed-has-history");
                    assertThat(e.getMessage()).isEqualTo("This bed has admission history and cannot be removed.");
                });
        verify(beds, never()).delete(any(), any());
        verify(auditLog, never()).record(eq("IPD_BED_REMOVED"), any(), any(), any(), any());
    }

    @Test
    void foreignKeyViolationOnDeleteIsAlsoBedHasHistory() {
        WardRow w = ward("A");
        BedRow b = bed(w, null, "A-A", "gen", "free");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("fk_admission_bed"))
                .when(beds).delete(TENANT, b.id());

        assertThatThrownBy(() -> service.removeBed(TENANT, ACTOR, b.id()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("bed-has-history"));
    }

    @Test
    void roomLevelSwitchKeepsOtherSettingsAndAudits() {
        service.setRoomLevel(TENANT, ACTOR, false);

        ArgumentCaptor<ConfigRow> saved = ArgumentCaptor.forClass(ConfigRow.class);
        verify(settings).upsertConfig(eq(TENANT), saved.capture(), eq(ACTOR), eq(NOW_MICROS));
        assertThat(saved.getValue()).isEqualTo(new ConfigRow(false, "midnight", 30000, 50000));
        verify(auditLog).record(eq("IPD_ROOM_LEVEL_CHANGED"), eq("IPD_CONFIG"), eq(null), any(), any());
        assertThatThrownBy(() -> service.setRoomLevel(TENANT, ACTOR, null)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void roomLevelUnchangedWritesNothing() {
        service.setRoomLevel(TENANT, ACTOR, true);

        verify(settings, never()).upsertConfig(any(), any(), any(), any());
    }

    @Test
    void updateCategoryUpsertsWithScaledMultiplierAndAudits() {
        service.updateCategory(TENANT, ACTOR, "semi", 200000L, new BigDecimal("1.5"));

        verify(settings).upsertCategory(eq(TENANT), eq("semi"), eq(200000L), eq(new BigDecimal("1.50")), eq(ACTOR),
                eq(NOW_MICROS));
        verify(auditLog).record(eq("IPD_CATEGORY_CHARGE_CHANGED"), eq("IPD_BED_CATEGORY"), eq(null), any(), any());
    }

    @Test
    void updateCategoryUsesSavedRowAsBefore() {
        when(settings.findCategories(TENANT)).thenReturn(List.of(new CategoryRow("gen", 100000L, new BigDecimal("1.00"))));

        service.updateCategory(TENANT, ACTOR, "gen", 100000L, new BigDecimal("1"));

        verify(settings, never()).upsertCategory(any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void updateCategoryValidation() {
        assertThatThrownBy(() -> service.updateCategory(TENANT, ACTOR, "vip", 1000L, BigDecimal.ONE))
                .isInstanceOf(NotFoundException.class);
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", -100L, BigDecimal.ONE), "ratePaise");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 10000100L, BigDecimal.ONE), "ratePaise");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 150L, BigDecimal.ONE), "ratePaise");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", null, BigDecimal.ONE), "ratePaise");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 1000L, new BigDecimal("0.49")), "multiplier");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 1000L, new BigDecimal("5.01")), "multiplier");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 1000L, new BigDecimal("1.255")), "multiplier");
        assertField(() -> service.updateCategory(TENANT, ACTOR, "gen", 1000L, null), "multiplier");
    }

    @Test
    void updateCategoryAcceptsBoundaries() {
        service.updateCategory(TENANT, ACTOR, "gen", 10000000L, new BigDecimal("5"));
        service.updateCategory(TENANT, ACTOR, "icu", 0L, new BigDecimal("0.50"));
        service.updateCategory(TENANT, ACTOR, "pvt", 1000L, new BigDecimal("1.500"));

        verify(settings).upsertCategory(eq(TENANT), eq("gen"), eq(10000000L), eq(new BigDecimal("5.00")), any(), any());
    }

    @Test
    void updateChargesUpsertsAndAudits() {
        service.updateCharges(TENANT, ACTOR, "24h", 40000L, 60000L);

        ArgumentCaptor<ConfigRow> saved = ArgumentCaptor.forClass(ConfigRow.class);
        verify(settings).upsertConfig(eq(TENANT), saved.capture(), eq(ACTOR), eq(NOW_MICROS));
        assertThat(saved.getValue()).isEqualTo(new ConfigRow(true, "24h", 40000, 60000));
        verify(auditLog).record(eq("IPD_CHARGES_CHANGED"), eq("IPD_CONFIG"), eq(null), any(), any());
    }

    @Test
    void updateChargesValidation() {
        assertField(() -> service.updateCharges(TENANT, ACTOR, "noon", 1L, 1L), "bedDayCut");
        assertField(() -> service.updateCharges(TENANT, ACTOR, null, 1L, 1L), "bedDayCut");
        assertField(() -> service.updateCharges(TENANT, ACTOR, "24h", -1L, 1L), "nursingPaise");
        assertField(() -> service.updateCharges(TENANT, ACTOR, "24h", 1L, 10000001L), "visitPaise");
        assertField(() -> service.updateCharges(TENANT, ACTOR, "24h", 1L, null), "visitPaise");
    }

    private static void assertField(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.field()).isEqualTo(field));
    }
}
