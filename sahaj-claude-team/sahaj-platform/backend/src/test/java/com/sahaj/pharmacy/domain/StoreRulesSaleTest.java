package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ADR-030 S2 and DEC-087: the sale capabilities and the CASHIER rule. */
class StoreRulesSaleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static StoreAccess at(String team, StoreRole role) {
        return StoreRules.access(team, role, false, true, false);
    }

    @Test
    void aCashierSellsAndReturnsButNeverSellsScheduledOrCancels() {
        StoreAccess cashier = at("BILLING", StoreRole.CASHIER);
        assertThat(cashier.sell()).isTrue();
        assertThat(cashier.salesReturn()).isTrue();
        assertThat(cashier.dayClose()).isTrue();
        assertThat(cashier.sellScheduled()).isFalse();
        assertThat(cashier.substitute()).isFalse();
        assertThat(cashier.overrideBlock()).isFalse();
        assertThat(cashier.cancelBill()).isFalse();
        assertThat(cashier.acceptSalesReturn()).isFalse();
        assertThat(cashier.dispense()).isFalse();
        assertThat(cashier.readRegisters()).isFalse();
        assertThat(cashier.recordWardReturn()).isFalse();
    }

    @Test
    void aPharmacistMemberHasEveryPharmacistCapability() {
        StoreAccess p = at("PHARMACIST", StoreRole.STORE_USER);
        assertThat(p.sell()).isTrue();
        assertThat(p.sellScheduled()).isTrue();
        assertThat(p.substitute()).isTrue();
        assertThat(p.overrideBlock()).isTrue();
        assertThat(p.cancelBill()).isTrue();
        assertThat(p.acceptSalesReturn()).isTrue();
        assertThat(p.dispense()).isTrue();
        assertThat(p.acceptWardReturn()).isTrue();
        assertThat(p.recordWardReturn()).isTrue();
        assertThat(p.readRegisters()).isTrue();
    }

    @Test
    void theOwnerSellsAndCancelsAnyDayButIsNeverAPharmacist() {
        StoreAccess owner = at("OWNER", StoreRole.APPROVER);
        assertThat(owner.sell()).isTrue();
        assertThat(owner.cancelBill()).isTrue();
        assertThat(owner.acceptSalesReturn()).isTrue();
        assertThat(owner.readRegisters()).isTrue();
        assertThat(owner.sellScheduled()).isFalse();
        assertThat(owner.overrideBlock()).isFalse();
        assertThat(owner.substitute()).isFalse();
        assertThat(owner.dispense()).isFalse();
        assertThat(owner.recordWardReturn()).isFalse();
    }

    @Test
    void aHodOrDelegateReadsRegistersAndAcceptsReturnsButDoesNotSell() {
        for (StoreRole role : new StoreRole[] {StoreRole.APPROVER, StoreRole.DELEGATE}) {
            StoreAccess nurse = at("NURSE", role);
            assertThat(nurse.readRegisters()).as(role.name()).isTrue();
            assertThat(nurse.acceptSalesReturn()).as(role.name()).isTrue();
            assertThat(nurse.sell()).as(role.name()).isFalse();
            assertThat(nurse.cancelBill()).as(role.name()).isFalse();
        }
    }

    @Test
    void aStoreUserMayRecordAWardReturnButNothingElseInSale() {
        StoreAccess user = at("NURSE", StoreRole.STORE_USER);
        assertThat(user.recordWardReturn()).isTrue();
        assertThat(user.sell()).isFalse();
        assertThat(user.dispense()).isFalse();
        assertThat(user.readRegisters()).isFalse();
    }

    @Test
    void aStoreThatDoesNotAllowSaleGivesNoSaleCapabilityToAnyone() {
        for (String team : new String[] {"OWNER", "PHARMACIST", "BILLING", "NURSE"}) {
            for (StoreRole role : StoreRole.values()) {
                StoreAccess a = StoreRules.access(team, role, true, false, false);
                assertThat(a.sell() || a.sellScheduled() || a.cancelBill() || a.salesReturn() || a.acceptSalesReturn()
                        || a.dispense() || a.recordWardReturn() || a.readRegisters() || a.dayClose())
                        .as(team + " " + role).isFalse();
            }
        }
    }

    @Test
    void theSaleFlagsAreFalseInTheStockOnlyForm() {
        StoreAccess a = StoreRules.access("OWNER", StoreRole.APPROVER, true);
        assertThat(a.sell()).isFalse();
        assertThat(a.has(StoreCapability.SELL)).isFalse();
    }

    @Test
    void aCashierMappingNeedsAStoreThatAllowsSaleAndIsNotCentral() {
        assertThat(StoreRules.cashierStore(true, StoreType.PHARMACY)).isTrue();
        assertThat(StoreRules.cashierStore(false, StoreType.PHARMACY)).isFalse();
        assertThat(StoreRules.cashierStore(true, StoreType.CENTRAL)).isFalse();
        assertThat(StoreRules.needsReview("BILLING", StoreRole.CASHIER, true)).isFalse();
        assertThat(StoreRules.needsReview("BILLING", StoreRole.CASHIER, false)).isTrue();
        assertThat(StoreRules.needsReview("PHARMACIST", StoreRole.CASHIER, true)).isTrue();
        assertThat(StoreRules.effectiveRole("BILLING", StoreRole.CASHIER, null, null, TODAY, true))
                .isEqualTo(StoreRole.CASHIER);
        assertThat(StoreRules.effectiveRole("BILLING", StoreRole.CASHIER, null, null, TODAY, false))
                .isEqualTo(StoreRole.STORE_USER);
    }

    @Test
    void onlyBillingAndStoreStaffMayBeCashiers() {
        assertThat(TeamRoles.allowedStoreRoles("BILLING")).contains(StoreRole.CASHIER);
        assertThat(TeamRoles.allowedStoreRoles("STORE_STAFF")).contains(StoreRole.CASHIER);
        for (String team : new String[] {"OWNER", "PHARMACIST", "NURSE", "DOCTOR", "FRONT_DESK"}) {
            assertThat(TeamRoles.allowedStoreRoles(team)).as(team).doesNotContain(StoreRole.CASHIER);
        }
    }

    @Test
    void hasMapsEverySaleCapability() {
        StoreAccess p = at("PHARMACIST", StoreRole.STORE_USER);
        for (StoreCapability c : new StoreCapability[] {StoreCapability.SELL, StoreCapability.SELL_SCHEDULED,
                StoreCapability.SUBSTITUTE, StoreCapability.OVERRIDE_BLOCK, StoreCapability.CANCEL_BILL,
                StoreCapability.SALES_RETURN, StoreCapability.ACCEPT_SALES_RETURN, StoreCapability.DISPENSE,
                StoreCapability.RECORD_WARD_RETURN, StoreCapability.ACCEPT_WARD_RETURN, StoreCapability.READ_REGISTERS,
                StoreCapability.DAY_CLOSE}) {
            assertThat(p.has(c)).as(c.name()).isTrue();
        }
    }
}
