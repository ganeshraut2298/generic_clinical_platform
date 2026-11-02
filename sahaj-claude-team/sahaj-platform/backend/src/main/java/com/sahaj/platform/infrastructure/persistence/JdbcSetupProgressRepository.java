package com.sahaj.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.application.SetupProgressRepository;
import com.sahaj.platform.domain.SetupProgress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.setup_progress}: one row per tenant, {@code tenant_id} as the primary key
 * (SAH-4 technical design). Row level security (owned by the db-engineer's migration) checks
 * {@code tenant_id = platform.current_tenant()}, the same as every other tenant table.
 */
@Repository
public class JdbcSetupProgressRepository implements SetupProgressRepository {

    private static final String FIND = """
            select current_step, answers_json, completed_at from platform.setup_progress where tenant_id = :tenantId
            """;
    private static final String UPSERT = """
            insert into platform.setup_progress (tenant_id, current_step, answers_json)
            values (:tenantId, :currentStep, cast(:answersJson as jsonb))
            on conflict (tenant_id) do update set
                current_step = excluded.current_step,
                answers_json = excluded.answers_json
            """;
    private static final String CREATE_INITIAL = """
            insert into platform.setup_progress (tenant_id, current_step, answers_json)
            values (:tenantId, 1, cast('{}' as jsonb))
            on conflict (tenant_id) do nothing
            """;
    private static final String MARK_COMPLETED = """
            update platform.setup_progress set completed_at = :completedAt where tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSetupProgressRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SetupProgress> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(this::map).optional();
    }

    @Override
    public void saveStep(UUID tenantId, int step, String stepKey, Map<String, Object> stepAnswers) {
        SetupProgress existing = find(tenantId).orElse(new SetupProgress(1, Map.of(), null));
        Map<String, Object> answers = new LinkedHashMap<>(existing.answers());
        answers.put(stepKey, stepAnswers);
        int newCurrentStep = Math.max(existing.currentStep(), Math.min(step + 1, 5));
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("currentStep", newCurrentStep)
                .param("answersJson", toJson(answers))
                .update();
    }

    @Override
    public void createInitial(UUID tenantId) {
        jdbc.sql(CREATE_INITIAL).param("tenantId", tenantId).update();
    }

    @Override
    public void markCompleted(UUID tenantId, Instant completedAt) {
        jdbc.sql(MARK_COMPLETED).param("tenantId", tenantId).param("completedAt", completedAt).update();
    }

    @Override
    public boolean isComplete(UUID tenantId) {
        return find(tenantId).map(SetupProgress::isComplete).orElse(false);
    }

    private SetupProgress map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new SetupProgress(
                rs.getInt("current_step"),
                fromJson(rs.getString("answers_json")),
                completedAt == null ? null : completedAt.toInstant());
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored setup answers could not be read", ex);
        }
    }

    private String toJson(Map<String, Object> answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Setup answers could not be written as JSON", ex);
        }
    }
}
