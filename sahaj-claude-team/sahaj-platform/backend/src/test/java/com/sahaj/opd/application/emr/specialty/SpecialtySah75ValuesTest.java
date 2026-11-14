package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * SAH-75 (ADR-026 D5, D6, D8): the Dental "Medical history and alerts" value, the pain marker, the oncology body map and
 * "Mark all due as given". Values from the EMR mockup, not clinically reviewed.
 */
class SpecialtySah75ValuesTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
                List.of(), List.of(), List.of(), List.of());
    }

    private static SpecialtyCtx ctx(String profile, LocalDate dob) {
        return new SpecialtyCtx(profile, NOW, TODAY, TODAY, MEMBER, dob, "F", reference(), Tariff.none(), Set.of(), null,
                c -> null, Hooks.none());
    }

    private static ObjectNode normalize(String component, String input, String stored, SpecialtyCtx ctx,
                                        List<String> errors, List<Note> notes) {
        Sp.Errors problems = new Sp.Errors("specialty");
        ObjectNode result = SpecialtyValues.normalize(component, json(input), stored == null ? null : json(stored), ctx,
                problems, notes);
        problems.messages().forEach(m -> errors.add(m.field() + ": " + m.message()));
        return result;
    }

    private static ObjectNode ok(String component, String input, String stored, SpecialtyCtx ctx, List<Note> notes) {
        List<String> errors = new ArrayList<>();
        ObjectNode result = normalize(component, input, stored, ctx, errors, notes);
        assertThat(errors).isEmpty();
        return result;
    }

    private static List<String> problems(String component, String input, SpecialtyCtx ctx) {
        List<String> errors = new ArrayList<>();
        assertThat(normalize(component, input, null, ctx, errors, new ArrayList<>())).isNull();
        return errors;
    }

    // ---- Dental: Medical history and alerts ----

    private static String medhx(String conditions, String medicines, String allergies, boolean reviewed) {
        return "{'component':'DENTAL_MED_HISTORY','schemaVersion':1,'conditions':[" + conditions + "],'medicines':["
                + medicines + "],'allergies':[" + allergies + "],'reviewedWithPatient':" + reviewed + "}";
    }

    @Test
    void reviewingTheCardStampsTheServersTimeAndWhoAndWritesOneAuditWithoutTheChips() {
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("DENTAL_MED_HISTORY", medhx("'DIABETES','HIGH_BP'", "'ASPIRIN'", "'PENICILLIN'", true),
                null, ctx("dental", null), notes);

        assertThat(value.path("reviewedWithPatient").asBoolean()).isTrue();
        assertThat(value.path("review").path("at").asText()).isEqualTo(NOW.toString());
        assertThat(value.path("review").path("byMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).kind()).isEqualTo("EMR_DENTAL_MEDHX_REVIEWED");
        assertThat(notes.get(0).values()).containsOnlyKeys("reviewed").containsEntry("reviewed", true);
        assertThat(notes.get(0).values().toString()).doesNotContain("DIABETES").doesNotContain("PENICILLIN");
    }

    @Test
    void aReviewStampSentByTheClientIsIgnoredAndTheStoredOneIsKeptWhileItStaysReviewed() {
        String stored = "{'component':'DENTAL_MED_HISTORY','schemaVersion':1,'conditions':[],'medicines':[],"
                + "'allergies':[],'reviewedWithPatient':true,'review':{'at':'2026-11-10T05:00:00Z',"
                + "'byMembershipId':'" + OTHER + "'}}";
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("DENTAL_MED_HISTORY", "{'component':'DENTAL_MED_HISTORY','schemaVersion':1,"
                + "'conditions':['THYROID'],'medicines':[],'allergies':[],'reviewedWithPatient':true,"
                + "'review':{'at':'2000-01-01T00:00:00Z','byMembershipId':'" + MEMBER + "'}}", stored,
                ctx("dental", null), notes);

        assertThat(value.path("review").path("at").asText()).isEqualTo("2026-11-10T05:00:00Z");
        assertThat(value.path("review").path("byMembershipId").asText()).isEqualTo(OTHER.toString());
        assertThat(notes).isEmpty();
    }

    @Test
    void takingTheReviewBackClearsTheStampAndAuditsOnlyThatItWasTakenBack() {
        String stored = "{'component':'DENTAL_MED_HISTORY','schemaVersion':1,'conditions':[],'medicines':[],"
                + "'allergies':[],'reviewedWithPatient':true,'review':{'at':'2026-11-10T05:00:00Z',"
                + "'byMembershipId':'" + OTHER + "'}}";
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("DENTAL_MED_HISTORY", medhx("", "", "", false), stored, ctx("dental", null), notes);

        assertThat(value.path("review").isNull()).isTrue();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).values()).containsEntry("reviewed", false);
    }

    @Test
    void aCardThatWasNeverReviewedAndStaysSoWritesNoAudit() {
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("DENTAL_MED_HISTORY", medhx("'ASTHMA'", "", "", false), null, ctx("dental", null), notes);

        assertThat(value.path("review").isNull()).isTrue();
        assertThat(notes).isEmpty();
    }

    @Test
    void noneTogetherWithAnotherChipOfTheSameGroupIsRefusedInEachGroup() {
        assertThat(problems("DENTAL_MED_HISTORY", medhx("'NONE','DIABETES'", "", "", false), ctx("dental", null)))
                .anyMatch(e -> e.startsWith("specialty.conditions"));
        assertThat(problems("DENTAL_MED_HISTORY", medhx("", "'NONE','ASPIRIN'", "", false), ctx("dental", null)))
                .anyMatch(e -> e.startsWith("specialty.medicines"));
        assertThat(problems("DENTAL_MED_HISTORY", medhx("", "", "'PENICILLIN','NONE'", false), ctx("dental", null)))
                .anyMatch(e -> e.startsWith("specialty.allergies"));
    }

    @Test
    void noneAloneIsAcceptedAndAChipOutsideTheDrawnListIsRefused() {
        ok("DENTAL_MED_HISTORY", medhx("'NONE'", "'NONE'", "'NONE'", false), null, ctx("dental", null),
                new ArrayList<>());
        assertThat(problems("DENTAL_MED_HISTORY", medhx("'GOUT'", "", "", false), ctx("dental", null))).isNotEmpty();
        assertThat(problems("DENTAL_MED_HISTORY", medhx("'DIABETES','DIABETES'", "", "", false), ctx("dental", null)))
                .isNotEmpty();
        assertThat(problems("DENTAL_MED_HISTORY", "{'component':'DENTAL_MED_HISTORY','schemaVersion':1,'conditions':[],"
                + "'medicines':[],'allergies':[]}", ctx("dental", null))).isNotEmpty();
    }

    @Test
    void theCardIsDoneOnlyWhenItWasReviewedWithThePatient() {
        SpecialtyDone.Ctx ctx = new SpecialtyDone.Ctx(TODAY, c -> null, "NONE");

        assertThat(SpecialtyDone.isDone("DENTAL_MED_HISTORY", json(medhx("'DIABETES'", "", "", false)), ctx)).isFalse();
        assertThat(SpecialtyDone.isDone("DENTAL_MED_HISTORY", json(medhx("", "", "", true)), ctx)).isTrue();
        assertThat(SpecialtyDone.isDone("DENTAL_MED_HISTORY", null, ctx)).isFalse();
    }

    // ---- Physiotherapy: pain site marker ----

    private static String pain(String extra) {
        return "{'component':'PAIN_SCORE','schemaVersion':1,'score':6" + extra + "}";
    }

    @Test
    void theNoteNowTakes120CharactersAndAMarkerOnADrawnRegionIsStored() {
        String note = "n".repeat(120);

        ObjectNode value = ok("PAIN_SCORE", pain(",'site':'" + note + "','siteMarker':{'side':'FRONT','x':0.31,'y':0.72,"
                + "'region':'rthigh'}"), null, ctx("physio", null), new ArrayList<>());

        assertThat(value.path("site").asText()).hasSize(120);
        assertThat(value.path("siteMarker").path("region").asText()).isEqualTo("rthigh");
        assertThat(problems("PAIN_SCORE", pain(",'site':'" + "n".repeat(121) + "'"), ctx("physio", null))).isNotEmpty();
    }

    @Test
    void aMarkerOutsideTheFigureTheBackViewOrTheUnitSquareIsRefusedAndNullClearsIt() {
        SpecialtyCtx physio = ctx("physio", null);

        assertThat(problems("PAIN_SCORE", pain(",'siteMarker':{'side':'FRONT','x':0.5,'y':0.5,'region':'tail'}"),
                physio)).anyMatch(e -> e.contains("siteMarker.region"));
        assertThat(problems("PAIN_SCORE", pain(",'siteMarker':{'side':'BACK','x':0.5,'y':0.5,'region':'chest'}"),
                physio)).isNotEmpty();
        assertThat(problems("PAIN_SCORE", pain(",'siteMarker':{'side':'FRONT','x':1.5,'y':0.5,'region':'chest'}"),
                physio)).isNotEmpty();
        assertThat(ok("PAIN_SCORE", pain(",'siteMarker':null"), null, physio, new ArrayList<>()).path("siteMarker")
                .isNull()).isTrue();
    }

    // ---- Oncology: body map inside the examination ----

    private static String onc(String bodyMap) {
        return "{'component':'ONC_EXAM','schemaVersion':1,'capAt2':false,'signs':{},'lesions':[],'newLesionSeen':false,"
                + "'breastSkin':[],'systems':{},'line':{'device':null,'checks':[]}" + (bodyMap == null ? "" : ",'bodyMap':" + bodyMap)
                + "}";
    }

    private static String pin(int no, String cause, boolean mlc) {
        return "{'no':" + no + ",'side':'FRONT','x':0.4,'y':0.3,'region':'chest','where':'upper outer','finding':'LUMP',"
                + "'cause':'" + cause + "','severity':'MILD','sizeCm':2.5,'note':'','mlc':" + mlc + "}";
    }

    private static String map(String... pins) {
        return "{'component':'BODY_MAP','schemaVersion':1,'pins':[" + String.join(",", pins) + "],'nextPinNo':1,"
                + "'noFindingsConfirmed':false}";
    }

    @Test
    void theOncologyBodyMapGetsTheSameStampsAndNextPinNumberAsEveryBodyMap() {
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("ONC_EXAM", onc(map(pin(1, "NO_INJURY", false), pin(2, "NO_INJURY", false))), null,
                ctx("onco", null), notes);

        JsonNode bodyMap = value.path("bodyMap");
        assertThat(bodyMap.path("pins")).hasSize(2);
        assertThat(bodyMap.path("nextPinNo").asInt()).isEqualTo(3);
        assertThat(bodyMap.path("pins").get(0).path("recordedAt").asText()).isEqualTo(NOW.toString());
        assertThat(bodyMap.path("pins").get(0).path("recordedByMembershipId").asText()).isEqualTo(MEMBER.toString());
        assertThat(notes).isEmpty();
    }

    @Test
    void aRoadAccidentCauseSetsTheMlcFlagAndWritesTheMlcAuditLikeEveryBodyMap() {
        List<Note> notes = new ArrayList<>();

        ObjectNode value = ok("ONC_EXAM", onc(map(pin(1, "ROAD_ACCIDENT", false))), null, ctx("onco", null), notes);

        assertThat(value.path("bodyMap").path("pins").get(0).path("mlc").asBoolean()).isTrue();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).kind()).isEqualTo("MLC_CHANGED");
        assertThat(notes.get(0).values()).containsEntry("pinNo", 1).containsEntry("mlc", true);
    }

    @Test
    void aPinNumberIsNeverReusedAndAPinOutsideTheDrawnRegionsIsRefusedWithTheErrorOnTheBodyMap() {
        String stored = onc(map(pin(1, "NO_INJURY", false), pin(3, "NO_INJURY", false)));
        ObjectNode storedValue = ok("ONC_EXAM", stored, null, ctx("onco", null), new ArrayList<>());

        List<String> errors = new ArrayList<>();
        normalize("ONC_EXAM", onc(map(pin(1, "NO_INJURY", false), pin(2, "NO_INJURY", false))), storedValue.toString(),
                ctx("onco", null), errors, new ArrayList<>());
        assertThat(errors).anyMatch(e -> e.startsWith("specialty.bodyMap"));

        assertThat(problems("ONC_EXAM", onc(map(pin(1, "NO_INJURY", false).replace("'chest'", "'tail'"))),
                ctx("onco", null))).anyMatch(e -> e.startsWith("specialty.bodyMap"));
    }

    @Test
    void noPinsOrNoMapStoresNullAndRemovingAMapWithAnMlcPinWritesTheMlcOffAudit() {
        ObjectNode withMlc = ok("ONC_EXAM", onc(map(pin(1, "ASSAULT", false))), null, ctx("onco", null),
                new ArrayList<>());
        assertThat(ok("ONC_EXAM", onc(null), null, ctx("onco", null), new ArrayList<>()).path("bodyMap").isNull())
                .isTrue();
        assertThat(ok("ONC_EXAM", onc(map()), null, ctx("onco", null), new ArrayList<>()).path("bodyMap").isNull())
                .isTrue();
        List<Note> notes = new ArrayList<>();

        ObjectNode removed = ok("ONC_EXAM", onc(null), withMlc.toString(), ctx("onco", null), notes);

        assertThat(removed.path("bodyMap").isNull()).isTrue();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).values()).containsEntry("pinNo", 1).containsEntry("mlc", false);
    }

    @Test
    void theBodyMapIsOptionalSoTheExaminationDoneRuleIsUnchangedByIt() {
        SpecialtyDone.Ctx ctx = new SpecialtyDone.Ctx(TODAY, c -> null, "NONE");
        JsonNode withoutMap = ok("ONC_EXAM", onc(null), null, ctx("onco", null), new ArrayList<>());
        JsonNode withMap = ok("ONC_EXAM", onc(map(pin(1, "NO_INJURY", false))), null, ctx("onco", null),
                new ArrayList<>());

        assertThat(SpecialtyDone.isDone("ONC_EXAM", withMap, ctx)).isEqualTo(SpecialtyDone.isDone("ONC_EXAM", withoutMap,
                ctx));
    }

    // ---- Paediatrics: Mark all due as given ----

    private static String vaccines(String keys) {
        StringBuilder given = new StringBuilder("[");
        for (String key : keys.split(",")) {
            if (given.length() > 1) {
                given.append(',');
            }
            given.append("{'vaccineKey':'").append(key).append("'}");
        }
        return "{'component':'IMMUNIZATION','schemaVersion':1,'givenToday':" + given + "],'scheduleReviewed':false}";
    }

    @Test
    void anOverdueVaccineAndADueTodayVaccineAreBothAcceptedInOneSave() {
        // 17 months: Hib booster 1 is due today (16 to 19 months), BCG is overdue (its window ended at 6 weeks)
        ObjectNode value = ok("IMMUNIZATION", vaccines("bcg,hib_booster_1"), null, ctx("paeds", TODAY.minusMonths(17)),
                new ArrayList<>());

        assertThat(value.path("givenToday")).hasSize(2);
        assertThat(value.path("givenToday").get(0).path("given").path("givenOn").asText()).isEqualTo("2026-11-12");
    }

    @Test
    void aVaccineSentTwiceByAQuickSecondPressIsStoredOnceWithoutAnError() {
        ObjectNode value = ok("IMMUNIZATION", vaccines("hib_booster_1,hib_booster_1,bcg,bcg"), null,
                ctx("paeds", TODAY.minusMonths(17)), new ArrayList<>());

        assertThat(value.path("givenToday")).extracting(v -> v.path("vaccineKey").asText())
                .containsExactly("hib_booster_1", "bcg");
    }
}
