package com.sahaj.opd.application;

import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-11 AC2, AC5: the clinic's booking rules (close-before window, no-show window, follow-up
 * priority, self-book, overbook). Any clinic role reads; only the owner changes. Until the first
 * save the clinic sees the mockup's defaults, the same lazy pattern as SAH-10's bill.
 */
@Service
public class BookingRulesService {

    static final BookingRulesRow DEFAULTS = new BookingRulesRow(1, 2, 15, true, false, false);

    private static final String ENTITY_TYPE = "BOOKING_RULES";

    private final BookingRulesRepository repository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public BookingRulesService(BookingRulesRepository repository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.repository = repository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public BookingRulesRow get(UUID tenantId) {
        return repository.find(tenantId).orElse(DEFAULTS);
    }

    @Transactional
    public BookingRulesRow save(UUID tenantId, UUID actorUserId, BookingRulesInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        BookingRulesRow after = validate(input);
        BookingRulesRow before = repository.find(tenantId).orElse(DEFAULTS);
        repository.upsert(tenantId, after, actorUserId);
        auditLog.record("BOOKING_RULES_UPDATED", ENTITY_TYPE, tenantId, snapshot(before), snapshot(after));
        return after;
    }

    private static BookingRulesRow validate(BookingRulesInput input) {
        if (input.closeBeforeHours() == null || input.closeBeforeHours() < 0 || input.closeBeforeHours() > 48) {
            throw new InvalidInputException("closeBeforeHours", "Enter a close-before window from 0 to 48 hours");
        }
        if (input.remindBeforeHours() == null || input.remindBeforeHours() < 0 || input.remindBeforeHours() > 48) {
            throw new InvalidInputException("remindBeforeHours", "Enter a reminder window from 0 to 48 hours");
        }
        if (input.noShowAfterMinutes() == null || input.noShowAfterMinutes() < 0 || input.noShowAfterMinutes() > 180) {
            throw new InvalidInputException("noShowAfterMinutes", "Enter a no-show window from 0 to 180 minutes");
        }
        if (input.followPriority() == null) {
            throw new InvalidInputException("followPriority", "Choose whether follow-ups get priority");
        }
        if (input.selfBook() == null) {
            throw new InvalidInputException("selfBook", "Choose whether patients can self-book");
        }
        if (input.overbook() == null) {
            throw new InvalidInputException("overbook", "Choose whether double booking is allowed");
        }
        return new BookingRulesRow(input.closeBeforeHours(), input.remindBeforeHours(), input.noShowAfterMinutes(),
                input.followPriority(), input.selfBook(), input.overbook());
    }

    private static Map<String, Object> snapshot(BookingRulesRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("closeBeforeHours", row.closeBeforeHours());
        values.put("remindBeforeHours", row.remindBeforeHours());
        values.put("noShowAfterMinutes", row.noShowAfterMinutes());
        values.put("followPriority", row.followPriority());
        values.put("selfBook", row.selfBook());
        values.put("overbook", row.overbook());
        return values;
    }
}
