package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentInput;
import com.sahaj.ipd.application.ScopeViews.WardAssignments;
import com.sahaj.ipd.application.ScopeViews.WardScopeInput;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.ipd.application.WardScopeRepository.Assignment;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/** SAH-34 story 34A, the data part: nurse ward assignments and the clinic switch. */
class WardAssignmentServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WARD_A = UUID.randomUUID();
    private static final UUID WARD_B = UUID.randomUUID();
    private static final UUID NURSE_1 = UUID.randomUUID();
    private static final UUID NURSE_2 = UUID.randomUUID();
    private static final UUID NURSE_3 = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-10T05:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WardScopeRepository repository;
    private WardRepository wards;
    private TeamMemberPort team;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ModuleEntitlementPort entitlements;
    private WardAssignmentService service;
    private final List<Assignment> live = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(WardScopeRepository.class);
        wards = mock(WardRepository.class);
        team = mock(TeamMemberPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        service = new WardAssignmentService(repository, wards, team, ownerGuard, auditLog,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(wards.findWards(TENANT)).thenReturn(List.of(new WardRow(WARD_A, "General ward", null),
                new WardRow(WARD_B, "ICU", null)));
        when(wards.findWard(TENANT, WARD_A)).thenReturn(Optional.of(new WardRow(WARD_A, "General ward", null)));
        when(wards.findWard(TENANT, WARD_B)).thenReturn(Optional.of(new WardRow(WARD_B, "ICU", null)));
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(
                new TeamMember(DOCTOR_M, "Dr Doctor", "DOCTOR", true, 0L),
                new TeamMember(NURSE_2, "Bina", "NURSE", true, 0L),
                new TeamMember(NURSE_1, "asha", "NURSE", true, 0L),
                new TeamMember(NURSE_3, "Chitra", "NURSE", true, 0L)));
        when(repository.liveAssignments(TENANT)).thenAnswer(call -> List.copyOf(live));
        when(repository.nurseWardScope(TENANT)).thenReturn(false);
    }

    private static JsonNode ids(UUID... ids) {
        var array = JSON.createArrayNode();
        for (UUID id : ids) {
            array.add(id.toString());
        }
        return array;
    }

    private static WardAssignmentInput body(UUID... ids) {
        return new WardAssignmentInput(ids(ids));
    }

    private static WardScopeInput scope(boolean on) {
        return new WardScopeInput(JSON.getNodeFactory().booleanNode(on));
    }

    // ---- get ----

    @Test
    void getAnswersTheSwitchEveryWardAndEveryActiveNurseByName() {
        live.add(new Assignment(WARD_A, NURSE_1));
        live.add(new Assignment(WARD_B, NURSE_1));
        live.add(new Assignment(WARD_A, NURSE_2));
        live.add(new Assignment(WARD_A, DOCTOR_M));

        WardAssignments view = service.get(TENANT, OWNER, "OWNER");

        assertThat(view.wardScope()).isFalse();
        assertThat(view.wards()).extracting(w -> w.wardName()).containsExactly("General ward", "ICU");
        assertThat(view.wards().get(0).nurseMembershipIds()).containsExactlyInAnyOrder(NURSE_1, NURSE_2);
        assertThat(view.wards().get(1).nurseMembershipIds()).containsExactly(NURSE_1);
        assertThat(view.nurses()).extracting(n -> n.name()).containsExactly("asha", "Bina", "Chitra");
        assertThat(view.nurses().get(0).wardIds()).containsExactlyInAnyOrder(WARD_A, WARD_B);
        assertThat(view.nursesWithoutWard()).isEqualTo(1);
        verifyNoInteractions(auditLog);
    }

    @Test
    void getIsOwnerOnlyByTokenAndMembershipAndAllowedInReadOnly() {
        assertThatThrownBy(() -> service.get(TENANT, OWNER, "NURSE")).isInstanceOf(AccessDeniedException.class);
        org.mockito.Mockito.doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, OWNER);
        assertThatThrownBy(() -> service.get(TENANT, OWNER, "OWNER")).isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.reset(ownerGuard);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        assertThat(service.get(TENANT, OWNER, "OWNER").wards()).hasSize(2);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.get(TENANT, OWNER, "OWNER")).isInstanceOf(ModuleOffException.class);
    }

    // ---- set the nurses of a ward ----

    @Test
    void setWardEndsTheRemovedAddsTheNewAndAuditsIdsOnly() {
        live.add(new Assignment(WARD_A, NURSE_1));
        live.add(new Assignment(WARD_A, NURSE_2));
        when(repository.endAssignment(TENANT, WARD_A, NURSE_2, OWNER)).thenReturn(true);

        service.setWard(TENANT, OWNER, "OWNER", WARD_A, body(NURSE_1, NURSE_3));

        InOrder order = inOrder(wards, ownerGuard, repository, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(ownerGuard).requireOwner(TENANT, OWNER);
        order.verify(repository).endAssignment(TENANT, WARD_A, NURSE_2, OWNER);
        order.verify(repository).insertAssignment(TENANT, WARD_A, NURSE_3, OWNER);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        order.verify(auditLog).record(eq("IPD_WARD_ASSIGNMENT_CHANGED"), eq("IPD_WARD_ASSIGNMENT"), eq(WARD_A),
                eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("wardId", WARD_A).containsEntry("added", List.of(NURSE_3))
                .containsEntry("removed", List.of(NURSE_2));
        verify(repository, never()).endAssignment(TENANT, WARD_A, NURSE_1, OWNER);
    }

    @Test
    void theSameSetWritesNothingAndAuditsNothing() {
        live.add(new Assignment(WARD_A, NURSE_1));
        live.add(new Assignment(WARD_B, NURSE_2));

        WardAssignments view = service.setWard(TENANT, OWNER, "OWNER", WARD_A, body(NURSE_1));

        assertThat(view.wards().get(0).nurseMembershipIds()).containsExactly(NURSE_1);
        verify(repository, never()).insertAssignment(any(), any(), any(), any());
        verify(repository, never()).endAssignment(any(), any(), any(), any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void anEmptySetRemovesEveryNurseOfTheWard() {
        live.add(new Assignment(WARD_A, NURSE_1));
        when(repository.endAssignment(TENANT, WARD_A, NURSE_1, OWNER)).thenReturn(true);

        service.setWard(TENANT, OWNER, "OWNER", WARD_A, body());

        verify(repository).endAssignment(TENANT, WARD_A, NURSE_1, OWNER);
        verify(auditLog).record(eq("IPD_WARD_ASSIGNMENT_CHANGED"), anyString(), eq(WARD_A), eq(null), any());
    }

    @Test
    void anIdThatIsNotAnActiveNurseOfTheClinicIsRefused() {
        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", WARD_A, body(DOCTOR_M)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("membershipIds");
                    assertThat(e.getMessage()).isEqualTo("Choose nurses from the list.");
                });
        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", WARD_A, body(UUID.randomUUID())))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).insertAssignment(any(), any(), any(), any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void theBodyIsCheckedBeforeReadOnlyAndBeforeAnyLock() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        JsonNode notArray = JSON.getNodeFactory().textNode("x");
        JsonNode repeated = ids(NURSE_1, NURSE_1);
        JsonNode notUuid = JSON.createArrayNode().add("nope");
        JsonNode tooMany = JSON.createArrayNode();
        for (int i = 0; i < 101; i++) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) tooMany).add(UUID.randomUUID().toString());
        }
        for (JsonNode bad : new JsonNode[] {null, notArray, repeated, notUuid, tooMany}) {
            assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", WARD_A, new WardAssignmentInput(bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("membershipIds"));
        }
        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", WARD_A, null))
                .isInstanceOf(InvalidInputException.class);
        verifyNoInteractions(ownerGuard);
        verify(wards, never()).lockTenant(any());
    }

    @Test
    void readOnlyIs409AfterTheBodyAndWritesNothing() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));

        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", WARD_A, body(NURSE_1)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        verify(wards, never()).lockTenant(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void anUnknownWardIs404() {
        UUID unknown = UUID.randomUUID();
        when(wards.findWard(TENANT, unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "OWNER", unknown, body(NURSE_1)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).insertAssignment(any(), any(), any(), any());
    }

    @Test
    void setWardIsOwnerOnly() {
        assertThatThrownBy(() -> service.setWard(TENANT, OWNER, "DOCTOR", WARD_A, body(NURSE_1)))
                .isInstanceOf(AccessDeniedException.class);
        verify(wards, never()).lockTenant(any());
    }

    // ---- the switch ----

    @Test
    void switchingOnIsRefusedWhileAnActiveNurseHasNoWard() {
        live.add(new Assignment(WARD_A, NURSE_1));
        live.add(new Assignment(WARD_B, NURSE_2));

        assertThatThrownBy(() -> service.setScope(TENANT, OWNER, "OWNER", scope(true)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("nurse-without-ward");
                    assertThat(e.getMessage()).isEqualTo("Assign a ward to every nurse before you switch this on.");
                });
        verify(repository, never()).upsertNurseWardScope(any(), eq(true), any(), any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void switchingOnWhenEveryNurseHasAWardStoresItAndAuditsBeforeAndAfter() {
        live.add(new Assignment(WARD_A, NURSE_1));
        live.add(new Assignment(WARD_B, NURSE_2));
        live.add(new Assignment(WARD_B, NURSE_3));

        service.setScope(TENANT, OWNER, "OWNER", scope(true));

        verify(repository).upsertNurseWardScope(TENANT, true, OWNER, NOW);
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_WARD_SCOPE_CHANGED"), eq("IPD_CONFIG"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("nurseWardScope", false);
        assertThat(after.getValue()).containsEntry("nurseWardScope", true);
    }

    @Test
    void switchingOffNeedsNoWardsAndTheSameValueWritesNothing() {
        service.setScope(TENANT, OWNER, "OWNER", scope(false));

        verify(repository, never()).upsertNurseWardScope(any(), eq(false), any(), any());
        verifyNoInteractions(auditLog);

        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        service.setScope(TENANT, OWNER, "OWNER", scope(false));
        verify(repository).upsertNurseWardScope(TENANT, false, OWNER, NOW);
    }

    @Test
    void theSwitchBodyMustBeAJsonBooleanAndIsCheckedBeforeReadOnly() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        for (JsonNode bad : new JsonNode[] {null, JSON.getNodeFactory().textNode("true"),
                JSON.getNodeFactory().numberNode(1)}) {
            assertThatThrownBy(() -> service.setScope(TENANT, OWNER, "OWNER", new WardScopeInput(bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("on");
                        assertThat(e.getMessage()).isEqualTo("Choose on or off");
                    });
        }
        assertThatThrownBy(() -> service.setScope(TENANT, OWNER, "OWNER", scope(true)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        assertThatThrownBy(() -> service.setScope(TENANT, OWNER, "FRONT_DESK", scope(true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theDefaultIsOffWhenTheClinicHasNeverSetIt() {
        assertThat(service.get(TENANT, OWNER, "OWNER").wardScope()).isFalse();
    }
}
