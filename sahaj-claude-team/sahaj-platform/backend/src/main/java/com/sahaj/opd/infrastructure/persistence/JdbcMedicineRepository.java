package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.MedicineRepository;
import com.sahaj.opd.application.SearchPrefix;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global, read-only {@code opd.medicine} joined to this clinic's {@code opd.medicine_favourite}
 * (SAH-7). The favourites join is limited to the bound clinic by row level security and by the
 * tenant id in the query, so one clinic's favourites never show for another. The name search is a
 * prefix match on {@code lower(name)}, which the {@code text_pattern_ops} index serves.
 */
@Repository
public class JdbcMedicineRepository implements MedicineRepository {

    private static final String FROM_AND_WHERE = """
            from opd.medicine m
            left join opd.medicine_favourite f on f.medicine_id = m.id and f.tenant_id = :tenantId
            where lower(m.name) like :pattern escape '\\'
              and (:favouritesOnly = false or f.medicine_id is not null)
            """;
    private static final String SEARCH = """
            select m.id, m.name, m.form, m.schedule, m.usual_dose, (f.medicine_id is not null) as favourite
            """ + FROM_AND_WHERE + """
            order by (f.medicine_id is null), lower(m.name), m.id
            limit :limit offset :offset
            """;
    private static final String COUNT = "select count(*) " + FROM_AND_WHERE;
    private static final String FIND_NAME = "select name from opd.medicine where id = :id";
    private static final String ADD_FAVOURITE = """
            insert into opd.medicine_favourite (medicine_id, created_by) values (:id, :actor)
            on conflict do nothing
            """;
    private static final String REMOVE_FAVOURITE = """
            delete from opd.medicine_favourite where tenant_id = :tenantId and medicine_id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcMedicineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MedicinePage search(UUID tenantId, String prefix, boolean favouritesOnly, int limit, int offset) {
        String pattern = SearchPrefix.lowerPattern(prefix);
        List<MedicineRow> items = jdbc.sql(SEARCH)
                .param("tenantId", tenantId).param("pattern", pattern).param("favouritesOnly", favouritesOnly)
                .param("limit", limit).param("offset", offset)
                .query(JdbcMedicineRepository::map).list();
        long total = jdbc.sql(COUNT)
                .param("tenantId", tenantId).param("pattern", pattern).param("favouritesOnly", favouritesOnly)
                .query(Long.class).single();
        return new MedicinePage(items, total);
    }

    @Override
    public Optional<String> findName(UUID medicineId) {
        return jdbc.sql(FIND_NAME).param("id", medicineId).query(String.class).optional();
    }

    @Override
    public int addFavourite(UUID medicineId, UUID actorUserId) {
        return jdbc.sql(ADD_FAVOURITE).param("id", medicineId).param("actor", actorUserId).update();
    }

    @Override
    public int removeFavourite(UUID tenantId, UUID medicineId) {
        return jdbc.sql(REMOVE_FAVOURITE).param("tenantId", tenantId).param("id", medicineId).update();
    }

    private static MedicineRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MedicineRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("form"),
                rs.getString("schedule"), rs.getString("usual_dose"), rs.getBoolean("favourite"));
    }
}
