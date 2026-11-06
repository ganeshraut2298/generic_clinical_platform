package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.BedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ipd.bed} (SAH-22). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcBedRepository implements BedRepository {

    private static final String FIND_ALL = """
            select id, ward_id, room_id, code, category_key, status
            from ipd.bed
            where tenant_id = :tenantId
            order by created_at, id
            """;
    private static final String FIND = """
            select id, ward_id, room_id, code, category_key, status
            from ipd.bed
            where tenant_id = :tenantId and id = :id
            """;
    private static final String INSERT = """
            insert into ipd.bed (id, tenant_id, ward_id, room_id, code, category_key, created_at, created_by)
            values (:id, :tenantId, :wardId, :roomId, :code, :category, :now, :actor)
            """;
    private static final String UPDATE_CATEGORY = """
            update ipd.bed
            set category_key = :category, version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String DELETE = """
            delete from ipd.bed
            where tenant_id = :tenantId and id = :id
            """;

    private static final String FIND_FOR_UPDATE = """
            select id, ward_id, room_id, code, category_key, status
            from ipd.bed
            where tenant_id = :tenantId and id = :id
            for update
            """;
    private static final String MARK_OCCUPIED = """
            update ipd.bed
            set status = 'occ', version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String UPDATE_STATUS = """
            update ipd.bed
            set status = :status, version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String HAS_HISTORY = """
            select exists (select 1 from ipd.admission where tenant_id = :tenantId and bed_id = :id)
                or exists (select 1 from ipd.bed_transfer
                           where tenant_id = :tenantId and (from_bed_id = :id or to_bed_id = :id))
            """;

    private final JdbcClient jdbc;

    public JdbcBedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BedRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).query(JdbcBedRepository::bed).list();
    }

    @Override
    public Optional<BedRow> find(UUID tenantId, UUID bedId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", bedId).query(JdbcBedRepository::bed).optional();
    }

    @Override
    public void insert(UUID tenantId, UUID id, UUID wardId, UUID roomId, String code, String categoryKey,
                       UUID actorUserId, Instant now) {
        jdbc.sql(INSERT).param("id", id).param("tenantId", tenantId).param("wardId", wardId)
                .param("roomId", roomId, Types.OTHER).param("code", code).param("category", categoryKey)
                .param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public void updateCategory(UUID tenantId, UUID bedId, String categoryKey) {
        jdbc.sql(UPDATE_CATEGORY).param("tenantId", tenantId).param("id", bedId).param("category", categoryKey)
                .update();
    }

    @Override
    public void delete(UUID tenantId, UUID bedId) {
        jdbc.sql(DELETE).param("tenantId", tenantId).param("id", bedId).update();
    }

    @Override
    public Optional<BedRow> findForUpdate(UUID tenantId, UUID bedId) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", bedId)
                .query(JdbcBedRepository::bed).optional();
    }

    @Override
    public void markOccupied(UUID tenantId, UUID bedId) {
        requireOne(jdbc.sql(MARK_OCCUPIED).param("tenantId", tenantId).param("id", bedId).update());
    }

    @Override
    public void updateStatus(UUID tenantId, UUID bedId, String status) {
        requireOne(jdbc.sql(UPDATE_STATUS).param("tenantId", tenantId).param("id", bedId).param("status", status)
                .update());
    }

    @Override
    public boolean hasAdmissionHistory(UUID tenantId, UUID bedId) {
        return jdbc.sql(HAS_HISTORY).param("tenantId", tenantId).param("id", bedId)
                .query((rs, rowNum) -> rs.getBoolean(1)).single();
    }

    /** Security review M1: the caller has locked the bed row, so any count but one is a server fault. */
    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("The bed status was not changed");
        }
    }

    private static BedRow bed(ResultSet rs, int rowNum) throws SQLException {
        return new BedRow(rs.getObject("id", UUID.class), rs.getObject("ward_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("code"), rs.getString("category_key"),
                rs.getString("status"));
    }
}
