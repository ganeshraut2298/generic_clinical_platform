package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-76-1 the route and unit lists (ADR-027 data item 16) and the medicine's new fields (routeKey, doseUnits). */
class RouteAndUnitTest {

    @Test
    void theRoutesAreTheContractListInSelectOrderWithTheirLabels() {
        assertThat(RouteKey.values()).extracting(RouteKey::key).containsExactly("ORAL", "IV", "IV_INFUSION", "IM", "SC",
                "SUBLINGUAL", "INHALED", "TOPICAL", "RECTAL", "NASOGASTRIC");
        assertThat(RouteKey.IV_INFUSION.label()).isEqualTo("IV infusion");
        assertThat(RouteKey.NASOGASTRIC.label()).isEqualTo("Nasogastric");
    }

    @Test
    void aRouteIsFoundByKeyWithCaseMattering() {
        assertThat(RouteKey.ofKey("IV")).contains(RouteKey.IV);
        assertThat(RouteKey.ofKey("iv")).isEmpty();
        assertThat(RouteKey.ofKey(null)).isEmpty();
        assertThat(RouteKey.ofKey("ORAL ")).isEmpty();
    }

    @Test
    void theBuiltRouteTextsMapToRouteKeys() {
        assertThat(RouteKey.ofLabel("Oral")).contains(RouteKey.ORAL);
        assertThat(RouteKey.ofLabel("IV")).contains(RouteKey.IV);
        assertThat(RouteKey.ofLabel("IV infusion")).contains(RouteKey.IV_INFUSION);
        assertThat(RouteKey.ofLabel("SC")).contains(RouteKey.SC);
        assertThat(RouteKey.ofLabel("Smoke")).isEmpty();
    }

    @Test
    void theUnitsAreTheContractListWithTheirLabels() {
        assertThat(DoseUnit.values()).extracting(DoseUnit::key).containsExactly("TABLET", "CAPSULE", "MG", "G", "MCG",
                "ML", "IU", "UNIT", "MMOL");
        assertThat(DoseUnit.UNIT.label()).isEqualTo("units");
        assertThat(DoseUnit.IU.label()).isEqualTo("IU");
        assertThat(DoseUnit.ofKey("MG")).contains(DoseUnit.MG);
        assertThat(DoseUnit.ofKey("mg")).isEmpty();
    }

    @Test
    void aDoseIsShownWithoutTrailingZeros() {
        assertThat(DoseUnit.TABLET.display(new BigDecimal("1.000"))).isEqualTo("1 tablet");
        assertThat(DoseUnit.MG.display(new BigDecimal("650"))).isEqualTo("650 mg");
        assertThat(DoseUnit.G.display(new BigDecimal("0.50"))).isEqualTo("0.5 g");
        assertThat(DoseUnit.MG.display(new BigDecimal("1E+2"))).isEqualTo("100 mg");
    }

    @Test
    void aMedicineBuiltWithTheShortConstructorReadsItsRouteAndAllowsEveryUnit() {
        Drug drug = new Drug("pcm", "Tab. Paracetamol 650 mg", "Oral", Set.of("analgesic"), null);

        assertThat(drug.routeKey()).isEqualTo(RouteKey.ORAL);
        assertThat(drug.doseUnits()).containsExactly(DoseUnit.values());
        assertThat(drug.timeCritical()).isFalse();
        assertThat(drug.allowsUnit(DoseUnit.MG)).isTrue();
    }

    @Test
    void aMedicineWithItsOwnUnitsAllowsOnlyThose() {
        Drug drug = new Drug("ins", "Inj. Regular insulin", "SC", Set.of(), "Insulin", RouteKey.SC,
                List.of(DoseUnit.UNIT), true);

        assertThat(drug.allowsUnit(DoseUnit.UNIT)).isTrue();
        assertThat(drug.allowsUnit(DoseUnit.MG)).isFalse();
        assertThat(drug.timeCritical()).isTrue();
    }

    @Test
    void thePrnFrequencyHasNoDoseTimes() {
        assertThat(Frequency.ofKey("prn")).contains(Frequency.PRN);
        assertThat(Frequency.PRN.label()).isEqualTo("As needed (PRN)");
        assertThat(Frequency.PRN.times()).isEmpty();
    }
}
