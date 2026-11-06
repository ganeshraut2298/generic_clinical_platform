package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.PrivacySettingsRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.privacy_settings}, one row per clinic, created on the first save (SAH-17). No delete statement. */
@Repository
public class JdbcPrivacySettingsRepository implements PrivacySettingsRepository {

    private static final String FIND = """
            select consent_required, promo_consent, record_copy, hfr_id
            from platform.privacy_settings
            where tenant_id = :tenantId
            """;
    private static final String UPSERT = """
            insert into platform.privacy_settings
                (tenant_id, consent_required, promo_consent, record_copy, hfr_id, updated_at, updated_by)
            values
                (:tenantId, :consentRequired, :promoConsent, :recordCopy, :hfrId, now(), :actor)
            on conflict (tenant_id) do update set
                consent_required = excluded.consent_required,
                promo_consent = excluded.promo_consent,
                record_copy = excluded.record_copy,
                hfr_id = excluded.hfr_id,
                updated_at = now(),
                updated_by = excluded.updated_by,
                version = platform.privacy_settings.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcPrivacySettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PrivacyRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(JdbcPrivacySettingsRepository::map).optional();
    }

    @Override
    public void upsert(UUID tenantId, PrivacyRow row, UUID actorUserId) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("consentRequired", row.consentRequired())
                .param("promoConsent", row.promoConsent())
                .param("recordCopy", row.recordCopy())
                .param("hfrId", row.hfrId(), Types.VARCHAR)
                .param("actor", actorUserId)
                .update();
    }

    private static PrivacyRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PrivacyRow(rs.getBoolean("consent_required"), rs.getBoolean("promo_consent"),
                rs.getBoolean("record_copy"), rs.getString("hfr_id"));
    }
}
