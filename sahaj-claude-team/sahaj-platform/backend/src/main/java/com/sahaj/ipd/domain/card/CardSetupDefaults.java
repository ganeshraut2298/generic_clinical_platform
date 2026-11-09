package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAH-37 (DEC-027, ADR-015 D3) the Sahaj standard setup (v1) and the three templates. Fixed Sahaj content, not clinic
 * data. The standard card is the SAH-36 card plus the locked Allergies and Early warning lines: layout Standard, 3 cards
 * per row, every label empty; Billing off; the readings, Last recorded, Diagnosis, IP number, Mobile and Admission type
 * off; Deposit action off.
 */
public final class CardSetupDefaults {

    /** Fields that are off in the standard setup. */
    private static final Set<String> STANDARD_OFF = Set.of("ipNumber", CardCatalogue.MOBILE, "admissionType",
            CardCatalogue.BP, CardCatalogue.PULSE, CardCatalogue.SPO2, CardCatalogue.TEMP, CardCatalogue.RR,
            CardCatalogue.LAST_RECORDED, CardCatalogue.DIAGNOSIS);

    private static final CardSetup STANDARD_SETUP = build();

    /** A template: applied by the editor to its draft (layout, sections and actions; the draft keeps cards per row). */
    public record Template(String key, String name, String description, String layout, List<Section> sections,
                           List<ActionPlacement> actions) {
    }

    private CardSetupDefaults() {
    }

    /** The Sahaj standard setup, v1. Immutable. */
    public static CardSetup standard() {
        return STANDARD_SETUP;
    }

    /** The three templates, each starting from the standard setup. */
    public static List<Template> templates() {
        CardSetup base = STANDARD_SETUP;
        return List.of(
                template("smallClinic", "Small clinic", "Compact card, no billing", CardCatalogue.COMPACT,
                        withSectionOn(base.sections(), CardCatalogue.BILLING, false), base.actions()),
                template("nursingHome", "Nursing home", "Standard card with deposit action", CardCatalogue.STANDARD,
                        base.sections(), actionsOn(base.actions(), Set.of(CardCatalogue.DEPOSIT_ACTION))),
                template("hospital", "Hospital", "Expanded card, billing section, all actions", CardCatalogue.EXPANDED,
                        withFieldsOn(withSectionOn(base.sections(), CardCatalogue.BILLING, true),
                                Set.of("ipNumber", "admissionType")),
                        actionsOn(base.actions(), Set.copyOf(CardCatalogue.actionKeys()))));
    }

    private static Template template(String key, String name, String description, String layout,
                                     List<Section> sections, List<ActionPlacement> actions) {
        return new Template(key, name, description, layout, sections, actions);
    }

    private static CardSetup build() {
        List<Section> sections = new ArrayList<>();
        for (CardCatalogue.SectionDef section : CardCatalogue.SECTIONS) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (CardCatalogue.FieldDef field : CardCatalogue.FIELDS) {
                if (field.standardSection().equals(section.key())) {
                    fields.add(new FieldPlacement(field.key(), !STANDARD_OFF.contains(field.key()), ""));
                }
            }
            sections.add(new Section(section.key(), !CardCatalogue.BILLING.equals(section.key()),
                    List.copyOf(fields)));
        }
        List<ActionPlacement> actions = new ArrayList<>();
        for (CardCatalogue.ActionDef action : CardCatalogue.ACTIONS) {
            actions.add(new ActionPlacement(action.key(), !CardCatalogue.DEPOSIT_ACTION.equals(action.key())));
        }
        return new CardSetup(CardCatalogue.STANDARD, 3, List.copyOf(sections), List.copyOf(actions));
    }

    private static List<Section> withSectionOn(List<Section> sections, String key, boolean on) {
        List<Section> result = new ArrayList<>();
        for (Section section : sections) {
            result.add(section.key().equals(key) ? new Section(section.key(), on, section.fields()) : section);
        }
        return List.copyOf(result);
    }

    private static List<Section> withFieldsOn(List<Section> sections, Set<String> fieldKeys) {
        List<Section> result = new ArrayList<>();
        for (Section section : sections) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement field : section.fields()) {
                fields.add(fieldKeys.contains(field.key()) ? new FieldPlacement(field.key(), true, field.label())
                        : field);
            }
            result.add(new Section(section.key(), section.on(), List.copyOf(fields)));
        }
        return List.copyOf(result);
    }

    private static List<ActionPlacement> actionsOn(List<ActionPlacement> actions, Set<String> keys) {
        List<ActionPlacement> result = new ArrayList<>();
        for (ActionPlacement action : actions) {
            result.add(keys.contains(action.key()) ? new ActionPlacement(action.key(), true) : action);
        }
        return List.copyOf(result);
    }
}
