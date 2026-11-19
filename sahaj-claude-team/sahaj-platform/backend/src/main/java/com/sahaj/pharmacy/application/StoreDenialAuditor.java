package com.sahaj.pharmacy.application;

import com.sahaj.platform.api.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the {@code PHARMACY_STORE_ACCESS_DENIED} audit row (ids only) for a {@link StoreAccessDeniedException}, in one short
 * transaction of its own. The pharmacy problem handler calls it after the caller's transaction has ended (rolled back or
 * committed), so the request never holds two pooled connections at once and a burst of denials cannot stall the pool
 * (code review N-1, security L7; ADR-028 R3).
 *
 * <p>The transaction is started by the application's transaction manager, which binds {@code app.tenant_id} and
 * {@code app.user_id} from the signed-in user at the start of every transaction
 * ({@code TenantBindingTransactionManager.doBegin}): the security context of the request is still set while the handler
 * runs, so the insert passes row level security like any other. The tenant is never read from the exception.
 */
@Component
public class StoreDenialAuditor {

    static final String ACTION = "PHARMACY_STORE_ACCESS_DENIED";

    private static final Logger LOG = LoggerFactory.getLogger(StoreDenialAuditor.class);

    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    public StoreDenialAuditor(AuditLog auditLog, PlatformTransactionManager transactionManager) {
        this.auditLog = auditLog;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Must be called with no transaction of the request still open. Fails if the audit cannot be written, and fails loudly
     * (an error log with the ids, then an {@link IllegalStateException}) if a transaction is open: the audit would then
     * join or nest in the caller's transaction and the denial row could be lost with a rollback, or a second connection be
     * taken (code review R3-2, security I9).
     *
     * <p>If the audit insert itself fails inside the handler, the exception is not caught: the client gets a generic 500, which
     * fails closed (the denial still answers no access, nothing is shown), and the failure is logged by the framework (n-3).
     */
    public void record(StoreAccessDeniedException denial) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            LOG.error("Store denial audit called inside a transaction: membershipId={} storeId={} capability={}",
                    denial.membershipId(), denial.storeId(), denial.capability());
            throw new IllegalStateException("StoreDenialAuditor.record must run after the request's transaction has ended");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("membershipId", String.valueOf(denial.membershipId()));
        values.put("capability", denial.capability().name());
        transaction.executeWithoutResult(status ->
                auditLog.record(ACTION, "PHARMACY_STORE", denial.storeId(), null, values));
    }
}
