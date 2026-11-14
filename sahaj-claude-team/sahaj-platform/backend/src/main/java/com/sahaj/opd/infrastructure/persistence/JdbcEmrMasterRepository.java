package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.EmrKeys;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.EmrMasterRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The three global EMR masters (SAH-43) and the usage-ranked quick options of all five masters (ADR-019 D3, D10).
 * The master tables are read-only for the application and the same for every clinic; the usage rows and the
 * clinic's medicine favourites are the bound clinic's by row level security and by the tenant id in each query.
 * Table names come from fixed constants below, never from a request; everything a person typed is a bound parameter
 * (the search text arrives as an escaped LIKE pattern). Prefix search on {@code lower(name)} is served by the
 * {@code text_pattern_ops} indexes. The search text is never logged.
 */
@Repository
public class JdbcEmrMasterRepository implements EmrMasterRepository {

    /** Where each master lives and how its quick-option columns are built. */
    private enum Source {
        COMPLAINTS(EmrMaster.COMPLAINTS, "opd.emr_complaint", "m.id::text", "m.name", "null::text", "null::text",
                "null::boolean", "",
                "case when m.specialty_hint = :specialty then 0 else 1 end, m.sort_order, lower(m.name), m.id"),
        DIAGNOSES(EmrMaster.DIAGNOSES, "opd.diagnosis", "m.code", "m.name", "m.code", "null::text",
                "null::boolean", "",
                "case when m.specialty = :specialty then 0 else 1 end, lower(m.name), m.code"),
        MEDICINES(EmrMaster.MEDICINES, "opd.medicine", "m.id::text", "m.name", "null::text", "m.form",
                "(f.medicine_id is not null)",
                "left join opd.medicine_favourite f on f.medicine_id = m.id and f.tenant_id = :tenantId",
                "(f.medicine_id is null), lower(m.name), m.id"),
        INVESTIGATIONS(EmrMaster.INVESTIGATIONS, "opd.emr_investigation", "m.id::text", "m.name", "null::text",
                "null::text", "null::boolean", "",
                "case when m.specialty_hint = :specialty then 0 else 1 end, m.sort_order, lower(m.name), m.id"),
        ADVICE(EmrMaster.ADVICE, "opd.emr_advice", "m.id::text", "m.name", "null::text", "null::text",
                "null::boolean", "",
                "case when m.specialty_hint = :specialty then 0 else 1 end, m.sort_order, lower(m.name), m.id");

        private final EmrMaster master;
        private final String table;
        private final String key;
        private final String name;
        private final String icd10;
        private final String form;
        private final String favourite;
        private final String extraJoin;
        private final String masterOrder;
        private final String nameMr;
        private final String nameHi;

        Source(EmrMaster master, String table, String key, String name, String icd10, String form, String favourite,
               String extraJoin, String masterOrder) {
            this.master = master;
            this.table = table;
            this.key = key;
            this.name = name;
            this.icd10 = icd10;
            this.form = form;
            this.favourite = favourite;
            this.extraJoin = extraJoin;
            this.masterOrder = masterOrder;
            boolean advice = master == EmrMaster.ADVICE;
            this.nameMr = advice ? "m.name_mr" : "null::text";
            this.nameHi = advice ? "m.name_hi" : "null::text";
        }

        static Source of(EmrMaster master) {
            for (Source source : values()) {
                if (source.master == master) {
                    return source;
                }
            }
            throw new IllegalArgumentException("Unknown master");
        }

        String selectColumns(String useCount, String lastUsedAt) {
            return key + " as item_key, " + name + " as name, " + icd10 + " as icd10_code, " + form + " as form, "
                    + favourite + " as favourite, " + useCount + " as use_count, " + lastUsedAt + " as last_used_at, "
                    + nameMr + " as name_mr, " + nameHi + " as name_hi";
        }

        /** Items this doctor used, most used first, then newest use; an item that left its master is not joined. */
        String usedSql() {
            return "select " + selectColumns("u.use_count", "u.last_used_at") + " from opd.emr_item_usage u join "
                    + table + " m on " + key + " = u.item_key " + extraJoin
                    + " where u.tenant_id = :tenantId and u.doctor_membership_id = :doctor and u.master = :masterKey"
                    + " order by u.use_count desc, u.last_used_at desc, lower(m.name), " + key + " limit :limit";
        }

        /** Items this doctor never used, in the master's own order. */
        String unusedSql() {
            return "select " + selectColumns("0", "null::timestamptz") + " from " + table + " m " + extraJoin
                    + " where not exists (select 1 from opd.emr_item_usage u where u.tenant_id = :tenantId"
                    + " and u.doctor_membership_id = :doctor and u.master = :masterKey and u.item_key = " + key + ")"
                    + " order by " + masterOrder + " limit :limit";
        }
    }

    private static final String SEARCH_COLUMNS = "select m.id, m.name, m.specialty_hint, ";

    private final JdbcClient jdbc;

    public JdbcEmrMasterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MasterPageRow search(EmrMaster master, String pattern, String specialty, int limit, int offset) {
        Source source = Source.of(master);
        if (!master.searchable()) {
            throw new IllegalArgumentException("This master is searched elsewhere.");
        }
        String where = " m where lower(m.name) like :pattern escape '\\'";
        String sql = SEARCH_COLUMNS + source.nameMr + " as name_mr, " + source.nameHi + " as name_hi from "
                + source.table + where + " order by " + source.masterOrder
                + " limit :limit offset :offset";
        List<MasterItemRow> items = jdbc.sql(sql)
                .param("pattern", pattern).param("specialty", specialty == null ? "" : specialty)
                .param("limit", limit).param("offset", offset)
                .query((rs, row) -> new MasterItemRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("specialty_hint"), rs.getString("name_mr"), rs.getString("name_hi")))
                .list();
        long total = jdbc.sql("select count(*) from " + source.table + where)
                .param("pattern", pattern).query(Long.class).single();
        return new MasterPageRow(items, total);
    }

    @Override
    public List<QuickOptionRow> ranked(UUID tenantId, UUID doctorMembershipId, EmrMaster master, String specialty,
                                       int limit) {
        Source source = Source.of(master);
        // ADR-020 D13: diagnoses keep the SAH-7 platform code as their hint; a starter key is mapped to it here.
        String hint = master == EmrMaster.DIAGNOSES ? EmrKeys.diagnosisSpecialty(specialty) : specialty;
        List<QuickOptionRow> result = new ArrayList<>(jdbc.sql(source.usedSql())
                .param("tenantId", tenantId).param("doctor", doctorMembershipId).param("masterKey", master.key())
                .param("limit", limit)
                .query(JdbcEmrMasterRepository::map).list());
        int remaining = limit - result.size();
        if (remaining > 0) {
            result.addAll(jdbc.sql(source.unusedSql())
                    .param("tenantId", tenantId).param("doctor", doctorMembershipId)
                    .param("masterKey", master.key()).param("limit", remaining)
                    .param("specialty", hint == null ? "" : hint)
                    .query(JdbcEmrMasterRepository::map).list());
        }
        return result;
    }

    private static QuickOptionRow map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime lastUsed = rs.getObject("last_used_at", OffsetDateTime.class);
        Boolean favourite = (Boolean) rs.getObject("favourite");
        return new QuickOptionRow(rs.getString("item_key"), rs.getString("name"), rs.getString("icd10_code"),
                rs.getString("form"), favourite, rs.getInt("use_count"),
                lastUsed == null ? null : lastUsed.toInstant(), rs.getString("name_mr"), rs.getString("name_hi"));
    }
}
