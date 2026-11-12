package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrSetupRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * This clinic's EMR template overrides and the revision counter per template (SAH-44, ADR-019 D5). Row level security
 * limits every statement to the bound clinic; the tenant id is also in each statement, so a wrong binding matches
 * nothing. The revision change is a single compare-and-set statement: when two owners change the same template at the
 * same moment, the second statement waits for the first commit and then matches no row.
 */
@Repository
public class JdbcEmrSetupRepository implements EmrSetupRepository {

    private static final String REVISION = """
            select revision from opd.emr_template_setup where tenant_id = :tenantId and template_key = :key
            """;
    private static final String FIRST_REVISION = """
            insert into opd.emr_template_setup (tenant_id, template_key, revision, created_by)
            values (:tenantId, :key, 1, :actor)
            on conflict (tenant_id, template_key) do nothing
            """;
    private static final String NEXT_REVISION = """
            update opd.emr_template_setup
            set revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and template_key = :key and revision = :expected
            """;
    private static final String ALL_OVERRIDES = """
            select template_key, section_key, shown, required
            from opd.emr_template_override where tenant_id = :tenantId
            order by template_key, section_key
            """;
    private static final String OVERRIDES = """
            select section_key, shown, required
            from opd.emr_template_override where tenant_id = :tenantId and template_key = :key
            order by section_key
            """;
    private static final String SAVE_OVERRIDE = """
            insert into opd.emr_template_override (tenant_id, template_key, section_key, shown, required, created_by)
            values (:tenantId, :key, :section, :shown, :required, :actor)
            on conflict (tenant_id, template_key, section_key)
            do update set shown = excluded.shown, required = excluded.required,
                          updated_at = now(), updated_by = :actor
            """;
    private static final String DELETE_OVERRIDE = """
            delete from opd.emr_template_override
            where tenant_id = :tenantId and template_key = :key and section_key = :section
            """;
    private static final String DELETE_OVERRIDES = """
            delete from opd.emr_template_override where tenant_id = :tenantId and template_key = :key
            """;

    private final JdbcClient jdbc;

    public JdbcEmrSetupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long revision(UUID tenantId, String templateKey) {
        return jdbc.sql(REVISION).param("tenantId", tenantId).param("key", templateKey)
                .query(Long.class).optional().orElse(0L);
    }

    @Override
    public Map<String, List<OverrideRow>> allOverrides(UUID tenantId) {
        Map<String, List<OverrideRow>> byTemplate = new LinkedHashMap<>();
        jdbc.sql(ALL_OVERRIDES).param("tenantId", tenantId).query(rs -> {
            byTemplate.computeIfAbsent(rs.getString("template_key"), key -> new ArrayList<>())
                    .add(new OverrideRow(rs.getString("section_key"), rs.getBoolean("shown"),
                            rs.getBoolean("required")));
        });
        return byTemplate;
    }

    @Override
    public List<OverrideRow> overrides(UUID tenantId, String templateKey) {
        return jdbc.sql(OVERRIDES).param("tenantId", tenantId).param("key", templateKey)
                .query((rs, row) -> new OverrideRow(rs.getString("section_key"), rs.getBoolean("shown"),
                        rs.getBoolean("required")))
                .list();
    }

    @Override
    public boolean bumpRevision(UUID tenantId, String templateKey, long expected, UUID actorUserId) {
        if (expected == 0) {
            return jdbc.sql(FIRST_REVISION).param("tenantId", tenantId).param("key", templateKey)
                    .param("actor", actorUserId).update() == 1;
        }
        return jdbc.sql(NEXT_REVISION).param("tenantId", tenantId).param("key", templateKey)
                .param("expected", expected).param("actor", actorUserId).update() == 1;
    }

    @Override
    public void saveOverride(UUID tenantId, String templateKey, String sectionKey, boolean shown, boolean required,
                             UUID actorUserId) {
        jdbc.sql(SAVE_OVERRIDE).param("tenantId", tenantId).param("key", templateKey).param("section", sectionKey)
                .param("shown", shown).param("required", required).param("actor", actorUserId).update();
    }

    @Override
    public void deleteOverride(UUID tenantId, String templateKey, String sectionKey) {
        jdbc.sql(DELETE_OVERRIDE).param("tenantId", tenantId).param("key", templateKey)
                .param("section", sectionKey).update();
    }

    @Override
    public int deleteOverrides(UUID tenantId, String templateKey) {
        return jdbc.sql(DELETE_OVERRIDES).param("tenantId", tenantId).param("key", templateKey).update();
    }
}
