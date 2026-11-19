package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.PrintTemplateRepository;
import com.sahaj.pharmacy.domain.PrintDocType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code pharmacy.print_template_setting} (SAH-78-10): settings only, the preview is the browser print view. No delete. */
@Repository
public class JdbcPrintTemplateRepository implements PrintTemplateRepository {

    private static final String LIST = """
            select id, doc_type, header_line_1, header_line_2, header_line_3, footer_note, paper_size, version
              from pharmacy.print_template_setting where tenant_id = :tenantId
            """;
    private static final String FIND = LIST + " and doc_type = :docType";
    private static final String INSERT = """
            insert into pharmacy.print_template_setting (tenant_id, unit_id, doc_type, header_line_1, header_line_2,
                                                         header_line_3, footer_note, paper_size, created_by, version)
            values (:tenantId, :unitId, :docType, cast(:h1 as text), cast(:h2 as text), cast(:h3 as text),
                    cast(:footer as text), :paper, :actor, 1)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.print_template_setting
               set header_line_1 = cast(:h1 as text), header_line_2 = cast(:h2 as text), header_line_3 = cast(:h3 as text),
                   footer_note = cast(:footer as text), paper_size = :paper, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;

    private final JdbcClient jdbc;

    public JdbcPrintTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> list(UUID tenantId) {
        return jdbc.sql(LIST).param("tenantId", tenantId).query(JdbcPrintTemplateRepository::map).list();
    }

    @Override
    public Optional<Row> find(UUID tenantId, PrintDocType docType) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("docType", docType.name())
                .query(JdbcPrintTemplateRepository::map).optional();
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, PrintDocType docType, List<String> headerLines, String footerNote,
                       String paperSize, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("docType", docType.name())
                .param("h1", line(headerLines, 0)).param("h2", line(headerLines, 1)).param("h3", line(headerLines, 2))
                .param("footer", footerNote).param("paper", paperSize).param("actor", actorUserId)
                .query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, List<String> headerLines, String footerNote,
                          String paperSize, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("h1", line(headerLines, 0)).param("h2", line(headerLines, 1)).param("h3", line(headerLines, 2))
                .param("footer", footerNote).param("paper", paperSize).param("actor", actorUserId).update() == 1;
    }

    private static String line(List<String> lines, int index) {
        return index < lines.size() ? lines.get(index) : null;
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String line = rs.getString("header_line_" + i);
            if (line != null) {
                lines.add(line);
            }
        }
        return new Row(rs.getObject("id", UUID.class), PrintDocType.valueOf(rs.getString("doc_type")), lines,
                rs.getString("footer_note"), rs.getString("paper_size"), rs.getLong("version"));
    }
}
