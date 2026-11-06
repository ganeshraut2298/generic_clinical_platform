package com.sahaj.platform.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.infrastructure.admin.AdminAuthentication;
import com.sahaj.platform.infrastructure.admin.AdminPrincipal;
import com.sahaj.platform.support.TestTenantSecurity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-003 D4/D8: an admin transaction binds the admin and its session, and never a tenant. */
class TenantBindingAdminTest {

    private Connection connection;
    private PreparedStatement statement;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        transactions = new TransactionTemplate(new TenantBindingTransactionManager(dataSource));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindsAdminIdAndSessionIdAndNeverTheTenant() throws SQLException {
        UUID adminId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        TestTenantSecurity.runAs(new AdminAuthentication(new AdminPrincipal(adminId, sessionId)),
                () -> transactions.executeWithoutResult(status -> { }));

        InOrder order = inOrder(connection, statement);
        order.verify(connection).prepareStatement("select set_config('app.admin_id', ?, true)");
        order.verify(statement).setString(1, adminId.toString());
        order.verify(connection).prepareStatement("select set_config('app.admin_session_id', ?, true)");
        order.verify(statement).setString(1, sessionId.toString());
        order.verify(connection).commit();
        verify(connection, never()).prepareStatement("select set_config('app.tenant_id', ?, true)");
        verify(connection, never()).prepareStatement("select set_config('app.user_id', ?, true)");
        verify(connection, times(2)).prepareStatement(anyString());
    }

    @Test
    void nothingIsBoundWithoutAnAdminOrTenantPrincipal() throws SQLException {
        transactions.executeWithoutResult(status -> { });

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void aClinicPrincipalStillBindsTenantAndUserOnly() throws SQLException {
        UUID tenant = UUID.randomUUID();
        TestTenantSecurity.runAs(TestTenantSecurity.authentication(tenant, UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection).prepareStatement("select set_config('app.tenant_id', ?, true)");
        verify(connection, never()).prepareStatement("select set_config('app.admin_id', ?, true)");
        verify(connection, never()).prepareStatement("select set_config('app.admin_session_id', ?, true)");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
