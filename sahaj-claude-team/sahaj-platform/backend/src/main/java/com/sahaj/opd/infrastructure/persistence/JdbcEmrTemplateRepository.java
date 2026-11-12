package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.EmrTemplateRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global, read-only EMR template tables and the specialty alias list (SAH-44, ADR-019 D4, D6). The same rows for
 * every clinic; the application role can only select. The newest version of a key is the highest version number.
 */
@Repository
public class JdbcEmrTemplateRepository implements EmrTemplateRepository {

    private static final String NEWEST = """
            select t.template_key, t.version, t.name
            from opd.emr_template t
            where t.version = (select max(x.version) from opd.emr_template x where x.template_key = t.template_key)
            """;
    private static final String NEWEST_ALL = NEWEST + """
            order by array_position(array['gp', 'dental', 'paeds', 'homeo'], t.template_key)
            """;
    private static final String NEWEST_ONE = NEWEST + " and t.template_key = :key";
    private static final String SECTIONS = """
            select section_key, position, label, part, required_default, hideable, editor_type, icon_key,
                   options_source, options_master
            from opd.emr_template_section
            where template_key = :key and version = :version
            order by (part = 'TREATMENT'), position
            """;
    private static final String OPTIONS = """
            select section_key, option_key, position, label, style, is_none
            from opd.emr_template_option
            where template_key = :key and version = :version
            order by section_key, position
            """;
    private static final String ALIAS = """
            select template_key from opd.emr_specialty_alias where alias_normalised = :alias
            """;
    private static final String COUNTS = """
            select 'complaints' as master, count(*) as total from opd.emr_complaint
            union all select 'diagnoses', count(*) from opd.diagnosis
            union all select 'medicines', count(*) from opd.medicine
            union all select 'investigations', count(*) from opd.emr_investigation
            union all select 'advice', count(*) from opd.emr_advice
            """;

    private final JdbcClient jdbc;

    public JdbcEmrTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TemplateHeader> newestTemplates() {
        return jdbc.sql(NEWEST_ALL)
                .query((rs, row) -> new TemplateHeader(rs.getString("template_key"), rs.getInt("version"),
                        rs.getString("name")))
                .list();
    }

    @Override
    public Optional<TemplateHeader> newest(String templateKey) {
        return jdbc.sql(NEWEST_ONE).param("key", templateKey)
                .query((rs, row) -> new TemplateHeader(rs.getString("template_key"), rs.getInt("version"),
                        rs.getString("name")))
                .optional();
    }

    @Override
    public List<SectionRow> sections(String templateKey, int version) {
        return jdbc.sql(SECTIONS).param("key", templateKey).param("version", version)
                .query((rs, row) -> new SectionRow(rs.getString("section_key"), rs.getInt("position"),
                        rs.getString("label"), rs.getString("part"), rs.getBoolean("required_default"),
                        rs.getBoolean("hideable"), rs.getString("editor_type"), rs.getString("icon_key"),
                        rs.getString("options_source"), rs.getString("options_master")))
                .list();
    }

    @Override
    public List<OptionRow> options(String templateKey, int version) {
        return jdbc.sql(OPTIONS).param("key", templateKey).param("version", version)
                .query((rs, row) -> new OptionRow(rs.getString("section_key"), rs.getString("option_key"),
                        rs.getInt("position"), rs.getString("label"), rs.getString("style"),
                        rs.getBoolean("is_none")))
                .list();
    }

    @Override
    public Optional<String> templateKeyForAlias(String normalisedSpecialty) {
        return jdbc.sql(ALIAS).param("alias", normalisedSpecialty).query(String.class).optional();
    }

    @Override
    public Map<EmrMaster, Long> masterCounts() {
        Map<EmrMaster, Long> counts = new EnumMap<>(EmrMaster.class);
        jdbc.sql(COUNTS).query(rs -> {
            long total = rs.getLong("total");
            EmrMaster.fromKey(rs.getString("master")).ifPresent(master -> counts.put(master, total));
        });
        return counts;
    }
}
