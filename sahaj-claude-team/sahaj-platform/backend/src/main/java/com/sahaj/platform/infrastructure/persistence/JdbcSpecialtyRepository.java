package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.SpecialtyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Global, read-only {@code platform.specialty} with its template (SAH-7). The application has SELECT only. */
@Repository
public class JdbcSpecialtyRepository implements SpecialtyRepository {

    private static final String SELECT = """
            select s.code, s.name, t.phase, t.template_json::text as template_json
            from platform.specialty s
            join platform.specialty_template t on t.code = s.code
            """;
    private static final String FIND_ALL = SELECT + " order by s.sort_order";
    private static final String FIND = SELECT + " where s.code = :code";

    private final JdbcClient jdbc;

    public JdbcSpecialtyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SpecialtyRow> findAll() {
        return jdbc.sql(FIND_ALL).query(JdbcSpecialtyRepository::map).list();
    }

    @Override
    public Optional<SpecialtyRow> find(String code) {
        return jdbc.sql(FIND).param("code", code).query(JdbcSpecialtyRepository::map).optional();
    }

    private static SpecialtyRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SpecialtyRow(rs.getString("code"), rs.getString("name"), rs.getInt("phase"),
                rs.getString("template_json"));
    }
}
