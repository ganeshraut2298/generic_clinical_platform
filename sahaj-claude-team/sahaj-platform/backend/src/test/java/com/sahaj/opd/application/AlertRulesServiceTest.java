package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahaj.opd.application.AlertRuleRepository.AlertRuleRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-18 AC2 to AC7: starter rules, builder validation, duplicates, suggestions, owner-only writes, audit. */
class AlertRulesServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private FakeRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private AlertRulesService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new AlertRulesService(repository, ownerGuard, auditLog);
    }

    @Test
    void aClinicThatNeverSavedSeesTheFiveStarterRulesWithoutIdsAndNothingIsWritten() {
        AlertRulesView view = service.get(TENANT_ID);

        assertThat(view.rules()).hasSize(5);
        assertThat(view.rules()).allMatch(r -> r.id() == null);
        assertThat(view.rules().get(0).when()).isEqualTo("Vitals");
        assertThat(view.rules().get(4).enabled()).isFalse();
        assertThat(view.rules().stream().filter(AlertRuleRow::enabled).count()).isEqualTo(4);
        assertThat(repository.rows).isEmpty();
        assertThat(repository.seeded).isFalse();
        assertThat(view.options().when()).hasSize(7);
        assertThat(view.suggestions()).hasSize(5);
    }

    @Test
    void theStarterEndpointSavesTheFiveRulesOnceAndIsIdempotent() {
        AlertRulesView first = service.saveStarter(TENANT_ID, USER_ID);
        AlertRulesView second = service.saveStarter(TENANT_ID, USER_ID);

        assertThat(first.rules()).hasSize(5).allMatch(r -> r.id() != null);
        assertThat(second.rules()).hasSize(5);
        assertThat(repository.rows).hasSize(5);
        assertThat(first.rules().get(0).when()).isEqualTo("Vitals");
    }

    @Test
    void anAddSavesTheStarterRulesPlusTheNewRuleSwitchedOn() {
        AlertRulesView view = service.add(TENANT_ID, USER_ID, "Billing", "bill not paid", "Alert owner");

        assertThat(view.rules()).hasSize(6);
        AlertRuleRow added = view.rules().get(5);
        assertThat(added.when()).isEqualTo("Billing");
        assertThat(added.enabled()).isTrue();
        verify(auditLog, org.mockito.Mockito.times(6)).record(eq("ALERT_RULE_ADDED"), eq("ALERT_RULE"), any(),
                eq(null), any());
    }

    @Test
    void deletingEverythingNeverBringsTheStarterRulesBack() {
        AlertRulesView view = service.saveStarter(TENANT_ID, USER_ID);
        for (AlertRuleRow rule : view.rules()) {
            service.delete(TENANT_ID, USER_ID, rule.id());
        }

        assertThat(service.get(TENANT_ID).rules()).isEmpty();
        assertThat(service.saveStarter(TENANT_ID, USER_ID).rules()).isEmpty();
        assertThat(service.add(TENANT_ID, USER_ID, "Token", "waiting over 30 min", "Alert owner").rules()).hasSize(1);
    }

    @Test
    void anIdenticalRuleIs409() {
        service.saveStarter(TENANT_ID, USER_ID);

        assertThatThrownBy(() -> service.add(TENANT_ID, USER_ID, "Vitals", "not done in 10 min", "Alert nurse"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("That rule already exists.");
    }

    @Test
    void valuesOutsideTheBuilderListsAre400() {
        assertThatThrownBy(() -> service.add(TENANT_ID, USER_ID, "Lab", "bill not paid", "Alert owner"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, USER_ID, "Billing", "whenever", "Alert owner"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, USER_ID, "Billing", "bill not paid", null))
                .isInstanceOf(InvalidInputException.class);
        assertThat(repository.rows).isEmpty();
        assertThat(repository.seeded).isFalse();
    }

    @Test
    void aSuggestionIsAddedSwitchedOnEvenWhenOutsideTheBuilderLists() {
        AlertRulesView view = service.addSuggestion(TENANT_ID, USER_ID, 1);

        assertThat(view.rules()).hasSize(6);
        AlertRuleRow added = view.rules().get(5);
        assertThat(added.when()).isEqualTo("Token");
        assertThat(added.cond()).isEqualTo("patient is 75+ or pregnant");
        assertThat(added.then()).isEqualTo("Move up in the queue");
        assertThat(added.enabled()).isTrue();
        assertThat(view.suggestions()).hasSize(4).noneMatch(s -> s.index() == 1);
    }

    @Test
    void suggestionIndexRefersToTheFullListAndAddedOnesAreHidden() {
        AlertRulesView view = service.get(TENANT_ID);
        assertThat(view.suggestions()).extracting(AlertRulesView.Suggestion::index).containsExactly(0, 1, 2, 3, 4);

        service.addSuggestion(TENANT_ID, USER_ID, 0);
        AlertRulesView after = service.get(TENANT_ID);

        assertThat(after.suggestions()).extracting(AlertRulesView.Suggestion::index).containsExactly(1, 2, 3, 4);
        assertThat(after.suggestions().get(0).why()).isNotBlank();
    }

    @Test
    void aSuggestionWhoseIfAndIsAreAlreadyAddedIs409AndHiddenWhenBuiltByHand() {
        service.add(TENANT_ID, USER_ID, "Billing", "unpaid at day closing", "Send WhatsApp reminder");

        assertThat(service.get(TENANT_ID).suggestions()).noneMatch(s -> s.index() == 3);
        assertThatThrownBy(() -> service.addSuggestion(TENANT_ID, USER_ID, 3)).isInstanceOf(ConflictException.class);
    }

    @Test
    void allSuggestionsAddedLeavesNoneToShow() {
        for (int i = 0; i < 5; i++) {
            service.addSuggestion(TENANT_ID, USER_ID, i);
        }

        assertThat(service.get(TENANT_ID).suggestions()).isEmpty();
    }

    @Test
    void anUnknownSuggestionIndexIs404() {
        assertThatThrownBy(() -> service.addSuggestion(TENANT_ID, USER_ID, 5)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addSuggestion(TENANT_ID, USER_ID, -1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theOwnerSwitchesARuleAndItIsAuditedWithBeforeAndAfter() {
        AlertRuleRow rule = service.saveStarter(TENANT_ID, USER_ID).rules().get(4);

        AlertRulesView view = service.setEnabled(TENANT_ID, USER_ID, rule.id(), true);

        assertThat(view.rules().get(4).enabled()).isTrue();
        verify(auditLog).record(eq("ALERT_RULE_TOGGLED"), eq("ALERT_RULE"), eq(rule.id()), any(), any());
    }

    @Test
    void aMissingEnabledFlagIs400AndAnUnknownRuleIs404() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.setEnabled(TENANT_ID, USER_ID, id, null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.setEnabled(TENANT_ID, USER_ID, id, true))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(TENANT_ID, USER_ID, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteIsAuditedWithTheRuleAsItWas() {
        AlertRuleRow rule = service.saveStarter(TENANT_ID, USER_ID).rules().get(0);

        service.delete(TENANT_ID, USER_ID, rule.id());

        verify(auditLog).record(eq("ALERT_RULE_DELETED"), eq("ALERT_RULE"), eq(rule.id()), any(), eq(null));
        assertThat(repository.rows).hasSize(4);
    }

    @Test
    void aNonOwnerCannotWriteAnything() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, USER_ID);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.add(TENANT_ID, USER_ID, "Billing", "bill not paid", "Alert owner"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.saveStarter(TENANT_ID, USER_ID)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.addSuggestion(TENANT_ID, USER_ID, 0))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.setEnabled(TENANT_ID, USER_ID, id, true))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(TENANT_ID, USER_ID, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(repository.rows).isEmpty();
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    /** In-memory stand-in for the table; the SQL itself is covered by the real-database tests. */
    private static final class FakeRepository implements AlertRuleRepository {
        final List<AlertRuleRow> rows = new ArrayList<>();
        boolean seeded;

        @Override
        public List<AlertRuleRow> list(UUID tenantId) {
            return List.copyOf(rows);
        }

        @Override
        public boolean isSeeded(UUID tenantId) {
            return seeded;
        }

        @Override
        public boolean markSeeded(UUID tenantId) {
            boolean created = !seeded;
            seeded = true;
            return created;
        }

        @Override
        public Optional<UUID> insert(UUID tenantId, String when, String cond, String then, boolean enabled,
                UUID actorUserId) {
            boolean exists = rows.stream().anyMatch(r -> r.when().equals(when) && r.cond().equals(cond)
                    && r.then().equals(then));
            if (exists) {
                return Optional.empty();
            }
            UUID id = UUID.randomUUID();
            rows.add(new AlertRuleRow(id, when, cond, then, enabled));
            return Optional.of(id);
        }

        @Override
        public Optional<AlertRuleRow> find(UUID tenantId, UUID id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public int setEnabled(UUID tenantId, UUID id, boolean enabled, UUID actorUserId) {
            for (int i = 0; i < rows.size(); i++) {
                AlertRuleRow r = rows.get(i);
                if (r.id().equals(id)) {
                    rows.set(i, new AlertRuleRow(id, r.when(), r.cond(), r.then(), enabled));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int delete(UUID tenantId, UUID id) {
            return rows.removeIf(r -> r.id().equals(id)) ? 1 : 0;
        }
    }
}
