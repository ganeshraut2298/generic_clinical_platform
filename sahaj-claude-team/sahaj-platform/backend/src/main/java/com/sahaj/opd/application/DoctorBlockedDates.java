package com.sahaj.opd.application;

import com.sahaj.opd.api.DoctorBlockedDatesPort;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** SAH-90: the blocked dates of many doctors, one call per port (never one per doctor with an overriding port). */
    Map<UUID, Set<LocalDate>> blockedForDoctors(UUID tenantId, Collection<UUID> doctorMembershipIds, LocalDate from,
                                                LocalDate to) {
        Map<UUID, Set<LocalDate>> result = new HashMap<>();
        for (DoctorBlockedDatesPort port : ports) {
            port.blockedDatesForDoctors(tenantId, doctorMembershipIds, from, to)
                    .forEach((id, dates) -> result.computeIfAbsent(id, key -> new HashSet<>()).addAll(dates));
        }
        return result;
    }
}
