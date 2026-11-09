package com.sahaj.ipd.domain.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import com.sahaj.ipd.domain.card.VitalMarks.Mark;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * SAH-37: the catalogue (25 fields, 7 sections, 5 actions), the access rule, the standard setup and the templates
 * (DEC-027), the resolved setup and the vital marks.
 */
class CardCatalogueAndRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- catalogue ----

    @Test
    void theCatalogueListsExactlyTheFieldsWithDataInPhaseOne() {
        assertThat(CardCatalogue.fieldKeys()).containsExactly("name", "uhid", "ageSex", "bed", "ipNumber", "mobile",
                "alerts", "admittedOn", "lengthOfStay", "ward", "room", "admissionType", "doctor", "bp", "pulse", "spo2",
                "temp", "rr", "ews", "lastRecorded", "diagnosis", "allergies", "runningBill", "deposit", "payer");
        assertThat(CardCatalogue.sectionKeys()).containsExactly("header", "status", "stay", "care", "vitals",
                "clinical", "billing");
        assertThat(CardCatalogue.actionKeys()).containsExactly("nursing", "emr", "transfer", "discharge", "deposit");
        assertThat(CardCatalogue.fieldKeys()).doesNotContain("stage", "nurse", "lastNote");
    }

    @Test
    void lockedItemsAreTheFiveFieldsAndTheFourSections() {
        assertThat(CardCatalogue.FIELDS.stream().filter(CardCatalogue.FieldDef::safety)
                .map(CardCatalogue.FieldDef::key).toList()).containsExactly("uhid", "alerts", "ews", "allergies");
        assertThat(CardCatalogue.FIELDS.stream().filter(CardCatalogue.FieldDef::required)
                .map(CardCatalogue.FieldDef::key).toList()).containsExactly("name");
        assertThat(CardCatalogue.SECTIONS.stream().filter(CardCatalogue.SectionDef::locked)
                .map(CardCatalogue.SectionDef::key).toList()).containsExactly("header");
        assertThat(CardCatalogue.SECTIONS.stream().filter(CardCatalogue.SectionDef::safety)
                .map(CardCatalogue.SectionDef::key).toList()).containsExactly("status", "vitals", "clinical");
    }

    @Test
    void everyFieldHasTheDataGroupOfTheDesignNote() {
        assertThat(CardCatalogue.field("mobile").get().group()).isEqualTo("contact");
        assertThat(CardCatalogue.field("diagnosis").get().group()).isEqualTo("clinical");
        assertThat(CardCatalogue.field("allergies").get().group()).isEqualTo("clinical");
        for (String key : List.of("bp", "pulse", "spo2", "temp", "rr", "ews", "lastRecorded")) {
            assertThat(CardCatalogue.field(key).get().group()).as(key).isEqualTo("vitals");
        }
        for (String key : List.of("runningBill", "deposit", "payer")) {
            assertThat(CardCatalogue.field(key).get().group()).as(key).isEqualTo("billing");
        }
        assertThat(CardCatalogue.field("alerts").get().group()).isEqualTo("patient");
        assertThat(CardCatalogue.field("name").get().allowedSections()).containsExactly("header");
        assertThat(CardCatalogue.field("ward").get().allowedSections()).hasSize(7);
    }

    @Test
    void theCatalogueIsSerialisedWithTheContractNamesAndNoInternalColumn() throws Exception {
        JsonNode json = JSON.valueToTree(CardCatalogue.catalogue());

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("sections", "fields", "groups", "actions",
                "viewAsRoles", "labelMaxLength");
        assertThat(json.get("labelMaxLength").asInt()).isEqualTo(40);
        JsonNode bp = json.get("fields").get(13);
        assertThat(bp.get("key").asText()).isEqualTo("bp");
        assertThat(bp.get("unit").asText()).isEqualTo("mmHg");
        assertThat(bp.has("standardSection")).isFalse();
        assertThat(json.get("fields").get(0).has("unit")).as("no unit: absent").isFalse();
        assertThat(json.get("viewAsRoles")).extracting(JsonNode::asText)
                .containsExactly("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");
        JsonNode deposit = json.get("actions").get(4);
        assertThat(deposit.get("usableBy")).extracting(JsonNode::asText).containsExactly("OWNER", "FRONT_DESK");
        assertThat(deposit.get("allowedInReadOnly").asBoolean()).isFalse();
        assertThat(deposit.get("icon").asText()).isEqualTo("deposit");
    }

    // ---- access rule ----

    @Test
    void theGroupsAndTheWhoLineFollowTheDesignNotes() {
        assertThat(CardAccessRule.groupRoles("patient")).containsExactly("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");
        assertThat(CardAccessRule.groupRoles("contact")).containsExactly("OWNER", "FRONT_DESK");
        assertThat(CardAccessRule.groupRoles("clinical")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(CardAccessRule.groupRoles("vitals")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(CardAccessRule.groupRoles("billing")).containsExactly("OWNER", "FRONT_DESK");
        assertThat(CardAccessRule.groupRoles("nonsense")).isEmpty();
        assertThat(CardCatalogue.action("nursing").get().usableBy()).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(CardCatalogue.action("emr").get().usableBy()).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(CardCatalogue.action("transfer").get().usableBy()).containsExactly("OWNER", "NURSE", "FRONT_DESK");
        assertThat(CardCatalogue.action("discharge").get().usableBy()).containsExactly("OWNER", "DOCTOR");
        assertThat(CardCatalogue.action("deposit").get().usableBy()).containsExactly("OWNER", "FRONT_DESK");
    }

    @Test
    void visibleGroupsNeedTheTokenRoleAndTheMembershipRoleBoth() {
        assertThat(CardAccessRule.visibleGroups("OWNER", "OWNER"))
                .containsExactly("patient", "contact", "clinical", "vitals", "billing");
        assertThat(CardAccessRule.visibleGroups("FRONT_DESK", "FRONT_DESK"))
                .containsExactly("patient", "contact", "billing");
        assertThat(CardAccessRule.visibleGroups("NURSE", "NURSE")).containsExactly("patient", "clinical", "vitals");
        assertThat(CardAccessRule.visibleGroups("DOCTOR", "DOCTOR")).containsExactly("patient", "clinical", "vitals");
        assertThat(CardAccessRule.visibleGroups("NURSE", "FRONT_DESK")).containsExactly("patient");
        assertThat(CardAccessRule.visibleGroups("BILLING", "BILLING")).isEmpty();
        assertThat(CardAccessRule.visibleGroups(null, "OWNER")).isEmpty();
    }

    @Test
    void theDepositActionIsForOwnerAndFrontDeskAndNotInReadOnly() {
        assertThat(CardAccessRule.abilities("OWNER", "OWNER", "on").deposit()).isTrue();
        assertThat(CardAccessRule.abilities("OWNER", "OWNER", "trial").deposit()).isTrue();
        assertThat(CardAccessRule.abilities("FRONT_DESK", "FRONT_DESK", "on").deposit()).isTrue();
        assertThat(CardAccessRule.abilities("OWNER", "OWNER", "readonly").deposit()).isFalse();
        assertThat(CardAccessRule.abilities("NURSE", "NURSE", "on").deposit()).isFalse();
        assertThat(CardAccessRule.abilities("DOCTOR", "DOCTOR", "on").deposit()).isFalse();
        assertThat(CardAccessRule.abilities("OWNER", "FRONT_DESK", "on").deposit()).isTrue();
        assertThat(CardAccessRule.abilities("OWNER", "NURSE", "on").deposit()).isFalse();
    }

    // ---- standard setup and templates ----

    @Test
    void theStandardSetupIsTheSah36CardPlusTheLockedLines() {
        CardSetup standard = CardSetupDefaults.standard();

        assertThat(standard.layout()).isEqualTo("standard");
        assertThat(standard.cardsPerRow()).isEqualTo(3);
        assertThat(standard.sections().stream().map(Section::key).toList())
                .containsExactlyElementsOf(CardCatalogue.sectionKeys());
        assertThat(standard.sections().stream().filter(s -> !s.on()).map(Section::key).toList())
                .containsExactly("billing");
        List<String> off = standard.sections().stream().flatMap(s -> s.fields().stream()).filter(f -> !f.on())
                .map(FieldPlacement::key).collect(Collectors.toList());
        assertThat(off).containsExactlyInAnyOrder("ipNumber", "mobile", "admissionType", "bp", "pulse", "spo2",
                "temp", "rr", "lastRecorded", "diagnosis");
        assertThat(standard.sections().stream().flatMap(s -> s.fields().stream()).map(FieldPlacement::label))
                .allMatch(String::isEmpty);
        assertThat(standard.sections().stream().mapToInt(s -> s.fields().size()).sum()).isEqualTo(25);
        assertThat(standard.actions().stream().filter(a -> a.on()).map(a -> a.key()).toList())
                .containsExactly("nursing", "emr", "transfer", "discharge");
        // the locked lines are on in the default card
        assertThat(standard.sections().stream().flatMap(s -> s.fields().stream())
                .filter(f -> List.of("uhid", "alerts", "ews", "allergies", "name").contains(f.key())))
                .allMatch(FieldPlacement::on);
    }

    @Test
    void theTemplatesChangeOnlyWhatTheDesignNoteSays() {
        List<CardSetupDefaults.Template> templates = CardSetupDefaults.templates();
        assertThat(templates).extracting(CardSetupDefaults.Template::key)
                .containsExactly("smallClinic", "nursingHome", "hospital");
        assertThat(templates).extracting(CardSetupDefaults.Template::name)
                .containsExactly("Small clinic", "Nursing home", "Hospital");
        assertThat(templates).extracting(CardSetupDefaults.Template::description).containsExactly(
                "Compact card, no billing", "Standard card with deposit action",
                "Expanded card, billing section, all actions");
        CardSetup standard = CardSetupDefaults.standard();

        CardSetupDefaults.Template small = templates.get(0);
        assertThat(small.layout()).isEqualTo("compact");
        assertThat(small.sections()).isEqualTo(standard.sections());
        assertThat(small.actions()).isEqualTo(standard.actions());

        CardSetupDefaults.Template home = templates.get(1);
        assertThat(home.layout()).isEqualTo("standard");
        assertThat(home.sections()).isEqualTo(standard.sections());
        assertThat(home.actions().stream().filter(a -> a.on()).map(a -> a.key()).toList())
                .containsExactly("nursing", "emr", "transfer", "discharge", "deposit");

        CardSetupDefaults.Template hospital = templates.get(2);
        assertThat(hospital.layout()).isEqualTo("expanded");
        assertThat(hospital.sections().stream().filter(Section::on).map(Section::key).toList())
                .contains("billing");
        assertThat(hospital.actions()).allMatch(a -> a.on());
        List<String> on = hospital.sections().stream().flatMap(s -> s.fields().stream()).filter(f -> f.on())
                .map(FieldPlacement::key).toList();
        assertThat(on).contains("ipNumber", "admissionType").doesNotContain("bp", "diagnosis", "mobile");
    }

    // ---- resolved setup ----

    @Test
    void theResolvedStandardSetupHoldsOnlyWhatIsOnWithResolvedLabels() {
        ResolvedCardSetup resolved = ResolvedCardSetup.of(1, true, false, CardSetupDefaults.standard());

        assertThat(resolved.sections().stream().map(ResolvedCardSetup.ResolvedSection::key).toList())
                .containsExactly("header", "status", "stay", "care", "vitals", "clinical");
        assertThat(resolved.shownFields()).contains("name", "uhid", "alerts", "ews", "allergies", "doctor")
                .doesNotContain("diagnosis", "bp", "mobile", "runningBill");
        assertThat(resolved.actions()).containsExactly("nursing", "emr", "transfer", "discharge");
        ResolvedCardSetup.ResolvedField ews = resolved.sections().get(4).fields().get(0);
        assertThat(ews.key()).isEqualTo("ews");
        assertThat(ews.label()).isEqualTo("Early warning");
        assertThat(ews.group()).isEqualTo("vitals");
        assertThat(ews.safety()).isTrue();
    }

    @Test
    void anOwnersLabelIsUsedForAnOrdinaryFieldAndNeverForALockedOne() {
        CardSetup typed = CardSetupValidatorTest.field(CardSetupDefaults.standard(), "ward",
                f -> new FieldPlacement(f.key(), true, "Wing"));
        typed = CardSetupValidatorTest.field(typed, "allergies", f -> new FieldPlacement(f.key(), true, "Pets"));

        ResolvedCardSetup resolved = ResolvedCardSetup.of(2, false, false, typed);

        List<ResolvedCardSetup.ResolvedField> all = resolved.sections().stream().flatMap(s -> s.fields().stream())
                .toList();
        assertThat(all).filteredOn(f -> f.key().equals("ward")).extracting(ResolvedCardSetup.ResolvedField::label)
                .containsExactly("Wing");
        assertThat(all).filteredOn(f -> f.key().equals("allergies"))
                .extracting(ResolvedCardSetup.ResolvedField::label).containsExactly("Allergies");
    }

    @Test
    void aFieldOfASwitchedOffSectionIsNotResolved() {
        CardSetup billingOn = CardSetupValidatorTest.section(CardSetupDefaults.standard(), "billing",
                s -> new Section(s.key(), true, s.fields()));
        assertThat(ResolvedCardSetup.of(2, false, false, billingOn).shownFields())
                .contains("runningBill", "deposit", "payer");
        assertThat(ResolvedCardSetup.of(1, true, false, CardSetupDefaults.standard()).shownFields())
                .doesNotContain("runningBill", "deposit", "payer");
    }

    // ---- vital marks ----

    @Test
    void aMarkIsTheSah30ParameterPointsAndNothingElse() {
        assertThat(VitalMarks.ofPoints(0)).isNull();
        assertThat(VitalMarks.ofPoints(1)).isEqualTo(Mark.CHECK);
        assertThat(VitalMarks.ofPoints(2)).isEqualTo(Mark.CHECK);
        assertThat(VitalMarks.ofPoints(3)).isEqualTo(Mark.CRITICAL);
    }

    @Test
    void marksAtTheBandEdgesFollowEarlyWarningScore() {
        // SpO2: 91 -> 3 critical, 92-93 -> 2 check, 94-95 -> 1 check, 96 -> none (the mockup's own limit would differ)
        assertThat(marks(120, 72, 91, "37.0", 16).spo2()).isEqualTo(Mark.CRITICAL);
        assertThat(marks(120, 72, 92, "37.0", 16).spo2()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 95, "37.0", 16).spo2()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 96, "37.0", 16).spo2()).isNull();
        // systolic: 90 -> 3, 91 -> 2, 110 -> 1, 111 -> 0, 220 -> 3
        assertThat(marks(90, 72, 98, "37.0", 16).bp()).isEqualTo(Mark.CRITICAL);
        assertThat(marks(91, 72, 98, "37.0", 16).bp()).isEqualTo(Mark.CHECK);
        assertThat(marks(110, 72, 98, "37.0", 16).bp()).isEqualTo(Mark.CHECK);
        assertThat(marks(111, 72, 98, "37.0", 16).bp()).isNull();
        assertThat(marks(220, 72, 98, "37.0", 16).bp()).isEqualTo(Mark.CRITICAL);
        // pulse: 40 -> 3, 41-50 -> 1, 51-90 -> 0, 91-110 -> 1, 111-130 -> 2, 131 -> 3
        assertThat(marks(120, 40, 98, "37.0", 16).pulse()).isEqualTo(Mark.CRITICAL);
        assertThat(marks(120, 50, 98, "37.0", 16).pulse()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 51, 98, "37.0", 16).pulse()).isNull();
        assertThat(marks(120, 91, 98, "37.0", 16).pulse()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 131, 98, "37.0", 16).pulse()).isEqualTo(Mark.CRITICAL);
        // temperature in tenths: 35.0 -> 3, 35.1 -> 1, 36.1 -> 0, 38.1 -> 1, 39.1 -> 2
        assertThat(marks(120, 72, 98, "35.0", 16).temp()).isEqualTo(Mark.CRITICAL);
        assertThat(marks(120, 72, 98, "35.1", 16).temp()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 98, "36.1", 16).temp()).isNull();
        assertThat(marks(120, 72, 98, "38.1", 16).temp()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 98, "39.1", 16).temp()).isEqualTo(Mark.CHECK);
        // respiratory rate: 8 -> 3, 9-11 -> 1, 12-20 -> 0, 21-24 -> 2, 25 -> 3
        assertThat(marks(120, 72, 98, "37.0", 8).rr()).isEqualTo(Mark.CRITICAL);
        assertThat(marks(120, 72, 98, "37.0", 11).rr()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 98, "37.0", 12).rr()).isNull();
        assertThat(marks(120, 72, 98, "37.0", 24).rr()).isEqualTo(Mark.CHECK);
        assertThat(marks(120, 72, 98, "37.0", 25).rr()).isEqualTo(Mark.CRITICAL);
    }

    @Test
    void normalReadingsHaveNoMark() {
        VitalMarks.Marks marks = marks(120, 72, 98, "36.8", 16);
        assertThat(marks).isEqualTo(new VitalMarks.Marks(null, null, null, null, null));
    }

    private static VitalMarks.Marks marks(int systolic, int pulse, int spo2, String temp, int rr) {
        return VitalMarks.of(systolic, pulse, spo2, new BigDecimal(temp), rr);
    }
}
