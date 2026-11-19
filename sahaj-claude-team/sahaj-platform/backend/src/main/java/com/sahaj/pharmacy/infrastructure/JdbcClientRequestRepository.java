package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ClientRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.client_request}. The key is a random id from the client; it is never logged. */
@Repository
public class JdbcClientRequestRepository implements ClientRequestRepository {

    private static final String TAKE = """
            insert into pharmacy.client_request (tenant_id, unit_id, membership_id, client_key, operation, subject_id)
            values (:tenantId, :unitId, :membershipId, :clientKey, :operation, :subjectId)
            on conflict (tenant_id, membership_id, client_key) do nothing
            """;
    private static final String OWN = """
            select membership_id, operation, subject_id from pharmacy.client_request
             where tenant_id = :tenantId and membership_id = :membershipId and client_key = :clientKey
            """;
    private static final String ANYONE = """
            select exists (select 1 from pharmacy.client_request where tenant_id = :tenantId and client_key = :clientKey)
            """;

    private final JdbcClient jdbc;

    public JdbcClientRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean take(UUID tenantId, UUID unitId, UUID membershipId, UUID clientKey, String operation, UUID subjectId) {
        return jdbc.sql(TAKE).param("tenantId", tenantId).param("unitId", unitId).param("membershipId", membershipId)
                .param("clientKey", clientKey).param("operation", operation).param("subjectId", subjectId).update() == 1;
    }

    @Override
    public Optional<Taken> own(UUID tenantId, UUID membershipId, UUID clientKey) {
        return jdbc.sql(OWN).param("tenantId", tenantId).param("membershipId", membershipId).param("clientKey", clientKey)
                .query((rs, n) -> new Taken(rs.getObject("membership_id", UUID.class), rs.getString("operation"),
                        rs.getObject("subject_id", UUID.class))).optional();
    }

    @Override
    public boolean heldByAnyone(UUID tenantId, UUID clientKey) {
        return Boolean.TRUE.equals(jdbc.sql(ANYONE).param("tenantId", tenantId).param("clientKey", clientKey)
                .query(Boolean.class).single());
    }
}
