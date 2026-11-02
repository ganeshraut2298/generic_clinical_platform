package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.DoctorProfileRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.doctor_profile}: one row per doctor membership (SAH-4 {@code complete}). */
@Repository
public class JdbcDoctorProfileRepository implements DoctorProfileRepository {

    private static final String UPSERT = """
            insert into platform.doctor_profile (membership_id, tenant_id, specialty, fee_paise, degree, registration_no)
            values (:membershipId, :tenantId, :specialty, :feePaise, :degree, :registrationNo)
            on conflict (membership_id) do update set
                specialty = excluded.specialty,
                fee_paise = excluded.fee_paise,
                degree = excluded.degree,
                registration_no = excluded.registration_no
            """;

    private final JdbcClient jdbc;

    public JdbcDoctorProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID membershipId, UUID tenantId, String specialty, long feePaise, String degree,
                        String registrationNo) {
        jdbc.sql(UPSERT)
                .param("membershipId", membershipId)
                .param("tenantId", tenantId)
                .param("specialty", specialty)
                .param("feePaise", feePaise)
                .param("degree", degree)
                .param("registrationNo", registrationNo)
                .update();
    }
}
