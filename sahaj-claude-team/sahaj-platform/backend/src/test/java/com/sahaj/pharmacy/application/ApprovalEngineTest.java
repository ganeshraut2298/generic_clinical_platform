package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.ApprovalRepository.NewRequest;
import com.sahaj.pharmacy.application.ApprovalRuleRepository.Version;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-5: before the owner saves any rule, the engine applies the same STANDARD preset that {@code GET /approval-rules} serves
 * (version 0), so a purchase order or a stock adjustment is never approved at once only because nothing was saved.
 */
class ApprovalEngineTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");

    private final ApprovalRuleRepository rules = mock(ApprovalRuleRepository.class);
    private final ApprovalRepository requests = mock(ApprovalRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ApprovalEngine engine = new ApprovalEngine(rules, requests, stores, mock(StoreStaffRepository.class),
            mock(TeamMemberPort.class), mock(ActiveReasonService.class), new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));

    private static ApprovalEngine.Subject subject(TransactionType type, long amount) {
        return new ApprovalEngine.Subject(type, type.name(), UUID.randomUUID(), "X-1", STORE, amount, false, amount, false,
                BigDecimal.ONE);
    }

    private void storeExists() {
        when(stores.find(TENANT, STORE)).thenReturn(Optional.of(new Store(STORE, UNIT, "Test Central Store", "CS",
                StoreType.CENTRAL, null, false, false, true, true, 0)));
    }

    @Test
    void withNoSavedRulesAPurchaseOrderAndAStockAdjustmentGetTheOwnerLevelOfTheStandardPreset() {
        storeExists();
        for (TransactionType type : List.of(TransactionType.PURCHASE_ORDER, TransactionType.STOCK_ADJUSTMENT)) {
            ApprovalEngine.Plan plan = engine.plan(TENANT, subject(type, 1_000L));

            assertThat(plan).as(type.name()).isNotNull();
            assertThat(plan.version().id()).isNull();
            assertThat(plan.version().versionNo()).isZero();
            assertThat(plan.firstLevel()).isEqualTo(1);
            assertThat(plan.levels()).singleElement().satisfies(l -> {
                assertThat(l.approver().name()).isEqualTo("OWNER");
                assertThat(l.applies()).isTrue();
            });
        }
        verify(rules, never()).rules(any(), any());
    }

    @Test
    void everyTypeFollowsTheServedDefaultAndATypeWithNoLevelUnderStandardStaysWithoutAnApproval() {
        storeExists();
        for (ApprovalRule served : ApprovalPresets.defaults()) {
            ApprovalEngine.Plan plan = engine.plan(TENANT, subject(served.type(), 1_000L));
            if (served.levels().isEmpty()) {
                assertThat(plan).as(served.type().name()).isNull();
            } else {
                assertThat(plan).as(served.type().name()).isNotNull();
                assertThat(plan.levels()).hasSize(served.levels().size());
            }
        }
    }

    @Test
    void theStoreApproverTypesOfTheDefaultStartAtLevelOne() {
        storeExists();
        ApprovalEngine.Plan plan = engine.plan(TENANT, subject(TransactionType.VENDOR_RETURN, 1_000L));

        assertThat(plan).isNotNull();
        assertThat(plan.levels().get(0).approver().name()).isEqualTo("STORE_APPROVER");
        assertThat(plan.firstLevel()).isEqualTo(1);
    }

    @Test
    void aSavedVersionWithNoLevelForTheTypeStillMeansNoApproval() {
        storeExists();
        UUID versionId = UUID.randomUUID();
        when(rules.current(TENANT)).thenReturn(Optional.of(new Version(versionId, 3, RulePreset.LEAN, null, NOW, ME)));
        when(rules.rules(TENANT, versionId)).thenReturn(ApprovalPresets.build(RulePreset.LEAN, java.util.Map.of()));

        assertThat(engine.plan(TENANT, subject(TransactionType.STOCK_ADJUSTMENT, 1_000L))).isNull();
        assertThat(engine.plan(TENANT, subject(TransactionType.PURCHASE_ORDER, 1_000L))).isNotNull();
    }

    @Test
    void submitWithNoSavedRulesStoresANullRuleVersionAndWritesNoVersionRow() {
        storeExists();
        when(requests.insertRequest(eq(TENANT), any(), eq(ME))).thenReturn(UUID.randomUUID());
        ApprovalEngine.Subject subject = subject(TransactionType.PURCHASE_ORDER, 1_000L);
        ApprovalEngine.Plan plan = engine.plan(TENANT, subject);

        engine.submit(TENANT, UNIT, ME, subject, plan);

        ArgumentCaptor<NewRequest> written = ArgumentCaptor.forClass(NewRequest.class);
        verify(requests).insertRequest(eq(TENANT), written.capture(), eq(ME));
        assertThat(written.getValue().ruleVersionId()).isNull();
        assertThat(written.getValue().currentLevel()).isEqualTo(plan.firstLevel());
        verify(requests).insertLevel(eq(TENANT), eq(UNIT), any(), any(), eq(ME));
        verify(rules, never()).insertVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        verify(rules, never()).insertSet(any(), any(), any(), any());
        verify(rules, never()).insertRule(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitWithASavedVersionDoesNotWriteADefault() {
        storeExists();
        UUID versionId = UUID.randomUUID();
        when(rules.current(TENANT)).thenReturn(Optional.of(new Version(versionId, 2, RulePreset.STANDARD, null, NOW, ME)));
        when(rules.rules(TENANT, versionId)).thenReturn(ApprovalPresets.defaults());
        when(requests.insertRequest(eq(TENANT), any(), eq(ME))).thenReturn(UUID.randomUUID());
        ApprovalEngine.Subject subject = subject(TransactionType.PURCHASE_ORDER, 1_000L);

        engine.submit(TENANT, UNIT, ME, subject, engine.plan(TENANT, subject));

        verify(rules, never()).insertVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        ArgumentCaptor<NewRequest> written = ArgumentCaptor.forClass(NewRequest.class);
        verify(requests).insertRequest(eq(TENANT), written.capture(), eq(ME));
        assertThat(written.getValue().ruleVersionId()).isEqualTo(versionId);
    }
}
