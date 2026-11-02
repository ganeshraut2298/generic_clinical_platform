package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.UserPreferenceRepository;
import com.sahaj.platform.domain.UserPreference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.user_preference}: one row per (tenant, user) (SAH-5 technical design). Row
 * level security scopes it to the bound tenant; {@code user_id} further narrows it to the
 * signed-in person's own row, the same shape as {@code platform.membership}.
 */
@Repository
public class JdbcUserPreferenceRepository implements UserPreferenceRepository {

    private static final String FIND = """
            select language, theme, menu_mode, sidebar_collapsed
            from platform.user_preference where tenant_id = :tenantId and user_id = :userId
            """;
    private static final String UPSERT = """
            insert into platform.user_preference (tenant_id, user_id, language, theme, menu_mode, sidebar_collapsed)
            values (:tenantId, :userId, :language, :theme, :menuMode, :sidebarCollapsed)
            on conflict (tenant_id, user_id) do update set
                language = excluded.language,
                theme = excluded.theme,
                menu_mode = excluded.menu_mode,
                sidebar_collapsed = excluded.sidebar_collapsed
            """;

    private final JdbcClient jdbc;

    public JdbcUserPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserPreference> find(UUID tenantId, UUID userId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("userId", userId)
                .query(JdbcUserPreferenceRepository::map).optional();
    }

    @Override
    public void upsert(UUID tenantId, UUID userId, UserPreference preference) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("language", preference.language())
                .param("theme", preference.theme())
                .param("menuMode", preference.menuMode())
                .param("sidebarCollapsed", preference.sidebarCollapsed())
                .update();
    }

    private static UserPreference map(ResultSet rs, int rowNum) throws SQLException {
        return new UserPreference(rs.getString("language"), rs.getString("theme"),
                rs.getString("menu_mode"), rs.getBoolean("sidebar_collapsed"));
    }
}
