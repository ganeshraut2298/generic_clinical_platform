package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-33: the batch name lookup used by the Safety board. One statement for many users, scoped to the tenant, with the
 * same naming rule as the single lookup (the membership's own display name, else the account name). The statement is
 * run against the real database by the QA tests.
 */
class JdbcActorDirectoryPortTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    @Test
    void manyUsersAreLookedUpWithOneStatementScopedToTheTenant() {
        when(jdbc.sql(anyString())).thenReturn(spec);

        try {
            new JdbcActorDirectoryPort(jdbc).displayNames(TENANT, List.of(UUID.randomUUID(), UUID.randomUUID()));
        } catch (RuntimeException e) {
            // the row mapping is not under test; the mock returns nothing after the statement
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue().replaceAll("\\s+", " ")).contains("coalesce(m.display_name, u.full_name)")
                .contains("m.tenant_id = :tenantId").contains("m.user_id in (:userIds)")
                .doesNotContain("update ").doesNotContain("delete ");
    }

    @Test
    void noUsersMeansNoStatementAtAll() {
        Map<UUID, String> names = new JdbcActorDirectoryPort(jdbc).displayNames(TENANT, List.of());

        assertThat(names).isEmpty();
        assertThat(new JdbcActorDirectoryPort(jdbc).displayNames(TENANT, null)).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
