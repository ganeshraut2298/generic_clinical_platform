package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.DiagnosisRepository;
import com.sahaj.opd.application.SearchPrefix;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global, read-only {@code opd.diagnosis} (SAH-7). Matches the start of the name or of the code;
 * both columns have a prefix index. The {@code specialty} column is returned as a hint only and is
 * never used to filter.
 */
@Repository
public class JdbcDiagnosisRepository implements DiagnosisRepository {

    private static final String SEARCH = """
            select code, name, specialty from opd.diagnosis
            where lower(name) like :namePattern escape '\\' or code like :codePattern escape '\\'
            order by case when specialty = :specialty then 0 else 1 end, lower(name), code
            limit :limit offset :offset
            """;

    private final JdbcClient jdbc;

    public JdbcDiagnosisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DiagnosisRow> search(String prefix, String specialty, int limit, int offset) {
        return jdbc.sql(SEARCH)
                .param("specialty", specialty == null ? "" : specialty)
                .param("offset", offset)
                .param("namePattern", SearchPrefix.lowerPattern(prefix))
                .param("codePattern", SearchPrefix.upperPattern(prefix))
                .param("limit", limit)
                .query(JdbcDiagnosisRepository::map).list();
    }

    private static DiagnosisRow map(ResultSet rs, int rowNum) throws SQLException {
        return new DiagnosisRow(rs.getString("code"), rs.getString("name"), rs.getString("specialty"));
    }
}
