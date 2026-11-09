package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.CardSetupRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The patient card setup tables (SAH-37). Inserts and updates list only the columns the database role may write: the
 * clinic, the id and the publish time come from the database. The setup text is cast to {@code jsonb} on the way in
 * and read back as text; it holds owner-typed labels and is never logged.
 */
@Repository
public class JdbcCardSetupRepository implements CardSetupRepository {

    private static final String FIND_LATEST = """
            select version, cast(setup as text) as setup, is_standard,
                   array_to_string(changed_parts, ',') as changed_parts, published_by, published_at
            from ipd.card_setup_version
            where tenant_id = :tenantId
            order by version desc
            limit 1
            """;
    private static final String FIND_HEADS = """
            select version, is_standard, array_to_string(changed_parts, ',') as changed_parts, published_by,
                   published_at
            from ipd.card_setup_version
            where tenant_id = :tenantId
            order by version desc
            limit :limit
            """;
    private static final String INSERT_VERSION = """
            insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, published_by)
            values (:version, cast(:setup as jsonb), :isStandard, cast(string_to_array(:parts, ',') as text[]), :by)
            """;
    private static final String FIND_DRAFT = """
            select cast(setup as text) as setup, base_version, revision, saved_by, saved_at
            from ipd.card_setup_draft
            where tenant_id = :tenantId
            """;
    private static final String INSERT_DRAFT = """
            insert into ipd.card_setup_draft (setup, base_version, revision, saved_by)
            values (cast(:setup as jsonb), :baseVersion, :revision, :by)
            """;
    private static final String UPDATE_DRAFT = """
            update ipd.card_setup_draft
            set setup = cast(:setup as jsonb), base_version = :baseVersion, revision = :revision, saved_by = :by,
                saved_at = :savedAt
            where tenant_id = :tenantId
            """;
    private static final String DELETE_DRAFT = "delete from ipd.card_setup_draft where tenant_id = :tenantId";

    private final JdbcClient jdbc;

    public JdbcCardSetupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VersionRow> findLatestVersion(UUID tenantId) {
        return jdbc.sql(FIND_LATEST).param("tenantId", tenantId)
                .query((rs, rowNum) -> new VersionRow(rs.getInt("version"), rs.getString("setup"),
                        rs.getBoolean("is_standard"), parts(rs.getString("changed_parts")),
                        rs.getObject("published_by", UUID.class),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public List<VersionHead> findVersionHeads(UUID tenantId, int limit) {
        return jdbc.sql(FIND_HEADS).param("tenantId", tenantId).param("limit", limit)
                .query((rs, rowNum) -> new VersionHead(rs.getInt("version"), rs.getBoolean("is_standard"),
                        parts(rs.getString("changed_parts")), rs.getObject("published_by", UUID.class),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public void insertVersion(UUID tenantId, int version, String setupJson, boolean isStandard,
                              List<String> changedParts, UUID publishedBy) {
        jdbc.sql(INSERT_VERSION).param("version", version).param("setup", setupJson).param("isStandard", isStandard)
                .param("parts", String.join(",", changedParts)).param("by", publishedBy).update();
    }

    @Override
    public Optional<DraftRow> findDraft(UUID tenantId) {
        return jdbc.sql(FIND_DRAFT).param("tenantId", tenantId)
                .query((rs, rowNum) -> new DraftRow(rs.getString("setup"), rs.getInt("base_version"),
                        rs.getLong("revision"), rs.getObject("saved_by", UUID.class),
                        rs.getObject("saved_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public void insertDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy) {
        jdbc.sql(INSERT_DRAFT).param("setup", setupJson).param("baseVersion", baseVersion)
                .param("revision", revision).param("by", savedBy).update();
    }

    @Override
    public void updateDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy,
                            Instant savedAt) {
        jdbc.sql(UPDATE_DRAFT).param("tenantId", tenantId).param("setup", setupJson)
                .param("baseVersion", baseVersion).param("revision", revision).param("by", savedBy)
                .param("savedAt", OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC)).update();
    }

    @Override
    public boolean deleteDraft(UUID tenantId) {
        return jdbc.sql(DELETE_DRAFT).param("tenantId", tenantId).update() > 0;
    }

    private static List<String> parts(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }
}
