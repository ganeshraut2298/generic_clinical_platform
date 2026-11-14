package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.EmrDepartmentRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The clinic's EMR departments, immutable versions and department doctors (SAH-53, SAH-45, SAH-54, ADR-020 D5, D6).
 * Row level security limits every statement to the bound clinic and the tenant id is also in each statement. The
 * revision changes only by compare-and-set, so two owners saving at once give one winner. Versions are insert-only.
 */
@Repository
public class JdbcEmrDepartmentRepository implements EmrDepartmentRepository {

    private static final String DEPARTMENT_COLUMNS = """
            id, department_key, name, initials, colour, starter_key, source_kind, copied_from_key, room_labels,
            draft_layout::text as draft_layout, published_version, revision
            """;
    private static final String FIND_ALL = "select " + DEPARTMENT_COLUMNS
            + " from opd.emr_department where tenant_id = :tenantId order by name";
    private static final String FIND = "select " + DEPARTMENT_COLUMNS
            + " from opd.emr_department where tenant_id = :tenantId and department_key = :key";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String COUNT_ADDED = """
            select count(*) from opd.emr_department
            where tenant_id = :tenantId and not (department_key = any (string_to_array(:starters, ',')))
            """;
    private static final String NAME_TAKEN = """
            select exists (select 1 from opd.emr_department where tenant_id = :tenantId and lower(name) = lower(:name))
            """;
    private static final String INSERT = """
            insert into opd.emr_department (id, tenant_id, department_key, name, initials, colour, starter_key,
                                            source_kind, copied_from_key, room_labels, draft_layout, draft_saved_at,
                                            draft_saved_by, published_version, revision, created_by)
            values (:id, :tenantId, :key, :name, :initials, :colour, :starterKey, :sourceKind, :copiedFrom,
                    coalesce(array(select jsonb_array_elements_text(cast(:rooms as jsonb))), '{}'),
                    cast(:draft as jsonb), case when cast(:draft as text) is null then null else now() end,
                    case when cast(:draft as text) is null then null else cast(:actor as uuid) end, 0, :revision,
                    :actor)
            """;
    private static final String INSERT_IF_ABSENT = INSERT + " on conflict do nothing";
    private static final String SAVE_DRAFT = """
            update opd.emr_department
            set draft_layout = cast(:draft as jsonb), draft_saved_at = now(), draft_saved_by = :actor,
                revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected
            """;
    private static final String MARK_PUBLISHED = """
            update opd.emr_department
            set published_version = :versionNo, draft_layout = null, draft_saved_at = null, draft_saved_by = null,
                revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected
            """;
    private static final String INSERT_VERSION = """
            insert into opd.emr_department_version (id, tenant_id, department_id, version_no, layout, published_by)
            values (:id, :tenantId, :departmentId, :versionNo, cast(:layout as jsonb), :actor)
            """;
    private static final String VERSION_COLUMNS = "id, department_id, version_no, layout::text as layout,"
            + " published_at, published_by";
    private static final String LATEST_VERSION = "select " + VERSION_COLUMNS
            + " from opd.emr_department_version where tenant_id = :tenantId and department_id = :departmentId"
            + " order by version_no desc limit 1";
    private static final String VERSION = "select " + VERSION_COLUMNS
            + " from opd.emr_department_version where tenant_id = :tenantId and department_id = :departmentId"
            + " and version_no = :versionNo";
    private static final String VERSION_BY_ID = "select " + VERSION_COLUMNS
            + " from opd.emr_department_version where tenant_id = :tenantId and id = :id";
    private static final String LATEST_VERSIONS = "select distinct on (department_id) " + VERSION_COLUMNS
            + " from opd.emr_department_version where tenant_id = :tenantId order by department_id, version_no desc";
    private static final String INSERT_DOCTOR = """
            insert into opd.emr_department_doctor (tenant_id, department_id, doctor_membership_id, created_by)
            values (:tenantId, :departmentId, :doctor, :actor)
            """;
    private static final String DOCTORS = """
            select department_id, doctor_membership_id from opd.emr_department_doctor where tenant_id = :tenantId
            """;
    private static final String PUBLISHED_FOR_DOCTOR = """
            select d.id, d.department_key, d.name, d.initials, d.colour, d.starter_key, d.source_kind,
                   d.copied_from_key, d.room_labels, d.draft_layout::text as draft_layout, d.published_version,
                   d.revision
            from opd.emr_department d join opd.emr_department_doctor x
              on x.tenant_id = d.tenant_id and x.department_id = d.id
            where d.tenant_id = :tenantId and x.doctor_membership_id = :doctor and d.published_version > 0
            order by d.name
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcEmrDepartmentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<DepartmentRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).query(JdbcEmrDepartmentRepository::department).list();
    }

    @Override
    public Optional<DepartmentRow> find(UUID tenantId, String departmentKey) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("key", departmentKey)
                .query(JdbcEmrDepartmentRepository::department).optional();
    }

    @Override
    public Optional<DepartmentRow> findForUpdate(UUID tenantId, String departmentKey) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("key", departmentKey)
                .query(JdbcEmrDepartmentRepository::department).optional();
    }

    @Override
    public int countAdded(UUID tenantId, List<String> starterKeys) {
        return jdbc.sql(COUNT_ADDED).param("tenantId", tenantId).param("starters", String.join(",", starterKeys))
                .query(Integer.class).single();
    }

    @Override
    public boolean nameTaken(UUID tenantId, String name) {
        return jdbc.sql(NAME_TAKEN).param("tenantId", tenantId).param("name", name).query(Boolean.class).single();
    }

    @Override
    public UUID insert(UUID tenantId, NewDepartment d, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT).param("id", id).param("tenantId", tenantId).param("key", d.key()).param("name", d.name())
                .param("initials", d.initials()).param("colour", d.colour()).param("starterKey", d.starterKey())
                .param("sourceKind", d.sourceKind()).param("copiedFrom", d.copiedFromKey())
                .param("rooms", json(d.rooms())).param("draft", d.draftLayout()).param("revision", d.revision())
                .param("actor", actorUserId).update();
        return id;
    }

    @Override
    public Optional<UUID> insertIfAbsent(UUID tenantId, NewDepartment d, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql(INSERT_IF_ABSENT).param("id", id).param("tenantId", tenantId).param("key", d.key())
                .param("name", d.name()).param("initials", d.initials()).param("colour", d.colour())
                .param("starterKey", d.starterKey()).param("sourceKind", d.sourceKind())
                .param("copiedFrom", d.copiedFromKey()).param("rooms", json(d.rooms()))
                .param("draft", d.draftLayout()).param("revision", d.revision()).param("actor", actorUserId).update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    @Override
    public boolean saveDraft(UUID tenantId, UUID departmentId, long expectedRevision, String draftLayout,
                             UUID actorUserId) {
        return jdbc.sql(SAVE_DRAFT).param("tenantId", tenantId).param("id", departmentId)
                .param("expected", expectedRevision).param("draft", draftLayout).param("actor", actorUserId)
                .update() == 1;
    }

    @Override
    public boolean markPublished(UUID tenantId, UUID departmentId, long expectedRevision, int versionNo,
                                 UUID actorUserId) {
        return jdbc.sql(MARK_PUBLISHED).param("tenantId", tenantId).param("id", departmentId)
                .param("expected", expectedRevision).param("versionNo", versionNo).param("actor", actorUserId)
                .update() == 1;
    }

    @Override
    public UUID insertVersion(UUID tenantId, UUID departmentId, int versionNo, String layout, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_VERSION).param("id", id).param("tenantId", tenantId).param("departmentId", departmentId)
                .param("versionNo", versionNo).param("layout", layout).param("actor", actorUserId).update();
        return id;
    }

    @Override
    public Optional<VersionRow> latestVersion(UUID tenantId, UUID departmentId) {
        return jdbc.sql(LATEST_VERSION).param("tenantId", tenantId).param("departmentId", departmentId)
                .query(JdbcEmrDepartmentRepository::version).optional();
    }

    @Override
    public Optional<VersionRow> version(UUID tenantId, UUID departmentId, int versionNo) {
        return jdbc.sql(VERSION).param("tenantId", tenantId).param("departmentId", departmentId)
                .param("versionNo", versionNo).query(JdbcEmrDepartmentRepository::version).optional();
    }

    @Override
    public Optional<VersionRow> versionById(UUID tenantId, UUID versionId) {
        return jdbc.sql(VERSION_BY_ID).param("tenantId", tenantId).param("id", versionId)
                .query(JdbcEmrDepartmentRepository::version).optional();
    }

    @Override
    public Map<UUID, VersionRow> latestVersions(UUID tenantId) {
        Map<UUID, VersionRow> result = new HashMap<>();
        for (VersionRow row : jdbc.sql(LATEST_VERSIONS).param("tenantId", tenantId)
                .query(JdbcEmrDepartmentRepository::version).list()) {
            result.put(row.departmentId(), row);
        }
        return result;
    }

    @Override
    public void insertDoctor(UUID tenantId, UUID departmentId, UUID doctorMembershipId, UUID actorUserId) {
        jdbc.sql(INSERT_DOCTOR).param("tenantId", tenantId).param("departmentId", departmentId)
                .param("doctor", doctorMembershipId).param("actor", actorUserId).update();
    }

    @Override
    public Map<UUID, Set<UUID>> doctorsByDepartment(UUID tenantId) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        jdbc.sql(DOCTORS).param("tenantId", tenantId).query(rs -> {
            result.computeIfAbsent(rs.getObject("department_id", UUID.class), k -> new HashSet<>())
                    .add(rs.getObject("doctor_membership_id", UUID.class));
        });
        return result;
    }

    @Override
    public List<DepartmentRow> publishedForDoctor(UUID tenantId, UUID doctorMembershipId) {
        return jdbc.sql(PUBLISHED_FOR_DOCTOR).param("tenantId", tenantId).param("doctor", doctorMembershipId)
                .query(JdbcEmrDepartmentRepository::department).list();
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Room labels could not be written", e);
        }
    }

    private static DepartmentRow department(ResultSet rs, int row) throws SQLException {
        List<String> rooms = new ArrayList<>();
        Array array = rs.getArray("room_labels");
        if (array != null) {
            for (Object room : (Object[]) array.getArray()) {
                rooms.add((String) room);
            }
        }
        return new DepartmentRow(rs.getObject("id", UUID.class), rs.getString("department_key"), rs.getString("name"),
                rs.getString("initials"), rs.getString("colour"), rs.getString("starter_key"),
                rs.getString("source_kind"), rs.getString("copied_from_key"), List.copyOf(rooms),
                rs.getString("draft_layout"), rs.getInt("published_version"), rs.getLong("revision"));
    }

    private static VersionRow version(ResultSet rs, int row) throws SQLException {
        return new VersionRow(rs.getObject("id", UUID.class), rs.getObject("department_id", UUID.class),
                rs.getInt("version_no"), rs.getString("layout"), rs.getTimestamp("published_at").toInstant(),
                rs.getObject("published_by", UUID.class));
    }
}
