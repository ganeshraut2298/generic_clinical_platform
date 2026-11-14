package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.MedicineSafetyCheck.FindingCode;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SAH-31 the one safety-check rule class (ADR-009 D2): allergy keywords and classes, the allergy chip, the duplicate
 * rule, the three interactions in both directions, the fixed order of findings and the check token.
 */
class MedicineSafetyCheckTest {

    private static final String NO_ALLERGY = "None known";
    private final MedicineSafetyCheck rule = new MedicineSafetyCheck(SeedCatalogue.catalogue());

    private OrderCheck check(String drugKey, String allergy, String... live) {
        return rule.check(SeedCatalogue.drug(drugKey), allergy, List.of(live));
    }

    // ---- the allergy chip ----

    @Test
    void theChipIsRecordedForAKnownAllergyClassWhateverTheCase() {
        assertThat(MedicineSafetyCheck.allergyStatus("Penicillin (rash)")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("PENICILLIN")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("allergic to Amoxicillin")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("Sulfa drugs")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("SULPHA")).isEqualTo(AllergyStatus.RECORDED);
    }

    @Test
    void aKeywordWinsOverNoneKnownSoTheChipNeverSaysNoKnownAllergiesWhenTheCheckFindsOne() {
        assertThat(MedicineSafetyCheck.allergyStatus("Penicillin; none known otherwise"))
                .isEqualTo(AllergyStatus.RECORDED);
    }

    @Test
    void noneKnownGivesTheGreenChip() {
        assertThat(MedicineSafetyCheck.allergyStatus("None known")).isEqualTo(AllergyStatus.NONE_KNOWN);
        assertThat(MedicineSafetyCheck.allergyStatus("  NONE KNOWN  ")).isEqualTo(AllergyStatus.NONE_KNOWN);
    }

    @Test
    void everyNoneWordOfTheSharedListGivesTheGreenChipSoNoKnownAllergyWrittenAtSignIsNoAllergy() {
        for (String word : new String[] {"none", "nil", "nka", "nkda", "none known", "no known allergy",
            "no known allergies", "no known drug allergy", "no known drug allergies", "No known allergy",
            "  NKDA.  ", "No Known Drug Allergies."}) {
            assertThat(MedicineSafetyCheck.allergyStatus(word)).as(word).isEqualTo(AllergyStatus.NONE_KNOWN);
        }
    }

    @Test
    void aNoneWordNeverHidesARealAllergyOrAnythingBeyondTheWholeText() {
        assertThat(MedicineSafetyCheck.allergyStatus("Penicillin, no known allergy otherwise"))
                .isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("Dust; no known drug allergy")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("none of the usual")).isEqualTo(AllergyStatus.RECORDED);
    }

    @Test
    void noKnownAllergyRaisesNoAllergyFindingAndNoNotRecordedWarning() {
        OrderCheck check = check("amox", "No known allergy");
        assertThat(check.allergyStatus()).isEqualTo(AllergyStatus.NONE_KNOWN);
        assertThat(check.hardStops()).isEmpty();
        assertThat(check.warnings()).extracting(Finding::code).doesNotContain(FindingCode.ALLERGIES_NOT_RECORDED);
    }

    @Test
    void anEmptyBlankOrNotRecordedTextGivesAllergiesNotRecorded() {
        assertThat(MedicineSafetyCheck.allergyStatus(null)).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("   ")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("  ")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("Not recorded")).isEqualTo(AllergyStatus.NOT_RECORDED);
    }

    @Test
    void anyOtherTextIsRecordedAsWritten() {
        assertThat(MedicineSafetyCheck.allergyStatus("Peanuts")).isEqualTo(AllergyStatus.RECORDED);
    }

    // ---- allergy hard stops ----

    @Test
    void aPenicillinDrugIsBlockedForAPenicillinAllergyWithTheExactText() {
        OrderCheck result = check("amox", "Penicillin (rash)");

        assertThat(result.blocked()).isTrue();
        assertThat(result.hardStops()).hasSize(1);
        Finding f = result.hardStops().get(0);
        assertThat(f.code()).isEqualTo(FindingCode.ALLERGY_PENICILLIN);
        assertThat(f.severity()).isEqualTo(Severity.HARD_STOP);
        assertThat(f.otherDrugKey()).isNull();
        assertThat(f.text()).isEqualTo(
                "Documented allergy: Penicillin (rash). Tab. Amoxicillin + clavulanate 625 mg is a penicillin.");
        assertThat(result.firstHardStopLogged())
                .isEqualTo("Documented penicillin allergy. Tab. Amoxicillin + clavulanate 625 mg is a penicillin.");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void theKeywordAmoxicIsEnoughForThePenicillinClass() {
        assertThat(check("pip", "Allergic to amoxicillin").hardStops()).extracting(Finding::code)
                .containsExactly(FindingCode.ALLERGY_PENICILLIN);
    }

    @Test
    void aSulfaDrugIsBlockedForASulfaAllergyWithTheExactText() {
        OrderCheck result = check("cotri", "SULPHA allergy");

        Finding f = result.hardStops().get(0);
        assertThat(f.code()).isEqualTo(FindingCode.ALLERGY_SULFA);
        assertThat(f.text()).isEqualTo("Documented allergy: SULPHA allergy. Tab. Cotrimoxazole 960 mg is a sulfa drug.");
        assertThat(result.firstHardStopLogged())
                .isEqualTo("Documented sulfa allergy. Tab. Cotrimoxazole 960 mg is a sulfa drug.");
    }

    @Test
    void theAllergyTextShownIsStrippedButNotChanged() {
        OrderCheck result = check("amox", "  Penicillin  ");

        assertThat(result.hardStops().get(0).text()).startsWith("Documented allergy: Penicillin. ");
    }

    @Test
    void theLoggedWordingNeverHoldsTheAllergyText() {
        OrderCheck result = check("amox", "Penicillin (rash) Anil Kumar 9876543210");

        assertThat(result.firstHardStopLogged()).doesNotContain("rash").doesNotContain("Anil")
                .doesNotContain("9876543210");
    }

    @Test
    void aDrugOfAnotherClassIsNotBlockedByAnAllergy() {
        assertThat(check("pcm", "Penicillin").hardStops()).isEmpty();
        assertThat(check("cotri", "Penicillin").hardStops()).isEmpty();
        assertThat(check("amox", "Sulfa").hardStops()).isEmpty();
    }

    @Test
    void hardStopsKeepTheOrderAllergyThenDuplicate() {
        OrderCheck result = check("amox", "Penicillin and sulfa", "amox");

        assertThat(result.hardStops()).extracting(Finding::code)
                .containsExactly(FindingCode.ALLERGY_PENICILLIN, FindingCode.DUPLICATE);
    }

    @Test
    void aCephalosporinWithAPenicillinAllergyIsAModerateWarningNotAStop() {
        OrderCheck result = check("ceft", "Penicillin");

        assertThat(result.hardStops()).isEmpty();
        assertThat(result.needsReason()).isTrue();
        Finding f = result.warnings().get(0);
        assertThat(f.code()).isEqualTo(FindingCode.ALLERGY_CROSS_CEPHALOSPORIN);
        assertThat(f.severity()).isEqualTo(Severity.MODERATE);
        assertThat(f.text()).isEqualTo(
                "Penicillin allergy: possible cross-reaction with cephalosporins. Confirm what reaction the patient had.");
        assertThat(f.warningLine()).startsWith("Moderate: Penicillin allergy");
    }

    @Test
    void aCephalosporinWithASulfaAllergyHasNoCrossReactionWarning() {
        assertThat(check("ceft", "Sulfa").warnings()).isEmpty();
    }

    // ---- allergies not recorded ----

    @Test
    void anEmptyAllergyFieldIsAModerateWarning() {
        OrderCheck result = check("pcm", null);

        assertThat(result.allergyStatus()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(result.hardStops()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        Finding f = result.warnings().get(0);
        assertThat(f.code()).isEqualTo(FindingCode.ALLERGIES_NOT_RECORDED);
        assertThat(f.severity()).isEqualTo(Severity.MODERATE);
        assertThat(f.text()).isEqualTo("Allergies are not recorded. Ask the patient and record them before the first dose.");
        assertThat(result.needsReason()).isTrue();
    }

    @Test
    void noneKnownGivesAClearCheckForAPlainDrug() {
        OrderCheck result = check("pcm", NO_ALLERGY);

        assertThat(result.hardStops()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.blocked()).isFalse();
        assertThat(result.needsReason()).isFalse();
        assertThat(result.firstHardStopLogged()).isNull();
    }

    // ---- duplicates ----

    @Test
    void theSameDrugAlreadyOnTheChartIsAHardStopWithTheExactText() {
        OrderCheck result = check("pcm", NO_ALLERGY, "pcm");

        Finding f = result.hardStops().get(0);
        assertThat(f.code()).isEqualTo(FindingCode.DUPLICATE);
        assertThat(f.otherDrugKey()).isEqualTo("pcm");
        assertThat(f.text()).isEqualTo(
                "Tab. Paracetamol 650 mg is already on the chart. Change the existing order instead of adding a second one.");
        assertThat(result.firstHardStopLogged()).isEqualTo(f.text());
    }

    @Test
    void aDifferentDrugOnTheChartIsNotADuplicate() {
        assertThat(check("pcm", NO_ALLERGY, "panto").hardStops()).isEmpty();
    }

    @Test
    void theCallerPassesLiveOrdersOnlySoARejectedOrStoppedDrugIsNotADuplicate() {
        // Rejected and stopped orders are not passed in (the service filters them); an empty list is clear.
        assertThat(check("pcm", NO_ALLERGY).hardStops()).isEmpty();
    }

    // ---- interactions ----

    @Test
    void heparinWithAspirinIsAMajorInteractionInBothDirections() {
        OrderCheck aspirinOnHeparin = check("asp", NO_ALLERGY, "hep");
        OrderCheck heparinOnAspirin = check("hep", NO_ALLERGY, "asp");

        for (OrderCheck result : List.of(aspirinOnHeparin, heparinOnAspirin)) {
            assertThat(result.hardStops()).isEmpty();
            assertThat(result.warnings()).hasSize(1);
            assertThat(result.warnings().get(0).code()).isEqualTo(FindingCode.INTERACTION);
            assertThat(result.warnings().get(0).severity()).isEqualTo(Severity.MAJOR);
        }
        assertThat(aspirinOnHeparin.warnings().get(0).text())
                .isEqualTo("Bleeding risk: heparin with aspirin (Inj. Heparin 5,000 IU is on the chart).");
        assertThat(aspirinOnHeparin.warnings().get(0).otherDrugKey()).isEqualTo("hep");
        assertThat(heparinOnAspirin.warnings().get(0).text())
                .isEqualTo("Bleeding risk: heparin with aspirin (Tab. Aspirin 75 mg is on the chart).");
        assertThat(heparinOnAspirin.warnings().get(0).warningLine()).startsWith("Major: Bleeding risk");
    }

    @Test
    void heparinWithAnNsaidIsMajorInBothDirections() {
        assertThat(check("ibu", NO_ALLERGY, "hep").warnings().get(0).text())
                .isEqualTo("Bleeding risk: heparin with an NSAID (Inj. Heparin 5,000 IU is on the chart).");
        assertThat(check("hep", NO_ALLERGY, "ibu").warnings().get(0).severity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    void aspirinWithAnNsaidIsModerateInBothDirections() {
        assertThat(check("ibu", NO_ALLERGY, "asp").warnings().get(0).text())
                .isEqualTo("Stomach bleeding risk: aspirin with an NSAID (Tab. Aspirin 75 mg is on the chart).");
        assertThat(check("asp", NO_ALLERGY, "ibu").warnings().get(0).severity()).isEqualTo(Severity.MODERATE);
    }

    @Test
    void unrelatedDrugsHaveNoInteraction() {
        assertThat(check("pcm", NO_ALLERGY, "hep", "asp", "ibu").warnings()).isEmpty();
    }

    @Test
    void twoWarningsAtOnceKeepChartOrderThenRuleOrder() {
        // Ibuprofen on a chart with heparin and aspirin: heparin first (chart order), then aspirin.
        OrderCheck result = check("ibu", NO_ALLERGY, "hep", "asp");

        assertThat(result.warnings()).extracting(Finding::otherDrugKey).containsExactly("hep", "asp");
        assertThat(result.warnings()).extracting(Finding::severity).containsExactly(Severity.MAJOR, Severity.MODERATE);
    }

    // ---- order of findings ----

    @Test
    void aHardStopAndAWarningTogetherBlockAndKeepBothLists() {
        OrderCheck result = check("pcm", null, "pcm");

        assertThat(result.blocked()).isTrue();
        assertThat(result.needsReason()).isFalse();
        assertThat(result.hardStops()).extracting(Finding::code).containsExactly(FindingCode.DUPLICATE);
        assertThat(result.warnings()).extracting(Finding::code).containsExactly(FindingCode.ALLERGIES_NOT_RECORDED);
    }

    @Test
    void warningsComeInTheOrderCrossReactionNotRecordedThenInteractions() {
        OrderCheck result = check("asp", null, "hep");

        assertThat(result.warnings()).extracting(Finding::code)
                .containsExactly(FindingCode.ALLERGIES_NOT_RECORDED, FindingCode.INTERACTION);
    }

    // ---- the token ----

    @Test
    void theTokenIs22UrlSafeCharactersAndStable() {
        OrderCheck a = check("asp", NO_ALLERGY, "hep");
        OrderCheck b = check("asp", NO_ALLERGY, "hep");

        assertThat(a.checkToken()).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(a.checkToken()).isEqualTo(b.checkToken());
    }

    @Test
    void theTokenChangesWithTheFindingsAndTheChip() {
        String clear = check("asp", NO_ALLERGY).checkToken();
        String withHeparin = check("asp", NO_ALLERGY, "hep").checkToken();
        String withNsaid = check("asp", NO_ALLERGY, "ibu").checkToken();
        String notRecorded = check("asp", null).checkToken();
        String recorded = check("asp", "Peanuts").checkToken();

        assertThat(List.of(clear, withHeparin, withNsaid, notRecorded, recorded)).doesNotHaveDuplicates();
    }

    @Test
    void theTokenCarriesNoAllergyText() {
        // Two different texts with the same chip and findings give the same token: the text is not in it.
        assertThat(check("pcm", "Peanuts").checkToken()).isEqualTo(check("pcm", "Shellfish").checkToken());
    }

    @Test
    void theTokenDoesNotDependOnTheOtherDrugsNameOnlyItsKey() {
        assertThat(check("asp", NO_ALLERGY, "hep").checkToken()).isNotEqualTo(check("asp", NO_ALLERGY, "ibu").checkToken());
    }

    // ---- redaction and failures ----

    @Test
    void toStringNeverHoldsTheTextOfAFinding() {
        OrderCheck result = check("amox", "Penicillin (rash)");

        assertThat(result.toString()).doesNotContain("rash").doesNotContain("Amoxicillin");
        assertThat(result.hardStops().get(0).toString()).doesNotContain("rash").doesNotContain("Amoxicillin");
    }

    @Test
    void aLiveOrderOfAnUnknownDrugIsAFault() {
        assertThatThrownBy(() -> check("pcm", NO_ALLERGY, "zzz")).isInstanceOf(IllegalStateException.class);
    }

    // ---- S1: spelling variants, brands and abbreviations (security review) ----

    @ParameterizedTest(name = "[{index}] penicillin keyword in \"{0}\"")
    @ValueSource(strings = {"Penicillin", "Ampicillin allergy", "Cloxacillin", "Amoxycillin", "Flucloxacillin rash",
            "AMOXYCILLIN", "Amoxicillin", "amoxy", "Augmentin", "AUGMENTIN 625", "PCN", "pcn allergy", "Allergic to pcn",
            "Benzathine penicillin", "Piperacillin"})
    void everyPenicillinKeywordNamesTheClassBlocksAPenicillinDrugAndShowsTheRecordedChip(String text) {
        assertThat(MedicineSafetyCheck.allergyStatus(text)).isEqualTo(AllergyStatus.RECORDED);
        OrderCheck result = check("amox", text);
        assertThat(result.hardStops()).extracting(Finding::code).containsExactly(FindingCode.ALLERGY_PENICILLIN);
        assertThat(result.firstHardStopLogged())
                .isEqualTo("Documented penicillin allergy. Tab. Amoxicillin + clavulanate 625 mg is a penicillin.");
        assertThat(result.allergyStatus()).isEqualTo(AllergyStatus.RECORDED);
        assertThat(result.warnings()).extracting(Finding::code).doesNotContain(FindingCode.ALLERGIES_NOT_RECORDED);
    }

    @ParameterizedTest(name = "[{index}] sulfa keyword in \"{0}\"")
    @ValueSource(strings = {"Sulfa", "sulpha", "Sulfonamide", "Sulphonamide allergy", "SULF drugs", "sulph",
            "Cotrimoxazole", "co-trimoxazole", "Co-Trimoxazole rash", "cotrimox", "Septran", "SEPTRAN DS", "Bactrim",
            "bactrim allergy"})
    void everySulfaKeywordNamesTheClassBlocksASulfaDrugAndShowsTheRecordedChip(String text) {
        assertThat(MedicineSafetyCheck.allergyStatus(text)).isEqualTo(AllergyStatus.RECORDED);
        OrderCheck result = check("cotri", text);
        assertThat(result.hardStops()).extracting(Finding::code).containsExactly(FindingCode.ALLERGY_SULFA);
        assertThat(result.firstHardStopLogged())
                .isEqualTo("Documented sulfa allergy. Tab. Cotrimoxazole 960 mg is a sulfa drug.");
        assertThat(result.allergyStatus()).isEqualTo(AllergyStatus.RECORDED);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not recorded")
    @ValueSource(strings = {"unknown", "Unknown", "UNKNOWN", "  unknown  ", "not known", "Not Known", " NOT KNOWN ", "?",
            "  ?  "})
    void unknownNotKnownAndAQuestionMarkCountAsNotRecordedWithTheWarning(String text) {
        assertThat(MedicineSafetyCheck.allergyStatus(text)).isEqualTo(AllergyStatus.NOT_RECORDED);
        OrderCheck result = check("pcm", text);
        assertThat(result.allergyStatus()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(result.hardStops()).isEmpty();
        assertThat(result.warnings()).extracting(Finding::code).containsExactly(FindingCode.ALLERGIES_NOT_RECORDED);
        assertThat(result.needsReason()).isTrue();
    }

    @Test
    void theNotRecordedTextsMatchTheWholeTextOnlySoRealInformationStaysRecorded() {
        assertThat(MedicineSafetyCheck.allergyStatus("Unknown rash after a tablet")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("Peanuts, not known otherwise")).isEqualTo(AllergyStatus.RECORDED);
        assertThat(MedicineSafetyCheck.allergyStatus("None known")).isEqualTo(AllergyStatus.NONE_KNOWN);
    }

    @Test
    void anotherDrugClassIsStillNotBlockedByTheWiderKeywords() {
        assertThat(check("pcm", "Augmentin").hardStops()).isEmpty();
        assertThat(check("pcm", "Septran").hardStops()).isEmpty();
        assertThat(check("cotri", "Augmentin").hardStops()).isEmpty();
        assertThat(check("amox", "Bactrim").hardStops()).isEmpty();
        assertThat(check("pcm", "Peanuts").hardStops()).isEmpty();
    }

    @Test
    void aBrandOrAbbreviationOfPenicillinKeepsTheCephalosporinCrossReactionWarning() {
        for (String text : new String[] {"Augmentin", "PCN", "Ampicillin", "Amoxycillin"}) {
            OrderCheck result = check("ceft", text);

            assertThat(result.hardStops()).as(text).isEmpty();
            assertThat(result.warnings()).as(text).extracting(Finding::code)
                    .containsExactly(FindingCode.ALLERGY_CROSS_CEPHALOSPORIN);
        }
    }

    @Test
    void aSulfaBrandNeverRaisesTheCephalosporinCrossReaction() {
        assertThat(check("ceft", "Septran").warnings()).isEmpty();
        assertThat(check("ceft", "Bactrim").warnings()).isEmpty();
    }

    @Test
    void bothClassesInOneTextBlockBothDrugsAndTheLoggedWordingHoldsNoFreeText() {
        String text = "PCN and Septran, severe rash";

        OrderCheck penicillin = check("amox", text);
        OrderCheck sulfa = check("cotri", text);

        assertThat(penicillin.hardStops()).extracting(Finding::code).containsExactly(FindingCode.ALLERGY_PENICILLIN);
        assertThat(sulfa.hardStops()).extracting(Finding::code).containsExactly(FindingCode.ALLERGY_SULFA);
        assertThat(penicillin.firstHardStopLogged()).doesNotContain("PCN").doesNotContain("rash");
        assertThat(sulfa.firstHardStopLogged()).doesNotContain("Septran").doesNotContain("rash");
        assertThat(penicillin.toString()).doesNotContain("rash");
    }

    @Test
    void theCheckTokenDoesNotDependOnWhichSpellingTheAllergyUses() {
        assertThat(check("amox", "Augmentin").checkToken()).isEqualTo(check("amox", "Penicillin").checkToken())
                .isEqualTo(check("amox", "PCN").checkToken());
    }
}
