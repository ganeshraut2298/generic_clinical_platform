package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DocumentSeriesRepository;
import com.sahaj.pharmacy.domain.DocType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.document_series} (SAH-78-10). Row level security scopes every statement to the bound tenant. No delete.
 * {@code pharmacy.document_counter} is only read here, for "numbers issued"; it is written by the database function
 * {@code pharmacy.next_document_no()} and by nothing else.
 */
@Repository
public class JdbcDocumentSeriesRepository implements DocumentSeriesRepository {

    private static final String COLUMNS = "id, doc_type, prefix, financial_year_part, sequence_length, restarts_each_fy, version";
    private static final String LIST = "select " + COLUMNS + " from pharmacy.document_series where tenant_id = :tenantId";
    private static final String FIND = LIST + " and doc_type = :docType";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    /**
     * Serves the series lock (DEC-078 item 4, migration 1280): pharmacy.next_document_no() sets last_issued_at on every number it
     * hands out; a row issued before the column existed has it null, and its updated_at is the same instant.
     */
    private static final String ISSUED_SINCE = """
            select exists (select 1 from pharmacy.document_counter
                            where tenant_id = :tenantId and doc_type = :docType
                              and coalesce(last_issued_at, updated_at) >= :since)
            """;
    private static final String ISSUED_TYPES_SINCE = """
            select distinct doc_type from pharmacy.document_counter
             where tenant_id = :tenantId and coalesce(last_issued_at, updated_at) >= :since
            """;
    private static final String INSERT = """
            insert into pharmacy.document_series (tenant_id, unit_id, doc_type, prefix, financial_year_part, sequence_length,
                                                  restarts_each_fy, created_by, version)
            values (:tenantId, :unitId, :docType, :prefix, :fyPart, :length, :restarts, :actor, 1)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.document_series
               set prefix = :prefix, financial_year_part = :fyPart, sequence_length = :length, restarts_each_fy = :restarts,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;

    private final JdbcClient jdbc;

    public JdbcDocumentSeriesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> list(UUID tenantId) {
        return jdbc.sql(LIST).param("tenantId", tenantId).query(JdbcDocumentSeriesRepository::map).list();
    }

    @Override
    public Optional<Row> find(UUID tenantId, DocType docType) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("docType", docType.name())
                .query(JdbcDocumentSeriesRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, DocType docType) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("docType", docType.name())
                .query(JdbcDocumentSeriesRepository::map).optional();
    }

    @Override
    public boolean issuedSince(UUID tenantId, DocType docType, Instant since) {
        return Boolean.TRUE.equals(jdbc.sql(ISSUED_SINCE).param("tenantId", tenantId).param("docType", docType.name())
                .param("since", java.sql.Timestamp.from(since)).query(Boolean.class).single());
    }

    @Override
    public Set<DocType> issuedSince(UUID tenantId, Instant since) {
        Set<DocType> issued = EnumSet.noneOf(DocType.class);
        jdbc.sql(ISSUED_TYPES_SINCE).param("tenantId", tenantId)
                .param("since", java.sql.Timestamp.from(since)).query(String.class).list()
                .forEach(type -> issued.add(DocType.valueOf(type)));
        return issued;
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, DocType docType, String prefix, boolean financialYearPart,
                       int sequenceLength, boolean restartsEachFinancialYear, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("docType", docType.name())
                .param("prefix", prefix).param("fyPart", financialYearPart).param("length", sequenceLength)
                .param("restarts", restartsEachFinancialYear).param("actor", actorUserId).query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, String prefix, boolean financialYearPart,
                          int sequenceLength, boolean restartsEachFinancialYear, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("prefix", prefix).param("fyPart", financialYearPart).param("length", sequenceLength)
                .param("restarts", restartsEachFinancialYear).param("actor", actorUserId).update() == 1;
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), DocType.valueOf(rs.getString("doc_type")), rs.getString("prefix"),
                rs.getBoolean("financial_year_part"), rs.getInt("sequence_length"), rs.getBoolean("restarts_each_fy"),
                rs.getLong("version"));
    }
}
