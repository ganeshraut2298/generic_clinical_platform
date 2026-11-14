package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrFormRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The clinic's forms, their immutable versions and the global starter forms (SAH-59; ADR-022 D11). Row level security
 * limits every statement to the bound clinic and the tenant id is also in each statement. The revision changes only by
 * compare-and-set, so two owners saving at once give one winner. Versions are insert-only. No delete anywhere.
 */
@Repository
public class JdbcEmrFormRepository implements EmrFormRepository {

    private static final String FORM_COLUMNS = """
            id, form_key, name, starter_key, status, live_version_no, draft_version_no, draft_state,
            draft_definition::text as draft_definition, sent_back_reason, submitted_at, submitted_by, revision,
            updated_at, updated_by, request_hash
            """;
    private static final String FIND_ALL = "select " + FORM_COLUMNS
            + " from opd.emr_form where tenant_id = :tenantId order by lower(name)";
    private static final String FIND = "select " + FORM_COLUMNS
            + " from opd.emr_form where tenant_id = :tenantId and form_key = :key";
    private static final String FIND_BY_KEY = "select " + FORM_COLUMNS
            + " from opd.emr_form where tenant_id = :tenantId and idempotency_key = :key";
    private static final String COUNT = "select count(*) from opd.emr_form where tenant_id = :tenantId";
    private static final String NAME_TAKEN = """
            select exists (select 1 from opd.emr_form where tenant_id = :tenantId and lower(name) = lower(:name)
                           and (cast(:except as uuid) is null or id <> cast(:except as uuid)))
            """;
    private static final String INSERT = """
            insert into opd.emr_form (id, tenant_id, form_key, name, starter_key, status, live_version_no,
                                      draft_version_no, draft_state, draft_definition, idempotency_key, request_hash,
                                      revision, created_by, updated_by)
            values (:id, :tenantId, :formKey, :name, :starterKey, 'ACTIVE', 0, :draftVersionNo, 'DRAFT',
                    cast(:definition as jsonb), :idempotencyKey, :requestHash, 0, :actor, :actor)
            """;
    private static final String UPDATE = """
            update opd.emr_form
            set name = :name, status = :status, live_version_no = :live, draft_version_no = :draftNo,
                draft_state = :state, draft_definition = cast(:definition as jsonb), sent_back_reason = :reason,
                submitted_at = :submittedAt, submitted_by = :submittedBy, revision = revision + 1,
                updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected
            """;
    private static final String INSERT_VERSION = """
            insert into opd.emr_form_version (id, tenant_id, form_id, version_no, definition, approved_by, approved_at,
                                              published_by)
            values (:id, :tenantId, :formId, :versionNo, cast(:definition as jsonb), :approvedBy,
                    case when cast(:approvedBy as uuid) is null then null else now() end, :publishedBy)
            """;
    private static final String VERSION_COLUMNS = """
            id, form_id, version_no, definition::text as definition, approved_by, approved_at, published_at,
            published_by
            """;
    private static final String VERSIONS = "select " + VERSION_COLUMNS
            + " from opd.emr_form_version where tenant_id = :tenantId and form_id = :formId order by version_no desc";
    private static final String VERSION = "select " + VERSION_COLUMNS
            + " from opd.emr_form_version where tenant_id = :tenantId and form_id = :formId and version_no = :versionNo";
    private static final String LIVE_FOR = """
            select f.form_key, f.name, f.live_version_no as version_no, v.definition::text as definition
            from opd.emr_form f
            join opd.emr_form_version v on v.tenant_id = f.tenant_id and v.form_id = f.id
                                       and v.version_no = f.live_version_no
            where f.tenant_id = :tenantId and f.status = 'ACTIVE' and f.live_version_no >= 1
              and ((cast(:department as text) is not null
                    and jsonb_exists(v.definition #> '{settings,useIn,departmentKeys}', cast(:department as text)))
                or (cast(:place as text) is not null
                    and jsonb_exists(v.definition #> '{settings,useIn,places}', cast(:place as text))))
            order by lower(f.name)
            """;
    private static final String FORM_VERSION = """
            select f.form_key, f.name, v.version_no, v.definition::text as definition
            from opd.emr_form f
            join opd.emr_form_version v on v.tenant_id = f.tenant_id and v.form_id = f.id
            where f.tenant_id = :tenantId and f.form_key = :formKey and v.version_no = :versionNo
            """;
    private static final String STARTER_COLUMNS = "starter_key, name, description, suited_for,"
            + " definition::text as definition";
    private static final String STARTERS = "select " + STARTER_COLUMNS + " from opd.emr_form_starter order by position";
    private static final String STARTER = "select " + STARTER_COLUMNS
            + " from opd.emr_form_starter where starter_key = :key";

    private final JdbcClient jdbc;

    public JdbcEmrFormRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FormRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).query(JdbcEmrFormRepository::form).list();
    }

    @Override
    public Optional<FormRow> find(UUID tenantId, String formKey) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("key", formKey).query(JdbcEmrFormRepository::form)
                .optional();
    }

    @Override
    public Optional<FormRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(FIND_BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcEmrFormRepository::form).optional();
    }

    @Override
    public int count(UUID tenantId) {
        return jdbc.sql(COUNT).param("tenantId", tenantId).query(Integer.class).single();
    }

    @Override
    public boolean nameTaken(UUID tenantId, String name, UUID exceptFormId) {
        return jdbc.sql(NAME_TAKEN).param("tenantId", tenantId).param("name", name).param("except", exceptFormId)
                .query(Boolean.class).single();
    }

    @Override
    public UUID insert(UUID tenantId, NewForm form, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT).param("id", id).param("tenantId", tenantId).param("formKey", form.formKey())
                .param("name", form.name()).param("starterKey", form.starterKey())
                .param("draftVersionNo", form.draftVersionNo()).param("definition", form.draftDefinition())
                .param("idempotencyKey", form.idempotencyKey()).param("requestHash", form.requestHash())
                .param("actor", actorUserId).update();
        return id;
    }

    @Override
    public boolean update(UUID tenantId, UUID formId, long expectedRevision, FormState s, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", formId).param("expected", expectedRevision)
                .param("name", s.name()).param("status", s.status()).param("live", s.liveVersionNo())
                .param("draftNo", s.draftVersionNo()).param("state", s.draftState())
                .param("definition", s.draftDefinition()).param("reason", s.sentBackReason())
                .param("submittedAt", s.submittedAt() == null ? null : Timestamp.from(s.submittedAt()))
                .param("submittedBy", s.submittedBy()).param("actor", actorUserId).update() == 1;
    }

    @Override
    public UUID insertVersion(UUID tenantId, UUID formId, int versionNo, String definition, UUID approvedBy,
                              UUID publishedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_VERSION).param("id", id).param("tenantId", tenantId).param("formId", formId)
                .param("versionNo", versionNo).param("definition", definition).param("approvedBy", approvedBy)
                .param("publishedBy", publishedBy).update();
        return id;
    }

    @Override
    public List<VersionRow> versions(UUID tenantId, UUID formId) {
        return jdbc.sql(VERSIONS).param("tenantId", tenantId).param("formId", formId)
                .query(JdbcEmrFormRepository::version).list();
    }

    @Override
    public Optional<VersionRow> version(UUID tenantId, UUID formId, int versionNo) {
        return jdbc.sql(VERSION).param("tenantId", tenantId).param("formId", formId).param("versionNo", versionNo)
                .query(JdbcEmrFormRepository::version).optional();
    }

    @Override
    public List<LiveForm> liveFor(UUID tenantId, String departmentKey, String place) {
        return jdbc.sql(LIVE_FOR).param("tenantId", tenantId).param("department", departmentKey)
                .param("place", place).query(JdbcEmrFormRepository::live).list();
    }

    @Override
    public Optional<LiveForm> formVersion(UUID tenantId, String formKey, int versionNo) {
        return jdbc.sql(FORM_VERSION).param("tenantId", tenantId).param("formKey", formKey)
                .param("versionNo", versionNo).query(JdbcEmrFormRepository::live).optional();
    }

    @Override
    public List<StarterRow> starters() {
        return jdbc.sql(STARTERS).query(JdbcEmrFormRepository::starter).list();
    }

    @Override
    public Optional<StarterRow> starter(String starterKey) {
        return jdbc.sql(STARTER).param("key", starterKey).query(JdbcEmrFormRepository::starter).optional();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private static FormRow form(ResultSet rs, int row) throws SQLException {
        return new FormRow(rs.getObject("id", UUID.class), rs.getString("form_key"), rs.getString("name"),
                rs.getString("starter_key"), rs.getString("status"), rs.getInt("live_version_no"),
                rs.getInt("draft_version_no"), rs.getString("draft_state"), rs.getString("draft_definition"),
                rs.getString("sent_back_reason"), instant(rs, "submitted_at"), rs.getObject("submitted_by", UUID.class),
                rs.getLong("revision"), instant(rs, "updated_at"), rs.getObject("updated_by", UUID.class),
                rs.getString("request_hash"));
    }

    private static VersionRow version(ResultSet rs, int row) throws SQLException {
        return new VersionRow(rs.getObject("id", UUID.class), rs.getObject("form_id", UUID.class),
                rs.getInt("version_no"), rs.getString("definition"), rs.getObject("approved_by", UUID.class),
                instant(rs, "approved_at"), instant(rs, "published_at"), rs.getObject("published_by", UUID.class));
    }

    private static LiveForm live(ResultSet rs, int row) throws SQLException {
        return new LiveForm(rs.getString("form_key"), rs.getString("name"), rs.getInt("version_no"),
                rs.getString("definition"));
    }

    private static StarterRow starter(ResultSet rs, int row) throws SQLException {
        return new StarterRow(rs.getString("starter_key"), rs.getString("name"), rs.getString("description"),
                rs.getString("suited_for"), rs.getString("definition"));
    }
}
