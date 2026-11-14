package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrCompletion.Progress;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmrCompletionTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");

    private final List<LayoutSection> layout = List.of(
            EmrTestData.section("complaints", "COMMON", "ASSESSMENT", true, true, false,
                    EmrTestData.field("complaint", "SEARCH_LIST", true), EmrTestData.field("note", "TEXT", false)),
            EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false,
                    EmrTestData.field("pulse", "NUMBER", false), EmrTestData.field("temp", "NUMBER", false)),
            EmrTestData.section("advice", "COMMON", "TREATMENT", true, true, false,
                    EmrTestData.field("trend", "CHART", true), EmrTestData.field("text", "TEXT", false)),
            EmrTestData.section("diagnosis", "COMMON", "TREATMENT", true, true, false,
                    EmrTestData.field("dx", "SEARCH_LIST", true)));

    private EmrRecordDocument document() {
        return EmrRecordDocument.empty(EmrTestData.MAPPER);
    }

    private ObjectNode values(String json) {
        try {
            return (ObjectNode) EmrTestData.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void nothingEnteredLeavesRequiredSectionsRequiredAndOptionalOnesOptional() {
        Progress progress = EmrCompletion.compute(layout, document());

        assertThat(progress.sections()).extracting(EmrCompletion.SectionProgress::status)
                .containsExactly("REQUIRED", "OPTIONAL", "REQUIRED", "REQUIRED");
        assertThat(progress.stillNeeded()).containsExactly("complaints", "advice", "diagnosis");
        assertThat(progress.readyToComplete()).isFalse();
        assertThat(progress.parts().get(0)).satisfies(p -> {
            assertThat(p.part()).isEqualTo("ASSESSMENT");
            assertThat(p.requiredDone()).isZero();
            assertThat(p.requiredTotal()).isEqualTo(1);
            assertThat(p.complete()).isFalse();
            assertThat(p.sectionsDone()).isZero();
            assertThat(p.sectionsTotal()).isEqualTo(2);
        });
    }

    @Test
    void aSectionWithRequiredFieldsIsDoneOnlyWhenEveryRequiredFillableFieldIsFilled() {
        EmrRecordDocument doc = document();
        doc.put("complaints", values("{\"note\":\"only a note\"}"), false, NOW, DOCTOR);
        assertThat(EmrCompletion.compute(layout, doc).sections().get(0).done()).isFalse();

        doc.put("complaints", values("{\"complaint\":[{\"itemKey\":null,\"text\":\"Cough\",\"newToList\":true}]}"),
                false, NOW, DOCTOR);
        Progress progress = EmrCompletion.compute(layout, doc);

        assertThat(progress.sections().get(0).status()).isEqualTo("DONE");
        assertThat(progress.parts().get(0).requiredDone()).isEqualTo(1);
        assertThat(progress.parts().get(0).complete()).isTrue();
        assertThat(progress.stillNeeded()).doesNotContain("complaints");
    }

    @Test
    void withNoRequiredFieldOneFilledFieldMakesTheSectionDone() {
        EmrRecordDocument doc = document();
        doc.put("vitals", values("{\"pulse\":72}"), false, NOW, DOCTOR);

        Progress progress = EmrCompletion.compute(layout, doc);

        assertThat(progress.sections().get(1).status()).isEqualTo("DONE");
        assertThat(progress.parts().get(0).sectionsDone()).isEqualTo(1);
    }

    @Test
    void aDisplayOnlyRequiredFieldIsIgnoredForTheDoneRule() {
        EmrRecordDocument doc = document();
        doc.put("advice", values("{\"text\":\"Rest\"}"), false, NOW, DOCTOR);

        assertThat(EmrCompletion.compute(layout, doc).sections().get(2).done()).isTrue();
    }

    @Test
    void copiedValuesAreNeverDoneUntilConfirmed() {
        EmrRecordDocument doc = document();
        doc.putCopied("vitals", values("{\"pulse\":80}"), UUID.randomUUID(), LocalDate.of(2026, 10, 1), NOW, DOCTOR);

        Progress pending = EmrCompletion.compute(layout, doc);
        assertThat(pending.sections().get(1).done()).isFalse();
        assertThat(pending.sections().get(1).copyPending()).isTrue();

        doc.put("vitals", values("{\"pulse\":80}"), true, NOW, DOCTOR);
        Progress confirmed = EmrCompletion.compute(layout, doc);
        assertThat(confirmed.sections().get(1).done()).isTrue();
        assertThat(confirmed.sections().get(1).copyPending()).isFalse();
    }

    @Test
    void readyToCompleteWhenEveryRequiredSectionIsDone() {
        EmrRecordDocument doc = document();
        doc.put("complaints", values("{\"complaint\":[{\"itemKey\":\"k\",\"text\":\"Fever\",\"newToList\":false}]}"),
                false, NOW, DOCTOR);
        doc.put("advice", values("{\"text\":\"Rest\"}"), false, NOW, DOCTOR);
        doc.put("diagnosis", values("{\"dx\":[{\"itemKey\":\"J06.9\",\"text\":\"URI\",\"newToList\":false}]}"), false,
                NOW, DOCTOR);

        Progress progress = EmrCompletion.compute(layout, doc);

        assertThat(progress.readyToComplete()).isTrue();
        assertThat(progress.stillNeeded()).isEmpty();
        assertThat(progress.parts()).allMatch(EmrCompletion.PartProgress::complete);
    }

    @Test
    void aPartWithNoRequiredSectionIsCompleteAndAHiddenSectionIsNotCounted() {
        List<LayoutSection> optionalOnly = List.of(EmrTestData.section("vitals", true, false,
                EmrTestData.field("pulse", "NUMBER", false)));

        Progress progress = EmrCompletion.compute(optionalOnly, document());

        assertThat(progress.parts().get(0).requiredTotal()).isZero();
        assertThat(progress.parts().get(0).complete()).isTrue();
        assertThat(progress.parts().get(1).sectionsTotal()).isZero();
        assertThat(progress.readyToComplete()).isTrue();
    }
}
