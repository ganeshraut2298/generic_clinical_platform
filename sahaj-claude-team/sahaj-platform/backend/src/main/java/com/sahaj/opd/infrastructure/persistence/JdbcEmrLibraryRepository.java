package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.EmrFieldType;
import com.sahaj.opd.application.emr.EmrLibraryRepository;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrModel.TableColumn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global, read-only EMR library, starters, starter layouts and aliases (SAH-53, ADR-020 D2, D3, D4). The tables have
 * no tenant column and the application role has SELECT only. Every value that arrives from a request is a bound
 * parameter; table names are fixed text.
 */
@Repository
public class JdbcEmrLibraryRepository implements EmrLibraryRepository {

    private static final String SECTIONS = """
            select section_key, name, kind, description, always_shown
            from opd.emr_section
            order by case kind when 'COMMON' then 0 when 'SPECIALTY' then 1 else 2 end, position, section_key
            """;
    private static final String FIELDS = """
            select section_key, field_key, label, field_type, meta, required, options_source, options_master,
                   allow_free_text, min_value, max_value, columns::text as columns
            from opd.emr_section_field order by section_key, position
            """;
    private static final String OPTIONS = """
            select section_key, field_key, option_key, label, is_none
            from opd.emr_section_field_option order by section_key, field_key, position
            """;
    private static final String USED_BY = """
            select s.section_key, s.starter_key
            from opd.emr_starter_department_section s join opd.emr_starter_department d on d.starter_key = s.starter_key
            order by s.section_key, d.position
            """;
    private static final String STARTERS = """
            select starter_key, name, initials, colour, description, consent_flag, pcpndt_flag, position,
                   current_layout_version
            from opd.emr_starter_department order by position
            """;
    private static final String CURRENT_LAYOUT = """
            select l.starter_key, l.layout_version, l.layout::text as layout
            from opd.emr_starter_layout l join opd.emr_starter_department d
              on d.starter_key = l.starter_key and d.current_layout_version = l.layout_version
            where l.starter_key = :key
            """;
    private static final String LAYOUT = """
            select starter_key, layout_version, layout::text as layout
            from opd.emr_starter_layout where starter_key = :key and layout_version = :version
            """;
    private static final String ALIAS = "select starter_key from opd.emr_department_alias where alias_normalised = :alias";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcEmrLibraryRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<LibrarySection> sections() {
        Map<String, List<FieldDefinition>> fields = fieldsBySection();
        Map<String, List<String>> usedBy = new HashMap<>();
        jdbc.sql(USED_BY).query(rs -> {
            usedBy.computeIfAbsent(rs.getString("section_key"), k -> new ArrayList<>()).add(rs.getString("starter_key"));
        });
        return jdbc.sql(SECTIONS).query((rs, row) -> {
            String key = rs.getString("section_key");
            return new LibrarySection(key, rs.getString("name"), rs.getString("kind"), rs.getString("description"),
                    rs.getBoolean("always_shown"),
                    com.sahaj.opd.application.emr.EmrComponentBinding.bind(key, fields.getOrDefault(key, List.of())),
                    List.copyOf(usedBy.getOrDefault(key, List.of())));
        }).list();
    }

    @Override
    public Optional<LibrarySection> section(String sectionKey) {
        return sections().stream().filter(section -> section.key().equals(sectionKey)).findFirst();
    }

    private Map<String, List<FieldDefinition>> fieldsBySection() {
        Map<String, List<FieldOption>> options = new HashMap<>();
        jdbc.sql(OPTIONS).query(rs -> {
            options.computeIfAbsent(rs.getString("section_key") + "/" + rs.getString("field_key"),
                    k -> new ArrayList<>())
                    .add(new FieldOption(rs.getString("option_key"), rs.getString("label"), rs.getBoolean("is_none")));
        });
        Map<String, List<FieldDefinition>> result = new LinkedHashMap<>();
        jdbc.sql(FIELDS).query(rs -> {
            String section = rs.getString("section_key");
            String key = rs.getString("field_key");
            String type = rs.getString("field_type");
            BigDecimal min = rs.getBigDecimal("min_value");
            BigDecimal max = rs.getBigDecimal("max_value");
            result.computeIfAbsent(section, k -> new ArrayList<>()).add(new FieldDefinition(key,
                    rs.getString("label"), type, EmrFieldType.valueKind(type), nullToEmpty(rs.getString("meta")),
                    rs.getBoolean("required"), rs.getString("options_source"), rs.getString("options_master"),
                    rs.getBoolean("allow_free_text"), List.copyOf(options.getOrDefault(section + "/" + key, List.of())),
                    columns(rs.getString("columns")), min, max));
        });
        return result;
    }

    private List<TableColumn> columns(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(json);
            List<TableColumn> columns = new ArrayList<>();
            for (JsonNode column : node) {
                columns.add(new TableColumn(column.path("key").asText(), column.path("label").asText()));
            }
            return List.copyOf(columns);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored EMR column list could not be read", e);
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    @Override
    public List<StarterRow> starters() {
        return jdbc.sql(STARTERS).query(JdbcEmrLibraryRepository::starter).list();
    }

    @Override
    public Optional<StarterRow> starter(String starterKey) {
        return starters().stream().filter(row -> row.starterKey().equals(starterKey)).findFirst();
    }

    private static StarterRow starter(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StarterRow(rs.getString("starter_key"), rs.getString("name"), rs.getString("initials"),
                rs.getString("colour"), rs.getString("description"), rs.getBoolean("consent_flag"),
                rs.getBoolean("pcpndt_flag"), rs.getInt("position"), rs.getInt("current_layout_version"));
    }

    @Override
    public Optional<StarterLayoutRow> currentStarterLayout(String starterKey) {
        return jdbc.sql(CURRENT_LAYOUT).param("key", starterKey)
                .query((rs, row) -> new StarterLayoutRow(rs.getString("starter_key"), rs.getInt("layout_version"),
                        rs.getString("layout")))
                .optional();
    }

    @Override
    public Optional<StarterLayoutRow> starterLayout(String starterKey, int layoutVersion) {
        return jdbc.sql(LAYOUT).param("key", starterKey).param("version", layoutVersion)
                .query((rs, row) -> new StarterLayoutRow(rs.getString("starter_key"), rs.getInt("layout_version"),
                        rs.getString("layout")))
                .optional();
    }

    @Override
    public Optional<String> starterForAlias(String normalisedSpecialty) {
        return jdbc.sql(ALIAS).param("alias", normalisedSpecialty).query(String.class).optional();
    }
}
