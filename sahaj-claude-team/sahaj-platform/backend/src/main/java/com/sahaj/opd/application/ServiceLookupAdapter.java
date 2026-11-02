package com.sahaj.opd.application;

import com.sahaj.opd.api.ServiceLookupPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link ServiceLookupPort} for {@code billing} (SAH-10) over {@link ServiceRepository}. */
@Service
class ServiceLookupAdapter implements ServiceLookupPort {

    private final ServiceRepository serviceRepository;

    ServiceLookupAdapter(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceSummary> find(UUID tenantId, UUID serviceId) {
        return serviceRepository.findById(tenantId, serviceId)
                .map(row -> new ServiceSummary(row.id(), row.name(), row.pricePaise(), row.active()));
    }
}
