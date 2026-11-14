package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import java.util.List;
import java.util.Optional;

/**
 * Port over the global, read-only EMR reference data (ADR-020 D2): the section library, the 15 starter departments, their
 * immutable starter layouts and the doctor-specialty aliases. The same for every clinic, changed by migration only;
 * the application has SELECT only. Starter content is not clinically reviewed (OQ-43-8).
 */
public interface EmrLibraryRepository {

    /** {@code description} is null except for Physiotherapy (the only one drawn in the mockup). */
    record StarterRow(String starterKey, String name, String initials, String colour, String description,
                      boolean consentFlag, boolean pcpndtFlag, int position, int currentLayoutVersion) {
    }

    /** A stored starter layout: the document of ADR-020 D6 (see {@link EmrLayoutJson}). */
    record StarterLayoutRow(String starterKey, int layoutVersion, String layout) {
    }

    /** All 100 sections with their fields and options, COMMON then SPECIALTY then COMPLIANCE, then library order. */
    List<LibrarySection> sections();

    Optional<LibrarySection> section(String sectionKey);

    /** The 15 starters in the mockup order. */
    List<StarterRow> starters();

    Optional<StarterRow> starter(String starterKey);

    /** The starter layout of the starter's current version. */
    Optional<StarterLayoutRow> currentStarterLayout(String starterKey);

    Optional<StarterLayoutRow> starterLayout(String starterKey, int layoutVersion);

    /** The starter key for a normalised doctor specialty text, if the alias list has it (ADR-020 D7). */
    Optional<String> starterForAlias(String normalisedSpecialty);
}
