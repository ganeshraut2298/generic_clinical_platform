package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-33 R8: the single-row membership query behind {@code TeamMemberPort.findActiveByUserId}: tenant and user (the
 * unique key {@code uq_membership_tenant_user}) and the ACTIVE status in the one statement; no whole-team read.
 */
class JdbcTeamRepositoryActiveLookupTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void theActiveLookupIsOneStatementOnTenantUserAndActiveStatus() {
        JdbcClient jdbc = mock(JdbcClient.class);
        StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(spec);

        try {
            new JdbcTeamRepository(jdbc).findActiveByUser(TENANT, USER);
        } catch (RuntimeException e) {
            // the row mapping is not under test
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        String text = sql.getValue().replaceAll("\s+", " ");
        assertThat(text).contains("m.tenant_id = :tenantId").contains("m.user_id = :userId")
                .contains("m.status = 'ACTIVE'").doesNotContain("order by").doesNotContain("m.id = :id");
        verify(spec).param("tenantId", TENANT);
        verify(spec).param("userId", USER);
    }
}
