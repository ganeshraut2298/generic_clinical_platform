package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.WardRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ipd.ward} and {@code ipd.room} (SAH-22). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcWardRepository implements WardRepository {

    private static final String LOCK = """
            select pg_advisory_xact_lock(hashtextextended(:key, 0))
            """;
    private static final String FIND_WARDS = """
            select id, name, station
            from ipd.ward
            where tenant_id = :tenantId
            order by created_at, id
            """;
    private static final String FIND_ROOMS = """
            select id, ward_id, name
            from ipd.room
            where tenant_id = :tenantId
            order by created_at, id
            """;
    private static final String FIND_WARD = """
            select id, name, station
            from ipd.ward
            where tenant_id = :tenantId and id = :id
            """;
    private static final String FIND_ROOM = """
            select id, ward_id, name
            from ipd.room
            where tenant_id = :tenantId and id = :id
            """;
    private static final String NAME_EXISTS = """
            select count(*)
            from ipd.ward
            where tenant_id = :tenantId
              and lower(translate(name, U&'\\200C\\200D', '')) = lower(translate(:name, U&'\\200C\\200D', ''))
            """;
    private static final String INSERT_WARD = """
            insert into ipd.ward (id, tenant_id, name, created_at, created_by)
            values (:id, :tenantId, :name, :now, :actor)
            """;
    private static final String INSERT_ROOM = """
            insert into ipd.room (id, tenant_id, ward_id, name, created_at, created_by)
            values (:id, :tenantId, :wardId, :name, :now, :actor)
            """;

    private final JdbcClient jdbc;

    public JdbcWardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockTenant(UUID tenantId) {
        jdbc.sql(LOCK).param("key", "ipd:" + tenantId).query((rs, rowNum) -> 1).list();
    }

    @Override
    public List<WardRow> findWards(UUID tenantId) {
        return jdbc.sql(FIND_WARDS).param("tenantId", tenantId).query(JdbcWardRepository::ward).list();
    }

    @Override
    public List<RoomRow> findRooms(UUID tenantId) {
        return jdbc.sql(FIND_ROOMS).param("tenantId", tenantId).query(JdbcWardRepository::room).list();
    }

    @Override
    public Optional<WardRow> findWard(UUID tenantId, UUID wardId) {
        return jdbc.sql(FIND_WARD).param("tenantId", tenantId).param("id", wardId)
                .query(JdbcWardRepository::ward).optional();
    }

    @Override
    public Optional<RoomRow> findRoom(UUID tenantId, UUID roomId) {
        return jdbc.sql(FIND_ROOM).param("tenantId", tenantId).param("id", roomId)
                .query(JdbcWardRepository::room).optional();
    }

    @Override
    public boolean wardNameExists(UUID tenantId, String name) {
        long count = jdbc.sql(NAME_EXISTS).param("tenantId", tenantId).param("name", name)
                .query((rs, rowNum) -> rs.getLong(1)).optional().orElse(0L);
        return count > 0;
    }

    @Override
    public void insertWard(UUID tenantId, UUID id, String name, UUID actorUserId, Instant now) {
        jdbc.sql(INSERT_WARD).param("id", id).param("tenantId", tenantId).param("name", name)
                .param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public void insertRoom(UUID tenantId, UUID id, UUID wardId, String name, UUID actorUserId, Instant now) {
        jdbc.sql(INSERT_ROOM).param("id", id).param("tenantId", tenantId).param("wardId", wardId)
                .param("name", name).param("now", now).param("actor", actorUserId).update();
    }

    private static WardRow ward(ResultSet rs, int rowNum) throws SQLException {
        return new WardRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("station"));
    }

    private static RoomRow room(ResultSet rs, int rowNum) throws SQLException {
        return new RoomRow(rs.getObject("id", UUID.class), rs.getObject("ward_id", UUID.class), rs.getString("name"));
    }
}
