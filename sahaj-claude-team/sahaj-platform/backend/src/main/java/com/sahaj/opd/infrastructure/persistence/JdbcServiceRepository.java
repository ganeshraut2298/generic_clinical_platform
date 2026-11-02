package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.ServiceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code opd.service} (SAH-7). Row level security limits every statement to the bound clinic and the
 * tenant id is in each read too; {@code tenant_id} is never written (the table fills it from the
 * bound clinic). The unique key on clinic and lower-case name is the duplicate guard.
 */
@Repository
public class JdbcServiceRepository implements ServiceRepository {

    private static final String SELECT = """
            select id, name, category, price_paise, active from opd.service where tenant_id = :tenantId
            """;
    private static final String FIND_ALL = SELECT
            + " and (cast(:active as boolean) is null or active = cast(:active as boolean)) order by lower(name)";
    private static final String FIND_BY_ID = SELECT + " and id = :id";
    private static final String INSERT = """
            insert into opd.service (id, name, category, price_paise, active, created_by)
            values (:id, :name, :category, :pricePaise, :active, :actor)
            """;
    private static final String UPDATE = """
            update opd.service
            set name = :name, category = :category, price_paise = :pricePaise, active = :active,
                updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcServiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ServiceRow> findAll(UUID tenantId, Boolean active) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).param("active", active, Types.BOOLEAN)
                .query(JdbcServiceRepository::map).list();
    }

    @Override
    public Optional<ServiceRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcServiceRepository::map).optional();
    }

    @Override
    public void insert(UUID id, String name, String category, long pricePaise, boolean active, UUID actorUserId) {
        jdbc.sql(INSERT).param("id", id).param("name", name).param("category", category)
                .param("pricePaise", pricePaise).param("active", active).param("actor", actorUserId).update();
    }

    @Override
    public void update(UUID id, String name, String category, long pricePaise, boolean active, UUID actorUserId) {
        jdbc.sql(UPDATE).param("id", id).param("name", name).param("category", category)
                .param("pricePaise", pricePaise).param("active", active).param("actor", actorUserId).update();
    }

    private static ServiceRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                rs.getLong("price_paise"), rs.getBoolean("active"));
    }
}
