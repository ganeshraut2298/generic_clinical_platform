package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.PatientScope.Kind;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessGrant;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessInput;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPicker;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewResult;
import com.sahaj.ipd.application.WardScopeRepository.Facts;
import com.sahaj.ipd.application.WardScopeRepository.Grant;
import com.sahaj.ipd.application.WardScopeRepository.Stamped;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-34 stories 34E and 34F (AC 44 to 63, 64 to 77): the picker, the grant and the owner's review. The fake database
 * clock is the {@link Stamped} answer: the service sends no time and no duration.
 */
class EmergencyAccessServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID NURSE_MEMBERSHIP = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID BED = UUID.randomUUID();
    private static final UUID WARD_MINE = UUID.randomUUID();
    private static final UUID WARD_OTHER = UUID.randomUUID();
    private static final UUID GRANT = UUID.randomUUID();
    private static final Instant OPENED = Instant.parse("2026-11-10T05:00:00.123456Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-10T05:30:00.123456Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WardScopeRepository repository;
    private AdmissionRepository admissions;
    private ClinicalEventRepository events;
    private PatientLookupPort patients;
    private PatientAccessPort patientAccess;
    private TeamMemberPort team;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ModuleEntitlementPort entitlements;
    private ScopeGuard guard;
    private EmergencyAccessReader reader;
    private EmergencyAccessService service;
    private PatientScope scope;

    @BeforeEach
    void setUp() {
        repository = mock(WardScopeRepository.class);
        admissions = mock(AdmissionRepository.class);
        events = mock(ClinicalEventRepository.class);
        patients = mock(PatientLookupPort.class);
        patientAccess = mock(PatientAccessPort.class);
        team = mock(TeamMemberPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        guard = mock(ScopeGuard.class);
        reader = mock(EmergencyAccessReader.class);
        service = new EmergencyAccessService(repository, admissions, events, patients, patientAccess, team, ownerGuard,
                auditLog, new IpdGate(entitlements, Clock.fixed(OPENED, ZoneOffset.UTC)), guard, reader);

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(team.findActiveByUserId(TENANT, NURSE))
                .thenReturn(Optional.of(new TeamMember(NURSE_MEMBERSHIP, "Nurse", "NURSE", true, 0L)));
        scope = new PatientScope(Kind.WARDS, NURSE, "NURSE", "NURSE", NURSE_MEMBERSHIP, Set.of(WARD_MINE), Map.of());
        when(guard.scopeWithMembership(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(scope);
        when(patients.find(TENANT, PATIENT))
                .thenReturn(Optional.of(new PatientSummary(PATIENT, "Test Patient", 40, "F", "9000000000", false, "UH-1")));
    }

    private static JsonNode text(String value) {
        return JSON.getNodeFactory().textNode(value);
    }

    private static EmergencyAccessInput input(String admission, String reason, Boolean confirmed) {
        return new EmergencyAccessInput(admission == null ? null : text(admission), reason == null ? null : text(reason),
                confirmed == null ? null : JSON.getNodeFactory().booleanNode(confirmed));
    }

    private static EmergencyAccessInput valid() {
        return input(ADMISSION.toString(), "DETERIORATING", true);
    }

    private AdmissionRow admission(String status) {
        return new AdmissionRow(ADMISSION, "IP/26-27/0001", PATIENT, BED, "B1", "gen", "General ward",
                UUID.randomUUID(), "planned", "Self", "Under evaluation", status, OPENED, 0L);
    }

    private Facts facts(UUID ward) {
        return new Facts(ADMISSION, PATIENT, BED, "B1", ward, UUID.randomUUID(), "ADMITTED");
    }

    // ---- the picker ----

    @Test
    void pickerListsOnlyThePatientsOutsideHerScopeByBedCodeWithIdentityOnly() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID inWard = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of(
                new Facts(a, p1, UUID.randomUUID(), "B2", WARD_OTHER, UUID.randomUUID(), "ADMITTED"),
                new Facts(inWard, UUID.randomUUID(), UUID.randomUUID(), "A1", WARD_MINE, UUID.randomUUID(), "ADMITTED"),
                new Facts(b, p2, UUID.randomUUID(), "B10", WARD_OTHER, UUID.randomUUID(), "ADMITTED")));
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(
                p1, new PatientSummary(p1, "Asha", 30, "F", "9000000001", false, "UH-2"),
                p2, new PatientSummary(p2, "Ravi", 50, "M", "9000000002", false, null)));

        EmergencyAccessPicker picker = service.picker(TENANT, NURSE, "NURSE");

        assertThat(picker.items()).extracting(i -> i.admissionId()).containsExactly(b, a);
        assertThat(picker.items()).extracting(i -> i.bedCode()).containsExactly("B10", "B2");
        assertThat(picker.items().get(1).uhid()).isEqualTo("UH-2");
        assertThat(picker.items().get(0).uhid()).isNull();
        assertThat(picker.reasons()).extracting(r -> r.key())
                .containsExactly("DETERIORATING", "COVERING_BREAK", "DUTY_DOCTOR_ASKED");
        verifyNoInteractions(patientAccess, events);
    }

    @Test
    void pickerWritesOneAuditEventWithTheCountOnlyAndNoPatientIdAfterTheListIsBuilt() {
        UUID a = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of(
                new Facts(a, p1, UUID.randomUUID(), "B2", WARD_OTHER, UUID.randomUUID(), "ADMITTED"),
                new Facts(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "B3", WARD_OTHER, UUID.randomUUID(),
                        "ADMITTED")));
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of());

        service.picker(TENANT, NURSE, "NURSE");

        InOrder order = inOrder(repository, patients, auditLog);
        order.verify(repository).findAdmittedFacts(TENANT);
        order.verify(patients).findAll(eq(TENANT), anyCollection());
        order.verify(auditLog).record("IPD_EMERGENCY_ACCESS_PICKER_VIEWED", "IPD_EMERGENCY_ACCESS", null, null,
                Map.of("count", 2));
        verifyNoMoreInteractions(auditLog);
    }

    @Test
    void pickerIsEmptyWhenNothingIsOutsideHerScopeAndStillWritesTheAuditEventWithCountZero() {
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of(facts(WARD_MINE)));

        assertThat(service.picker(TENANT, NURSE, "NURSE").items()).isEmpty();
        verify(patients, never()).findAll(any(), anyCollection());
        verify(auditLog).record("IPD_EMERGENCY_ACCESS_PICKER_VIEWED", "IPD_EMERGENCY_ACCESS", null, null,
                Map.of("count", 0));
    }

    @Test
    void ifThePickerAuditWriteFailsTheRequestFailsAndNoListIsReturned() {
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of(facts(WARD_OTHER)));
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(auditLog)
                .record(eq("IPD_EMERGENCY_ACCESS_PICKER_VIEWED"), anyString(), any(), any(), any());

        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusedPickerRequestWritesNoAuditEvent() {
        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "DOCTOR")).isInstanceOf(ForbiddenException.class);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(ModuleOffException.class);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(guard.scopeWithMembership(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(PatientScope.all());
        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(auditLog);
    }

    @Test
    void thePickerRunsInOneRepeatableReadTransactionThatIsNotReadOnlyBecauseItWritesTheAuditRow() throws Exception {
        org.springframework.transaction.annotation.Transactional tx = EmergencyAccessService.class
                .getMethod("picker", UUID.class, UUID.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    @Test
    void pickerIsForAScopedNurseOnly() {
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR"}) {
            assertThatThrownBy(() -> service.picker(TENANT, NURSE, role)).as(role).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Emergency access is only for staff who see some wards.");
        }
        when(guard.scopeWithMembership(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(PatientScope.all());
        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(ForbiddenException.class)
                .hasMessage("Emergency access is only for staff who see some wards.");
        verify(repository, never()).findAdmittedFacts(any());
    }

    @Test
    void pickerAnswersToANurseWhoseMembershipIsNoLongerANurseWith403() {
        when(team.findActiveByUserId(TENANT, NURSE))
                .thenReturn(Optional.of(new TeamMember(NURSE_MEMBERSHIP, "Nurse", "DOCTOR", true, 0L)));

        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void pickerAndGrantAreAllowedInReadOnlyButNotWhenIpdIsOff() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of());
        assertThat(service.picker(TENANT, NURSE, "NURSE").items()).isEmpty();

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.picker(TENANT, NURSE, "NURSE")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(ModuleOffException.class);
    }

    // ---- open ----

    private void admitted(UUID ward) {
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED")));
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(ward)));
        when(repository.insertGrant(eq(TENANT), eq(ADMISSION), eq(BED), eq(NURSE), eq(NURSE_MEMBERSHIP), eq("NURSE"),
                anyString())).thenReturn(new Stamped(GRANT, OPENED, EXPIRES));
    }

    @Test
    void openWritesTheGrantTheEntryTheAccessRowAndTheAuditInOneOrderAndNoTime() {
        admitted(WARD_OTHER);

        EmergencyAccessGrant grant = service.open(TENANT, NURSE, "NURSE", valid());

        InOrder order = inOrder(admissions, repository, events, patientAccess, auditLog);
        order.verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        order.verify(repository).insertGrant(TENANT, ADMISSION, BED, NURSE, NURSE_MEMBERSHIP, "NURSE", "DETERIORATING");
        order.verify(events).insertForGrant(TENANT, ADMISSION,
                "Emergency access opened · Patient deteriorating, rapid response", NURSE, GRANT);
        order.verify(patientAccess).recordOpened(TENANT, PATIENT, NURSE);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        order.verify(auditLog).record(eq("IPD_EMERGENCY_ACCESS_OPENED"), eq("IPD_EMERGENCY_ACCESS"), eq(GRANT),
                eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("grantId", GRANT).containsEntry("admissionId", ADMISSION)
                .containsEntry("reasonKey", "DETERIORATING").containsEntry("expiresAt", EXPIRES.toString())
                .doesNotContainKey("patientName");
        assertThat(grant.id()).isEqualTo(GRANT);
        assertThat(grant.patientName()).isEqualTo("Test Patient");
        assertThat(grant.bedCode()).isEqualTo("B1");
        assertThat(grant.reasonLabel()).isEqualTo("Patient deteriorating, rapid response");
        assertThat(grant.openedAt()).isEqualTo(OPENED);
        assertThat(grant.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void openRefusesAPatientAlreadyInHerWardsAndWritesNothing() {
        admitted(WARD_MINE);

        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(ConflictException.class)
                .hasMessage("This patient is already in your list.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("already-in-scope"));
        verify(repository, never()).insertGrant(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(events, patientAccess, auditLog);
    }

    @Test
    void openRefusesWhenSheAlreadyHasALiveGrantAndKeepsTheExpiry() {
        admitted(WARD_OTHER);
        when(repository.hasLiveGrant(TENANT, NURSE_MEMBERSHIP, "NURSE", ADMISSION)).thenReturn(true);

        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(ConflictException.class);
        verify(repository, never()).insertGrant(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(events, patientAccess, auditLog);
    }

    @Test
    void openAnswers404ForAnUnknownOrDischargedAdmission() {
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(NotFoundException.class);

        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.of(admission("DISCHARGED")));
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(NotFoundException.class)
                .hasMessage("This patient is not admitted any more.");
        verifyNoInteractions(events, patientAccess, auditLog);
    }

    @Test
    void openReportsEveryFieldErrorTogetherInOrderAndStoresNothing() {
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", input(null, null, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(m -> m.field() + ": " + m.message()).containsExactly(
                                "admissionId: Choose a patient.", "reasonKey: Choose a reason for emergency access.",
                                "confirmed: Tick the box to confirm this access is recorded."));
        verifyNoInteractions(admissions, repository, events, patientAccess, auditLog);
    }

    @Test
    void openRefusesAReasonOutsideTheListAndAConfirmationThatIsNotTheJsonTrue() {
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE",
                input(ADMISSION.toString(), "OTHER", true))).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(m -> m.field()).containsExactly("reasonKey"));
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE",
                input(ADMISSION.toString(), "COVERING_BREAK", false))).isInstanceOfSatisfying(
                        InvalidFieldsException.class, e -> assertThat(e.errors()).extracting(m -> m.field())
                                .containsExactly("confirmed"));
        EmergencyAccessInput stringTrue = new EmergencyAccessInput(text(ADMISSION.toString()), text("COVERING_BREAK"),
                text("true"));
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", stringTrue))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", input("not-a-uuid", "COVERING_BREAK", true)))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(m -> m.field()).containsExactly("admissionId"));
    }

    @Test
    void openIsRefusedForEveryoneButAScopedNurse() {
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR"}) {
            assertThatThrownBy(() -> service.open(TENANT, NURSE, role, valid())).as(role)
                    .isInstanceOf(ForbiddenException.class);
        }
        when(guard.scopeWithMembership(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(PatientScope.all());
        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(admissions, events, patientAccess, auditLog);
    }

    @Test
    void openFailureInAnyWriteLeavesNoOtherWriteBehindBecauseTheyShareOneTransaction() {
        admitted(WARD_OTHER);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(auditLog)
                .record(anyString(), anyString(), any(), any(), any());

        assertThatThrownBy(() -> service.open(TENANT, NURSE, "NURSE", valid())).isInstanceOf(IllegalStateException.class);
        // The service is @Transactional: the exception rolls the grant, the entry and the access row back together.
        assertThat(EmergencyAccessService.class.getMethods()).anySatisfy(m -> {
            if (m.getName().equals("open")) {
                assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
                assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation())
                        .isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
            }
        });
    }

    // ---- the owner's list ----

    @Test
    void listIsOwnerOnlyChecksLimitAndCursorBeforeTheMembershipAndWritesNothing() {
        EmergencyAccessReviewPage page = new EmergencyAccessReviewPage(OPENED, 3, List.of(), null);
        when(repository.databaseNow()).thenReturn(OPENED);
        when(reader.page(eq(TENANT), eq(OPENED), eq(25), eq(null))).thenReturn(page);

        assertThat(service.list(TENANT, OWNER, "OWNER", null, null)).isSameAs(page);

        assertThatThrownBy(() -> service.list(TENANT, OWNER, "NURSE", null, null))
                .isInstanceOf(AccessDeniedException.class);
        for (String limit : new String[] {"0", "26", "x", "-1", "1000"}) {
            assertThatThrownBy(() -> service.list(TENANT, OWNER, "OWNER", limit, null)).as(limit)
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("limit"));
        }
        assertThatThrownBy(() -> service.list(TENANT, OWNER, "OWNER", null, "garbage!"))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("cursor"));
        verifyNoInteractions(auditLog, patientAccess, events);
    }

    @Test
    void listPassesTheDecodedCursorAndTheLimit() {
        when(repository.databaseNow()).thenReturn(OPENED);
        UUID id = UUID.randomUUID();
        String cursor = GrantCursor.encode(OPENED, id);

        service.list(TENANT, OWNER, "OWNER", "10", cursor);

        ArgumentCaptor<GrantCursor.Position> after = ArgumentCaptor.forClass(GrantCursor.Position.class);
        verify(reader).page(eq(TENANT), eq(OPENED), eq(10), after.capture());
        assertThat(after.getValue().openedAt()).isEqualTo(OPENED);
        assertThat(after.getValue().id()).isEqualTo(id);
        verify(ownerGuard).requireOwner(TENANT, OWNER);
    }

    // ---- review ----

    private Grant grant(UUID grantee) {
        return new Grant(GRANT, ADMISSION, PATIENT, BED, "B1", grantee, NURSE_MEMBERSHIP, "NURSE", "DETERIORATING",
                OPENED, EXPIRES, null, "ADMITTED");
    }

    @Test
    void reviewRecordsOnceWritesTheEntryAndTheAuditWithNoName() {
        when(repository.findGrant(TENANT, GRANT)).thenReturn(Optional.of(grant(NURSE)));
        Instant reviewedAt = Instant.parse("2026-11-10T06:00:00Z");
        when(repository.markReviewed(TENANT, GRANT, OWNER)).thenReturn(Optional.of(reviewedAt));
        when(repository.countUnreviewed(TENANT)).thenReturn(2);

        EmergencyAccessReviewResult result = service.review(TENANT, OWNER, "OWNER", GRANT);

        assertThat(result).isEqualTo(new EmergencyAccessReviewResult(GRANT, reviewedAt, 2));
        verify(ownerGuard).requireOwner(TENANT, OWNER);
        verify(events).insertForGrant(TENANT, ADMISSION, "Reviewed emergency access", OWNER, GRANT);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_EMERGENCY_ACCESS_REVIEWED"), eq("IPD_EMERGENCY_ACCESS"), eq(GRANT), eq(null),
                after.capture());
        assertThat(after.getValue()).containsOnlyKeys("grantId", "admissionId");
        verifyNoInteractions(patientAccess);
    }

    @Test
    void reviewByTheGranteeHerselfIs403BeforeAnyUpdate() {
        when(repository.findGrant(TENANT, GRANT)).thenReturn(Optional.of(grant(OWNER)));

        assertThatThrownBy(() -> service.review(TENANT, OWNER, "OWNER", GRANT)).isInstanceOf(ForbiddenException.class)
                .hasMessage("You cannot review your own emergency access.");
        verify(repository, never()).markReviewed(any(), any(), any());
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void aSecondReviewIs409AlreadyReviewedAndWritesNothing() {
        when(repository.findGrant(TENANT, GRANT)).thenReturn(Optional.of(grant(NURSE)));
        when(repository.markReviewed(TENANT, GRANT, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(TENANT, OWNER, "OWNER", GRANT)).isInstanceOf(ConflictException.class)
                .hasMessage("Already reviewed.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("already-reviewed"));
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void aGrantOfAnotherClinicOrUnknownIs404() {
        when(repository.findGrant(TENANT, GRANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(TENANT, OWNER, "OWNER", GRANT)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).markReviewed(any(), any(), any());
    }

    @Test
    void reviewIsOwnerOnlyByTokenAndMembershipAndAllowedInReadOnly() {
        assertThatThrownBy(() -> service.review(TENANT, OWNER, "DOCTOR", GRANT)).isInstanceOf(AccessDeniedException.class);
        org.mockito.Mockito.doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, OWNER);
        assertThatThrownBy(() -> service.review(TENANT, OWNER, "OWNER", GRANT)).isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(events, auditLog);

        org.mockito.Mockito.reset(ownerGuard);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        when(repository.findGrant(TENANT, GRANT)).thenReturn(Optional.of(grant(NURSE)));
        when(repository.markReviewed(TENANT, GRANT, OWNER)).thenReturn(Optional.of(OPENED));
        assertThat(service.review(TENANT, OWNER, "OWNER", GRANT).grantId()).isEqualTo(GRANT);
    }
}
