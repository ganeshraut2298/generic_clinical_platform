package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ApprovalRuleRepository;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The approval rule tables (SAH-78-9). Row level security scopes every statement to the bound tenant and each names the tenant
 * as well. The version, rule and level tables are insert-only (the database grants nothing else); only the pointer
 * {@code pharmacy.approval_rule_set} is updated. Store types are written as an enum-name list, never as free text.
 */
@Repository
public class JdbcApprovalRuleRepository implements ApprovalRuleRepository {

    private static final String VERSION_COLUMNS =
            "v.id, v.version_no, v.preset, v.high_value_threshold_paise, v.created_at, v.created_by";
    private static final String CURRENT = "select " + VERSION_COLUMNS + """
             from pharmacy.approval_rule_set s
             join pharmacy.approval_rule_version v on v.tenant_id = s.tenant_id and v.id = s.current_version_id
            where s.tenant_id = :tenantId
            """;
    private static final String BY_NO = "select " + VERSION_COLUMNS + """
             from pharmacy.approval_rule_version v
            where v.tenant_id = :tenantId and v.version_no = :versionNo
            """;
    private static final String VERSIONS = "select " + VERSION_COLUMNS + """
             from pharmacy.approval_rule_version v
            where v.tenant_id = :tenantId order by v.version_no desc limit :limit offset :offset
            """;
    private static final String VERSIONS_COUNT =
            "select count(*) from pharmacy.approval_rule_version where tenant_id = :tenantId";
    private static final String RULES = """
            select r.transaction_type, r.escalate_after_hours, r.urgent_escalate_after_minutes, l.level_no,
                   l.approver_kind, l.condition_kind, l.amount_paise, l.percent, l.store_types
              from pharmacy.approval_rule r
              left join pharmacy.approval_rule_level l on l.tenant_id = r.tenant_id and l.rule_id = r.id
             where r.tenant_id = :tenantId and r.version_id = :versionId
             order by r.transaction_type, l.level_no
            """;
    private static final String INSERT_VERSION = """
            insert into pharmacy.approval_rule_version (tenant_id, unit_id, version_no, preset, high_value_threshold_paise,
                                                        created_by)
            values (:tenantId, :unitId, :versionNo, :preset, cast(:high as bigint), :actor)
            returning id
            """;
    private static final String INSERT_RULE = """
            insert into pharmacy.approval_rule (tenant_id, unit_id, version_id, transaction_type, escalate_after_hours,
                                                urgent_escalate_after_minutes, created_by)
            values (:tenantId, :unitId, :versionId, :type, cast(:hours as integer), cast(:urgent as integer), :actor)
            returning id
            """;
    private static final String INSERT_LEVEL = """
            insert into pharmacy.approval_rule_level (tenant_id, unit_id, rule_id, level_no, approver_kind, condition_kind,
                                                      amount_paise, percent, store_types, created_by)
            values (:tenantId, :unitId, :ruleId, :levelNo, :approver, :condition, cast(:amount as bigint),
                    cast(:percent as numeric), cast(:storeTypes as text[]), :actor)
            """;
    private static final String INSERT_SET = """
            insert into pharmacy.approval_rule_set (tenant_id, unit_id, current_version_id, created_by)
            values (:tenantId, :unitId, :versionId, :actor)
            """;
    private static final String UPDATE_SET = """
            update pharmacy.approval_rule_set
               set current_version_id = :versionId, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and unit_id = :unitId
            """;

    private final JdbcClient jdbc;

    public JdbcApprovalRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Version> current(UUID tenantId) {
        return jdbc.sql(CURRENT).param("tenantId", tenantId).query(JdbcApprovalRuleRepository::version).optional();
    }

    @Override
    public Optional<Version> version(UUID tenantId, int versionNo) {
        return jdbc.sql(BY_NO).param("tenantId", tenantId).param("versionNo", versionNo)
                .query(JdbcApprovalRuleRepository::version).optional();
    }

    @Override
    public VersionSlice versions(UUID tenantId, int limit, int offset) {
        List<Version> items = jdbc.sql(VERSIONS).param("tenantId", tenantId).param("limit", limit)
                .param("offset", offset).query(JdbcApprovalRuleRepository::version).list();
        long total = jdbc.sql(VERSIONS_COUNT).param("tenantId", tenantId).query(Long.class).single();
        return new VersionSlice(items, total);
    }

    @Override
    public List<ApprovalRule> rules(UUID tenantId, UUID versionId) {
        Map<TransactionType, Integer[]> escalation = new EnumMap<>(TransactionType.class);
        Map<TransactionType, List<ApprovalLevel>> levels = new EnumMap<>(TransactionType.class);
        jdbc.sql(RULES).param("tenantId", tenantId).param("versionId", versionId).query((rs, n) -> {
            TransactionType type = TransactionType.valueOf(rs.getString("transaction_type"));
            escalation.computeIfAbsent(type, t -> {
                try {
                    return new Integer[] {nullableInt(rs, "escalate_after_hours"),
                        nullableInt(rs, "urgent_escalate_after_minutes")};
                } catch (SQLException unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            });
            List<ApprovalLevel> list = levels.computeIfAbsent(type, t -> new ArrayList<>());
            int levelNo = rs.getInt("level_no");
            if (!rs.wasNull()) {
                list.add(new ApprovalLevel(levelNo, ApproverKind.valueOf(rs.getString("approver_kind")), condition(rs)));
            }
            return null;
        }).list();
        List<ApprovalRule> rules = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            Integer[] values = escalation.get(type);
            if (values != null) {
                rules.add(new ApprovalRule(type, levels.get(type), values[0], values[1]));
            }
        }
        return rules;
    }

    @Override
    public UUID insertVersion(UUID tenantId, UUID unitId, int versionNo, RulePreset preset, Long high, UUID actor) {
        return jdbc.sql(INSERT_VERSION).param("tenantId", tenantId).param("unitId", unitId)
                .param("versionNo", versionNo).param("preset", preset.name()).param("high", high).param("actor", actor)
                .query(UUID.class).single();
    }

    @Override
    public UUID insertRule(UUID tenantId, UUID unitId, UUID versionId, TransactionType type, Integer hours,
                           Integer urgent, UUID actor) {
        return jdbc.sql(INSERT_RULE).param("tenantId", tenantId).param("unitId", unitId).param("versionId", versionId)
                .param("type", type.name()).param("hours", hours).param("urgent", urgent).param("actor", actor)
                .query(UUID.class).single();
    }

    @Override
    public void insertLevel(UUID tenantId, UUID unitId, UUID ruleId, ApprovalLevel level, UUID actor) {
        ApprovalCondition c = level.condition();
        String storeTypes = c.storeTypes() == null ? null
                : c.storeTypes().stream().map(StoreType::name).collect(Collectors.joining(",", "{", "}"));
        jdbc.sql(INSERT_LEVEL).param("tenantId", tenantId).param("unitId", unitId).param("ruleId", ruleId)
                .param("levelNo", level.level()).param("approver", level.approver().name())
                .param("condition", c.kind().name()).param("amount", c.amountPaise()).param("percent", c.percent())
                .param("storeTypes", storeTypes).param("actor", actor).update();
    }

    @Override
    public void insertSet(UUID tenantId, UUID unitId, UUID versionId, UUID actor) {
        jdbc.sql(INSERT_SET).param("tenantId", tenantId).param("unitId", unitId).param("versionId", versionId)
                .param("actor", actor).update();
    }

    @Override
    public void updateSet(UUID tenantId, UUID unitId, UUID versionId, UUID actor) {
        jdbc.sql(UPDATE_SET).param("tenantId", tenantId).param("unitId", unitId).param("versionId", versionId)
                .param("actor", actor).update();
    }

    private static Version version(ResultSet rs, int rowNum) throws SQLException {
        long high = rs.getLong("high_value_threshold_paise");
        Long highValue = rs.wasNull() ? null : high;
        Timestamp created = rs.getTimestamp("created_at");
        return new Version(rs.getObject("id", UUID.class), rs.getInt("version_no"),
                RulePreset.valueOf(rs.getString("preset")), highValue, created.toInstant(),
                rs.getObject("created_by", UUID.class));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static ApprovalCondition condition(ResultSet rs) throws SQLException {
        ConditionKind kind = ConditionKind.valueOf(rs.getString("condition_kind"));
        long amount = rs.getLong("amount_paise");
        Long amountPaise = rs.wasNull() ? null : amount;
        BigDecimal percent = rs.getBigDecimal("percent");
        List<StoreType> storeTypes = null;
        Array array = rs.getArray("store_types");
        if (array != null) {
            storeTypes = new ArrayList<>();
            for (Object type : (Object[]) array.getArray()) {
                storeTypes.add(StoreType.valueOf((String) type));
            }
        }
        return new ApprovalCondition(kind, amountPaise, percent, storeTypes);
    }
}
