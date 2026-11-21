package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ApprovalRepository;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.TransactionType;
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
 * JDBC over the approval tables. The request keeps its identity; only its level, level clock and status are updated, each
 * under the row lock and a version check. Levels and decisions are inserted only; {@code decided_at} is the database clock and
 * is never sent. Reasons and notes are file text of a person and are never logged here.
 */
@Repository
public class JdbcApprovalRepository implements ApprovalRepository {

    private static final String REQUEST_COLUMNS = """
            r.id, r.unit_id, r.transaction_type, r.subject_type, r.subject_id, r.subject_no, r.rule_store_id, r.raised_by,
            r.raised_at, r.rule_version_id, r.amount_paise, r.has_schedule_x, r.has_high_value_item, r.urgent,
            r.max_rate_rise_percent, r.escalate_after_minutes, r.current_level, r.level_entered_at, r.status, r.version
            """;
    private static final String REQUEST_FROM = " from pharmacy.approval_request r where r.tenant_id = :tenantId";
    private static final String FIND = "select " + REQUEST_COLUMNS + REQUEST_FROM + " and r.id = :id";
    private static final String BY_SUBJECT = "select " + REQUEST_COLUMNS + REQUEST_FROM
            + " and r.subject_type = :subjectType and r.subject_id = :subjectId order by r.raised_at desc, r.id limit 1";
    private static final String PENDING = "select " + REQUEST_COLUMNS + REQUEST_FROM
            + " and r.status = 'PENDING' order by r.level_entered_at, r.id";
    private static final String PENDING_FOR = "select " + REQUEST_COLUMNS + REQUEST_FROM
            + " and r.status = 'PENDING'"
            + " and (:allStores or r.rule_store_id in (:storeIds)) order by r.level_entered_at, r.id limit :limit";
    private static final String LEVELS = """
            select level_no, approver_kind, condition_kind, applies, rule_text from pharmacy.approval_request_level
             where tenant_id = :tenantId and request_id = :requestId order by level_no
            """;
    private static final String DECISIONS = """
            select level_no, decision, decided_by, acting_as, reason_key, note, decided_at from pharmacy.approval_decision
             where tenant_id = :tenantId and request_id = :requestId order by level_no
            """;

    private static final String LEVELS_OF = """
            select request_id, level_no, approver_kind, condition_kind, applies, rule_text from pharmacy.approval_request_level
             where tenant_id = :tenantId and request_id in (:ids) order by request_id, level_no
            """;
    private static final String DECISIONS_OF = """
            select request_id, level_no, decision, decided_by, acting_as, reason_key, note, decided_at
              from pharmacy.approval_decision where tenant_id = :tenantId and request_id in (:ids) order by request_id, level_no
            """;
    private static final String VERSION_NO = """
            select version_no from pharmacy.approval_rule_version where tenant_id = :tenantId and id = :id
            """;
    private static final String INSERT_REQUEST = """
            insert into pharmacy.approval_request (tenant_id, unit_id, transaction_type, subject_type, subject_id, subject_no,
                   rule_store_id, raised_by, rule_version_id, amount_paise, has_schedule_x, has_high_value_item, urgent,
                   max_rate_rise_percent, escalate_after_minutes, current_level, created_by)
            values (:tenantId, :unitId, :type, :subjectType, :subjectId, cast(:subjectNo as text), :ruleStoreId, :raisedBy,
                   cast(:ruleVersionId as uuid), :amount, :scheduleX, :highValue, :urgent, cast(:rise as numeric),
                   cast(:escalate as integer), :level, :raisedBy)
            returning id
            """;
    private static final String INSERT_LEVEL = """
            insert into pharmacy.approval_request_level (tenant_id, unit_id, request_id, level_no, approver_kind,
                   condition_kind, amount_paise, percent, store_types, applies, rule_text, created_by)
            values (:tenantId, :unitId, :requestId, :levelNo, :approver, :condition, cast(:amount as bigint),
                   cast(:percent as numeric), cast(:storeTypes as text[]), :applies, :ruleText, :actor)
            """;
    private static final String INSERT_DECISION = """
            insert into pharmacy.approval_decision (tenant_id, unit_id, request_id, level_no, decision, decided_by, raised_by,
                   acting_as, reason_key, note)
            values (:tenantId, :unitId, :requestId, :levelNo, :decision, :decidedBy, :raisedBy, :actingAs,
                   cast(:reason as text), cast(:note as text))
            """;
    private static final String ADVANCE = """
            update pharmacy.approval_request
               set current_level = :level, level_entered_at = now(), updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'PENDING'
            """;
    private static final String FINISH = """
            update pharmacy.approval_request
               set status = :status, current_level = null, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'PENDING'
            """;

    private final JdbcClient jdbc;

    public JdbcApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Request> find(UUID tenantId, UUID requestId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", requestId).query(JdbcApprovalRepository::map)
                .optional();
    }

    @Override
    public Optional<Request> findForUpdate(UUID tenantId, UUID requestId) {
        return jdbc.sql(FIND + " for update of r").param("tenantId", tenantId).param("id", requestId)
                .query(JdbcApprovalRepository::map).optional();
    }

    @Override
    public Optional<Request> findBySubject(UUID tenantId, String subjectType, UUID subjectId) {
        return jdbc.sql(BY_SUBJECT).param("tenantId", tenantId).param("subjectType", subjectType)
                .param("subjectId", subjectId).query(JdbcApprovalRepository::map).optional();
    }

    @Override
    public List<Request> pending(UUID tenantId) {
        return jdbc.sql(PENDING).param("tenantId", tenantId).query(JdbcApprovalRepository::map).list();
    }

    @Override
    public List<Request> pendingFor(UUID tenantId, java.util.Collection<UUID> storeIds, int limit) {
        boolean all = storeIds == null;
        java.util.Collection<UUID> ids = all || storeIds.isEmpty() ? List.of(new UUID(0, 0)) : storeIds;
        return jdbc.sql(PENDING_FOR).param("tenantId", tenantId).param("allStores", all).param("storeIds", ids)
                .param("limit", limit).query(JdbcApprovalRepository::map).list();
    }

    @Override
    public java.util.Map<UUID, List<Level>> levelsOf(UUID tenantId, java.util.Collection<UUID> requestIds) {
        java.util.Map<UUID, List<Level>> out = new java.util.HashMap<>();
        if (requestIds.isEmpty()) {
            return out;
        }
        jdbc.sql(LEVELS_OF).param("tenantId", tenantId).param("ids", requestIds).query((rs, n) -> {
            out.computeIfAbsent(rs.getObject("request_id", UUID.class), k -> new java.util.ArrayList<>()).add(new Level(
                    rs.getInt("level_no"), ApproverKind.valueOf(rs.getString("approver_kind")),
                    ConditionKind.valueOf(rs.getString("condition_kind")), rs.getBoolean("applies"), rs.getString("rule_text")));
            return null;
        }).list();
        return out;
    }

    @Override
    public java.util.Map<UUID, List<Decision>> decisionsOf(UUID tenantId, java.util.Collection<UUID> requestIds) {
        java.util.Map<UUID, List<Decision>> out = new java.util.HashMap<>();
        if (requestIds.isEmpty()) {
            return out;
        }
        jdbc.sql(DECISIONS_OF).param("tenantId", tenantId).param("ids", requestIds).query((rs, n) -> {
            out.computeIfAbsent(rs.getObject("request_id", UUID.class), k -> new java.util.ArrayList<>()).add(new Decision(
                    rs.getInt("level_no"), rs.getString("decision"), rs.getObject("decided_by", UUID.class),
                    rs.getString("acting_as"), rs.getString("reason_key"), rs.getString("note"),
                    rs.getTimestamp("decided_at").toInstant()));
            return null;
        }).list();
        return out;
    }

    @Override
    public List<Level> levels(UUID tenantId, UUID requestId) {
        return jdbc.sql(LEVELS).param("tenantId", tenantId).param("requestId", requestId)
                .query((rs, n) -> new Level(rs.getInt("level_no"), ApproverKind.valueOf(rs.getString("approver_kind")),
                        ConditionKind.valueOf(rs.getString("condition_kind")), rs.getBoolean("applies"),
                        rs.getString("rule_text"))).list();
    }

    @Override
    public List<Decision> decisions(UUID tenantId, UUID requestId) {
        return jdbc.sql(DECISIONS).param("tenantId", tenantId).param("requestId", requestId)
                .query((rs, n) -> new Decision(rs.getInt("level_no"), rs.getString("decision"),
                        rs.getObject("decided_by", UUID.class), rs.getString("acting_as"), rs.getString("reason_key"),
                        rs.getString("note"), rs.getTimestamp("decided_at").toInstant())).list();
    }

    @Override
    public int versionNo(UUID tenantId, UUID ruleVersionId) {
        if (ruleVersionId == null) {
            return 0; // the served Standard default has no stored version (D-5)
        }
        return jdbc.sql(VERSION_NO).param("tenantId", tenantId).param("id", ruleVersionId).query(Integer.class).single();
    }

    @Override
    public UUID insertRequest(UUID tenantId, NewRequest r, UUID actorMembershipId) {
        return jdbc.sql(INSERT_REQUEST).param("tenantId", tenantId).param("unitId", r.unitId())
                .param("type", r.type().name()).param("subjectType", r.subjectType()).param("subjectId", r.subjectId())
                .param("subjectNo", r.subjectNo()).param("ruleStoreId", r.ruleStoreId()).param("raisedBy", r.raisedBy())
                .param("ruleVersionId", r.ruleVersionId()).param("amount", r.amountPaise())
                .param("scheduleX", r.hasScheduleX()).param("highValue", r.hasHighValueItem()).param("urgent", r.urgent())
                .param("rise", r.maxRateRisePercent()).param("escalate", r.escalateAfterMinutes())
                .param("level", r.currentLevel()).query(UUID.class).single();
    }

    @Override
    public void insertLevel(UUID tenantId, UUID unitId, UUID requestId, NewLevel l, UUID actorMembershipId) {
        String storeTypes = l.storeTypes() == null ? null : "{" + String.join(",", l.storeTypes()) + "}";
        jdbc.sql(INSERT_LEVEL).param("tenantId", tenantId).param("unitId", unitId).param("requestId", requestId)
                .param("levelNo", l.levelNo()).param("approver", l.approver().name()).param("condition", l.condition().name())
                .param("amount", l.amountPaise()).param("percent", l.percent()).param("storeTypes", storeTypes)
                .param("applies", l.applies()).param("ruleText", l.ruleText()).param("actor", actorMembershipId).update();
    }

    @Override
    public void insertDecision(UUID tenantId, UUID unitId, UUID requestId, int levelNo, String decision, UUID decidedBy,
                               UUID raisedBy, String actingAs, String reasonKey, String note) {
        jdbc.sql(INSERT_DECISION).param("tenantId", tenantId).param("unitId", unitId).param("requestId", requestId)
                .param("levelNo", levelNo).param("decision", decision).param("decidedBy", decidedBy)
                .param("raisedBy", raisedBy).param("actingAs", actingAs).param("reason", reasonKey).param("note", note)
                .update();
    }

    @Override
    public boolean advanceToLevel(UUID tenantId, UUID requestId, long version, int level, UUID actorMembershipId) {
        return jdbc.sql(ADVANCE).param("tenantId", tenantId).param("id", requestId).param("version", version)
                .param("level", level).param("actor", actorMembershipId).update() == 1;
    }

    @Override
    public boolean finish(UUID tenantId, UUID requestId, long version, String status, UUID actorMembershipId) {
        return jdbc.sql(FINISH).param("tenantId", tenantId).param("id", requestId).param("version", version)
                .param("status", status).param("actor", actorMembershipId).update() == 1;
    }

    private static Request map(ResultSet rs, int rowNum) throws SQLException {
        int level = rs.getInt("current_level");
        Integer currentLevel = rs.wasNull() ? null : level;
        int minutes = rs.getInt("escalate_after_minutes");
        Integer escalate = rs.wasNull() ? null : minutes;
        return new Request(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                TransactionType.valueOf(rs.getString("transaction_type")), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("subject_no"),
                rs.getObject("rule_store_id", UUID.class), rs.getObject("raised_by", UUID.class),
                instant(rs.getTimestamp("raised_at")), rs.getObject("rule_version_id", UUID.class),
                rs.getLong("amount_paise"), rs.getBoolean("has_schedule_x"), rs.getBoolean("has_high_value_item"),
                rs.getBoolean("urgent"), rs.getBigDecimal("max_rate_rise_percent"), escalate, currentLevel,
                instant(rs.getTimestamp("level_entered_at")), rs.getString("status"), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
