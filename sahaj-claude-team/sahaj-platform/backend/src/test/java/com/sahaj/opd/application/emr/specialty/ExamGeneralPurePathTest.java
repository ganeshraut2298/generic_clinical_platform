package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.specialty.Sp.Errors;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * DEC-054: the pure code paths of one general examination save (shape check, Done rule, JSON round trip, the section
 * and profile lookups) must finish at once. The payload is the one the live journey script sends.
 */
@Timeout(5)
class ExamGeneralPurePathTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String PAYLOAD = "{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,"
            + "\"signs\":{\"pallor\":\"ABSENT\",\"icterus\":\"ABSENT\",\"cyanosis\":\"ABSENT\",\"oedema\":\"ABSENT\"},"
            + "\"systems\":{\"cvs\":{\"state\":\"NORMAL\",\"text\":\"S1 S2 heard, no murmur\"},"
            + "\"rs\":{\"state\":\"NORMAL\",\"text\":\"Air entry equal, clear\"},"
            + "\"pa\":{\"state\":\"NORMAL\",\"text\":\"Soft, non-tender\"}}}";

    private static SpecialtyDone.Ctx doneCtx() {
        return new SpecialtyDone.Ctx(LocalDate.of(2026, 11, 14), component -> null, "NONE");
    }

    @Test
    void shapeCheckAndNormalizeFinish() throws Exception {
        JsonNode input = MAPPER.readTree(PAYLOAD);
        Errors errors = new Errors("sections[0].values.specialty");
        List<Note> notes = new ArrayList<>();
        ObjectNode value = SpecialtyValues.normalize("EXAM_GENERAL", input, null, null, errors, notes);
        assertThat(errors.messages()).isEmpty();
        assertThat(value).isNotNull();
        assertThat(value.path("systems").path("cvs").path("state").asText()).isEqualTo("NORMAL");
        assertThat(notes).isEmpty();
    }

    @Test
    void doneRuleAndRoundTripFinish() throws Exception {
        JsonNode input = MAPPER.readTree(PAYLOAD);
        Errors errors = new Errors("x");
        ObjectNode value = SpecialtyValues.normalize("EXAM_GENERAL", input, null, null, errors, new ArrayList<>());
        assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isTrue();
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("specialty", value);
        JsonNode back = MAPPER.readTree(MAPPER.writeValueAsString(wrapper));
        assertThat(back).isEqualTo(wrapper);
        assertThat(SpecialtyRecordSupport.componentValue("gm", null, "EXAM_GENERAL")).isNull();
    }

    @Test
    void anAbnormalSystemWithoutTextIsNotDone() throws Exception {
        JsonNode value = MAPPER.readTree("{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,\"signs\":{},"
                + "\"systems\":{\"cvs\":{\"state\":\"ABNORMAL\"}}}");
        assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isFalse();
    }
}
