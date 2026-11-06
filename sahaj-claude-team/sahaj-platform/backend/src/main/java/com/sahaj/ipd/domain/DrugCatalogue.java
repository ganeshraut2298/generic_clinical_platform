package com.sahaj.ipd.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SAH-31 the immutable medicine catalogue and interaction rules, in select order (ADR-009 D3). It is loaded once
 * (reference data changes only by a migration, which is a deploy). The constructor fails fast when the catalogue is
 * empty or when a class the code relies on ({@code penicillin}, {@code sulfa}, {@code cephalosporin}) is held by no
 * medicine, because the safety check would then silently never find an allergy.
 */
public final class DrugCatalogue {

    /** The classes {@link MedicineSafetyCheck} names in code. */
    public static final Set<String> REQUIRED_CLASSES = Set.of(MedicineSafetyCheck.PENICILLIN,
            MedicineSafetyCheck.SULFA, MedicineSafetyCheck.CEPHALOSPORIN);

    private final List<Drug> drugs;
    private final List<DrugInteraction> interactions;
    private final Map<String, Drug> byKey = new HashMap<>();

    public DrugCatalogue(List<Drug> drugs, List<DrugInteraction> interactions) {
        if (drugs == null || drugs.isEmpty()) {
            throw new IllegalStateException("The medicine catalogue is empty");
        }
        this.drugs = List.copyOf(drugs);
        this.interactions = List.copyOf(interactions == null ? List.of() : interactions);
        for (Drug drug : this.drugs) {
            if (byKey.put(drug.key(), drug) != null) {
                throw new IllegalStateException("The medicine catalogue repeats a key");
            }
        }
        for (String required : REQUIRED_CLASSES) {
            boolean found = false;
            for (Drug drug : this.drugs) {
                found = found || drug.has(required);
            }
            if (!found) {
                throw new IllegalStateException("The medicine catalogue has no medicine of class " + required);
            }
        }
    }

    /** Every medicine, in select order. */
    public List<Drug> drugs() {
        return drugs;
    }

    /** Every interaction rule, in rule order. */
    public List<DrugInteraction> interactions() {
        return interactions;
    }

    public Optional<Drug> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }
}
