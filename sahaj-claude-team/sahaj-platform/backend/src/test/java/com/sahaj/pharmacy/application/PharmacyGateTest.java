package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * ADR-028 D4 layers 1 and 2: the module switch first (403 module-off), then the access level (403 forbidden). The Store
 * staff role gives nothing by itself.
 */
class PharmacyGateTest {

    private final PharmacyFixture f = new PharmacyFixture();

    private void requireAs(PharmacyFixture.Person p, AccessLevel level) {
        f.gate.require(PharmacyFixture.TENANT, p.userId(), level);
    }

    // ---- layer 1: the module switch ----

    @Test
    void withTheSwitchOffEveryoneIncludingTheOwnerGetsModuleOffBeforeAnyOtherCheckOrRead() {
        f.pharmacyOn = false;
        for (PharmacyFixture.Person p : f.people.values()) {
            for (AccessLevel level : AccessLevel.values()) {
                assertThatThrownBy(() -> requireAs(p, level)).as(p.member().role() + " " + level)
                        .isInstanceOf(ModuleOffException.class)
                        .hasMessage("Pharmacy & Inventory is not switched on for your clinic.");
            }
        }
        assertThat(f.writes).isEmpty();
    }

    @Test
    void theModuleOffAnswerComesEvenForACallerWhoIsNoLongerOnTheTeam() {
        f.pharmacyOn = false;
        assertThatThrownBy(() -> f.gate.require(PharmacyFixture.TENANT, java.util.UUID.randomUUID(),
                AccessLevel.PHARMACY_ACCESS)).isInstanceOf(ModuleOffException.class);
    }

    // ---- layer 2: the access level ----

    @Test
    void theOwnerMeetsEveryLevelWithNoMapping() {
        for (AccessLevel level : AccessLevel.values()) {
            assertThat(f.gate.require(PharmacyFixture.TENANT, f.owner.userId(), level).isOwner()).isTrue();
        }
    }

    @Test
    void aPharmacistWithNoMappingReadsMastersAndHasPharmacyAccessButCannotEdit() {
        requireAs(f.pharmacist, AccessLevel.SETUP_VIEW);
        requireAs(f.pharmacist, AccessLevel.PHARMACY_ACCESS);
        assertThatThrownBy(() -> requireAs(f.pharmacist, AccessLevel.SETUP_EDIT))
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("forbidden");
                    assertThat(ex.getMessage()).isEqualTo("Only the clinic owner can do this.");
                });
    }

    @Test
    void everyOtherRoleWithNoMappingGetsForbiddenWithTheNoAccessSentenceOnEveryLevel() {
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.nurse, f.doctor, f.frontDesk, f.storeStaff}) {
            for (AccessLevel level : AccessLevel.values()) {
                assertThatThrownBy(() -> requireAs(p, level)).as(p.member().role() + " " + level)
                        .isInstanceOfSatisfying(ForbiddenException.class, ex -> {
                            assertThat(ex.problemType()).isEqualTo("forbidden");
                            assertThat(ex.getMessage())
                                    .isEqualTo("You do not have access to Pharmacy & Inventory. Ask your owner.");
                        });
            }
        }
    }

    @Test
    void aStoreStaffMemberReachesPharmacyOnlyThroughAMappingAndNeverEditsOrReadsSetupAsStoreUser() {
        var central = f.createCentral();
        var ward = f.createStore("Test Ward Store", "WRD", com.sahaj.pharmacy.domain.StoreType.WARD);
        f.map(ward, f.storeStaff, StoreRole.STORE_USER);

        requireAs(f.storeStaff, AccessLevel.PHARMACY_ACCESS);
        assertThatThrownBy(() -> requireAs(f.storeStaff, AccessLevel.SETUP_VIEW)).isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not have access to this.");
        assertThatThrownBy(() -> requireAs(f.storeStaff, AccessLevel.SETUP_EDIT)).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the clinic owner can do this.");
        assertThat(central).isNotNull();
    }

    @Test
    void aCentralStoreUserReadsSetupAndAnyMappedMemberHasPharmacyAccess() {
        var central = f.createCentral();
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        f.map(central, f.nurse, StoreRole.STORE_USER);

        requireAs(f.storeStaff, AccessLevel.SETUP_VIEW);
        requireAs(f.storeStaff, AccessLevel.PHARMACY_ACCESS);
        requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS);
        assertThatThrownBy(() -> requireAs(f.nurse, AccessLevel.SETUP_VIEW)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> requireAs(f.storeStaff, AccessLevel.SETUP_EDIT)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aCentralStoreUserMappingThatNoLongerFitsTheTeamRoleStopsGivingSetupView() {
        var central = f.createCentral();
        f.map(central, f.storeStaff, StoreRole.CENTRAL_STORE_USER);
        f.changeRole(f.storeStaff, "COUNSELLOR");

        assertThatThrownBy(() -> requireAs(f.storeStaff, AccessLevel.SETUP_VIEW)).isInstanceOf(ForbiddenException.class);
        requireAs(f.storeStaff, AccessLevel.PHARMACY_ACCESS);
    }

    @Test
    void aSwitchedOffStoreOrARemovedMappingGivesNoAccess() {
        var central = f.createCentral();
        var mapping = f.map(central, f.nurse, StoreRole.STORE_USER);
        requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS);

        f.staffService.remove(PharmacyFixture.TENANT, f.owner.userId(), central.id(), mapping.id());
        assertThatThrownBy(() -> requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS)).isInstanceOf(ForbiddenException.class);

        f.map(central, f.nurse, StoreRole.STORE_USER);
        requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS);
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), central.id());
        assertThatThrownBy(() -> requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aMemberSwitchedOffIsAnAuthenticationFailureNotAForbidden() {
        f.switchOff(f.nurse);
        assertThatThrownBy(() -> requireAs(f.nurse, AccessLevel.PHARMACY_ACCESS))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void theRoleIsReadAgainEveryCallNotTakenFromTheToken() {
        requireAs(f.pharmacist, AccessLevel.SETUP_VIEW);
        f.changeRole(f.pharmacist, "COUNSELLOR");
        assertThatThrownBy(() -> requireAs(f.pharmacist, AccessLevel.SETUP_VIEW)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aDelegateOutsideItsDatesCountsAsStoreUserAndMeetsOnlyPharmacyAccess() {
        var central = f.createCentral();
        f.putStaff(new com.sahaj.pharmacy.domain.StoreStaff(java.util.UUID.randomUUID(), central.id(), f.nurse.id(),
                StoreRole.DELEGATE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), true, 0L));
        var caller = f.gate.require(PharmacyFixture.TENANT, f.nurse.userId(), AccessLevel.PHARMACY_ACCESS);
        assertThat(caller.mappings()).singleElement().satisfies(m -> {
            assertThat(m.effective()).isEqualTo(StoreRole.STORE_USER);
            assertThat(m.access().approve()).isFalse();
        });
    }
}
