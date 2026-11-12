package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-43 (ADR-019 D7): the clinic's specialty code, empty when there is none. */
class ClinicSpecialtyAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static ProfileRow profile(String specialtyCode) {
        return new ProfileRow("Test clinic", null, "1 Test Road", "Pune", "411001", "9000000001", null, null, null,
                "test-clinic", specialtyCode, "teal", null);
    }

    @Test
    void returnsTheClinicsSpecialtyCode() {
        ClinicProfileRepository repository = mock(ClinicProfileRepository.class);
        when(repository.find(TENANT)).thenReturn(Optional.of(profile("dental")));

        assertThat(new ClinicSpecialtyAdapter(repository).specialtyCode(TENANT)).contains("dental");
    }

    @Test
    void isEmptyWhenNoSpecialtyOrNoProfile() {
        ClinicProfileRepository repository = mock(ClinicProfileRepository.class);
        ClinicSpecialtyAdapter adapter = new ClinicSpecialtyAdapter(repository);

        when(repository.find(TENANT)).thenReturn(Optional.of(profile(null)));
        assertThat(adapter.specialtyCode(TENANT)).isEmpty();

        when(repository.find(TENANT)).thenReturn(Optional.of(profile("  ")));
        assertThat(adapter.specialtyCode(TENANT)).isEmpty();

        when(repository.find(TENANT)).thenReturn(Optional.empty());
        assertThat(adapter.specialtyCode(TENANT)).isEmpty();
    }
}
