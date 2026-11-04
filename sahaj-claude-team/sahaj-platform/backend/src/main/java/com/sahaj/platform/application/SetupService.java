package com.sahaj.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.OpdDefaultsPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.PlanLimits;
import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.SetupProgress;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clinic setup wizard (SAH-4): five steps saved one at a time, resumable, and a final
 * transaction that writes the real rows the rest of the app reads. Every public method here
 * already runs with the owner's tenant bound (the request is authenticated before it reaches
 * this service - unlike SAH-3's bootstrap, there is no {@code AuthenticationScope} to thread
 * through).
 */
@Service
public class SetupService {

    /** Mockup's reserved web addresses (SAH-4 technical design): never offered or accepted. */
    private static final Set<String> RESERVED_SLUGS =
            Set.of("apollo", "care", "demo", "clinic", "city", "lifeline", "sai", "admin", "www");

    private static final String STEP_CLINIC = "clinic";
    private static final String STEP_SPECIALTY = "specialty";
    private static final String STEP_TEAM = "team";
    private static final String STEP_OPD = "opd";
    private static final String STEP_LOOK = "look";

    private final SetupProgressRepository progressRepository;
    private final ClinicProfileRepository clinicProfileRepository;
    private final DoctorProfileRepository doctorProfileRepository;
    private final MembershipRepository membershipRepository;
    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final TeamRepository teamRepository;
    private final SpecialtyService specialtyService;
    private final OpdDefaultsPort opdDefaultsPort;
    private final AuditLog auditLog;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SetupService(SetupProgressRepository progressRepository, ClinicProfileRepository clinicProfileRepository,
                         DoctorProfileRepository doctorProfileRepository, MembershipRepository membershipRepository,
                         AppUserRepository appUserRepository, TenantRepository tenantRepository,
                         TeamRepository teamRepository, SpecialtyService specialtyService,
                         OpdDefaultsPort opdDefaultsPort, AuditLog auditLog, Validator validator,
                         ObjectMapper objectMapper, Clock clock) {
        this.progressRepository = progressRepository;
        this.clinicProfileRepository = clinicProfileRepository;
        this.doctorProfileRepository = doctorProfileRepository;
        this.membershipRepository = membershipRepository;
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.teamRepository = teamRepository;
        this.specialtyService = specialtyService;
        this.opdDefaultsPort = opdDefaultsPort;
        this.auditLog = auditLog;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SetupState getState(UUID tenantId) {
        SetupProgress progress = progressRepository.find(tenantId).orElse(new SetupProgress(1, Map.of(), null));
        return new SetupState(progress.currentStep(), progress.answers(), PlanLimits.MAX_DOCTORS, PlanLimits.MAX_USERS);
    }

    /** AC1 to AC11: validates one step's shape and business rules, then saves and audits it. */
    @Transactional
    public SetupState saveStep(UUID tenantId, int step, JsonNode body) {
        String stepKey;
        Object input;
        switch (step) {
            case 1:
                stepKey = STEP_CLINIC;
                input = convert(body, SetupClinicInput.class);
                break;
            case 2:
                stepKey = STEP_SPECIALTY;
                input = convert(body, SetupSpecialtyInput.class);
                break;
            case 3:
                SetupTeamInput team = convert(body, SetupTeamInput.class);
                validate(team);
                int totalUsers = team.doctors().size() + team.staff().size();
                if (totalUsers > PlanLimits.MAX_USERS) {
                    throw new PlanLimitException(
                            "The free plan allows up to " + PlanLimits.MAX_USERS + " users in total.");
                }
                saveAndAudit(tenantId, step, STEP_TEAM, team);
                return getState(tenantId);
            case 4:
                stepKey = STEP_OPD;
                input = convert(body, SetupOpdInput.class);
                break;
            case 5:
                stepKey = STEP_LOOK;
                input = convert(body, SetupLookInput.class);
                break;
            default:
                throw new SetupStepInvalidException("Step must be 1 to 5.");
        }
        validate(input);
        saveAndAudit(tenantId, step, stepKey, input);
        return getState(tenantId);
    }

    private void saveAndAudit(UUID tenantId, int step, String stepKey, Object input) {
        Map<String, Object> stepAnswers = objectMapper.convertValue(input,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        progressRepository.saveStep(tenantId, step, stepKey, stepAnswers);
        auditLog.record("SETUP_STEP_SAVED", "SETUP_PROGRESS", tenantId, null, Map.of("step", step));
    }

    /** AC3, AC11: reserved words never pass; the real check is a cross-tenant database function. */
    @Transactional(readOnly = true)
    public SlugAvailability checkSlugAvailability(UUID tenantId, String slug) {
        if (isReservedOrUnavailable(slug)) {
            return new SlugAvailability(false, alternativesFor(tenantId, slug));
        }
        return new SlugAvailability(true, List.of());
    }

    private boolean isReservedOrUnavailable(String slug) {
        return RESERVED_SLUGS.contains(slug) || !tenantRepository.isSlugAvailable(slug);
    }

    private List<String> alternativesFor(UUID tenantId, String slug) {
        List<String> candidates = new ArrayList<>();
        String city = cityHint(tenantId);
        if (city != null && !city.isBlank()) {
            candidates.add(slugify(slug + "-" + city));
        }
        for (int suffix = 2; suffix <= 9 && candidates.size() < 5; suffix++) {
            candidates.add(slugify(slug + "-" + suffix));
        }
        List<String> alternatives = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.matches("^[a-z0-9][a-z0-9-]{2,23}$") && !isReservedOrUnavailable(candidate)) {
                alternatives.add(candidate);
            }
            if (alternatives.size() == 3) {
                break;
            }
        }
        return alternatives;
    }

    private String cityHint(UUID tenantId) {
        Object clinicAnswers = progressRepository.find(tenantId).map(SetupProgress::answers)
                .map(answers -> answers.get(STEP_CLINIC)).orElse(null);
        if (clinicAnswers instanceof Map<?, ?> map && map.get("city") instanceof String city) {
            return slugify(city);
        }
        return null;
    }

    private static String slugify(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-");
    }

    /** AC1, AC12, AC13: writes the real rows in one transaction; calling it twice changes nothing more. */
    @Transactional
    public SetupCompleteResult complete(UUID tenantId, UUID ownerUserId) {
        SetupProgress progress = progressRepository.find(tenantId)
                .orElseThrow(() -> new SetupIncompleteException("Finish every step before creating your clinic."));
        if (progress.isComplete()) {
            return tenantRepository.findProfile(tenantId)
                    .map(profile -> new SetupCompleteResult(profile.brand()))
                    .orElseThrow(() -> new SetupIncompleteException("Finish every step before creating your clinic."));
        }
        Map<String, Object> answers = progress.answers();
        if (!answers.containsKey(STEP_CLINIC) || !answers.containsKey(STEP_SPECIALTY)
                || !answers.containsKey(STEP_TEAM) || !answers.containsKey(STEP_OPD) || !answers.containsKey(STEP_LOOK)) {
            throw new SetupIncompleteException("Finish every step before creating your clinic.");
        }

        SetupClinicInput clinic = objectMapper.convertValue(answers.get(STEP_CLINIC), SetupClinicInput.class);
        SetupSpecialtyInput specialty = objectMapper.convertValue(answers.get(STEP_SPECIALTY), SetupSpecialtyInput.class);
        SetupTeamInput team = objectMapper.convertValue(answers.get(STEP_TEAM), SetupTeamInput.class);
        SetupOpdInput opd = objectMapper.convertValue(answers.get(STEP_OPD), SetupOpdInput.class);
        SetupLookInput look = objectMapper.convertValue(answers.get(STEP_LOOK), SetupLookInput.class);

        if (team.doctors().size() + team.staff().size() > PlanLimits.MAX_USERS) {
            throw new PlanLimitException("The free plan allows up to " + PlanLimits.MAX_USERS + " users in total.");
        }
        if (isReservedOrUnavailable(look.slug())) {
            throw new SlugTakenException("That web address is already taken. Choose another.");
        }

        try {
            tenantRepository.claimSlugAndBrand(tenantId, clinic.name(), look.brand(), look.slug());
        } catch (DataIntegrityViolationException ex) {
            throw new SlugTakenException("That web address was just taken. Choose another.");
        }

        clinicProfileRepository.upsert(tenantId, clinic.name(), clinic.address(), clinic.city(), clinic.pin(),
                clinic.phone(), clinic.registrationNo(), specialty.code(), look.brand(), look.language());

        specialtyService.seedStarterServices(tenantId, ownerUserId, specialty.code());

        saveDoctors(tenantId, ownerUserId, team.doctors());
        saveStaff(tenantId, team.staff());

        opdDefaultsPort.saveDefaults(tenantId, opd.feeTiming(), opd.queue(), opd.vitals(), opd.followUpDays(),
                opd.followUpFee());

        progressRepository.markCompleted(tenantId, clock.instant());
        auditLog.record("SETUP_COMPLETED", "TENANT", tenantId, null, Map.of("brand", look.brand()));
        return new SetupCompleteResult(look.brand());
    }

    private void saveDoctors(UUID tenantId, UUID ownerUserId, List<SetupTeamInput.Doctor> doctors) {
        for (int i = 0; i < doctors.size(); i++) {
            SetupTeamInput.Doctor doctor = doctors.get(i);
            long feePaise = doctor.feeRupees() * 100L;
            UUID membershipId;
            if (i == 0) {
                membershipId = membershipRepository.findByTenantAndUser(tenantId, ownerUserId)
                        .map(Membership::id)
                        .orElseThrow(() -> new SetupIncompleteException("The clinic owner's membership was not found."));
                teamRepository.update(membershipId, null, doctor.name(), ownerUserId);
            } else {
                if (doctor.mobile() == null || doctor.mobile().isBlank()) {
                    throw new SetupStepInvalidException("Enter a 10-digit mobile number for the second doctor.");
                }
                AppUser appUser = findOrCreateUser(doctor.mobile(), doctor.name());
                membershipId = UUID.randomUUID();
                membershipRepository.insert(membershipId, tenantId, appUser.id(), MembershipRole.DOCTOR, null);
            }
            doctorProfileRepository.insert(membershipId, tenantId, doctor.specialty(), feePaise, null, null);
        }
    }

    private void saveStaff(UUID tenantId, List<SetupTeamInput.Staff> staff) {
        for (SetupTeamInput.Staff member : staff) {
            AppUser appUser = findOrCreateUser(member.mobile(), member.name());
            membershipRepository.insert(UUID.randomUUID(), tenantId, appUser.id(),
                    MembershipRole.valueOf(member.role()), null);
        }
    }

    private AppUser findOrCreateUser(String mobile, String fullName) {
        return appUserRepository.findByMobile(mobile).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            appUserRepository.insert(id, mobile, fullName);
            return new AppUser(id, mobile, fullName);
        });
    }

    private <T> T convert(JsonNode body, Class<T> type) {
        try {
            return objectMapper.treeToValue(body, type);
        } catch (Exception ex) {
            throw new SetupStepInvalidException("This step's answers could not be read.");
        }
    }

    private void validate(Object input) {
        Set<ConstraintViolation<Object>> violations = new LinkedHashSet<>(validator.validate(input));
        if (!violations.isEmpty()) {
            throw new jakarta.validation.ConstraintViolationException(violations);
        }
    }
}
