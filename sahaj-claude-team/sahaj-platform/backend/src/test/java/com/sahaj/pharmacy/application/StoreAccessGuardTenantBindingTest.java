package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.infrastructure.tenancy.TenantBindingTransactionManager;
import com.sahaj.platform.support.TestTenantSecurity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Code review N-1 / security L7, proven without a database. The denial is a plain exception thrown inside the caller's
 * transaction (it takes the first pooled connection and the guard takes none), and the denial audit is written afterwards by
 * {@link StoreDenialAuditor} (what the pharmacy problem handler calls) in one short transaction. The real
 * {@link TenantBindingTransactionManager} is used, so the audit transaction is bound to the tenant of the signed-in user, from
 * the security context, never from the exception. The data source is a mock that counts the connections open at the same
 * time. That the row passes row level security in a real database, and that parallel denials do not stall a small pool, are
 * covered by the integration tests.
 */
class StoreAccessGuardTenantBindingTest {

    private static final String BIND_TENANT = "select set_config('app.tenant_id', ?, true)";

    private final PharmacyFixture f = new PharmacyFixture();

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** A data source whose connections are mocks; it counts how many are open at once. */
    private static final class CountingDataSource {
        final DataSource dataSource = mock(DataSource.class);
        final List<Connection> connections = new ArrayList<>();
        final List<PreparedStatement> binds = new ArrayList<>();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();

        CountingDataSource() throws SQLException {
            when(dataSource.getConnection()).thenAnswer(invocation -> {
                Connection connection = mock(Connection.class);
                PreparedStatement bind = mock(PreparedStatement.class);
                when(connection.getAutoCommit()).thenReturn(true);
                when(connection.prepareStatement(anyString())).thenReturn(bind);
                doAnswer(close -> {
                    open.decrementAndGet();
                    return null;
                }).when(connection).close();
                connections.add(connection);
                binds.add(bind);
                maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
                return connection;
            });
        }
    }

    private PharmacyCaller storeStaffCaller(PharmacyViews.Store store) {
        f.map(store, f.storeStaff, StoreRole.STORE_USER);
        return f.gate.require(PharmacyFixture.TENANT, f.storeStaff.userId(), AccessLevel.PHARMACY_ACCESS);
    }

    @Test
    void aDenialInsideTheCallersTransactionTakesNoSecondConnectionAndTheAuditThenUsesTheOnlyOne() throws SQLException {
        PharmacyViews.Store opd = f.createCentral();
        PharmacyCaller caller = storeStaffCaller(opd);
        CountingDataSource pool = new CountingDataSource();
        TenantBindingTransactionManager manager = new TenantBindingTransactionManager(pool.dataSource);
        List<Boolean> auditInsideATransaction = new ArrayList<>();
        StoreDenialAuditor auditor = new StoreDenialAuditor((action, type, id, before, after) ->
                auditInsideATransaction.add(TransactionSynchronizationManager.isActualTransactionActive()), manager);
        TransactionTemplate callersTransaction = new TransactionTemplate(manager);
        List<StoreAccessDeniedException> denials = new ArrayList<>();
        List<Integer> connectionsWhileTheCallerHoldsOne = new ArrayList<>();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(PharmacyFixture.TENANT, f.storeStaff.userId(),
                "STORE_STAFF"), () -> {
            // the caller's transaction (the service) holds the first connection while the guard refuses
            assertThatThrownBy(() -> callersTransaction.executeWithoutResult(status -> {
                try {
                    f.guard.require(caller, opd.id(), StoreCapability.APPROVE);
                } finally {
                    connectionsWhileTheCallerHoldsOne.add(pool.connections.size());
                }
            })).isInstanceOf(StoreAccessDeniedException.class).satisfies(ex -> denials.add((StoreAccessDeniedException) ex));
            // the handler runs after the caller's transaction has ended
            assertThat(pool.open.get()).isZero();
            auditor.record(denials.get(0));
        });

        // while the caller's transaction was open exactly one connection had been taken: the guard asked for none
        assertThat(connectionsWhileTheCallerHoldsOne).containsExactly(1);
        // never two at once, and nothing left open
        assertThat(pool.maxOpen.get()).isEqualTo(1);
        assertThat(pool.open.get()).isZero();
        assertThat(pool.connections).hasSize(2);
        // the caller's transaction was bound to the tenant and rolled back
        Connection callerConnection = pool.connections.get(0);
        verify(callerConnection).prepareStatement(BIND_TENANT);
        verify(pool.binds.get(0)).setString(1, PharmacyFixture.TENANT.toString());
        verify(callerConnection).rollback();
        // the audit ran in a transaction of its own, bound to the same tenant from the security context, and committed
        assertThat(auditInsideATransaction).containsExactly(true);
        Connection auditConnection = pool.connections.get(1);
        verify(auditConnection).prepareStatement(BIND_TENANT);
        verify(pool.binds.get(1)).setString(1, PharmacyFixture.TENANT.toString());
        verify(auditConnection).commit();
        verify(auditConnection, never()).rollback();
    }

    @Test
    void aDenialWithNoTransactionAtAllTakesNoConnectionAndTheAuditGetsItsOwnTenantBoundTransaction() throws SQLException {
        PharmacyViews.Store opd = f.createCentral();
        PharmacyCaller caller = storeStaffCaller(opd);
        CountingDataSource pool = new CountingDataSource();
        TenantBindingTransactionManager manager = new TenantBindingTransactionManager(pool.dataSource);
        StoreDenialAuditor auditor = new StoreDenialAuditor((a, t, i, b, v) -> { }, manager);

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(PharmacyFixture.TENANT, f.storeStaff.userId(),
                "STORE_STAFF"), () -> {
            assertThatThrownBy(() -> f.guard.require(caller, opd.id(), StoreCapability.APPROVE))
                    .isInstanceOfSatisfying(StoreAccessDeniedException.class, auditor::record);
        });

        assertThat(pool.connections).hasSize(1);
        verify(pool.binds.get(0)).setString(1, PharmacyFixture.TENANT.toString());
        verify(pool.connections.get(0)).commit();
        assertThat(pool.maxOpen.get()).isEqualTo(1);
        assertThat(pool.open.get()).isZero();
    }

    @Test
    void theAuditorFailsLoudlyAndWritesNothingWhenCalledInsideATransaction() throws SQLException {
        PharmacyViews.Store opd = f.createCentral();
        PharmacyCaller caller = storeStaffCaller(opd);
        StoreAccessDeniedException denial = f.guard.refuse(caller, opd.id(), StoreCapability.APPROVE);
        CountingDataSource pool = new CountingDataSource();
        TenantBindingTransactionManager manager = new TenantBindingTransactionManager(pool.dataSource);
        List<String> written = new ArrayList<>();
        StoreDenialAuditor auditor = new StoreDenialAuditor((a, t, i, b, v) -> written.add(a), manager);

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(PharmacyFixture.TENANT, f.storeStaff.userId(),
                "STORE_STAFF"), () -> assertThatThrownBy(() -> new TransactionTemplate(manager)
                        .executeWithoutResult(status -> auditor.record(denial)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("after the request's transaction"));

        assertThat(written).isEmpty();
        assertThat(pool.open.get()).isZero();
    }

    @Test
    void theTenantComesFromTheSignedInUserNotFromTheDenial() throws SQLException {
        PharmacyViews.Store opd = f.createCentral();
        PharmacyCaller caller = storeStaffCaller(opd);
        StoreAccessDeniedException denial = f.guard.refuse(caller, opd.id(), StoreCapability.APPROVE);
        CountingDataSource pool = new CountingDataSource();
        StoreDenialAuditor auditor = new StoreDenialAuditor((a, t, i, b, v) -> { },
                new TenantBindingTransactionManager(pool.dataSource));

        java.util.UUID otherTenant = java.util.UUID.randomUUID();
        TestTenantSecurity.runAs(TestTenantSecurity.authentication(otherTenant, f.storeStaff.userId(), "STORE_STAFF"),
                () -> auditor.record(denial));

        // the bound tenant is the one of the request; the denial has no tenant to offer
        verify(pool.binds.get(0)).setString(1, otherTenant.toString());
    }
}
