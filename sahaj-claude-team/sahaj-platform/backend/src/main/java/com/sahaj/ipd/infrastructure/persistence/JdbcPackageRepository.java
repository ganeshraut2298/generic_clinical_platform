package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.PackageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ipd.package} (SAH-29). Select only: the database role has no other grant on the table. */
@Repository
public class JdbcPackageRepository implements PackageRepository {

    static final String FIND_ACTIVE = """
            select id, name, price_paise, included_days
            from ipd.package
            where tenant_id = :tenantId and active
            order by created_at, id
            """;

    private final JdbcClient jdbc;

    public JdbcPackageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PackageRow> findActive(UUID tenantId) {
        return jdbc.sql(FIND_ACTIVE).param("tenantId", tenantId)
                .query((rs, rowNum) -> new PackageRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("price_paise"), rs.getInt("included_days")))
                .list();
    }
}
