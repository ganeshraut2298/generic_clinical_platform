package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientClinicalUpdatePort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-51-3, SAH-51-4 and SAH-67 (ADR-025 D4, D5): the autosave writes no draft audit entry, one doctor edits at a time (the
 * save of another doctor is refused while the hold is active), opening a record takes the hold and stores the patient facts
 * fingerprint, and an IPD round can now be saved.
 */
@SuppressWarnings("unchecked")
class EmrRecordServiceEditHoldTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrDepartmentResolver resolver = mock(EmrDepartmentResolver.class);
    private final EmrLayouts layouts = mock(EmrLayouts.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final EmrEncounterSource encounters = mock(EmrEncounterSource.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final EmrFinishRepository finish = mock(EmrFinishRepository.class);
    private EmrRecordService service;
    private PatientClinical patient;

    @BeforeEach
    void setUp() {
        List<LayoutSection> layout = List.of(EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false,
                EmrTestData.field("pulse", "NUMBER", false)));
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(team.find(TENANT, OTHER)).thenReturn(Optional.of(new TeamMember(OTHER, "Dr Other", "DOCTOR", true, 0)));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        Department gm = new Department(null, "gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of("Room 2"), 0, 0, null);
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm, new Published("STARTER", null, null, 1, layout),
                "DOCTOR_SPECIALTY"));
        when(resolver.switchOptions(TENANT)).thenReturn(List.of(new DepartmentRef("gm", "General Medicine")));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(layouts.find(TENANT, "gm")).thenReturn(Optional.of(gm));
        patient = new PatientClinical(PATIENT, "UH-1", "Meena Patil", 41, "F", "Dust", "DM");
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(patient));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBER, "NEW",
                "WITH_DOCTOR", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1)));
        when(visits.previousVisitAt(eq(TENANT), eq(PATIENT), any())).thenReturn(Optional.empty());
        when(visits.countVisitsUpTo(eq(TENANT), eq(PATIENT), any())).thenReturn(1);
        ObjectProvider<EmrVisitChargePort> charges = mock(ObjectProvider.class);
        service = new EmrRecordService(access, records, resolver, layouts, visits, mock(VisitService.class), patients, team,
                encounters, entitlements, audit, EmrTestData.MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
                new EmrComponentValues(EmrTestData.MAPPER), mock(EmrMedicineRepository.class), mock(ServiceLookupPort.class),
                charges, mock(com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport.class), finish,
                mock(ObjectProvider.class), mock(ObjectProvider.class));
    }

    private RecordRow record(String type, String status, long revision) {
        boolean ipd = "IPD_ROUND".equals(type);
        return new RecordRow(RECORD, type, ipd ? null : VISIT, ipd ? ADMISSION : null, ipd ? LocalDate.of(2026, 11, 12) : null,
                null, PATIENT, MEMBER, "gm", "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", status,
                null, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson(), "ASSESSMENT", null, false, null, revision,
                Instant.parse("2026-11-12T05:00:00Z"));
    }

    private static RecordState state(UUID holder, boolean active) {
        return new RecordState(RECORD, "DRAFT", null, null, null, null, null, holder, null,
                holder == null ? null : NOW.plusSeconds(100), active, null);
    }

    private static RecordDraftInput draft(long revision, String kind) {
        return new RecordDraftInput(revision, new UiStateInput("ASSESSMENT", null), List.of(), null, kind);
    }

    // ---- autosave ----

    @Test
    void aManualSaveWritesTheDraftAuditAndAnAutoSaveDoesNotAndBothRenewTheHold() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        when(finish.takeHold(eq(TENANT), eq(RECORD), eq(MEMBER), eq(USER), anyInt(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(true);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(MEMBER, true)));

        service.saveDraft(DOCTOR, RECORD, draft(3, "AUTO"));
        verify(audit, never()).record(eq("EMR_RECORD_DRAFT_SAVED"), any(), any(), any(), any());

        service.saveDraft(DOCTOR, RECORD, draft(3, "MANUAL"));
        verify(audit).record(eq("EMR_RECORD_DRAFT_SAVED"), eq("EMR_RECORD"), eq(RECORD), isNull(), any());

        service.saveDraft(DOCTOR, RECORD, draft(3, null));
        verify(audit, org.mockito.Mockito.times(2)).record(eq("EMR_RECORD_DRAFT_SAVED"), any(), any(), any(), any());
        verify(finish, org.mockito.Mockito.times(3)).takeHold(TENANT, RECORD, MEMBER, USER, 120, false);
    }

    @Test
    void anUnknownSaveKindIsRefusedAsAValidationErrorAndNothingIsSavedOrHeld() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));

        for (String kind : List.of("LOUD", "auto", "", " AUTO")) {
            assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(3, kind)))
                    .isInstanceOfSatisfying(com.sahaj.platform.api.InvalidFieldsException.class,
                            e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("saveKind"));
        }
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
        verify(finish, never()).takeHold(any(), any(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(audit, never()).record(eq("EMR_RECORD_DRAFT_SAVED"), any(), any(), any(), any());
    }

    // ---- one editor at a time ----

    @Test
    void aSaveWhileAnotherDoctorHoldsTheRecordIsRefusedWithTheirNameAndNothingIsSaved() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));
        when(finish.takeHold(TENANT, RECORD, MEMBER, USER, 120, false)).thenReturn(false);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(OTHER, true)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(3, "AUTO"))).isInstanceOfSatisfying(
                EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("emr-record-held");
                    assertThat(e.extensions().get("holderName")).isEqualTo("Dr. Dr Other");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void aSaveThatMeetsARecordSignedMeanwhileIsRefusedAsSignedNotAsHeldByAnotherDoctor() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));
        when(finish.takeHold(TENANT, RECORD, MEMBER, USER, 120, false)).thenReturn(false);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "SIGNED", OTHER, null, null,
                null, null, null, null, null, false, null)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(3, "AUTO"))).isInstanceOfSatisfying(
                EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("emr-record-signed");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void openingTakesTheHoldAndStoresTheFingerprintOfThePatientFactsForANewRecord() {
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 0)));
        when(finish.state(eq(TENANT), any())).thenReturn(Optional.empty());

        service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        verify(finish).setFingerprint(eq(TENANT), eq(RECORD), eq(PatientClinicalUpdatePort.fingerprint("Dust", "DM")));
        verify(finish).takeHold(TENANT, RECORD, MEMBER, USER, 120, false);
    }

    @Test
    void theViewOfARecordHeldByAnotherDoctorIsReadOnlyWithTheBannerFacts() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(OTHER, true)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.readOnly()).isTrue();
        assertThat(view.readOnlyReason()).isEqualTo("HELD_BY_OTHER");
        assertThat(view.editHold().holderName()).isEqualTo("Dr Other");
        assertThat(view.editHold().mine()).isFalse();
        assertThat(view.editHold().canTakeOver()).isFalse();
        assertThat(view.addenda()).isEmpty();
        assertThat(view.canAddAddendum()).isFalse();
    }

    @Test
    void anExpiredHoldOrYourOwnHoldLeavesTheRecordEditable() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 3)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(OTHER, false)));
        assertThat(service.get(DOCTOR, RECORD).readOnly()).isFalse();
        assertThat(service.get(DOCTOR, RECORD).editHold()).isNull();

        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(MEMBER, true)));
        RecordView mine = service.get(DOCTOR, RECORD);
        assertThat(mine.readOnly()).isFalse();
        assertThat(mine.editHold().mine()).isTrue();
    }

    // ---- IPD drafts are on (OQ-43-27) ----

    @Test
    void anAdmittedPatientsRoundCanBeSavedAndItsPrescriptionIsTheOrderComponent() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("IPD_ROUND", "DRAFT", 2)));
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionFacts(ADMISSION, PATIENT,
                "IP/26-27/0001", "ADMITTED", 2, MEMBER, Access.ALLOWED)));
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        when(finish.takeHold(TENANT, RECORD, MEMBER, USER, 120, false)).thenReturn(true);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state(MEMBER, true)));

        RecordView view = service.saveDraft(DOCTOR, RECORD, draft(2, "MANUAL"));

        assertThat(view.encounterType()).isEqualTo("IPD_ROUND");
        assertThat(view.prescriptionIsOrder()).isTrue();
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(2L), anyString(), eq("ASSESSMENT"), any(), eq(USER));
    }

    @Test
    void aSignedRecordShowsTheSignatureFactsAndNoHold() {
        RecordRow signed = new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, MEMBER, "gm",
                "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "SIGNED",
                Instant.parse("2026-11-12T07:00:00Z"), EmrRecordDocument.empty(EmrTestData.MAPPER).toJson(), "ASSESSMENT",
                null, false, null, 5, Instant.parse("2026-11-12T05:00:00Z"));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(signed));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "SIGNED", MEMBER, USER, "k",
                "h", "{}", null, null, null, false, null)));
        when(finish.addenda(TENANT, RECORD)).thenReturn(List.of());
        when(finish.latestRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(encounters.openAdmissionOf(TENANT, PATIENT)).thenReturn(Optional.empty());

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.readOnlyReason()).isEqualTo("SIGNED");
        assertThat(view.signedAt()).isEqualTo(Instant.parse("2026-11-12T07:00:00Z"));
        assertThat(view.signedByName()).isEqualTo("Dr Test");
        assertThat(view.editHold()).isNull();
        assertThat(view.canAddAddendum()).isTrue();
        assertThat(view.admission().state()).isEqualTo("NONE");
    }
}
