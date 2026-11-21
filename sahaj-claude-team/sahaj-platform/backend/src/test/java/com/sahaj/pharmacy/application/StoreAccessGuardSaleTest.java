package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import org.junit.jupiter.api.Test;

/** SAH-80 rules 24 and 25: the guard for sale operations and the CASHIER mapping check. */
class StoreAccessGuardSaleTest {

    private final PharmacyFixture f = new PharmacyFixture();
    private final PharmacyViews.Store central = f.createCentral();
    private final PharmacyViews.Store shop = f.createStore("Test Main Pharmacy", "MP", StoreType.PHARMACY);
    private final PharmacyViews.Store ward = f.createStore("Test Ward Store", "WS", StoreType.SUB_STORE);
    private final PharmacyFixture.Person billing = f.person("BILLING", "Test Billing One", true);

    private PharmacyCaller caller(PharmacyFixture.Person p) {
        return f.gate.require(PharmacyFixture.TENANT, p.userId(), AccessLevel.PHARMACY_ACCESS);
    }

    @Test
    void aCashierMayRequireSellInASaleStore() {
        f.map(shop, billing, StoreRole.CASHIER);
        assertThat(f.guard.requireSale(caller(billing), shop.id(), StoreCapability.SELL).id()).isEqualTo(shop.id());
    }

    @Test
    void aCashierIsRefusedAScheduledSaleWith403() {
        f.map(shop, billing, StoreRole.CASHIER);
        assertThatThrownBy(() -> f.guard.requireSale(caller(billing), shop.id(), StoreCapability.SELL_SCHEDULED))
                .isInstanceOf(StoreAccessDeniedException.class);
    }

    @Test
    void aStoreThatDoesNotAllowSaleAnswers409AfterTheMappingCheck() {
        f.map(ward, f.pharmacist, StoreRole.STORE_USER);
        assertThatThrownBy(() -> f.guard.requireSale(caller(f.pharmacist), ward.id(), StoreCapability.SELL))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("sale-not-allowed");
                    assertThat(e.getMessage()).isEqualTo("Sale is not allowed in this store.");
                });
    }

    @Test
    void anUnmappedStoreIsStillA404NotA409() {
        f.map(shop, f.pharmacist, StoreRole.STORE_USER);
        assertThatThrownBy(() -> f.guard.requireSale(caller(f.pharmacist), ward.id(), StoreCapability.SELL))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theOwnerReachesASaleStoreWithoutAMappingButNotAWardStore() {
        assertThat(f.guard.requireSale(caller(f.owner), shop.id(), StoreCapability.CANCEL_BILL).id()).isEqualTo(shop.id());
        assertThatThrownBy(() -> f.guard.requireSale(caller(f.owner), ward.id(), StoreCapability.SELL))
                .isInstanceOf(PharmacyProblemException.class);
        assertThatThrownBy(() -> f.guard.requireSale(caller(f.owner), shop.id(), StoreCapability.SELL_SCHEDULED))
                .isInstanceOf(StoreAccessDeniedException.class);
    }

    @Test
    void aCashierMappingIsRefusedAtTheCentralStoreAtANonSaleStoreAndForOtherTeamRoles() {
        for (PharmacyViews.Store store : new PharmacyViews.Store[] {central, ward}) {
            assertThatThrownBy(() -> f.map(store, billing, StoreRole.CASHIER)).isInstanceOf(InvalidFieldsException.class)
                    .satisfies(e -> assertThat(((InvalidFieldsException) e).errors().get(0).message())
                            .isEqualTo("This person cannot be a Cashier."));
        }
        assertThatThrownBy(() -> f.map(shop, f.nurse, StoreRole.CASHIER)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theCandidateListOffersCashierOnlyForBillingAndStoreStaffAtASaleStore() {
        var items = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), shop.id().toString(), null).items();
        assertThat(items.stream().filter(c -> c.role().equals("STORE_STAFF")).findFirst().orElseThrow().allowedStoreRoles())
                .contains(StoreRole.CASHIER);
        assertThat(items.stream().filter(c -> c.role().equals("NURSE")).findFirst().orElseThrow().allowedStoreRoles())
                .doesNotContain(StoreRole.CASHIER);
        var atWard = f.staffService.candidates(PharmacyFixture.TENANT, f.owner.userId(), ward.id().toString(), null).items();
        assertThat(atWard.stream().filter(c -> c.role().equals("STORE_STAFF")).findFirst().orElseThrow().allowedStoreRoles())
                .doesNotContain(StoreRole.CASHIER);
    }
}
