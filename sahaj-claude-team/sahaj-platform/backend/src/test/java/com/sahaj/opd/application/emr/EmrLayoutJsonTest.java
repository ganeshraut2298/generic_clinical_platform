package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmrLayoutJsonTest {

    private final EmrLayoutJson json = EmrTestData.JSON;

    @Test
    void aLayoutSurvivesAWriteAndARead() {
        FieldDefinition severity = new FieldDefinition("severity", "Severity", "DROPDOWN", EmrValueKind.CHOICE,
                "Mild / Moderate", true, "FIELD", null, false,
                List.of(new FieldOption("mild", "Mild", false), new FieldOption("none", "None", true)), List.of(),
                null, null);
        LayoutSection allergy = EmrTestData.section("allergy", "COMMON", "ASSESSMENT", true, true, true, severity);
        LayoutSection rx = EmrTestData.section("rx", "COMMON", "TREATMENT", false, false, false,
                EmrTestData.table("meds", "drug", "dose"));

        List<LayoutSection> back = json.readLayout(json.writeLayout(List.of(allergy, rx)));

        assertThat(back).hasSize(2);
        assertThat(back.get(0).hideLocked()).isTrue();
        assertThat(back.get(0).hideLockReason()).isEqualTo("Allergies are always shown for patient safety.");
        assertThat(json.writeLayout(List.of(allergy))).doesNotContain("hideLockReason").doesNotContain("valueKind");
        assertThat(back.get(0).fields().get(0).options()).extracting(FieldOption::isNone).containsExactly(false, true);
        assertThat(back.get(0).fields().get(0).valueKind()).isEqualTo(EmrValueKind.CHOICE);
        assertThat(back.get(1).shown()).isFalse();
        assertThat(back.get(1).part()).isEqualTo("TREATMENT");
        assertThat(back.get(1).fields().get(0).columns()).extracting(c -> c.key()).containsExactly("drug", "dose");
        assertThat(back).extracting(LayoutSection::position).containsExactly(1, 1);
    }

    @Test
    void aMissingOptionalPropertyGetsADefaultAndAnUnknownTypeBecomesLater() {
        String stored = "{\"schemaVersion\":1,\"sections\":[{\"key\":\"x\",\"name\":\"X\",\"fields\":["
                + "{\"key\":\"f\",\"label\":\"F\",\"type\":\"HOLOGRAM\"}]}]}";

        LayoutSection section = json.readLayout(stored).get(0);

        assertThat(section.part()).isEqualTo("ASSESSMENT");
        assertThat(section.shown()).isTrue();
        assertThat(section.whoCanFill()).isEmpty();
        assertThat(section.fields().get(0).valueKind()).isEqualTo(EmrValueKind.LATER);
        assertThat(section.fields().get(0).options()).isEmpty();
    }

    @Test
    void settingsKeepTheirOrderAndTheRolesComeBackInFixedOrder() {
        List<SectionSettings> settings = List.of(
                new SectionSettings("b", "TREATMENT", true, false, true, false, true, List.of("DOCTOR", "NURSE")),
                new SectionSettings("a", "ASSESSMENT", false, false, false, false, false, List.of("RECEPTION")));

        List<SectionSettings> back = json.readSettings(json.writeSettings(settings));

        assertThat(back).isEqualTo(settings);
        String reversed = "{\"sections\":[{\"key\":\"a\",\"whoCanFill\":[\"RECEPTION\",\"DOCTOR\"]}]}";
        assertThat(json.readSettings(reversed).get(0).whoCanFill()).containsExactly("DOCTOR", "RECEPTION");
    }

    @Test
    void aDamagedDocumentIsAnErrorNotAnEmptyLayout() {
        assertThatThrownBy(() -> json.readLayout("{\"schemaVersion\":1}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> json.readLayout("not json")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> json.readSettings("{}")).isInstanceOf(IllegalStateException.class);
    }
}
