package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyViews.Context;
import com.sahaj.pharmacy.application.PharmacyViews.Store;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78-1 and 78-4: GET /context (the one capability answer) and the working store. Mapped stores only, the preference is
 * a convenience, an unmapped store is 404 and changes nothing.
 */
class ContextServiceTest {

    private final PharmacyFixture f = new PharmacyFixture();
    private final Store central = f.createCentral();
    private final Store pharmacy = f.createStore("Test Main Pharmacy", "PHARM", StoreType.PHARMACY);
    private final Store opd = f.createStore("Test OPD Store", "OPD", StoreType.SUB_STORE);

    private Context get(PharmacyFixture.Person p) {
        return f.contextService.get(PharmacyFixture.TENANT, p.userId());
    }

    private Context pick(PharmacyFixture.Person p, UUID storeId) {
        return f.contextService.setWorkingStore(PharmacyFixture.TENANT, p.userId(), new PharmacyInputs.WorkingStore(storeId));
    }

    // ---- the unit header and the caller ----

    @Test
    void theOwnerGetsEditAccessEveryCapabilityAndNoStoresUntilMapped() {
        Context c = get(f.owner);

        assertThat(c.unit().name()).isEqualTo("Test Clinic Baner");
        assertThat(c.unit().code()).isEqualTo("SUN");
        assertThat(c.unit().integrationMode()).isEqualTo(IntegrationMode.INTEGRATED);
        assertThat(c.caller().membershipId()).isEqualTo(f.owner.id());
        assertThat(c.caller().role()).isEqualTo("OWNER");
        assertThat(c.caller().roleLabel()).isEqualTo("Owner");
        assertThat(c.caller().setupAccess()).isEqualTo("EDIT");
        assertThat(c.capabilities()).containsExactly("SETUP_VIEW", "SETUP_EDIT", "OPENING_STOCK_UPLOAD",
                "OPENING_STOCK_APPROVE", "INVENTORY", "APPROVALS_INBOX", "PURCHASE", "STOCK_REPORTS", "PHARMACY", "POS",
                "SALES_RETURN", "REGISTERS", "COMPLIANCE", "COMMAND_CENTRE", "SALES_REPORTS", "PATIENT_LINK", "ALLERGY_CHECK",
                "OPD_QUEUE");
        assertThat(c.stores()).isEmpty(); // the owner's select lists mapped stores only
        assertThat(c.workingStoreId()).isNull();
        assertThat(c.mustPickWorkingStore()).isFalse();
        assertThat(c.fixedRules().fefo()).isTrue();
        assertThat(c.fixedRules().negativeStockBlocked()).isTrue();
    }

    @Test
    void theModeComesFromTheUnitSettings() {
        f.unitService.save(PharmacyFixture.TENANT, f.owner.userId(),
                PharmacyInputs.UnitSettings.of(0L, null, null, null, IntegrationMode.STANDALONE));
        assertThat(get(f.owner).unit().integrationMode()).isEqualTo(IntegrationMode.STANDALONE);
    }

    @Test
    void aPharmacistWithNoMappingHasViewAccessAndNoStoresAndNoUploadCapability() {
        Context c = get(f.pharmacist);
        assertThat(c.caller().setupAccess()).isEqualTo("VIEW");
        assertThat(c.capabilities()).containsExactly("SETUP_VIEW", "COMPLIANCE");
        assertThat(c.stores()).isEmpty();
        assertThat(c.mustPickWorkingStore()).isFalse();
    }

    @Test
    void aPharmacistInTwoStoresMatchesTheContractExample() {
        f.map(pharmacy, f.pharmacist, StoreRole.STORE_USER);
        f.map(opd, f.pharmacist, StoreRole.STORE_USER);

        Context c = get(f.pharmacist);

        assertThat(c.capabilities()).containsExactly("SETUP_VIEW", "OPENING_STOCK_UPLOAD", "INVENTORY", "STOCK_REPORTS",
                "PHARMACY", "POS", "SALES_RETURN", "REGISTERS", "COMPLIANCE", "COMMAND_CENTRE", "SALES_REPORTS", "PATIENT_LINK",
                "ALLERGY_CHECK", "OPD_QUEUE");
        assertThat(c.stores()).extracting(PharmacyViews.MyStore::name).containsExactly("Test Main Pharmacy", "Test OPD Store");
        assertThat(c.stores().get(0).access().receiveScheduleX()).isTrue();
        assertThat(c.stores().get(0).access().consumeReturn()).isFalse();
        assertThat(c.stores().get(1).access().consumeReturn()).isFalse(); // the sub-store does not allow consumption by default
        assertThat(c.workingStoreId()).isNull();
        assertThat(c.mustPickWorkingStore()).isTrue();
    }

    @Test
    void aStoreStaffMemberSeesOnlyTheirStoresWithNoSetupAccessAndNoSetupCapability() {
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);

        Context c = get(f.storeStaff);

        assertThat(c.caller().role()).isEqualTo("STORE_STAFF");
        assertThat(c.caller().roleLabel()).isEqualTo("Store staff");
        assertThat(c.caller().setupAccess()).isEqualTo("NONE");
        assertThat(c.capabilities()).containsExactly("INVENTORY", "STOCK_REPORTS");
        assertThat(c.stores()).singleElement().satisfies(s -> {
            assertThat(s.storeId()).isEqualTo(opd.id());
            assertThat(s.storeRole()).isEqualTo(StoreRole.STORE_USER);
            assertThat(s.effectiveStoreRole()).isEqualTo(StoreRole.STORE_USER);
            assertThat(s.access().receiveScheduleX()).isFalse();
            assertThat(s.access().uploadOpeningStock()).isFalse();
        });
    }

    @Test
    void aCentralStoreUserGetsViewAndTheUploadCapability() {
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        Context c = get(f.storeStaff);
        assertThat(c.caller().setupAccess()).isEqualTo("VIEW");
        assertThat(c.capabilities()).containsExactly("SETUP_VIEW", "OPENING_STOCK_UPLOAD", "INVENTORY", "PURCHASE",
                "STOCK_REPORTS");
    }

    @Test
    void aStoreHodGetsTheApprovalsInboxAndInventoryButNotPurchase() {
        f.map(pharmacy, f.nurse, StoreRole.APPROVER);

        assertThat(get(f.nurse).capabilities()).containsExactly("INVENTORY",
                "APPROVALS_INBOX", "STOCK_REPORTS", "PHARMACY", "REGISTERS", "COMMAND_CENTRE", "SALES_REPORTS");
        assertThat(get(f.pharmacist).capabilities()).containsExactly("SETUP_VIEW", "COMPLIANCE");
    }

    @Test
    void aPlainStoreUserNeverGetsTheApprovalsInboxOrPurchase() {
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        assertThat(get(f.storeStaff).capabilities()).doesNotContain("APPROVALS_INBOX", "PURCHASE");
    }

    // ---- the working store ----

    @Test
    void oneStoreIsTheWorkingStoreWithoutAsking() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        Context c = get(f.nurse);
        assertThat(c.workingStoreId()).isEqualTo(opd.id());
        assertThat(c.mustPickWorkingStore()).isFalse();
    }

    @Test
    void severalStoresMustBePickedAndThePickIsRememberedForTheNextSignIn() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.map(pharmacy, f.nurse, StoreRole.STORE_USER);
        assertThat(get(f.nurse).mustPickWorkingStore()).isTrue();

        Context picked = pick(f.nurse, pharmacy.id());

        assertThat(picked.workingStoreId()).isEqualTo(pharmacy.id());
        assertThat(picked.mustPickWorkingStore()).isFalse();
        assertThat(f.preferences.get(f.nurse.id())).isEqualTo(pharmacy.id());
        assertThat(get(f.nurse).workingStoreId()).isEqualTo(pharmacy.id()); // the next sign-in
        assertThat(f.auditActions()).doesNotContain("PHARMACY_WORKING_STORE_CHANGED"); // not audited
    }

    @Test
    void anUnmappedStoreIs404AndTheRememberedStoreDoesNotChange() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.map(pharmacy, f.nurse, StoreRole.STORE_USER);
        pick(f.nurse, pharmacy.id());

        assertThatThrownBy(() -> pick(f.nurse, central.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> pick(f.nurse, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertThat(f.preferences.get(f.nurse.id())).isEqualTo(pharmacy.id());
    }

    @Test
    void theOwnerMustBeMappedToPickAStoreToo() {
        assertThatThrownBy(() -> pick(f.owner, opd.id())).isInstanceOf(NotFoundException.class);
        f.map(opd, f.owner, StoreRole.STORE_USER);
        assertThat(pick(f.owner, opd.id()).workingStoreId()).isEqualTo(opd.id());
        // an owner mapped as Store user still has the owner's capabilities
        assertThat(get(f.owner).stores().get(0).access().approve()).isTrue();
    }

    @Test
    void aMissingStoreIdIsAFieldError() {
        assertThatThrownBy(() -> pick(f.owner, null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aRememberedStoreThatWasSwitchedOffOrUnmappedIsDroppedAndTheClientAsksAgain() {
        PharmacyViews.StaffMapping inOpd = f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.map(pharmacy, f.nurse, StoreRole.STORE_USER);
        pick(f.nurse, opd.id());
        assertThat(get(f.nurse).workingStoreId()).isEqualTo(opd.id());

        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), opd.id(), inOpd.id());

        // one store left: it becomes the working store
        assertThat(get(f.nurse).workingStoreId()).isEqualTo(pharmacy.id());

        f.map(opd, f.nurse, StoreRole.STORE_USER);
        pick(f.nurse, opd.id());
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThat(get(f.nurse).stores()).extracting(PharmacyViews.MyStore::storeId).containsExactly(pharmacy.id());
        assertThat(get(f.nurse).workingStoreId()).isEqualTo(pharmacy.id());
    }

    @Test
    void whenTheRememberedStoreIsGoneAndSeveralRemainTheClientMustPick() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.map(pharmacy, f.nurse, StoreRole.STORE_USER);
        f.map(central, f.nurse, StoreRole.STORE_USER);
        pick(f.nurse, opd.id());
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());

        Context c = get(f.nurse);

        assertThat(c.workingStoreId()).isNull();
        assertThat(c.mustPickWorkingStore()).isTrue();
        assertThat(c.stores()).extracting(PharmacyViews.MyStore::name).containsExactly("Central Store", "Test Main Pharmacy");
    }

    // ---- access ----

    @Test
    void everyoneWithoutPharmacyAccessGetsForbiddenAndModuleOffComesFirst() {
        assertThatThrownBy(() -> get(f.nurse)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> get(f.storeStaff)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> pick(f.frontDesk, opd.id())).isInstanceOf(ForbiddenException.class);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> get(f.owner)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> pick(f.owner, opd.id())).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void aSwitchedOffMemberLosesTheirStoresAtOnce() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        assertThat(get(f.nurse).stores()).hasSize(1);
        f.switchOff(f.nurse);
        assertThatThrownBy(() -> get(f.nurse)).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }
}
