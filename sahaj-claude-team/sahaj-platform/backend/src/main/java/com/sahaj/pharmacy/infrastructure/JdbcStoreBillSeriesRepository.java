package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StoreBillSeriesRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.store_bill_series} (SAH-80-1). Every statement names the tenant as well as relying on row level security. No delete. */
@Repository
public class JdbcStoreBillSeriesRepository implements StoreBillSeriesRepository {

    private static final String COLUMNS = "id, store_id, prefix, financial_year_part, sequence_length, restarts_each_fy, version";
    private static final String LIST = "select " + COLUMNS + " from pharmacy.store_bill_series where tenant_id = :tenantId";
    private static final String FIND = LIST + " and store_id = :storeId";
    private static final String INSERT = """
            insert into pharmacy.store_bill_series (tenant_id, unit_id, store_id, prefix, financial_year_part, sequence_length,
                                                    restarts_each_fy, created_by, version)
            values (:tenantId, :unitId, :storeId, :prefix, :fyPart, :length, :restarts, :actor, 1)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.store_bill_series
               set prefix = :prefix, financial_year_part = :fyPart, sequence_length = :length, restarts_each_fy = :restarts,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String WITH_NUMBERS = "select distinct store_id from pharmacy.store_bill_counter where tenant_id = :tenantId";
    private static final String ISSUED_SINCE = WITH_NUMBERS + " and coalesce(last_issued_at, updated_at) >= :since";

    private final JdbcClient jdbc;

    public JdbcStoreBillSeriesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> list(UUID tenantId) {
        return jdbc.sql(LIST).param("tenantId", tenantId).query(JdbcStoreBillSeriesRepository::map).list();
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId)
                .query(JdbcStoreBillSeriesRepository::map).optional();
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, UUID storeId, String prefix, boolean financialYearPart, int sequenceLength,
                       boolean restartsEachFinancialYear, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("prefix", prefix).param("fyPart", financialYearPart).param("length", sequenceLength)
                .param("restarts", restartsEachFinancialYear).param("actor", actorUserId)
                .query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, String prefix, boolean financialYearPart, int sequenceLength,
                          boolean restartsEachFinancialYear, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version).param("prefix", prefix)
                .param("fyPart", financialYearPart).param("length", sequenceLength)
                .param("restarts", restartsEachFinancialYear).param("actor", actorUserId).update() == 1;
    }

    @Override
    public Set<UUID> storesWithNumbers(UUID tenantId) {
        return new HashSet<>(jdbc.sql(WITH_NUMBERS).param("tenantId", tenantId).query(UUID.class).list());
    }

    @Override
    public Set<UUID> storesIssuedSince(UUID tenantId, Instant since) {
        return new HashSet<>(jdbc.sql(ISSUED_SINCE).param("tenantId", tenantId).param("since", Timestamp.from(since))
                .query(UUID.class).list());
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("prefix"),
                rs.getBoolean("financial_year_part"), rs.getInt("sequence_length"), rs.getBoolean("restarts_each_fy"),
                rs.getLong("version"));
    }
}
