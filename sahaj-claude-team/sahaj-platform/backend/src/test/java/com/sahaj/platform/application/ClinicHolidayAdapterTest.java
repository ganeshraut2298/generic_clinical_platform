package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicHolidayAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void returnsOnlyClosedAllDayHolidaysInsideTheRange() {
        HolidayRepository repository = mock(HolidayRepository.class);
        when(repository.findAll(TENANT)).thenReturn(List.of(
                new HolidayRow(UUID.randomUUID(), LocalDate.of(2026, 11, 4), "Before", "CLOSED"),
                new HolidayRow(UUID.randomUUID(), LocalDate.of(2026, 11, 5), "Edge", "CLOSED"),
                new HolidayRow(UUID.randomUUID(), LocalDate.of(2026, 11, 7), "Half", "MORNING_ONLY"),
                new HolidayRow(UUID.randomUUID(), LocalDate.of(2026, 11, 11), "Last", "CLOSED"),
                new HolidayRow(UUID.randomUUID(), LocalDate.of(2026, 11, 12), "After", "CLOSED")));
        ClinicHolidayAdapter adapter = new ClinicHolidayAdapter(repository);

        assertThat(adapter.closedDates(TENANT, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 11)))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 11));
    }
}
