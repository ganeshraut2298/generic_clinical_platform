package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.TariffItem;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The server rules of the specialty component values (ADR-023 D2, D3): the closed shape, the server stamps that a client can
 * never set, the fixed lists and the reference tables. Rule values are tagged (DEC-039, not clinically reviewed).
 */
class SpecialtyValuesTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SpecialtyReferenceData reference() {
        return new SpecialtyReferenceData(
                List.of(new Group("birth", "Birth", 1, 0, "DAYS", 6, "WEEKS"),
                        new Group("m16", "16–18 months", 2, 16, "MONTHS", 19, "MONTHS")),
                List.of(new Vaccine("bcg", "birth", "BCG", 1), new Vaccine("hib_booster_1", "m16", "Hib booster 1", 1)),
                List.of(),
                List.of(new SpecialtyReferenceData.Milestone(18, "walks_alone", "Walks alone", "GROSS_MOTOR", 1),
                        new SpecialtyReferenceData.Milestone(18, "says_6_or_more_words", "Says 6 or more words",
                                "LANGUAGE", 2)),
                List.of(), List.of());
    }

    private static SpecialtyCtx ctx(String profile) {
        return ctx(profile, Tariff.none(), null, Set.of(), c -> null);
    }

    private static SpecialtyCtx ctx(String profile, Tariff tariff, LocalDate dob, Set<String> earlierVaccines,
                                    Function<String, JsonNode> sibling) {
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, MEMBER, dob, "F", reference(), tariff, earlierVaccines,
                null, sibling, Hooks.none());
    }

    private static ObjectNode normalize(String component, String input, String stored, SpecialtyCtx ctx,
                                        List<String> errors, List<Note> notes) {
        Sp.Errors problems = new Sp.Errors("specialty");
        ObjectNode result = SpecialtyValues.normalize(component, json(input), stored == null ? null : json(stored), ctx,
                problems, notes);
        problems.messages().forEach(m -> errors.add(m.field() + ": " + m.message()));
        return result;
    }

    private static ObjectNode ok(String component, String input, SpecialtyCtx ctx) {
        List<String> errors = new ArrayList<>();
        ObjectNode result = normalize(component, input, null, ctx, errors, new ArrayList<>());
        assertThat(errors).isEmpty();
        return result;
    }

    private static List<String> problems(String component, String input, String stored, SpecialtyCtx ctx) {
        List<String> errors = new ArrayList<>();
        ObjectNode result = normalize(component, input, stored, ctx, errors, new ArrayList<>());
        assertThat(result).isNull();
        return errors;
    }

    // ---- the closed shape ----

    @Test
    void aPropertyTheContractDoesNotListIsRefused() {
        assertThat(problems("PAIN_SCORE", "{'component':'PAIN_SCORE','schemaVersion':1,'score':4,'colour':'red'}", null,
                ctx("physio"))).anyMatch(e -> e.contains("not allowed"));
    }

    @Test
    void aWrongComponentOrVersionIsRefused() {
        assertThat(problems("PAIN_SCORE", "{'component':'GOALS','schemaVersion':1,'score':4}", null, ctx("physio")))
                .isNotEmpty();
        assertThat(problems("PAIN_SCORE", "{'component':'PAIN_SCORE','schemaVersion':2,'score':4}", null,
                ctx("physio"))).isNotEmpty();
    }

    @Test
    void numbersOutsideTheSanityLimitAndStepAreRefused() {
        assertThat(problems("PAIN_SCORE", "{'component':'PAIN_SCORE','schemaVersion':1,'score':11}", null,
                ctx("physio"))).anyMatch(e -> e.contains("Enter 0 to 10"));
        assertThat(problems("GROWTH", "{'component':'GROWTH','schemaVersion':1,'weightKg':10.55}", null, ctx("paeds")))
                .anyMatch(e -> e.contains("steps of 0.1"));
        assertThat(problems("MMT_TABLE", "{'component':'MMT_TABLE','schemaVersion':1,'rows':[{'group':'Quadriceps',"
                + "'right':6,'left':null}]}", null, ctx("physio"))).anyMatch(e -> e.contains("0 to 5"));
    }

    // ---- VITALS_LOG ----

    private static final String VITALS = "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'%s',"
            + "'bpSystolic':150,'bpDiastolic':95,'temperature':{'value':%s,'unit':'%s'},'recorded':{'at':'2000-01-01T00:00:00Z',"
            + "'byMembershipId':'" + OTHER + "'},'how':'NURSE'}]}";

    @Test
    void aVitalsEntryIsStampedByTheServerAndAClientStampIsIgnored() {
        ObjectNode value = ok("VITALS_LOG", VITALS.formatted(OTHER, "99.1", "F"), ctx("gm"));
        JsonNode entry = value.path("entries").get(0);
        assertThat(entry.path("recorded").path("at").asText()).isEqualTo(NOW.toString());
        assertThat(entry.path("recorded").path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(entry.path("how").asText()).isEqualTo("TYPED");
    }

    @Test
    void aStoredVitalsStampIsKeptWhenTheSameEntryIsSavedAgain() {
        String stored = "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'" + OTHER + "','pulse':80,"
                + "'how':'TYPED','recorded':{'at':'2026-11-12T04:00:00Z','byMembershipId':'" + OTHER + "'}}]}";
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("VITALS_LOG", "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'"
                + OTHER + "','pulse':82}]}", stored, ctx("gm"), errors, new ArrayList<>());
        assertThat(errors).isEmpty();
        assertThat(value.path("entries").get(0).path("recorded").path("at").asText())
                .isEqualTo("2026-11-12T04:00:00Z");
    }

    @Test
    void temperatureUnitFollowsTheProfileFahrenheitInGmCelsiusInOnco() {
        assertThat(problems("VITALS_LOG", VITALS.formatted(OTHER, "37.2", "C"), null, ctx("gm")))
                .anyMatch(e -> e.contains("85 to 110"));
        ok("VITALS_LOG", VITALS.formatted(OTHER, "37.2", "C"), ctx("onco"));
        assertThat(problems("VITALS_LOG", VITALS.formatted(OTHER, "99.1", "F"), null, ctx("onco")))
                .anyMatch(e -> e.contains("30 to 43"));
    }

    @Test
    void anEntryWithNoReadingIsRefused() {
        assertThat(problems("VITALS_LOG", "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'" + OTHER
                + "'}]}", null, ctx("gm"))).anyMatch(e -> e.contains("at least one reading"));
    }

    // ---- TOOTH_CHART ----

    private static String chart(String teeth) {
        return "{'component':'TOOTH_CHART','schemaVersion':1,'numbering':'FDI','dentition':'ADULT','showNumbers':true,"
                + "'teeth':" + teeth + ",'shade':'A2','gum':'PINK','allHealthyConfirmed':false}";
    }

    @Test
    void aToothChartKeepsOnlyTeethThatDifferFromHealthy() {
        ObjectNode value = ok("TOOTH_CHART", chart("[{'tooth':16,'condition':'CARIES','surfaces':['O'],'mobility':0},"
                + "{'tooth':11,'condition':'HEALTHY','surfaces':[],'mobility':0},"
                + "{'tooth':12,'condition':'HEALTHY','surfaces':[],'mobility':2}]"), ctx("dental"));
        assertThat(value.path("teeth")).hasSize(2);
        assertThat(value.path("teeth").get(0).path("tooth").asInt()).isEqualTo(16);
        assertThat(value.path("teeth").get(1).path("tooth").asInt()).isEqualTo(12);
    }

    @Test
    void aToothChartRefusesABadFdiNumberSurfacesOnAHealthyToothAndARepeatedTooth() {
        assertThat(problems("TOOTH_CHART", chart("[{'tooth':19,'condition':'CARIES','surfaces':[],'mobility':0}]"), null,
                ctx("dental"))).anyMatch(e -> e.contains("FDI"));
        assertThat(problems("TOOTH_CHART", chart("[{'tooth':16,'condition':'MISSING','surfaces':['O'],'mobility':0}]"),
                null, ctx("dental"))).anyMatch(e -> e.contains("caries, filled and crown"));
        assertThat(problems("TOOTH_CHART", chart("[{'tooth':16,'condition':'CARIES','surfaces':[],'mobility':0},"
                + "{'tooth':16,'condition':'FILLED','surfaces':[],'mobility':0}]"), null, ctx("dental")))
                .anyMatch(e -> e.contains("listed twice"));
    }

    // ---- DENTAL_PLAN ----

    private static String plan(String rows, String extra) {
        return "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':" + rows + extra + "}";
    }

    private static String row(String id, int tooth, String text, String status, int planned, int current) {
        return "{'id':'" + id + "','tooth':" + tooth + ",'procedureKey':null,'procedureText':'" + text
                + "','phase':1,'status':'" + status + "','sittingsPlanned':" + planned + ",'currentSitting':" + current
                + "}";
    }

    private static final Tariff TARIFF = name -> name.equalsIgnoreCase("Composite filling")
            ? Optional.of(new TariffItem(SERVICE, 150_000)) : Optional.empty();

    @Test
    void theSamePlanRowTwiceIgnoringCaseIsADuplicateItem() {
        String rows = "[" + row(OTHER.toString(), 16, "Composite filling (O)", "PLANNED", 1, 1) + ","
                + row(UUID.randomUUID().toString(), 16, "composite FILLING (o)", "PLANNED", 1, 1) + "]";
        assertThatThrownBy(() -> normalize("DENTAL_PLAN", plan(rows, ""), null, ctx("dental"), new ArrayList<>(),
                new ArrayList<>())).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> assertThat(((EmrProblemException) e).slug()).isEqualTo("duplicate-item"));
    }

    @Test
    void aTariffMatchGivesTheEstimateTaggedB2AndTheSharesAddUp() {
        String rows = "[" + row(OTHER.toString(), 16, "Composite filling (O)", "PLANNED", 1, 1) + ","
                + row(UUID.randomUUID().toString(), 26, "Root canal treatment", "PLANNED", 3, 1) + "]";
        ObjectNode value = ok("DENTAL_PLAN", plan(rows, ""), ctx("dental", TARIFF, null, Set.of(), c -> null));
        JsonNode first = value.path("rows").get(0);
        assertThat(first.path("estimateSource").asText()).isEqualTo("TARIFF");
        assertThat(first.path("estimatePaise").asLong()).isEqualTo(150_000);
        assertThat(first.path("serviceId").asText()).isEqualTo(SERVICE.toString());
        JsonNode second = value.path("rows").get(1);
        assertThat(second.path("estimateSource").asText()).isEqualTo("NONE");
        assertThat(second.path("estimatePaise").isNull()).isTrue();
        assertThat(value.path("estimate").path("totalPaise").asLong()).isEqualTo(150_000);
    }

    @Test
    void aTypedAmountIsUsedOnlyWhenTheProcedureIsNotInTheTariff() {
        String typed = "{'id':'" + OTHER + "','tooth':36,'procedureKey':null,'procedureText':'Gum surgery','phase':1,"
                + "'status':'PLANNED','sittingsPlanned':2,'currentSitting':1,'typedEstimatePaise':1000000}";
        ObjectNode value = ok("DENTAL_PLAN", plan("[" + typed + "]", ""), ctx("dental", TARIFF, null, Set.of(),
                c -> null));
        JsonNode planned = value.path("rows").get(0);
        assertThat(planned.path("estimateSource").asText()).isEqualTo("TYPED");
        assertThat(planned.path("estimatePaise").asLong()).isEqualTo(1_000_000);
        assertThat(planned.path("sittingSharesPaise")).hasSize(2);
        assertThat(planned.path("sittingSharesPaise").get(0).asLong() + planned.path("sittingSharesPaise").get(1).asLong())
                .isEqualTo(1_000_000);
    }

    @Test
    void doneOnIsStampedWithTodayAndKeptTheClientCannotSetIt() {
        String sent = "{'id':'" + OTHER + "','tooth':16,'procedureKey':null,'procedureText':'Extraction','phase':1,"
                + "'status':'DONE','sittingsPlanned':1,'currentSitting':1,'doneOn':'2000-01-01'}";
        ObjectNode value = ok("DENTAL_PLAN", plan("[" + sent + "]", ""), ctx("dental"));
        assertThat(value.path("rows").get(0).path("doneOn").asText()).isEqualTo("2026-11-12");
        String stored = value.toString();
        ctxLater();
        List<String> errors = new ArrayList<>();
        ObjectNode again = normalize("DENTAL_PLAN", plan("[" + sent.replace("2000-01-01", "2001-01-01") + "]", ""),
                stored, new SpecialtyCtx("dental", NOW.plusSeconds(86_400), TODAY.plusDays(1), TODAY, MEMBER, null,
                        "F", reference(), Tariff.none(), Set.of(), null, c -> null, Hooks.none()), errors,
                new ArrayList<>());
        assertThat(errors).isEmpty();
        assertThat(again.path("rows").get(0).path("doneOn").asText()).isEqualTo("2026-11-12");
        String reopened = sent.replace("'DONE'", "'PLANNED'");
        assertThat(ok("DENTAL_PLAN", plan("[" + reopened + "]", ""), ctx("dental")).path("rows").get(0).path("doneOn")
                .isNull()).isTrue();
    }

    private void ctxLater() {
        // the second save below runs "tomorrow": the stamp of the first day must stay
    }

    @Test
    void theEstimateSignatureIsStampedAndAnyLaterChangeClearsItWithAnAuditNote() {
        String rows = "[" + row(OTHER.toString(), 16, "Composite filling (O)", "PLANNED", 1, 1) + "]";
        List<Note> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ObjectNode signed = normalize("DENTAL_PLAN", plan(rows, ",'signEstimate':true"), null,
                ctx("dental", TARIFF, null, Set.of(), c -> null), errors, notes);
        assertThat(errors).isEmpty();
        assertThat(signed.path("estimate").path("state").asText()).isEqualTo("SIGNED");
        assertThat(signed.path("estimate").path("signed").path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(notes).extracting(Note::kind).containsExactly("EMR_ESTIMATE_SIGNED");
        assertThat(notes.get(0).values()).containsEntry("totalPaise", 150_000L);
        assertThat(signed.has("signEstimate")).isFalse();

        // saved again without a change: still signed, no new note
        notes.clear();
        ObjectNode same = normalize("DENTAL_PLAN", plan(rows, ""), signed.toString(),
                ctx("dental", TARIFF, null, Set.of(), c -> null), errors, notes);
        assertThat(same.path("estimate").path("state").asText()).isEqualTo("SIGNED");
        assertThat(notes).isEmpty();

        // a changed plan clears the signature
        String changed = "[" + row(OTHER.toString(), 17, "Composite filling (O)", "PLANNED", 1, 1) + "]";
        ObjectNode after = normalize("DENTAL_PLAN", plan(changed, ""), same.toString(),
                ctx("dental", TARIFF, null, Set.of(), c -> null), errors, notes);
        assertThat(after.path("estimate").path("state").asText()).isEqualTo("CHANGED");
        assertThat(after.path("estimate").path("signed").isNull()).isTrue();
        assertThat(notes).extracting(Note::kind).containsExactly("EMR_ESTIMATE_SIGN_CLEARED");

        // staying changed does not repeat the note, signing again signs the new plan
        notes.clear();
        ObjectNode still = normalize("DENTAL_PLAN", plan(changed, ""), after.toString(),
                ctx("dental", TARIFF, null, Set.of(), c -> null), errors, notes);
        assertThat(still.path("estimate").path("state").asText()).isEqualTo("CHANGED");
        assertThat(notes).isEmpty();
        ObjectNode again = normalize("DENTAL_PLAN", plan(changed, ",'signEstimate':true"), still.toString(),
                ctx("dental", TARIFF, null, Set.of(), c -> null), errors, notes);
        assertThat(again.path("estimate").path("state").asText()).isEqualTo("SIGNED");
    }

    @Test
    void anEmptyPlanCannotBeSigned() {
        ObjectNode value = ok("DENTAL_PLAN", plan("[]", ",'signEstimate':true"), ctx("dental"));
        assertThat(value.path("estimate").path("state").asText()).isEqualTo("NOT_SIGNED");
    }

    // ---- LAB_WORK and CONSENT_CHECKLIST ----

    @Test
    void aLabDueDateBeforeTodayIsRefusedForANewOrChangedItemOnly() {
        String item = "{'component':'LAB_WORK','schemaVersion':1,'items':[{'id':'" + OTHER + "','planRowId':null,"
                + "'tooth':16,'item':'CROWN','labName':'Lab one','dueDate':'2026-11-11'}]}";
        assertThat(problems("LAB_WORK", item, null, ctx("dental"))).anyMatch(e -> e.contains("dueDate")
                && e.contains("before today"));
        ok("LAB_WORK", item.replace("2026-11-11", "2026-11-20"), ctx("dental"));
        List<String> errors = new ArrayList<>();
        normalize("LAB_WORK", item, item.replace('\'', '"'), ctx("dental"), errors, new ArrayList<>());
        assertThat(errors).as("an unchanged old item is not checked again").isEmpty();
    }

    private static String consent(String items) {
        return "{'component':'CONSENT_CHECKLIST','schemaVersion':1,'items':" + items + "}";
    }

    @Test
    void surgeryConsentTakesTheFiveFixedItemsAndStampsTicksWithACountNote() {
        List<Note> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("CONSENT_CHECKLIST", consent("[{'key':'PROCEDURE_RISKS','confirmed':true},"
                + "{'key':'ANAESTHESIA','confirmed':false}]"), null, ctx("surgery"), errors, notes);
        assertThat(errors).isEmpty();
        assertThat(value.path("items").get(0).path("confirmedStamp").path("at").asText()).isEqualTo(NOW.toString());
        assertThat(value.path("items").get(1).path("confirmedStamp").isNull()).isTrue();
        assertThat(notes).extracting(Note::kind).containsExactly("EMR_CONSENT_RECORDED");
        assertThat(notes.get(0).values()).containsEntry("ticked", 1).containsEntry("total", 2);
        assertThat(problems("CONSENT_CHECKLIST", consent("[{'key':'SOMETHING_ELSE','confirmed':true}]"), null,
                ctx("surgery"))).anyMatch(e -> e.contains("five consent items"));
        assertThat(problems("CONSENT_CHECKLIST", consent("[{'key':'ANAESTHESIA','confirmed':true},"
                + "{'key':'ANAESTHESIA','confirmed':false}]"), null, ctx("surgery"))).anyMatch(e -> e.contains(
                "listed twice"));
    }

    @Test
    void dentalConsentItemsAreKeyedByPlanRowAndAStoredTickStampIsKept() {
        String stored = "{'component':'CONSENT_CHECKLIST','schemaVersion':1,'items':[{'key':'row-1','confirmed':true,"
                + "'confirmedStamp':{'at':'2026-11-12T01:00:00Z','byMembershipId':'" + OTHER + "'}}]}";
        List<Note> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("CONSENT_CHECKLIST", consent("[{'key':'row-1','formLabel':'RCT consent',"
                + "'confirmed':true}]"), stored, ctx("dental"), errors, notes);
        assertThat(errors).isEmpty();
        assertThat(value.path("items").get(0).path("confirmedStamp").path("at").asText())
                .isEqualTo("2026-11-12T01:00:00Z");
        assertThat(notes).isEmpty();
    }

    // ---- ANC_CHECK, FORM_F, GYNAE_EXAM ----

    private static final String ANC = "{'component':'ANC_CHECK','schemaVersion':1,'today':{'weightKg':60.5,"
            + "'bpSystolic':142,'bpDiastolic':92,'fundalHeightCm':27,'fetalHeartBpm':140,'hbGdl':9.5},"
            + "'symptoms':['HEADACHE'],'orders':[{'key':'GROWTH_SCAN_FORM_F'}],'scans':[{'key':'DATING','done':true}]}";

    @Test
    void anAncOrderIsStampedAndThePregnancyCourseComesFromTheServer() {
        UUID course = UUID.randomUUID();
        SpecialtyCtx withCourse = new SpecialtyCtx("og", NOW, TODAY, TODAY, MEMBER, null, "F", reference(),
                Tariff.none(), Set.of(), course, c -> null, Hooks.none());
        ObjectNode value = ok("ANC_CHECK", ANC.replace("'orders':[{'key':'GROWTH_SCAN_FORM_F'}]",
                "'orders':[{'key':'GROWTH_SCAN_FORM_F','ordered':{'at':'2000-01-01T00:00:00Z','byMembershipId':'"
                        + OTHER + "'}}],'pregnancyCourseId':'" + OTHER + "'"), withCourse);
        assertThat(value.path("orders").get(0).path("ordered").path("at").asText()).isEqualTo(NOW.toString());
        assertThat(value.path("pregnancyCourseId").asText()).isEqualTo(course.toString());
    }

    @Test
    void aValueOutsideTheAncSanityLimitOrAnOddFetalHeartIsRefusedTaggedC4() {
        assertThat(problems("ANC_CHECK", ANC.replace("'fetalHeartBpm':140", "'fetalHeartBpm':141"), null, ctx("og")))
                .anyMatch(e -> e.contains("steps of 2"));
        assertThat(problems("ANC_CHECK", ANC.replace("'hbGdl':9.5", "'hbGdl':25.0"), null, ctx("og")))
                .anyMatch(e -> e.contains("2 to 20"));
    }

    @Test
    void formFIsMarkedByTheServerAndTheScanKeyFollowsTheAncOrder() {
        JsonNode anc = json(ANC);
        List<Note> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ObjectNode marked = normalize("FORM_F", "{'component':'FORM_F','schemaVersion':1,'filledAndSigned':true}", null,
                ctx("og", Tariff.none(), null, Set.of(), c -> "ANC_CHECK".equals(c) ? anc : null), errors, notes);
        assertThat(errors).isEmpty();
        assertThat(marked.path("marked").path("at").asText()).isEqualTo(NOW.toString());
        assertThat(marked.path("scanKey").asText()).isEqualTo("GROWTH_SCAN_FORM_F");
        assertThat(marked.path("scanRemoved").asBoolean()).isFalse();
        assertThat(notes).extracting(Note::kind).containsExactly("EMR_FORM_F_MARKED");

        // the scan order is removed later: "Scan removed. Form F kept"
        notes.clear();
        ObjectNode kept = normalize("FORM_F", "{'component':'FORM_F','schemaVersion':1,'filledAndSigned':true}",
                marked.toString(), ctx("og"), errors, notes);
        assertThat(kept.path("scanRemoved").asBoolean()).isTrue();
        assertThat(kept.path("scanKey").asText()).isEqualTo("GROWTH_SCAN_FORM_F");
        assertThat(notes).isEmpty();
    }

    @Test
    void aChaperoneNameWithDeclinedIsRefused() {
        assertThat(problems("GYNAE_EXAM", "{'component':'GYNAE_EXAM','schemaVersion':1,'chaperoneName':'Sister A',"
                + "'chaperoneDeclined':true}", null, ctx("og"))).anyMatch(e -> e.contains("declined"));
        ok("GYNAE_EXAM", "{'component':'GYNAE_EXAM','schemaVersion':1,'chaperoneDeclined':true}", ctx("og"));
    }

    // ---- IMMUNIZATION and MILESTONES ----

    private static String vaccines(String keys) {
        StringBuilder given = new StringBuilder("[");
        for (String key : keys.split(",")) {
            if (given.length() > 1) {
                given.append(',');
            }
            given.append("{'vaccineKey':'").append(key).append("','given':{'at':'2000-01-01T00:00:00Z',"
                    + "'byMembershipId':'").append(OTHER).append("'}}");
        }
        return "{'component':'IMMUNIZATION','schemaVersion':1,'givenToday':" + given + "],'scheduleReviewed':false}";
    }

    @Test
    void aVaccineGivenTodayIsStampedWithTheDateAndWhoGaveIt() {
        ObjectNode value = ok("IMMUNIZATION", vaccines("hib_booster_1"), ctx("paeds", Tariff.none(),
                TODAY.minusMonths(17), Set.of(), c -> null));
        JsonNode given = value.path("givenToday").get(0).path("given");
        assertThat(given.path("givenOn").asText()).isEqualTo("2026-11-12");
        assertThat(given.path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(given.path("at").asText()).isEqualTo(NOW.toString());
    }

    @Test
    void aVaccineGivenInAnEarlierRecordOrNotDueOrUnknownCannotBeAddedTaggedC5() {
        SpecialtyCtx child = ctx("paeds", Tariff.none(), TODAY.minusMonths(17), Set.of("bcg"), c -> null);
        assertThat(problems("IMMUNIZATION", vaccines("bcg"), null, child)).anyMatch(e -> e.contains("given earlier"));
        SpecialtyCtx young = ctx("paeds", Tariff.none(), TODAY.minusWeeks(2), Set.of(), c -> null);
        assertThat(problems("IMMUNIZATION", vaccines("hib_booster_1"), null, young)).anyMatch(e -> e.contains(
                "not due yet"));
        assertThat(problems("IMMUNIZATION", vaccines("nope"), null, young)).anyMatch(e -> e.contains("schedule"));
    }

    @Test
    void withoutADateOfBirthNoNewVaccineCanBeAddedAndTheErrorNamesTheEntryNotTheValue() {
        // opd.yaml 0.5.3 (SAH-62-2): no date of birth, every vaccine is LATER, so none can be added (400); DEC-051 M7
        assertThat(problems("IMMUNIZATION", vaccines("hib_booster_1"), null, ctx("paeds")))
                .anyMatch(e -> e.contains("givenToday[0].vaccineKey") && e.contains("not due yet"));
    }

    @Test
    void withoutADateOfBirthAVaccineAlreadyStoredOnTheRecordStaysAndAnUnknownVaccineStillSaysSchedule() {
        ObjectNode stored = ok("IMMUNIZATION", vaccines("hib_booster_1"), ctx("paeds", Tariff.none(),
                TODAY.minusMonths(17), Set.of(), c -> null));
        List<String> errors = new ArrayList<>();
        normalize("IMMUNIZATION", vaccines("hib_booster_1"), stored.toString(), ctx("paeds"), errors, new ArrayList<>());
        assertThat(errors).isEmpty();
        assertThat(problems("IMMUNIZATION", vaccines("nope"), null, ctx("paeds"))).anyMatch(e -> e.contains("schedule"));
    }

    @Test
    void milestonesAreOnlyForAgeBandsInTheReferenceTaggedC5() {
        String eighteen = "{'component':'MILESTONES','schemaVersion':1,'ageBandMonths':18,'achieved':['walks_alone'],"
                + "'notYet':['says_6_or_more_words']}";
        ok("MILESTONES", eighteen, ctx("paeds"));
        assertThat(problems("MILESTONES", eighteen.replace(":18,", ":24,"), null, ctx("paeds"))).anyMatch(
                e -> e.contains("not set up"));
        assertThat(problems("MILESTONES", eighteen.replace("'notYet':['says_6_or_more_words']",
                "'notYet':['walks_alone']"), null, ctx("paeds"))).anyMatch(e -> e.contains("both achieved"));
        assertThat(problems("MILESTONES", eighteen.replace("walks_alone'],", "flies'],"), null, ctx("paeds")))
                .anyMatch(e -> e.contains("from the list"));
    }

    // ---- physio ----

    @Test
    void aGoalGetsItsAchievedDateWhenItBecomesAchievedAndKeepsIt() {
        String goal = "{'component':'GOALS','schemaVersion':1,'goals':[{'id':'" + OTHER
                + "','text':'Walk 500 m','achieved':true,'achievedOn':'2000-01-01'}]}";
        ObjectNode first = ok("GOALS", goal, ctx("physio"));
        assertThat(first.path("goals").get(0).path("achievedOn").asText()).isEqualTo("2026-11-12");
        List<String> errors = new ArrayList<>();
        ObjectNode later = normalize("GOALS", goal, first.toString(), new SpecialtyCtx("physio", NOW.plusSeconds(86_400),
                TODAY.plusDays(1), TODAY, MEMBER, null, "F", reference(), Tariff.none(), Set.of(), null, c -> null,
                Hooks.none()), errors, new ArrayList<>());
        assertThat(later.path("goals").get(0).path("achievedOn").asText()).isEqualTo("2026-11-12");
        ObjectNode reopened = ok("GOALS", goal.replace("'achieved':true", "'achieved':false"), ctx("physio"));
        assertThat(reopened.path("goals").get(0).path("achievedOn").isNull()).isTrue();
    }

    @Test
    void aModalityWithAKeyTakesItsDefaultParametersTaggedC6() {
        ObjectNode value = ok("MODALITIES", "{'component':'MODALITIES','schemaVersion':1,'given':[{'key':'IFT',"
                + "'text':'IFT','params':null},{'key':null,'text':'Cupping','params':null}]}", ctx("physio"));
        assertThat(value.path("given").get(0).path("params").asText()).isEqualTo("15 min · knee, 4-pole");
        assertThat(value.path("given").get(1).path("params").isNull()).isTrue();
    }

    @Test
    void romRowsAndSpecialTestsCannotRepeat() {
        assertThat(problems("ROM_TABLE", "{'component':'ROM_TABLE','schemaVersion':1,'joint':'knee','rows':["
                + "{'movement':'flexion','rightDeg':100,'leftDeg':null},{'movement':'Flexion','rightDeg':90,"
                + "'leftDeg':null}]}", null, ctx("physio"))).anyMatch(e -> e.contains("listed twice"));
    }

    // ---- surgery ----

    @Test
    void aSurgeryPlanTakesThePackageFromTheTariffElseTheTypedAmountElseNone() {
        String planJson = "{'component':'SURGERY_PLAN','schemaVersion':1,'procedureKey':'LAP_CHOLECYSTECTOMY',"
                + "'procedureText':null,'anaesthesia':'GA','admission':'IPD_1_2_DAYS','payer':'SELF',"
                + "'typedPackagePaise':9900000,'booking':{'stamp':{'at':'2000-01-01T00:00:00Z'}}}";
        Tariff lap = name -> name.equals("Lap cholecystectomy") ? Optional.of(new TariffItem(SERVICE, 6_500_000))
                : Optional.empty();
        ObjectNode tariff = ok("SURGERY_PLAN", planJson, ctx("surgery", lap, null, Set.of(), c -> null));
        assertThat(tariff.path("procedureText").asText()).isEqualTo("Lap cholecystectomy");
        assertThat(tariff.path("packageSource").asText()).isEqualTo("TARIFF");
        assertThat(tariff.path("packagePaise").asLong()).isEqualTo(6_500_000);
        assertThat(tariff.path("booking").isNull()).as("a client cannot set the booking").isTrue();
        ObjectNode typed = ok("SURGERY_PLAN", planJson, ctx("surgery"));
        assertThat(typed.path("packageSource").asText()).isEqualTo("TYPED");
        assertThat(typed.path("packagePaise").asLong()).isEqualTo(9_900_000);
        ObjectNode none = ok("SURGERY_PLAN", planJson.replace("'typedPackagePaise':9900000,", ""), ctx("surgery"));
        assertThat(none.path("packageSource").asText()).isEqualTo("NONE");
        assertThat(none.path("packagePaise").isNull()).isTrue();
    }

    @Test
    void aStoredBookingStaysOnTheSurgeryPlan() {
        String stored = "{'component':'SURGERY_PLAN','schemaVersion':1,'booking':{'stamp':{'at':'2026-11-12T05:00:00Z'},"
                + "'courseId':'" + OTHER + "'}}";
        ObjectNode value = normalize("SURGERY_PLAN", "{'component':'SURGERY_PLAN','schemaVersion':1,"
                + "'procedureKey':null,'procedureText':'Wound toilet','anaesthesia':'LOCAL','admission':'DAY_CARE',"
                + "'payer':'SELF'}", stored, ctx("surgery"), new ArrayList<>(), new ArrayList<>());
        assertThat(value.path("booking").path("courseId").asText()).isEqualTo(OTHER.toString());
    }

    // ---- oncology ----

    @Test
    void anOncologyLesionKeepsItsLastSizeAndTheClientCannotSetIt() {
        String exam = "{'component':'ONC_EXAM','schemaVersion':1,'heightCm':160,'weightKg':60,'capAt2':true,'signs':{},"
                + "'lesions':[{'id':'%s','name':'Left breast','baselineCm':5.0,'lastCm':1.0,'todayCm':3.5}],"
                + "'newLesionSeen':false,'breastSkin':[],'systems':{},'line':{'device':'PORT','checks':[]}}";
        String stored = "{'component':'ONC_EXAM','schemaVersion':1,'lesions':[{'id':'" + OTHER
                + "','name':'Left breast','baselineCm':5.0,'lastCm':4.0,'todayCm':null}]}";
        List<String> errors = new ArrayList<>();
        ObjectNode value = normalize("ONC_EXAM", exam.formatted(OTHER), stored, ctx("onco"), errors, new ArrayList<>());
        assertThat(errors).isEmpty();
        assertThat(value.path("lesions").get(0).path("lastCm").asDouble()).isEqualTo(4.0);
        ObjectNode fresh = ok("ONC_EXAM", exam.formatted(OTHER), ctx("onco"));
        assertThat(fresh.path("lesions").get(0).path("lastCm").isNull()).isTrue();
    }

    @Test
    void oncologyLabsTimeCannotBeInTheFutureAndDefaultsToNowTaggedC8() {
        String labs = "{'component':'ONC_LABS','schemaVersion':1,'drawnAt':%s,'values':{'hb':10.5,'anc':1800}}";
        assertThat(problems("ONC_LABS", labs.formatted("'2026-11-12T08:00:00Z'"), null, ctx("onco")))
                .anyMatch(e -> e.contains("future"));
        ObjectNode value = ok("ONC_LABS", labs.formatted("null"), ctx("onco"));
        assertThat(value.path("drawnAt").asText()).isEqualTo(NOW.toString());
        assertThat(value.path("source").asText()).isEqualTo("TYPED");
        ObjectNode noValues = ok("ONC_LABS", "{'component':'ONC_LABS','schemaVersion':1,'drawnAt':null,'values':{}}",
                ctx("onco"));
        assertThat(noValues.path("drawnAt").isNull()).isTrue();
    }

    @Test
    void aGradeOutsideZeroToFourAndARepeatedSymptomAreRefused() {
        assertThat(problems("ONC_TOX", "{'component':'ONC_TOX','schemaVersion':1,'symptoms':[{'key':'NAUSEA',"
                + "'patientGrade':1,'confirmedGrade':5}],'events':[]}", null, ctx("onco"))).anyMatch(
                e -> e.contains("0 to 4"));
        assertThat(problems("ONC_TOX", "{'component':'ONC_TOX','schemaVersion':1,'symptoms':[{'key':'NAUSEA',"
                + "'patientGrade':1,'confirmedGrade':1},{'key':'NAUSEA','patientGrade':0,'confirmedGrade':0}],"
                + "'events':[]}", null, ctx("onco"))).anyMatch(e -> e.contains("listed twice"));
    }
}
