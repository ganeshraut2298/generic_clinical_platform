package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.ServiceRepository.ServiceRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.RehabInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.application.emr.specialty.VisitTemplateRepository.Row;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Premed;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The specialty reference endpoint (ADR-023 D4), the visit templates (D10, H12) and the server-computed specialty view
 * (D4). Every rule value is tagged "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed".
 */
class ReferenceTemplateAndViewTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final String TAG = "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed";

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Protocol AC = new Protocol("AC", "AC", "Doxorubicin + cyclophosphamide · every 21 days", 21,
            "HIGH", false, List.of(new Drug("doxorubicin", "Doxorubicin", 60, 15, 5, true, true, "Vesicant"),
            new Drug("cyclophosphamide", "Cyclophosphamide", 600, 30, 20, false, false, null)),
            List.of(new Premed("ondansetron_iv", "Ondansetron 8 mg IV", 15)));
    private static final SpecialtyReferenceData DATA = new SpecialtyReferenceData(
            List.of(new Group("birth", "Birth", 1, 0, "DAYS", 6, "WEEKS"),
                    new Group("m16", "16–18 months", 2, 16, "MONTHS", 19, "MONTHS")),
            List.of(new Vaccine("bcg", "birth", "BCG", 1), new Vaccine("hib_booster_1", "m16", "Hib booster 1", 1)),
            List.of(new GrowthBand("WEIGHT", null, 18, 18, 8.6, "Below 3rd centile", true, 1),
                    new GrowthBand("WEIGHT", null, 18, 18, null, "Above 97th centile", true, 2),
                    new GrowthBand("LENGTH", null, 18, 18, null, "~40th centile", false, 1)),
            List.of(new SpecialtyReferenceData.Milestone(18, "walks_alone", "Walks alone", "GROSS_MOTOR", 1)),
            List.of(new SpecialtyReferenceData.JointItem("knee", "ROM", "flexion", "Flexion", 0, 135, 1),
                    new SpecialtyReferenceData.JointItem("knee", "ROM", "extension", "Extension", 0, 0, 2),
                    new SpecialtyReferenceData.JointItem("knee", "MMT", "quadriceps", "Quadriceps", null, null, 1)),
            List.of(AC));

    // ---- reference ----

    private final EmrAccess access = mock(EmrAccess.class);
    private final ServiceRepository services = mock(ServiceRepository.class);
    private final SpecialtyReferenceService reference = new SpecialtyReferenceService(access, () -> DATA, services,
            MAPPER);

    private ObjectNode referenceOf(String profile) {
        when(services.findAll(TENANT, true)).thenReturn(List.of(new ServiceRow(SERVICE, "composite  FILLING", "Dental",
                150_000, true), new ServiceRow(UUID.randomUUID(), "Lap cholecystectomy", "Surgery", 6_500_000, true)));
        return reference.get(DOCTOR, profile);
    }

    @Test
    void everyProfileAnswersWithTheTagAndItsOwnLists() {
        for (String profile : SpecialtyProfiles.PROFILES) {
            ObjectNode ref = referenceOf(profile);
            assertThat(ref.path("profile").asText()).isEqualTo(profile);
            assertThat(ref.path("tagNote").asText()).isEqualTo(TAG);
            assertThat(ref.path("lists").size()).as(profile).isGreaterThan(0);
            assertThat(ref.path("thresholds").size()).as(profile).isGreaterThan(0);
        }
        verify(access, org.mockito.Mockito.times(7)).requireConsultationWrite(DOCTOR);
    }

    @Test
    void aProfileOutsideTheSevenIsBadRequestAndAnUnauthorisedRoleIsRefusedBeforeAnyRead() {
        assertThatThrownBy(() -> reference.get(DOCTOR, "ortho")).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> assertThat(((EmrProblemException) e).slug()).isEqualTo("bad-request"));
        when(access.requireConsultationWrite(DOCTOR)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> reference.get(DOCTOR, "gm")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void dentalSuggestionsCarryTheClinicTariffMatchIgnoringCaseAndSpacesElseNullTaggedH17() {
        JsonNode suggestions = referenceOf("dental").path("lists").path("dentalSuggestions");
        JsonNode composite = null;
        JsonNode bridge = null;
        for (JsonNode item : suggestions) {
            if ("COMPOSITE_FILLING".equals(item.path("key").asText())) {
                composite = item;
            }
            if ("BRIDGE".equals(item.path("key").asText())) {
                bridge = item;
            }
        }
        assertThat(composite.path("tariff").path("serviceId").asText()).isEqualTo(SERVICE.toString());
        assertThat(composite.path("tariff").path("pricePaise").asLong()).isEqualTo(150_000);
        assertThat(composite.path("group").asText()).isEqualTo("CARIES");
        assertThat(bridge.path("tariff").isNull()).as("Not in tariff").isTrue();
        assertThat(referenceOf("dental").toString()).as("the mockup's sample rates are never returned")
                .doesNotContain("4500").doesNotContain("30000");
        JsonNode thresholds = referenceOf("dental").path("thresholds");
        assertThat(thresholds.path("dentalRootCanalSittings").asInt()).isEqualTo(3);
        assertThat(thresholds.path("dentalEstimateStepPaise").asLong()).isEqualTo(5000);
    }

    @Test
    void oncologyReturnsTheProtocolsAndTheTaggedThresholds() {
        ObjectNode ref = referenceOf("onco");
        JsonNode t = ref.path("thresholds");
        assertThat(t.path("oncBsaCap").asDouble()).isEqualTo(2.0);
        assertThat(t.path("oncDoxorubicinLifetime").asDouble()).isEqualTo(450.0);
        assertThat(t.path("oncLabHbMin").asDouble()).isEqualTo(8.0);
        assertThat(t.path("oncLabAncMin").asInt()).isEqualTo(1500);
        assertThat(t.path("oncLabsFreshHours").asInt()).isEqualTo(72);
        assertThat(t.path("oncWeightChangeRecalculatePct").asDouble()).isEqualTo(10.0);
        assertThat(ref.path("protocols").get(0).path("key").asText()).isEqualTo("AC");
        assertThat(ref.path("protocols").get(0).path("drugs").get(0).path("vesicant").asBoolean()).isTrue();
        assertThat(ref.path("lists").path("oncDoseLevels")).hasSize(4);
        assertThat(ref.path("lists").path("oncDecisions")).hasSize(5);
        assertThat(ref.path("lists").path("oncLabs")).hasSize(7);
    }

    @Test
    void paediatricsHidesTheLengthAndHeadCircumferenceBandsPerDec044H8() {
        ObjectNode ref = referenceOf("paeds");
        assertThat(ref.path("growthBands")).hasSize(2).allMatch(b -> "WEIGHT".equals(b.path("measure").asText()));
        assertThat(ref.path("lists").path("paedsDoseRules")).hasSize(3);
        assertThat(ref.path("thresholds").path("paedsParacetamol1205MaxMg").asInt()).isEqualTo(500);
        assertThat(ref.path("thresholds").path("paedsParacetamol1205MgPerMl").asInt()).isEqualTo(24);
        assertThat(ref.path("thresholds").path("paedsParacetamol1205MgPerKg").isMissingNode()).isFalse();
    }

    @Test
    void physioAndSurgeryListsAreInTheDrawnOrder() {
        JsonNode physio = referenceOf("physio").path("lists");
        assertThat(physio.path("physioModalities")).extracting(m -> m.path("key").asText()).containsExactly("IFT", "HOT_PACK",
                "ULTRASOUND", "TENS", "EXERCISE_THERAPY", "TAPING");
        assertThat(physio.path("jointRom").get(0).path("sub").asText()).isEqualTo("0–135°");
        JsonNode surgery = referenceOf("surgery").path("lists");
        assertThat(surgery.path("surgeryProcedures")).hasSize(8);
        assertThat(surgery.path("surgeryProcedures").get(0).path("tariff").path("pricePaise").asLong())
                .isEqualTo(6_500_000);
        assertThat(surgery.path("surgeryProcedures").get(0).path("defaults").path("admission").asText())
                .isEqualTo("IPD_1_2_DAYS");
        assertThat(surgery.path("surgeryConsentItems")).hasSize(5);
        assertThat(surgery.path("surgeryBlood")).extracting(a -> a.path("key").asText())
                .containsExactly("NOT_NEEDED", "GROUP_AND_SAVE", "ONE_UNIT_RESERVED");
    }

    // ---- visit templates ----

    private final VisitTemplateRepository repo = mock(VisitTemplateRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final VisitTemplateService templates = new VisitTemplateService(access, repo, audit, MAPPER);

    private VisitTemplateService.Input template(String name, String items) {
        return new VisitTemplateService.Input(name, null, json(items));
    }

    private void doctor() {
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0));
    }

    @Test
    void aTemplateIsSavedWithItsItemsSharedAndAuditedWithCountsOnly() {
        doctor();
        when(repo.nameExists(TENANT, "Fever, adult")).thenReturn(false);
        when(repo.countActive(TENANT)).thenReturn(3L);

        ObjectNode created = templates.create(DOCTOR, template("Fever, adult", "{'complaints':[{'itemKey':null,"
                + "'text':'Fever','newToList':true}],'medicines':[{'medicineId':null,'name':'Paracetamol 500 mg',"
                + "'frequency':'1-1-1','days':3,'when':'AFTER_FOOD'}],'repeatLastRx':false}"));

        assertThat(created.path("name").asText()).isEqualTo("Fever, adult");
        assertThat(created.path("items").path("complaints")).hasSize(1);
        assertThat(created.path("items").has("schemaVersion")).isFalse();
        org.mockito.ArgumentCaptor<Row> row = org.mockito.ArgumentCaptor.forClass(Row.class);
        verify(repo).insert(eq(TENANT), row.capture(), eq(MEMBER), eq(USER));
        assertThat(row.getValue().items()).contains("\"schemaVersion\":1");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> after = (org.mockito.ArgumentCaptor<Map<String, Object>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_VISIT_TEMPLATE_CREATED"), eq("EMR_VISIT_TEMPLATE"), any(), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("complaints", 1).containsEntry("medicines", 1)
                .containsEntry("diagnoses", 0);
        assertThat(after.getValue().toString()).doesNotContain("Fever").doesNotContain("Paracetamol");
    }

    @Test
    void aDuplicateNameTheLimitAndABadBodyAreRefused() {
        doctor();
        when(repo.nameExists(TENANT, "Fever, adult")).thenReturn(true);
        assertThatThrownBy(() -> templates.create(DOCTOR, template("Fever, adult", "{}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("A template with this name exists"));
        when(repo.nameExists(TENANT, "New one")).thenReturn(false);
        when(repo.countActive(TENANT)).thenReturn(100L);
        assertThatThrownBy(() -> templates.create(DOCTOR, template("New one", "{}"))).isInstanceOf(
                EmrProblemException.class).satisfies(e -> assertThat(((EmrProblemException) e).slug())
                .isEqualTo("visit-template-limit"));
        when(repo.countActive(TENANT)).thenReturn(0L);
        for (VisitTemplateService.Input bad : List.of(template("", "{}"), template("x".repeat(61), "{}"),
                template("ok", "{'colour':'red'}"), template("ok", "{'complaints':[{'itemKey':null,'text':'','newToList':false}]}"),
                new VisitTemplateService.Input("ok", "s".repeat(121), json("{}")), new VisitTemplateService.Input("ok",
                        null, null), new VisitTemplateService.Input(null, null, json("{}")))) {
            assertThatThrownBy(() -> templates.create(DOCTOR, bad)).isInstanceOf(InvalidFieldsException.class);
        }
        verify(repo, never()).insert(any(), any(), any(), any());
    }

    @Test
    void theListShowsActiveTemplatesWithoutTheSchemaVersion() {
        doctor();
        when(repo.active(TENANT)).thenReturn(List.of(new Row(UUID.randomUUID(), "Fever, adult", null,
                "{\"schemaVersion\":1,\"advice\":[{\"itemKey\":null,\"text\":\"Rest\",\"newToList\":true}]}")));
        List<ObjectNode> list = templates.list(DOCTOR);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("subLine").isNull()).isTrue();
        assertThat(list.get(0).path("items").path("advice")).hasSize(1);
        assertThat(list.get(0).path("items").has("schemaVersion")).isFalse();
        when(access.requireConsultationWrite(DOCTOR)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> templates.list(DOCTOR)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.create(DOCTOR, template("a", "{}"))).isInstanceOf(
                AccessDeniedException.class);
    }

    // ---- the specialty view ----

    private static SpecialtyCtx view(String profile, Map<String, String> components, Map<String, String> sections,
                                     LocalDate dob, String sex, Hooks hooks) {
        Function<String, JsonNode> sibling = c -> components.containsKey(c) ? json(components.get(c)) : null;
        Hooks effective = new Hooks(key -> sections.containsKey(key) ? json(sections.get(key)) : null,
                hooks.earlier(), hooks.prescriptionRows(), hooks.hasActiveDiagnosis(), hooks.rehab(), hooks.chemo());
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, MEMBER, dob, sex, DATA, Tariff.none(), Set.of(), null,
                sibling, effective);
    }

    private static JsonNode build(String profile, SpecialtyCtx ctx, String mode, String decision) {
        return SpecialtyViewBuilder.build(profile, ctx, mode, decision == null ? null : json(decision));
    }

    @Test
    void generalMedicineFlagsTheLatestVitalsEntryTaggedC1() {
        SpecialtyCtx ctx = view("gm", Map.of("VITALS_LOG", "{'entries':[{'id':'a','pulse':50},{'id':'b','bpSystolic':150,"
                + "'bpDiastolic':85,'spo2':93,'temperature':{'value':100.4,'unit':'F'},'respRate':22}]}"), Map.of(), null,
                "F", Hooks.none());
        JsonNode view = build("gm", ctx, null, null);
        assertThat(view.path("tagNote").asText()).isEqualTo(TAG);
        assertThat(view.path("vitalsFlags")).extracting(f -> f.path("field").asText() + "=" + f.path("label").asText())
                .containsExactly("bp=High", "spo2=Low", "temperature=Fever", "respRate=High");
        assertThat(view.path("mode").isNull()).isTrue();
        assertThat(build("gm", view("gm", Map.of(), Map.of(), null, "F", Hooks.none()), null, null).path("vitalsFlags"))
                .isEmpty();
    }

    @Test
    void dentalViewHasTheEstimateStateTheRowsDoneTodayAndTheChildTabForUnderThirteenTaggedH15() {
        SpecialtyCtx ctx = view("dental", Map.of("DENTAL_PLAN", "{'rows':[{'id':'r1','status':'DONE','doneOn':'2026-11-12'},"
                + "{'id':'r2','status':'DONE','doneOn':'2026-11-01'},{'id':'r3','status':'PLANNED'}],"
                + "'estimate':{'state':'CHANGED','totalPaise':350000}}"), Map.of(), LocalDate.of(2018, 3, 1), "M",
                Hooks.none());
        JsonNode dental = build("dental", ctx, null, null).path("dental");
        assertThat(dental.path("estimateTotalPaise").asLong()).isEqualTo(350_000);
        assertThat(dental.path("estimateState").asText()).isEqualTo("CHANGED");
        assertThat(dental.path("doneTodayRowIds")).extracting(JsonNode::asText).containsExactly("r1");
        assertThat(dental.path("childTabDefault").asBoolean()).isTrue();
        assertThat(build("dental", view("dental", Map.of(), Map.of(), null, "F", Hooks.none()), null, null)
                .path("dental").path("childTabDefault").asBoolean()).isFalse();
    }

    @Test
    void obstetricsViewWorksOutTheDatesNotesAlertsAndScanStatesTaggedC4() {
        String anc = "{'today':{'weightKg':61.5,'bpSystolic':142,'bpDiastolic':88,'fundalHeightCm':30,'fetalHeartBpm':170,"
                + "'hbGdl':9.5},'symptoms':['REDUCED_MOVEMENTS'],'orders':[{'key':'OGTT_75G'},{'key':'GROWTH_SCAN_FORM_F'}],"
                + "'scans':[{'key':'DATING','done':true},{'key':'NT','done':true}]}";
        LocalDate lmp = TODAY.minusDays(27 * 7 + 1);
        Hooks hooks = new Hooks(k -> null, c -> "ANC_CHECK".equals(c) ? List.of(new Earlier(UUID.randomUUID(),
                TODAY.minusDays(28), json("{'today':{'weightKg':60.0}}"))) : List.of(), List::of, () -> false,
                () -> null, ChemoInfo::none);
        SpecialtyCtx ctx = view("og", Map.of("ANC_CHECK", anc), Map.of("menstrual", "{'lmp':'" + lmp + "'}",
                "obstetric", "{'previous_lscs':true}"), null, "F", hooks);

        JsonNode og = build("og", ctx, "ANC", null).path("og");

        assertThat(og.path("gestationalAge").path("weeks").asInt()).isEqualTo(27);
        assertThat(og.path("gestationalAge").path("days").asInt()).isEqualTo(1);
        assertThat(og.path("edd").asText()).isEqualTo(lmp.plusDays(280).toString());
        assertThat(og.path("eddSource").asText()).isEqualTo("LMP");
        assertThat(og.path("weightChangeKg").asDouble()).isEqualTo(1.5);
        assertThat(og.path("notes")).extracting(n -> n.path("label").asText()).containsExactly("High: 140/90 or above",
                "Does not match 27 weeks", "Outside 110–160", "Moderate anaemia");
        assertThat(og.path("alerts")).extracting(JsonNode::asText).containsExactly("POSSIBLE_PRE_ECLAMPSIA",
                "ANAEMIA_MODERATE", "WARNING_SIGN");
        assertThat(og.path("riskChips").get(0).path("label").asText()).isEqualTo("High risk: previous LSCS");
        assertThat(og.path("scanStates")).extracting(s -> s.path("key").asText() + "=" + s.path("state").asText())
                .containsExactly("DATING=DONE", "NT=DONE", "ANOMALY=DUE", "GROWTH=LATER", "DOPPLER_NST=LATER");
        assertThat(og.path("dueOrders").get(0).path("ordered").asBoolean()).isTrue();
        assertThat(og.path("dueOrders").get(1).path("ordered").asBoolean()).isFalse();
        assertThat(og.path("formFRequired").asBoolean()).isTrue();
    }

    @Test
    void withoutAnLmpOrDatingScanTheGestationalAgeIsNotRecordedAndFundalNeedsOne() {
        SpecialtyCtx ctx = view("og", Map.of("ANC_CHECK", "{'today':{'fundalHeightCm':20}}"), Map.of(), null, "F",
                Hooks.none());
        JsonNode og = build("og", ctx, null, null).path("og");
        assertThat(og.path("gestationalAge").isNull()).isTrue();
        assertThat(og.path("eddSource").asText()).isEqualTo("NONE");
        assertThat(og.path("notes").get(0).path("label").asText()).isEqualTo("Needs LMP or dating scan");
    }

    @Test
    void paediatricsViewHasTheAgeWeightNoteVaccineStatesAndNoLengthOrHeadNotePerDec044H8() {
        SpecialtyCtx ctx = view("paeds", Map.of("GROWTH", "{'weightKg':8.5,'lengthCm':80.0,'headCircCm':47.0}",
                "IMMUNIZATION", "{'givenToday':[{'vaccineKey':'hib_booster_1'}],'scheduleReviewed':false}"), Map.of(),
                TODAY.minusMonths(18).minusDays(3), "F", new Hooks(k -> null, c -> List.of(), () -> List.of(json(
                        "{'id':'rx1','calculation':{'weightKg':9.0}}"), json("{'id':'rx2','calculation':{'weightKg':8.5}}"),
                        json("{'id':'rx3','calculation':null}")), () -> false, () -> null, ChemoInfo::none));
        JsonNode paeds = build("paeds", ctx, null, null).path("paeds");
        assertThat(paeds.path("ageMonths").asInt()).isEqualTo(18);
        assertThat(paeds.path("ageDays").asInt()).isEqualTo(3);
        assertThat(paeds.path("growthNotes")).hasSize(1);
        assertThat(paeds.path("growthNotes").get(0).path("label").asText()).isEqualTo("Below 3rd centile");
        assertThat(paeds.path("growthNotes").get(0).path("alert").asBoolean()).isTrue();
        assertThat(paeds.path("vaccines")).extracting(v -> v.path("vaccineKey").asText() + "=" + v.path("state").asText())
                .containsExactly("bcg=OVERDUE", "hib_booster_1=GIVEN");
        assertThat(paeds.path("vaccines").get(1).path("givenHere").asBoolean()).isTrue();
        assertThat(paeds.path("overdueCount").asInt()).isEqualTo(1);
        assertThat(paeds.path("dueTodayCount").asInt()).isZero();
        assertThat(paeds.path("milestonesSetUp").asBoolean()).isTrue();
        assertThat(paeds.path("doseWeightKg").asDouble()).isEqualTo(8.5);
        assertThat(paeds.path("doseWeightChangedRowIds")).extracting(JsonNode::asText).containsExactly("rx1");
        SpecialtyCtx unknownAge = view("paeds", Map.of("GROWTH", "{'weightKg':8.5}"), Map.of(), null, "F", Hooks.none());
        JsonNode noAge = build("paeds", unknownAge, null, null).path("paeds");
        assertThat(noAge.path("ageMonths").isNull()).isTrue();
        assertThat(noAge.path("growthNotes").get(0).path("label").asText()).isEqualTo("Centile not available for this age");
        assertThat(noAge.path("milestonesSetUp").asBoolean()).isFalse();
    }

    @Test
    void physioViewHasTheSessionOfTheActivePackageAndTheRomBarsTaggedC6() {
        Hooks hooks = new Hooks(k -> null, c -> List.of(), List::of, () -> false,
                () -> new RehabInfo("Knee rehab · 10 sessions", 3, 10), ChemoInfo::none);
        SpecialtyCtx ctx = view("physio", Map.of("ROM_TABLE", "{'joint':'knee','rows':[{'movement':'flexion',"
                + "'rightDeg':105,'leftDeg':135},{'movement':'extension','rightDeg':0,'leftDeg':0},{'movement':"
                + "'pivot','rightDeg':20,'leftDeg':null}]}"), Map.of(), null, "M", hooks);
        JsonNode physio = build("physio", ctx, "SESSION", null).path("physio");
        assertThat(physio.path("sessionNo").asInt()).isEqualTo(3);
        assertThat(physio.path("sessionsPlanned").asInt()).isEqualTo(10);
        assertThat(physio.path("packageName").asText()).isEqualTo("Knee rehab · 10 sessions");
        assertThat(physio.path("romPercents").get(0).path("rightPct").asInt()).isEqualTo(78);
        assertThat(physio.path("romPercents").get(0).path("normal").asText()).isEqualTo("0–135°");
        assertThat(physio.path("romPercents").get(1).path("rightPct").asInt()).isEqualTo(100);
        assertThat(physio.path("romPercents").get(2).path("rightPct").isNull()).as("a typed movement has no normal range")
                .isTrue();
        assertThat(build("physio", ctx, "SESSION", null).path("mode").asText()).isEqualTo("SESSION");
    }

    @Test
    void surgeryViewHasFitnessDepositPayerChipBookStateAndMissingPartsTaggedC7B1B3() {
        Map<String, String> components = Map.of(
                "SURGERY_FITNESS", "{'pac':'FIT','tests':[{'key':'PT_INR','state':'PENDING'},{'key':'CBC','state':'NORMAL'}]}",
                "SURGERY_PLAN", "{'payer':'INSURANCE_TPA','packagePaise':6500000,'procedureText':'Lap cholecystectomy',"
                        + "'booking':null}",
                "CONSENT_CHECKLIST", "{'items':[{'key':'PROCEDURE_RISKS','confirmed':true},{'key':'ANAESTHESIA',"
                        + "'confirmed':false}]}",
                "PREAUTH", "{'sent':false}");
        Hooks hooks = new Hooks(k -> null, c -> List.of(), List::of, () -> true, () -> null, ChemoInfo::none);
        JsonNode surgery = build("surgery", view("surgery", components, Map.of(), null, "M", hooks), null, null)
                .path("surgery");
        assertThat(surgery.path("fitness").path("text").asText()).isEqualTo("1 test(s) pending: PT/INR");
        assertThat(surgery.path("fitness").path("state").asText()).isEqualTo("TESTS_PENDING");
        assertThat(surgery.path("depositPaise").asLong()).isEqualTo(1_000_000);
        assertThat(surgery.path("payerChip").asText()).isEqualTo("Insurance (TPA) · pre-auth pending");
        assertThat(surgery.path("bookState").asText()).isEqualTo("NOT_BOOKED");
        assertThat(surgery.path("bookMissing")).extracting(JsonNode::asText).containsExactly("4 consent item(s)",
                "pre-authorisation");
        assertThat(surgery.path("consentCount").path("ticked").asInt()).isEqualTo(1);
        assertThat(surgery.path("consentCount").path("total").asInt()).isEqualTo(5);
        Map<String, String> self = Map.of("SURGERY_PLAN", "{'payer':'SELF','packagePaise':6500000,'booking':null}",
                "CONSENT_CHECKLIST", "{'items':[{'key':'PROCEDURE_RISKS','confirmed':true},{'key':'CONVERSION_TO_OPEN',"
                        + "'confirmed':true},{'key':'ANAESTHESIA','confirmed':true},{'key':'BLOOD_TRANSFUSION',"
                        + "'confirmed':true},{'key':'PHOTOS_VIDEO','confirmed':true}]}");
        JsonNode ready = build("surgery", view("surgery", self, Map.of(), null, "M", hooks), null, null).path("surgery");
        assertThat(ready.path("bookState").asText()).isEqualTo("READY");
        assertThat(ready.path("depositPaise").asLong()).isEqualTo(3_300_000);
        assertThat(ready.path("payerChip").asText()).isEqualTo("Self-pay");
        assertThat(ready.path("bookMissing")).isEmpty();
    }

    @Test
    void oncologyViewCarriesTheEngineResultsTheLifetimeDoseAndTheNextCycleTaggedC8() {
        Map<String, String> components = Map.of(
                "ONC_EXAM", "{'heightCm':160,'weightKg':60,'capAt2':true,'lesions':[{'id':'l1','name':'L','baselineCm':5.0,"
                        + "'lastCm':4.0,'todayCm':3.0}],'newLesionSeen':false,'systems':{'cvs':{'state':'NORMAL'}},"
                        + "'line':{'device':'PORT','checks':['FLUSHES_FREELY','BLOOD_RETURN','NO_REDNESS_SWELLING']}}",
                "ONC_LABS", "{'drawnAt':'2026-11-12T04:00:00Z','values':{'hb':10.5,'anc':1000,'plt':200,'cr':0.8,"
                        + "'bili':0.7,'alt':30,'lvef':50}}",
                "ONC_ECOG", "{'grade':1}",
                "ONC_TOX", "{'symptoms':[{'key':'NAUSEA','confirmedGrade':2}],'events':['ER_VISIT']}",
                "VITALS_LOG", "{'entries':[{'id':'a','bpSystolic':165,'bpDiastolic':70,'temperature':{'value':37.0,"
                        + "'unit':'C'}}]}");
        ChemoInfo info = new ChemoInfo(60.0, 120.0, 100.0, 2, Set.of("AC"), "DRAFT", null, "AC", 100, null);
        Hooks hooks = new Hooks(k -> null, c -> List.of(), List::of, () -> false, () -> null, () -> info);
        String decision = "{'choice':'GIVE_AS_PLANNED','reasonKey':null,'suggestionAtDecision':'GIVE_AS_PLANNED',"
                + "'decidedAt':'2026-11-12T05:00:00Z','decidedByMembershipId':'" + MEMBER + "'}";
        JsonNode view = build("onco", view("onco", components, Map.of(), null, "F", hooks), null, decision);

        assertThat(view.path("vitalsFlags")).extracting(f -> f.path("label").asText()).containsExactly("Check again");
        JsonNode onco = view.path("onco");
        assertThat(onco.path("bsa").asDouble()).isEqualTo(1.63);
        assertThat(onco.path("capped").asBoolean()).isFalse();
        assertThat(onco.path("cycle1WeightKg").asDouble()).isEqualTo(60.0);
        assertThat(onco.path("weightChangePct").asDouble()).isZero();
        assertThat(onco.path("response").asText()).isEqualTo("PARTIAL_RESPONSE");
        assertThat(onco.path("lesionChanges").get(0).path("colour").asText()).isEqualTo("GREEN");
        assertThat(onco.path("sums").path("baseline").asDouble()).isEqualTo(5.0);
        assertThat(onco.path("labFails")).extracting(f -> f.path("key").asText()).containsExactly("anc");
        assertThat(onco.path("labsBadge").asText()).isEqualTo("1 out of range");
        assertThat(onco.path("labsFresh").asBoolean()).isTrue();
        assertThat(onco.path("ecogText").asText()).isEqualTo("ECOG 1 · fit for chemo");
        assertThat(onco.path("lineReady").asBoolean()).isTrue();
        assertThat(onco.path("suggestion").asText()).isEqualTo("DELAY_1_WEEK");
        assertThat(onco.path("feverOrAdmissionEvent").asBoolean()).isFalse();
        assertThat(onco.path("worstGrade").asInt()).isEqualTo(2);
        assertThat(onco.path("reasons")).hasSize(6);
        assertThat(onco.path("factsChanged").asBoolean()).as("the decision was made on another suggestion").isTrue();
        assertThat(onco.path("decision").path("choice").asText()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(onco.path("alerts")).extracting(JsonNode::asText).containsExactly("LAB_OUT_OF_RANGE");
        assertThat(onco.path("cumulativeDoxorubicinMgM2").asDouble()).isEqualTo(280.0);
        assertThat(onco.path("cycleNo").asInt()).isEqualTo(3);
        assertThat(onco.path("nextCycle").path("defaultDate").asText()).isEqualTo(TODAY.plusDays(21).toString());
        assertThat(onco.path("nextCycle").path("label").asText()).isEqualTo("NEXT_CHEMO");
        assertThat(onco.path("nextCycle").path("bloodTestDate").asText()).isEqualTo(TODAY.plusDays(20).toString());
        assertThat(onco.path("orderStatus").asText()).isEqualTo("DRAFT");
        List<String> keys = new ArrayList<>();
        onco.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).contains("bsa", "bsaUsed", "capped", "labsFresh", "suggestion", "reasons", "nextCycle");
    }

    @Test
    void aDelayDecisionRebooksTheNextCycleAWeekLater() {
        Hooks hooks = new Hooks(k -> null, c -> List.of(), List::of, () -> false, () -> null, ChemoInfo::none);
        String decision = "{'choice':'DELAY_1_WEEK','reasonKey':'CLINICAL_JUDGEMENT','suggestionAtDecision':null,"
                + "'decidedAt':'2026-11-12T05:00:00Z','decidedByMembershipId':'" + MEMBER + "'}";
        JsonNode onco = build("onco", view("onco", Map.of(), Map.of(), null, "F", hooks), null, decision).path("onco");
        assertThat(onco.path("nextCycle").path("defaultDate").asText()).isEqualTo(TODAY.plusDays(7).toString());
        assertThat(onco.path("nextCycle").path("label").asText()).isEqualTo("REBOOKED_CHEMO");
        assertThat(onco.path("cycleNo").asInt()).isEqualTo(1);
        assertThat(onco.path("decision").path("stamp").path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
    }

}
