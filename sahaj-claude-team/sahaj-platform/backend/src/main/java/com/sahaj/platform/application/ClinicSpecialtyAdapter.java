package com.sahaj.platform.application;

import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link ClinicSpecialtyPort} over {@link ClinicProfileRepository} (SAH-43). */
@Service
public class ClinicSpecialtyAdapter implements ClinicSpecialtyPort {

    private final ClinicProfileRepository clinicProfileRepository;

    public ClinicSpecialtyAdapter(ClinicProfileRepository clinicProfileRepository) {
        this.clinicProfileRepository = clinicProfileRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<String> specialtyCode(UUID tenantId) {
        return clinicProfileRepository.find(tenantId)
                .map(ProfileRow::specialtyCode)
                .map(String::strip)
                .filter(code -> !code.isEmpty());
    }
}
