package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrEmergencyPort.TriageLevel;
import com.sahaj.opd.api.EmrEmergencyPort.TriageScale;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.emr.EmrInputs.VisitTypeInput;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-67-4 and SAH-68-2 (ADR-025 D11, D14, H11): EMERGENCY needs a level of the clinic's scale; the fee is worked out again
 * only while the bill has no payment; a visit whose record is signed refuses the change; the audit keeps the type, level and
 * fee before and after.
 */
@SuppressWarnings("unchecked")
class EmrVisitTypeEmergencyTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final VisitService visitService = mock(VisitService.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrVisitChargePort charges = mock(EmrVisitChargePort.class);
    private final EmrEmergencyPort emergency = mock(EmrEmergencyPort.class);
    private EmrVisitTypeService service;

    private static VisitRow visit(String type, Integer level, long fee) {
        return new VisitRow(VISIT, PATIENT, MEMBER, type, "WITH_DOCTOR", 7, fee, Instant.parse("2026-11-12T04:00:00Z"), 1, 1,
                null, "NONE", level, level == null ? null : 3);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @BeforeEach
    void setUp() {
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 80000);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30, "F",
                "9000000000", false)));
        when(emergency.triageScale(TENANT)).thenReturn(new TriageScale(3, true, List.of(
                new TriageLevel(1, "Red · Immediate", "", "crit"), new TriageLevel(2, "Amber · Urgent", "", "warn"),
                new TriageLevel(3, "Green · Can wait", "", "good"))));
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(visitService.withTriageNames(eq(TENANT), any(VisitView.class))).thenAnswer(call -> call.getArgument(1));
        service = new EmrVisitTypeService(access, visits, patients, team, audit, visitService, records, provider(charges),
                provider(emergency));
    }

    @Test
    void emergencyWithALevelOfTheScaleStoresTheLevelAndScaleAndAuditsBeforeAndAfter() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("NEW", null, 80000)), Optional.of(visit("NEW", null, 80000)),
                Optional.of(visit("EMERGENCY", 1, 80000)));
        when(charges.hasPayment(TENANT, VISIT)).thenReturn(false);
        when(visitService.feeForType(eq(TENANT), any(VisitRow.class), eq("EMERGENCY"))).thenReturn(80000L);

        VisitView view = service.change(DOCTOR, VISIT, new VisitTypeInput("EMERGENCY", 1));

        assertThat(view.visitType()).isEqualTo("EMERGENCY");
        assertThat(view.triageLevel()).isEqualTo(1);
        assertThat(view.feeChange().outcome()).isEqualTo("UNCHANGED");
        verify(visits).updateVisitTypeAndTriage(TENANT, VISIT, "EMERGENCY", 1, 3, USER);
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("visitType", "EMERGENCY");
        after.put("triageLevel", 1);
        after.put("feePaise", 80000L);
        verify(audit).record(eq("EMR_VISIT_TYPE_CHANGED"), eq("OPD_VISIT"), eq(VISIT), any(), eq(after));
    }

    @Test
    void emergencyWithoutALevelOrWithAnotherLevelAndALevelOnAnotherTypeAreValidationErrors() {
        for (VisitTypeInput bad : List.of(new VisitTypeInput("EMERGENCY", null), new VisitTypeInput("EMERGENCY", 4),
                new VisitTypeInput("NEW", 1))) {
            assertThatThrownBy(() -> service.change(DOCTOR, VISIT, bad)).isInstanceOf(InvalidInputException.class);
        }
        verify(visits, never()).updateVisitTypeAndTriage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void whileTheBillHasNoPaymentTheFeeIsWorkedOutAgainAndSaved() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("NEW", null, 80000)), Optional.of(visit("NEW", null, 80000)),
                Optional.of(visit("FOLLOW_UP", null, 40000)));
        when(charges.hasPayment(TENANT, VISIT)).thenReturn(false);
        when(visitService.feeForType(eq(TENANT), any(VisitRow.class), eq("FOLLOW_UP"))).thenReturn(40000L);

        VisitView view = service.change(DOCTOR, VISIT, new VisitTypeInput("FOLLOW_UP", null));

        assertThat(view.feeChange().outcome()).isEqualTo("UPDATED");
        assertThat(view.feeChange().beforePaise()).isEqualTo(80000);
        assertThat(view.feeChange().afterPaise()).isEqualTo(40000);
        verify(visits).updateFee(TENANT, VISIT, 40000L, USER);
    }

    @Test
    void aPaidBillKeepsTheFeeAndSaysAlreadyBilled() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("NEW", null, 80000)), Optional.of(visit("NEW", null, 80000)),
                Optional.of(visit("FOLLOW_UP", null, 80000)));
        when(charges.hasPayment(TENANT, VISIT)).thenReturn(true);

        VisitView view = service.change(DOCTOR, VISIT, new VisitTypeInput("FOLLOW_UP", null));

        assertThat(view.visitType()).isEqualTo("FOLLOW_UP");
        assertThat(view.feeChange().outcome()).isEqualTo("ALREADY_BILLED");
        verify(visits, never()).updateFee(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(visits).updateVisitTypeAndTriage(TENANT, VISIT, "FOLLOW_UP", null, null, USER);
    }

    @Test
    void changingBackFromEmergencyDropsTheLevel() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("EMERGENCY", 2, 80000)), Optional.of(visit("EMERGENCY", 2, 80000)),
                Optional.of(visit("NEW", null, 80000)));
        when(charges.hasPayment(TENANT, VISIT)).thenReturn(false);
        when(visitService.feeForType(any(), any(), any())).thenReturn(80000L);

        service.change(DOCTOR, VISIT, new VisitTypeInput("NEW", null));

        verify(visits).updateVisitTypeAndTriage(TENANT, VISIT, "NEW", null, null, USER);
    }

    @Test
    void theVisitIsLockedBeforeThePaymentIsLookedAtAndTheWritesCarryTheClinic() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("NEW", null, 80000)),
                Optional.of(visit("NEW", null, 80000)), Optional.of(visit("FOLLOW_UP", null, 40000)));
        when(charges.hasPayment(TENANT, VISIT)).thenReturn(false);
        when(visitService.feeForType(eq(TENANT), any(VisitRow.class), eq("FOLLOW_UP"))).thenReturn(40000L);

        service.change(DOCTOR, VISIT, new VisitTypeInput("FOLLOW_UP", null));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(visits, charges);
        order.verify(visits).lockById(TENANT, VISIT);
        order.verify(charges).hasPayment(TENANT, VISIT);
        order.verify(visits).updateFee(TENANT, VISIT, 40000L, USER);
        order.verify(visits).updateVisitTypeAndTriage(TENANT, VISIT, "FOLLOW_UP", null, null, USER);
    }

    @Test
    void aSignedRecordRefusesTheChangeAndTheSameTypeAndLevelWritesNothing() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit("NEW", null, 80000)));
        RecordRow signed = mock(RecordRow.class);
        when(signed.status()).thenReturn("SIGNED");
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput("REVISIT", null)))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> assertThat(e.slug()).isEqualTo("emr-record-signed"));

        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        service.change(DOCTOR, VISIT, new VisitTypeInput("NEW", null));
        verify(visits, never()).updateVisitTypeAndTriage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theTriageLevelsAreTheClinicsScaleForADoctor() {
        assertThat(service.triageLevels(DOCTOR).levels()).hasSize(3);
        assertThat(service.triageLevels(DOCTOR).clinicDefault()).isTrue();
    }
}
