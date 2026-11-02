package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.platform.api.OpdSetupStatus;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Three yes/no facts about the clinic's OPD masters for the setup overview (SAH-7), read in the caller's transaction. */
@Repository
public class JdbcOpdSetupStatus implements OpdSetupStatus {

    private static final String STATUS = """
            select exists (select 1 from opd.service where tenant_id = :tenantId and active) as has_service,
                   exists (select 1 from opd.opd_settings where tenant_id = :tenantId) as has_fees,
                   exists (select 1 from opd.case_paper_settings where tenant_id = :tenantId) as has_paper
            """;

    private final JdbcClient jdbc;

    public JdbcOpdSetupStatus(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Status statusFor(UUID tenantId) {
        return jdbc.sql(STATUS).param("tenantId", tenantId)
                .query((rs, rowNum) -> new Status(rs.getBoolean("has_service"), rs.getBoolean("has_fees"),
                        rs.getBoolean("has_paper")))
                .single();
    }
}
