package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.DoctorBlockedDatesPort;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.platform.api.ClinicHolidayPort;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-20: a doctor has OPD on a date when the weekday pattern has slots and the clinic is not closed. */
class DoctorOpdDaysAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    /** Thursday 5 Nov 2026. */
    private static final LocalDate THU = LocalDate.of(2026, 11, 5);

    private DoctorSlotSettingsRepository repository;
    private ClinicHolidayPort holidays;
    private AppointmentRepository appointments;
    private DoctorBlockedDatesPort blockedPort;
    private DoctorOpdDaysAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(DoctorSlotSettingsRepository.class);
        holidays = mock(ClinicHolidayPort.class);
        appointments = mock(AppointmentRepository.class);
        blockedPort = mock(DoctorBlockedDatesPort.class);
        adapter = new DoctorOpdDaysAdapter(repository, holidays, appointments, new DoctorBlockedDates(List.of(blockedPort)));
        when(holidays.closedDates(TENANT, THU, THU.plusDays(6))).thenReturn(Set.of());
    }

    private static SlotSettingsRow monToFri() {
        return new SlotSettingsRow(15, List.of(1, 2, 3, 4, 5), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));
    }

    @Test
    void onlyWorkingWeekdaysAreOpd() {
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.of(monToFri()));

        Set<LocalDate> opd = adapter.opdDates(TENANT, DOCTOR, THU.datesUntil(THU.plusDays(7)).toList());

        // Thu, Fri, Mon, Tue, Wed; not Sat 7 and Sun 8 Nov.
        assertThat(opd).containsExactlyInAnyOrder(THU, THU.plusDays(1), THU.plusDays(4), THU.plusDays(5),
                THU.plusDays(6));
    }

    @Test
    void aClosedHolidayRemovesTheDay() {
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.of(monToFri()));
        when(holidays.closedDates(TENANT, THU, THU.plusDays(6))).thenReturn(Set.of(THU.plusDays(1)));

        Set<LocalDate> opd = adapter.opdDates(TENANT, DOCTOR, THU.datesUntil(THU.plusDays(7)).toList());

        assertThat(opd).contains(THU).doesNotContain(THU.plusDays(1));
    }

    @Test
    void aDoctorWithNoSavedSettingsUsesTheDefaultEveryDayPattern() {
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.empty());

        assertThat(adapter.opdDates(TENANT, DOCTOR, THU.datesUntil(THU.plusDays(7)).toList())).hasSize(7);
    }

    @Test
    void hasOpdSlotsAnswersForOneDate() {
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.of(monToFri()));
        LocalDate saturday = THU.plusDays(2);
        when(holidays.closedDates(TENANT, saturday, saturday)).thenReturn(Set.of());
        when(holidays.closedDates(TENANT, THU, THU)).thenReturn(Set.of());

        assertThat(adapter.hasOpdSlots(TENANT, DOCTOR, THU)).isTrue();
        assertThat(adapter.hasOpdSlots(TENANT, DOCTOR, saturday)).isFalse();
    }

    @Test
    void noDatesMeansNoResult() {
        assertThat(adapter.opdDates(TENANT, DOCTOR, List.of())).isEmpty();
    }

    @Test
    void aDateBlockedByLeaveRemovesTheDayForThatDoctor() {
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.of(monToFri()));
        when(blockedPort.blockedDates(TENANT, DOCTOR, THU, THU.plusDays(6))).thenReturn(Set.of(THU));

        Set<LocalDate> opd = adapter.opdDates(TENANT, DOCTOR, THU.datesUntil(THU.plusDays(7)).toList());

        assertThat(opd).doesNotContain(THU).contains(THU.plusDays(1));
    }

    @Test
    void withNoBlockedDatesPortNothingIsBlocked() {
        adapter = new DoctorOpdDaysAdapter(repository, holidays, appointments, new DoctorBlockedDates(List.of()));
        when(repository.find(TENANT, DOCTOR)).thenReturn(Optional.of(monToFri()));

        assertThat(adapter.opdDates(TENANT, DOCTOR, List.of(THU))).containsExactly(THU);
    }

    @Test
    void bookedAppointmentsComeFromTheAppointmentRepository() {
        when(appointments.countBooked(TENANT, DOCTOR, THU, THU.plusDays(2))).thenReturn(3);

        assertThat(adapter.bookedAppointments(TENANT, DOCTOR, THU, THU.plusDays(2))).isEqualTo(3);
    }
}
