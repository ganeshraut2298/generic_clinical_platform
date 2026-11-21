package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.ForbiddenException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-80-19, the role read-out: every cell comes from the capability table and the rule set in force. */
class RoleReadoutServiceTest {

    private static String value(SaleViews.RoleReadout readout, String action, String column) {
        return readout.rows().stream().filter(r -> r.action().equals(action)).findFirst().orElseThrow().cells().stream()
                .filter(c -> c.column().equals(column)).findFirst().orElseThrow().value();
    }

    private static String text(SaleViews.RoleReadout readout, String action, String column) {
        return readout.rows().stream().filter(r -> r.action().equals(action)).findFirst().orElseThrow().cells().stream()
                .filter(c -> c.column().equals(column)).findFirst().orElseThrow().ruleText();
    }

    private static List<ApprovalRule> withLimits(BigDecimal discountPercent, Long returnPaise) {
        List<ApprovalRule> rules = new ArrayList<>(ApprovalPresets.defaults());
        rules.removeIf(r -> r.type() == TransactionType.SALE_DISCOUNT || r.type() == TransactionType.SALES_RETURN);
        rules.add(new ApprovalRule(TransactionType.SALE_DISCOUNT, discountPercent == null ? List.of()
                : List.of(new ApprovalLevel(1, ApproverKind.OWNER, ApprovalCondition.abovePercent(discountPercent))), null, null));
        rules.add(new ApprovalRule(TransactionType.SALES_RETURN, returnPaise == null ? List.of()
                : List.of(new ApprovalLevel(1, ApproverKind.OWNER, ApprovalCondition.aboveAmount(returnPaise))), null, null));
        return rules;
    }

    @Test
    void theSentenceColumnsAndSixRowsAreTheContractsInOrder() {
        SaleViews.RoleReadout readout = RoleReadoutService.build(withLimits(null, null));

        assertThat(readout.sentence()).isEqualTo("These are the rules in force. The owner changes them in Inventory setup.");
        assertThat(readout.columns()).containsExactly("OWNER", "PHARMACIST", "CASHIER", "STORE_KEEPER", "WARD_NURSE");
        assertThat(readout.rows()).extracting(SaleViews.RoleRow::action).containsExactly("DISCOUNT_ABOVE_LIMIT",
                "CANCEL_SAVED_BILL", "SALES_RETURN", "STOCK_ADJUSTMENT", "OVERRIDE_ALLERGY_H1", "RAISE_WARD_INDENT");
        assertThat(readout.rows()).allSatisfy(r -> assertThat(r.cells()).hasSize(5));
    }

    @Test
    void cancellingABillIsTheOwnersAnyDayAndThePharmacistsOnTheSameDayNeverACashiers() {
        SaleViews.RoleReadout readout = RoleReadoutService.build(withLimits(null, null));

        assertThat(value(readout, "CANCEL_SAVED_BILL", "OWNER")).isEqualTo("ALLOW");
        assertThat(value(readout, "CANCEL_SAVED_BILL", "PHARMACIST")).isEqualTo("SAME_DAY_ONLY");
        assertThat(value(readout, "CANCEL_SAVED_BILL", "CASHIER")).isEqualTo("DENY");
        assertThat(value(readout, "CANCEL_SAVED_BILL", "STORE_KEEPER")).isEqualTo("DENY");
        assertThat(value(readout, "CANCEL_SAVED_BILL", "WARD_NURSE")).isEqualTo("DENY");
    }

    @Test
    void overridesBelongToThePharmacistAloneNeverTheOwnerOrACashier() {
        SaleViews.RoleReadout readout = RoleReadoutService.build(withLimits(null, null));

        assertThat(value(readout, "OVERRIDE_ALLERGY_H1", "PHARMACIST")).isEqualTo("ALLOW");
        for (String column : new String[] {"OWNER", "CASHIER", "STORE_KEEPER", "WARD_NURSE"}) {
            assertThat(value(readout, "OVERRIDE_ALLERGY_H1", column)).as(column).isEqualTo("DENY");
        }
    }

    @Test
    void aDiscountLimitShowsItsWordsAndNoLimitMeansAllowForThoseWhoSell() {
        SaleViews.RoleReadout limited = RoleReadoutService.build(withLimits(new BigDecimal("10"), null));
        assertThat(value(limited, "DISCOUNT_ABOVE_LIMIT", "CASHIER")).isEqualTo("NEEDS_APPROVAL");
        assertThat(text(limited, "DISCOUNT_ABOVE_LIMIT", "CASHIER")).isEqualTo("Needs approval above 10%");
        assertThat(value(limited, "DISCOUNT_ABOVE_LIMIT", "STORE_KEEPER")).isEqualTo("DENY");

        assertThat(value(limited, "DISCOUNT_ABOVE_LIMIT", "OWNER")).isEqualTo("ALLOW");
        assertThat(text(limited, "DISCOUNT_ABOVE_LIMIT", "OWNER")).isNull();

        SaleViews.RoleReadout open = RoleReadoutService.build(withLimits(null, null));
        assertThat(value(open, "DISCOUNT_ABOVE_LIMIT", "PHARMACIST")).isEqualTo("ALLOW");
        assertThat(text(open, "DISCOUNT_ABOVE_LIMIT", "PHARMACIST")).isNull();
    }

    @Test
    void aReturnLimitIsShownInIndianGroupingAndACashierAlwaysNeedsAPharmacistToAcceptTheGoods() {
        SaleViews.RoleReadout readout = RoleReadoutService.build(withLimits(null, 12_500_000L));

        assertThat(text(readout, "SALES_RETURN", "PHARMACIST")).isEqualTo("Needs approval above ₹ 1,25,000");
        assertThat(value(readout, "SALES_RETURN", "CASHIER")).isEqualTo("NEEDS_APPROVAL");
        assertThat(text(readout, "SALES_RETURN", "CASHIER")).isEqualTo("A pharmacist accepts the goods first");
        assertThat(value(readout, "SALES_RETURN", "WARD_NURSE")).isEqualTo("DENY");
    }

    @Test
    void stockAdjustmentIsForThePharmacistAndOwnerAndRaisingAWardIndentForEveryoneMapped() {
        SaleViews.RoleReadout readout = RoleReadoutService.build(withLimits(null, null));

        assertThat(value(readout, "STOCK_ADJUSTMENT", "PHARMACIST")).isIn("ALLOW", "NEEDS_APPROVAL");
        assertThat(value(readout, "STOCK_ADJUSTMENT", "CASHIER")).isEqualTo("DENY");
        assertThat(value(readout, "STOCK_ADJUSTMENT", "STORE_KEEPER")).isEqualTo("DENY");
        assertThat(value(readout, "RAISE_WARD_INDENT", "WARD_NURSE")).isEqualTo("ALLOW");
        assertThat(value(readout, "RAISE_WARD_INDENT", "STORE_KEEPER")).isEqualTo("ALLOW");
    }

    @Test
    void onlyTheOwnerAndAPharmacistMayRead() {
        MasterFixture m = new MasterFixture();
        PharmacyFixture f = m.f;
        RoleReadoutService service = new RoleReadoutService(f.gate, m.ruleRepository);

        assertThat(service.get(PharmacyFixture.TENANT, f.owner.userId()).columns()).hasSize(5);
        assertThat(service.get(PharmacyFixture.TENANT, f.pharmacist.userId()).rows()).hasSize(6);
        assertThatThrownBy(() -> service.get(PharmacyFixture.TENANT, f.storeStaff.userId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.get(PharmacyFixture.TENANT, f.nurse.userId())).isInstanceOf(ForbiddenException.class);
    }
}
