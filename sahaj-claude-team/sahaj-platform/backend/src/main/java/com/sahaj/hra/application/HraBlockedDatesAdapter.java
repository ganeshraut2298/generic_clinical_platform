package com.sahaj.hra.application;

import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.opd.api.DoctorBlockedDatesPort;
import com.sahaj.platform.api.ModuleEntitlementPort;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-21: implements {@code opd}'s {@link DoctorBlockedDatesPort} with the days of a doctor's approved leave.
 * Returns nothing while the HRA add-on is off (the leave data is kept).
 */
@Service
class HraBlockedDatesAdapter implements DoctorBlockedDatesPort {

    private final LeaveRepository leaveRepository;
    private final ModuleEntitlementPort entitlementPort;

    HraBlockedDatesAdapter(LeaveRepository leaveRepository, ModuleEntitlementPort entitlementPort) {
        this.leaveRepository = leaveRepository;
        this.entitlementPort = entitlementPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<LocalDate> blockedDates(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to) {
        Set<LocalDate> result = new HashSet<>();
        if (!entitlementPort.forTenant(tenantId).hraOn()) {
            return result;
        }
        for (LeaveRow row : leaveRepository.findApprovedOverlappingForMember(tenantId, doctorMembershipId, from, to)) {
            LocalDate day = row.from().isBefore(from) ? from : row.from();
            LocalDate last = row.to().isAfter(to) ? to : row.to();
            for (; !day.isAfter(last); day = day.plusDays(1)) {
                result.add(day);
            }
        }
        return result;
    }
}
