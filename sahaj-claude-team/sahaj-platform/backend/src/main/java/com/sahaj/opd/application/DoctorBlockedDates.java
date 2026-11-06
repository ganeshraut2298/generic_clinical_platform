package com.sahaj.opd.application;

import com.sahaj.opd.api.DoctorBlockedDatesPort;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** SAH-21: asks every {@link DoctorBlockedDatesPort} bean and merges the answers; with none, nothing is blocked. */
@Component
class DoctorBlockedDates {

    private final List<DoctorBlockedDatesPort> ports;

    @Autowired
    DoctorBlockedDates(ObjectProvider<DoctorBlockedDatesPort> ports) {
        this.ports = ports.orderedStream().toList();
    }

    DoctorBlockedDates(List<DoctorBlockedDatesPort> ports) {
        this.ports = List.copyOf(ports);
    }

    Set<LocalDate> blocked(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to) {
        Set<LocalDate> result = new HashSet<>();
        for (DoctorBlockedDatesPort port : ports) {
            result.addAll(port.blockedDates(tenantId, doctorMembershipId, from, to));
        }
        return result;
    }
}
