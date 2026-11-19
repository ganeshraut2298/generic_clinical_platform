package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.UnitRepository;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Unit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code pharmacy.unit}, one row per clinic. Row level security scopes every statement to the bound tenant. No delete. */
@Repository
public class JdbcUnitRepository implements UnitRepository {

    private static final String SELECT = """
            select id, code, gstin, drug_licence_no, drug_licence_valid_till, integration_mode, updated_at, version
            from pharmacy.unit
            where tenant_id = :tenantId
            """;
    private static final String ENSURE = """
            insert into pharmacy.unit (tenant_id, code, created_by)
            values (:tenantId, :code, :actor)
            on conflict (tenant_id) do nothing
            """;
    private static final String UPDATE_SETTINGS = """
            update pharmacy.unit
            set gstin = cast(:gstin as text), drug_licence_no = cast(:licenceNo as text),
                drug_licence_valid_till = cast(:licenceTill as date), integration_mode = :mode,
                updated_at = now(), updated_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id and version = :version
            """;

    private final JdbcClient jdbc;

    public JdbcUnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Unit> find(UUID tenantId) {
        return jdbc.sql(SELECT).param("tenantId", tenantId).query(JdbcUnitRepository::map).optional();
    }

    @Override
    public Unit ensureAndLock(UUID tenantId, String code, UUID actorUserId) {
        jdbc.sql(ENSURE).param("tenantId", tenantId).param("code", code).param("actor", actorUserId).update();
        return jdbc.sql(SELECT + " for update").param("tenantId", tenantId).query(JdbcUnitRepository::map).single();
    }

    @Override
    public boolean updateSettings(UUID tenantId, UUID unitId, long version, String gstin, String drugLicenceNo,
                                  LocalDate drugLicenceValidTill, IntegrationMode integrationMode, UUID actorUserId) {
        return jdbc.sql(UPDATE_SETTINGS).param("tenantId", tenantId).param("id", unitId).param("version", version)
                .param("gstin", gstin).param("licenceNo", drugLicenceNo).param("licenceTill", drugLicenceValidTill)
                .param("mode", integrationMode.name()).param("actor", actorUserId).update() == 1;
    }

    private static Unit map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Unit(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("gstin"),
                rs.getString("drug_licence_no"), rs.getObject("drug_licence_valid_till", LocalDate.class),
                IntegrationMode.valueOf(rs.getString("integration_mode")), updated == null ? null : updated.toInstant(),
                rs.getLong("version"));
    }
}
