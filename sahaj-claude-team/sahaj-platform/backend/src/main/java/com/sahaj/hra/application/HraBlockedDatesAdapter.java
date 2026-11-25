package com.sahaj.hra.application;

import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.opd.api.DoctorBlockedDatesPort;
import com.sahaj.platform.api.ModuleEntitlementPort;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
            addDays(result, row, from, to);
        }
        return result;
    }

    /** SAH-90 (ADR-032 D1): one read of the clinic's approved leave, then split by doctor; none while HRA is off. */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<LocalDate>> blockedDatesForDoctors(UUID tenantId, Collection<UUID> doctorMembershipIds,
                                                            LocalDate from, LocalDate to) {
        Map<UUID, Set<LocalDate>> result = new HashMap<>();
        if (doctorMembershipIds.isEmpty() || !entitlementPort.forTenant(tenantId).hraOn()) {
            return result;
        }
        Set<UUID> wanted = Set.copyOf(doctorMembershipIds);
        for (LeaveRow row : leaveRepository.findApprovedOverlapping(tenantId, from, to)) {
            if (wanted.contains(row.membershipId())) {
                addDays(result.computeIfAbsent(row.membershipId(), id -> new HashSet<>()), row, from, to);
            }
        }
        return result;
    }

    private static void addDays(Set<LocalDate> into, LeaveRow row, LocalDate from, LocalDate to) {
        LocalDate day = row.from().isBefore(from) ? from : row.from();
        LocalDate last = row.to().isAfter(to) ? to : row.to();
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            into.add(day);
        }
    }
}
