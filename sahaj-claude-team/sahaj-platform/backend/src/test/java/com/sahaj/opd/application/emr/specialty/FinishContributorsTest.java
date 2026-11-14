package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.EmrVisitChargePort.Outcome;
import com.sahaj.opd.api.EmrVisitChargePort.PricedLine;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentService.BookedSlot;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import com.sahaj.opd.application.emr.finish.FinishModels;
import com.sahaj.opd.application.emr.finish.FinishModels.DentalChoice;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import com.sahaj.opd.domain.emr.specialty.PaedsRules;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishChoices;
import com.sahaj.opd.application.emr.finish.FinishModels.NextSlot;
import com.sahaj.opd.application.emr.finish.FinishModels.PhysioChoice;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor.FinishContext;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor.ProfileResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-69-3, SAH-63-3, SAH-70-2 (ADR-025 D7, H7, H9): what each profile adds at the end of a visit. Plan rows are made-up
 * test text; nothing here is clinical advice.
 */
@SuppressWarnings("unchecked")
class FinishContributorsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T05:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    private final EmrVisitChargePort charges = mock(EmrVisitChargePort.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final AppointmentService appointments = mock(AppointmentService.class);
    private final SpecialtyRecordSupport support = mock(SpecialtyRecordSupport.class);
    private final EmrFinishRepository finishRows = mock(EmrFinishRepository.class);
    private Instant recordCreatedAt = NOW.minusSeconds(3600);
    private DentalFinishContributor dental;
    private PhysioFinishContributor physio;
    private OncoFinishContributor onco;

    @BeforeEach
    void setUp() {
        ObjectProvider<EmrVisitChargePort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(charges);
        dental = new DentalFinishContributor(provider, courses, appointments);
        physio = new PhysioFinishContributor(courses, appointments, support);
        onco = new OncoFinishContributor(support, courses);
    }

    /** A specialty context with the given reference and earlier components; the view comes from the mocked support. */
    private SpecialtyCtx specialtyContext(SpecialtyReferenceData data, java.util.function.Function<String,
            List<SpecialtyCtx.Earlier>> earlier) {
        return new SpecialtyCtx("x", NOW, TODAY, TODAY, MEMBERSHIP, null, "F", data, SpecialtyCtx.Tariff.none(), Set.of(),
                null, key -> null, new SpecialtyCtx.Hooks(key -> null, earlier, List::of, () -> false, () -> null,
                SpecialtyCtx.ChemoInfo::none));
    }

    private void viewIs(String profile, SpecialtyCtx specialty, String json) throws Exception {
        when(support.context(eq(TENANT), any(RecordRow.class), eq(profile), eq(MEMBERSHIP), anyString(), any(Instant.class),
                any(EmrRecordDocument.class))).thenReturn(specialty);
        when(support.view(eq(profile), eq(specialty), any(RecordRow.class))).thenReturn(MAPPER.readTree(json));
    }

    private static EmrRecordDocument documentWith(String profile, String component, ObjectNode value) {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        value.put("component", component);
        values.set("specialty", value);
        document.put(SpecialtyProfiles.sectionOf(profile, component), values, false, NOW, MEMBERSHIP);
        return document;
    }

    private static VisitRow visit() {
        return new VisitRow(VISIT, PATIENT, MEMBERSHIP, "NEW", "WITH_DOCTOR", 1, 0, NOW.minusSeconds(3600), 0);
    }

    private FinishContext context(String profile, EmrRecordDocument document, FinishChoices choices) {
        RecordRow row = mock(RecordRow.class);
        when(row.id()).thenReturn(UUID.randomUUID());
        when(row.patientId()).thenReturn(PATIENT);
        when(row.createdAt()).thenReturn(recordCreatedAt);
        return new FinishContext(TENANT, ACTOR, MEMBERSHIP, "DOCTOR", row, document, visit(), choices, "key-0001-abcd",
                TODAY, NOW);
    }

    private static ObjectNode planRow(String id, String status, String doneOn, int current, int planned, long... shares) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", id);
        row.put("tooth", 16);
        row.put("procedureText", "Test procedure");
        row.put("status", status);
        if (doneOn == null) {
            row.putNull("doneOn");
        } else {
            row.put("doneOn", doneOn);
        }
        row.put("currentSitting", current);
        row.put("sittingsPlanned", planned);
        ArrayNode list = row.putArray("sittingSharesPaise");
        for (long share : shares) {
            list.add(share);
        }
        return row;
    }

    private static EmrRecordDocument dentalDocument(boolean withCourse, ObjectNode... rows) {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        String section = SpecialtyProfiles.sectionOf("dental", "DENTAL_PLAN");
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode plan = values.putObject("specialty");
        plan.put("component", "DENTAL_PLAN");
        if (withCourse) {
            plan.put("courseId", COURSE.toString());
        }
        ArrayNode list = plan.putArray("rows");
        for (ObjectNode row : rows) {
            list.add(row);
        }
        document.put(section, values, false, NOW, MEMBERSHIP);
        return document;
    }

    // ---- dental ----

    @Test
    void rowsDoneTodayBecomeKeyedPricedLinesAndEarlierOrOpenRowsDoNot() {
        EmrRecordDocument document = dentalDocument(false,
                planRow("r1", "DONE", "2026-11-13", 2, 3, 50000, 50000, 50000),
                planRow("r2", "DONE", "2026-11-01", 1, 1, 80000),
                planRow("r3", "PLANNED", null, 1, 2, 10000, 10000));
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.SYNCED);

        ProfileResult result = dental.apply(context("dental", document, FinishChoices.none()));

        ArgumentCaptor<List<PricedLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(charges).addPricedLines(eq(TENANT), eq(ACTOR), eq(VISIT), lines.capture());
        assertThat(lines.getValue()).containsExactly(new PricedLine("plan:r1:2", "Test procedure · tooth 16", 50000));
        assertThat(result.billLines()).isEqualTo(1);
        assertThat(result.billTotalPaise()).isEqualTo(50000);
    }

    @Test
    void aPlanLineThatBillingRefusesRefusesTheWholeSign() {
        EmrRecordDocument document = dentalDocument(false, planRow("r1", "DONE", "2026-11-13", 1, 1, 50000));
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.ALREADY_BILLED);

        assertThatThrownBy(() -> dental.apply(context("dental", document, FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> assertThat(e.slug()).isEqualTo("already-billed"));
    }

    @Test
    void aLabelOrAmountOutOfRangeIsAValidationErrorNotAnAlreadyBilledAnswer() {
        EmrRecordDocument document = dentalDocument(false, planRow("r1", "DONE", "2026-11-13", 1, 1, 50000));
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.INVALID_SERVICE);

        assertThatThrownBy(() -> dental.apply(context("dental", document, FinishChoices.none())))
                .isInstanceOf(com.sahaj.platform.api.InvalidFieldsException.class);
    }

    @Test
    void aRowMarkedDoneSinceTheRecordWasOpenedCountsEvenOnAnEarlierDayAndOneBeforeTheOpenDayOrInTheFutureDoesNot() {
        // opened on 11 Nov (IST), signed on 13 Nov: done on 11 or 12 Nov counts, 10 Nov and 14 Nov do not (DEC-048 H28)
        recordCreatedAt = Instant.parse("2026-11-11T10:00:00Z");
        EmrRecordDocument document = dentalDocument(false,
                planRow("r1", "DONE", "2026-11-12", 1, 1, 30000),
                planRow("r2", "DONE", "2026-11-11", 1, 1, 40000),
                planRow("r3", "DONE", "2026-11-10", 1, 1, 50000),
                planRow("r4", "DONE", "2026-11-14", 1, 1, 60000),
                planRow("r5", "DONE", "not a date", 1, 1, 70000));
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.SYNCED);

        dental.apply(context("dental", document, FinishChoices.none()));

        ArgumentCaptor<List<PricedLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(charges).addPricedLines(eq(TENANT), eq(ACTOR), eq(VISIT), lines.capture());
        assertThat(lines.getValue()).extracting(PricedLine::sourceKey).containsExactly("plan:r1:1", "plan:r2:1");
    }

    @Test
    void aRowWithNoAmountMakesNoBillLineAndIsShownWithNoAmount() {
        ObjectNode noShare = planRow("r1", "DONE", "2026-11-13", 2, 3, 50000);
        ObjectNode noTariff = planRow("r2", "DONE", "2026-11-13", 1, 1, 80000);
        noTariff.put("estimateSource", "NONE");
        EmrRecordDocument document = dentalDocument(false, noShare, noTariff);

        ProfileResult result = dental.apply(context("dental", document, FinishChoices.none()));

        // r1 is at sitting 2 but only one share is stored: no amount, not a zero line
        verify(charges, never()).addPricedLines(any(), any(), any(), anyList());
        assertThat(result.billLines()).isZero();
        ProfileEffects.Dental preview = (ProfileEffects.Dental) dental.preview(context("dental", document,
                FinishChoices.none()));
        assertThat(preview.doneRows()).extracting(ProfileEffects.DentalRow::amountPaise).containsExactly(null, null);
        assertThat(preview.billLines()).isEmpty();
    }

    @Test
    void theNextSittingIsAPlannedCourseSittingWithTheOpenRowsAndAnAppointmentOnTheFirstFreeSlot() {
        EmrRecordDocument document = dentalDocument(true, planRow("r3", "PLANNED", null, 1, 2, 10000, 20000));
        CourseRow course = mock(CourseRow.class);
        when(course.status()).thenReturn("ACTIVE");
        when(courses.findForUpdate(TENANT, COURSE)).thenReturn(Optional.of(course));
        when(courses.sittingByIdempotencyKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(4);
        UUID sittingId = UUID.randomUUID();
        when(courses.insertSitting(eq(TENANT), any(NewSitting.class), eq(ACTOR))).thenReturn(sittingId);
        SittingRow sitting = mock(SittingRow.class);
        when(sitting.id()).thenReturn(sittingId);
        when(courses.sitting(TENANT, sittingId)).thenReturn(Optional.of(sitting));
        UUID appointmentId = UUID.randomUUID();
        when(appointments.bookFirstFree(eq(TENANT), eq(ACTOR), eq(PATIENT), eq(MEMBERSHIP),
                eq(LocalDate.of(2026, 11, 20)), eq(7), eq("FOLLOW_UP"), eq("EMR"))).thenReturn(Optional.of(
                new BookedSlot(appointmentId, LocalDate.of(2026, 11, 21), LocalTime.of(10, 30))));

        ProfileResult result = dental.apply(context("dental", document, FinishChoices.none()));

        ArgumentCaptor<NewSitting> created = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), created.capture(), eq(ACTOR));
        // treatment_course_sitting.add_option_key is checked ^[A-Z][A-Z0-9_]{1,39}$ (V2026_12_10_1120): the stored key, upper case
        assertThat(created.getValue().addOptionKey()).isEqualTo("OPD_SITTING").matches("^[A-Z][A-Z0-9_]{1,39}$");
        assertThat(created.getValue().plannedDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(created.getValue().items()).hasSize(1);
        assertThat(created.getValue().chargePaise()).isEqualTo(20000);
        assertThat(result.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(result.appointmentId()).isEqualTo(appointmentId);
        assertThat(result.course().nextAppointmentDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(result.course().nextAppointmentTime()).isEqualTo("10:30");
    }

    @Test
    void theChosenIntervalSetsTheDateAndAllRowsDoneBooksNothing() {
        EmrRecordDocument open = dentalDocument(false, planRow("r3", "PLANNED", null, 1, 2, 10000, 20000));
        FinishChoices month = new FinishChoices(null, null, new DentalChoice("M1"), null, null, null);
        assertThat(dental.apply(context("dental", open, month)).followUpDate()).isEqualTo(LocalDate.of(2026, 12, 13));

        EmrRecordDocument done = dentalDocument(true, planRow("r1", "DONE", "2026-11-13", 1, 1, 50000));
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.SYNCED);
        ProfileResult result = dental.apply(context("dental", done, FinishChoices.none()));
        assertThat(result.followUpDate()).isNull();
        assertThat(result.course()).isNull();
        verify(courses, never()).insertSitting(any(), any(), any());
    }

    @Test
    void theDentalPreviewListsTheDoneRowsAndTheNextSittingDateWithoutWritingAnything() {
        EmrRecordDocument document = dentalDocument(true, planRow("r1", "DONE", "2026-11-13", 1, 1, 50000),
                planRow("r3", "PLANNED", null, 1, 2, 10000, 20000));

        ProfileEffects.Dental effects = (ProfileEffects.Dental) dental.preview(context("dental", document,
                FinishChoices.none()));

        assertThat(effects.billLines()).hasSize(1);
        assertThat(effects.doneRows()).hasSize(1);
        assertThat(effects.doneRows().get(0).name()).isEqualTo("16 · Test procedure");
        assertThat(effects.doneRows().get(0).sitting()).isEqualTo(new ProfileEffects.Sitting(1, 1));
        assertThat(effects.doneRows().get(0).amountPaise()).isEqualTo(50000L);
        assertThat(effects.allRowsDone()).isFalse();
        assertThat(effects.carriedRowCount()).isEqualTo(1);
        assertThat(effects.nextSittingInterval()).isEqualTo("D7");
        assertThat(effects.nextSittingDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(effects.nextSittingSlot()).isNull();
        assertThat(effects.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        verify(charges, never()).addPricedLines(any(), any(), any(), anyList());
    }

    @Test
    void theDentalPreviewNamesTheFirstFreeSlotAndAllRowsDoneBooksNoNextSitting() {
        EmrRecordDocument open = dentalDocument(true, planRow("r3", "PLANNED", null, 1, 2, 10000, 20000));
        when(appointments.freeSlots(TENANT, MEMBERSHIP, LocalDate.of(2026, 12, 13), 7, 1)).thenReturn(List.of(
                new AppointmentService.FreeSlot(LocalDate.of(2026, 12, 14), LocalTime.of(17, 0))));
        FinishChoices month = new FinishChoices(null, null, new DentalChoice("M1"), null, null, null);

        ProfileEffects.Dental booked = (ProfileEffects.Dental) dental.preview(context("dental", open, month));
        assertThat(booked.nextSittingInterval()).isEqualTo("M1");
        assertThat(booked.nextSittingSlot()).isEqualTo(new FinishModels.FreeSlot(LocalDate.of(2026, 12, 14), "17:00"));

        EmrRecordDocument done = dentalDocument(true, planRow("r1", "DONE", "2026-11-13", 2, 3, 50000, 50000, 50000));
        ProfileEffects.Dental all = (ProfileEffects.Dental) dental.preview(context("dental", done, FinishChoices.none()));
        assertThat(all.allRowsDone()).isTrue();
        assertThat(all.nextSittingInterval()).isNull();
        assertThat(all.nextSittingDate()).isNull();
        assertThat(all.nextSittingSlot()).isNull();
        assertThat(all.carriedRowCount()).isZero();
        assertThat(all.followUpDate()).isNull();
        assertThat(all.doneRows().get(0).sitting()).isEqualTo(new ProfileEffects.Sitting(2, 3));
    }

    @Test
    void aRowWithNoTariffMatchAndNoTypedAmountIsListedWithANullAmountAndMakesNoBillLine() {
        ObjectNode none = planRow("r1", "DONE", "2026-11-13", 1, 1, 0);
        none.put("estimateSource", "NONE");
        EmrRecordDocument document = dentalDocument(false, none);
        when(charges.addPricedLines(any(), any(), any(), anyList())).thenReturn(Outcome.SYNCED);

        ProfileEffects.Dental effects = (ProfileEffects.Dental) dental.preview(context("dental", document,
                FinishChoices.none()));
        ProfileResult result = dental.apply(context("dental", document, FinishChoices.none()));

        assertThat(effects.doneRows()).hasSize(1);
        assertThat(effects.doneRows().get(0).amountPaise()).isNull();
        assertThat(effects.billLines()).isEmpty();
        assertThat(result.billLines()).isZero();
        verify(charges, never()).addPricedLines(any(), any(), any(), anyList());
    }

    @Test
    void theDentalEstimateTotalIsThePlansAndAPlanlessRecordHasAnEmptyBlock() {
        ObjectNode plan = MAPPER.createObjectNode();
        plan.putObject("estimate").put("totalPaise", 125000);
        plan.putArray("rows").add(planRow("r1", "DONE", "2026-11-13", 1, 1, 50000));
        EmrRecordDocument document = documentWith("dental", "DENTAL_PLAN", plan);

        ProfileEffects.Dental effects = (ProfileEffects.Dental) dental.preview(context("dental", document,
                FinishChoices.none()));
        ProfileEffects.Dental empty = (ProfileEffects.Dental) dental.preview(context("dental",
                EmrRecordDocument.empty(MAPPER), FinishChoices.none()));

        assertThat(effects.estimateTotalPaise()).isEqualTo(125000);
        assertThat(empty.doneRows()).isEmpty();
        assertThat(empty.allRowsDone()).isTrue();
        assertThat(empty.estimateTotalPaise()).isZero();
    }

    // ---- physiotherapy ----

    private SittingRow sitting(UUID id, int no, UUID visitId, Instant doneAt) {
        return new SittingRow(id, COURSE, no, "OPD", "extra_session", LocalDate.of(2026, 11, 14), List.of("Session"), 0,
                null, visitId, null, false, doneAt, "h");
    }

    @Test
    void thePackageIsCompleteWhenNoSessionIsLeftAndNothingIsBooked() {
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 6, VISIT, null)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(UUID.randomUUID(), 5, UUID.randomUUID(),
                NOW), sitting(UUID.randomUUID(), 6, VISIT, null)));

        ProfileResult result = physio.apply(context("physio", EmrRecordDocument.empty(MAPPER), FinishChoices.none()));

        assertThat(result.course().packageComplete()).isTrue();
        verify(appointments, never()).bookAt(any(), any(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void theNextSessionMovesToTheChosenSlotAndIsBookedThereOrKeepsTheDateWhenNoSlotWasChosen() {
        UUID next = UUID.randomUUID();
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 2, VISIT, null)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(UUID.randomUUID(), 2, VISIT, null),
                sitting(next, 3, null, null)));
        UUID appointmentId = UUID.randomUUID();
        when(appointments.isOpenSlot(TENANT, MEMBERSHIP, LocalDate.of(2026, 11, 18), LocalTime.of(17, 0))).thenReturn(true);
        when(appointments.bookAt(TENANT, ACTOR, PATIENT, MEMBERSHIP, LocalDate.of(2026, 11, 18), LocalTime.of(17, 0),
                "FOLLOW_UP", "EMR")).thenReturn(Optional.of(new BookedSlot(appointmentId, LocalDate.of(2026, 11, 18),
                LocalTime.of(17, 0))));
        FinishChoices slot = new FinishChoices(null, null, null, new PhysioChoice(new NextSlot(LocalDate.of(2026, 11, 18),
                "17:00")), null, null);

        ProfileResult booked = physio.apply(context("physio", EmrRecordDocument.empty(MAPPER), slot));

        assertThat(booked.course().nextSittingId()).isEqualTo(next);
        assertThat(booked.course().nextAppointmentTime()).isEqualTo("17:00");
        verify(courses).updateSitting(eq(TENANT), eq(next), any(), eq(ACTOR));

        ProfileResult dateOnly = physio.apply(context("physio", EmrRecordDocument.empty(MAPPER), FinishChoices.none()));
        assertThat(dateOnly.course().nextSittingId()).isEqualTo(next);
        assertThat(dateOnly.course().nextAppointmentTime()).isNull();
        verify(appointments, org.mockito.Mockito.times(1)).bookAt(any(), any(), any(), any(), any(), any(), anyString(),
                anyString());
    }

    @Test
    void aSlotThatIsNotARealSlotOfTheDoctorIsRefusedBeforeAnythingIsMovedOrBooked() {
        UUID next = UUID.randomUUID();
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 2, VISIT, null)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(UUID.randomUUID(), 2, VISIT, null),
                sitting(next, 3, null, null)));
        // off the grid, past, on leave, reserved for walk-ins: the appointment service says it is not an open slot
        when(appointments.isOpenSlot(any(), any(), any(), any())).thenReturn(false);

        for (NextSlot bad : List.of(new NextSlot(LocalDate.of(2026, 11, 18), "17:07"),
                new NextSlot(LocalDate.of(2026, 11, 18), "late"), new NextSlot(LocalDate.of(2026, 11, 12), "10:00"),
                new NextSlot(LocalDate.of(2026, 11, 12), null))) {
            FinishChoices choices = new FinishChoices(null, null, null, new PhysioChoice(bad), null, null);
            assertThatThrownBy(() -> physio.apply(context("physio", EmrRecordDocument.empty(MAPPER), choices)))
                    .isInstanceOf(com.sahaj.platform.api.InvalidFieldsException.class);
        }
        verify(courses, never()).updateSitting(any(), any(), any(), any());
        verify(appointments, never()).bookAt(any(), any(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aSlotTakenByAnotherPatientMeanwhileKeepsTheDateOnlyAsBefore() {
        UUID next = UUID.randomUUID();
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 2, VISIT, null)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(UUID.randomUUID(), 2, VISIT, null),
                sitting(next, 3, null, null)));
        when(appointments.isOpenSlot(TENANT, MEMBERSHIP, LocalDate.of(2026, 11, 18), LocalTime.of(17, 0))).thenReturn(true);
        when(appointments.bookAt(any(), any(), any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        FinishChoices choices = new FinishChoices(null, null, null, new PhysioChoice(new NextSlot(LocalDate.of(2026, 11, 18),
                "17:00")), null, null);

        ProfileResult result = physio.apply(context("physio", EmrRecordDocument.empty(MAPPER), choices));

        assertThat(result.course().nextSittingId()).isEqualTo(next);
        assertThat(result.course().nextAppointmentTime()).isNull();
    }

    // ---- oncology ----

    @Test
    void theFollowUpFallsOnTheNextCycleDateAndNoVisitBillLineIsMade() {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode plan = values.putObject("specialty");
        plan.put("component", "CHEMO_PLAN");
        plan.put("nextCycleDate", "2026-12-04");
        document.put(SpecialtyProfiles.sectionOf("onco", "CHEMO_PLAN"), values, false, NOW, MEMBERSHIP);

        ProfileResult result = onco.apply(context("onco", document, FinishChoices.none()));

        assertThat(result.followUpDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(result.billLines()).isZero();
        assertThat(onco.preview(context("onco", EmrRecordDocument.empty(MAPPER), FinishChoices.none())).followUpDate())
                .isNull();
    }

    private static final String ONCO_VIEW = "{\"onco\":{\"cycleNo\":2,\"orderStatus\":\"SENT\","
            + "\"decision\":{\"choice\":\"%s\"},\"nextCycle\":{\"defaultDate\":\"2026-12-04\"}}}";

    private SittingRow chemoSitting(UUID id, LocalDate planned, long charge, Instant doneAt) {
        return new SittingRow(id, COURSE, 3, "DAY_CARE", "CHEMO_CYCLE", planned, List.of("Cycle 3"), charge, null, null,
                null, false, doneAt, "h");
    }

    @Test
    void theOncologyBlockReadsTheScreenViewAndTheDefaultNextCycleDateAndPrintsTheCycleRecordAndTheCalendar()
            throws Exception {
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("onco", specialty, String.format(ONCO_VIEW, "GIVE_AS_PLANNED"));
        UUID sittingId = UUID.randomUUID();
        CourseRow course = new CourseRow(COURSE, 1, PATIENT, "CHEMO", "CYCLE", "Chemotherapy course", null, MEMBERSHIP,
                TODAY, "ACTIVE", 1, "h", NOW);
        when(courses.forPatient(TENANT, PATIENT, 50)).thenReturn(List.of(course));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(
                chemoSitting(UUID.randomUUID(), LocalDate.of(2026, 11, 20), 100, null),
                chemoSitting(sittingId, LocalDate.of(2026, 12, 4), 4500000, null)));

        ProfileEffects.Onco effects = (ProfileEffects.Onco) onco.preview(context("onco", EmrRecordDocument.empty(MAPPER),
                FinishChoices.none()));

        assertThat(effects.cycleNo()).isEqualTo(2);
        assertThat(effects.decision()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(effects.orderStatus()).isEqualTo("SENT");
        assertThat(effects.nextCycleNo()).isEqualTo(3);
        assertThat(effects.nextCycleLabel()).isEqualTo("NEXT_CHEMO");
        assertThat(effects.nextCycleDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(effects.nextCycleSittingId()).isEqualTo(sittingId);
        assertThat(effects.cycleChargePaise()).isEqualTo(4500000L);
        assertThat(effects.followUpFromNextCycle()).isTrue();
        assertThat(effects.followUpDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(effects.prints()).containsExactly(FinishPrint.CYCLE_RECORD, FinishPrint.CHEMO_CALENDAR);
        assertThat(onco.prints(context("onco", EmrRecordDocument.empty(MAPPER), FinishChoices.none())))
                .containsExactly(FinishPrint.CYCLE_RECORD, FinishPrint.CHEMO_CALENDAR);
    }

    @Test
    void stopChemoHasNoNextCycleSoOnlyTheCycleRecordPrintsAndDelayOneWeekIsRebooked() throws Exception {
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("onco", specialty, String.format(ONCO_VIEW, "STOP"));
        ProfileEffects.Onco stopped = (ProfileEffects.Onco) onco.preview(context("onco", EmrRecordDocument.empty(MAPPER),
                FinishChoices.none()));
        assertThat(stopped.nextCycleDate()).isNull();
        assertThat(stopped.nextCycleNo()).isNull();
        assertThat(stopped.nextCycleLabel()).isNull();
        assertThat(stopped.followUpDate()).isNull();
        assertThat(stopped.prints()).containsExactly(FinishPrint.CYCLE_RECORD);

        viewIs("onco", specialty, String.format(ONCO_VIEW, "DELAY_1_WEEK"));
        ProfileEffects.Onco delayed = (ProfileEffects.Onco) onco.preview(context("onco", EmrRecordDocument.empty(MAPPER),
                FinishChoices.none()));
        assertThat(delayed.nextCycleLabel()).isEqualTo("REBOOKED_CHEMO");
    }

    @Test
    void aChosenFollowUpIntervalWinsOverTheNextCycleDate() throws Exception {
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("onco", specialty, String.format(ONCO_VIEW, "GIVE_AS_PLANNED"));
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode next = values.putObject("next_visit");
        next.put("component", "FOLLOW_UP");
        next.put("interval", "D7");
        document.put("followup", values, false, NOW, MEMBERSHIP);

        ProfileEffects.Onco effects = (ProfileEffects.Onco) onco.preview(context("onco", document, FinishChoices.none()));

        assertThat(effects.nextCycleDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(effects.followUpFromNextCycle()).isFalse();
        assertThat(effects.followUpDate()).isNull();
        assertThat(onco.apply(context("onco", document, FinishChoices.none())).followUpDate()).isNull();
    }

    // ---- general medicine, obstetrics, paediatrics, surgery ----

    @Test
    void generalMedicineHasAnEmptyProfileBlock() {
        assertThat(new GmFinishContributor().preview(context("gm", EmrRecordDocument.empty(MAPPER), FinishChoices.none())))
                .isEqualTo(new ProfileEffects.Gm());
    }

    @Test
    void theObstetricsBlockHasTheAncVisitNumberTheOrderedLabelsAndTodaysValues() throws Exception {
        UUID course = UUID.randomUUID();
        ObjectNode anc = MAPPER.createObjectNode();
        anc.put("pregnancyCourseId", course.toString());
        ObjectNode today = anc.putObject("today");
        today.put("bpSystolic", 128);
        today.put("bpDiastolic", 82);
        today.put("hbGdl", 10.4);
        ArrayNode orders = anc.putArray("orders");
        orders.addObject().put("key", "OGTT_75G").putObject("ordered").put("at", "2026-11-13T05:30:00Z");
        orders.addObject().put("key", "NST");
        anc.put("nextAncWeeks", 32);
        EmrRecordDocument document = documentWith("og", "ANC_CHECK", anc);
        ObjectNode sameCourse = MAPPER.createObjectNode().put("pregnancyCourseId", course.toString());
        ObjectNode otherCourse = MAPPER.createObjectNode().put("pregnancyCourseId", UUID.randomUUID().toString());
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of(
                new SpecialtyCtx.Earlier(UUID.randomUUID(), LocalDate.of(2026, 10, 1), sameCourse),
                new SpecialtyCtx.Earlier(UUID.randomUUID(), LocalDate.of(2026, 9, 1), sameCourse),
                new SpecialtyCtx.Earlier(UUID.randomUUID(), LocalDate.of(2025, 1, 1), otherCourse)));
        viewIs("og", specialty, "{\"mode\":\"ANC\",\"og\":{\"gestationalAge\":{\"weeks\":28,\"days\":3}}}");

        ProfileEffects.Og effects = (ProfileEffects.Og) new OgFinishContributor(support).preview(context("og", document,
                FinishChoices.none()));

        assertThat(effects.mode()).isEqualTo("ANC");
        assertThat(effects.ancVisitNo()).isEqualTo(3);
        assertThat(effects.gestationalAge()).isEqualTo(new ProfileEffects.GestationalAge(28, 3));
        assertThat(effects.bpSystolic()).isEqualTo(128);
        assertThat(effects.bpDiastolic()).isEqualTo(82);
        assertThat(effects.hbGdl()).isEqualTo(10.4);
        assertThat(effects.orderedLabels()).containsExactly("OGTT (75 g)");
        assertThat(effects.nextAncWeeks()).isEqualTo(32);
    }

    @Test
    void aGynaeVisitHasNoAncVisitNumberAndNoCourseMeansNone() throws Exception {
        ObjectNode anc = MAPPER.createObjectNode();
        EmrRecordDocument document = documentWith("og", "ANC_CHECK", anc);
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("og", specialty, "{\"mode\":\"GYNAE\",\"og\":{\"gestationalAge\":null}}");

        ProfileEffects.Og gynae = (ProfileEffects.Og) new OgFinishContributor(support).preview(context("og", document,
                FinishChoices.none()));
        assertThat(gynae.mode()).isEqualTo("GYNAE");
        assertThat(gynae.ancVisitNo()).isNull();
        assertThat(gynae.gestationalAge()).isNull();
        assertThat(gynae.bpSystolic()).isNull();
        assertThat(gynae.orderedLabels()).isEmpty();

        viewIs("og", specialty, "{\"mode\":\"ANC\",\"og\":{}}");
        ProfileEffects.Og noCourse = (ProfileEffects.Og) new OgFinishContributor(support).preview(context("og", document,
                FinishChoices.none()));
        assertThat(noCourse.ancVisitNo()).isNull();
    }

    @Test
    void theChildBlockHasTheWeightNoteTheVaccinesGivenAndTheNextDueVaccineWithLabels() throws Exception {
        PaedsRules.Vaccine bcg = new PaedsRules.Vaccine("BCG", "BIRTH", "BCG", 1);
        PaedsRules.Vaccine mmr = new PaedsRules.Vaccine("MMR1", "M15", "MMR 1", 2);
        SpecialtyReferenceData data = new SpecialtyReferenceData(List.of(), List.of(bcg, mmr), List.of(), List.of(),
                List.of(), List.of());
        ObjectNode growth = MAPPER.createObjectNode().put("weightKg", 9.4);
        EmrRecordDocument document = documentWith("paeds", "GROWTH", growth);
        ObjectNode immunization = MAPPER.createObjectNode();
        immunization.putArray("givenToday").addObject().put("vaccineKey", "BCG");
        ObjectNode values = MAPPER.createObjectNode();
        immunization.put("component", "IMMUNIZATION");
        values.set("specialty", immunization);
        document.put(SpecialtyProfiles.sectionOf("paeds", "IMMUNIZATION"), values, false, NOW, MEMBERSHIP);
        SpecialtyCtx specialty = specialtyContext(data, key -> List.of());
        viewIs("paeds", specialty, "{\"paeds\":{\"growthNotes\":[{\"field\":\"weightKg\","
                + "\"label\":\"Below 3rd centile\",\"alert\":true}],\"nextDue\":{\"vaccineKey\":\"MMR1\","
                + "\"groupLabel\":\"15 months\"}}}");

        ProfileEffects.Paeds effects = (ProfileEffects.Paeds) new PaedsFinishContributor(support).preview(
                context("paeds", document, FinishChoices.none()));

        assertThat(effects.weightKg()).isEqualTo(9.4);
        assertThat(effects.weightNote()).isEqualTo("Below 3rd centile");
        assertThat(effects.vaccinesGivenToday()).containsExactly(new ProfileEffects.GivenVaccine("BCG", "BCG"));
        assertThat(effects.nextDue()).isEqualTo(new ProfileEffects.NextDue("MMR1", "MMR 1", "15 months"));
    }

    @Test
    void withoutAWeightTheChildBlockHasNoNoteAndNoVaccineMeansNoneAndNoNextDue() throws Exception {
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("paeds", specialty, "{\"paeds\":{\"growthNotes\":[],\"nextDue\":null}}");

        ProfileEffects.Paeds effects = (ProfileEffects.Paeds) new PaedsFinishContributor(support).preview(
                context("paeds", EmrRecordDocument.empty(MAPPER), FinishChoices.none()));

        assertThat(effects.weightKg()).isNull();
        assertThat(effects.weightNote()).isNull();
        assertThat(effects.vaccinesGivenToday()).isEmpty();
        assertThat(effects.nextDue()).isNull();
    }

    @Test
    void theSurgeryBlockHasTheBookingTextTheAdmissionTheWardBedAndTheFitnessLine() throws Exception {
        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("procedureText", "Laparoscopic cholecystectomy");
        plan.put("otDate", "2026-11-18");
        plan.put("slotText", "OT 2 · 10:00");
        plan.put("admission", "IPD_1_2_DAYS");
        plan.putObject("booking").put("admissionRequested", true);
        EmrRecordDocument document = documentWith("surgery", "SURGERY_PLAN", plan);
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("surgery", specialty, "{\"surgery\":{\"bookState\":\"BOOKED\",\"fitness\":{\"state\":\"FIT\","
                + "\"text\":\"ASA II · fit\"}}}");
        SurgeryFinishContributor surgery = new SurgeryFinishContributor(support, finishRows);

        ProfileEffects.Surgery effects = (ProfileEffects.Surgery) surgery.preview(context("surgery", document,
                FinishChoices.none()));

        assertThat(effects.bookState()).isEqualTo("BOOKED");
        assertThat(effects.procedureText()).isEqualTo("Laparoscopic cholecystectomy");
        assertThat(effects.otDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(effects.slotText()).isEqualTo("OT 2 · 10:00");
        assertThat(effects.admission()).isEqualTo("IPD_1_2_DAYS");
        assertThat(effects.admissionRequested()).isTrue();
        assertThat(effects.fitnessText()).isEqualTo("ASA II · fit");
        assertThat(effects.prints()).containsExactly(FinishPrint.ADMISSION_LETTER, FinishPrint.PREOP_SHEET);
        assertThat(surgery.prints(context("surgery", document, FinishChoices.none())))
                .containsExactly(FinishPrint.ADMISSION_LETTER, FinishPrint.PREOP_SHEET);
    }

    @Test
    void anOpenAdmissionRequestCountsAsAWardBedAndAnUnbookedPlanPrintsNothingExtra() throws Exception {
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of());
        viewIs("surgery", specialty, "{\"surgery\":{\"bookState\":\"READY\",\"fitness\":{\"text\":\"Not assessed\"}}}");
        when(finishRows.openRequestOfRecord(eq(TENANT), any())).thenReturn(Optional.of(
                mock(EmrFinishRepository.RequestRow.class)));
        SurgeryFinishContributor surgery = new SurgeryFinishContributor(support, finishRows);

        ProfileEffects.Surgery effects = (ProfileEffects.Surgery) surgery.preview(context("surgery",
                EmrRecordDocument.empty(MAPPER), FinishChoices.none()));

        assertThat(effects.admissionRequested()).isTrue();
        assertThat(effects.bookState()).isEqualTo("READY");
        assertThat(effects.procedureText()).isNull();
        assertThat(effects.otDate()).isNull();
        assertThat(effects.prints()).isEmpty();
    }

    // ---- physiotherapy block ----

    @Test
    void thePhysioBlockHasTheSessionTheNextSessionTheCourseAndBothPainScores() throws Exception {
        UUID next = UUID.randomUUID();
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 2, VISIT, null)));
        // the numbers shown are ranks among the sittings listed (ADR-026 D4 displayNo); a removed one is not listed
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(UUID.randomUUID(), 1, UUID.randomUUID(), NOW),
                sitting(UUID.randomUUID(), 2, VISIT, null),
                sitting(next, 3, null, null)));
        ObjectNode pain = MAPPER.createObjectNode().put("score", 4);
        EmrRecordDocument document = documentWith("physio", "PAIN_SCORE", pain);
        SpecialtyCtx specialty = specialtyContext(SpecialtyReferenceData.empty(), key -> List.of(
                new SpecialtyCtx.Earlier(UUID.randomUUID(), LocalDate.of(2026, 11, 6), MAPPER.createObjectNode().put("score", 7)),
                new SpecialtyCtx.Earlier(UUID.randomUUID(), LocalDate.of(2026, 10, 30), MAPPER.createObjectNode().put("score", 9))));
        when(support.context(eq(TENANT), any(RecordRow.class), eq("physio"), eq(MEMBERSHIP), anyString(), any(Instant.class),
                any(EmrRecordDocument.class))).thenReturn(specialty);

        ProfileEffects.Physio effects = (ProfileEffects.Physio) physio.preview(context("physio", document,
                FinishChoices.none()));

        assertThat(effects.courseId()).isEqualTo(COURSE);
        assertThat(effects.sessionNo()).isEqualTo(2);
        assertThat(effects.packageSize()).isEqualTo(3);
        assertThat(effects.nextSessionNo()).isEqualTo(3);
        assertThat(effects.packageComplete()).isFalse();
        assertThat(effects.painScore()).isEqualTo(4);
        assertThat(effects.previousPainScore()).isEqualTo(7);
    }

    @Test
    void aPhysioVisitWithoutACourseHasNullSessionFactsAndNoPackage() {
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());

        ProfileEffects.Physio effects = (ProfileEffects.Physio) physio.preview(context("physio",
                EmrRecordDocument.empty(MAPPER), FinishChoices.none()));

        assertThat(effects.courseId()).isNull();
        assertThat(effects.sessionNo()).isNull();
        assertThat(effects.packageSize()).isNull();
        assertThat(effects.nextSessionNo()).isNull();
        assertThat(effects.packageComplete()).isFalse();
        assertThat(effects.painScore()).isNull();
    }

    @Test
    void theContextShowsIdsOnly() {
        assertThat(context("dental", EmrRecordDocument.empty(MAPPER), FinishChoices.none()).toString())
                .startsWith("FinishContext[recordId=");
    }
}
