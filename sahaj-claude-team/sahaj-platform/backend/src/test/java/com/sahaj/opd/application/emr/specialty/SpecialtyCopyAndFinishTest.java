package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * "Copy from last visit" for the specialty components (ADR-023 D3) and the finish extension point (D9, SAH-51 later).
 */
class SpecialtyCopyAndFinishTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final LocalDate SIGNED = LocalDate.of(2026, 10, 20);

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ObjectNode copy(String component, String previous) {
        return SpecialtyCopy.copy(component, json(previous), SIGNED);
    }

    @Test
    void theToothChartCopiesTeethShadeAndGumButNotTheAllHealthyConfirmation() {
        ObjectNode copied = copy("TOOTH_CHART", "{'component':'TOOTH_CHART','schemaVersion':1,'numbering':'FDI',"
                + "'dentition':'CHILD','showNumbers':false,'teeth':[{'tooth':16,'condition':'CARIES','surfaces':['O'],"
                + "'mobility':0}],'shade':'A3','gum':'LIGHT_BROWN','allHealthyConfirmed':true}");
        assertThat(copied.path("teeth")).hasSize(1);
        assertThat(copied.path("shade").asText()).isEqualTo("A3");
        assertThat(copied.path("gum").asText()).isEqualTo("LIGHT_BROWN");
        assertThat(copied.path("allHealthyConfirmed").asBoolean()).isFalse();
        assertThat(copy("TOOTH_CHART", "{'component':'TOOTH_CHART','schemaVersion':1,'teeth':[],'shade':'A2',"
                + "'gum':'PINK','allHealthyConfirmed':true}")).as("nothing to copy").isNull();
    }

    @Test
    void theDentalPlanCarriesOverOnlyRowsStillPlannedOrInProgressWithoutTheEstimate() {
        ObjectNode copied = copy("DENTAL_PLAN", "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':["
                + "{'id':'a','tooth':16,'status':'DONE','doneOn':'2026-10-20'},{'id':'b','tooth':26,"
                + "'status':'IN_PROGRESS','doneOn':null},{'id':'c','tooth':36,'status':'PLANNED','doneOn':null}],"
                + "'estimate':{'state':'SIGNED','totalPaise':100}}");
        assertThat(copied.path("rows")).extracting(r -> r.path("id").asText()).containsExactly("b", "c");
        assertThat(copied.has("estimate")).isFalse();
        assertThat(copy("DENTAL_PLAN", "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':[{'id':'a',"
                + "'status':'DONE'}]}")).isNull();
    }

    @Test
    void growthIsCopiedIntoPreviousForComparisonOnlyWithTheSignedDate() {
        ObjectNode copied = copy("GROWTH", "{'component':'GROWTH','schemaVersion':1,'weightKg':9.8,'lengthCm':80.0,"
                + "'previous':{'date':'2026-01-01','weightKg':1.0}}");
        assertThat(copied.has("weightKg")).as("never today's values").isFalse();
        assertThat(copied.path("previous").path("date").asText()).isEqualTo("2026-10-20");
        assertThat(copied.path("previous").path("weightKg").asDouble()).isEqualTo(9.8);
        assertThat(copied.path("previous").path("headCircCm").isNull()).isTrue();
        assertThat(copy("GROWTH", "{'component':'GROWTH','schemaVersion':1}")).isNull();
    }

    @Test
    void goalsKeepTheirAchievedStateAndDate() {
        ObjectNode copied = copy("GOALS", "{'component':'GOALS','schemaVersion':1,'goals':[{'id':'g1','text':'Walk',"
                + "'achieved':true,'achievedOn':'2026-10-20'},{'id':'g2','text':'Stairs','achieved':false,"
                + "'achievedOn':null}]}");
        assertThat(copied.path("goals")).hasSize(2);
        assertThat(copied.path("goals").get(0).path("achievedOn").asText()).isEqualTo("2026-10-20");
    }

    @Test
    void anOncologyExamCopiesLesionNamesAndBaselinesWithLastTimesSizeAsTheLastSize() {
        ObjectNode copied = copy("ONC_EXAM", "{'component':'ONC_EXAM','schemaVersion':1,'heightCm':160,'weightKg':60,"
                + "'capAt2':false,'signs':{'pallor':'ABSENT'},'lesions':[{'id':'l1','name':'Left breast','baselineCm':5.0,"
                + "'lastCm':4.0,'todayCm':3.5}],'newLesionSeen':true,'breastSkin':['ULCERATION'],'systems':"
                + "{'cvs':{'state':'NORMAL'}},'line':{'device':'PORT','checks':['BLOOD_RETURN']}}");
        assertThat(copied.has("weightKg")).isFalse();
        assertThat(copied.path("capAt2").asBoolean()).isFalse();
        JsonNode lesion = copied.path("lesions").get(0);
        assertThat(lesion.path("name").asText()).isEqualTo("Left breast");
        assertThat(lesion.path("baselineCm").asDouble()).isEqualTo(5.0);
        assertThat(lesion.path("lastCm").asDouble()).isEqualTo(3.5);
        assertThat(lesion.path("todayCm").isNull()).isTrue();
        assertThat(copied.path("newLesionSeen").asBoolean()).isFalse();
        assertThat(copied.path("systems")).isEmpty();
        assertThat(copied.path("line").path("device").asText()).isEqualTo("PORT");
        assertThat(copied.path("line").path("checks")).isEmpty();
    }

    @Test
    void romMmtAndSpecialTestRowsAreCopiedAndEveryOtherComponentCopiesNothing() {
        assertThat(copy("ROM_TABLE", "{'component':'ROM_TABLE','schemaVersion':1,'joint':'knee','rows':["
                + "{'movement':'flexion','rightDeg':100,'leftDeg':null}]}").path("rows")).hasSize(1);
        assertThat(copy("MMT_TABLE", "{'component':'MMT_TABLE','schemaVersion':1,'rows':[{'group':'Quads','right':4,"
                + "'left':5}]}").path("rows")).hasSize(1);
        assertThat(copy("SPECIAL_TESTS", "{'component':'SPECIAL_TESTS','schemaVersion':1,'joint':'knee','results':["
                + "{'test':'Lachman','result':'POSITIVE'}]}").path("results")).hasSize(1);
        for (String component : new String[] {"VITALS_LOG", "EXAM_GENERAL", "LAB_WORK", "CONSENT_CHECKLIST", "ANC_CHECK",
                "FORM_F", "GYNAE_EXAM", "IMMUNIZATION", "MILESTONES", "PAIN_SCORE", "POSTURE_GAIT", "MODALITIES",
                "HOME_EXERCISES", "SESSION_NOTE", "SURGICAL_EXAM", "SURGERY_FITNESS", "SURGERY_PLAN", "PREAUTH",
                "PREOP_ORDERS", "ONC_TOX", "ONC_LABS", "ONC_ECOG", "CHEMO_PLAN", "DENTAL_DONE", "WEIGHT_DOSE"}) {
            assertThat(SpecialtyCopy.copy(component, json("{'component':'" + component + "','schemaVersion':1}"), SIGNED))
                    .as(component).isNull();
        }
        assertThat(copy("GOALS", "{'component':'TOOTH_CHART','schemaVersion':1}")).as("a value of another component")
                .isNull();
        assertThat(SpecialtyCopy.copy("GOALS", null, SIGNED)).isNull();
    }

    // ---- the finish extension point (no sign logic yet: SAH-51) ----

    @Test
    void aFinishContextCarriesTheRecordAndTheKeyAndItsToStringHoldsIdsOnly() {
        UUID id = UUID.randomUUID();
        RecordRow record = new RecordRow(id, "OPD_VISIT", UUID.randomUUID(), null, null, null, UUID.randomUUID(),
                UUID.randomUUID(), "dental", "Dental", "dental", "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null,
                "{}", null, null, false, null, 0, Instant.parse("2026-11-12T05:00:00Z"));
        SpecialtyFinishContributor contributor = new SpecialtyFinishContributor() {
            @Override
            public String profile() {
                return "dental";
            }
        };
        SpecialtyFinishContributor.FinishContext finish = new SpecialtyFinishContributor.FinishContext(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "DOCTOR", record, EmrRecordDocument.empty(MAPPER), null, null,
                "sign-key-0001", java.time.LocalDate.parse("2026-11-12"), Instant.parse("2026-11-12T05:00:00Z"));

        assertThat(contributor.profile()).isEqualTo("dental");
        assertThat(contributor.apply(finish)).isEqualTo(SpecialtyFinishContributor.ProfileResult.none());
        assertThat(finish.toString()).isEqualTo("FinishContext[recordId=" + id + "]");
        assertThat(finish.idempotencyKey()).isEqualTo("sign-key-0001");
    }
}
