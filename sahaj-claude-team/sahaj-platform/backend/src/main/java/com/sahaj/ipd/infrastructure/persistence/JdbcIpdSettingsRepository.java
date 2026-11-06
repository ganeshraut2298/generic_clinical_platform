package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.IpdSettingsRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ipd.bed_category} and {@code ipd.config} (SAH-22). Both are lazy: a row exists only after a change. */
@Repository
public class JdbcIpdSettingsRepository implements IpdSettingsRepository {

    private static final String FIND_CATEGORIES = """
            select key, rate_paise, multiplier
            from ipd.bed_category
            where tenant_id = :tenantId
            """;
    private static final String FIND_CONFIG = """
            select room_level, bed_day_cut, nursing_paise, visit_paise
            from ipd.config
            where tenant_id = :tenantId
            """;
    private static final String UPSERT_CATEGORY = """
            insert into ipd.bed_category (tenant_id, key, rate_paise, multiplier, updated_at, updated_by)
            values (:tenantId, :key, :rate, :multiplier, :now, :actor)
            on conflict (tenant_id, key) do update set
                rate_paise = excluded.rate_paise,
                multiplier = excluded.multiplier,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;
    private static final String UPSERT_CONFIG = """
            insert into ipd.config (tenant_id, room_level, bed_day_cut, nursing_paise, visit_paise, updated_at, updated_by)
            values (:tenantId, :roomLevel, :cut, :nursing, :visit, :now, :actor)
            on conflict (tenant_id) do update set
                room_level = excluded.room_level,
                bed_day_cut = excluded.bed_day_cut,
                nursing_paise = excluded.nursing_paise,
                visit_paise = excluded.visit_paise,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;

    private static final String FIND_DEPOSIT_RULES = """
            select dep_planned_paise, dep_emergency_paise, dep_daycare_paise, dep_maternity_paise,
                   cat_add_gen_paise, cat_add_semi_paise, cat_add_pvt_paise, cat_add_icu_paise,
                   emergency_without_deposit, deposit_alert_percent, deposit_rules_confirmed
            from ipd.config
            where tenant_id = :tenantId
            """;
    private static final String UPSERT_DEPOSIT_RULES = """
            insert into ipd.config (tenant_id, dep_planned_paise, dep_emergency_paise, dep_daycare_paise,
                                    dep_maternity_paise, cat_add_gen_paise, cat_add_semi_paise, cat_add_pvt_paise,
                                    cat_add_icu_paise, emergency_without_deposit, deposit_alert_percent,
                                    deposit_rules_confirmed, updated_at, updated_by)
            values (:tenantId, :planned, :emergency, :daycare, :maternity, :gen, :semi, :pvt, :icu,
                    :emergencyFree, :alert, :confirmed, :now, :actor)
            on conflict (tenant_id) do update set
                dep_planned_paise = excluded.dep_planned_paise,
                dep_emergency_paise = excluded.dep_emergency_paise,
                dep_daycare_paise = excluded.dep_daycare_paise,
                dep_maternity_paise = excluded.dep_maternity_paise,
                cat_add_gen_paise = excluded.cat_add_gen_paise,
                cat_add_semi_paise = excluded.cat_add_semi_paise,
                cat_add_pvt_paise = excluded.cat_add_pvt_paise,
                cat_add_icu_paise = excluded.cat_add_icu_paise,
                emergency_without_deposit = excluded.emergency_without_deposit,
                deposit_alert_percent = excluded.deposit_alert_percent,
                deposit_rules_confirmed = excluded.deposit_rules_confirmed,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;

    private static final String FIND_TRANSFER_RULES = """
            select transfer_upgrade_owner_only, new_rate_from
            from ipd.config
            where tenant_id = :tenantId
            """;
    private static final String UPSERT_TRANSFER_RULES = """
            insert into ipd.config (tenant_id, transfer_upgrade_owner_only, new_rate_from, updated_at, updated_by)
            values (:tenantId, :ownerOnly, :newRateFrom, :now, :actor)
            on conflict (tenant_id) do update set
                transfer_upgrade_owner_only = excluded.transfer_upgrade_owner_only,
                new_rate_from = excluded.new_rate_from,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;

    private static final String FIND_DISCHARGE_SETTINGS = """
            select discharge_template_key, discharge_chain, followup_days
            from ipd.config
            where tenant_id = :tenantId
            """;
    private static final String UPSERT_DISCHARGE_SETTINGS = """
            insert into ipd.config (tenant_id, discharge_template_key, discharge_chain, followup_days, updated_at,
                                    updated_by)
            values (:tenantId, :template, :chain, :days, :now, :actor)
            on conflict (tenant_id) do update set
                discharge_template_key = excluded.discharge_template_key,
                discharge_chain = excluded.discharge_chain,
                followup_days = excluded.followup_days,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;

    private static final String FIND_EMERGENCY_SETUP = """
            select triage_scale, array_to_string(triage_targets_3, ',') as targets_3,
                   array_to_string(triage_targets_5, ',') as targets_5, er_bays, unknown_patient_registration
            from ipd.config
            where tenant_id = :tenantId
            """;
    private static final String UPSERT_EMERGENCY_SETUP = """
            insert into ipd.config (tenant_id, triage_scale, triage_targets_3, triage_targets_5, er_bays,
                                    unknown_patient_registration, updated_at, updated_by)
            values (:tenantId, :scale, cast(:targets3 as smallint[]), cast(:targets5 as smallint[]), :bays,
                    :unknown, :now, :actor)
            on conflict (tenant_id) do update set
                triage_scale = excluded.triage_scale,
                triage_targets_3 = excluded.triage_targets_3,
                triage_targets_5 = excluded.triage_targets_5,
                er_bays = excluded.er_bays,
                unknown_patient_registration = excluded.unknown_patient_registration,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;

    private final JdbcClient jdbc;

    public JdbcIpdSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryRow> findCategories(UUID tenantId) {
        return jdbc.sql(FIND_CATEGORIES).param("tenantId", tenantId)
                .query((rs, rowNum) -> new CategoryRow(rs.getString("key"), rs.getLong("rate_paise"),
                        rs.getBigDecimal("multiplier")))
                .list();
    }

    @Override
    public Optional<ConfigRow> findConfig(UUID tenantId) {
        return jdbc.sql(FIND_CONFIG).param("tenantId", tenantId)
                .query((rs, rowNum) -> new ConfigRow(rs.getBoolean("room_level"), rs.getString("bed_day_cut"),
                        rs.getLong("nursing_paise"), rs.getLong("visit_paise")))
                .optional();
    }

    @Override
    public void upsertCategory(UUID tenantId, String key, long ratePaise, BigDecimal multiplier, UUID actorUserId,
                               Instant now) {
        jdbc.sql(UPSERT_CATEGORY).param("tenantId", tenantId).param("key", key).param("rate", ratePaise)
                .param("multiplier", multiplier).param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public Optional<DepositRules> findDepositRules(UUID tenantId) {
        return jdbc.sql(FIND_DEPOSIT_RULES).param("tenantId", tenantId)
                .query((rs, rowNum) -> new DepositRules(rs.getLong("dep_planned_paise"),
                        rs.getLong("dep_emergency_paise"), rs.getLong("dep_daycare_paise"),
                        rs.getLong("dep_maternity_paise"), rs.getLong("cat_add_gen_paise"),
                        rs.getLong("cat_add_semi_paise"), rs.getLong("cat_add_pvt_paise"),
                        rs.getLong("cat_add_icu_paise"), rs.getBoolean("emergency_without_deposit"),
                        rs.getInt("deposit_alert_percent"), rs.getBoolean("deposit_rules_confirmed")))
                .optional();
    }

    @Override
    public void upsertDepositRules(UUID tenantId, DepositRules r, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_DEPOSIT_RULES).param("tenantId", tenantId).param("planned", r.planned())
                .param("emergency", r.emergency()).param("daycare", r.daycare()).param("maternity", r.maternity())
                .param("gen", r.catGen()).param("semi", r.catSemi()).param("pvt", r.catPvt()).param("icu", r.catIcu())
                .param("emergencyFree", r.emergencyWithoutDeposit()).param("alert", r.alertPercent())
                .param("confirmed", r.confirmed()).param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public Optional<TransferRules> findTransferRules(UUID tenantId) {
        return jdbc.sql(FIND_TRANSFER_RULES).param("tenantId", tenantId)
                .query((rs, rowNum) -> new TransferRules(rs.getBoolean("transfer_upgrade_owner_only"),
                        rs.getString("new_rate_from")))
                .optional();
    }

    @Override
    public void upsertTransferRules(UUID tenantId, TransferRules rules, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_TRANSFER_RULES).param("tenantId", tenantId).param("ownerOnly", rules.upgradeNeedsOwner())
                .param("newRateFrom", rules.newRateFrom()).param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public Optional<DischargeSettings> findDischargeSettings(UUID tenantId) {
        return jdbc.sql(FIND_DISCHARGE_SETTINGS).param("tenantId", tenantId)
                .query((rs, rowNum) -> new DischargeSettings(rs.getString("discharge_template_key"),
                        rs.getBoolean("discharge_chain"), rs.getInt("followup_days")))
                .optional();
    }

    @Override
    public void upsertDischargeSettings(UUID tenantId, DischargeSettings settings, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_DISCHARGE_SETTINGS).param("tenantId", tenantId)
                .param("template", settings.templateKey(), java.sql.Types.VARCHAR)
                .param("chain", settings.approvalChain()).param("days", settings.followUpDays())
                .param("now", now).param("actor", actorUserId).update();
    }

    @Override
    public Optional<EmergencySetup> findEmergencySetup(UUID tenantId) {
        return jdbc.sql(FIND_EMERGENCY_SETUP).param("tenantId", tenantId)
                .query((rs, rowNum) -> new EmergencySetup(rs.getInt("triage_scale"), parse(rs.getString("targets_3")),
                        parse(rs.getString("targets_5")), rs.getInt("er_bays"),
                        rs.getBoolean("unknown_patient_registration")))
                .optional();
    }

    @Override
    public void upsertEmergencySetup(UUID tenantId, EmergencySetup setup, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_EMERGENCY_SETUP).param("tenantId", tenantId).param("scale", setup.scale())
                .param("targets3", literal(setup.targets3())).param("targets5", literal(setup.targets5()))
                .param("bays", setup.bays()).param("unknown", setup.unknownPatientRegistration())
                .param("now", now).param("actor", actorUserId).update();
    }

    /** "5,30,120" to a list. */
    private static List<Integer> parse(String commaSeparated) {
        List<Integer> values = new java.util.ArrayList<>();
        for (String part : commaSeparated.split(",")) {
            values.add(Integer.valueOf(part.trim()));
        }
        return List.copyOf(values);
    }

    /** A list of integers as a PostgreSQL array literal, for example {5,30,120}. Integers only, so nothing to escape. */
    private static String literal(List<Integer> values) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            text.append(i == 0 ? "" : ",").append(values.get(i).intValue());
        }
        return text.append("}").toString();
    }

    @Override
    public void upsertConfig(UUID tenantId, ConfigRow config, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_CONFIG).param("tenantId", tenantId).param("roomLevel", config.roomLevel())
                .param("cut", config.bedDayCut()).param("nursing", config.nursingPaise())
                .param("visit", config.visitPaise()).param("now", now).param("actor", actorUserId).update();
    }
}
