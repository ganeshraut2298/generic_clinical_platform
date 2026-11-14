package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SpPreopOrders.blood is one of NOT_NEEDED, GROUP_AND_SAVE, ONE_UNIT_RESERVED or null (docs/api/opd.yaml 0.5.2; ADR-023 D14
 * item 3, rule C-7). Any other value on write is a validation error named {@code blood}; a stored legacy value reads as null.
 */
class SpecialtyPreopBloodTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ObjectNode normalize(String blood, List<String> errors) {
        String bloodPart = blood == null ? "" : ",'blood':" + blood;
        SpecialtyCtx ctx = new SpecialtyCtx("surgery", NOW, TODAY, TODAY, UUID.randomUUID(), null, "F",
                SpecialtyReferenceData.empty(), Tariff.none(), Set.of(), null, c -> null, Hooks.none());
        Sp.Errors problems = new Sp.Errors("specialty");
        ObjectNode result = SpecialtyValues.normalize("PREOP_ORDERS", json("{'component':'PREOP_ORDERS','schemaVersion':1,"
                + "'ticked':['NIL_BY_MOUTH'],'extraLines':[]" + bloodPart + "}"), null, ctx, problems,
                new ArrayList<Note>());
        problems.messages().forEach(m -> errors.add(m.field() + ": " + m.message()));
        return result;
    }

    @Test
    void eachOfTheThreeBloodKeysIsAcceptedTaggedC7() {
        for (String key : List.of("NOT_NEEDED", "GROUP_AND_SAVE", "ONE_UNIT_RESERVED")) {
            List<String> errors = new ArrayList<>();
            ObjectNode value = normalize("'" + key + "'", errors);
            assertThat(errors).isEmpty();
            assertThat(value.path("blood").asText()).isEqualTo(key);
        }
    }

    @Test
    void noBloodChoiceIsAcceptedAsNullOrMissingTaggedC7() {
        List<String> errors = new ArrayList<>();
        assertThat(normalize("null", errors).path("blood").isNull()).isTrue();
        assertThat(normalize(null, errors).has("blood")).isFalse();
        assertThat(errors).isEmpty();
    }

    @Test
    void anyOtherBloodValueIsRefusedOnTheFieldBloodAndNeverEchoedTaggedC7() {
        for (String bad : List.of("'1 unit reserved'", "'group_and_save'", "'TYPO'", "5", "true", "['NOT_NEEDED']")) {
            List<String> errors = new ArrayList<>();
            ObjectNode value = normalize(bad, errors);
            assertThat(value).as(bad).isNull();
            assertThat(errors).as(bad).hasSize(1);
            assertThat(errors.get(0)).startsWith("specialty.blood: ").doesNotContain("TYPO");
        }
    }

    @Test
    void aStoredLegacyBloodValueReadsAsNullAndTheStoredValueIsUntouchedTaggedC7() {
        ObjectNode stored = (ObjectNode) json("{'component':'PREOP_ORDERS','schemaVersion':1,'ticked':[],"
                + "'extraLines':[],'blood':'Two units of O positive'}");
        ObjectNode copy = stored.deepCopy();
        SpecialtyValues.readable(copy);
        assertThat(copy.has("blood")).isTrue();
        assertThat(copy.path("blood").isNull()).isTrue();
        assertThat(stored.path("blood").asText()).isEqualTo("Two units of O positive");
    }

    @Test
    void aValidStoredBloodValueAndOtherComponentsAreReadAsTheyAre() {
        ObjectNode valid = (ObjectNode) json("{'component':'PREOP_ORDERS','schemaVersion':1,'ticked':[],"
                + "'extraLines':[],'blood':'GROUP_AND_SAVE'}");
        SpecialtyValues.readable(valid);
        assertThat(valid.path("blood").asText()).isEqualTo("GROUP_AND_SAVE");
        ObjectNode none = (ObjectNode) json("{'component':'PREOP_ORDERS','schemaVersion':1,'ticked':[],'extraLines':[]}");
        SpecialtyValues.readable(none);
        assertThat(none.has("blood")).isFalse();
        ObjectNode other = (ObjectNode) json("{'component':'PREAUTH','schemaVersion':1,'blood':'anything'}");
        SpecialtyValues.readable(other);
        assertThat(other.path("blood").asText()).isEqualTo("anything");
    }
}
