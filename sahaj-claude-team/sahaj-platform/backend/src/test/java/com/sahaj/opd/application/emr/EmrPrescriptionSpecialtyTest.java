package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Context;
import com.sahaj.opd.application.emr.EmrComponentValues.Lookups;
import com.sahaj.opd.application.emr.EmrComponentValues.MedicineRef;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The 0.5.0 prescription rows (docs/api/opd.yaml EmrPrescriptionRow): dose by weight recomputed by the server (SAH-62-3,
 * tagged C-5: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed), supplements and
 * take-home lines with a fixed frequency text, the 0-1-0 frequency and a null frequency only with its text.
 */
class EmrPrescriptionSpecialtyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID PARA = UUID.randomUUID();

    private final EmrComponentValues values = new EmrComponentValues(EmrTestData.MAPPER);
    private final Lookups lookups = new Lookups() {
        @Override
        public Optional<MedicineRef> medicine(UUID id) {
            return PARA.equals(id) ? Optional.of(new MedicineRef(PARA, "Paracetamol syrup 120 mg / 5 ml", "Syrup", null,
                    "1-1-1", 3, "AFTER_FOOD")) : Optional.empty();
        }

        @Override
        public boolean activeService(UUID id) {
            return false;
        }
    };

    private static FieldDefinition field() {
        return new FieldDefinition("rx", "Rx", "SEARCH_LIST", EmrValueKind.COMPONENT, "", false, "MASTER", "medicines",
                true, List.of(), List.of(), null, null, new ComponentRef("PRESCRIPTION", null));
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode save(String row) {
        String rx = "{'component':'PRESCRIPTION','schemaVersion':1,'rows':[" + row + "]}";
        return values.normalize(field(), json(rx), null, new Context(NOW, DOC, null, lookups)).value();
    }

    private static String row(String frequency, String extra) {
        return "{'id':'" + UUID.randomUUID() + "','medicineId':'" + PARA + "','name':'x','form':null,'frequency':"
                + frequency + ",'days':3,'when':'AFTER_FOOD','source':'DOSE_BY_WEIGHT'" + extra + "}";
    }

    @Test
    void aDoseByWeightRowKeepsItsTextsAndTheServersOwnCalculationTaggedC5() {
        JsonNode saved = save(row("null", ",'doseText':'6.3 ml (150 mg)','frequencyText':'every 6 hours if needed, at most "
                + "4 doses a day','note':'Calculated by weight','calculation':{'doseRuleKey':'PARACETAMOL_120_5',"
                + "'weightKg':10.0,'mg':150,'ml':6.3,'capped':true}")).path("rows").get(0);
        assertThat(saved.path("frequency").isNull()).isTrue();
        assertThat(saved.path("doseText").asText()).isEqualTo("6.3 ml (150 mg)");
        assertThat(saved.path("frequencyText").asText()).startsWith("every 6 hours");
        assertThat(saved.path("note").asText()).isEqualTo("Calculated by weight");
        assertThat(saved.path("source").asText()).isEqualTo("DOSE_BY_WEIGHT");
        assertThat(saved.path("calculation").path("mg").asInt()).isEqualTo(150);
        assertThat(saved.path("calculation").path("ml").asDouble()).isEqualTo(6.3);
        assertThat(saved.path("calculation").path("capped").asBoolean()).as("capped is computed, never trusted").isFalse();
    }

    @Test
    void aWrongMgOrMlOrWeightIsA400DoseCalculationMismatchTaggedC5() {
        for (String calc : new String[] {
                "{'doseRuleKey':'PARACETAMOL_120_5','weightKg':10.0,'mg':160,'ml':6.3}",
                "{'doseRuleKey':'PARACETAMOL_120_5','weightKg':10.0,'mg':150,'ml':6.4}",
                "{'doseRuleKey':'PARACETAMOL_120_5','weightKg':10.5,'mg':150,'ml':6.3}",
                "{'doseRuleKey':'IBUPROFEN_100_5','weightKg':10.0,'mg':150,'ml':7.5}"}) {
            assertThatThrownBy(() -> save(row("null", ",'frequencyText':'x','calculation':" + calc)))
                    .isInstanceOf(EmrProblemException.class).satisfies(e -> {
                        assertThat(((EmrProblemException) e).slug()).isEqualTo("dose-calculation-mismatch");
                        assertThat(((EmrProblemException) e).status()).isEqualTo(400);
                        assertThat(e.getMessage()).doesNotContain("150").doesNotContain("6.3");
                    });
        }
    }

    @Test
    void theMaximumDoseIsAppliedAndSetCappedTaggedC5() {
        JsonNode saved = save(row("null", ",'frequencyText':'every 6 hours','calculation':{'doseRuleKey':'PARACETAMOL_120_5',"
                + "'weightKg':40.0,'mg':500,'ml':20.8}")).path("rows").get(0);
        assertThat(saved.path("calculation").path("capped").asBoolean()).isTrue();
    }

    @Test
    void anUnknownDoseRuleOrAnOutOfRangeWeightIsRefusedAsInvalid() {
        assertThatThrownBy(() -> save(row("null", ",'frequencyText':'x','calculation':{'doseRuleKey':'NOPE',"
                + "'weightKg':10.0,'mg':150,'ml':6.3}"))).isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> save(row("null", ",'frequencyText':'x','calculation':{'doseRuleKey':"
                + "'PARACETAMOL_120_5','weightKg':0.1,'mg':2,'ml':0.1}"))).isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> save(row("null", ",'frequencyText':'x','calculation':{'doseRuleKey':"
                + "'PARACETAMOL_120_5','weightKg':10.0,'mg':150,'ml':6.3,'colour':'red'}")))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void aNullFrequencyNeedsItsTextAndTheNewFrequencyZeroOneZeroIsAllowed() {
        assertThatThrownBy(() -> save(row("null", ""))).isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThat(save(row("'0-1-0'", ",'note':'2 h apart from iron'")).path("rows").get(0).path("frequency").asText())
                .isEqualTo("0-1-0");
        assertThatThrownBy(() -> save(row("'9-9-9'", ""))).isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void theTextsAreLimitedAndAnOldRowWithoutThemStillSavesWithNulls() {
        assertThatThrownBy(() -> save(row("'1-0-0'", ",'doseText':'" + "x".repeat(61) + "'"))).isInstanceOf(
                EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> save(row("null", ",'frequencyText':'" + "x".repeat(121) + "'"))).isInstanceOf(
                EmrValueRules.InvalidValue.class);
        JsonNode plain = save(row("'1-0-0'", "")).path("rows").get(0);
        assertThat(plain.path("doseText").isNull()).isTrue();
        assertThat(plain.path("calculation").isNull()).isTrue();
        assertThat(plain.path("source").asText()).isEqualTo("DOSE_BY_WEIGHT");
    }

    @Test
    void theNewSourcesAreAcceptedForAMasterMedicineAndATypedOneStaysTyped() {
        for (String source : new String[] {"DOSE_BY_WEIGHT", "SPECIALTY_LIST"}) {
            JsonNode saved = save(row("'1-0-0'", "").replace("DOSE_BY_WEIGHT", source)).path("rows").get(0);
            assertThat(saved.path("source").asText()).isEqualTo(source);
        }
        String typed = "{'id':'" + UUID.randomUUID() + "','medicineId':null,'name':'Home remedy','form':null,"
                + "'frequency':'1-0-0','days':3,'when':'AFTER_FOOD','source':'SPECIALTY_LIST'}";
        assertThat(save(typed).path("rows").get(0).path("source").asText()).isEqualTo("TYPED");
    }
}
