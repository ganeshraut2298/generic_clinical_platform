package com.sahaj.platform.application;

import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implements {@link ClinicLetterheadPort} over the clinic profile (SAH-55). Read only. */
@Component
class ClinicLetterheadAdapter implements ClinicLetterheadPort {

    private final ClinicProfileRepository profiles;

    ClinicLetterheadAdapter(ClinicProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public Optional<Letterhead> find(UUID tenantId) {
        return profiles.find(tenantId).map(ClinicLetterheadAdapter::letterhead);
    }

    static Letterhead letterhead(ProfileRow row) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {row.address(), row.city(), row.pin()}) {
            if (part != null && !part.isBlank()) {
                parts.add(part.strip());
            }
        }
        String phone = row.phone() == null || row.phone().isBlank() ? null : row.phone().strip();
        return new Letterhead(row.name(), parts.isEmpty() ? null : String.join(", ", parts), phone);
    }
}
