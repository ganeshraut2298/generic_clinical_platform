package com.sahaj.opd.application;

import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC10: when the fee is collected, the follow-up window (1 to 30 days) and fee, the discount
 * limit of each role (0 to 100 percent) and the UPI QR on the bill. Any clinic user reads; only the
 * owner changes it. A role missing from the discount limits has no discount right. Until the first
 * save the clinic sees the defaults of the mockup.
 */
@Service
public class FeeSettingsService {

    static final FeeRow DEFAULTS = new FeeRow("before", 7, "free",
            Map.of("FRONT_DESK", 10, "NURSE", 0, "OWNER", 100), true);

    private static final Set<String> TIMINGS = Set.of("before", "after", "split");
    private static final Set<String> FOLLOW_UP_FEES = Set.of("free", "half", "full");
    private static final Set<String> ROLES = Set.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK",
            "BILLING", "PHARMACIST", "COUNSELLOR");
    private static final String ENTITY_TYPE = "FEE_SETTINGS";

    private final FeeSettingsRepository repository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public FeeSettingsService(FeeSettingsRepository repository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.repository = repository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public FeeRow get(UUID tenantId) {
        return repository.find(tenantId).orElse(DEFAULTS);
    }

    @Transactional
    public FeeRow save(UUID tenantId, UUID actorUserId, FeeSettingsInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (input.feeTiming() == null || !TIMINGS.contains(input.feeTiming())) {
            throw new InvalidInputException("feeTiming", "Choose when the fee is collected");
        }
        if (input.followUpDays() == null || input.followUpDays() < 1 || input.followUpDays() > 30) {
            throw new InvalidInputException("followUpDays", "Enter a follow-up window from 1 to 30 days");
        }
        if (input.followUpFee() == null || !FOLLOW_UP_FEES.contains(input.followUpFee())) {
            throw new InvalidInputException("followUpFee", "Choose Free, 50% or Full");
        }
        if (input.upiOnBill() == null) {
            throw new InvalidInputException("upiOnBill", "Choose whether the bill shows a UPI QR");
        }
        FeeRow after = new FeeRow(input.feeTiming(), input.followUpDays(), input.followUpFee(),
                limits(input.discountLimits()), input.upiOnBill());

        FeeRow before = repository.find(tenantId).orElse(null);
        if (after.equals(before)) {
            return before;
        }
        repository.upsert(tenantId, after, actorUserId);
        auditLog.record("FEE_SETTINGS_UPDATED", ENTITY_TYPE, tenantId, snapshot(before == null ? DEFAULTS : before),
                snapshot(after));
        return after;
    }

    private static Map<String, Integer> limits(Map<String, Integer> given) {
        if (given == null) {
            throw new InvalidInputException("discountLimits", "Enter a discount limit for each role");
        }
        Map<String, Integer> clean = new LinkedHashMap<>();
        given.forEach((role, percent) -> {
            if (!ROLES.contains(role)) {
                throw new InvalidInputException("discountLimits", "Choose roles from the list");
            }
            if (percent == null || percent < 0 || percent > 100) {
                throw new InvalidInputException("discountLimits", "A discount limit is from 0 to 100 percent");
            }
            clean.put(role, percent);
        });
        return clean;
    }

    private static Map<String, Object> snapshot(FeeRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("feeTiming", row.feeTiming());
        values.put("followUpDays", row.followUpDays());
        values.put("followUpFee", row.followUpFee());
        values.put("discountLimits", new LinkedHashMap<String, Object>(row.discountLimits()));
        values.put("upiOnBill", row.upiOnBill());
        return values;
    }
}
