package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Done rule of every component (ADR-023 D3) and the profile registry (D1). */
class SpecialtyDoneAndProfilesTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean done(String component, String value) {
        return SpecialtyDone.isDone(component, value == null ? null : json(value),
                new SpecialtyDone.Ctx(TODAY, c -> null, "NONE"));
    }

    private static boolean done(String component, String value, Map<String, String> siblings, String orderStatus) {
        return SpecialtyDone.isDone(component, value == null ? null : json(value),
                new SpecialtyDone.Ctx(TODAY, c -> siblings.containsKey(c) ? json(siblings.get(c)) : null, orderStatus));
    }

    // ---- registry ----

    @Test
    void onlyAnOpdVisitOfOneOfTheSevenStartersHasAProfile() {
        for (String starter : new String[] {"gm", "dental", "og", "paeds", "physio", "onco", "surgery"}) {
            assertThat(SpecialtyProfiles.profileOf("OPD_VISIT", starter)).contains(starter);
        }
        for (String starter : new String[] {"ortho", "eye", "ent", "derm", "diab", "cardio", "ayush", "psych", null}) {
            assertThat(SpecialtyProfiles.profileOf("OPD_VISIT", starter)).isEmpty();
        }
        assertThat(SpecialtyProfiles.profileOf("IPD_ROUND", "gm")).isEmpty();
        assertThat(SpecialtyProfiles.profileOf("ER_ASSESSMENT", "surgery")).isEmpty();
    }

    @Test
    void theAnchorSectionsAreTheOnesInTheAdr() {
        assertThat(SpecialtyProfiles.anchors("gm")).containsEntry("vitals", "VITALS_LOG").containsEntry("exam",
                "EXAM_GENERAL").hasSize(2);
        assertThat(SpecialtyProfiles.anchors("dental")).hasSize(6).containsEntry("dental_done", "DENTAL_DONE")
                .containsEntry("dental_medhx", "DENTAL_MED_HISTORY");
        assertThat(SpecialtyProfiles.anchors("og")).containsEntry("pcpndt", "FORM_F").hasSize(3);
        assertThat(SpecialtyProfiles.anchors("paeds")).containsEntry("wtdose", "WEIGHT_DOSE").hasSize(4);
        assertThat(SpecialtyProfiles.anchors("physio")).hasSize(10).containsEntry("hep", "HOME_EXERCISES")
                .containsEntry("func_scales", "OUTCOME_SCALES");
        assertThat(SpecialtyProfiles.anchors("surgery")).containsEntry("sx_consent", "CONSENT_CHECKLIST").hasSize(6);
        assertThat(SpecialtyProfiles.anchors("onco")).containsEntry("onc_bsa", "ONC_EXAM")
                .containsEntry("onc_regimen", "CHEMO_PLAN").containsEntry("vitals", "VITALS_LOG").hasSize(6);
        assertThat(SpecialtyProfiles.sectionOf("onco", "ONC_ECOG")).isEqualTo("onc_ecog");
        assertThat(SpecialtyProfiles.component("gm", "history")).isNull();
        assertThat(SpecialtyProfiles.modes("og")).containsExactly("ANC", "GYNAE");
        assertThat(SpecialtyProfiles.modes("physio")).containsExactly("FIRST_VISIT", "SESSION");
        assertThat(SpecialtyProfiles.modes("onco")).isEmpty();
    }

    @Test
    void everyComponentOfTheContractHasAShape() {
        for (String component : new String[] {"VITALS_LOG", "EXAM_GENERAL", "TOOTH_CHART", "DENTAL_PLAN", "DENTAL_DONE",
                "LAB_WORK", "CONSENT_CHECKLIST", "ANC_CHECK", "FORM_F", "GYNAE_EXAM", "GROWTH", "IMMUNIZATION",
                "MILESTONES", "WEIGHT_DOSE", "PAIN_SCORE", "POSTURE_GAIT", "ROM_TABLE", "MMT_TABLE", "SPECIAL_TESTS",
                "GOALS", "MODALITIES", "HOME_EXERCISES", "SESSION_NOTE", "SURGICAL_EXAM", "SURGERY_FITNESS",
                "SURGERY_PLAN", "PREAUTH", "PREOP_ORDERS", "ONC_TOX", "ONC_EXAM", "ONC_LABS", "ONC_ECOG",
                "CHEMO_PLAN"}) {
            assertThat(SpecialtyShapes.known(component)).as(component).isTrue();
        }
        for (String profile : SpecialtyProfiles.PROFILES) {
            SpecialtyProfiles.anchors(profile).values().forEach(c -> assertThat(SpecialtyShapes.known(c)).isTrue());
        }
    }

    // ---- Done rules ----

    @Test
    void vitalsIsDoneWithOneEntryInThisRecord() {
        assertThat(done("VITALS_LOG", "{'entries':[]}")).isFalse();
        assertThat(done("VITALS_LOG", "{'entries':[{'id':'a','pulse':80}]}")).isTrue();
        assertThat(done("VITALS_LOG", null)).isFalse();
    }

    @Test
    void generalExamNeedsOneMarkAndTextForEveryAbnormalSystem() {
        assertThat(done("EXAM_GENERAL", "{'signs':{},'systems':{}}")).isFalse();
        assertThat(done("EXAM_GENERAL", "{'signs':{'pallor':'ABSENT'},'systems':{}}")).isTrue();
        assertThat(done("EXAM_GENERAL", "{'signs':{'pallor':null},'systems':{'cvs':{'state':'NORMAL','text':'S1 S2'}}}"))
                .isTrue();
        assertThat(done("EXAM_GENERAL", "{'signs':{'pallor':'ABSENT'},'systems':{'rs':{'state':'ABNORMAL','text':''}}}"))
                .isFalse();
        assertThat(done("EXAM_GENERAL", "{'signs':{},'systems':{'rs':{'state':'ABNORMAL','text':'Wheeze'}}}")).isTrue();
    }

    @Test
    void toothChartIsDoneWithOneToothOrAllHealthyConfirmed() {
        assertThat(done("TOOTH_CHART", "{'teeth':[],'allHealthyConfirmed':false}")).isFalse();
        assertThat(done("TOOTH_CHART", "{'teeth':[],'allHealthyConfirmed':true}")).isTrue();
        assertThat(done("TOOTH_CHART", "{'teeth':[{'tooth':16,'condition':'CARIES'}],'allHealthyConfirmed':false}"))
                .isTrue();
    }

    @Test
    void dentalPlanAndDoneTodayAndLab() {
        assertThat(done("DENTAL_PLAN", "{'rows':[]}")).isFalse();
        assertThat(done("DENTAL_PLAN", "{'rows':[{'id':'a'}]}")).isTrue();
        Map<String, String> today = Map.of("DENTAL_PLAN", "{'rows':[{'status':'DONE','doneOn':'2026-11-12'}]}");
        Map<String, String> yesterday = Map.of("DENTAL_PLAN", "{'rows':[{'status':'DONE','doneOn':'2026-11-11'}]}");
        assertThat(done("DENTAL_DONE", null, today, "NONE")).isTrue();
        assertThat(done("DENTAL_DONE", null, yesterday, "NONE")).isFalse();
        assertThat(done("DENTAL_DONE", null, Map.of(), "NONE")).isFalse();
        assertThat(done("LAB_WORK", "{'items':[{'id':'a'}]}")).isTrue();
        assertThat(done("LAB_WORK", "{'items':[]}")).isFalse();
    }

    @Test
    void consentIsDoneWhenThereIsAnItemAndEveryItemIsTicked() {
        assertThat(done("CONSENT_CHECKLIST", "{'items':[]}")).isFalse();
        assertThat(done("CONSENT_CHECKLIST", "{'items':[{'key':'a','confirmed':true},{'key':'b','confirmed':false}]}"))
                .isFalse();
        assertThat(done("CONSENT_CHECKLIST", "{'items':[{'key':'a','confirmed':true}]}")).isTrue();
    }

    @Test
    void ancIsDoneWithOneOfTodaysValues() {
        assertThat(done("ANC_CHECK", "{'today':{}}")).isFalse();
        assertThat(done("ANC_CHECK", "{'today':{'hbGdl':null}}")).isFalse();
        assertThat(done("ANC_CHECK", "{'today':{'weightKg':60.5}}")).isTrue();
    }

    @Test
    void formFIsDoneWhenNoScanIsOrderedOrItIsMarkedTaggedL2() {
        Map<String, String> ordered = Map.of("ANC_CHECK", "{'orders':[{'key':'GROWTH_SCAN_FORM_F'}]}");
        assertThat(done("FORM_F", "{'filledAndSigned':false}", Map.of(), "NONE")).isTrue();
        assertThat(done("FORM_F", "{'filledAndSigned':false}", ordered, "NONE")).isFalse();
        assertThat(done("FORM_F", "{'filledAndSigned':true}", ordered, "NONE")).isTrue();
        assertThat(done("FORM_F", null, ordered, "NONE")).isFalse();
    }

    @Test
    void gynaeNeedsAFindingAndTheChaperone() {
        assertThat(done("GYNAE_EXAM", "{'perAbdomen':'Soft'}")).isFalse();
        assertThat(done("GYNAE_EXAM", "{'perAbdomen':'Soft','chaperoneName':'Sister A'}")).isTrue();
        assertThat(done("GYNAE_EXAM", "{'perSpeculum':'Normal','chaperoneDeclined':true}")).isTrue();
        assertThat(done("GYNAE_EXAM", "{'chaperoneDeclined':true}")).isFalse();
    }

    @Test
    void paediatricComponents() {
        assertThat(done("GROWTH", "{'weightKg':10.5}")).isTrue();
        assertThat(done("GROWTH", "{'lengthCm':80}")).isFalse();
        assertThat(done("IMMUNIZATION", "{'givenToday':[],'scheduleReviewed':false}")).isFalse();
        assertThat(done("IMMUNIZATION", "{'givenToday':[],'scheduleReviewed':true}")).isTrue();
        assertThat(done("IMMUNIZATION", "{'givenToday':[{'vaccineKey':'bcg'}],'scheduleReviewed':false}")).isTrue();
        assertThat(done("MILESTONES", "{'achieved':[],'notYet':['walks_alone']}")).isTrue();
        assertThat(done("MILESTONES", "{'achieved':[],'notYet':[]}")).isFalse();
        assertThat(done("WEIGHT_DOSE", "{}")).as("the dose by weight stores nothing and is never Done by itself")
                .isFalse();
    }

    @Test
    void physioComponents() {
        assertThat(done("PAIN_SCORE", "{'score':0}")).isTrue();
        assertThat(done("PAIN_SCORE", "{'score':null}")).isFalse();
        assertThat(done("POSTURE_GAIT", "{'gait':'NORMAL'}")).isTrue();
        assertThat(done("POSTURE_GAIT", "{'gait':null}")).isFalse();
        assertThat(done("ROM_TABLE", "{'rows':[{'rightDeg':null,'leftDeg':90}]}")).isTrue();
        assertThat(done("ROM_TABLE", "{'rows':[{'rightDeg':null,'leftDeg':null}]}")).isFalse();
        assertThat(done("MMT_TABLE", "{'rows':[{'right':0,'left':null}]}")).isTrue();
        assertThat(done("MMT_TABLE", "{'rows':[{'right':null,'left':null}]}")).isFalse();
        assertThat(done("SPECIAL_TESTS", "{'results':[{'result':'NOT_DONE'}]}")).isFalse();
        assertThat(done("SPECIAL_TESTS", "{'results':[{'result':'NOT_DONE'},{'result':'POSITIVE'}]}")).isTrue();
        assertThat(done("GOALS", "{'goals':[{'id':'a'}]}")).isTrue();
        assertThat(done("MODALITIES", "{'given':[]}")).isFalse();
        assertThat(done("MODALITIES", "{'given':[{'text':'IFT'}]}")).isTrue();
        assertThat(done("HOME_EXERCISES", "{'items':[{'id':'a'}]}")).isTrue();
        assertThat(done("SESSION_NOTE", "{'note':'  '}")).isFalse();
        assertThat(done("SESSION_NOTE", "{'note':'Better'}")).isTrue();
    }

    @Test
    void surgeryComponentsFollowTheFitnessSummaryTaggedC7() {
        assertThat(done("SURGICAL_EXAM", "{'abdomen':[],'herniaSites':[]}")).isFalse();
        assertThat(done("SURGICAL_EXAM", "{'abdomen':['SOFT'],'herniaSites':[]}")).isTrue();
        assertThat(done("SURGICAL_EXAM", "{'abdomen':[],'herniaSites':[],'murphySign':'NEGATIVE'}")).isTrue();
        assertThat(done("SURGERY_FITNESS", "{'pac':'FIT','tests':[{'key':'CBC','state':'NORMAL'}]}")).isTrue();
        assertThat(done("SURGERY_FITNESS", "{'pac':'FIT_WITH_CAUTION','tests':[]}")).isTrue();
        assertThat(done("SURGERY_FITNESS", "{'pac':'FIT','tests':[{'key':'CBC','state':'PENDING'}]}")).isFalse();
        assertThat(done("SURGERY_FITNESS", "{'pac':'FIT','tests':[{'key':'CBC','state':'ABNORMAL'}]}")).isFalse();
        assertThat(done("SURGERY_FITNESS", "{'pac':'NOT_DONE','tests':[]}")).isFalse();
        assertThat(done("SURGERY_FITNESS", "{'pac':'UNFIT','tests':[]}")).isFalse();
        assertThat(done("PREAUTH", "{'sent':true}")).isTrue();
        assertThat(done("PREAUTH", "{'sent':false}")).isFalse();
        assertThat(done("PREOP_ORDERS", "{'ticked':['NIL_BY_MOUTH']}")).isTrue();
        assertThat(done("PREOP_ORDERS", "{'ticked':[]}")).isFalse();
    }

    @Test
    void surgeryPlanIsDoneOnlyWhileTheBookingMatchesThePlanConsentAndPreauth() {
        ObjectNode plan = (ObjectNode) json("{'procedureKey':'LAP_CHOLECYSTECTOMY','procedureText':'Lap cholecystectomy',"
                + "'anaesthesia':'GA','admission':'IPD_1_2_DAYS','otDate':'2026-11-24','slotText':'OT 2','payer':'SELF',"
                + "'packagePaise':6500000}");
        JsonNode consent = json("{'items':[{'key':'PROCEDURE_RISKS','confirmed':true}]}");
        JsonNode preauth = json("{'sent':false}");
        String fingerprint = SpecialtyDone.bookingFingerprint(plan, consent, preauth);
        plan.putObject("booking").put("planFingerprint", fingerprint);
        Map<String, String> siblings = Map.of("CONSENT_CHECKLIST", consent.toString(), "PREAUTH", preauth.toString());
        assertThat(done("SURGERY_PLAN", plan.toString(), siblings, "NONE")).isTrue();
        SpecialtyDone.Ctx ctx = new SpecialtyDone.Ctx(TODAY, c -> "CONSENT_CHECKLIST".equals(c) ? consent
                : "PREAUTH".equals(c) ? preauth : null, "NONE");
        assertThat(SpecialtyDone.bookingState(plan, ctx)).isEqualTo("BOOKED");

        // a change of plan, consent or pre-authorisation after booking: "Changed after booking. Book again"
        ObjectNode changedPlan = plan.deepCopy();
        changedPlan.put("otDate", "2026-11-25");
        assertThat(SpecialtyDone.bookingState(changedPlan, ctx)).isEqualTo("CHANGED");
        SpecialtyDone.Ctx lessConsent = new SpecialtyDone.Ctx(TODAY, c -> "CONSENT_CHECKLIST".equals(c)
                ? json("{'items':[]}") : preauth, "NONE");
        assertThat(SpecialtyDone.bookingState(plan, lessConsent)).isEqualTo("CHANGED");
        SpecialtyDone.Ctx sent = new SpecialtyDone.Ctx(TODAY, c -> "CONSENT_CHECKLIST".equals(c) ? consent
                : json("{'sent':true}"), "NONE");
        assertThat(SpecialtyDone.bookingState(plan, sent)).isEqualTo("CHANGED");
        assertThat(done("SURGERY_PLAN", changedPlan.toString(), siblings, "NONE")).isFalse();
        ((ObjectNode) plan).remove("booking");
        assertThat(SpecialtyDone.bookingState(plan, ctx)).isEqualTo("NOT_BOOKED");
    }

    @Test
    void oncologyComponentsTaggedC8() {
        StringBuilder nine = new StringBuilder("{'symptoms':[");
        String[] keys = {"NAUSEA", "VOMITING", "MOUTH_SORES", "LOOSE_MOTIONS", "TINGLING_NUMBNESS", "FATIGUE",
                "FEVER_LOW_COUNTS", "PAIN", "LOSS_OF_APPETITE"};
        for (int i = 0; i < keys.length; i++) {
            nine.append(i == 0 ? "" : ",").append("{'key':'").append(keys[i]).append("','confirmedGrade':")
                    .append(i == 8 ? "null" : "1").append('}');
        }
        nine.append("]}");
        assertThat(done("ONC_TOX", nine.toString())).as("eight of nine confirmed").isFalse();
        assertThat(SpecialtyDone.oncToxOpen(json(nine.toString()))).isEqualTo(1);
        assertThat(done("ONC_TOX", nine.toString().replace("null", "0"))).isTrue();
        assertThat(SpecialtyDone.oncToxOpen(json("{'symptoms':[]}"))).isEqualTo(9);

        String system = "{'state':'NORMAL'}";
        String exam = "{'heightCm':160,'weightKg':60,'systems':{'cvs':%s,'rs':%s,'pa':%s,'cns':%s,'bone':%s}}";
        assertThat(done("ONC_EXAM", exam.formatted(system, system, system, system, system))).isTrue();
        assertThat(done("ONC_EXAM", exam.formatted(system, system, system, "null", system))).isFalse();
        assertThat(done("ONC_EXAM", exam.formatted(system, system, system, system, "null")))
                .as("Spine and bones counts as the fifth system (0.5.1)").isFalse();
        assertThat(done("ONC_EXAM", exam.formatted(system, system, system, system, system)
                .replace("'heightCm':160,", ""))).isFalse();

        String labs = "{'drawnAt':'2026-11-12T04:00:00Z','values':{'hb':10,'anc':1800,'plt':200,'cr':0.8,'bili':0.7,"
                + "'alt':30,'lvef':%s}}";
        assertThat(done("ONC_LABS", labs.formatted("60"))).isTrue();
        assertThat(done("ONC_LABS", labs.formatted("null"))).isFalse();
        assertThat(done("ONC_LABS", labs.formatted("60").replace("'drawnAt':'2026-11-12T04:00:00Z',", ""))).isFalse();
        assertThat(done("ONC_ECOG", "{'grade':0}")).isTrue();
        assertThat(done("ONC_ECOG", "{'grade':null}")).isFalse();
    }

    @Test
    void theChemoPlanIsDoneWhenTheOrderIsSentOrTheHoldIsRecorded() {
        assertThat(done("CHEMO_PLAN", "{'takeHome':[]}", Map.of(), "NONE")).isFalse();
        assertThat(done("CHEMO_PLAN", "{'takeHome':[]}", Map.of(), "DRAFT")).isFalse();
        assertThat(done("CHEMO_PLAN", "{'takeHome':[]}", Map.of(), "SENT")).isTrue();
        assertThat(done("CHEMO_PLAN", "{'takeHome':[]}", Map.of(), "HELD")).isTrue();
    }

    // ---- the closed-shape checker ----

    @Test
    void theShapeCheckerCleansAndRefusesAsTheContractSays() {
        Sp.Errors errors = new Sp.Errors("specialty");
        Sp.Shape shape = Sp.obj(Sp.req("a", Sp.i(1, 3)), Sp.opt("b", Sp.sN(5)), Sp.srv("c"));
        JsonNode cleaned = shape.check(json("{'a':2,'b':'  ','c':'ignored'}"), "", errors);
        assertThat(errors.any()).isFalse();
        assertThat(cleaned.has("c")).isFalse();
        assertThat(cleaned.path("b").isNull()).isTrue();
        assertThat(shape.check(json("{'b':'x'}"), "", errors)).isNull();
        assertThat(errors.messages()).extracting(m -> m.field()).contains("specialty.a");
        Sp.Errors again = new Sp.Errors("x");
        assertThat(shape.check(json("{'a':2,'z':1}"), "", again)).isNull();
        assertThat(again.messages().get(0).message()).contains("not allowed");
        assertThat(Sp.s(1, 5).check(json("'<b>'"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.set(Sp.e("A", "B"), 3).check(json("['A','A']"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.arr(Sp.i(0, 9), 2).check(json("[1,2,3]"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.n(0, 10, 0.5).check(json("2.25"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.n(0, 10, 0.5).check(json("2.5"), "t", new Sp.Errors("x"))).isNotNull();
        assertThat(Sp.i(0, 9).check(json("1.5"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.date().check(json("'12 Nov'"), "t", new Sp.Errors("x"))).isNull();
        assertThat(Sp.dateTime().check(json("'2026-11-12T04:00:00Z'"), "t", new Sp.Errors("x"))).isNotNull();
    }
}
