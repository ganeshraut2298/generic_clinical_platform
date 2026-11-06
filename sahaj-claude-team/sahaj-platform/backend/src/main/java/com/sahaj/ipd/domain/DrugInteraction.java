package com.sahaj.ipd.domain;

/**
 * SAH-31 one interaction rule of the catalogue ({@code ipd.drug_interaction}): two drug classes, stored once and
 * matched in both directions, a severity and the text shown to the doctor. Demo content (OQ-31-1).
 */
public record DrugInteraction(String classA, String classB, MedicineSafetyCheck.Severity severity, String text) {

    /** True when one drug has a class of the pair and the other drug the other class. */
    boolean matches(Drug candidate, Drug onChart) {
        return (candidate.has(classA) && onChart.has(classB)) || (candidate.has(classB) && onChart.has(classA));
    }
}
