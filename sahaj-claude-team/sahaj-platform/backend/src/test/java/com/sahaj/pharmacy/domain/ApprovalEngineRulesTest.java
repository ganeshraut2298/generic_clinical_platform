package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79-2 (ADR-029 P6): who can decide a request now, the conditions worked out at submit, and the "Rule" text. */
class ApprovalEngineRulesTest {

    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID HOD = UUID.randomUUID();
    private static final UUID DELEGATE = UUID.randomUUID();
    private static final UUID OWNER_A = UUID.randomUUID();
    private static final UUID OWNER_B = UUID.randomUUID();

    // ---- who decides ----

    @Test
    void aStoreApproverLevelIsTheHodAndTheCurrentDelegateAndNeverTheRaiser() {
        ApproverResolver.Result result = ApproverResolver.resolve(ApproverKind.STORE_APPROVER, RAISER, null, 1, false, HOD,
                DELEGATE, List.of(OWNER_A));

        assertThat(result.approvers()).containsEntry(HOD, "HOD").containsEntry(DELEGATE, "DELEGATE")
                .doesNotContainKey(OWNER_A);
        assertThat(result.needsAnotherApprover()).isFalse();

        ApproverResolver.Result hodRaised = ApproverResolver.resolve(ApproverKind.STORE_APPROVER, HOD, null, 1, false, HOD,
                DELEGATE, List.of(OWNER_A));
        assertThat(hodRaised.approvers()).containsOnlyKeys(DELEGATE);
    }

    @Test
    void aStoreWithNoHodAndNoDelegateFallsToEveryOwner() {
        ApproverResolver.Result result = ApproverResolver.resolve(ApproverKind.STORE_APPROVER, RAISER, null, 1, false, null,
                null, List.of(OWNER_A, OWNER_B));

        assertThat(result.approvers()).containsOnlyKeys(OWNER_A, OWNER_B).containsEntry(OWNER_A, "OWNER");
        assertThat(result.needsAnotherApprover()).isFalse();
    }

    @Test
    void escalationAddsTheOwnersToAStoreLevelButOnlyMarksAnOwnerLevel() {
        ApproverResolver.Result store = ApproverResolver.resolve(ApproverKind.STORE_APPROVER, RAISER, null, 1, true, HOD, null,
                List.of(OWNER_A));
        assertThat(store.escalated()).isTrue();
        assertThat(store.approvers()).containsEntry(HOD, "HOD").containsEntry(OWNER_A, "ESCALATED");

        ApproverResolver.Result owner = ApproverResolver.resolve(ApproverKind.OWNER, RAISER, null, 1, true, HOD, null,
                List.of(OWNER_A));
        assertThat(owner.escalated()).isTrue();
        assertThat(owner.approvers()).containsOnlyKeys(OWNER_A);
    }

    @Test
    void theLevelOneApproverCannotDecideLevelTwo() {
        ApproverResolver.Result result = ApproverResolver.resolve(ApproverKind.OWNER, RAISER, OWNER_A, 2, false, null, null,
                List.of(OWNER_A, OWNER_B));

        assertThat(result.approvers()).containsOnlyKeys(OWNER_B);
        assertThat(result.needsAnotherApprover()).isFalse();
    }

    @Test
    void whenNobodyIsLeftTheItemNeedsAnotherApproverAndShowsToTheOwnersWhoRemain() {
        // the HOD raised it and there is no delegate: nobody at the store is left, so the owners see it
        ApproverResolver.Result toOwners = ApproverResolver.resolve(ApproverKind.STORE_APPROVER, HOD, null, 1, false, HOD, null,
                List.of(OWNER_A));
        assertThat(toOwners.needsAnotherApprover()).isTrue();
        assertThat(toOwners.approvers()).containsOnlyKeys(OWNER_A).containsEntry(OWNER_A, "OWNER");

        // a one-owner clinic whose owner raised it: the item waits, nobody can decide it, nothing is bypassed
        ApproverResolver.Result waits = ApproverResolver.resolve(ApproverKind.OWNER, OWNER_A, null, 1, false, null, null,
                List.of(OWNER_A));
        assertThat(waits.needsAnotherApprover()).isTrue();
        assertThat(waits.approvers()).isEmpty();
        assertThat(waits.canDecide(OWNER_A)).isFalse();
    }

    @Test
    void aWaitLongerThanTheLimitIsEscalatedAndNoLimitNeverEscalates() {
        Instant since = Instant.parse("2026-11-19T04:00:00Z");

        assertThat(ApproverResolver.isEscalated(60, since, since.plusSeconds(3601))).isTrue();
        assertThat(ApproverResolver.isEscalated(60, since, since.plusSeconds(3600))).isFalse();
        assertThat(ApproverResolver.isEscalated(null, since, since.plusSeconds(10_000_000))).isFalse();
    }

    // ---- conditions ----

    private static ApprovalConditions.Facts facts(long amount, boolean x, boolean high, boolean urgent, String rise,
                                                  StoreType type) {
        return new ApprovalConditions.Facts(amount, x, high, urgent, rise == null ? null : new BigDecimal(rise), type);
    }

    @Test
    void anAmountConditionAppliesOnlyAboveTheLimitAndAPercentOnlyAboveItsPercent() {
        ApprovalCondition amount = ApprovalCondition.aboveAmount(500_000L);

        assertThat(ApprovalConditions.applies(amount, facts(500_001L, false, false, false, null, StoreType.WARD))).isTrue();
        assertThat(ApprovalConditions.applies(amount, facts(500_000L, false, false, false, null, StoreType.WARD))).isFalse();
        ApprovalCondition percent = ApprovalCondition.abovePercent(new BigDecimal("5"));
        assertThat(ApprovalConditions.applies(percent, facts(0, false, false, false, "5.01", StoreType.WARD))).isTrue();
        assertThat(ApprovalConditions.applies(percent, facts(0, false, false, false, "5.00", StoreType.WARD))).isFalse();
        assertThat(ApprovalConditions.applies(percent, facts(0, false, false, false, null, StoreType.WARD))).isFalse();
    }

    @Test
    void theOtherConditionsReadTheirFacts() {
        ApprovalConditions.Facts all = facts(0, true, true, true, null, StoreType.WARD);
        ApprovalConditions.Facts none = facts(0, false, false, false, null, StoreType.CENTRAL);

        assertThat(ApprovalConditions.applies(ApprovalCondition.always(), none)).isTrue();
        for (ConditionKind kind : List.of(ConditionKind.SCHEDULE_X_ITEM, ConditionKind.HIGH_VALUE_ITEM,
                ConditionKind.URGENT_PRIORITY)) {
            ApprovalCondition condition = new ApprovalCondition(kind, null, null, null);
            assertThat(ApprovalConditions.applies(condition, all)).as(kind.name()).isTrue();
            assertThat(ApprovalConditions.applies(condition, none)).as(kind.name()).isFalse();
        }
        ApprovalCondition wards = new ApprovalCondition(ConditionKind.STORE_TYPE, null, null, List.of(StoreType.WARD));
        assertThat(ApprovalConditions.applies(wards, all)).isTrue();
        assertThat(ApprovalConditions.applies(wards, none)).isFalse();
    }

    // ---- text ----

    @Test
    void theRuleTextReadsLikeTheMockup() {
        ApprovalLevel amount = new ApprovalLevel(1, ApproverKind.OWNER, ApprovalCondition.aboveAmount(5_000_000L));
        ApprovalLevel rise = new ApprovalLevel(1, ApproverKind.OWNER, ApprovalCondition.abovePercent(new BigDecimal("5.00")));

        assertThat(ApprovalConditions.ruleText(TransactionType.PURCHASE_ORDER, amount)).isEqualTo("PO above ₹ 50,000");
        assertThat(ApprovalConditions.ruleText(TransactionType.GRN_RATE_RISE, rise)).isEqualTo("Rate up more than 5%");
        assertThat(ApprovalConditions.ruleText(TransactionType.STOCK_ADJUSTMENT,
                new ApprovalLevel(1, ApproverKind.STORE_APPROVER, ApprovalCondition.always()))).isEqualTo("Store HOD approval");
        assertThat(ApprovalConditions.ruleText(TransactionType.STOCK_ADJUSTMENT,
                new ApprovalLevel(2, ApproverKind.OWNER, ApprovalCondition.always()))).isEqualTo("Owner approval");
    }

    @Test
    void rupeesUseIndianGroupingAndShowPaiseOnlyWhenNotWhole() {
        assertThat(ApprovalConditions.rupees(0)).isEqualTo("0");
        assertThat(ApprovalConditions.rupees(99_900)).isEqualTo("999");
        assertThat(ApprovalConditions.rupees(500_000)).isEqualTo("5,000");
        assertThat(ApprovalConditions.rupees(12_500_000)).isEqualTo("1,25,000");
        assertThat(ApprovalConditions.rupees(999_999_900L)).isEqualTo("99,99,999");
        assertThat(ApprovalConditions.rupees(9_999_999_900L)).isEqualTo("9,99,99,999");
        assertThat(ApprovalConditions.rupees(41_250)).isEqualTo("412.50");
    }
}
