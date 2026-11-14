package com.sahaj.patient.infrastructure.persistence;

import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.application.PatientRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
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

    /** The "Children" chip: under 13 (mockup ptMatch, SAH-73-3). */
    static final int KIDS_UNDER_YEARS = 13;
    /** The "60+" chip. */
    static final int SENIORS_FROM_YEARS = 60;

    private static final String SELECT = """
            select id, uhid, name, age, date_of_birth, sex, mobile, area, language, allergy, chronic, abha_linked,
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
            insert into patient.patient (id, name, age, date_of_birth, sex, mobile, area, language, allergy, chronic,
                                          abha_linked, created_by)
            values (:id, :name, :age, :dateOfBirth, :sex, :mobile, :area, :language, :allergy, :chronic,
                    :abhaLinked, :actor)
            """;
    private static final String FIND_BY_ID_FOR_UPDATE = FIND_BY_ID + " for update";
    /** SAH-68-3: a front-desk change of the allergy text clears who recorded it from the EMR (ADR-025 D13). */
    private static final String UPDATE = """
            update patient.patient
            set name = :name, age = :age, date_of_birth = :dateOfBirth, sex = :sex, area = :area,
                language = :language, allergy = :allergy,
                allergy_recorded_at = case when allergy is distinct from cast(:allergy as text) then null
                                           else allergy_recorded_at end,
                allergy_recorded_by = case when allergy is distinct from cast(:allergy as text) then null
                                           else allergy_recorded_by end,
                chronic = :chronic, abha_linked = :abhaLinked, updated_by = :actor, updated_at = now(),
                version = version + 1
            where id = :id
            """;
    private static final String UPDATE_FACTS = """
            update patient.patient
            set allergy = coalesce(cast(:allergy as text), allergy), chronic = coalesce(cast(:chronic as text), chronic),
                allergy_recorded_at = case when cast(:allergy as text) is null then allergy_recorded_at else now() end,
                allergy_recorded_by = case when cast(:allergy as text) is null then allergy_recorded_by else :actor end,
                updated_by = :actor, updated_at = now(), version = version + 1
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
    public PatientPage search(UUID tenantId, String q, String filter, int limit, int offset, LocalDate today) {
        String filterCondition = filterCondition(filter);
        String pattern = SearchPattern.contains(q);
        String searchSql = SELECT + filterCondition + SEARCH_CONDITION + " order by lower(name), id limit :limit offset :offset";
        String countSql = COUNT + filterCondition + SEARCH_CONDITION;
        // ADR-024 D1: the chips are date bounds worked out in Java (India), never a function of the column.
        LocalDate kidsBound = PatientAge.yearsBound(today, KIDS_UNDER_YEARS);
        LocalDate seniorsBound = PatientAge.yearsBound(today, SENIORS_FROM_YEARS);

        List<PatientRow> items = jdbc.sql(searchSql)
                .param("tenantId", tenantId).param("pattern", pattern, Types.VARCHAR)
                .param("kidsBound", kidsBound).param("seniorsBound", seniorsBound)
                .param("limit", limit).param("offset", offset)
                .query(JdbcPatientRepository::map).list();
        long total = jdbc.sql(countSql)
                .param("tenantId", tenantId).param("pattern", pattern, Types.VARCHAR)
                .param("kidsBound", kidsBound).param("seniorsBound", seniorsBound)
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
    public void insert(UUID id, String name, int age, LocalDate dateOfBirth, String sex, String mobile, String area,
                        String language, String allergy, String chronic, boolean abhaLinked, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id).param("name", name).param("age", age).param("dateOfBirth", dateOfBirth, Types.DATE)
                .param("sex", sex).param("mobile", mobile)
                .param("area", area).param("language", language).param("allergy", allergy)
                .param("chronic", chronic).param("abhaLinked", abhaLinked).param("actor", actorUserId)
                .update();
    }

    @Override
    public void update(UUID id, String name, int age, LocalDate dateOfBirth, String sex, String area,
                        String language, String allergy, String chronic, boolean abhaLinked, UUID actorUserId) {
        jdbc.sql(UPDATE)
                .param("id", id).param("name", name).param("age", age).param("dateOfBirth", dateOfBirth, Types.DATE)
                .param("sex", sex).param("area", area)
                .param("language", language).param("allergy", allergy).param("chronic", chronic)
                .param("abhaLinked", abhaLinked).param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<PatientRow> findByIdForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID_FOR_UPDATE).param("tenantId", tenantId).param("id", id)
                .query(JdbcPatientRepository::map).optional();
    }

    @Override
    public void updateClinicalFacts(UUID id, String allergy, String chronic, UUID actorUserId) {
        jdbc.sql(UPDATE_FACTS).param("id", id).param("allergy", allergy, Types.VARCHAR)
                .param("chronic", chronic, Types.VARCHAR).param("actor", actorUserId).update();
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
            // SAH-73-3 (mockup ptMatch: age < 13): dated patients by the date of birth, the others by the stored age.
            case "kids" -> " and ((date_of_birth is null and age < " + KIDS_UNDER_YEARS
                    + ") or date_of_birth > :kidsBound)";
            case "seniors" -> " and ((date_of_birth is null and age >= " + SENIORS_FROM_YEARS
                    + ") or date_of_birth <= :seniorsBound)";
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
                rs.getLong("version"), rs.getObject("date_of_birth", LocalDate.class));
    }
}
