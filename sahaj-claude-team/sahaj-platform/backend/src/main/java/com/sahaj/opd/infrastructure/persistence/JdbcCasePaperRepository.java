package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.CasePaperRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.case_paper_settings}, one row per clinic created by the first save (SAH-7). */
@Repository
public class JdbcCasePaperRepository implements CasePaperRepository {

    private static final String FIND = """
            select size, lang2, preprinted, caps, sections_json::text as sections_json
            from opd.case_paper_settings where tenant_id = :tenantId
            """;
    private static final String UPSERT = """
            insert into opd.case_paper_settings (tenant_id, size, lang2, preprinted, caps, sections_json, updated_by)
            values (:tenantId, :size, :lang2, :preprinted, :caps, cast(:sections as jsonb), :actor)
            on conflict (tenant_id) do update set
                size = excluded.size,
                lang2 = excluded.lang2,
                preprinted = excluded.preprinted,
                caps = excluded.caps,
                sections_json = excluded.sections_json,
                updated_by = excluded.updated_by,
                updated_at = now(),
                version = opd.case_paper_settings.version + 1
            """;
    private static final TypeReference<Map<String, Boolean>> SECTIONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCasePaperRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PaperRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(this::map).optional();
    }

    @Override
    public void upsert(UUID tenantId, PaperRow row, UUID actorUserId) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("size", row.size())
                .param("lang2", row.secondLanguage())
                .param("preprinted", row.preprinted())
                .param("caps", row.caps())
                .param("sections", write(row.sections()))
                .param("actor", actorUserId)
                .update();
    }

    private PaperRow map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new PaperRow(rs.getString("size"), rs.getString("lang2"), rs.getBoolean("preprinted"),
                    rs.getBoolean("caps"), objectMapper.readValue(rs.getString("sections_json"), SECTIONS));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("case_paper_settings.sections_json is not valid JSON", ex);
        }
    }

    private String write(Map<String, Boolean> sections) {
        try {
            return objectMapper.writeValueAsString(sections);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Sections could not be written as JSON", ex);
        }
    }
}
