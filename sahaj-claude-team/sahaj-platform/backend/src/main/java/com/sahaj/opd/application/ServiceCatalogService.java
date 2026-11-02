package com.sahaj.opd.application;

import com.sahaj.opd.application.ServiceRepository.ServiceRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 Fees and billing, price list (AC2, AC13). Any clinic user reads; only the owner changes it.
 * A service is never deleted: "remove" sets {@code active} to false so past bills keep it. The name
 * is unique per clinic ignoring case, removed services included, so a removed service is brought
 * back with {@code active = true} instead of being added twice. Prices are whole rupees in the API
 * and paise in the database.
 */
@Service
public class ServiceCatalogService {

    static final String DUPLICATE = "That service is already in the list";
    private static final Set<String> CATEGORIES =
            Set.of("Consultation", "Procedure", "Nursing", "Lab", "Radiology", "Dental", "Package");
    private static final String ENTITY_TYPE = "SERVICE";

    private final ServiceRepository serviceRepository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public ServiceCatalogService(ServiceRepository serviceRepository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.serviceRepository = serviceRepository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<ServiceView> list(UUID tenantId, Boolean active) {
        return serviceRepository.findAll(tenantId, active).stream().map(ServiceCatalogService::toView).toList();
    }

    /** AC2, AC13: a new service needs a name, a category and a price above ₹0. */
    @Transactional
    public ServiceView add(UUID tenantId, UUID actorUserId, ServiceInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        String name = cleanName(input.name());
        String category = cleanCategory(input.category());
        if (input.priceRupees() == null || input.priceRupees() <= 0) {
            throw new InvalidInputException("priceRupees", "Enter a price above ₹0");
        }
        boolean active = input.active() == null || input.active();
        UUID id = UUID.randomUUID();
        try {
            serviceRepository.insert(id, name, category, input.priceRupees() * 100L, active, actorUserId);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        ServiceRow added = find(tenantId, id);
        auditLog.record("SERVICE_ADDED", ENTITY_TYPE, id, null, snapshot(added));
        return toView(added);
    }

    /** AC13: an edit may set the price to ₹0; sending only {@code active} removes or brings back a service. */
    @Transactional
    public ServiceView update(UUID tenantId, UUID actorUserId, UUID id, ServiceInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        ServiceRow before = find(tenantId, id);
        String name = input.name() == null ? before.name() : cleanName(input.name());
        String category = input.category() == null ? before.category() : cleanCategory(input.category());
        long pricePaise = before.pricePaise();
        if (input.priceRupees() != null) {
            if (input.priceRupees() < 0) {
                throw new InvalidInputException("priceRupees", "The price cannot be below ₹0");
            }
            pricePaise = input.priceRupees() * 100L;
        }
        boolean active = input.active() == null ? before.active() : input.active();

        ServiceRow after = new ServiceRow(id, name, category, pricePaise, active);
        if (after.equals(before)) {
            return toView(before);
        }
        try {
            serviceRepository.update(id, name, category, pricePaise, active, actorUserId);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        auditLog.record(actionFor(before, after), ENTITY_TYPE, id, snapshot(before), snapshot(after));
        return toView(find(tenantId, id));
    }

    private static String actionFor(ServiceRow before, ServiceRow after) {
        if (before.active() && !after.active()) {
            return "SERVICE_REMOVED";
        }
        if (!before.active() && after.active()) {
            return "SERVICE_RESTORED";
        }
        return "SERVICE_UPDATED";
    }

    private ServiceRow find(UUID tenantId, UUID id) {
        return serviceRepository.findById(tenantId, id).orElseThrow(() -> new NotFoundException("Service not found"));
    }

    private static ConflictException duplicate() {
        return new ConflictException("duplicate-service", "That service is already in the list", DUPLICATE);
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new InvalidInputException("name", "Enter the service name");
        }
        return trimmed;
    }

    private static String cleanCategory(String category) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new InvalidInputException("category", "Choose a category from the list");
        }
        return category;
    }

    private static ServiceView toView(ServiceRow row) {
        return new ServiceView(row.id(), row.name(), row.category(), row.pricePaise() / 100, row.active());
    }

    private static Map<String, Object> snapshot(ServiceRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", row.name());
        values.put("category", row.category());
        values.put("priceRupees", row.pricePaise() / 100);
        values.put("active", row.active());
        return values;
    }
}
