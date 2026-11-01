package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.ConsentRecordRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.consent_record}: no tenant, no row level security, append-only (SAH-3 AC12). */
@Repository
public class JdbcConsentRecordRepository implements ConsentRecordRepository {

    private static final String INSERT = """
            insert into platform.consent_record (id, user_id, terms_version, privacy_version, source_ip)
            values (:id, :userId, :termsVersion, :privacyVersion, cast(:sourceIp as inet))
            """;

    private final JdbcClient jdbc;

    public JdbcConsentRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID userId, String termsVersion, String privacyVersion, String sourceIp) {
        jdbc.sql(INSERT)
                .param("id", UUID.randomUUID()).param("userId", userId)
                .param("termsVersion", termsVersion).param("privacyVersion", privacyVersion)
                .param("sourceIp", sourceIp)
                .update();
    }
}
