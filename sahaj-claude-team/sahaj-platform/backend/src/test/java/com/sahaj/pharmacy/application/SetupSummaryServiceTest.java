package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyViews.SetupSummary;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import org.junit.jupiter.api.Test;

/** SAH-78-1: the live subtitles ("7 stores · 24 staff mapped", "12 active suppliers"), counted by the server. */
class SetupSummaryServiceTest {

    private final PharmacyFixture f = new PharmacyFixture();

    private SetupSummary get(PharmacyFixture.Person p) {
        return f.summaryService.get(PharmacyFixture.TENANT, p.userId());
    }

    @Test
    void anEmptyUnitCountsZeroAndWritesNothing() {
        assertThat(get(f.owner)).isEqualTo(new SetupSummary(0, 0, 0));
        assertThat(f.writes).isEmpty();
    }

    @Test
    void storesAreActiveOnesAndStaffAreDistinctMembersWhoCanSignInInActiveStores() {
        var central = f.createCentral();
        var ward = f.createStore("Test Ward Store", "WRD", StoreType.WARD);
        var opd = f.createStore("Test OPD Store", "OPD", StoreType.SUB_STORE);
        f.map(central, f.nurse, StoreRole.STORE_USER);
        f.map(ward, f.nurse, StoreRole.STORE_USER);       // the same person twice counts once
        f.map(ward, f.doctor, StoreRole.APPROVER);
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        f.vendorCount = 12;

        assertThat(get(f.owner)).isEqualTo(new SetupSummary(3, 3, 12));

        f.switchOff(f.doctor);                              // cannot sign in: not counted
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id()); // its mapping stops counting
        assertThat(get(f.pharmacist)).isEqualTo(new SetupSummary(2, 1, 12));
    }

    @Test
    void aRemovedMappingIsNotCounted() {
        var central = f.createCentral();
        var m = f.map(central, f.nurse, StoreRole.STORE_USER);
        assertThat(get(f.owner).mappedStaffCount()).isEqualTo(1);
        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), central.id(), m.id());
        assertThat(get(f.owner).mappedStaffCount()).isZero();
    }

    @Test
    void ownerPharmacistAndCentralStoreUsersReadAndEveryoneElseIsForbidden() {
        var central = f.createCentral();
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        f.map(central, f.nurse, StoreRole.STORE_USER);

        get(f.owner);
        get(f.pharmacist);
        get(f.storeStaff);
        assertThatThrownBy(() -> get(f.nurse)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> get(f.doctor)).isInstanceOf(ForbiddenException.class);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> get(f.owner)).isInstanceOf(ModuleOffException.class);
    }
}
