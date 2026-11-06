package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.application.ConsentRepository.ConsentRow;
import com.sahaj.patient.application.ConsentService.ConsentStateView;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-17 AC3, AC4: consent is appended, never edited; the latest row per kind is the state. */
class ConsentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-11-04T05:30:00Z");

    private PatientRepository patientRepository;
    private ConsentRepository consentRepository;
    private ActorDirectoryPort actorDirectory;
    private ConsentService service;

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        consentRepository = mock(ConsentRepository.class);
        actorDirectory = mock(ActorDirectoryPort.class);
        service = new ConsentService(patientRepository, consentRepository, actorDirectory);
        PatientRow patient = new PatientRow(PATIENT_ID, "PT-00001", "Asha Rao", 30, "F", "9123450001", null, "mr",
                null, null, false, null, T1, 0);
        when(patientRepository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.of(patient));
        when(actorDirectory.displayName(TENANT_ID, STAFF)).thenReturn(Optional.of("Sunita Pawar"));
    }

    @Test
    void aPatientWithNoRowsHasNullForBothKinds() {
        when(consentRepository.findLatestPerKind(TENANT_ID, PATIENT_ID)).thenReturn(List.of());

        ConsentStateView state = service.state(TENANT_ID, PATIENT_ID);

        assertThat(state.records()).isNull();
        assertThat(state.promotional()).isNull();
    }

    @Test
    void theStateShowsTheLatestRowPerKindWithTheStaffName() {
        when(consentRepository.findLatestPerKind(TENANT_ID, PATIENT_ID)).thenReturn(List.of(
                new ConsentRow("RECORDS", true, T1, STAFF),
                new ConsentRow("PROMOTIONAL", false, T1.plusSeconds(60), STAFF)));

        ConsentStateView state = service.state(TENANT_ID, PATIENT_ID);

        assertThat(state.records().given()).isTrue();
        assertThat(state.records().recordedByName()).isEqualTo("Sunita Pawar");
        assertThat(state.records().recordedAt()).isEqualTo(T1);
        assertThat(state.promotional().given()).isFalse();
    }

    @Test
    void aStaffMemberWhoNoLongerResolvesGetsAPlaceholder() {
        UUID gone = UUID.randomUUID();
        when(actorDirectory.displayName(TENANT_ID, gone)).thenReturn(Optional.empty());
        when(consentRepository.findLatestPerKind(TENANT_ID, PATIENT_ID))
                .thenReturn(List.of(new ConsentRow("RECORDS", true, T1, gone)));

        assertThat(service.state(TENANT_ID, PATIENT_ID).records().recordedByName()).isEqualTo("A staff member");
    }

    @Test
    void anUnknownOrOtherTenantPatientIs404ForReadAndWrite() {
        UUID other = UUID.randomUUID();
        when(patientRepository.findById(TENANT_ID, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.state(TENANT_ID, other)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.record(TENANT_ID, STAFF, other, new ConsentInput("RECORDS", true)))
                .isInstanceOf(NotFoundException.class);
        verify(consentRepository, never()).insert(any(), any(), anyBoolean(), any());
    }

    @Test
    void recordingAppendsARowByTheSignedInUserAndReturnsTheUpdatedState() {
        when(consentRepository.findLatestPerKind(TENANT_ID, PATIENT_ID))
                .thenReturn(List.of(new ConsentRow("RECORDS", true, T1, STAFF)));

        ConsentStateView state = service.record(TENANT_ID, STAFF, PATIENT_ID, new ConsentInput("RECORDS", true));

        verify(consentRepository).insert(PATIENT_ID, "RECORDS", true, STAFF);
        assertThat(state.records().given()).isTrue();
        assertThat(state.promotional()).isNull();
    }

    @Test
    void aWithdrawalIsANewRowWithGivenFalse() {
        when(consentRepository.findLatestPerKind(TENANT_ID, PATIENT_ID))
                .thenReturn(List.of(new ConsentRow("PROMOTIONAL", false, T1, STAFF)));

        ConsentStateView state = service.record(TENANT_ID, STAFF, PATIENT_ID, new ConsentInput("PROMOTIONAL", false));

        verify(consentRepository).insert(PATIENT_ID, "PROMOTIONAL", false, STAFF);
        assertThat(state.promotional().given()).isFalse();
    }

    @Test
    void anUnknownKindOrAMissingAnswerIs400AndNothingIsWritten() {
        assertThatThrownBy(() -> service.record(TENANT_ID, STAFF, PATIENT_ID, new ConsentInput("MARKETING", true)))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("kind"));
        assertThatThrownBy(() -> service.record(TENANT_ID, STAFF, PATIENT_ID, new ConsentInput("RECORDS", null)))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("given"));
        assertThatThrownBy(() -> service.record(TENANT_ID, STAFF, PATIENT_ID, new ConsentInput(null, true)))
                .isInstanceOf(InvalidInputException.class);
        verify(consentRepository, never()).insert(any(), any(), anyBoolean(), any());
    }
}
