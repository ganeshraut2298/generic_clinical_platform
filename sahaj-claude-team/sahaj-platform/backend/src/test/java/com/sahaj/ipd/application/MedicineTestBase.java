package com.sahaj.ipd.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionService.EmrHeader;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared set-up of the SAH-31 service tests: an in-memory order repository, mocks of everything else, one admitted
 * patient with a bed, and a team in which each user has a membership role the test can change. No database.
 */
abstract class MedicineTestBase {

    static final UUID TENANT = UUID.randomUUID();
    static final UUID DOCTOR = UUID.randomUUID();
    static final UUID OTHER_DOCTOR = UUID.randomUUID();
    static final UUID PHARMACIST = UUID.randomUUID();
    static final UUID OTHER_PHARMACIST = UUID.randomUUID();
    static final UUID NURSE = UUID.randomUUID();
    static final UUID ADMISSION = UUID.randomUUID();
    static final UUID PATIENT = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    FakeMedicineOrders fake;
    FakeDoses doses;
    AdmissionRepository admissions;
    ClinicalEventRepository events;
    PatientClinicalPort patients;
    PatientAccessPort patientAccess;
    ActorDirectoryPort actors;
    TeamMemberPort team;
    AuditLog auditLog;
    ModuleEntitlementPort entitlements;
    AdmissionService admissionService;
    MedicineCatalogue catalogue;
    MedicineAssembler assembler;
    FakeOrderNotices notices;
    FakePharmacistReviews reviewRepo;
    NursingSettingsReader nursingSettings;
    /** The nursing values the tests run with: the built defaults (verification on); a test may replace them. */
    com.sahaj.ipd.domain.EffectiveNursingSettings settingsInForce = com.sahaj.ipd.domain.EffectiveNursingSettings.defaults();
    IpdAudit ipdAudit;
    IpdGate gate;

    /** The membership role of each user; a user who is absent has no active membership. */
    final Map<UUID, String> memberRoles = new HashMap<>();
    /** The stable membership id of each user (the services compare membership ids). */
    final Map<UUID, UUID> membershipIds = new HashMap<>();
    /** The clinic's team by membership id, for the second-nurse list and lookups. */
    final Map<UUID, TeamMember> roster = new java.util.LinkedHashMap<>();
    boolean memberActive = true;
    String allergyText = "None known";
    boolean patientFound = true;
    AdmissionRow admission;
    /** SAH-34: a guard that reaches every patient; a test replaces its answer to prove a refusal. */
    ScopeGuard guard = ScopeStubs.unscoped();

    @BeforeEach
    void baseSetUp() {
        fake = new FakeMedicineOrders();
        admissions = mock(AdmissionRepository.class);
        events = mock(ClinicalEventRepository.class);
        patients = mock(PatientClinicalPort.class);
        patientAccess = mock(PatientAccessPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        admissionService = mock(AdmissionService.class);
        catalogue = new MedicineCatalogue(SeedCatalogue.catalogue());
        gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        doses = new FakeDoses();
        notices = new FakeOrderNotices();
        reviewRepo = new FakePharmacistReviews();
        nursingSettings = new NursingSettingsReader() {
            @Override
            public com.sahaj.ipd.domain.EffectiveNursingSettings effectiveFor(UUID tenantId, UUID admissionId) {
                return settingsInForce;
            }

            @Override
            public com.sahaj.ipd.domain.EffectiveNursingSettings clinicDefaults(UUID tenantId) {
                return settingsInForce;
            }
        };
        ipdAudit = new IpdAudit(auditLog, new AuditDevice());
        assembler = new MedicineAssembler(catalogue, fake, doses, patients, patientAccess, actors, admissionService,
                fake, fake, notices, nursingSettings, reviewRepo);

        memberRoles.put(DOCTOR, "DOCTOR");
        memberRoles.put(OTHER_DOCTOR, "DOCTOR");
        memberRoles.put(PHARMACIST, "PHARMACIST");
        memberRoles.put(OTHER_PHARMACIST, "PHARMACIST");
        memberRoles.put(NURSE, "NURSE");

        admission = admissionRow("ADMITTED");
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(team.findActiveByUserId(eq(TENANT), any())).thenAnswer(call -> {
            String role = memberRoles.get(call.<UUID>getArgument(1));
            return role == null ? Optional.empty()
                    : Optional.of(new TeamMember(membership(call.<UUID>getArgument(1)), "Member", role, memberActive, 0L));
        });
        when(team.find(eq(TENANT), any())).thenAnswer(call -> Optional.ofNullable(roster.get(call.<UUID>getArgument(1))));
        when(team.findActiveMembers(eq(TENANT))).thenAnswer(call -> new java.util.ArrayList<>(roster.values()));
        when(admissions.findForNoKeyUpdate(eq(TENANT), eq(ADMISSION))).thenAnswer(call -> Optional.of(admission));
        when(admissions.find(eq(TENANT), eq(ADMISSION))).thenAnswer(call -> Optional.of(admission));
        when(patients.find(eq(TENANT), eq(PATIENT))).thenAnswer(call -> patientFound
                ? Optional.of(new PatientClinical(PATIENT, "UH-000123", "Test Patient", 54, "F", allergyText))
                : Optional.empty());
        when(patients.findAll(eq(TENANT), anyCollection())).thenAnswer(call -> patientFound
                ? Map.of(PATIENT, new PatientClinical(PATIENT, "UH-000123", "Test Patient", 54, "F", allergyText))
                : Map.of());
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(DOCTOR, "Dr Meera Joshi",
                OTHER_DOCTOR, "Dr Asha Rao", PHARMACIST, "Ph. Ravi Kulkarni", OTHER_PHARMACIST, "Ph. Sunita Patil"));
        when(actors.displayName(eq(TENANT), any())).thenAnswer(call -> Optional.ofNullable(Map.of(DOCTOR,
                "Dr Meera Joshi", OTHER_DOCTOR, "Dr Asha Rao", PHARMACIST, "Ph. Ravi Kulkarni", OTHER_PHARMACIST,
                "Ph. Sunita Patil").get(call.<UUID>getArgument(1))));
        when(admissionService.emrHeader(eq(TENANT), any())).thenReturn(new EmrHeader("Dr Meera Joshi", 3));
        fake.patientOfAdmission.put(ADMISSION, PATIENT);
        fake.bedOfAdmission.put(ADMISSION, "202");
    }

    static AdmissionRow admissionRow(String status) {
        return new AdmissionRow(ADMISSION, "IP-26-27-0001", PATIENT, UUID.randomUUID(), "202", "general", "General",
                UUID.randomUUID(), "planned", "Self", "Chest infection", status, NOW.minusSeconds(86_400), 0,
                "DISCHARGED".equals(status) ? NOW.minusSeconds(60) : null);
    }

    UUID membership(UUID user) {
        return membershipIds.computeIfAbsent(user, k -> UUID.randomUUID());
    }

    void ipdState(String state) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(state, false, 25));
    }

    /**
     * Appends a version written by {@code author} (ADR-027 A9: the author of the current version). The head keeps its
     * current_version; the checks under test read the timeline.
     */
    void appendVersionBy(MedicineOrderRepository.OrderRow order, UUID author) {
        java.util.List<com.sahaj.ipd.domain.order.MedicineOrderVersion> versions = fake.versionsOf(order.id());
        com.sahaj.ipd.domain.order.MedicineOrderVersion last = versions.get(versions.size() - 1);
        versions.add(new com.sahaj.ipd.domain.order.MedicineOrderVersion(order.id(), last.versionNo() + 1, last.kind(),
                last.frequencyKey(), last.doseAmount(), last.doseUnit(), last.routeKey(), last.startAt(), last.endAt(),
                last.indication(), last.priority(), last.minIntervalMin(), last.maxPer24h(), "Changed", author,
                last.createdAt(), null, last.verification(), null, null));
    }

    /** The order service over the in-memory repositories of this base (the one constructor every test uses). */
    MedicineOrderService newOrderService() {
        return new MedicineOrderService(fake, admissions, events, patients, patientAccess, actors, team, ipdAudit, gate,
                catalogue, assembler, guard, fake, fake, fake, notices, reviewRepo, nursingSettings);
    }

    OrderChangeService newChangeService() {
        return new OrderChangeService(fake, admissions, patients, patientAccess, team, events, ipdAudit, gate, assembler,
                guard, fake, fake, fake, notices, nursingSettings);
    }

    PharmacistQueryService newQueryService() {
        return new PharmacistQueryService(fake, admissions, patients, patientAccess, team, events, ipdAudit, gate,
                assembler, guard, fake, fake);
    }

    PharmacistReviewService newReviewService() {
        return new PharmacistReviewService(reviewRepo, fake, admissions, patients, patientAccess, actors, team, events,
                ipdAudit, gate, assembler, fake);
    }

    /**
     * The SAH-31 order body with the SAH-76 fields filled in as a doctor would on a plain order (1 mg, oral, routine,
     * until stopped), so the SAH-31 tests keep their meaning: the five arguments are the SAH-31 ones.
     */
    static MedicineViews.MedicineOrderInput legacyOrder(String clientKey, String drugKey, String frequencyKey,
                                                        String overrideReasonKey, String checkToken) {
        return new MedicineViews.MedicineOrderInput(clientKey, drugKey, frequencyKey, overrideReasonKey, checkToken,
                java.math.BigDecimal.ONE, "MG", "ORAL", null, null, null, "ROUTINE", null, null);
    }

    /** The verify body of version 1 (the SAH-76 shape): the PIN, the check token and the version seen. */
    static MedicineViews.VerifyOrderInput verifyV1(String pin, String checkToken) {
        return new MedicineViews.VerifyOrderInput(pin, checkToken, 1);
    }

    /** The stop body of version 1 with a reason of four or more characters. */
    static MedicineViews.StopOrderInput stopV1() {
        return new MedicineViews.StopOrderInput(UUID.randomUUID().toString(), 1, "No longer needed");
    }
}
