package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.HolidayRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.clinic_holiday} (SAH-7). Row level security limits every statement to the bound
 * clinic and the tenant id is also in each query. The unique key on clinic and date is what refuses
 * a repeated date (it surfaces as a {@link org.springframework.dao.DuplicateKeyException}).
 */
@Repository
public class JdbcHolidayRepository implements HolidayRepository {

    private static final String FIND_ALL = """
            select id, holiday_date, name, type from platform.clinic_holiday
            where tenant_id = :tenantId order by holiday_date
            """;
    private static final String FIND_BY_ID = """
            select id, holiday_date, name, type from platform.clinic_holiday
            where tenant_id = :tenantId and id = :id
            """;
    private static final String INSERT = """
            insert into platform.clinic_holiday (id, holiday_date, name, type, created_by)
            values (:id, :date, :name, :type, :actor)
            """;
    private static final String DELETE = "delete from platform.clinic_holiday where tenant_id = :tenantId and id = :id";
    private static final String COUNT_FROM = """
            select count(*) from platform.clinic_holiday where tenant_id = :tenantId and holiday_date >= :from
            """;

    private final JdbcClient jdbc;

    public JdbcHolidayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<HolidayRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).query(JdbcHolidayRepository::map).list();
    }

    @Override
    public Optional<HolidayRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcHolidayRepository::map).optional();
    }

    @Override
    public void insert(UUID id, LocalDate date, String name, String type, UUID actorUserId) {
        jdbc.sql(INSERT).param("id", id).param("date", date).param("name", name).param("type", type)
                .param("actor", actorUserId).update();
    }

    @Override
    public int delete(UUID tenantId, UUID id) {
        return jdbc.sql(DELETE).param("tenantId", tenantId).param("id", id).update();
    }

    @Override
    public int countFrom(UUID tenantId, LocalDate from) {
        return jdbc.sql(COUNT_FROM).param("tenantId", tenantId).param("from", from).query(Integer.class).single();
    }

    private static HolidayRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HolidayRow(rs.getObject("id", UUID.class), rs.getObject("holiday_date", LocalDate.class),
                rs.getString("name"), rs.getString("type"));
    }
}
