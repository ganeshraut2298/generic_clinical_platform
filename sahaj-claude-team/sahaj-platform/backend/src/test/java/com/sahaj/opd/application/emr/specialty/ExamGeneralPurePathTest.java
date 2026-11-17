package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.specialty.Sp.Errors;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * DEC-054: the pure code paths of one general examination save (shape check, Done rule, JSON round trip, the section
 * and profile lookups) must finish at once. The payload is the one the live journey script sends.
 */
class ExamGeneralPurePathTest {

    /** Preemptive: a loop that never returns is stopped and fails the test instead of hanging the run. */
    private static void within5s(Executable body) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), body);
    }

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
        within5s(() -> {
            JsonNode input = MAPPER.readTree(PAYLOAD);
            Errors errors = new Errors("sections[0].values.specialty");
            List<Note> notes = new ArrayList<>();
            ObjectNode value = SpecialtyValues.normalize("EXAM_GENERAL", input, null, null, errors, notes);
            assertThat(errors.messages()).isEmpty();
            assertThat(value).isNotNull();
            assertThat(value.path("systems").path("cvs").path("state").asText()).isEqualTo("NORMAL");
            assertThat(notes).isEmpty();
        });
    }

    @Test
    void doneRuleAndRoundTripFinish() throws Exception {
        within5s(() -> {
            JsonNode input = MAPPER.readTree(PAYLOAD);
            Errors errors = new Errors("x");
            ObjectNode value = SpecialtyValues.normalize("EXAM_GENERAL", input, null, null, errors, new ArrayList<>());
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isTrue();
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set("specialty", value);
            JsonNode back = MAPPER.readTree(MAPPER.writeValueAsString(wrapper));
            assertThat(back).isEqualTo(wrapper);
            assertThat(SpecialtyRecordSupport.componentValue("gm", com.sahaj.opd.application.emr.EmrRecordDocument.empty(MAPPER), "EXAM_GENERAL")).isNull();
        });
    }

    /** The root cause: a loop that skipped next() once a sign was marked never ended (two or more signs hung). */
    @Test
    void severalSignsAreReadWithoutLooping() throws Exception {
        within5s(() -> {
            JsonNode value = MAPPER.readTree("{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,"
                    + "\"signs\":{\"pallor\":\"ABSENT\",\"icterus\":\"PRESENT\",\"cyanosis\":null,\"oedema\":\"ABSENT\"},"
                    + "\"systems\":{}}");
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isTrue();
            JsonNode onlyNulls = MAPPER.readTree("{\"signs\":{\"pallor\":null,\"icterus\":null},\"systems\":{}}");
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", onlyNulls, doneCtx())).isFalse();
        });
    }

    @Test
    void anAbnormalSystemWithoutTextIsNotDone() throws Exception {
        within5s(() -> {
            JsonNode value = MAPPER.readTree("{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,\"signs\":{},"
                    + "\"systems\":{\"cvs\":{\"state\":\"ABNORMAL\"}}}");
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isFalse();
        });
    }

    /** Five entries per object, as many signs and systems as the form can carry: the loops must visit all and return. */
    @Test
    void fiveSignsAndFiveSystemsAreReadWithoutLooping() throws Exception {
        within5s(() -> {
            JsonNode value = MAPPER.readTree("{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,"
                    + "\"signs\":{\"a\":\"ABSENT\",\"b\":\"PRESENT\",\"c\":\"ABSENT\",\"d\":null,\"e\":\"ABSENT\"},"
                    + "\"systems\":{\"cvs\":{\"state\":\"NORMAL\"},\"rs\":{\"state\":\"NORMAL\"},"
                    + "\"pa\":{\"state\":\"NORMAL\"},\"cns\":{\"state\":\"NORMAL\"},"
                    + "\"ent\":{\"state\":\"ABNORMAL\"}}}");
            // the last system is abnormal without text: all five must be read to find it
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", value, doneCtx())).isFalse();
            JsonNode fine = MAPPER.readTree("{\"signs\":{},\"systems\":{\"cvs\":{\"state\":\"NORMAL\"},"
                    + "\"rs\":{\"state\":\"NORMAL\"},\"pa\":{\"state\":\"NORMAL\"},\"cns\":{\"state\":\"NORMAL\"},"
                    + "\"ent\":{\"state\":\"ABNORMAL\",\"text\":\"Congested\"}}}");
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", fine, doneCtx())).isTrue();
            JsonNode nullsOnly = MAPPER.readTree("{\"signs\":{\"a\":null,\"b\":null,\"c\":null,\"d\":null,"
                    + "\"e\":null},\"systems\":{}}");
            assertThat(SpecialtyDone.isDone("EXAM_GENERAL", nullsOnly, doneCtx())).isFalse();
        });
    }
}
