package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.specialty.VisitTemplateRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.emr_visit_template} (SAH-60-4). Row level security limits every statement to the bound clinic. */
@Repository
public class JdbcVisitTemplateRepository implements VisitTemplateRepository {

    private final JdbcClient jdbc;

    public JdbcVisitTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> active(UUID tenantId) {
        return jdbc.sql("""
                select id, name, sub_line, items::text as items from opd.emr_visit_template
                where tenant_id = :tenantId and active order by lower(name)
                """).param("tenantId", tenantId).query((rs, n) -> new Row(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("sub_line"), rs.getString("items"))).list();
    }

    @Override
    public long countActive(UUID tenantId) {
        return jdbc.sql("select count(*) from opd.emr_visit_template where tenant_id = :tenantId and active")
                .param("tenantId", tenantId).query(Long.class).single();
    }

    @Override
    public boolean nameExists(UUID tenantId, String name) {
        return jdbc.sql("select count(*) from opd.emr_visit_template where tenant_id = :tenantId and lower(name) = lower(:name)")
                .param("tenantId", tenantId).param("name", name).query(Long.class).single() > 0;
    }

    @Override
    public void insert(UUID tenantId, Row row, UUID membershipId, UUID actorUserId) {
        jdbc.sql("""
                insert into opd.emr_visit_template (id, tenant_id, name, sub_line, items, created_by_membership_id,
                                                    active, created_by)
                values (:id, :tenantId, :name, :subLine, cast(:items as jsonb), :membership, true, :actor)
                """).param("id", row.id()).param("tenantId", tenantId).param("name", row.name())
                .param("subLine", row.subLine()).param("items", row.items()).param("membership", membershipId)
                .param("actor", actorUserId).update();
    }
}
