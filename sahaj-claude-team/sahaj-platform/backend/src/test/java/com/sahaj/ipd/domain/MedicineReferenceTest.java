package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.MedicineSafetyCheck.FindingCode;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-31 the fixed lists (frequencies, reasons), the fail-fast catalogue and the fingerprint. */
class MedicineReferenceTest {

    // ---- frequencies and reasons ----

    @Test
    void theFourFrequenciesHaveTheContractKeysLabelsAndDoseTimes() {
        assertThat(Frequency.values()).extracting(Frequency::key).containsExactly("od", "bd", "tds", "q8");
        assertThat(Frequency.values()).extracting(Frequency::label)
                .containsExactly("Once daily", "Twice daily", "Three times daily", "8-hourly");
        assertThat(Frequency.OD.doseTimes()).containsExactly("8:00 AM");
        assertThat(Frequency.BD.doseTimes()).containsExactly("8:00 AM", "8:00 PM");
        assertThat(Frequency.TDS.doseTimes()).containsExactly("8:00 AM", "2:00 PM", "8:00 PM");
        assertThat(Frequency.Q8.doseTimes()).containsExactly("6:00 AM", "2:00 PM", "10:00 PM");
        assertThat(Frequency.BD.lowerLabel()).isEqualTo("twice daily");
        assertThat(Frequency.Q8.lowerLabel()).isEqualTo("8-hourly");
    }

    @Test
    void aFrequencyIsFoundByItsExactKeyOnly() {
        assertThat(Frequency.ofKey("tds")).contains(Frequency.TDS);
        assertThat(Frequency.ofKey("TDS")).isEmpty();
        assertThat(Frequency.ofKey("qd")).isEmpty();
        assertThat(Frequency.ofKey(null)).isEmpty();
    }

    @Test
    void theOverrideReasonsHaveTheContractKeysAndLabelsInSelectOrder() {
        assertThat(OverrideReason.values()).extracting(OverrideReason::key).containsExactly("BENEFIT_MONITORED",
                "SPECIALIST_ADVISED", "ALLERGY_HISTORY_CHECKED", "CARDIOLOGY_STENT");
        assertThat(OverrideReason.values()).extracting(OverrideReason::label).containsExactly(
                "Benefit outweighs the risk, monitoring in place", "Specialist advised this combination",
                "Allergy history checked with the patient: mild rash only, no anaphylaxis",
                "Recent coronary stent, cardiology advised");
        assertThat(OverrideReason.ofKey("CARDIOLOGY_STENT")).contains(OverrideReason.CARDIOLOGY_STENT);
        assertThat(OverrideReason.ofKey("OTHER")).isEmpty();
        assertThat(OverrideReason.ofKey(null)).isEmpty();
    }

    @Test
    void theRejectReasonsHaveTheContractKeysAndLabelsInSelectOrder() {
        assertThat(RejectReason.values()).extracting(RejectReason::key).containsExactly("ALLERGY_CONFLICT",
                "INTERACTION_RISK", "WRONG_DOSE", "DUPLICATE_THERAPY");
        assertThat(RejectReason.values()).extracting(RejectReason::label).containsExactly("Allergy conflict",
                "Interaction risk too high", "Wrong dose or frequency", "Duplicate therapy");
        assertThat(RejectReason.ofKey("WRONG_DOSE")).contains(RejectReason.WRONG_DOSE);
        assertThat(RejectReason.ofKey("wrong_dose")).isEmpty();
    }

    // ---- the catalogue ----

    @Test
    void theSeedCatalogueHasFourteenDrugsAndThreeRulesInOrder() {
        DrugCatalogue catalogue = SeedCatalogue.catalogue();

        assertThat(catalogue.drugs()).extracting(Drug::key).containsExactly("pcm", "panto", "ondan", "ns", "ceft",
                "amox", "pip", "cotri", "asp", "ibu", "met", "hep", "ins", "kcl");
        assertThat(catalogue.interactions()).hasSize(3);
        assertThat(catalogue.find("hep")).get().extracting(Drug::highAlert).isEqualTo("Anticoagulant");
        assertThat(catalogue.find("zzz")).isEmpty();
        assertThat(catalogue.find(null)).isEmpty();
    }

    @Test
    void anEmptyCatalogueFailsFast() {
        assertThatThrownBy(() -> new DrugCatalogue(List.of(), List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DrugCatalogue(null, List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCatalogueMissingAClassTheCodeReliesOnFailsFast() {
        for (String missing : DrugCatalogue.REQUIRED_CLASSES) {
            List<Drug> without = new ArrayList<>();
            for (Drug d : SeedCatalogue.drugs()) {
                Set<String> classes = new java.util.HashSet<>(d.classes());
                classes.remove(missing);
                without.add(new Drug(d.key(), d.name(), d.route(), classes, d.highAlert()));
            }
            assertThatThrownBy(() -> new DrugCatalogue(without, SeedCatalogue.interactions()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(missing);
        }
    }

    @Test
    void aRepeatedKeyFailsFast() {
        List<Drug> twice = new ArrayList<>(SeedCatalogue.drugs());
        twice.add(SeedCatalogue.drug("pcm"));

        assertThatThrownBy(() -> new DrugCatalogue(twice, List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDrugWithBothAllergyClassesGivesBothStopsInTheOrderPenicillinThenSulfa() {
        List<Drug> drugs = new ArrayList<>(SeedCatalogue.drugs());
        drugs.add(new Drug("both", "Test combination", "Oral", Set.of("penicillin", "sulfa"), null));
        MedicineSafetyCheck rule = new MedicineSafetyCheck(new DrugCatalogue(drugs, SeedCatalogue.interactions()));

        OrderCheck result = rule.check(drugs.get(drugs.size() - 1), "Sulfa and penicillin", List.of());

        assertThat(result.hardStops()).extracting(Finding::code)
                .containsExactly(FindingCode.ALLERGY_PENICILLIN, FindingCode.ALLERGY_SULFA);
        assertThat(result.firstHardStopLogged()).startsWith("Documented penicillin allergy.");
    }

    // ---- the fingerprint ----

    @Test
    void theFingerprintIs22UrlSafeCharactersAndStable() {
        String a = Fingerprint.of(List.of("one", "two"));

        assertThat(a).hasSize(Fingerprint.LENGTH).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(Fingerprint.of(List.of("one", "two"))).isEqualTo(a);
        assertThat(Fingerprint.of(List.of("two", "one"))).isNotEqualTo(a);
        assertThat(Fingerprint.of(List.of())).hasSize(22);
    }

    @Test
    void theFingerprintOfNoLinesIsTheHashOfTheEmptyText() {
        // SHA-256 of "" starts with 47DEQpj8HBSa-_TImW-5JA (first 16 bytes, base64url).
        assertThat(Fingerprint.of(List.of())).isEqualTo("47DEQpj8HBSa-_TImW-5JA");
    }
}
