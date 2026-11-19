package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-028 D11: the port the sidebar asks. True only for an ACTIVE mapping to an ACTIVE store. */
class PharmacyAccessAdapterTest {

    private final PharmacyFixture f = new PharmacyFixture();
    private final PharmacyAccessAdapter adapter = new PharmacyAccessAdapter(f.staffRepository);

    @Test
    void nobodyIsMappedInAnEmptyUnit() {
        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, f.nurse.id())).isFalse();
        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, UUID.randomUUID())).isFalse();
    }

    @Test
    void aMappedMemberHasTheEntryUntilTheMappingIsRemovedOrTheStoreSwitchedOff() {
        var central = f.createCentral();
        var opd = f.createStore("Test OPD Store", "OPD", StoreType.SUB_STORE);
        var inOpd = f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        f.map(central, f.nurse, StoreRole.STORE_USER);

        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, f.storeStaff.id())).isTrue();
        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, f.doctor.id())).isFalse();

        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), opd.id(), inOpd.id());
        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, f.storeStaff.id())).isFalse();

        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(adapter.hasActiveMapping(PharmacyFixture.TENANT, f.nurse.id())).isTrue(); // central is still active
    }

    @Test
    void anotherClinicsMappingNeverCounts() {
        var central = f.createCentral();
        f.map(central, f.nurse, StoreRole.STORE_USER);
        assertThat(adapter.hasActiveMapping(PharmacyFixture.OTHER_TENANT, f.nurse.id())).isFalse();
    }
}
