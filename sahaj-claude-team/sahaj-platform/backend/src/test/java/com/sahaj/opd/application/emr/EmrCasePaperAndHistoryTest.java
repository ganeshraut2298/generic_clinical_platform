package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.PatientIpdHistorySource;
import com.sahaj.opd.api.PatientIpdHistorySource.Access;
import com.sahaj.opd.api.PatientIpdHistorySource.AdmissionHistory;
import com.sahaj.opd.application.emr.EmrCasePaperService.CasePaperContext;
import com.sahaj.opd.application.emr.EmrHistoryService.KeyFacts;
import com.sahaj.opd.application.emr.EmrHistoryService.PastVisitPage;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.CasePaperRepository;
import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicLetterheadPort.Letterhead;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmrCasePaperAndHistoryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository repository = mock(EmrRecordRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);

    private static JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---- case paper ----

    @Test
    void everySectionGoesUnderOneOfTheTenHeadingsAndTheAdministrativeOnesUnderNone() {
        assertThat(EmrCasePaperService.HEADINGS).hasSize(10);
        assertThat(EmrCasePaperService.HEADINGS.get(0).mr()).isEqualTo("मुख्य तक्रारी");
        assertThat(EmrCasePaperService.headingOf("complaints")).isEqualTo("CHIEF_COMPLAINTS");
        assertThat(EmrCasePaperService.headingOf("rx")).isEqualTo("MEDICINES");
        assertThat(EmrCasePaperService.headingOf("vitals")).isEqualTo("VITALS_LINE");
        assertThat(EmrCasePaperService.headingOf("abha")).isEqualTo("NONE");
        assertThat(EmrCasePaperService.headingOf("dental_chart")).isEqualTo("EXAMINATION");
        assertThat(EmrCasePaperService.headingOf("f_abcdefghij")).isEqualTo("EXAMINATION");
    }

    @Test
    void theContextHasLetterheadDoctorQualificationClinicDefaultsAndNoShareAndBlocksPrintOnAnAllergyAlert() {
        RecordView view = view(List.of("ALLERGY_ALERT"));
        when(records.get(DOCTOR, RECORD)).thenReturn(view);
        ClinicLetterheadPort letterheads = mock(ClinicLetterheadPort.class);
        when(letterheads.find(TENANT)).thenReturn(Optional.of(new Letterhead("Patil Clinic", "Main road, Pune", null)));
        CasePaperRepository paper = mock(CasePaperRepository.class);
        when(paper.find(TENANT)).thenReturn(Optional.of(new PaperRow("A5", "mr", false, false, Map.of())));
        when(team.doctorDetails(TENANT, MEMBER)).thenReturn(Optional.of(new DoctorDetails(MEMBER, "Dr Test", "DOCTOR",
                "ACTIVE", "gm", "MBBS", null)));

        CasePaperContext context = new EmrCasePaperService(records, letterheads, paper, team).context(DOCTOR, RECORD);

        assertThat(context.letterhead().clinicName()).isEqualTo("Patil Clinic");
        assertThat(context.doctor().qualification()).isEqualTo("MBBS");
        assertThat(context.doctor().registrationNo()).isNull();
        assertThat(context.clinicDefaults().paperSize()).isEqualTo("A5");
        assertThat(context.clinicDefaults().language()).isEqualTo("MR");
        assertThat(context.clinicDefaults().capitals()).isFalse();
        assertThat(context.visitLine()).isEqualTo("OPD · follow-up visit");
        assertThat(context.printBlocked()).isEqualTo("ALLERGY_ALERT");
        assertThat(context.share().available()).isFalse();
        assertThat(context.share().reason()).isEqualTo("Needs the WhatsApp and ABHA services");
        assertThat(context.signature().signed()).isFalse();
        assertThat(context.toString()).doesNotContain("Meena");
        assertThat(context.patient().toString()).doesNotContain("Meena");
    }

    private RecordView view(List<String> blockers) {
        return new RecordView(RECORD, "OPD_VISIT", UUID.randomUUID(), null, "DRAFT", false, null, 1,
                new EmrViews.RecordHeader(PATIENT, "Meena Patil", "MP", 41, "F", "UH-1", null, null, null, 2,
                        "FOLLOW_UP", new EmrViews.DoctorOnRecord(MEMBER, "Dr Test", null), null, null, false),
                new EmrViews.Snapshot("gm", "General Medicine", "gm", "STARTER", 1, "X", NOW,
                        List.of(EmrTestData.section("rx", true, false))),
                List.of(), List.of(), List.of(), true, new EmrViews.UiState("ASSESSMENT", null), false, null, List.of(),
                new EmrViews.IpdState("OFF", false), List.of(), blockers, null, new EmrViews.PrintPreferences("HI", null),
                false);
    }

    // ---- history ----

    private EmrHistoryService history(PatientIpdHistorySource ipd, PatientClinicalPort patients) {
        return new EmrHistoryService(records, repository, ipd, patients, team, EmrTestData.MAPPER);
    }

    private RecordRow row(UUID id, String type, String status, Instant created, String sections) {
        return new RecordRow(id, type, UUID.randomUUID(), null, null, null, PATIENT, MEMBER, "gm", "General Medicine",
                "gm", "STARTER_LAYOUT", null, 1, "X", status, "SIGNED".equals(status) ? created : null, sections,
                "ASSESSMENT", null, false, null, 1, created);
    }

    private static String signedDoc() {
        return "{\"complaints\":{\"values\":{\"complaint\":{\"component\":\"EMR_LIST\",\"kind\":\"COMPLAINTS\","
                + "\"items\":[{\"id\":\"a\",\"text\":\"Headache\",\"active\":true}]}}},"
                + "\"diagnosis\":{\"values\":{\"d\":{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"items\":["
                + "{\"id\":\"b\",\"text\":\"Migraine\",\"code\":\"G43.9\",\"active\":true}]}}},"
                + "\"rx\":{\"values\":{\"drug\":{\"component\":\"PRESCRIPTION\",\"rows\":[{\"id\":\"c\","
                + "\"name\":\"Paracetamol 650 mg\",\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\"}]}}}}";
    }

    @Test
    void theListShowsOpdAndAllowedIpdRowsNewestFirstCountsThemAndHidesOutOfScopeAdmissions() {
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(new TeamMember(MEMBER, "Dr", "DOCTOR", true, 0),
                row(RECORD, "OPD_VISIT", "DRAFT", NOW, "{}"), PATIENT));
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(repository.forPatient(TENANT, PATIENT, 500)).thenReturn(List.of(
                row(RECORD, "OPD_VISIT", "DRAFT", NOW, "{}"),
                row(newer, "OPD_VISIT", "SIGNED", NOW.minusSeconds(86_400L * 10), signedDoc()),
                row(older, "OPD_VISIT", "DRAFT", NOW.minusSeconds(86_400L * 40), "{}")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(MEMBER, new TeamMember(MEMBER, "Sharma", "DOCTOR", true,
                0)));
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(new TeamMember(MEMBER, "Sharma", "DOCTOR", true, 0)));
        UUID allowed = UUID.randomUUID();
        PatientIpdHistorySource ipd = mock(PatientIpdHistorySource.class);
        when(ipd.admissionsOf(TENANT, PATIENT, 200)).thenReturn(List.of(
                new AdmissionHistory(allowed, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), "DISCHARGED",
                        "Dengue", false, MEMBER, "Ward A", "A-12", Access.ALLOWED),
                new AdmissionHistory(UUID.randomUUID(), LocalDate.of(2026, 8, 1), null, "ADMITTED", "Secret", true,
                        MEMBER, "ICU", "I-1", Access.OUTSIDE_SCOPE)));
        EmrHistoryService service = history(ipd, mock(PatientClinicalPort.class));

        PastVisitPage all = service.list(DOCTOR, RECORD, "ALL", null, 0);

        assertThat(all.counts().opd()).isEqualTo(2);
        assertThat(all.counts().ipd()).isEqualTo(1);
        assertThat(all.someHidden()).isTrue();
        assertThat(all.items()).extracting(v -> v.id()).containsExactly(newer, older, allowed);
        assertThat(all.items().get(0).title()).isEqualTo("Migraine");
        assertThat(all.items().get(0).place()).isEqualTo("Dr. Sharma · General Medicine");
        assertThat(all.items().get(0).signed()).isTrue();
        assertThat(all.items().get(2).place()).isEqualTo("Dr. Sharma · Ward A bed A-12");
        assertThat(all.items()).noneMatch(v -> v.title().contains("Secret"));

        assertThat(service.list(DOCTOR, RECORD, "IPD", null, 0).items()).hasSize(1);
        assertThat(service.list(DOCTOR, RECORD, "OPD", "paracetamol", 0).items()).extracting(v -> v.id())
                .containsExactly(newer);
        assertThat(service.list(DOCTOR, RECORD, "ALL", "G43", 0).items()).hasSize(1);
        assertThat(service.list(DOCTOR, RECORD, "ALL", "nothing like this", 0).items()).isEmpty();
        assertThat(service.list(DOCTOR, RECORD, "ALL", "SHARMA", 0).items()).hasSize(3);
    }

    @Test
    void anUnsignedRecordMatchesOnlyItsTitleAndPlaceNotItsContent() {
        UUID draft = UUID.randomUUID();
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(new TeamMember(MEMBER, "Dr", "DOCTOR", true, 0),
                row(RECORD, "OPD_VISIT", "DRAFT", NOW, "{}"), PATIENT));
        when(repository.forPatient(TENANT, PATIENT, 500)).thenReturn(List.of(row(draft, "OPD_VISIT", "DRAFT", NOW,
                signedDoc())));
        PatientIpdHistorySource ipd = mock(PatientIpdHistorySource.class);
        EmrHistoryService service = history(ipd, mock(PatientClinicalPort.class));

        assertThat(service.list(DOCTOR, RECORD, "ALL", "paracetamol", 0).items()).isEmpty();
        assertThat(service.list(DOCTOR, RECORD, "ALL", "migraine", 0).items()).hasSize(1);
        assertThat(service.list(DOCTOR, RECORD, "ALL", null, 0).items().get(0).signed()).isFalse();
    }

    @Test
    void anEmergencyRecordWithNoPatientAnswersAnEmptyPageAndNeverAsksIpd() {
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(new TeamMember(MEMBER, "Dr", "DOCTOR", true, 0),
                row(RECORD, "ER_ASSESSMENT", "DRAFT", NOW, "{}"), null));
        PatientIpdHistorySource ipd = mock(PatientIpdHistorySource.class);

        PastVisitPage page = history(ipd, mock(PatientClinicalPort.class)).list(DOCTOR, RECORD, null, null, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.counts().opd()).isZero();
        org.mockito.Mockito.verifyNoInteractions(ipd);
    }

    @Test
    void detailsComeOnlyFromSignedRecordsAndTheCopyButtonsFollowTheCurrentRecord() {
        UUID signed = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(new TeamMember(MEMBER, "Dr", "DOCTOR", true, 0),
                row(RECORD, "OPD_VISIT", "DRAFT", NOW, "{}"), PATIENT));
        when(repository.findById(TENANT, signed)).thenReturn(Optional.of(row(signed, "OPD_VISIT", "SIGNED", NOW,
                signedDoc())));
        when(repository.findById(TENANT, draft)).thenReturn(Optional.of(row(draft, "OPD_VISIT", "DRAFT", NOW, "{}")));
        EmrHistoryService service = history(mock(PatientIpdHistorySource.class), mock(PatientClinicalPort.class));

        EmrHistoryService.PastVisitDetail detail = service.detail(DOCTOR, RECORD, "OPD", signed);
        EmrHistoryService.PastVisitDetail unsigned = service.detail(DOCTOR, RECORD, "OPD", draft);

        assertThat(detail.signed()).isTrue();
        assertThat(detail.medicines()).extracting(m -> m.name()).containsExactly("Paracetamol 650 mg");
        assertThat(detail.canCopyMedicines()).isTrue();
        assertThat(detail.open().target()).isEqualTo("RECORD");
        assertThat(detail.lines()).extracting(EmrHistoryService.Line::value).contains("Headache");
        assertThat(unsigned.signed()).isFalse();
        assertThat(unsigned.lines()).isEmpty();
        assertThat(unsigned.canCopyMedicines()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.detail(DOCTOR, RECORD, "OPD", RECORD))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("past-visit-not-found"));
    }

    @Test
    void keyFactsUseTheDepartmentLabelsAndShowNotRecordedAsNullAndNeverInventNoneKnown() {
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(new TeamMember(MEMBER, "Dr", "DOCTOR", true, 0),
                row(RECORD, "OPD_VISIT", "DRAFT", NOW, "{}"), PATIENT));
        PatientClinicalPort patients = mock(PatientClinicalPort.class);
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                41, "F", "Penicillin", "  ")));
        when(repository.signedForPatient(TENANT, PATIENT, 20)).thenReturn(List.of(new EmrRecordRepository.SignedCopy(
                UUID.randomUUID(), NOW, signedDoc())));
        EmrHistoryService service = history(mock(PatientIpdHistorySource.class), patients);

        KeyFacts facts = service.keyFacts(DOCTOR, RECORD);

        assertThat(facts.facts()).extracting(EmrHistoryService.Fact::label)
                .containsExactly("Allergies", "Long-term", "Surgeries", "Regular meds");
        assertThat(facts.facts().get(0).value()).isEqualTo("Penicillin");
        assertThat(facts.facts().get(0).important()).isTrue();
        assertThat(facts.facts().get(1).value()).isNull();
        assertThat(facts.facts().get(1).important()).isFalse();
        assertThat(facts.facts().get(3).value()).isEqualTo("Paracetamol 650 mg");
    }

    @Test
    void theSevenDrawnDepartmentsHaveTheirOwnLabelsAndTheOthersUseGeneralMedicine() {
        assertThat(EmrHistoryService.definitions("dental").stream().map(d -> d.label()).toList())
                .containsExactly("Allergies", "Medical", "Habits", "Past dental");
        assertThat(EmrHistoryService.definitions("og").stream().map(d -> d.label()).toList())
                .containsExactly("Allergies", "Obstetric", "Blood group", "Risk");
        assertThat(EmrHistoryService.definitions("paeds").stream().map(d -> d.label()).toList())
                .containsExactly("Allergies", "Birth", "Vaccines", "Past illness");
        assertThat(EmrHistoryService.definitions("physio").stream().map(d -> d.label()).toList())
                .containsExactly("Diagnosis", "Referred by", "Medical", "Package");
        assertThat(EmrHistoryService.definitions("onco").stream().map(d -> d.label()).toList())
                .containsExactly("Diagnosis", "Receptors", "Allergies", "Scheme");
        assertThat(EmrHistoryService.definitions("surgery").stream().map(d -> d.label()).toList())
                .containsExactly("Allergies", "Past surgery", "Medical", "Blood thinners");
        assertThat(EmrHistoryService.definitions("ent").stream().map(d -> d.label()).toList())
                .containsExactly("Allergies", "Long-term", "Surgeries", "Regular meds");
        assertThat(EmrHistoryService.definitions(null)).hasSize(4);
    }

    @Test
    void renderingJoinsItemsAndIgnoresEmptyValues() {
        assertThat(EmrHistoryService.render(json("[\"DM\",\"HTN\"]"))).isEqualTo("DM, HTN");
        assertThat(EmrHistoryService.render(json("\"  \""))).isNull();
        assertThat(EmrHistoryService.render(json("[]"))).isNull();
        assertThat(EmrHistoryService.render(json("[{\"procedure\":\"Appendectomy\",\"year\":\"2019\"}]")))
                .isEqualTo("Appendectomy");
        assertThat(EmrHistoryService.render(json("{\"component\":\"EMR_LIST\",\"items\":[{\"text\":\"A\",\"active\":false},"
                + "{\"text\":\"B\",\"active\":true}]}"))).isEqualTo("B");
    }
}
