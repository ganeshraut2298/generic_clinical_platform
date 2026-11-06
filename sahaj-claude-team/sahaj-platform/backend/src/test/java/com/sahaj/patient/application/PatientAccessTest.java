package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-23 (security review M1 and L1): the access port and the merged flag of the lookup port. */
class PatientAccessTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private PatientRepository patientRepository;
    private RecordAccessRepository accessRepository;

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        accessRepository = mock(RecordAccessRepository.class);
    }

    private static PatientRow row(UUID mergedInto) {
        return new PatientRow(PATIENT, "UH-1", "Test Patient", 40, "F", "9000000001", null, "mr", null, null, false,
                mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 0L);
    }

    @Test
    void recordOpenedWritesOneRowWithPatientAndUser() {
        when(patientRepository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(null)));

        new PatientAccess(patientRepository, accessRepository).recordOpened(TENANT, PATIENT, USER);

        verify(accessRepository).insert(PATIENT, USER);
    }

    @Test
    void recordOpenedForAPatientOfAnotherClinicIsNotFoundAndWritesNothing() {
        when(patientRepository.findById(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new PatientAccess(patientRepository, accessRepository)
                .recordOpened(TENANT, PATIENT, USER)).isInstanceOf(NotFoundException.class);
        verify(accessRepository, never()).insert(PATIENT, USER);
    }

    @Test
    void lookupFlagsAMergedAwayRecord() {
        UUID kept = UUID.randomUUID();
        when(patientRepository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(kept)));
        when(patientRepository.findByIds(TENANT, List.of(PATIENT))).thenReturn(List.of(row(null)));
        PatientLookup lookup = new PatientLookup(patientRepository);

        PatientSummary merged = lookup.find(TENANT, PATIENT).orElseThrow();
        assertThat(merged.merged()).isTrue();
        assertThat(lookup.findAll(TENANT, List.of(PATIENT)).get(PATIENT).merged()).isFalse();
    }
}
