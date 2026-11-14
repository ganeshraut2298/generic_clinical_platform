package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OT booking (SAH-64-3, SAH-71-1, SAH-71-2; ADR-023 D8, H14). The booking rule is tagged C-7, L-3, B-3 (DEC-039, not
 * clinically reviewed). The OT date and slot are text: there is no OT calendar and no SMS.
 */
class SurgeryBookingServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID SITTING = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final String KEY = "key-0000001";

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository recordRepository = mock(EmrRecordRepository.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private SurgeryBookingService service;
    private TeamMember doctor;
    private String sections;
    private String starter = "surgery";
    private String ipd = "on";

    @BeforeEach
    void setUp() {
        doctor = new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0);
        service = new SurgeryBookingService(records, recordRepository, courses, entitlements, audit, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        sections = readySections("SELF", true, 5, false).toJson();
        when(records.authorize(DOCTOR, RECORD)).thenAnswer(i -> new Authorized(doctor, row(), PATIENT));
        when(recordRepository.findByIdForUpdate(TENANT, RECORD)).thenAnswer(i -> Optional.of(row()));
        when(recordRepository.saveSectionsLocked(eq(TENANT), eq(RECORD), anyString(), eq(USER))).thenAnswer(i -> {
            sections = i.getArgument(2);
            return true;
        });
        when(entitlements.forTenant(TENANT)).thenAnswer(i -> new ModuleEntitlement(ipd, false, 25));
        when(courses.forPatient(eq(TENANT), eq(PATIENT), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(courses.nextCourseNo(TENANT)).thenReturn(9);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(new CourseRow(COURSE, 9, PATIENT, "SURGERY", "STEP",
                "Surgery episode", null, MEMBER, LocalDate.parse("2026-11-12"), "ACTIVE", 0, null, NOW, RECORD)));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(1);
        when(courses.insertSitting(eq(TENANT), any(), eq(USER))).thenReturn(SITTING);
    }

    private static ObjectNode specialty(String json) {
        try {
            ObjectNode values = MAPPER.createObjectNode();
            values.set("specialty", MAPPER.readTree(json.replace('\'', '"')));
            return values;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static EmrRecordDocument readySections(String payer, boolean diagnosis, int ticked, boolean preauth) {
        EmrRecordDocument doc = EmrRecordDocument.empty(MAPPER);
        doc.put("sx_plan", specialty("{'component':'SURGERY_PLAN','schemaVersion':1,'procedureKey':"
                + "'LAP_CHOLECYSTECTOMY','procedureText':'Lap cholecystectomy','anaesthesia':'GA',"
                + "'admission':'IPD_1_2_DAYS','otDate':'2026-11-24','slotText':'OT 2 · 10:00','payer':'" + payer
                + "','packagePaise':6500000,'packageSource':'TARIFF','booking':null}"), true, NOW, MEMBER);
        StringBuilder items = new StringBuilder();
        String[] keys = {"PROCEDURE_RISKS", "CONVERSION_TO_OPEN", "ANAESTHESIA", "BLOOD_TRANSFUSION", "PHOTOS_VIDEO"};
        for (int i = 0; i < keys.length; i++) {
            items.append(i == 0 ? "" : ",").append("{'key':'").append(keys[i]).append("','confirmed':")
                    .append(i < ticked).append('}');
        }
        doc.put("sx_consent", specialty("{'component':'CONSENT_CHECKLIST','schemaVersion':1,'items':[" + items + "]}"),
                true, NOW, MEMBER);
        doc.put("sx_preauth", specialty("{'component':'PREAUTH','schemaVersion':1,'sent':" + preauth + "}"), true, NOW,
                MEMBER);
        if (diagnosis) {
            ObjectNode dx = MAPPER.createObjectNode();
            try {
                dx.set("diagnosis", MAPPER.readTree("{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\","
                        + "\"schemaVersion\":1,\"items\":[{\"id\":\"x\",\"text\":\"Gallstones\",\"active\":true}]}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            doc.put("diagnosis", dx, true, NOW, MEMBER);
        }
        return doc;
    }

    private RecordRow row() {
        return new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, MEMBER, starter,
                "General Surgery", starter, "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, sections, null,
                null, false, null, 4, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private JsonNode storedBooking() {
        return EmrRecordDocument.parse(MAPPER, sections).values("sx_plan").path("specialty").path("booking");
    }

    @Test
    void aBookingMissingItsPartsIsRefusedWithTheListOfWhatIsMissingAndNothingIsWritten() {
        sections = readySections("INSURANCE_TPA", false, 3, false).toJson();
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, 4L)).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> {
                    assertThat(((EmrProblemException) e).slug()).isEqualTo("booking-not-ready");
                    assertThat(((EmrProblemException) e).status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo(
                            "Before booking: diagnosis, 2 consent item(s), pre-authorisation.");
                });
        verify(recordRepository, never()).saveSectionsLocked(any(), any(), anyString(), any());
        verify(courses, never()).insertCourse(any(), any(), any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void preAuthorisationIsNotNeededForSelfPayTaggedB3() {
        sections = readySections("SELF", true, 5, false).toJson();
        service.book(DOCTOR, RECORD, KEY, 4L);
        sections = readySections("MJPJAY_PMJAY", true, 5, true).toJson();
        service.book(DOCTOR, RECORD, "key-0000002", 4L);
    }

    @Test
    void withIpdOnTheCourseAndOneIpdSittingAreMadeWithTheAdmissionRequestedAndTheMessage() {
        var result = service.book(DOCTOR, RECORD, KEY, 4L);

        assertThat(result.courseId()).isEqualTo(COURSE);
        assertThat(result.sittingId()).isEqualTo(SITTING);
        assertThat(result.admissionRequested()).isTrue();
        assertThat(result.message()).isEqualTo(
                "Lap cholecystectomy booked for 24 Nov 2026, OT 2 · 10:00. Ward bed requested.");
        ArgumentCaptor<NewCourse> course = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), course.capture(), eq(USER));
        assertThat(course.getValue().kind()).isEqualTo("SURGERY");
        assertThat(course.getValue().originRecordId()).isEqualTo(RECORD);
        ArgumentCaptor<NewSitting> sitting = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), sitting.capture(), eq(USER));
        assertThat(sitting.getValue().encounterKind()).isEqualTo("IPD");
        assertThat(sitting.getValue().addOptionKey()).isEqualTo("RE_ADMISSION");
        assertThat(sitting.getValue().plannedDate()).isEqualTo(LocalDate.parse("2026-11-24"));
        assertThat(sitting.getValue().items()).containsExactly("Lap cholecystectomy");
        assertThat(sitting.getValue().chargePaise()).isEqualTo(6_500_000L);
        assertThat(sitting.getValue().note()).isEqualTo("OT 2026-11-24, OT 2 · 10:00");
        JsonNode booking = storedBooking();
        assertThat(booking.path("stamp").path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(booking.path("courseId").asText()).isEqualTo(COURSE.toString());
        assertThat(booking.path("sittingId").asText()).isEqualTo(SITTING.toString());
        assertThat(booking.path("planFingerprint").asText()).matches("^[0-9a-f]{64}$");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_SURGERY_BOOKED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("recordId", "courseId", "sittingId");
        assertThat(after.getValue().toString()).doesNotContain("cholecystectomy");
    }

    @Test
    void aDayCareStayMakesADayCareSitting() {
        sections = sections.replace("IPD_1_2_DAYS", "DAY_CARE");
        service.book(DOCTOR, RECORD, KEY, 4L);
        ArgumentCaptor<NewSitting> sitting = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), sitting.capture(), eq(USER));
        assertThat(sitting.getValue().encounterKind()).isEqualTo("DAY_CARE");
        assertThat(sitting.getValue().addOptionKey()).isEqualTo("DAY_CARE_PROCEDURE");
    }

    @Test
    void withIpdOffTheBookingAndCourseAreMadeButNoSittingTaggedH14() {
        ipd = "off";
        var result = service.book(DOCTOR, RECORD, KEY, 4L);
        assertThat(result.sittingId()).isNull();
        assertThat(result.admissionRequested()).isFalse();
        assertThat(result.message()).isEqualTo("Booked. This clinic has no IPD, so no ward bed was requested.");
        verify(courses, never()).insertSitting(any(), any(), any());
        verify(courses).insertCourse(eq(TENANT), any(), eq(USER));
        assertThat(storedBooking().path("sittingId").isNull()).isTrue();
        assertThat(storedBooking().path("admissionRequested").asBoolean()).isFalse();
    }

    @Test
    void theSameKeyAndRevisionAnswersTheFirstResultWithoutWritingAgain() {
        service.book(DOCTOR, RECORD, KEY, 4L);
        org.mockito.Mockito.clearInvocations(courses, recordRepository, audit);

        var again = service.book(DOCTOR, RECORD, KEY, 4L);

        assertThat(again.courseId()).isEqualTo(COURSE);
        assertThat(again.sittingId()).isEqualTo(SITTING);
        assertThat(again.admissionRequested()).isTrue();
        verify(courses, never()).insertCourse(any(), any(), any());
        verify(courses, never()).insertSitting(any(), any(), any());
        verify(recordRepository, never()).saveSectionsLocked(any(), any(), anyString(), any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, 9L)).satisfies(e -> assertThat(
                ((EmrProblemException) e).slug()).isEqualTo("idempotency-conflict"));
    }

    @Test
    void bookingAgainWithANewKeyUpdatesTheSittingWhileNoAdmissionIsLinked() {
        service.book(DOCTOR, RECORD, KEY, 4L);
        sections = sections.replace("2026-11-24", "2026-11-26");
        SittingRow open = new SittingRow(SITTING, COURSE, 1, "IPD", "RE_ADMISSION", LocalDate.parse("2026-11-24"),
                List.of("Lap cholecystectomy"), 6_500_000, null, null, null, true, null, null);
        when(courses.sittingForUpdate(TENANT, SITTING)).thenReturn(Optional.of(open));
        org.mockito.Mockito.clearInvocations(courses);

        service.book(DOCTOR, RECORD, "key-0000002", 4L);

        ArgumentCaptor<SittingChange> change = ArgumentCaptor.forClass(SittingChange.class);
        verify(courses).updateSitting(eq(TENANT), eq(SITTING), change.capture(), eq(USER));
        assertThat(change.getValue().plannedDate()).isEqualTo(LocalDate.parse("2026-11-26"));
        verify(courses, never()).insertSitting(any(), any(), any());
        verify(courses, never()).insertCourse(any(), any(), any());
    }

    @Test
    void aLinkedAdmissionIsNeverChangedByABookingAgain() {
        service.book(DOCTOR, RECORD, KEY, 4L);
        SittingRow linked = new SittingRow(SITTING, COURSE, 1, "IPD", "RE_ADMISSION", LocalDate.parse("2026-11-24"),
                List.of("Lap cholecystectomy"), 6_500_000, null, null, UUID.randomUUID(), true, null, null);
        when(courses.sittingForUpdate(TENANT, SITTING)).thenReturn(Optional.of(linked));
        service.book(DOCTOR, RECORD, "key-0000002", 4L);
        verify(courses, never()).updateSitting(any(), any(), any(), any());
    }

    @Test
    void aStaleRevisionABadKeyAndANonSurgeryRecordAreRefused() {
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, 3L)).satisfies(e -> assertThat(
                ((EmrProblemException) e).slug()).isEqualTo("emr-record-changed"));
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, "x", 4L)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, null, 4L)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, null)).isInstanceOf(InvalidFieldsException.class);
        starter = "gm";
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, 4L)).satisfies(e -> assertThat(
                ((EmrProblemException) e).slug()).isEqualTo("not-applicable"));
        verify(recordRepository, never()).saveSectionsLocked(any(), any(), anyString(), any());
    }

    @Test
    void aPlanWithoutAProcedureOrDateCannotBeBooked() {
        sections = sections.replace("\"otDate\":\"2026-11-24\",", "");
        assertThatThrownBy(() -> service.book(DOCTOR, RECORD, KEY, 4L)).isInstanceOf(InvalidFieldsException.class);
    }
}
