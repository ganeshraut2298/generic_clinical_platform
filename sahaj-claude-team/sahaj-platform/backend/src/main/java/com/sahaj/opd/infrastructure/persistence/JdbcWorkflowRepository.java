package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.WorkflowRepository;
import com.sahaj.opd.application.WorkflowStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.workflow_draft} and {@code opd.workflow_version} (SAH-12). Row level security scopes every statement. */
@Repository
public class JdbcWorkflowRepository implements WorkflowRepository {

    private static final TypeReference<List<Map<String, Object>>> STEPS = new TypeReference<>() {
    };

    private static final String SELECT_VERSION = """
            select version_no, steps::text as steps, published_at, published_by from opd.workflow_version
            where tenant_id = :tenantId
            """;
    private static final String FIND_CURRENT = SELECT_VERSION + " order by version_no desc limit 1";
    private static final String FIND_VERSION = SELECT_VERSION + " and version_no = :versionNo";
    private static final String LIST = SELECT_VERSION + " order by version_no desc";
    private static final String INSERT_VERSION = """
            insert into opd.workflow_version (tenant_id, version_no, steps, published_by)
            values (:tenantId, :versionNo, cast(:steps as jsonb), :publishedBy)
            on conflict (tenant_id, version_no) do nothing
            """;
    private static final String FIND_DRAFT =
            "select steps::text as steps from opd.workflow_draft where tenant_id = :tenantId";
    private static final String UPSERT_DRAFT = """
            insert into opd.workflow_draft (tenant_id, steps, updated_at, updated_by)
            values (:tenantId, cast(:steps as jsonb), now(), :actor)
            on conflict (tenant_id) do update set
                steps = excluded.steps,
                updated_at = now(),
                updated_by = excluded.updated_by,
                version = opd.workflow_draft.version + 1
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<VersionRow> findCurrent(UUID tenantId) {
        return jdbc.sql(FIND_CURRENT).param("tenantId", tenantId).query(this::mapVersion).optional();
    }

    @Override
    public Optional<VersionRow> findVersion(UUID tenantId, int versionNo) {
        return jdbc.sql(FIND_VERSION).param("tenantId", tenantId).param("versionNo", versionNo)
                .query(this::mapVersion).optional();
    }

    @Override
    public List<VersionRow> listVersions(UUID tenantId) {
        return jdbc.sql(LIST).param("tenantId", tenantId).query(this::mapVersion).list();
    }

    @Override
    public boolean insertVersionIfAbsent(UUID tenantId, int versionNo, List<WorkflowStep> steps, UUID publishedBy) {
        return jdbc.sql(INSERT_VERSION)
                .param("tenantId", tenantId)
                .param("versionNo", versionNo)
                .param("steps", write(steps))
                .param("publishedBy", publishedBy)
                .update() == 1;
    }

    @Override
    public Optional<DraftRow> findDraft(UUID tenantId) {
        return jdbc.sql(FIND_DRAFT).param("tenantId", tenantId)
                .query((rs, rowNum) -> new DraftRow(read(rs.getString("steps")))).optional();
    }

    @Override
    public void upsertDraft(UUID tenantId, List<WorkflowStep> steps, UUID actorUserId) {
        jdbc.sql(UPSERT_DRAFT).param("tenantId", tenantId).param("steps", write(steps)).param("actor", actorUserId)
                .update();
    }

    private VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(rs.getInt("version_no"), read(rs.getString("steps")),
                rs.getTimestamp("published_at").toInstant(), rs.getObject("published_by", UUID.class));
    }

    private List<WorkflowStep> read(String json) {
        try {
            List<WorkflowStep> steps = new ArrayList<>();
            for (Map<String, Object> item : objectMapper.readValue(json, STEPS)) {
                steps.add(new WorkflowStep((String) item.get("key"), Boolean.TRUE.equals(item.get("on")),
                        (String) item.get("owner")));
            }
            return steps;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Workflow steps are not valid JSON", ex);
        }
    }

    private String write(List<WorkflowStep> steps) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkflowStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", step.key());
            item.put("on", step.on());
            if (step.owner() != null) {
                item.put("owner", step.owner());
            }
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Workflow steps could not be written as JSON", ex);
        }
    }
}
