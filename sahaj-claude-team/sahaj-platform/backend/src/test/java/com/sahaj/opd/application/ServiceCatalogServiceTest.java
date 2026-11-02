package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.ServiceRepository.ServiceRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC2, AC4 and AC13: the price list, with its duplicate guard, price rules and soft delete. */
class ServiceCatalogServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();

    private ServiceRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ServiceCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(ServiceRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new ServiceCatalogService(repository, ownerGuard, auditLog);
    }

    private void saved(boolean active) {
        when(repository.findById(TENANT_ID, SERVICE_ID))
                .thenReturn(Optional.of(new ServiceRow(SERVICE_ID, "Dressing", "Procedure", 15000, active)));
    }

    @Test
    void ownerAddsAServiceRupeesBecomePaiseAndItIsAudited() {
        when(repository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(new ServiceRow(call.getArgument(1), "Dressing", "Procedure", 15000, true)));

        ServiceView added = service.add(TENANT_ID, OWNER_USER, new ServiceInput(" Dressing ", "Procedure", 150, null));

        assertThat(added.priceRupees()).isEqualTo(150);
        assertThat(added.active()).isTrue();
        verify(repository).insert(any(UUID.class), eq("Dressing"), eq("Procedure"), eq(15000L), eq(true),
                eq(OWNER_USER));
        verify(auditLog).record(eq("SERVICE_ADDED"), eq("SERVICE"), any(UUID.class), isNull(), any());
    }

    @Test
    void aNewServiceNeedsAPriceAboveZero() {
        for (Integer price : new Integer[] {null, 0, -5}) {
            assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, new ServiceInput("Dressing", "Procedure", price, null)))
                    .isInstanceOf(InvalidInputException.class).hasMessage("Enter a price above ₹0");
        }
        verify(repository, never()).insert(any(), anyString(), anyString(), anyLong(), anyBoolean(), any());
    }

    @Test
    void aDuplicateNameIsRefusedWithTheMockupMessage() {
        doThrow(new DuplicateKeyException("uq_service_tenant_name")).when(repository)
                .insert(any(), anyString(), anyString(), anyLong(), anyBoolean(), any());

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, new ServiceInput("dressing", "Procedure", 150, null)))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("That service is already in the list");
                    assertThat(ex.problemType()).isEqualTo("duplicate-service");
                });
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void theCategoryMustBeOneOfTheSeven() {
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, new ServiceInput("Dressing", "Pharmacy", 150, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, new ServiceInput(" ", "Procedure", 150, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anEditMayPriceAServiceAtZeroButNotBelow() {
        saved(true);

        service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput(null, null, 0, null));
        verify(repository).update(SERVICE_ID, "Dressing", "Procedure", 0L, true, OWNER_USER);

        assertThatThrownBy(() -> service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput(null, null, -1, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void removingSetsActiveFalseAndNeverDeletesTheRow() {
        saved(true);

        service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput(null, null, null, false));

        verify(repository).update(SERVICE_ID, "Dressing", "Procedure", 15000L, false, OWNER_USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("SERVICE_REMOVED"), eq("SERVICE"), eq(SERVICE_ID), any(), after.capture());
        assertThat(after.getValue()).containsEntry("active", false).containsEntry("priceRupees", 150L);
    }

    @Test
    void aRemovedServiceCanBeBroughtBack() {
        saved(false);

        service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput(null, null, null, true));

        verify(auditLog).record(eq("SERVICE_RESTORED"), eq("SERVICE"), eq(SERVICE_ID), any(), any());
    }

    @Test
    void renamingToAnExistingNameIsAConflict() {
        saved(true);
        doThrow(new DuplicateKeyException("uq_service_tenant_name")).when(repository)
                .update(any(), anyString(), anyString(), anyLong(), anyBoolean(), any());

        assertThatThrownBy(() -> service.update(TENANT_ID, OWNER_USER, SERVICE_ID,
                new ServiceInput("X-ray", null, null, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void anUnchangedEditWritesNothing() {
        saved(true);

        service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput("Dressing", "Procedure", 150, true));

        verify(repository, never()).update(any(), anyString(), anyString(), anyLong(), anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anotherClinicsServiceIsNotFound() {
        when(repository.findById(TENANT_ID, SERVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(TENANT_ID, OWNER_USER, SERVICE_ID,
                new ServiceInput(null, null, 10, null))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNonOwnerCannotAddOrEdit() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, new ServiceInput("Dressing", "Procedure", 150, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(TENANT_ID, OWNER_USER, SERVICE_ID, new ServiceInput(null, null, 1, null)))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).insert(any(), anyString(), anyString(), anyLong(), anyBoolean(), any());
    }
}
