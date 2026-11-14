package com.sahaj.opd.application.emr;

/**
 * How the generic EMR renderer edits and stores a field (ADR-020 D3, docs/api/opd.yaml EmrValueKind). Fixed in code by
 * the field type, never stored.
 */
public enum EmrValueKind {
    TEXT, NUMBER, NUMBER_UNIT, DATE, BOOLEAN, CHOICE, MULTI_CHOICE, ITEM_LIST, TABLE, STRUCTURED, DISPLAY_ONLY, LATER,
    /** A shared component field (EMR list, prescription, body map; ADR-022 D2). */
    COMPONENT,
    /** A sibling field the component replaces: not rendered, not stored, never required (ADR-022 D2). */
    COVERED;

    /** DISPLAY_ONLY, LATER and COVERED fields store nothing, so they are neither filled nor checked for Done (ADR-020 D8). */
    public boolean fillable() {
        return this != DISPLAY_ONLY && this != LATER && this != COVERED;
    }
}
