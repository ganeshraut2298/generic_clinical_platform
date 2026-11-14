package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.ServiceRepository.ServiceRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SpecialtyEditor;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What the record service reads for a specialty screen (ADR-023 D2, D7): the tariff match, the ANC course, the physio
 * session of the active package, vaccines given earlier and the oncology orders of the patient. All reads are lazy and
 * scoped to the signed-in clinic.
 */
class SpecialtyRecordSupportTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");

    private final ServiceRepository services = mock(ServiceRepository.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final FakeChemoOrders orders = new FakeChemoOrders();
    private SpecialtyRecordSupport support;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        support = new SpecialtyRecordSupport(() -> SpecialtyReferenceData.empty(), services, mock(TeamMemberPort.class),
                patients, courses, orders, records, mock(AuditLog.class), MAPPER, mock(ObjectProvider.class));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                1, "F", null, null, LocalDate.of(2025, 5, 9))));
        when(records.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
    }

    private RecordRow row(String starter) {
        return new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, MEMBER, starter, "D", starter,
                "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, "{}", null, null, false, null, 2,
                Instant.parse("2026-11-12T05:00:00Z"));
    }

    private SpecialtyCtx ctx(String profile) {
        return support.context(TENANT, row(profile), profile, MEMBER, NOW, EmrRecordDocument.empty(MAPPER));
    }

    private static CourseRow course(String kind, String status, String name) {
        return new CourseRow(COURSE, 1, PATIENT, kind, "SESSION", name, null, MEMBER, LocalDate.parse("2026-11-01"),
                status, 0, null, NOW, null);
    }

    private static SittingRow sitting(int no, UUID visit) {
        return new SittingRow(UUID.randomUUID(), COURSE, no, "OPD", "EXTRA_SESSION", null, List.of(), 0, null, visit, null,
                false, null, null);
    }

    @Test
    void theContextTakesTheDatesTheDateOfBirthAndTheSexFromTheServerAndThePatientPort() {
        SpecialtyCtx ctx = ctx("paeds");
        assertThat(ctx.today()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(ctx.recordDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(ctx.dateOfBirth()).isEqualTo(LocalDate.of(2025, 5, 9));
        assertThat(ctx.sex()).isEqualTo("F");
        assertThat(ctx.membershipId()).isEqualTo(MEMBER);
    }

    @Test
    void theTariffMatchesTheActiveServiceByNameIgnoringCaseAndSpacesAndIsReadOnce() {
        UUID id = UUID.randomUUID();
        when(services.findAll(TENANT, true)).thenReturn(List.of(new ServiceRow(id, "  Composite   Filling ", "Dental",
                150_000, true)));
        SpecialtyCtx ctx = ctx("dental");
        assertThat(ctx.tariff().match("composite filling").get().serviceId()).isEqualTo(id);
        assertThat(ctx.tariff().match("Composite filling").get().pricePaise()).isEqualTo(150_000);
        assertThat(ctx.tariff().match("Bridge")).isEmpty();
        org.mockito.Mockito.verify(services, org.mockito.Mockito.times(1)).findAll(TENANT, true);
    }

    @Test
    void anObstetricsRecordGetsThePatientsActiveAncCourseAndItIsStampedIntoTheAncCheck() {
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                course("ANC", "ACTIVE", "Pregnancy care · ANC course")));
        SpecialtyCtx ctx = ctx("og");
        assertThat(ctx.ancCourseId()).isEqualTo(COURSE);

        LayoutSection anc = anchor("anc", "ANC_CHECK", "og");
        List<FieldMessage> errors = new ArrayList<>();
        ObjectNode input = MAPPER.createObjectNode();
        try {
            input.set("specialty", MAPPER.readTree("{\"component\":\"ANC_CHECK\",\"schemaVersion\":1,\"today\":{},"
                    + "\"symptoms\":[],\"orders\":[],\"scans\":[]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ObjectNode cleaned = support.cleanSection("sections[0]", anc, input, null, ctx, errors, new ArrayList<>());
        assertThat(errors).isEmpty();
        assertThat(cleaned.path("specialty").path("pregnancyCourseId").asText()).isEqualTo(COURSE.toString());
    }

    @Test
    void anEndedOrOtherKindOfCourseIsNotTheAncCourse() {
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                course("ANC", "ENDED", "old"), course("DENTAL", "ACTIVE", "x")));
        assertThat(ctx("og").ancCourseId()).isNull();
    }

    @Test
    void physioShowsSessionNOfMOfTheActiveRehabPackageThroughThisVisitsSitting() {
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                course("REHAB", "ACTIVE", "Knee rehab · 10 sessions")));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(1, UUID.randomUUID()),
                sitting(2, UUID.randomUUID()), sitting(3, VISIT), sitting(4, null)));
        SpecialtyCtx.RehabInfo rehab = ctx("physio").hooks().rehab().get();
        assertThat(rehab.packageName()).isEqualTo("Knee rehab · 10 sessions");
        assertThat(rehab.sessionNo()).isEqualTo(3);
        assertThat(rehab.sessionsPlanned()).isEqualTo(4);
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sitting(1, null)));
        assertThat(ctx("physio").hooks().rehab().get().sessionNo()).as("this visit is not in the package").isNull();
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
        assertThat(ctx("physio").hooks().rehab().get()).isNull();
    }

    @Test
    void vaccinesGivenInEarlierSignedRecordsAreKnownToThePaediatricsContext() {
        EmrRecordDocument earlier = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        try {
            values.set("specialty", MAPPER.readTree("{\"component\":\"IMMUNIZATION\",\"schemaVersion\":1,"
                    + "\"givenToday\":[{\"vaccineKey\":\"bcg\"},{\"vaccineKey\":\"opv_0\"}],\"scheduleReviewed\":false}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        earlier.put("immunization", values, true, NOW, MEMBER);
        when(records.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(new SignedCopy(
                UUID.randomUUID(), Instant.parse("2025-06-20T06:00:00Z"), earlier.toJson())));
        assertThat(ctx("paeds").earlierVaccines()).containsExactlyInAnyOrder("bcg", "opv_0");
        assertThat(ctx("gm").earlierVaccines()).isEmpty();
    }

    @Test
    void theOncologyContextReadsTheFirstSentWeightTheSentDoxorubicinAndTheLatestOutsideDose() {
        orders.rows.put(UUID.randomUUID(), sent("2026-09-01T05:00:00Z", 54.0, "60.00", "100.00"));
        orders.rows.put(UUID.randomUUID(), sent("2026-09-22T05:00:00Z", 52.5, "54.00", "20.00"));
        SpecialtyCtx.ChemoInfo info = ctx("onco").hooks().chemo().get();
        assertThat(info.cycle1WeightKg()).isEqualTo(54.0);
        assertThat(info.sentCount()).isEqualTo(2);
        assertThat(info.sentDoxorubicinMgM2()).isEqualTo(114.0);
        assertThat(info.latestOutsideMgM2()).isEqualTo(20.0);
        assertThat(info.sentProtocols()).containsExactly("AC");
        assertThat(info.orderStatus()).isEqualTo("NONE");
    }

    @Test
    void aSectionWithoutAnEditorIsNeverDecoratedAndAnOtherProfilesSectionNeitherDecoratedNorChecked() {
        LayoutSection plain = new LayoutSection("history", "LIBRARY", "History", "COMMON", "", "ASSESSMENT", 1, true,
                false, false, false, false, List.of(), false, null, List.of());
        LayoutSection vitals = new LayoutSection("vitals", "LIBRARY", "Vitals", "COMMON", "", "ASSESSMENT", 2, true,
                true, false, false, false, List.of(), false, null, List.of());
        List<LayoutSection> decorated = support.decorate("gm", List.of(plain, vitals));
        assertThat(decorated.get(0).specialtyEditor()).isNull();
        assertThat(decorated.get(1).specialtyEditor()).isEqualTo(new SpecialtyEditor("VITALS_LOG", "gm"));
        assertThat(support.decorate("dental", List.of(plain, vitals))).allMatch(s -> s.specialtyEditor() == null);
    }

    @Test
    void aRowsDoneStateIsFalseForAGenericSectionSoTheGenericRuleApplies() {
        LayoutSection plain = new LayoutSection("history", "LIBRARY", "History", "COMMON", "", "ASSESSMENT", 1, true,
                false, false, false, false, List.of(), false, null, List.of());
        assertThat(support.done(plain, EmrRecordDocument.empty(MAPPER), ctx("gm"), "NONE")).isNull();
    }

    private static LayoutSection anchor(String key, String component, String profile) {
        return new LayoutSection(key, "LIBRARY", key, "SPECIALTY", "", "ASSESSMENT", 1, true, false, false, false, false,
                List.of(), false, null, List.of(), null, null, null, new SpecialtyEditor(component, profile));
    }

    private ChemoOrderRow sent(String at, double weight, String dox, String outside) {
        ChemoOrderRow row = new ChemoOrderRow();
        row.id = UUID.randomUUID();
        row.recordId = UUID.randomUUID();
        row.patientId = PATIENT;
        row.status = "SENT";
        row.protocolKey = "AC";
        row.sentAt = Instant.parse(at);
        row.snapshot = "{\"weightKg\":" + weight + "}";
        row.doxorubicinMgM2 = new BigDecimal(dox);
        row.priorOutsideMgM2 = new BigDecimal(outside);
        return row;
    }

    @Test
    void theContextTakesTheEffectiveYearsFromThePatientModulesAnswerOtherwiseFromTheSameFunction() {
        // the patient module's own answer wins: 12 years 11 months from its own clock
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                12, "F", null, null, LocalDate.of(2013, 12, 14),
                com.sahaj.patient.api.PatientAge.fromDate(LocalDate.of(2013, 12, 14), LocalDate.of(2026, 11, 13)))));
        assertThat(ctx("dental").ageYears()).isEqualTo(12);
        // a record built by hand with no age detail: PatientAge over the date of birth, as of the IST day of the context
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                99, "F", null, null, LocalDate.of(2013, 11, 12))));
        assertThat(ctx("dental").ageYears()).as("13 on 12 Nov 2026, the stored 99 is never read").isEqualTo(13);
        // no date of birth: the stored whole number
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                9, "F", null, null, null)));
        assertThat(ctx("dental").ageYears()).isEqualTo(9);
    }

    @Test
    void theDateOfBirthIsVisibleToTheContextOnlyForARoleThatMaySeeIt() {
        for (String role : List.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK")) {
            assertThat(support.context(TENANT, row("paeds"), "paeds", MEMBER, role, NOW,
                    EmrRecordDocument.empty(MAPPER)).dateOfBirthVisible()).as(role).isTrue();
        }
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR", "CUSTOM_ROLE", null}) {
            assertThat(support.context(TENANT, row("paeds"), "paeds", MEMBER, role, NOW,
                    EmrRecordDocument.empty(MAPPER)).dateOfBirthVisible()).as(String.valueOf(role)).isFalse();
        }
        assertThat(ctx("paeds").dateOfBirthVisible()).as("no role given").isFalse();
    }

    @Test
    void theHistoryHelperGivesTheDateOfBirthOrNull() {
        assertThat(support.dateOfBirth(TENANT, PATIENT)).isEqualTo(LocalDate.of(2025, 5, 9));
        assertThat(support.dateOfBirth(TENANT, UUID.randomUUID())).isNull();
    }
}
