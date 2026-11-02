package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SAH-7 AC8: holidays and one-session days. Any clinic user reads; only the owner adds or removes. A repeated date is refused (409). */
@Service
public class HolidayService {

    private static final Set<String> TYPES = Set.of("CLOSED", "MORNING_ONLY", "EVENING_ONLY");
    private static final String ENTITY_TYPE = "CLINIC_HOLIDAY";

    private final HolidayRepository holidayRepository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public HolidayService(HolidayRepository holidayRepository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.holidayRepository = holidayRepository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<HolidayRow> list(UUID tenantId) {
        return holidayRepository.findAll(tenantId);
    }

    @Transactional
    public HolidayRow add(UUID tenantId, UUID actorUserId, LocalDate date, String name, String type) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (date == null) {
            throw new InvalidInputException("date", "Choose the date");
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new InvalidInputException("name", "Enter the holiday name");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new InvalidInputException("type", "Choose Closed, Morning only or Evening only");
        }
        UUID id = UUID.randomUUID();
        try {
            holidayRepository.insert(id, date, cleanName, type, actorUserId);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("holiday-date-taken", "That date already has a holiday",
                    "A holiday is already set for that date.");
        }
        HolidayRow added = new HolidayRow(id, date, cleanName, type);
        auditLog.record("HOLIDAY_ADDED", ENTITY_TYPE, id, null, snapshot(added));
        return added;
    }

    @Transactional
    public void remove(UUID tenantId, UUID actorUserId, UUID id) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        HolidayRow before = holidayRepository.findById(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Holiday not found"));
        if (holidayRepository.delete(tenantId, id) == 0) {
            throw new NotFoundException("Holiday not found");
        }
        auditLog.record("HOLIDAY_REMOVED", ENTITY_TYPE, id, snapshot(before), null);
    }

    private static Map<String, Object> snapshot(HolidayRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", row.date().toString());
        values.put("name", row.name());
        values.put("type", row.type());
        return values;
    }
}
