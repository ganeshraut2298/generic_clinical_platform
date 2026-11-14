package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.SearchPrefix;
import com.sahaj.opd.application.emr.EmrMedicineRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global medicine master read for the prescription (ADR-022 D4, D5): the allergy class and defaults columns added
 * in phase 3, the clinic's favourites and the doctor's use counts from {@code opd.emr_item_usage}. Read only.
 */
@Repository
public class JdbcEmrMedicineRepository implements EmrMedicineRepository {

    private static final String COLUMNS = """
            m.id, m.name, m.form, m.schedule, m.allergy_class, m.default_frequency, m.default_days, m.default_when,
            (f.medicine_id is not null) as favourite, coalesce(u.use_count, 0) as use_count
            """;
    private static final String JOINS = """
            from opd.medicine m
            left join opd.medicine_favourite f on f.medicine_id = m.id and f.tenant_id = :tenantId
            left join opd.emr_item_usage u on u.tenant_id = :tenantId and u.doctor_membership_id = :doctor
                 and u.master = 'medicines' and u.item_key = m.id::text
            """;
    private static final String FIND = "select " + COLUMNS + JOINS + " where m.id = :id";
    private static final String WHERE_PREFIX = " where lower(m.name) like :pattern escape '\'";
    private static final String SEARCH = "select " + COLUMNS + JOINS + WHERE_PREFIX
            + " order by coalesce(u.use_count, 0) desc, (f.medicine_id is null), lower(m.name), m.id"
            + " limit :limit offset :offset";
    private static final String COUNT = "select count(*) " + JOINS + WHERE_PREFIX;
    private static final String FAVOURITES = "select " + COLUMNS + JOINS
            + " where f.medicine_id is not null order by lower(m.name), m.id";
    private static final String CLASSES = "select class_key, label from opd.emr_allergy_class order by position";

    private final JdbcClient jdbc;

    public JdbcEmrMedicineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MedicineRow> find(UUID tenantId, UUID medicineId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("doctor", new UUID(0, 0))
                .param("id", medicineId).query(JdbcEmrMedicineRepository::map).optional();
    }

    @Override
    public MedicinePage search(UUID tenantId, UUID doctorMembershipId, String prefix, int limit, int offset) {
        String pattern = SearchPrefix.lowerPattern(prefix);
        List<MedicineRow> items = jdbc.sql(SEARCH).param("tenantId", tenantId).param("doctor", doctorMembershipId)
                .param("pattern", pattern).param("limit", limit).param("offset", offset)
                .query(JdbcEmrMedicineRepository::map).list();
        long total = jdbc.sql(COUNT).param("tenantId", tenantId).param("doctor", doctorMembershipId)
                .param("pattern", pattern).query(Long.class).single();
        return new MedicinePage(items, total);
    }

    @Override
    public List<MedicineRow> favourites(UUID tenantId) {
        return jdbc.sql(FAVOURITES).param("tenantId", tenantId).param("doctor", new UUID(0, 0))
                .query(JdbcEmrMedicineRepository::map).list();
    }

    @Override
    public List<AllergyClassRow> allergyClasses() {
        return jdbc.sql(CLASSES).query((rs, n) -> new AllergyClassRow(rs.getString("class_key"),
                rs.getString("label"))).list();
    }

    private static MedicineRow map(ResultSet rs, int rowNum) throws SQLException {
        int days = rs.getInt("default_days");
        Integer defaultDays = rs.wasNull() ? null : days;
        return new MedicineRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("form"),
                rs.getString("schedule"), rs.getString("allergy_class"), rs.getString("default_frequency"),
                defaultDays, rs.getString("default_when"), rs.getBoolean("favourite"), rs.getInt("use_count"));
    }
}
