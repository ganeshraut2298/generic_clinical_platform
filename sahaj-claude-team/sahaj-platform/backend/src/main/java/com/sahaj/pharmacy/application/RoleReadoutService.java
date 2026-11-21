package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ApprovalRuleRepository.Version;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.ForbiddenException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-19, "Role-based permissions &amp; approvals" (docs/api/pharmacy-sales.yaml pharmacyRoleReadout, DEC-074 OQ-80-38). A
 * read-out of what the server enforces: every cell is worked out from {@link StoreRules#access} (the code the store guard uses)
 * for the typical holder of each column, and from the approval rule set in force, so the screen cannot disagree with the server.
 * Nothing is stored and nothing is written. Visible to the OWNER and to a PHARMACIST member (the contract's role line).
 *
 * <p>Columns: OWNER; PHARMACIST (team role PHARMACIST, Store user); CASHIER (BILLING, Cashier); STORE_KEEPER (Store staff, Store
 * user); WARD_NURSE (a nurse, Store user at a ward store, where sale is not allowed).
 */
@Service
public class RoleReadoutService {

    static final String SENTENCE = "These are the rules in force. The owner changes them in Inventory setup.";
    static final String ALLOW = "ALLOW";
    static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    static final String DENY = "DENY";
    static final String SAME_DAY_ONLY = "SAME_DAY_ONLY";
    static final String OWNER = "OWNER";
    static final String PHARMACIST = "PHARMACIST";
    static final String CASHIER = "CASHIER";
    static final String STORE_KEEPER = "STORE_KEEPER";
    static final String WARD_NURSE = "WARD_NURSE";
    static final List<String> COLUMNS = List.of(OWNER, PHARMACIST, CASHIER, STORE_KEEPER, WARD_NURSE);

    private final PharmacyGate gate;
    private final ApprovalRuleRepository rules;

    public RoleReadoutService(PharmacyGate gate, ApprovalRuleRepository rules) {
        this.gate = gate;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public SaleViews.RoleReadout get(UUID tenantId, UUID userId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        if (!caller.isOwner() && !caller.isPharmacist()) {
            throw new ForbiddenException("forbidden", "Not allowed", PharmacyGate.NOT_ALLOWED);
        }
        Version version = rules.current(tenantId).orElse(null);
        List<ApprovalRule> ruleSet = version == null || version.id() == null ? ApprovalPresets.defaults()
                : rules.rules(tenantId, version.id());
        return build(ruleSet);
    }

    /** The read-out for a rule set; a pure function of the capability table and the rules. */
    static SaleViews.RoleReadout build(List<ApprovalRule> ruleSet) {
        StoreAccess owner = StoreRules.access("OWNER", StoreRole.APPROVER, false, true, false);
        StoreAccess pharmacist = StoreRules.access("PHARMACIST", StoreRole.STORE_USER, false, true, false);
        StoreAccess cashier = StoreRules.access("BILLING", StoreRole.CASHIER, false, true, false);
        StoreAccess keeper = StoreRules.access("STORE_STAFF", StoreRole.STORE_USER, false, true, false);
        StoreAccess nurse = StoreRules.access("NURSE", StoreRole.STORE_USER, true, false, false);
        List<StoreAccess> accesses = List.of(owner, pharmacist, cashier, keeper, nurse);

        String discount = approvalText(ruleSet, TransactionType.SALE_DISCOUNT);
        String adjust = approvalText(ruleSet, TransactionType.STOCK_ADJUSTMENT);
        String saleReturn = approvalText(ruleSet, TransactionType.SALES_RETURN);

        List<SaleViews.RoleRow> rows = new ArrayList<>();
        // DEC-090: the owner is exempt from the discount rule (mockup 05)
        rows.add(row("DISCOUNT_ABOVE_LIMIT", accesses, a -> !a.sell() ? cell(DENY, null)
                : a == owner || discount == null ? cell(ALLOW, null) : cell(NEEDS_APPROVAL, discount)));
        rows.add(row("CANCEL_SAVED_BILL", accesses, a -> !a.cancelBill() ? cell(DENY, null)
                : a == owner ? cell(ALLOW, null) : cell(SAME_DAY_ONLY, "Same day only")));
        rows.add(row("SALES_RETURN", accesses, a -> {
            if (!a.salesReturn()) {
                return cell(DENY, null);
            }
            if (a == cashier) {
                return cell(NEEDS_APPROVAL, "A pharmacist accepts the goods first");
            }
            return saleReturn == null ? cell(ALLOW, null) : cell(NEEDS_APPROVAL, saleReturn);
        }));
        rows.add(row("STOCK_ADJUSTMENT", accesses, a -> !a.adjustStock() ? cell(DENY, null)
                : adjust == null ? cell(ALLOW, null) : cell(NEEDS_APPROVAL, adjust)));
        rows.add(row("OVERRIDE_ALLERGY_H1", accesses, a -> a.overrideBlock() ? cell(ALLOW, null) : cell(DENY, null)));
        rows.add(row("RAISE_WARD_INDENT", accesses, a -> a.raiseIndent() ? cell(ALLOW, null) : cell(DENY, null)));
        return new SaleViews.RoleReadout(SENTENCE, COLUMNS, rows);
    }

    private record Cell(String value, String text) {
    }

    private static Cell cell(String value, String text) {
        return new Cell(value, text);
    }

    private static SaleViews.RoleRow row(String action, List<StoreAccess> accesses, Function<StoreAccess, Cell> rule) {
        List<SaleViews.RoleCell> cells = new ArrayList<>();
        for (int i = 0; i < COLUMNS.size(); i++) {
            Cell c = rule.apply(accesses.get(i));
            cells.add(new SaleViews.RoleCell(COLUMNS.get(i), c.value(), c.text()));
        }
        return new SaleViews.RoleRow(action, cells);
    }

    /**
     * The words of the rule of a type, or null when no approval applies: "Needs approval above 10%" for a percent limit,
     * "Needs approval above ₹ 5,000" for an amount limit, "Needs approval" otherwise. A rule with levels is the owner's or the
     * store HOD's step; the first level's condition tells the limit.
     */
    static String approvalText(List<ApprovalRule> ruleSet, TransactionType type) {
        ApprovalRule rule = ruleSet.stream().filter(r -> r.type() == type).findFirst().orElse(null);
        if (rule == null || rule.levels().isEmpty()) {
            return null;
        }
        ApprovalLevel first = rule.levels().get(0);
        switch (first.condition().kind()) {
            case ABOVE_PERCENT:
                return "Needs approval above " + first.condition().percent().stripTrailingZeros().toPlainString() + "%";
            case ABOVE_AMOUNT:
                return "Needs approval above ₹ " + ApprovalConditions.rupees(first.condition().amountPaise());
            default:
                return "Needs approval";
        }
    }

}
