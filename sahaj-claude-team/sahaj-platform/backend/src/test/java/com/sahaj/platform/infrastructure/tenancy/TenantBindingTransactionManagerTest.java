package com.sahaj.platform.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.infrastructure.security.TenantOnlyAuthentication;
import com.sahaj.platform.infrastructure.security.TenantOnlyPrincipal;
import com.sahaj.platform.infrastructure.security.VerifiedUserAuthentication;
import com.sahaj.platform.infrastructure.security.VerifiedUserPrincipal;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks, without a database, that the tenant is bound at transaction start from the principal and
 * from nowhere else. The real row level security behaviour is covered by the integration tests.
 */
class TenantBindingTransactionManagerTest {

    private static final String BIND_SQL = "select set_config('app.tenant_id', ?, true)";

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement bindStatement;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        bindStatement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(bindStatement);
        transactions = new TransactionTemplate(new TenantBindingTransactionManager(dataSource));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void bindsTheTenantOfThePrincipalAtTheStartOfTheTransaction() throws SQLException {
        UUID tenant = UUID.randomUUID();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(tenant, UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { }));

        InOrder order = inOrder(connection, bindStatement);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement(BIND_SQL);
        order.verify(bindStatement).setString(1, tenant.toString());
        order.verify(bindStatement).execute();
        order.verify(connection).commit();
    }

    @Test
    void bindsAgainForEveryNewTransaction() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(first, UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { }));
        TestTenantSecurity.runAs(TestTenantSecurity.authentication(second, UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(bindStatement).setString(1, first.toString());
        verify(bindStatement).setString(1, second.toString());
    }

    @Test
    void bindsNothingWhenNobodyIsLoggedIn() throws SQLException {
        transactions.executeWithoutResult(status -> { });

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void bindsNothingForAnAnonymousVisitor() throws SQLException {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        TestTenantSecurity.runAs(anonymous, () -> transactions.executeWithoutResult(status -> { }));

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void bindsNothingForALoggedInUserWhoseSessionHasNoTenant() throws SQLException {
        TestTenantSecurity.runAs(TestTenantSecurity.signedInWithoutTenant(),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void theTenantIsAlwaysABindParameterNeverPartOfTheSql() throws SQLException {
        UUID tenant = UUID.randomUUID();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(tenant, UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection).prepareStatement(BIND_SQL);
        assertThat(BIND_SQL).doesNotContain(tenant.toString());
    }

    @Test
    void releasesTheConnectionAndFailsWhenTheBindingFails() throws SQLException {
        when(bindStatement.execute()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> TestTenantSecurity.runAs(
                TestTenantSecurity.authentication(UUID.randomUUID(), UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> { })))
                .isInstanceOf(CannotCreateTransactionException.class);

        verify(connection).close();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test
    void bindsOnlyAppUserIdForAVerifiedUserWithNoTenantYet() throws SQLException {
        UUID userId = UUID.randomUUID();

        TestTenantSecurity.runAs(new VerifiedUserAuthentication(new VerifiedUserPrincipal(userId)),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection).prepareStatement("select set_config('app.user_id', ?, true)");
        verify(bindStatement).setString(1, userId.toString());
    }

    @Test
    void bindsOnlyAppTenantIdFromATenantOnlyPrincipal() throws SQLException {
        UUID tenant = UUID.randomUUID();

        TestTenantSecurity.runAs(new TenantOnlyAuthentication(new TenantOnlyPrincipal(tenant)),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection).prepareStatement(BIND_SQL);
        verify(bindStatement).setString(1, tenant.toString());
    }

    @Test
    void aFullTenantPrincipalBindsBothTheTenantAndTheUser() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(tenant, user),
                () -> transactions.executeWithoutResult(status -> { }));

        verify(connection).prepareStatement(BIND_SQL);
        verify(connection).prepareStatement("select set_config('app.user_id', ?, true)");
        verify(bindStatement).setString(1, tenant.toString());
        verify(bindStatement).setString(1, user.toString());
    }

    @Test
    void rollbackEndsTheTransactionLikeACommit() throws SQLException {
        TestTenantSecurity.runAs(TestTenantSecurity.authentication(UUID.randomUUID(), UUID.randomUUID()),
                () -> transactions.executeWithoutResult(status -> status.setRollbackOnly()));

        verify(connection).rollback();
        verify(connection, never()).commit();
    }
}
