package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Treatment courses, their sittings and the clinic's course counter (SAH-58; ADR-022 D12). Row level security scopes
 * every statement to the bound clinic and the tenant id is also in each statement. Nothing is deleted; the grants allow
 * only the columns named here to change. Items are written from a JSON array so no text needs array-literal escaping.
 */
@Repository
public class JdbcTreatmentCourseRepository implements TreatmentCourseRepository {

    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(:key, 0))";
    private static final String NEXT_COURSE_NO = """
            insert into opd.treatment_course_counter (tenant_id, last_no) values (:tenantId, 1)
            on conflict (tenant_id) do update set last_no = treatment_course_counter.last_no + 1
            returning last_no
            """;
    private static final String COURSE_COLUMNS = """
            id, course_no, patient_id, kind, unit, name, note, doctor_membership_id, started_on, status, revision,
            request_hash, created_at, origin_record_id
            """;
    private static final String INSERT_COURSE = """
            insert into opd.treatment_course (id, tenant_id, course_no, patient_id, kind, unit, name, note,
                                              doctor_membership_id, started_on, origin_record_id,
                                              origin_record_created_at, idempotency_key, request_hash, revision,
                                              created_by)
            values (:id, :tenantId, :courseNo, :patientId, :kind, :unit, :name, :note, :doctor, :startedOn,
                    :originId, :originCreatedAt, :idempotencyKey, :requestHash, 0, :actor)
            """;
    private static final String FIND = "select " + COURSE_COLUMNS
            + " from opd.treatment_course where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_BY_KEY = "select " + COURSE_COLUMNS
            + " from opd.treatment_course where tenant_id = :tenantId and idempotency_key = :key";
    private static final String FOR_PATIENT = "select " + COURSE_COLUMNS
            + " from opd.treatment_course where tenant_id = :tenantId and patient_id = :patientId"
            + " order by created_at desc, course_no desc limit :limit";
    private static final String SITTING_COLUMNS = """
            id, course_id, sitting_no, encounter_kind, add_option_key, planned_date, items, charge_paise, note,
            opd_visit_id, ipd_admission_id, admission_requested, done_at, request_hash
            """;
    private static final String SITTINGS_OF = "select " + SITTING_COLUMNS
            + " from opd.treatment_course_sitting where tenant_id = :tenantId and course_id in (:courseIds)"
            + " order by course_id, sitting_no";
    private static final String SITTING = "select " + SITTING_COLUMNS
            + " from opd.treatment_course_sitting where tenant_id = :tenantId and id = :id";
    private static final String SITTING_FOR_UPDATE = SITTING + " for update";
    private static final String SITTING_BY_KEY = "select " + SITTING_COLUMNS
            + " from opd.treatment_course_sitting where tenant_id = :tenantId and idempotency_key = :key";
    private static final String SITTING_BY_VISIT = "select " + SITTING_COLUMNS
            + " from opd.treatment_course_sitting where tenant_id = :tenantId and opd_visit_id = :visitId";
    private static final String NEXT_SITTING_NO = """
            select coalesce(max(sitting_no), 0) + 1 from opd.treatment_course_sitting
            where tenant_id = :tenantId and course_id = :courseId
            """;
    private static final String ITEMS_EXPR = "(select coalesce(array_agg(t.v order by t.n), cast('{}' as text[]))"
            + " from jsonb_array_elements_text(cast(:items as jsonb)) with ordinality as t(v, n))";
    private static final String INSERT_SITTING = """
            insert into opd.treatment_course_sitting (id, tenant_id, course_id, sitting_no, encounter_kind,
                                                      add_option_key, planned_date, items, charge_paise, note,
                                                      admission_requested, idempotency_key, request_hash, created_by)
            values (:id, :tenantId, :courseId, :sittingNo, :kind, :optionKey, :plannedDate,
            """ + ITEMS_EXPR + """
            , :charge, :note, :requested, :idempotencyKey, :requestHash, :actor)
            """;
    private static final String UPDATE_SITTING = """
            update opd.treatment_course_sitting
            set encounter_kind = :kind, planned_date = :plannedDate,
                items =
            """ + ITEMS_EXPR + """
            , charge_paise = :charge, note = :note, admission_requested = :requested, updated_at = now(),
                updated_by = :actor
            where tenant_id = :tenantId and id = :id
            """;
    private static final String LINK_VISIT = """
            update opd.treatment_course_sitting set opd_visit_id = :visitId, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id
            """;
    private static final String LINK_ADMISSION = """
            update opd.treatment_course_sitting set ipd_admission_id = :admissionId, updated_at = now(),
                updated_by = :actor
            where tenant_id = :tenantId and id = :id
            """;
    private static final String MARK_DONE = """
            update opd.treatment_course_sitting set done_at = :at, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and done_at is null
            """;
    private static final String OPEN_ADMISSION = "select " + SITTING_COLUMNS
            + " from opd.treatment_course_sitting s where tenant_id = :tenantId and admission_requested = true"
            + " and ipd_admission_id is null and done_at is null and exists (select 1 from opd.treatment_course c"
            + " where c.tenant_id = s.tenant_id and c.id = s.course_id and c.status = 'ACTIVE')"
            + " order by created_at desc limit 200";
    private static final String BUMP = """
            update opd.treatment_course set revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcTreatmentCourseRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void lockIdempotency(UUID tenantId, String idempotencyKey) {
        jdbc.sql(LOCK).param("key", "course:" + tenantId + ":" + idempotencyKey).query().singleRow();
    }

    @Override
    public int nextCourseNo(UUID tenantId) {
        return jdbc.sql(NEXT_COURSE_NO).param("tenantId", tenantId).query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public UUID insertCourse(UUID tenantId, NewCourse c, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_COURSE).param("id", id).param("tenantId", tenantId).param("courseNo", c.courseNo())
                .param("patientId", c.patientId()).param("kind", c.kind()).param("unit", c.unit())
                .param("name", c.name()).param("note", c.note()).param("doctor", c.doctorMembershipId())
                .param("startedOn", c.startedOn()).param("originId", c.originRecordId())
                .param("originCreatedAt", c.originRecordCreatedAt() == null ? null
                        : Timestamp.from(c.originRecordCreatedAt()))
                .param("idempotencyKey", c.idempotencyKey()).param("requestHash", c.requestHash())
                .param("actor", actorUserId).update();
        return id;
    }

    @Override
    public Optional<CourseRow> find(UUID tenantId, UUID courseId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", courseId)
                .query(JdbcTreatmentCourseRepository::course).optional();
    }

    @Override
    public Optional<CourseRow> findForUpdate(UUID tenantId, UUID courseId) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", courseId)
                .query(JdbcTreatmentCourseRepository::course).optional();
    }

    @Override
    public Optional<CourseRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(FIND_BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcTreatmentCourseRepository::course).optional();
    }

    @Override
    public List<CourseRow> forPatient(UUID tenantId, UUID patientId, int limit) {
        return jdbc.sql(FOR_PATIENT).param("tenantId", tenantId).param("patientId", patientId).param("limit", limit)
                .query(JdbcTreatmentCourseRepository::course).list();
    }

    @Override
    public List<SittingRow> sittingsOf(UUID tenantId, Collection<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SITTINGS_OF).param("tenantId", tenantId).param("courseIds", courseIds)
                .query(JdbcTreatmentCourseRepository::sitting).list();
    }

    @Override
    public void markDone(UUID tenantId, UUID sittingId, Instant at, UUID actorUserId) {
        jdbc.sql(MARK_DONE).param("tenantId", tenantId).param("id", sittingId).param("at", Timestamp.from(at))
                .param("actor", actorUserId).update();
    }

    @Override
    public List<SittingRow> openAdmissionSittings(UUID tenantId) {
        return jdbc.sql(OPEN_ADMISSION).param("tenantId", tenantId).query(JdbcTreatmentCourseRepository::sitting).list();
    }

    @Override
    public Optional<SittingRow> sitting(UUID tenantId, UUID sittingId) {
        return jdbc.sql(SITTING).param("tenantId", tenantId).param("id", sittingId)
                .query(JdbcTreatmentCourseRepository::sitting).optional();
    }

    @Override
    public Optional<SittingRow> sittingForUpdate(UUID tenantId, UUID sittingId) {
        return jdbc.sql(SITTING_FOR_UPDATE).param("tenantId", tenantId).param("id", sittingId)
                .query(JdbcTreatmentCourseRepository::sitting).optional();
    }

    @Override
    public Optional<SittingRow> sittingByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SITTING_BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcTreatmentCourseRepository::sitting).optional();
    }

    @Override
    public Optional<SittingRow> sittingByVisit(UUID tenantId, UUID visitId) {
        return jdbc.sql(SITTING_BY_VISIT).param("tenantId", tenantId).param("visitId", visitId)
                .query(JdbcTreatmentCourseRepository::sitting).optional();
    }

    @Override
    public int nextSittingNo(UUID tenantId, UUID courseId) {
        return jdbc.sql(NEXT_SITTING_NO).param("tenantId", tenantId).param("courseId", courseId)
                .query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public UUID insertSitting(UUID tenantId, NewSitting s, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_SITTING).param("id", id).param("tenantId", tenantId).param("courseId", s.courseId())
                .param("sittingNo", s.sittingNo()).param("kind", s.encounterKind())
                .param("optionKey", s.addOptionKey()).param("plannedDate", s.plannedDate())
                .param("items", itemsJson(s.items())).param("charge", s.chargePaise()).param("note", s.note())
                .param("requested", !"OPD".equals(s.encounterKind())).param("idempotencyKey", s.idempotencyKey())
                .param("requestHash", s.requestHash()).param("actor", actorUserId).update();
        return id;
    }

    @Override
    public void updateSitting(UUID tenantId, UUID sittingId, SittingChange c, UUID actorUserId) {
        jdbc.sql(UPDATE_SITTING).param("tenantId", tenantId).param("id", sittingId).param("kind", c.encounterKind())
                .param("plannedDate", c.plannedDate()).param("items", itemsJson(c.items()))
                .param("charge", c.chargePaise()).param("note", c.note())
                .param("requested", !"OPD".equals(c.encounterKind())).param("actor", actorUserId).update();
    }

    @Override
    public void linkVisit(UUID tenantId, UUID sittingId, UUID visitId, UUID actorUserId) {
        jdbc.sql(LINK_VISIT).param("tenantId", tenantId).param("id", sittingId).param("visitId", visitId)
                .param("actor", actorUserId).update();
    }

    @Override
    public void linkAdmission(UUID tenantId, UUID sittingId, UUID admissionId, UUID actorUserId) {
        jdbc.sql(LINK_ADMISSION).param("tenantId", tenantId).param("id", sittingId)
                .param("admissionId", admissionId).param("actor", actorUserId).update();
    }

    @Override
    public void bumpRevision(UUID tenantId, UUID courseId, UUID actorUserId) {
        jdbc.sql(BUMP).param("tenantId", tenantId).param("id", courseId).param("actor", actorUserId).update();
    }

    private String itemsJson(List<String> items) {
        try {
            return mapper.writeValueAsString(items == null ? List.of() : items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sitting items could not be written", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private static CourseRow course(ResultSet rs, int row) throws SQLException {
        return new CourseRow(rs.getObject("id", UUID.class), rs.getInt("course_no"),
                rs.getObject("patient_id", UUID.class), rs.getString("kind"), rs.getString("unit"),
                rs.getString("name"), rs.getString("note"), rs.getObject("doctor_membership_id", UUID.class),
                rs.getObject("started_on", LocalDate.class), rs.getString("status"), rs.getLong("revision"),
                rs.getString("request_hash"), instant(rs, "created_at"), rs.getObject("origin_record_id", UUID.class));
    }

    private static SittingRow sitting(ResultSet rs, int row) throws SQLException {
        List<String> items = new ArrayList<>();
        Array array = rs.getArray("items");
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                items.add((String) value);
            }
        }
        return new SittingRow(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getInt("sitting_no"), rs.getString("encounter_kind"), rs.getString("add_option_key"),
                rs.getObject("planned_date", LocalDate.class), List.copyOf(items), rs.getLong("charge_paise"),
                rs.getString("note"), rs.getObject("opd_visit_id", UUID.class),
                rs.getObject("ipd_admission_id", UUID.class), rs.getBoolean("admission_requested"),
                instant(rs, "done_at"), rs.getString("request_hash"));
    }
}
