package com.sahaj.platform.api;

import java.util.UUID;

/**
 * Port the clinic setup wizard (SAH-4) writes OPD defaults through, so {@code platform} never
 * reaches into {@code opd}'s own table (ADR-001 D1).
 *
 * <p>The interface lives in {@code platform.api} and {@code opd} implements it (SAH-7). That keeps
 * the dependency one-way: {@code opd} depends on {@code platform.api} (audit, owner check, current
 * user) and {@code platform} depends on nothing in {@code opd}. A call from each module to the
 * other would be a cycle, which Spring Modulith's {@code ModularityTests} refuses.
 *
 * <p>{@code tenantId} is the caller's own, already-authenticated tenant (never a value read from
 * a request body); the implementation runs inside the caller's own transaction
 * ({@code Propagation.MANDATORY}) so it commits or rolls back with the rest of "Create my clinic".
 */
public interface OpdDefaultsPort {

    /**
     * Saves (or replaces) the one row of OPD defaults for {@code tenantId}.
     *
     * @param feeTiming    {@code before}, {@code after} or {@code split}
     * @param queueOn      true when patients are given a token number
     * @param vitalsOn     true when a nurse records vitals before the consultation
     * @param followUpDays 1 to 30
     * @param followUpFee  {@code free}, {@code half} or {@code full}
     */
    void saveDefaults(UUID tenantId, String feeTiming, boolean queueOn, boolean vitalsOn, int followUpDays,
                       String followUpFee);
}
