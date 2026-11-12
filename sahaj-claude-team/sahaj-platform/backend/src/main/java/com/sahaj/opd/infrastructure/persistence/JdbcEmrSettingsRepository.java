package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The bound clinic's one {@code opd.emr_settings} row (SAH-44, ADR-019 D11). The first save inserts the row at version
 * 1, not 0: "version 0" then means only "never saved", so an owner who read version 0 before another owner's first
 * save is told apart and answered with 409 instead of overwriting. Every later save adds 1 and only matches the version
 * the caller read.
 */
@Repository
public class JdbcEmrSettingsRepository implements EmrSettingsRepository {

    private static final String FIND = """
            select section_panel, right_panel_first, version from opd.emr_settings where tenant_id = :tenantId
            """;
    private static final String INSERT = """
            insert into opd.emr_settings (tenant_id, section_panel, right_panel_first, version, created_by)
            values (:tenantId, :sectionPanel, :rightPanelFirst, 1, :actor)
            on conflict (tenant_id) do nothing
            """;
    private static final String UPDATE = """
            update opd.emr_settings
            set section_panel = :sectionPanel, right_panel_first = :rightPanelFirst,
                version = version + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and version = :expected
            """;

    private final JdbcClient jdbc;

    public JdbcEmrSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SettingsRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId)
                .query((rs, row) -> new SettingsRow(rs.getString("section_panel"), rs.getString("right_panel_first"),
                        rs.getLong("version")))
                .optional();
    }

    @Override
    public boolean insert(UUID tenantId, String sectionPanel, String rightPanelFirst, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("sectionPanel", sectionPanel)
                .param("rightPanelFirst", rightPanelFirst).param("actor", actorUserId).update() == 1;
    }

    @Override
    public boolean update(UUID tenantId, String sectionPanel, String rightPanelFirst, long expectedVersion,
                          UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("sectionPanel", sectionPanel)
                .param("rightPanelFirst", rightPanelFirst).param("expected", expectedVersion)
                .param("actor", actorUserId).update() == 1;
    }
}
