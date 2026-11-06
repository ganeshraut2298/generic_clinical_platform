package com.sahaj.patient.infrastructure.persistence;

import com.sahaj.patient.application.PatientRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code patient.patient} (SAH-8). Row level security limits every statement to the bound clinic;
 * {@code tenant_id}, {@code id} and {@code uhid} are never written by the application - the table
 * fills them from {@code platform.current_tenant()}, {@code gen_random_uuid()} and
 * {@code patient.next_uhid()} - except {@code id}, which the application generates up front so it
 * can audit and re-read the new row with a single value. There is no delete statement anywhere
 * here: a merge is the {@code setMergedInto} update, never a row removal.
 */
@Repository
public class JdbcPatientRepository implements PatientRepository {

    private static final String SELECT = """
            select id, uhid, name, age, sex, mobile, area, language, allergy, chronic, abha_linked,
                   merged_into_id, created_at, version
            from patient.patient
            where tenant_id = :tenantId
            """;
    private static final String FIND_BY_ID = SELECT + " and id = :id";
    private static final String FIND_BY_IDS = SELECT + " and id in (:ids)";
    private static final String FIND_FAMILY = SELECT
            + " and mobile = :mobile and merged_into_id is null and id <> :excludeId order by created_at";

    private static final String SEARCH_CONDITION = """
             and merged_into_id is null
             and (cast(:pattern as text) is null
                  or lower(name) like :pattern escape '\\'
                  or mobile like :pattern escape '\\'
                  or lower(uhid) like :pattern escape '\\')
            """;
    private static final String COUNT = "select count(*) from patient.patient where tenant_id = :tenantId";

    private static final String INSERT = """
            insert into patient.patient (id, name, age, sex, mobile, area, language, allergy, chronic,
                                          abha_linked, created_by)
            values (:id, :name, :age, :sex, :mobile, :area, :language, :allergy, :chronic, :abhaLinked, :actor)
            """;
    private static final String UPDATE = """
            update patient.patient
            set name = :name, age = :age, sex = :sex, area = :area, language = :language, allergy = :allergy,
                chronic = :chronic, abha_linked = :abhaLinked, updated_by = :actor, updated_at = now(),
                version = version + 1
            where id = :id
            """;
    private static final String SET_MERGED_INTO = """
            update patient.patient
            set merged_into_id = :keepId, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcPatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PatientPage search(UUID tenantId, String q, String filter, int limit, int offset) {
        String filterCondition = filterCondition(filter);
        String pattern = SearchPattern.contains(q);
        String searchSql = SELECT + filterCondition + SEARCH_CONDITION + " order by lower(name), id limit :limit offset :offset";
        String countSql = COUNT + filterCondition + SEARCH_CONDITION;

        List<PatientRow> items = jdbc.sql(searchSql)
                .param("tenantId", tenantId).param("pattern", pattern, Types.VARCHAR)
                .param("limit", limit).param("offset", offset)
                .query(JdbcPatientRepository::map).list();
        long total = jdbc.sql(countSql)
                .param("tenantId", tenantId).param("pattern", pattern, Types.VARCHAR)
                .query(Long.class).single();
        return new PatientPage(items, total);
    }

    @Override
    public Optional<PatientRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcPatientRepository::map).optional();
    }

    @Override
    public List<PatientRow> findByIds(UUID tenantId, Collection<UUID> ids) {
        return jdbc.sql(FIND_BY_IDS).param("tenantId", tenantId).param("ids", ids)
                .query(JdbcPatientRepository::map).list();
    }

    @Override
    public List<PatientRow> findFamily(UUID tenantId, String mobile, UUID excludeId) {
        return jdbc.sql(FIND_FAMILY).param("tenantId", tenantId).param("mobile", mobile)
                .param("excludeId", excludeId).query(JdbcPatientRepository::map).list();
    }

    @Override
    public void insert(UUID id, String name, int age, String sex, String mobile, String area, String language,
                        String allergy, String chronic, boolean abhaLinked, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id).param("name", name).param("age", age).param("sex", sex).param("mobile", mobile)
                .param("area", area).param("language", language).param("allergy", allergy)
                .param("chronic", chronic).param("abhaLinked", abhaLinked).param("actor", actorUserId)
                .update();
    }

    @Override
    public void update(UUID id, String name, int age, String sex, String area, String language, String allergy,
                        String chronic, boolean abhaLinked, UUID actorUserId) {
        jdbc.sql(UPDATE)
                .param("id", id).param("name", name).param("age", age).param("sex", sex).param("area", area)
                .param("language", language).param("allergy", allergy).param("chronic", chronic)
                .param("abhaLinked", abhaLinked).param("actor", actorUserId)
                .update();
    }

    @Override
    public void setMergedInto(UUID id, UUID keepId, UUID actorUserId) {
        jdbc.sql(SET_MERGED_INTO).param("id", id).param("keepId", keepId).param("actor", actorUserId).update();
    }

    /** Only {@code all}, {@code new}, {@code chronic}, {@code kids} and {@code seniors} reach a query. */
    private static String filterCondition(String filter) {
        return switch (filter) {
            case "new" -> " and created_at >= date_trunc('month', now())";
            case "chronic" -> " and chronic is not null and btrim(chronic) <> ''";
            case "kids" -> " and age < 18";
            case "seniors" -> " and age >= 60";
            default -> "";
        };
    }

    private static PatientRow map(ResultSet rs, int rowNum) throws SQLException {
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Object mergedIntoId = rs.getObject("merged_into_id");
        return new PatientRow(rs.getObject("id", UUID.class), rs.getString("uhid"), rs.getString("name"),
                rs.getInt("age"), rs.getString("sex"), rs.getString("mobile"), rs.getString("area"),
                rs.getString("language"), rs.getString("allergy"), rs.getString("chronic"),
                rs.getBoolean("abha_linked"), mergedIntoId == null ? null : (UUID) mergedIntoId, createdAt,
                rs.getLong("version"));
    }
}
