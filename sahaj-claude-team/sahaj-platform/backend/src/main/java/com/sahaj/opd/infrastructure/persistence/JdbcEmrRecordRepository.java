package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrRecordRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code opd.emr_record} (SAH-46, SAH-47, SAH-52, ADR-020 D8). Clinical data: the values document is read and written as
 * JSON text and never logged. Row level security limits every statement to the bound clinic and the tenant id is also in
 * each statement. A record is found by its encounter id, not by the creation time, so the monthly partitions of the
 * table (planned by the db-engineer) do not matter here; two first opens are serialised by an advisory lock.
 */
@Repository
public class JdbcEmrRecordRepository implements EmrRecordRepository {

    private static final String COLUMNS = """
            id, encounter_type, opd_visit_id, ipd_admission_id, round_date, er_case_id, patient_id,
            opened_by_membership_id, department_key, department_name, starter_key, layout_kind,
            department_version_id, starter_layout_version, choice_source, status, signed_at,
            sections::text as sections, ui_part, ui_section_key, admission_advised, referral_line, revision,
            created_at, print_language, paper_size, form_refs::text as form_refs, screen_state::text as screen_state
            """;
    private static final String BY_ID = "select " + COLUMNS + " from opd.emr_record where tenant_id = :tenantId and id = :id";
    private static final String OPD = "select " + COLUMNS
            + " from opd.emr_record where tenant_id = :tenantId and opd_visit_id = :id";
    private static final String IPD = "select " + COLUMNS
            + " from opd.emr_record where tenant_id = :tenantId and ipd_admission_id = :id and round_date = :round";
    private static final String LATEST_IPD = "select " + COLUMNS
            + " from opd.emr_record where tenant_id = :tenantId and ipd_admission_id = :id"
            + " order by round_date desc limit 1";
    private static final String ER = "select " + COLUMNS
            + " from opd.emr_record where tenant_id = :tenantId and er_case_id = :id";
    private static final String FOR_PATIENT = "select " + COLUMNS
            + " from opd.emr_record where tenant_id = :tenantId and patient_id = :patient"
            + " order by created_at desc limit :limit";
    private static final String BY_ID_FOR_UPDATE = BY_ID + " for update";
    private static final String SAVE_PRINT = """
            update opd.emr_record set print_language = :language, paper_size = :size, updated_at = now(),
                updated_by = :actor
            where tenant_id = :tenantId and id = :id
            """;
    private static final String SAVE_LOCKED = """
            update opd.emr_record
            set sections = cast(:sections as jsonb), revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and status = 'DRAFT'
            """;
    private static final String SIGNED_FOR_PATIENT = """
            select id, signed_at, sections::text as sections from opd.emr_record
            where tenant_id = :tenantId and patient_id = :patient and status = 'SIGNED'
            order by signed_at desc limit :limit
            """;
    private static final String LATEST_SIGNED_IPD = """
            select id, signed_at, sections::text as sections from opd.emr_record
            where tenant_id = :tenantId and ipd_admission_id = :admission and status = 'SIGNED'
            order by round_date desc, signed_at desc limit 1
            """;
    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(:key, 0))";
    private static final String INSERT = """
            insert into opd.emr_record (id, tenant_id, encounter_type, opd_visit_id, ipd_admission_id, round_date,
                                        er_case_id, patient_id, opened_by_membership_id, department_key,
                                        department_name, starter_key, layout_kind, department_version_id,
                                        starter_layout_version, choice_source, status, sections, ui_part,
                                        admission_advised, revision, created_by, form_refs)
            values (:id, :tenantId, :type, :visit, :admission, :round, :erCase, :patient, :openedBy, :departmentKey,
                    :departmentName, :starterKey, :layoutKind, :versionId, :starterVersion, :choiceSource, 'DRAFT',
                    cast(:sections as jsonb), 'ASSESSMENT', false, 0, :actor, cast(:formRefs as jsonb))
            """;
    private static final String SAVE_DRAFT = """
            update opd.emr_record
            set sections = cast(:sections as jsonb), ui_part = :part, ui_section_key = :sectionKey,
                revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected and status = 'DRAFT'
            """;
    private static final String SAVE_DRAFT_SCREEN = """
            update opd.emr_record
            set sections = cast(:sections as jsonb), ui_part = :part, ui_section_key = :sectionKey,
                screen_state = coalesce(cast(:screen as jsonb), screen_state),
                revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected and status = 'DRAFT'
            """;
    private static final String SWITCH_LAYOUT = """
            update opd.emr_record
            set department_key = :departmentKey, department_name = :departmentName, starter_key = :starterKey,
                layout_kind = :layoutKind, department_version_id = :versionId, starter_layout_version = :starterVersion,
                choice_source = :choiceSource, revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected and status = 'DRAFT'
            """;
    private static final String SAVE_FORM_REFS = """
            update opd.emr_record set form_refs = cast(:formRefs as jsonb), updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and status = 'DRAFT'
            """;
    private static final String ADVISE = """
            update opd.emr_record
            set admission_advised = true, referral_line = :referral, revision = revision + 1, updated_at = now(),
                updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected and status = 'DRAFT'
            """;
    private static final String SET_REFERRAL = """
            update opd.emr_record
            set referral_line = :referral, revision = revision + 1, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected and status = 'DRAFT'
              and encounter_type = 'ER_ASSESSMENT'
            """;
    private static final String LATEST_SIGNED = """
            select id, signed_at, sections::text as sections from opd.emr_record
            where tenant_id = :tenantId and patient_id = :patient and department_key = :departmentKey
              and status = 'SIGNED'
            order by signed_at desc limit 1
            """;

    private final JdbcClient jdbc;

    public JdbcEmrRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockEncounter(UUID tenantId, UUID encounterId) {
        jdbc.sql(LOCK).param("key", tenantId + ":" + encounterId).query().singleRow();
    }

    @Override
    public Optional<RecordRow> findById(UUID tenantId, UUID recordId) {
        return jdbc.sql(BY_ID).param("tenantId", tenantId).param("id", recordId)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public Optional<RecordRow> findOpd(UUID tenantId, UUID visitId) {
        return jdbc.sql(OPD).param("tenantId", tenantId).param("id", visitId)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public Optional<RecordRow> findIpd(UUID tenantId, UUID admissionId, LocalDate roundDate) {
        return jdbc.sql(IPD).param("tenantId", tenantId).param("id", admissionId).param("round", roundDate)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public Optional<RecordRow> latestIpd(UUID tenantId, UUID admissionId) {
        return jdbc.sql(LATEST_IPD).param("tenantId", tenantId).param("id", admissionId)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public Optional<RecordRow> findEmergency(UUID tenantId, UUID caseId) {
        return jdbc.sql(ER).param("tenantId", tenantId).param("id", caseId)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public void insert(UUID tenantId, NewRecord r, UUID actorUserId) {
        jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("type", r.encounterType())
                .param("visit", r.opdVisitId()).param("admission", r.ipdAdmissionId()).param("round", r.roundDate())
                .param("erCase", r.erCaseId()).param("patient", r.patientId())
                .param("openedBy", r.openedByMembershipId()).param("departmentKey", r.departmentKey())
                .param("departmentName", r.departmentName()).param("starterKey", r.starterKey())
                .param("layoutKind", r.layoutKind()).param("versionId", r.departmentVersionId())
                .param("starterVersion", r.starterLayoutVersion()).param("choiceSource", r.choiceSource())
                .param("sections", r.sections()).param("formRefs", r.formRefs() == null ? "[]" : r.formRefs())
                .param("actor", actorUserId).update();
    }

    @Override
    public boolean saveDraft(UUID tenantId, UUID recordId, long expectedRevision, String sections, String uiPart,
                             String uiSectionKey, UUID actorUserId) {
        return jdbc.sql(SAVE_DRAFT).param("tenantId", tenantId).param("id", recordId)
                .param("expected", expectedRevision).param("sections", sections).param("part", uiPart)
                .param("sectionKey", uiSectionKey).param("actor", actorUserId).update() == 1;
    }

    @Override
    public boolean saveDraft(UUID tenantId, UUID recordId, long expectedRevision, String sections, String uiPart,
                             String uiSectionKey, String screenState, UUID actorUserId) {
        return jdbc.sql(SAVE_DRAFT_SCREEN).param("tenantId", tenantId).param("id", recordId)
                .param("expected", expectedRevision).param("sections", sections).param("part", uiPart)
                .param("sectionKey", uiSectionKey).param("screen", screenState).param("actor", actorUserId)
                .update() == 1;
    }

    @Override
    public boolean switchLayout(UUID tenantId, UUID recordId, long expectedRevision, String departmentKey,
                                String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                                Integer starterLayoutVersion, String choiceSource, UUID actorUserId) {
        return jdbc.sql(SWITCH_LAYOUT).param("tenantId", tenantId).param("id", recordId)
                .param("expected", expectedRevision).param("departmentKey", departmentKey)
                .param("departmentName", departmentName).param("starterKey", starterKey)
                .param("layoutKind", layoutKind).param("versionId", departmentVersionId)
                .param("starterVersion", starterLayoutVersion).param("choiceSource", choiceSource)
                .param("actor", actorUserId).update() == 1;
    }

    @Override
    public void saveFormRefs(UUID tenantId, UUID recordId, String formRefs, UUID actorUserId) {
        jdbc.sql(SAVE_FORM_REFS).param("tenantId", tenantId).param("id", recordId).param("formRefs", formRefs)
                .param("actor", actorUserId).update();
    }

    @Override
    public boolean adviseAdmission(UUID tenantId, UUID recordId, long expectedRevision, String referralLine,
                                   UUID actorUserId) {
        return jdbc.sql(ADVISE).param("tenantId", tenantId).param("id", recordId).param("expected", expectedRevision)
                .param("referral", referralLine).param("actor", actorUserId).update() == 1;
    }

    @Override
    public boolean setReferralLine(UUID tenantId, UUID recordId, long expectedRevision, String referralLine,
                                   UUID actorUserId) {
        return jdbc.sql(SET_REFERRAL).param("tenantId", tenantId).param("id", recordId).param("expected", expectedRevision)
                .param("referral", referralLine).param("actor", actorUserId).update() == 1;
    }

    @Override
    public Optional<SignedCopy> latestSigned(UUID tenantId, UUID patientId, String departmentKey) {
        return jdbc.sql(LATEST_SIGNED).param("tenantId", tenantId).param("patient", patientId)
                .param("departmentKey", departmentKey)
                .query((rs, row) -> new SignedCopy(rs.getObject("id", UUID.class),
                        rs.getTimestamp("signed_at").toInstant(), rs.getString("sections")))
                .optional();
    }

    @Override
    public Optional<SignedCopy> latestSignedIpd(UUID tenantId, UUID admissionId) {
        return jdbc.sql(LATEST_SIGNED_IPD).param("tenantId", tenantId).param("admission", admissionId)
                .query((rs, row) -> new SignedCopy(rs.getObject("id", UUID.class),
                        rs.getTimestamp("signed_at").toInstant(), rs.getString("sections")))
                .optional();
    }

    @Override
    public List<SignedCopy> signedForPatient(UUID tenantId, UUID patientId, int limit) {
        return jdbc.sql(SIGNED_FOR_PATIENT).param("tenantId", tenantId).param("patient", patientId)
                .param("limit", limit).query((rs, row) -> new SignedCopy(rs.getObject("id", UUID.class),
                        rs.getTimestamp("signed_at").toInstant(), rs.getString("sections"))).list();
    }

    @Override
    public List<RecordRow> forPatient(UUID tenantId, UUID patientId, int limit) {
        return jdbc.sql(FOR_PATIENT).param("tenantId", tenantId).param("patient", patientId).param("limit", limit)
                .query(JdbcEmrRecordRepository::record).list();
    }

    @Override
    public boolean savePrintPreferences(UUID tenantId, UUID recordId, String language, String paperSize,
                                        UUID actorUserId) {
        return jdbc.sql(SAVE_PRINT).param("tenantId", tenantId).param("id", recordId).param("language", language)
                .param("size", paperSize).param("actor", actorUserId).update() == 1;
    }

    @Override
    public Optional<RecordRow> findByIdForUpdate(UUID tenantId, UUID recordId) {
        return jdbc.sql(BY_ID_FOR_UPDATE).param("tenantId", tenantId).param("id", recordId)
                .query(JdbcEmrRecordRepository::record).optional();
    }

    @Override
    public boolean saveSectionsLocked(UUID tenantId, UUID recordId, String sections, UUID actorUserId) {
        return jdbc.sql(SAVE_LOCKED).param("tenantId", tenantId).param("id", recordId).param("sections", sections)
                .param("actor", actorUserId).update() == 1;
    }

    private static RecordRow record(ResultSet rs, int row) throws SQLException {
        java.sql.Date round = rs.getDate("round_date");
        java.sql.Timestamp signed = rs.getTimestamp("signed_at");
        int starterVersion = rs.getInt("starter_layout_version");
        Integer starterLayoutVersion = rs.wasNull() ? null : starterVersion;
        return new RecordRow(rs.getObject("id", UUID.class), rs.getString("encounter_type"),
                rs.getObject("opd_visit_id", UUID.class), rs.getObject("ipd_admission_id", UUID.class),
                round == null ? null : round.toLocalDate(), rs.getObject("er_case_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getObject("opened_by_membership_id", UUID.class),
                rs.getString("department_key"), rs.getString("department_name"), rs.getString("starter_key"),
                rs.getString("layout_kind"), rs.getObject("department_version_id", UUID.class), starterLayoutVersion,
                rs.getString("choice_source"), rs.getString("status"), signed == null ? null : signed.toInstant(),
                rs.getString("sections"), rs.getString("ui_part"), rs.getString("ui_section_key"),
                rs.getBoolean("admission_advised"), rs.getString("referral_line"), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("print_language"), rs.getString("paper_size"),
                rs.getString("form_refs"), rs.getString("screen_state"));
    }
}
