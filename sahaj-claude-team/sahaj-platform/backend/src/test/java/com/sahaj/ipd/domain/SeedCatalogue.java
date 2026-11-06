package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.MedicineSafetyCheck.Severity;
import java.util.List;
import java.util.Set;

/**
 * The catalogue of migration V2026_11_28_1000 (14 medicines and 3 interactions of the mockup) as test data, so the unit
 * tests of the rules and of the services run on the same content as the database (no database is used).
 */
public final class SeedCatalogue {

    private SeedCatalogue() {
    }

    public static DrugCatalogue catalogue() {
        return new DrugCatalogue(drugs(), interactions());
    }

    public static List<Drug> drugs() {
        return List.of(
                new Drug("pcm", "Tab. Paracetamol 650 mg", "Oral", Set.of(), null),
                new Drug("panto", "Inj. Pantoprazole 40 mg", "IV", Set.of(), null),
                new Drug("ondan", "Inj. Ondansetron 4 mg", "IV", Set.of(), null),
                new Drug("ns", "IV Normal saline 500 ml", "IV", Set.of(), null),
                new Drug("ceft", "Inj. Ceftriaxone 1 g", "IV", Set.of("cephalosporin"), null),
                new Drug("amox", "Tab. Amoxicillin + clavulanate 625 mg", "Oral", Set.of("penicillin"), null),
                new Drug("pip", "Inj. Piperacillin + tazobactam 4.5 g", "IV", Set.of("penicillin"), null),
                new Drug("cotri", "Tab. Cotrimoxazole 960 mg", "Oral", Set.of("sulfa"), null),
                new Drug("asp", "Tab. Aspirin 75 mg", "Oral", Set.of("antiplatelet"), null),
                new Drug("ibu", "Tab. Ibuprofen 400 mg", "Oral", Set.of("nsaid"), null),
                new Drug("met", "Tab. Metformin 500 mg", "Oral", Set.of(), null),
                new Drug("hep", "Inj. Heparin 5,000 IU", "SC", Set.of("anticoag"), "Anticoagulant"),
                new Drug("ins", "Inj. Regular insulin, sliding scale", "SC", Set.of("insulin"), "Insulin"),
                new Drug("kcl", "Inj. Potassium chloride 20 mmol in 500 ml", "IV infusion", Set.of("potassium"),
                        "Concentrated electrolyte"));
    }

    public static List<DrugInteraction> interactions() {
        return List.of(
                new DrugInteraction("anticoag", "antiplatelet", Severity.MAJOR,
                        "Bleeding risk: heparin with aspirin"),
                new DrugInteraction("anticoag", "nsaid", Severity.MAJOR, "Bleeding risk: heparin with an NSAID"),
                new DrugInteraction("antiplatelet", "nsaid", Severity.MODERATE,
                        "Stomach bleeding risk: aspirin with an NSAID"));
    }

    public static Drug drug(String key) {
        return catalogue().find(key).orElseThrow();
    }
}
