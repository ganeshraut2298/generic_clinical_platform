package com.sahaj.platform.application;

import com.sahaj.platform.api.ClinicHolidayPort;
import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link ClinicHolidayPort} over {@link HolidayRepository} (SAH-20). */
@Service
class ClinicHolidayAdapter implements ClinicHolidayPort {

    private final HolidayRepository holidayRepository;

    ClinicHolidayAdapter(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Set<LocalDate> closedDates(UUID tenantId, LocalDate from, LocalDate to) {
        return holidayRepository.findAll(tenantId).stream()
                .filter(row -> "CLOSED".equals(row.type()))
                .map(HolidayRow::date)
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .collect(Collectors.toSet());
    }
}
