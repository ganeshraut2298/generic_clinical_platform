package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sahaj.opd.application.emr.EmrViews;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The EMR response bodies of docs/api/opd.yaml 0.2.0 (SAH-43, SAH-44). Null fields are sent as null, as the contract says. */
public final class EmrResponses {

    private EmrResponses() {
    }

    public record SectionOptions(String source, String master) {
        static SectionOptions from(EmrViews.SectionOptions options) {
            return new SectionOptions(options.source(), options.master());
        }
    }

    public record TemplateOption(String key, String label, String style, @JsonProperty("isNone") boolean isNone) {
        static TemplateOption from(EmrViews.TemplateOption option) {
            return new TemplateOption(option.key(), option.label(), option.style(), option.isNone());
        }
    }

    public record Section(String key, String label, String part, int position, String editorType, String iconKey,
                          SectionOptions options, Integer quickOptionCount, List<TemplateOption> templateOptions,
                          boolean templateRequired, boolean hideable, boolean shown, boolean required,
                          boolean overridden) {
        static Section from(EmrViews.SectionView view) {
            return new Section(view.key(), view.label(), view.part(), view.position(), view.editorType(),
                    view.iconKey(), SectionOptions.from(view.options()), view.quickOptionCount(),
                    view.templateOptions().stream().map(TemplateOption::from).toList(), view.templateRequired(),
                    view.hideable(), view.shown(), view.required(), view.overridden());
        }
    }

    public record TemplateSummary(String templateKey, String name, int version, boolean clinicSpecialty,
                                  int sectionCount, int sectionsOn, int requiredOn, boolean hasOverrides) {
        static TemplateSummary from(EmrViews.TemplateSummary summary) {
            return new TemplateSummary(summary.templateKey(), summary.name(), summary.version(),
                    summary.clinicSpecialty(), summary.sectionCount(), summary.sectionsOn(), summary.requiredOn(),
                    summary.hasOverrides());
        }
    }

    public record Template(String templateKey, String name, int version, boolean clinicSpecialty, long revision,
                           boolean canEdit, int sectionsOn, int requiredOn, List<Section> sections) {
        static Template from(EmrViews.TemplateView view) {
            return new Template(view.templateKey(), view.name(), view.version(), view.clinicSpecialty(),
                    view.revision(), view.canEdit(), view.sectionsOn(), view.requiredOn(),
                    view.sections().stream().map(Section::from).toList());
        }
    }

    public record TemplateChoice(String templateKey, int templateVersion, String templateName, String choiceSource) {
        static TemplateChoice from(EmrViews.TemplateChoice choice) {
            return new TemplateChoice(choice.templateKey(), choice.templateVersion(), choice.templateName(),
                    choice.choiceSource());
        }
    }

    public record DoctorContext(UUID membershipId, String name, String role, String specialty, String degree,
                                String registrationNo, List<String> missing, TemplateChoice template) {
        static DoctorContext from(EmrViews.DoctorContext context) {
            return new DoctorContext(context.membershipId(), context.name(), context.role(), context.specialty(),
                    context.degree(), context.registrationNo(), context.missing(),
                    TemplateChoice.from(context.template()));
        }
    }

    public record MasterItem(UUID id, String name, String specialtyHint) {
    }

    public record MasterPage(List<MasterItem> items, long total, int page, int pageSize) {
        static MasterPage from(EmrViews.MasterPage page) {
            return new MasterPage(page.items().stream()
                    .map(item -> new MasterItem(item.id(), item.name(), item.specialtyHint())).toList(),
                    page.total(), page.page(), page.pageSize());
        }
    }

    public record QuickOption(String itemKey, String name, String icd10Code, String form, Boolean favourite,
                              int useCount, Instant lastUsedAt) {
    }

    public record QuickOptionList(String master, List<QuickOption> items) {
        static QuickOptionList from(EmrViews.QuickOptionList list) {
            return new QuickOptionList(list.master(), list.items().stream()
                    .map(item -> new QuickOption(item.itemKey(), item.name(), item.icd10Code(), item.form(),
                            item.favourite(), item.useCount(), item.lastUsedAt()))
                    .toList());
        }
    }

    public record Settings(String sectionPanel, String rightPanelFirst, String defaultPart, long version,
                           boolean canEdit) {
        static Settings from(EmrViews.Settings settings) {
            return new Settings(settings.sectionPanel(), settings.rightPanelFirst(), settings.defaultPart(),
                    settings.version(), settings.canEdit());
        }
    }
}
