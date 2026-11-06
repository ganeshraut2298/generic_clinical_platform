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
        assembler = new MedicineAssembler(catalogue, fake, doses, patients, patientAccess, actors, admissionService);

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
}
