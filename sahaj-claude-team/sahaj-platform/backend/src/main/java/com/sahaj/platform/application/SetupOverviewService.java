package com.sahaj.platform.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OpdSetupStatus;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.AuditEventRepository.AuditRow;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC6 and AC12: the setup percentage, the checklist and the change history. Owner only.
 * The percentage and the checklist are computed here from a fixed list (the Fix links and the
 * tiles are drawn by the screen from the {@code key}). The newest {@code audit_event} is the
 * "last change".
 */
@Service
public class SetupOverviewService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final ClinicProfileRepository profileRepository;
    private final HolidayRepository holidayRepository;
    private final OpdSetupStatus opdSetupStatus;
    private final StaffPinRepository staffPinRepository;
    private final AuditEventRepository auditEventRepository;
    private final OwnerGuard ownerGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SetupOverviewService(ClinicProfileRepository profileRepository, HolidayRepository holidayRepository,
                                 OpdSetupStatus opdSetupStatus, StaffPinRepository staffPinRepository,
                                 AuditEventRepository auditEventRepository,
                                 OwnerGuard ownerGuard, ObjectMapper objectMapper, Clock clock) {
        this.profileRepository = profileRepository;
        this.holidayRepository = holidayRepository;
        this.opdSetupStatus = opdSetupStatus;
        this.staffPinRepository = staffPinRepository;
        this.auditEventRepository = auditEventRepository;
        this.ownerGuard = ownerGuard;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record Check(String key, String label, String why, boolean done, boolean optional) {
    }

    public record Overview(int percent, List<Check> checks, AuditEventView lastChange) {
    }

    public record AuditEventView(UUID id, String actorName, String action, String entityType,
                                 Map<String, Object> before, Map<String, Object> after, Instant at) {
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID tenantId, UUID actorUserId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        ProfileRow profile = profileRepository.find(tenantId).orElse(null);
        OpdSetupStatus.Status opd = opdSetupStatus.statusFor(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(INDIA));

        List<Check> checks = new ArrayList<>();
        checks.add(new Check("profile", "Clinic name, address and phone", "Printed on every case paper and bill",
                profile != null && filled(profile.name()) && filled(profile.address()) && filled(profile.phone()),
                false));
        checks.add(new Check("holidays", "Holidays for the coming month",
                "Patients with bookings are told ahead of time",
                holidayRepository.countFrom(tenantId, today) > 0, false));
        checks.add(new Check("specialty", "Specialty template chosen",
                "Loads your starter price list for the specialty",
                profile != null && filled(profile.specialtyCode()), false));
        checks.add(new Check("services", "Service price list", "Stops prices being typed by hand at billing",
                opd.hasActiveService(), false));
        checks.add(new Check("fees", "Fees and billing settings", "Decides when the fee is collected and the follow-up window",
                opd.hasFeeSettings(), false));
        checks.add(new Check("paper", "Case paper settings saved", "Sets the paper size, language and sections that print",
                opd.hasCasePaperSettings(), false));
        // SAH-31 (ADR-010 D12, Q2): optional, so the setup percent of OPD-only clinics does not change.
        checks.add(new Check("pin", "Own PIN for each staff member", "So medicine checks show who did them",
                staffPinRepository.countActiveMembersWithoutPin(tenantId) == 0, true));

        long required = checks.stream().filter(check -> !check.optional()).count();
        long done = checks.stream().filter(check -> !check.optional() && check.done()).count();
        int percent = required == 0 ? 100 : (int) Math.round(done * 100.0 / required);

        AuditEventView last = auditEventRepository.findLatest(tenantId, 1).stream().findFirst()
                .map(this::toView).orElse(null);
        return new Overview(percent, checks, last);
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> auditEvents(UUID tenantId, UUID actorUserId, Integer limit) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        int count = limit == null ? DEFAULT_LIMIT : limit;
        if (count < 1 || count > MAX_LIMIT) {
            throw new InvalidInputException("limit", "Choose a number from 1 to " + MAX_LIMIT);
        }
        return auditEventRepository.findLatest(tenantId, count).stream().map(this::toView).toList();
    }

    private AuditEventView toView(AuditRow row) {
        return new AuditEventView(row.id(), row.actorName(), row.action(), row.entityType(),
                json(row.beforeJson()), json(row.afterJson()), row.at());
    }

    private Map<String, Object> json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
