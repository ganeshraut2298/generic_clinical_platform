package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.MedicineRepository.MedicinePage;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC3 and AC5: favourites are the clinic's own; marking is idempotent; unmarking a non-favourite is a 404. */
class MedicineServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID MEDICINE_ID = UUID.randomUUID();

    private MedicineRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private MedicineService service;

    @BeforeEach
    void setUp() {
        repository = mock(MedicineRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new MedicineService(repository, ownerGuard, auditLog);
    }

    @Test
    void searchPagesTwentyAtATimeForTheSignedInClinic() {
        when(repository.search(eq(TENANT_ID), eq("para"), eq(false), anyInt(), anyInt()))
                .thenReturn(new MedicinePage(List.of(), 0));

        service.search(TENANT_ID, "para", false, 2);
        service.search(TENANT_ID, "para", false, -3);

        verify(repository).search(TENANT_ID, "para", false, 20, 40);
        verify(repository).search(TENANT_ID, "para", false, 20, 0);
    }

    @Test
    void markingAFavouriteIsAuditedOnceAndRepeatingItIsQuiet() {
        when(repository.findName(MEDICINE_ID)).thenReturn(Optional.of("Paracetamol 650 mg"));
        when(repository.addFavourite(MEDICINE_ID, OWNER_USER)).thenReturn(1, 0);

        service.markFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID);
        service.markFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID);

        verify(auditLog).record(eq("MEDICINE_FAVOURITED"), eq("MEDICINE"), eq(MEDICINE_ID), any(), any());
    }

    @Test
    void anUnknownMedicineIsNotFound() {
        when(repository.findName(MEDICINE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).addFavourite(any(), any());
    }

    @Test
    void unmarkingAFavouriteDeletesItAndIsAudited() {
        when(repository.removeFavourite(TENANT_ID, MEDICINE_ID)).thenReturn(1);
        when(repository.findName(MEDICINE_ID)).thenReturn(Optional.of("Paracetamol 650 mg"));

        service.unmarkFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID);

        verify(auditLog).record(eq("MEDICINE_UNFAVOURITED"), eq("MEDICINE"), eq(MEDICINE_ID), any(), any());
    }

    @Test
    void unmarkingAMedicineThatIsNotAFavouriteIsNotFound() {
        when(repository.removeFavourite(TENANT_ID, MEDICINE_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.unmarkFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID))
                .isInstanceOf(NotFoundException.class);
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aNonOwnerCannotChangeFavourites() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.markFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.unmarkFavourite(TENANT_ID, OWNER_USER, MEDICINE_ID))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).addFavourite(any(), any());
        verify(repository, never()).removeFavourite(any(), any());
    }

    @Test
    void searchPrefixNeverLetsTypedTextActAsAWildcard() {
        assertThat(SearchPrefix.lowerPattern("Para")).isEqualTo("para%");
        assertThat(SearchPrefix.lowerPattern("100%_x\\")).isEqualTo("100\\%\\_x\\\\%");
        assertThat(SearchPrefix.lowerPattern("  ")).isEqualTo("%");
        assertThat(SearchPrefix.lowerPattern(null)).isEqualTo("%");
        assertThat(SearchPrefix.upperPattern("j06")).isEqualTo("J06%");
    }
}
