package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.NursingProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.nursing_profile}, {@code ipd.nursing_profile_template} and {@code ipd.ward.nursing_profile_key} (SAH-76,
 * docs/database/SAH-76.md section 1.3). The profile insert names the seven granted columns; the update names granted
 * columns only. Statements are fixed text; every value is a bind parameter. Profile names are shown on screen only.
 */
@Repository
public class JdbcNursingProfileRepository implements NursingProfileRepository {

    private static final String COLUMNS = """
            key, name, med_verification_required, vitals_every_min, dose_window_before_min, dose_window_after_min,
            sort_order
            """;
    private static final String HAS_PROFILES = """
            select exists (select 1 from ipd.nursing_profile where tenant_id = :tenantId)
            """;
    private static final String FIND_PROFILES = "select " + COLUMNS + """
            from ipd.nursing_profile where tenant_id = :tenantId order by sort_order, lower(name), key
            """;
    private static final String FIND_TEMPLATES = """
            select key, name, med_verification_required, vitals_every_min,
                   null::int as dose_window_before_min, null::int as dose_window_after_min, sort_order
            from ipd.nursing_profile_template order by sort_order, key
            """;
    private static final String FIND_PROFILE = "select " + COLUMNS + """
            from ipd.nursing_profile where tenant_id = :tenantId and key = :key
            """;
    private static final String OF_ADMISSIONS = """
            select a.id as admission_id, p.key, p.name, p.med_verification_required, p.vitals_every_min,
                   p.dose_window_before_min, p.dose_window_after_min, p.sort_order
            from ipd.admission a
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id
            join ipd.nursing_profile p on p.tenant_id = w.tenant_id and p.key = w.nursing_profile_key
            where a.tenant_id = :tenantId and a.id = any(cast(:ids as uuid[]))
            """;
    private static final String COPY_TEMPLATES = """
            insert into ipd.nursing_profile (key, name, med_verification_required, vitals_every_min, sort_order)
            select t.key, t.name, t.med_verification_required, t.vitals_every_min, t.sort_order
            from ipd.nursing_profile_template t
            """;
    private static final String NAME_TAKEN = """
            select exists (select 1 from ipd.nursing_profile
                           where tenant_id = :tenantId and lower(name) = lower(:name)
                             and (cast(:except as text) is null or key <> cast(:except as text)))
            """;
    private static final String KEY_TAKEN = """
            select exists (select 1 from ipd.nursing_profile where tenant_id = :tenantId and key = :key)
            """;
    private static final String INSERT = """
            insert into ipd.nursing_profile (key, name, med_verification_required, vitals_every_min,
                                             dose_window_before_min, dose_window_after_min, sort_order)
            values (:key, :name, :verification, :vitals, :before, :after, :sort)
            """;
    private static final String UPDATE = """
            update ipd.nursing_profile
            set name = :name, med_verification_required = :verification, vitals_every_min = :vitals,
                dose_window_before_min = :before, dose_window_after_min = :after,
                updated_at = :now, updated_by = :actor
            where tenant_id = :tenantId and key = :key
            """;
    private static final String WARD_PROFILES = """
            select id, name, nursing_profile_key from ipd.ward where tenant_id = :tenantId order by created_at, id
            """;
    private static final String WARD_COUNTS = """
            select nursing_profile_key, count(*) as n from ipd.ward
            where tenant_id = :tenantId and nursing_profile_key is not null group by nursing_profile_key
            """;
    private static final String SET_WARD_PROFILE = """
            update ipd.ward set nursing_profile_key = :key where tenant_id = :tenantId and id = :wardId
            """;

    private final JdbcClient jdbc;

    public JdbcNursingProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasProfiles(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_PROFILES).param("tenantId", tenantId).query(Boolean.class).single());
    }

    @Override
    public List<ProfileRow> findProfiles(UUID tenantId) {
        return jdbc.sql(FIND_PROFILES).param("tenantId", tenantId).query((rs, rowNum) -> row(rs)).list();
    }

    @Override
    public List<ProfileRow> findTemplates() {
        return jdbc.sql(FIND_TEMPLATES).query((rs, rowNum) -> row(rs)).list();
    }

    @Override
    public Optional<ProfileRow> findProfile(UUID tenantId, String key) {
        return jdbc.sql(FIND_PROFILE).param("tenantId", tenantId).param("key", key).query((rs, rowNum) -> row(rs))
                .optional();
    }

    @Override
    public Map<UUID, ProfileRow> findProfilesOfAdmissions(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, ProfileRow> found = new HashMap<>();
        if (admissionIds.isEmpty()) {
            return found;
        }
        jdbc.sql(OF_ADMISSIONS).param("tenantId", tenantId)
                .param("ids", JdbcMedicineRoundRepository.arrayLiteral(admissionIds))
                .query((rs, rowNum) -> {
                    found.put(rs.getObject("admission_id", UUID.class), row(rs));
                    return 1;
                }).list();
        return found;
    }

    @Override
    public void copyTemplates(UUID tenantId) {
        jdbc.sql(COPY_TEMPLATES).update();
    }

    @Override
    public boolean nameTaken(UUID tenantId, String name, String exceptKey) {
        return Boolean.TRUE.equals(jdbc.sql(NAME_TAKEN).param("tenantId", tenantId).param("name", name)
                .param("except", exceptKey, Types.VARCHAR).query(Boolean.class).single());
    }

    @Override
    public boolean keyTaken(UUID tenantId, String key) {
        return Boolean.TRUE.equals(jdbc.sql(KEY_TAKEN).param("tenantId", tenantId).param("key", key)
                .query(Boolean.class).single());
    }

    @Override
    public void insert(UUID tenantId, NewProfile profile) {
        jdbc.sql(INSERT).param("key", profile.key()).param("name", profile.name())
                .param("verification", profile.medVerificationRequired()).param("vitals", profile.vitalsEveryMinutes())
                .param("before", profile.doseWindowBeforeMin(), Types.INTEGER)
                .param("after", profile.doseWindowAfterMin(), Types.INTEGER).param("sort", profile.sortOrder())
                .update();
    }

    @Override
    public boolean update(UUID tenantId, String key, NewProfile profile, UUID actor, Instant now) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("key", key).param("name", profile.name())
                .param("verification", profile.medVerificationRequired()).param("vitals", profile.vitalsEveryMinutes())
                .param("before", profile.doseWindowBeforeMin(), Types.INTEGER)
                .param("after", profile.doseWindowAfterMin(), Types.INTEGER).param("now", now).param("actor", actor)
                .update() == 1;
    }

    @Override
    public List<WardProfile> findWardProfiles(UUID tenantId) {
        return jdbc.sql(WARD_PROFILES).param("tenantId", tenantId)
                .query((rs, rowNum) -> new WardProfile(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("nursing_profile_key")))
                .list();
    }

    @Override
    public Map<String, Integer> countWardsPerProfile(UUID tenantId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql(WARD_COUNTS).param("tenantId", tenantId).query((rs, rowNum) -> {
            counts.put(rs.getString("nursing_profile_key"), rs.getInt("n"));
            return 1;
        }).list();
        return counts;
    }

    @Override
    public boolean setWardProfile(UUID tenantId, UUID wardId, String profileKey) {
        return jdbc.sql(SET_WARD_PROFILE).param("tenantId", tenantId).param("wardId", wardId)
                .param("key", profileKey, Types.VARCHAR).update() == 1;
    }

    private static ProfileRow row(ResultSet rs) throws SQLException {
        return new ProfileRow(rs.getString("key"), rs.getString("name"), rs.getBoolean("med_verification_required"),
                rs.getInt("vitals_every_min"), (Integer) rs.getObject("dose_window_before_min"),
                (Integer) rs.getObject("dose_window_after_min"), rs.getInt("sort_order"));
    }
}
