package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.patient.application.RecordAccessRepository.AccessRow;
import com.sahaj.patient.application.RecordAccessService.RecordAccessView;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-17 AC8: the owner reads the latest file opens; names are resolved at read time. */
class RecordAccessServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

    private RecordAccessRepository accessRepository;
    private PatientRepository patientRepository;
    private ActorDirectoryPort actorDirectory;
    private OwnerGuard ownerGuard;
    private RecordAccessService service;

    @BeforeEach
    void setUp() {
        accessRepository = mock(RecordAccessRepository.class);
        patientRepository = mock(PatientRepository.class);
        actorDirectory = mock(ActorDirectoryPort.class);
        ownerGuard = mock(OwnerGuard.class);
        service = new RecordAccessService(accessRepository, patientRepository, actorDirectory, ownerGuard);
    }

    @Test
    void theDefaultLimitIs5AndNamesAreResolvedAtReadTime() {
        when(accessRepository.findRecent(TENANT_ID, 5)).thenReturn(List.of(
                new AccessRow(PATIENT_ID, NURSE, NOW), new AccessRow(PATIENT_ID, NURSE, NOW.minusSeconds(30))));
        when(actorDirectory.displayName(TENANT_ID, NURSE)).thenReturn(Optional.of("Sunita Pawar"));
        when(patientRepository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.of(patient()));

        List<RecordAccessView> rows = service.recent(TENANT_ID, OWNER, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new RecordAccessView(NOW, "Sunita Pawar", PATIENT_ID, "Asha Rao"));
        verify(ownerGuard).requireOwner(TENANT_ID, OWNER);
        verify(actorDirectory, times(1)).displayName(TENANT_ID, NURSE);
        verify(patientRepository, times(1)).findById(TENANT_ID, PATIENT_ID);
    }

    @Test
    void aStaffMemberOrPatientWhoNoLongerResolvesGetsAPlaceholderInsteadOfFailing() {
        when(accessRepository.findRecent(TENANT_ID, 10)).thenReturn(List.of(new AccessRow(PATIENT_ID, NURSE, NOW)));
        when(actorDirectory.displayName(TENANT_ID, NURSE)).thenReturn(Optional.empty());
        when(patientRepository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.empty());

        RecordAccessView row = service.recent(TENANT_ID, OWNER, 10).get(0);

        assertThat(row.userName()).isEqualTo("A staff member");
        assertThat(row.patientName()).isEqualTo("A patient");
        assertThat(row.patientId()).isEqualTo(PATIENT_ID);
    }

    @Test
    void aLimitOutsideOneToFiftyIs400AndNothingIsQueried() {
        for (int bad : new int[] {0, -1, 51, 1000}) {
            assertThatThrownBy(() -> service.recent(TENANT_ID, OWNER, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            ex -> assertThat(ex.field()).isEqualTo("limit"));
        }
        verify(accessRepository, never()).findRecent(any(), anyInt());
    }

    @Test
    void theLimitsOneAndFiftyAreAccepted() {
        when(accessRepository.findRecent(any(), anyInt())).thenReturn(List.of());

        assertThat(service.recent(TENANT_ID, OWNER, 1)).isEmpty();
        assertThat(service.recent(TENANT_ID, OWNER, 50)).isEmpty();
        verify(accessRepository).findRecent(TENANT_ID, 1);
        verify(accessRepository).findRecent(TENANT_ID, 50);
    }

    @Test
    void aNonOwnerIsRefusedBeforeAnyRowIsRead() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, NURSE);

        assertThatThrownBy(() -> service.recent(TENANT_ID, NURSE, 5)).isInstanceOf(AccessDeniedException.class);
        verify(accessRepository, never()).findRecent(any(), anyInt());
    }

    private static PatientRow patient() {
        return new PatientRow(PATIENT_ID, "PT-00001", "Asha Rao", 30, "F", "9123450001", null, "mr", null, null,
                false, null, NOW, 0);
    }
}
