package com.sahaj.opd.application;

import com.sahaj.opd.api.DoctorOpdDaysPort;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.platform.api.ClinicHolidayPort;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link DoctorOpdDaysPort} for {@code hra} (SAH-20) over the doctor slot settings and the clinic holidays. */
@Service
class DoctorOpdDaysAdapter implements DoctorOpdDaysPort {

    private final DoctorSlotSettingsRepository repository;
    private final ClinicHolidayPort holidayPort;
    private final AppointmentRepository appointmentRepository;
    private final DoctorBlockedDates blockedDates;

    DoctorOpdDaysAdapter(DoctorSlotSettingsRepository repository, ClinicHolidayPort holidayPort,
                         AppointmentRepository appointmentRepository, DoctorBlockedDates blockedDates) {
        this.repository = repository;
        this.holidayPort = holidayPort;
        this.appointmentRepository = appointmentRepository;
        this.blockedDates = blockedDates;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<LocalDate> opdDates(UUID tenantId, UUID doctorMembershipId, Collection<LocalDate> dates) {
        Set<LocalDate> result = new LinkedHashSet<>();
        if (dates.isEmpty()) {
            return result;
        }
        SlotSettingsRow settings = repository.find(tenantId, doctorMembershipId)
                .orElse(DoctorSlotSettingsService.DEFAULTS);
        LocalDate from = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate to = dates.stream().max(LocalDate::compareTo).orElseThrow();
        Set<LocalDate> closed = new LinkedHashSet<>(holidayPort.closedDates(tenantId, from, to));
        closed.addAll(blockedDates.blocked(tenantId, doctorMembershipId, from, to));
        for (LocalDate date : dates) {
            if (!closed.contains(date) && hasSlots(settings, date)) {
                result.add(date);
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpdSlots(UUID tenantId, UUID doctorMembershipId, LocalDate date) {
        return !opdDates(tenantId, doctorMembershipId, List.of(date)).isEmpty();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Map<LocalDate, Integer>> bookedAppointmentsByDay(UUID tenantId,
                                                                     Collection<UUID> doctorMembershipIds,
                                                                     LocalDate from, LocalDate to) {
        if (doctorMembershipIds.isEmpty()) {
            return Map.of();
        }
        return appointmentRepository.countBookedByDay(tenantId, doctorMembershipIds, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public int bookedAppointments(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to) {
        return appointmentRepository.countBooked(tenantId, doctorMembershipId, from, to);
    }

    private static boolean hasSlots(SlotSettingsRow settings, LocalDate date) {
        List<SlotComputation.Session> sessions = settings.sessions().stream()
                .map((SessionRow s) -> new SlotComputation.Session(s.sessionIndex(), s.startTime(), s.endTime()))
                .toList();
        return !SlotComputation.slotsFor(date, settings.slotMinutes(), Set.copyOf(settings.workingDays()),
                settings.walkInEveryNth(), sessions).isEmpty();
    }
}
