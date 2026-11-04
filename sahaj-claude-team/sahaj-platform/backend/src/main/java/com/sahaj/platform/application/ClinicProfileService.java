package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 Clinic profile (AC1, AC7): any clinic user reads it, only the owner changes it. The clinic
 * name and colour are also written to {@code platform.tenant}, so the clinic list at login and the
 * header show the new name at once. The specialty is not changed here (see {@link SpecialtyService}).
 */
@Service
public class ClinicProfileService {

    private static final Set<String> BRANDS = Set.of("blue", "teal", "green", "maroon", "custom");
    private static final Pattern PIN = Pattern.compile("^[0-9]{6}$");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String ENTITY_TYPE = "CLINIC_PROFILE";

    private final ClinicProfileRepository profileRepository;
    private final TenantRepository tenantRepository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public ClinicProfileService(ClinicProfileRepository profileRepository, TenantRepository tenantRepository,
                                 OwnerGuard ownerGuard, AuditLog auditLog) {
        this.profileRepository = profileRepository;
        this.tenantRepository = tenantRepository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public ProfileRow get(UUID tenantId) {
        return find(tenantId);
    }

    @Transactional
    public ProfileRow save(UUID tenantId, UUID actorUserId, ClinicProfileInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        ProfileRow before = find(tenantId);

        String name = required(input.name(), 160, "name", "Enter the clinic name");
        String brand = input.brand() == null ? before.brand() : input.brand().trim();
        if (!BRANDS.contains(brand)) {
            throw new InvalidInputException("brand", "Choose one of the colours");
        }
        ProfileRow after = new ProfileRow(
                name,
                optional(input.nameLocal(), 160, "nameLocal", "Use 160 characters or fewer"),
                optional(input.address(), 400, "address", "Use 400 characters or fewer"),
                city(input, before),
                pin(input.pin(), before.pin()),
                optional(input.phone(), 20, "phone", "Use 20 characters or fewer"),
                optional(input.whatsapp(), 20, "whatsapp", "Use 20 characters or fewer"),
                mapsUrl(input.mapsUrl()),
                input.registrationNo() == null ? before.registrationNo()
                        : optional(input.registrationNo(), 80, "registrationNo", "Use 80 characters or fewer"),
                before.slug(),
                before.specialtyCode(),
                brand,
                "custom".equals(brand) ? brandHex(input.brandHex()) : null);

        if (after.equals(before)) {
            return before;
        }
        profileRepository.update(tenantId, after, actorUserId);
        if (!after.name().equals(before.name()) || !after.brand().equals(before.brand())) {
            tenantRepository.updateNameAndBrand(tenantId, after.name(), after.brand());
        }
        auditLog.record("CLINIC_PROFILE_UPDATED", ENTITY_TYPE, tenantId, snapshot(before), snapshot(after));
        return find(tenantId);
    }

    private ProfileRow find(UUID tenantId) {
        return profileRepository.find(tenantId).orElseThrow(() -> new NotFoundException("Clinic profile not found"));
    }

    /**
     * Audit values. The postal code uses its own key because the audit sanitizer hides any key that
     * contains the word for a login code; the phone numbers are masked there to their last four digits.
     */
    static Map<String, Object> snapshot(ProfileRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", row.name());
        values.put("nameLocal", row.nameLocal());
        values.put("address", row.address());
        values.put("area", row.city());
        values.put("postalCode", row.pin());
        values.put("phone", row.phone());
        values.put("whatsapp", row.whatsapp());
        values.put("mapsUrl", row.mapsUrl());
        values.put("registrationNo", row.registrationNo());
        values.put("brand", row.brand());
        values.put("brandHex", row.brandHex());
        return values;
    }

    private static String required(String value, int max, String field, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new InvalidInputException(field, message);
        }
        return trimmed;
    }

    private static String optional(String value, int max, String field, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) {
            throw new InvalidInputException(field, message);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String city(ClinicProfileInput input, ProfileRow before) {
        String given = input.area() != null && !input.area().isBlank() ? input.area() : input.city();
        if (given == null || given.isBlank()) {
            return before.city();
        }
        return required(given, 120, "area", "Use 120 characters or fewer");
    }

    private static String pin(String given, String saved) {
        if (given == null) {
            return saved;
        }
        String trimmed = given.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!PIN.matcher(trimmed).matches()) {
            throw new InvalidInputException("pin", "Enter a 6-digit PIN code");
        }
        return trimmed;
    }

    /** Only a web link: http or https with a host. Anything else (javascript:, data:, file:) is refused. */
    private static String mapsUrl(String given) {
        String trimmed = given == null ? "" : given.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String message = "Paste a link that starts with https://";
        if (trimmed.length() > 500) {
            throw new InvalidInputException("mapsUrl", message);
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                throw new InvalidInputException("mapsUrl", message);
            }
        } catch (URISyntaxException ex) {
            throw new InvalidInputException("mapsUrl", message);
        }
        return trimmed;
    }

    private static String brandHex(String given) {
        String trimmed = given == null ? "" : given.trim();
        if (!HEX.matcher(trimmed).matches()) {
            throw new InvalidInputException("brandHex", "Enter 6 characters, like #1F5BC8");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
