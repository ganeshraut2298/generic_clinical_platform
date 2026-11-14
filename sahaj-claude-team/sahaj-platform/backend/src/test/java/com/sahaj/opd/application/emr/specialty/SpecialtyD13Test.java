package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceRepository.MedicineRef;
import com.sahaj.opd.domain.emr.specialty.PhysioRules;
import com.sahaj.platform.api.ClinicUser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The 0.5.1 follow-up of docs/api/opd.yaml (ADR-023 D13, DEC-044 addendum H20 to H22): the General Medicine "General"
 * system, the oncology "Spine and bones" system, the Loperamide take-home line, the eight referrals, the physiotherapy
 * outcome scales, the visit-mode hidden sections, the pegfilgrastim suggestion and the closed reference list names. Rule
 * values are tagged: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed".
 */
class SpecialtyD13Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.randomUUID();
    private static final ClinicUser DOCTOR = new ClinicUser(UUID.randomUUID(), TENANT, "DOCTOR");

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SpecialtyCtx ctx(String profile, Function<String, JsonNode> sibling, Hooks hooks) {
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, MEMBER, null, "F", SpecialtyReferenceData.empty(),
                Tariff.none(), Set.of(), null, sibling, hooks);
    }

    private static ObjectNode normalize(String component, String input, List<String> errors) {
        Sp.Errors problems = new Sp.Errors("specialty");
        ObjectNode result = SpecialtyValues.normalize(component, json(input), null, ctx("physio", c -> null,
                Hooks.none()), problems, new ArrayList<Note>());
        problems.messages().forEach(m -> errors.add(m.field() + ": " + m.message()));
        return result;
    }

    private static String scales(String rows) {
        return "{'component':'OUTCOME_SCALES','schemaVersion':1,'rows':" + rows + "}";
    }

    // ---- OUTCOME_SCALES (H20) ----

    @Test
    void outcomeScaleScoresAreAcceptedUpToEachScalesRangeTaggedC6H20() {
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("OUTCOME_SCALES", scales("[{'scale':'ODI','score':100},{'scale':'NDI','score':50},"
                + "{'scale':'WOMAC','score':96},{'scale':'LEFS','score':80}]"), errors);
        assertThat(errors).isEmpty();
        assertThat(value.path("rows")).hasSize(4);
        assertThat(PhysioRules.OUTCOME_SCALE_MAX).containsEntry("ODI", 100).containsEntry("NDI", 50)
                .containsEntry("WOMAC", 96).containsEntry("LEFS", 80);
    }

    @Test
    void aScoreAboveItsScalesRangeIsRefusedWithTheRowNamedTaggedH20() {
        for (String[] bad : new String[][] {{"NDI", "51"}, {"WOMAC", "97"}, {"LEFS", "81"}, {"ODI", "101"}}) {
            List<String> errors = new ArrayList<>();
            ObjectNode value = normalize("OUTCOME_SCALES", scales("[{'scale':'" + bad[0] + "','score':" + bad[1] + "}]"),
                    errors);
            assertThat(value).as(bad[0]).isNull();
            assertThat(errors).as(bad[0]).hasSize(1);
            assertThat(errors.get(0)).startsWith("specialty.rows[0].score");
            assertThat(errors.get(0)).as("the value is never echoed").doesNotContain(bad[1] + "\"");
        }
        List<String> negative = new ArrayList<>();
        assertThat(normalize("OUTCOME_SCALES", scales("[{'scale':'ODI','score':-1}]"), negative)).isNull();
        assertThat(negative).isNotEmpty();
        List<String> unknown = new ArrayList<>();
        assertThat(normalize("OUTCOME_SCALES", scales("[{'scale':'DASH','score':10}]"), unknown)).isNull();
    }

    @Test
    void theSameScaleTwiceIsAnotherBadRequestWithTheDuplicateItemProblem() {
        assertThatThrownBy(() -> normalize("OUTCOME_SCALES", scales("[{'scale':'WOMAC','score':48},"
                + "{'scale':'WOMAC','score':41}]"), new ArrayList<>())).isInstanceOfSatisfying(EmrProblemException.class,
                e -> {
                    assertThat(e.slug()).isEqualTo("duplicate-item");
                    assertThat(e.status()).isEqualTo(400);
                });
    }

    @Test
    void aScaleWithoutAScoreIsKeptAsNullAndFiveRowsAreRefused() {
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("OUTCOME_SCALES", scales("[{'scale':'ODI','score':null}]"), errors);
        assertThat(errors).isEmpty();
        assertThat(value.path("rows").get(0).path("score").isNull()).isTrue();
        List<String> tooMany = new ArrayList<>();
        assertThat(normalize("OUTCOME_SCALES", scales("[{'scale':'ODI','score':1},{'scale':'NDI','score':1},"
                + "{'scale':'WOMAC','score':1},{'scale':'LEFS','score':1},{'scale':'ODI','score':1}]"), tooMany)).isNull();
    }

    @Test
    void outcomeScalesAreDoneWithOneScoreAndCopyNothingFromTheLastVisit() {
        SpecialtyDone.Ctx done = new SpecialtyDone.Ctx(TODAY, c -> null, "NONE");
        assertThat(SpecialtyDone.isDone("OUTCOME_SCALES", json("{'rows':[{'scale':'ODI','score':20}]}"), done)).isTrue();
        assertThat(SpecialtyDone.isDone("OUTCOME_SCALES", json("{'rows':[{'scale':'ODI','score':0}]}"), done))
                .as("a score of 0 is a score").isTrue();
        assertThat(SpecialtyDone.isDone("OUTCOME_SCALES", json("{'rows':[{'scale':'ODI','score':null}]}"), done)).isFalse();
        assertThat(SpecialtyDone.isDone("OUTCOME_SCALES", json("{'rows':[]}"), done)).isFalse();
        assertThat(SpecialtyDone.isDone("OUTCOME_SCALES", null, done)).isFalse();
        assertThat(SpecialtyCopy.copy("OUTCOME_SCALES", json("{'component':'OUTCOME_SCALES','schemaVersion':1,"
                + "'rows':[{'scale':'ODI','score':20}]}"), TODAY)).isNull();
    }

    @Test
    void theOutcomeScalesBelongToTheFuncScalesSectionOfPhysiotherapy() {
        assertThat(SpecialtyProfiles.component("physio", "func_scales")).isEqualTo("OUTCOME_SCALES");
        assertThat(SpecialtyProfiles.sectionOf("physio", "OUTCOME_SCALES")).isEqualTo("func_scales");
    }

    @Test
    void theViewChipHasTheScoreTodayAndTheLatestEarlierSignedScoreOfEachScale() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        List<Earlier> earlier = List.of(
                new Earlier(newer, LocalDate.of(2026, 11, 5), json("{'rows':[{'scale':'WOMAC','score':48}]}")),
                new Earlier(older, LocalDate.of(2026, 10, 28), json("{'rows':[{'scale':'WOMAC','score':55},"
                        + "{'scale':'ODI','score':30}]}")));
        Hooks hooks = new Hooks(key -> null, component -> "OUTCOME_SCALES".equals(component) ? earlier : List.of(),
                List::of, () -> false, () -> null, ChemoInfo::none);
        SpecialtyCtx ctx = ctx("physio", c -> "OUTCOME_SCALES".equals(c) ? json("{'rows':[{'scale':'WOMAC','score':41},"
                + "{'scale':'LEFS','score':60}]}") : null, hooks);

        JsonNode scalesNode = SpecialtyViewBuilder.build("physio", ctx, "SESSION", null).path("physio")
                .path("outcomeScales");

        assertThat(scalesNode).extracting(n -> n.path("scale").asText()).containsExactly("ODI", "WOMAC", "LEFS");
        JsonNode odi = scalesNode.get(0);
        assertThat(odi.path("previousScore").asInt()).as("only an earlier record has ODI").isEqualTo(30);
        assertThat(odi.path("previousDate").asText()).isEqualTo("2026-10-28");
        assertThat(odi.path("todayScore").isNull()).isTrue();
        JsonNode womac = scalesNode.get(1);
        assertThat(womac.path("previousScore").asInt()).as("the latest earlier record wins").isEqualTo(48);
        assertThat(womac.path("previousDate").asText()).isEqualTo("2026-11-05");
        assertThat(womac.path("todayScore").asInt()).isEqualTo(41);
        JsonNode lefs = scalesNode.get(2);
        assertThat(lefs.path("previousScore").isNull()).isTrue();
        assertThat(lefs.path("previousDate").isNull()).isTrue();
        assertThat(lefs.path("todayScore").asInt()).isEqualTo(60);
    }

    @Test
    void withNoScoresTheViewHasNoChips() {
        JsonNode view = SpecialtyViewBuilder.build("physio", ctx("physio", c -> null, Hooks.none()), "FIRST_VISIT", null);
        assertThat(view.path("physio").path("outcomeScales").isArray()).isTrue();
        assertThat(view.path("physio").path("outcomeScales")).isEmpty();
    }

    // ---- mockup items added in 0.5.1 ----

    @Test
    void generalMedicineAcceptsTheGeneralSystemAndOncologyTheSpineAndBonesSystem() {
        List<String> errors = new ArrayList<>();
        ObjectNode gm = normalize("EXAM_GENERAL", "{'component':'EXAM_GENERAL','schemaVersion':1,'signs':{},'systems':"
                + "{'general':{'state':'NORMAL'},'cvs':{'state':'NORMAL'},'rs':null,'pa':null,'cns':null}}", errors);
        assertThat(errors).isEmpty();
        assertThat(gm.path("systems").path("general").path("state").asText()).isEqualTo("NORMAL");
        ObjectNode onc = normalize("ONC_EXAM", "{'component':'ONC_EXAM','schemaVersion':1,'capAt2':true,'signs':{},"
                + "'lesions':[],'newLesionSeen':false,'breastSkin':[],'systems':{'bone':{'state':'ABNORMAL',"
                + "'chips':['Spine tenderness','Rib tenderness']}},'line':{'device':null,'checks':[]}}", errors);
        assertThat(errors).isEmpty();
        assertThat(onc.path("systems").path("bone").path("chips")).hasSize(2);
    }

    @Test
    void theOncologySystemicExamIsDoneOnlyWhenAllFiveSystemsAreMarkedAndWeightAndHeightAreTyped() {
        SpecialtyDone.Ctx done = new SpecialtyDone.Ctx(TODAY, c -> null, "NONE");
        String four = "{'heightCm':160,'weightKg':60,'systems':{'cvs':{'state':'NORMAL'},'rs':{'state':'NORMAL'},"
                + "'pa':{'state':'NORMAL'},'cns':{'state':'NORMAL'}}}";
        String five = "{'heightCm':160,'weightKg':60,'systems':{'cvs':{'state':'NORMAL'},'rs':{'state':'NORMAL'},"
                + "'pa':{'state':'NORMAL'},'cns':{'state':'NORMAL'},'bone':{'state':'NORMAL'}}}";
        assertThat(SpecialtyDone.isDone("ONC_EXAM", json(four), done)).isFalse();
        assertThat(SpecialtyDone.isDone("ONC_EXAM", json(five), done)).isTrue();
        assertThat(SpecialtyDone.isDone("ONC_EXAM", json(five.replace("'weightKg':60,", "")), done)).isFalse();
    }

    @Test
    void theChemoPlanAcceptsLoperamideAndTheEightReferralsAndRefusesAnythingElse() {
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("CHEMO_PLAN", "{'component':'CHEMO_PLAN','schemaVersion':1,'takeHome':["
                + "'ONDANSETRON_8','DEXAMETHASONE_4','PANTOPRAZOLE_40','PEGFILGRASTIM_6','BENZYDAMINE_MOUTHWASH',"
                + "'LOPERAMIDE_2'],'referrals':['RADIOTHERAPY','SURGICAL_ONCOLOGY','PALLIATIVE_CARE','DIETITIAN',"
                + "'PSYCHO_ONCOLOGY_COUNSELLING','FERTILITY_COUNSELLING','SOCIAL_WORKER_SCHEME_PAPERS',"
                + "'CARDIOLOGY_ECHO']}", errors);
        assertThat(errors).isEmpty();
        assertThat(value.path("takeHome")).hasSize(6);
        assertThat(value.path("referrals")).hasSize(8);
        List<String> bad = new ArrayList<>();
        assertThat(normalize("CHEMO_PLAN", "{'component':'CHEMO_PLAN','schemaVersion':1,'takeHome':['ASPIRIN'],"
                + "'referrals':[]}", bad)).isNull();
        assertThat(normalize("CHEMO_PLAN", "{'component':'CHEMO_PLAN','schemaVersion':1,'takeHome':[],"
                + "'referrals':['DIETITIAN','DIETITIAN']}", new ArrayList<>())).as("a list holds each item once").isNull();
    }

    @Test
    void pegfilgrastimIsSuggestedForTheTcProtocolOrAFeverOrAdmissionEventAndOtherwiseNotTaggedC8() {
        ChemoInfo tc = new ChemoInfo(null, 0, 0, 0, Set.of(), "DRAFT", 1, "TC", 100, null);
        ChemoInfo ac = new ChemoInfo(null, 0, 0, 0, Set.of(), "DRAFT", 1, "AC", 100, null);
        assertThat(onco(tc, "{'symptoms':[],'events':[]}").path("onco").path("pegfilgrastimSuggested").asBoolean())
                .isTrue();
        assertThat(onco(ac, "{'symptoms':[],'events':[]}").path("onco").path("pegfilgrastimSuggested").asBoolean())
                .isFalse();
        assertThat(onco(ac, "{'symptoms':[],'events':['ADMITTED']}").path("onco").path("pegfilgrastimSuggested")
                .asBoolean()).isTrue();
        assertThat(onco(ac, "{'symptoms':[],'events':['FEVER_38_AT_HOME']}").path("onco")
                .path("pegfilgrastimSuggested").asBoolean()).isTrue();
        assertThat(onco(ChemoInfo.none(), "{'symptoms':[],'events':['ER_VISIT']}").path("onco")
                .path("pegfilgrastimSuggested").asBoolean()).as("an ER visit alone does not count (H3)").isFalse();
    }

    private static JsonNode onco(ChemoInfo chemo, String tox) {
        Hooks hooks = new Hooks(key -> null, component -> List.of(), List::of, () -> false, () -> null, () -> chemo);
        SpecialtyCtx context = ctx("onco", c -> "ONC_TOX".equals(c) ? json("{'component':'ONC_TOX','schemaVersion':1,"
                + tox.substring(1)) : null, hooks);
        return SpecialtyViewBuilder.build("onco", context, null, null);
    }

    // ---- the visit mode ----

    @Test
    void theEffectiveModeIsTheStoredOneElseAncElsePhysioSessionFromTheSecondSessionOnTaggedD13() {
        assertThat(SpecialtyProfiles.effectiveMode("og", null, null)).isEqualTo("ANC");
        assertThat(SpecialtyProfiles.effectiveMode("og", "GYNAE", null)).isEqualTo("GYNAE");
        assertThat(SpecialtyProfiles.effectiveMode("physio", null, null)).isEqualTo("FIRST_VISIT");
        assertThat(SpecialtyProfiles.effectiveMode("physio", null, 1)).isEqualTo("FIRST_VISIT");
        assertThat(SpecialtyProfiles.effectiveMode("physio", null, 2)).isEqualTo("SESSION");
        assertThat(SpecialtyProfiles.effectiveMode("physio", "FIRST_VISIT", 5)).as("the stored mode wins")
                .isEqualTo("FIRST_VISIT");
        for (String other : List.of("gm", "dental", "paeds", "surgery", "onco")) {
            assertThat(SpecialtyProfiles.effectiveMode(other, null, 3)).as(other).isNull();
        }
    }

    @Test
    void theModeHidesTheSectionsOfTheStoriesAndPcpndtStaysWhileFormFIsRequiredH21() {
        assertThat(SpecialtyProfiles.hiddenSections("og", "ANC", false)).containsExactly("gyn_exam", "infertility",
                "contraception");
        assertThat(SpecialtyProfiles.hiddenSections("og", "GYNAE", false)).containsExactly("obstetric", "anc", "pcpndt",
                "usg");
        assertThat(SpecialtyProfiles.hiddenSections("og", "GYNAE", true)).containsExactly("obstetric", "anc", "usg");
        assertThat(SpecialtyProfiles.hiddenSections("og", "ANC", true)).as("Form F only matters in Gynae mode")
                .containsExactly("gyn_exam", "infertility", "contraception");
        assertThat(SpecialtyProfiles.hiddenSections("physio", "FIRST_VISIT", false)).containsExactly("modalities",
                "sessions");
        assertThat(SpecialtyProfiles.hiddenSections("physio", "SESSION", false)).containsExactly("special_tests",
                "posture");
        assertThat(SpecialtyProfiles.hiddenSections("gm", null, false)).isEmpty();
        assertThat(SpecialtyProfiles.hiddenSections("onco", null, true)).isEmpty();
    }

    @Test
    void theViewListsTheHiddenKeysItWasGivenAndAlwaysHasTheArray() {
        SpecialtyCtx context = ctx("og", c -> null, Hooks.none());
        JsonNode view = SpecialtyViewBuilder.build("og", context, "GYNAE", null, List.of("anc", "obstetric"));
        assertThat(view.path("hiddenSectionKeys")).extracting(JsonNode::asText).containsExactly("anc", "obstetric");
        assertThat(view.path("mode").asText()).isEqualTo("GYNAE");
        assertThat(SpecialtyViewBuilder.build("gm", ctx("gm", c -> null, Hooks.none()), null, null)
                .path("hiddenSectionKeys").isArray()).isTrue();
    }

    @Test
    void paediatricGrowthNotesAreWeightOnlyAndNamedByWeightKgPerDec044() {
        SpecialtyReferenceData data = new SpecialtyReferenceData(List.of(), List.of(),
                List.of(new com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand("WEIGHT", null, 18, 18, 8.6,
                        "Below 3rd centile", true, 1)), List.of(), List.of(), List.of());
        Function<String, JsonNode> sibling = c -> "GROWTH".equals(c) ? json("{'weightKg':8.0,'lengthCm':80,"
                + "'headCircCm':47}") : null;
        SpecialtyCtx context = new SpecialtyCtx("paeds", NOW, TODAY, TODAY, MEMBER, LocalDate.of(2025, 5, 12), "F", data,
                Tariff.none(), Set.of(), null, sibling, Hooks.none());
        JsonNode notes = SpecialtyViewBuilder.build("paeds", context, null, null).path("paeds").path("growthNotes");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).path("field").asText()).isEqualTo("weightKg");
    }

    // ---- the reference lists (D13.1) ----

    private static final Map<String, List<String>> LIST_NAMES = Map.of(
            "gm", List.of("gmSigns", "gmSystems", "gmFollowUp"),
            "dental", List.of("dentalConditions", "dentalSurfaces", "dentalMobility", "dentalShades", "dentalGumTones",
                    "dentalSuggestions", "dentalNextIntervals", "dentalLabItems"),
            "og", List.of("ancSymptoms", "ancDueOrders", "ancScans", "ancSupplements", "gynaeQuickOrders"),
            "paeds", List.of("iapGroups", "iapVaccines", "milestones", "paedsDoseRules"),
            "physio", List.of("physioGaits", "physioModalities", "jointRom", "jointMmt", "jointSpecialTests",
                    "outcomeScales"),
            "surgery", List.of("surgeryProcedures", "surgeryPayers", "surgeryConsentItems", "surgeryTests",
                    "surgeryPreopOrders", "surgeryBlood"),
            "onco", List.of("oncSymptoms", "oncEvents", "oncSystems", "oncSystemChips", "oncLabs", "oncEcog",
                    "oncDecisions", "oncDeviationReasons", "oncDoseLevels", "oncReductionReasons", "oncTakeHome",
                    "oncReferrals"));

    private final EmrAccess access = mock(EmrAccess.class);
    private final ServiceRepository services = mock(ServiceRepository.class);

    private static final UUID FERROUS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID LOPERAMIDE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private ObjectNode reference(String profile) {
        SpecialtyReferenceRepository repo = new SpecialtyReferenceRepository() {
            @Override
            public SpecialtyReferenceData load() {
                return new SpecialtyReferenceData(
                        List.of(new com.sahaj.opd.domain.emr.specialty.PaedsRules.Group("week_6", "6 weeks", 2, 6, "WEEKS",
                                10, "WEEKS")),
                        List.of(new com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine("hep_b_1", "week_6", "Hep B 1",
                                1)),
                        List.of(), List.of(new SpecialtyReferenceData.Milestone(18, "walks_alone", "Walks alone",
                                "GROSS_MOTOR", 1)),
                        List.of(new SpecialtyReferenceData.JointItem("knee", "ROM", "flexion", "Flexion", 0, 135, 1),
                                new SpecialtyReferenceData.JointItem("knee", "MMT", "quadriceps", "Quadriceps", null,
                                        null, 1),
                                new SpecialtyReferenceData.JointItem("knee", "SPECIAL_TEST", "mcmurray_medial",
                                        "McMurray (medial)", null, null, 1)),
                        List.of());
            }

            @Override
            public Map<String, MedicineRef> medicinesByName(java.util.Collection<String> names) {
                return Map.of("ferrous ascorbate 100 mg", new MedicineRef(FERROUS, "Ferrous ascorbate 100 mg", "Tablet"),
                        "loperamide 2 mg", new MedicineRef(LOPERAMIDE, "Loperamide 2 mg", "Capsule"));
            }
        };
        when(services.findAll(TENANT, true)).thenReturn(List.of());
        return new SpecialtyReferenceService(access, repo, services, MAPPER).get(DOCTOR, profile);
    }

    private static JsonNode find(JsonNode list, String key) {
        for (JsonNode item : list) {
            if (key.equals(item.path("key").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item " + key);
    }

    @Test
    void everyProfileHasExactlyItsFixedListNamesAndTheSetIsTheFortyFourOfTheContract() {
        int total = 0;
        for (Map.Entry<String, List<String>> entry : LIST_NAMES.entrySet()) {
            List<String> names = new ArrayList<>();
            reference(entry.getKey()).path("lists").fieldNames().forEachRemaining(names::add);
            assertThat(names).as(entry.getKey()).containsExactlyInAnyOrderElementsOf(entry.getValue());
            total += names.size();
        }
        assertThat(total).isEqualTo(44);
    }

    @Test
    void aListItemAlwaysHasKeyAndLabelAndTheNewFieldsAreNullWhereAListDoesNotNameThem() {
        for (String profile : LIST_NAMES.keySet()) {
            reference(profile).path("lists").fields().forEachRemaining(list -> {
                for (JsonNode item : list.getValue()) {
                    assertThat(item.path("key").asText()).as(list.getKey()).isNotEmpty();
                    assertThat(item.path("label").asText()).as(list.getKey()).isNotEmpty();
                    assertThat(item.path("key").asText().length()).as(list.getKey()).isLessThanOrEqualTo(40);
                    assertThat(item.has("note")).as(list.getKey()).isTrue();
                    assertThat(item.has("defaultOn")).as(list.getKey()).isTrue();
                    assertThat(item.has("defaults")).as(list.getKey()).isTrue();
                    assertThat(item.has("medicine")).as(list.getKey()).isTrue();
                }
            });
        }
        JsonNode signs = reference("gm").path("lists").path("gmSigns");
        assertThat(find(signs, "pallor").path("note").isNull()).isTrue();
        assertThat(find(signs, "pallor").path("defaults").isNull()).isTrue();
    }

    @Test
    void generalMedicineListsTheGeneralSystemFirstAndTheFollowUpCountsWithTheirUnit() {
        JsonNode lists = reference("gm").path("lists");
        assertThat(lists.path("gmSystems")).extracting(i -> i.path("key").asText())
                .containsExactly("general", "cvs", "rs", "pa", "cns");
        assertThat(find(lists.path("gmSystems"), "general").path("label").asText()).isEqualTo("General");
        assertThat(find(lists.path("gmSystems"), "general").path("sub").asText())
                .isEqualTo("No pallor, icterus, cyanosis, oedema");
        JsonNode oneMonth = find(lists.path("gmFollowUp"), "1m");
        assertThat(oneMonth.path("number").asInt()).isEqualTo(1);
        assertThat(oneMonth.path("group").asText()).isEqualTo("MONTHS");
        assertThat(find(lists.path("gmFollowUp"), "3d").path("group").asText()).isEqualTo("DAYS");
    }

    @Test
    void dentalMobilityHasKeysZeroToThreeLabelledG0ToG3AndSuggestionsCarrySittings() {
        JsonNode lists = reference("dental").path("lists");
        assertThat(lists.path("dentalMobility")).extracting(i -> i.path("key").asText() + "=" + i.path("label").asText())
                .containsExactly("0=G0", "1=G1", "2=G2", "3=G3");
        JsonNode rootCanal = null;
        for (JsonNode item : lists.path("dentalSuggestions")) {
            if ("ROOT_CANAL".equals(item.path("key").asText())) {
                rootCanal = item;
            }
        }
        assertThat(rootCanal.path("defaults").path("sittings").asInt()).isEqualTo(3);
        assertThat(rootCanal.path("number").asInt()).isPositive();
        assertThat(find(lists.path("dentalNextIntervals"), "14d").path("number").asInt()).isEqualTo(14);
        assertThat(lists.path("dentalLabItems")).extracting(i -> i.path("key").asText())
                .containsExactly("CROWN", "BRIDGE", "DENTURE", "ALIGNER");
    }

    @Test
    void obstetricsListsCarryTheDrawnNotesFrequenciesAndTheMasterMedicineWhereOneMatches() {
        JsonNode lists = reference("og").path("lists");
        JsonNode iron = find(lists.path("ancSupplements"), "IRON");
        assertThat(iron.path("defaults").path("frequency").asText()).isEqualTo("1-0-1");
        assertThat(iron.path("note").asText()).isEqualTo("After food");
        assertThat(iron.path("medicine").path("medicineId").asText()).isEqualTo(FERROUS.toString());
        assertThat(iron.path("medicine").path("name").asText()).isEqualTo("Ferrous ascorbate 100 mg");
        JsonNode calcium = find(lists.path("ancSupplements"), "CALCIUM_VIT_D3");
        assertThat(calcium.path("note").asText()).isEqualTo("2 h apart from iron");
        assertThat(calcium.path("defaults").path("frequency").asText()).isEqualTo("0-1-0");
        assertThat(calcium.path("medicine").isNull()).as("no master row: a typed row then").isTrue();
        assertThat(lists.path("gynaeQuickOrders")).extracting(i -> i.path("key").asText())
                .containsExactly("PAP_SMEAR", "PELVIC_USG", "THYROID_PROFILE", "CBC", "HORMONES");
        assertThat(find(lists.path("ancDueOrders"), "TD_2ND_DOSE").path("sub").asText()).isEqualTo("give today");
    }

    @Test
    void paediatricListsUseTheTableKeysAndTheDoseRulesPointAtTheirMedicine() {
        JsonNode lists = reference("paeds").path("lists");
        JsonNode group = find(lists.path("iapGroups"), "week_6");
        assertThat(group.path("number").asInt()).isEqualTo(2);
        assertThat(group.path("sub").asText()).isEqualTo("6 weeks to 10 weeks");
        JsonNode vaccine = find(lists.path("iapVaccines"), "hep_b_1");
        assertThat(vaccine.path("group").asText()).isEqualTo("week_6");
        JsonNode milestone = find(lists.path("milestones"), "walks_alone");
        assertThat(milestone.path("group").asText()).isEqualTo("GROSS_MOTOR");
        assertThat(milestone.path("number").asInt()).isEqualTo(18);
        JsonNode rule = find(lists.path("paedsDoseRules"), "PARACETAMOL_120_5");
        assertThat(rule.path("number").asInt()).isEqualTo(15);
        assertThat(rule.path("defaults").path("frequencyText").asText()).isNotEmpty();
        assertThat(rule.path("medicine").isNull()).isTrue();
        assertThat(rule.has("maxMg")).as("the maximum is a threshold, not an item field").isFalse();
    }

    @Test
    void physioJointListsUseTheTableItemKeyWithTheJointAsGroup() {
        JsonNode lists = reference("physio").path("lists");
        JsonNode flexion = find(lists.path("jointRom"), "flexion");
        assertThat(flexion.path("group").asText()).isEqualTo("knee");
        assertThat(flexion.path("sub").asText()).isEqualTo("0–135°");
        assertThat(flexion.path("number").asInt()).isEqualTo(135);
        assertThat(find(lists.path("jointMmt"), "quadriceps").path("group").asText()).isEqualTo("knee");
        assertThat(find(lists.path("jointSpecialTests"), "mcmurray_medial").path("label").asText())
                .isEqualTo("McMurray (medial)");
        assertThat(lists.path("outcomeScales")).extracting(i -> i.path("key").asText() + "=" + i.path("number").asInt())
                .containsExactly("ODI=100", "NDI=50", "WOMAC=96", "LEFS=80");
    }

    @Test
    void oncologyListsHaveTheFiveSystemsTheirChipsTheSixTakeHomeLinesAndTheEightReferrals() {
        JsonNode lists = reference("onco").path("lists");
        assertThat(lists.path("oncSystems")).extracting(i -> i.path("key").asText())
                .containsExactly("cvs", "rs", "pa", "cns", "bone");
        JsonNode bone = find(lists.path("oncSystems"), "bone");
        assertThat(bone.path("label").asText()).isEqualTo("Spine and bones");
        assertThat(bone.path("sub").asText()).isEqualTo("Bone spread");
        assertThat(find(lists.path("oncSystems"), "pa").path("label").asText()).isEqualTo("Abdomen");
        JsonNode chip = find(lists.path("oncSystemChips"), "Rib tenderness");
        assertThat(chip.path("group").asText()).isEqualTo("bone");
        assertThat(lists.path("oncSystemChips")).hasSize(19);
        assertThat(find(lists.path("oncLabs"), "anc").path("number").asInt()).as("the step, not the limit").isEqualTo(100);
        assertThat(find(lists.path("oncLabs"), "anc").path("sub").asText()).isEqualTo("/µL");
        assertThat(lists.path("oncEcog")).extracting(i -> i.path("key").asText()).containsExactly("0", "1", "2", "3", "4");
        assertThat(find(lists.path("oncDoseLevels"), "90").path("number").asInt()).isEqualTo(90);

        assertThat(lists.path("oncTakeHome")).extracting(i -> i.path("key").asText()).containsExactly("ONDANSETRON_8",
                "DEXAMETHASONE_4", "PANTOPRAZOLE_40", "PEGFILGRASTIM_6", "BENZYDAMINE_MOUTHWASH", "LOPERAMIDE_2");
        assertThat(lists.path("oncTakeHome")).extracting(i -> i.path("defaultOn").asBoolean())
                .containsExactly(true, true, true, false, true, false);
        JsonNode loperamide = find(lists.path("oncTakeHome"), "LOPERAMIDE_2");
        assertThat(loperamide.path("label").asText()).isEqualTo("Loperamide 2 mg");
        assertThat(loperamide.path("note").asText()).isEqualTo("Loose motions");
        assertThat(loperamide.path("defaults").path("frequencyText").asText()).isEqualTo("After each loose motion (max 8)");
        assertThat(loperamide.path("defaults").path("days").isMissingNode()).as("no days drawn (H22)").isTrue();
        assertThat(loperamide.path("medicine").path("medicineId").asText()).isEqualTo(LOPERAMIDE.toString());
        JsonNode ondansetron = find(lists.path("oncTakeHome"), "ONDANSETRON_8");
        assertThat(ondansetron.path("defaults").path("frequency").asText()).isEqualTo("1-0-1");
        assertThat(ondansetron.path("defaults").path("days").asInt()).isEqualTo(3);
        assertThat(ondansetron.path("medicine").isNull()).isTrue();

        assertThat(lists.path("oncReferrals")).extracting(i -> i.path("key").asText()).containsExactly("RADIOTHERAPY",
                "SURGICAL_ONCOLOGY", "PALLIATIVE_CARE", "DIETITIAN", "PSYCHO_ONCOLOGY_COUNSELLING",
                "FERTILITY_COUNSELLING", "SOCIAL_WORKER_SCHEME_PAPERS", "CARDIOLOGY_ECHO");
        assertThat(lists.path("oncReferrals")).allMatch(i -> !i.path("defaultOn").asBoolean(true));
        assertThat(find(lists.path("oncReferrals"), "CARDIOLOGY_ECHO").path("label").asText())
                .isEqualTo("Cardiology (echo)");
    }

    @Test
    void theMedicineNamesAProfileLooksUpAreOnlyThoseOfItsOwnLists() {
        assertThat(SpecialtyReferenceService.medicineNames("gm")).isEmpty();
        assertThat(SpecialtyReferenceService.medicineNames("og")).containsExactly("Ferrous ascorbate 100 mg",
                "Calcium 500 mg + Vitamin D3", "Folic acid 5 mg");
        assertThat(SpecialtyReferenceService.medicineNames("paeds")).hasSize(3);
        assertThat(SpecialtyReferenceService.medicineNames("onco")).hasSize(6).contains("Loperamide 2 mg");
    }
}
