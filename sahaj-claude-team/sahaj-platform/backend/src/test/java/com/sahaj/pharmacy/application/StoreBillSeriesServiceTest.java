package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.SaleInputs.StoreBillSeriesInput;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-1: the bill series of a selling store (docs/api/pharmacy-sales.yaml pharmacyListStoreBillSeries and pharmacyPutStoreBillSeries). */
class StoreBillSeriesServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;
    private final PharmacyViews.Store central = f.createCentral();
    private final PharmacyViews.Store shop = f.createStore("Test Main Pharmacy", "MP", StoreType.PHARMACY);
    private final PharmacyViews.Store annex = f.createStore("Test Annex Pharmacy", "AN", StoreType.PHARMACY);
    private final PharmacyViews.Store ward = f.createStore("Test Ward Store", "WS", StoreType.SUB_STORE);

    private final Map<UUID, StoreBillSeriesRepository.Row> rows = new HashMap<>();
    private final Set<UUID> issuedThisYear = new HashSet<>();
    private final StoreBillSeriesRepository repository = new StoreBillSeriesRepository() {
        @Override
        public List<Row> list(UUID tenantId) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public Optional<Row> find(UUID tenantId, UUID storeId) {
            return Optional.ofNullable(rows.get(storeId));
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, UUID storeId, String prefix, boolean fy, int length, boolean restarts,
                           UUID actor) {
            UUID id = UUID.randomUUID();
            rows.put(storeId, new Row(id, storeId, prefix, fy, length, restarts, 1L));
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, String prefix, boolean fy, int length, boolean restarts,
                              UUID actor) {
            Row old = rows.values().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            if (old.version() != version) {
                return false;
            }
            rows.put(old.storeId(), new Row(id, old.storeId(), prefix, fy, length, restarts, version + 1));
            return true;
        }

        @Override
        public Set<UUID> storesWithNumbers(UUID tenantId) {
            return issuedThisYear;
        }

        @Override
        public Set<UUID> storesIssuedSince(UUID tenantId, Instant since) {
            return issuedThisYear;
        }
    };
    private final StoreBillSeriesService service = new StoreBillSeriesService(f.gate, f.unitService, f.storeRepository,
            repository, m.seriesRepository, f.auditLog, f.time);

    private static StoreBillSeriesInput in(long version, String prefix, boolean fy, int length, boolean restarts) {
        return new StoreBillSeriesInput(version, prefix, fy, BigDecimal.valueOf(length), restarts);
    }

    private SaleViews.StoreBillSeries put(UUID storeId, StoreBillSeriesInput input) {
        return service.put(TENANT, f.owner.userId(), storeId.toString(), input);
    }

    @Test
    void theListHasOneRowPerActiveSaleStoreAndNeverTheCentralOrANonSaleStore() {
        SaleViews.StoreBillSeriesList list = service.list(TENANT, f.pharmacist.userId());

        assertThat(list.items()).extracting(s -> s.store().name()).containsExactly("Test Annex Pharmacy", "Test Main Pharmacy");
        SaleViews.StoreBillSeries row = list.items().get(1);
        assertThat(row.usesUnitSeries()).isTrue();
        assertThat(row.prefix()).isEqualTo("SB/");
        assertThat(row.example()).isEqualTo("SB/26-27/000001");
        assertThat(row.version()).isZero();
        assertThat(row.locked()).isFalse();
    }

    @Test
    void aStoreWithoutItsOwnSeriesFollowsTheSavedUnitBillSeries() {
        m.numberingService.save(TENANT, f.owner.userId(), "BILL",
                new PharmacyMasterInputs.SeriesSave(0L, "UB/", true, BigDecimal.valueOf(5), true));
        assertThat(service.list(TENANT, f.owner.userId()).items().get(0).prefix()).isEqualTo("UB/");
    }

    @Test
    void ownSeriesIsCreatedAtVersionZeroAndThenServedWithItsOwnValues() {
        SaleViews.StoreBillSeries saved = put(shop.id(), in(0, "SBN/", true, 6, true));

        assertThat(saved.usesUnitSeries()).isFalse();
        assertThat(saved.prefix()).isEqualTo("SBN/");
        assertThat(saved.example()).isEqualTo("SBN/26-27/000001");
        assertThat(saved.renderedLength()).isEqualTo(16);
        assertThat(saved.version()).isEqualTo(1L);
        assertThat(service.list(TENANT, f.owner.userId()).items().get(1).usesUnitSeries()).isFalse();
        assertThat(f.auditActions()).contains("PHARMACY_SERIES_CHANGED");
    }

    @Test
    void anOldVersionIs409SettingsChangedWithTheCurrentRow() {
        put(shop.id(), in(0, "SBN/", true, 6, true));

        assertThatThrownBy(() -> put(shop.id(), in(0, "SBX/", true, 6, true)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("settings-changed");
                    assertThat(e.extensions()).containsKey("current");
                });
    }

    @Test
    void aSavedChangeBumpsTheVersionAndAnUnchangedSaveIsANoOp() {
        put(shop.id(), in(0, "SBN/", true, 6, true));
        assertThat(put(shop.id(), in(1, "SBN/", true, 6, true)).version()).isEqualTo(1L);
        assertThat(put(shop.id(), in(1, "SBM/", true, 6, true)).version()).isEqualTo(2L);
    }

    @Test
    void thePrefixMustDifferFromEveryUnitSeriesAndEveryOtherStore() {
        put(shop.id(), in(0, "SBN/", true, 6, true));

        for (String used : new String[] {"SB/", "po-", "SBN/", "sbn/"}) {
            assertThatThrownBy(() -> put(annex.id(), in(0, used, true, 6, true))).as(used)
                    .isInstanceOf(ConflictException.class);
        }
        assertThat(put(shop.id(), in(1, "SBN/", true, 6, true)).prefix()).isEqualTo("SBN/");
    }

    @Test
    void onceANumberWasIssuedThisYearTheSeriesIsLockedButUnchangedValuesStillAnswer() {
        put(shop.id(), in(0, "SBN/", true, 6, true));
        issuedThisYear.add(shop.id());

        assertThatThrownBy(() -> put(shop.id(), in(1, "SBM/", true, 6, true))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getMessage()).isEqualTo("Numbers have already been issued this year."));
        assertThat(put(shop.id(), in(1, "SBN/", true, 6, true)).locked()).isTrue();
    }

    @Test
    void aStoreThatCannotSellIsRefusedWithAFieldMessage() {
        for (PharmacyViews.Store store : new PharmacyViews.Store[] {central, ward}) {
            assertThatThrownBy(() -> put(store.id(), in(0, "ZZ/", true, 6, true))).isInstanceOf(InvalidFieldsException.class)
                    .satisfies(e -> assertThat(((InvalidFieldsException) e).errors())
                            .contains(new InvalidFieldsException.FieldMessage("storeId", "Choose a store where sale is allowed.")));
        }
    }

    @Test
    void fieldErrorsComeTogetherAndAnUnknownStoreIs404() {
        assertThatThrownBy(() -> put(shop.id(), new StoreBillSeriesInput(null, "bad prefix!", null, BigDecimal.valueOf(3), true)))
                .isInstanceOf(InvalidFieldsException.class)
                .satisfies(e -> assertThat(((InvalidFieldsException) e).errors())
                        .extracting(InvalidFieldsException.FieldMessage::field)
                        .contains("version", "prefix", "sequenceLength", "financialYearPart"));
        assertThatThrownBy(() -> put(UUID.randomUUID(), in(0, "ZZ/", true, 6, true))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restartNeedsTheYearPartAndTheRenderedNumberFitsSixteenCharacters() {
        assertThatThrownBy(() -> put(shop.id(), in(0, "ZZ/", false, 6, true))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> put(shop.id(), in(0, "ABCDEF", true, 8, true))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void onlyTheOwnerSavesAndAPharmacistCanRead() {
        assertThatThrownBy(() -> service.put(TENANT, f.pharmacist.userId(), shop.id().toString(), in(0, "ZZ/", true, 6, true)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service.list(TENANT, f.pharmacist.userId()).items()).isNotEmpty();
        assertThatThrownBy(() -> service.list(TENANT, f.nurse.userId())).isInstanceOf(ForbiddenException.class);
        assertThat(DocType.BILL.defaultPrefix()).isEqualTo("SB/");
    }

    @Test
    void aSwitchedOffStoreStaysListedOnlyWhileItsOwnSeriesIssuedNumbers() {
        put(shop.id(), in(0, "SBN/", true, 6, true));
        f.storeService.switchOff(TENANT, f.owner.userId(), shop.id());
        assertThat(service.list(TENANT, f.owner.userId()).items()).extracting(s -> s.store().name())
                .containsExactly("Test Annex Pharmacy");

        issuedThisYear.add(shop.id());
        List<SaleViews.StoreBillSeries> items = service.list(TENANT, f.owner.userId()).items();
        assertThat(items).extracting(s -> s.store().name()).containsExactly("Test Annex Pharmacy", "Test Main Pharmacy");
        assertThat(items.get(1).storeActive()).isFalse();
    }
}
