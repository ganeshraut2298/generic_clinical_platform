package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.application.emr.EmrSetupRepository.OverrideRow;
import com.sahaj.opd.application.emr.EmrTemplateComposer.Effective;
import com.sahaj.opd.application.emr.EmrTemplateRepository.OptionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrViews.SectionView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-019 D5: the effective section is the template default, then the clinic's override. */
class EmrTemplateComposerTest {

    private static final SectionRow COMPLAINTS = new SectionRow("chief_complaints", 1, "Chief complaints",
            "ASSESSMENT", true, true, "COMPLAINTS", null, "MASTER", "complaints");
    private static final SectionRow ALLERGIES = new SectionRow("history_allergies", 2, "History & allergies",
            "ASSESSMENT", false, false, "CHIP_LIST", null, "TEMPLATE", null);
    private static final SectionRow VITALS = new SectionRow("vitals", 3, "Vitals", "ASSESSMENT", true, true,
            "VITALS_STEPPERS", null, "NONE", null);
    private static final SectionRow SERVICES = new SectionRow("services", 4, "Procedures & services", "TREATMENT",
            false, true, "SERVICES", null, "TARIFF", null);
    private static final List<SectionRow> SECTIONS = List.of(COMPLAINTS, ALLERGIES, VITALS, SERVICES);
    private static final List<OptionRow> OPTIONS = List.of(
            new OptionRow("history_allergies", "none", 1, "None", "NORMAL", true),
            new OptionRow("history_allergies", "penicillin", 2, "Penicillin allergy", "ALERT", false));

    private static List<SectionView> views(List<OverrideRow> overrides) {
        return EmrTemplateComposer.views(SECTIONS, OPTIONS, EmrTemplateComposer.byKey(overrides),
                Map.of(EmrMaster.COMPLAINTS, 59L), 7);
    }

    @Test
    void withNoOverrideEverythingIsShownAndRequiredAsTheTemplateSays() {
        List<SectionView> views = views(List.of());

        assertThat(views).extracting(SectionView::shown).containsOnly(true);
        assertThat(views).extracting(SectionView::required).containsExactly(true, false, true, false);
        assertThat(views).extracting(SectionView::overridden).containsOnly(false);
        assertThat(EmrTemplateComposer.sectionsOn(views)).isEqualTo(4);
        assertThat(EmrTemplateComposer.requiredOn(views)).isEqualTo(2);
    }

    @Test
    void anOverrideChangesShowAndRequiredForThatSectionOnly() {
        List<SectionView> views = views(List.of(
                new OverrideRow("vitals", false, false), new OverrideRow("services", true, true)));

        assertThat(views.get(2).shown()).isFalse();
        assertThat(views.get(2).required()).isFalse();
        assertThat(views.get(2).templateRequired()).isTrue();
        assertThat(views.get(2).overridden()).isTrue();
        assertThat(views.get(3).required()).isTrue();
        assertThat(views.get(0).shown()).isTrue();
        assertThat(EmrTemplateComposer.sectionsOn(views)).isEqualTo(3);
        assertThat(EmrTemplateComposer.requiredOn(views)).isEqualTo(2);
    }

    @Test
    void aHiddenSectionIsNeverRequiredEvenIfAStoredRowSaysSo() {
        Effective effective = EmrTemplateComposer.effective(VITALS, new OverrideRow("vitals", false, true));
        assertThat(effective).isEqualTo(new Effective(false, false));
    }

    @Test
    void aSectionThatCannotBeHiddenStaysShown() {
        Effective effective = EmrTemplateComposer.effective(ALLERGIES, new OverrideRow("history_allergies", false, false));
        assertThat(effective.shown()).isTrue();
    }

    @Test
    void anOverrideForAKeyNotInTheTemplateIsIgnored() {
        List<SectionView> views = views(List.of(new OverrideRow("removed_section", false, false)));

        assertThat(views).extracting(SectionView::overridden).containsOnly(false);
        assertThat(EmrTemplateComposer.sectionsOn(views)).isEqualTo(4);
    }

    @Test
    void quickOptionCountsFollowTheOptionsSource() {
        List<SectionView> views = views(List.of());

        assertThat(views.get(0).quickOptionCount()).isEqualTo(59);
        assertThat(views.get(1).quickOptionCount()).isEqualTo(2);
        assertThat(views.get(2).quickOptionCount()).isNull();
        assertThat(views.get(3).quickOptionCount()).isEqualTo(7);
        assertThat(views.get(0).options().master()).isEqualTo("complaints");
        assertThat(views.get(1).options().master()).isNull();
    }

    @Test
    void onlyTemplateSectionsCarryTheirChips() {
        List<SectionView> views = views(List.of());

        assertThat(views.get(1).templateOptions()).hasSize(2);
        assertThat(views.get(1).templateOptions().get(0).isNone()).isTrue();
        assertThat(views.get(1).templateOptions().get(1).style()).isEqualTo("ALERT");
        assertThat(views.get(0).templateOptions()).isEmpty();
    }
}
