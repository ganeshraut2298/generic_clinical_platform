package com.sahaj.platform.infrastructure.tenancy;

import com.sahaj.platform.infrastructure.security.CurrentTenant;
import com.sahaj.platform.infrastructure.security.CurrentTenantOnly;
import com.sahaj.platform.infrastructure.security.CurrentVerifiedUser;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * The single place that binds the tenant to the database (SAH-1, ADR-002 D2 and D4).
 *
 * <p>Right after a transaction begins, this runs {@code set_config('app.tenant_id', ?, true)},
 * which is the same as {@code SET LOCAL app.tenant_id}: the value lives only inside that
 * transaction and PostgreSQL discards it at commit or rollback, so a pooled connection can never
 * carry one clinic's identity into the next request. A logged-in clinic session binds
 * {@code app.tenant_id} and {@code app.user_id} together (SAH-5: per-user preference rows need
 * both). The values come from the authenticated {@link TenantPrincipal} only. It is passed as a bind parameter, never concatenated.
 *
 * <p>When nobody is logged in as a clinic user, nothing is set. The row level security policies
 * read the missing setting as NULL, so every tenant table then returns no rows and accepts no
 * inserts (fail closed). Nothing else in {@code src/main} may set {@code app.*} settings.
 *
 * <p>SAH-3 (DEC-007) adds two narrower, transient sources, each used only for a moment between a
 * confirmed one-time code and a chosen tenant, when a full {@link TenantPrincipal} cannot yet be
 * built:
 * <ul>
 *   <li>{@link CurrentVerifiedUser}: binds {@code app.user_id} only, so the application can read
 *       its own membership rows (which clinics does this user belong to?) and revoke its own
 *       refresh tokens across every tenant (log out on all devices), under the narrow additional
 *       policies added by migrations 1011 and 1012.</li>
 *   <li>{@link CurrentTenantOnly}: binds {@code app.tenant_id} only, from the (not yet trusted)
 *       prefix of a presented refresh token, so a refresh token row can be looked up by its hash
 *       under the ordinary tenant-scoped policy before the user it belongs to is confirmed.</li>
 * </ul>
 * At most one of the three sources applies per transaction; {@link CurrentTenant} (a full,
 * logged-in session) takes priority when present.
 *
 * <p>Data access that must see tenant or user rows has to run inside a transaction; outside one,
 * the setting would apply to a single statement only.
 */
public class TenantBindingTransactionManager extends DataSourceTransactionManager {

    private static final long serialVersionUID = 1L;

    private static final String BIND_TENANT_SQL = "select set_config('app.tenant_id', ?, true)";
    private static final String BIND_USER_SQL = "select set_config('app.user_id', ?, true)";

    public TenantBindingTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        try {
            Optional<TenantPrincipal> principal = CurrentTenant.principal();
            if (principal.isPresent()) {
                // SAH-5: platform.user_preference row level security needs both settings.
                bind(BIND_TENANT_SQL, principal.get().tenantId());
                bind(BIND_USER_SQL, principal.get().userId());
                return;
            }
            Optional<UUID> verifiedUserId = CurrentVerifiedUser.userId();
            if (verifiedUserId.isPresent()) {
                bind(BIND_USER_SQL, verifiedUserId.get());
                return;
            }
            Optional<UUID> tenantOnly = CurrentTenantOnly.tenantId();
            if (tenantOnly.isPresent()) {
                bind(BIND_TENANT_SQL, tenantOnly.get());
            }
        } catch (RuntimeException ex) {
            // The transaction manager does not clean up when doBegin fails, so release the connection here.
            doCleanupAfterCompletion(transaction);
            throw ex;
        }
    }

    private void bind(String sql, UUID value) {
        DataSource dataSource = obtainDataSource();
        Connection connection;
        try {
            connection = DataSourceUtils.getConnection(dataSource);
        } catch (CannotGetJdbcConnectionException ex) {
            throw new CannotCreateTransactionException("Could not bind the session setting for the transaction", ex);
        }
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            statement.setString(1, value.toString());
            statement.execute();
        } catch (SQLException ex) {
            throw new CannotCreateTransactionException("Could not bind the session setting for the transaction", ex);
        } finally {
            JdbcUtils.closeStatement(statement);
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
