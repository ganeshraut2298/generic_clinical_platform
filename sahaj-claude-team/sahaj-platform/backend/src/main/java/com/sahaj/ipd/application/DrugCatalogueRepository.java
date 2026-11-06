package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugInteraction;
import java.util.List;

/**
 * SAH-31 {@code ipd.drug} and {@code ipd.drug_interaction}: global reference tables, select only for the application
 * (ADR-009 D3). Demo content from the mockup, not clinically validated (OQ-31-1, blocking for go-live).
 */
public interface DrugCatalogueRepository {

    /** Every medicine in select order ({@code sort_order}). */
    List<Drug> findDrugs();

    /** Every interaction rule in rule order ({@code sort_order}). */
    List<DrugInteraction> findInteractions();
}
