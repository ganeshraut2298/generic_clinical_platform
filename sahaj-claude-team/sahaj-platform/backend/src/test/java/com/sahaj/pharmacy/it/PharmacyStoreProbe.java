package com.sahaj.pharmacy.it;

import com.sahaj.pharmacy.application.PharmacyCaller;
import com.sahaj.pharmacy.application.PharmacyGate;
import com.sahaj.pharmacy.application.StoreAccessDeniedException;
import com.sahaj.pharmacy.application.StoreAccessGuard;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.web.PharmacyApi;
import com.sahaj.platform.api.ClinicUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY controller (SAH-78 QA). It calls the real gate and the real guard behind the real security chain and the real
 * problem handler, so the guard's 403 {@code store-permission-denied} and its audit row can be reached over HTTP with a
 * capability that no product endpoint of the caller's role has. It is not part of the product API, lives under
 * {@code src/test}, and is found only by {@code @Import(PharmacyStoreProbe.class)} (a test component is not component scanned).
 *
 * <p>It {@code implements PharmacyApi}, as every pharmacy controller does, so that {@code PharmacyProblemHandler} applies to
 * it: the handler (not the guard) writes the {@code PHARMACY_STORE_ACCESS_DENIED} audit row, after the caller's transaction has
 * ended, in one short transaction (code review N-1, security L7; ADR-028 R5-1). There is no REQUIRES_NEW any more.
 *
 * <p>Three transaction shapes, because the audit row must exist whatever way the caller declares its transaction:
 * <ul>
 *   <li>{@code /stores/...}: {@code noRollbackFor = StoreAccessDeniedException} (the shape of WP-A; the transaction commits
 *       and the handler writes the row afterwards);</li>
 *   <li>{@code /rolled-back/stores/...}: a plain {@code @Transactional} that rolls back on the refusal, after a marker write
 *       (the store name gets a suffix) so the roll back can be seen;</li>
 *   <li>{@code /read-only/stores/...}: {@code readOnly = true}.</li>
 * </ul>
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/pharmacy/it-probe")
public class PharmacyStoreProbe implements PharmacyApi {

    /** What the rolled-back probe appends to the store name before the guard runs. */
    public static final String MARKER = " (probe)";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final JdbcClient jdbc;

    public PharmacyStoreProbe(PharmacyGate gate, StoreAccessGuard guard, JdbcClient jdbc) {
        this.gate = gate;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    @GetMapping("/stores/{storeId}/{capability}")
    @Transactional(noRollbackFor = StoreAccessDeniedException.class)
    public Map<String, Object> probe(@PathVariable UUID storeId, @PathVariable StoreCapability capability) {
        return check(storeId, capability);
    }

    /** Writes the marker first. When the guard refuses, the exception rolls this transaction back; the denial audit stays. */
    @GetMapping("/rolled-back/stores/{storeId}/{capability}")
    @Transactional
    public Map<String, Object> probeThatRollsBack(@PathVariable UUID storeId, @PathVariable StoreCapability capability) {
        jdbc.sql("update pharmacy.store set name = name || :marker where id = :id")
                .param("marker", MARKER).param("id", storeId).update();
        return check(storeId, capability);
    }

    @GetMapping("/read-only/stores/{storeId}/{capability}")
    @Transactional(readOnly = true)
    public Map<String, Object> probeReadOnly(@PathVariable UUID storeId, @PathVariable StoreCapability capability) {
        return check(storeId, capability);
    }

    private Map<String, Object> check(UUID storeId, StoreCapability capability) {
        ClinicUser user = ClinicUser.current();
        PharmacyCaller caller = gate.require(user.tenantId(), user.userId(), AccessLevel.PHARMACY_ACCESS);
        Store store = guard.require(caller, storeId, capability);
        return Map.of("storeId", store.id().toString(), "name", store.name());
    }
}
