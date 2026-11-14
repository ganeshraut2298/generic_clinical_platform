package com.sahaj.opd.application.emr;

import java.util.Map;

/**
 * The 30 field types of the EMR mockup library (ADR-020 D3) and the value kind each one has. The Form builder
 * (SAH-59) adds types later; a type this code does not know maps to {@link EmrValueKind#LATER}, so an unknown type
 * stores nothing and never fails a read.
 */
public final class EmrFieldType {

    private static final Map<String, EmrValueKind> KINDS = Map.ofEntries(
            Map.entry("TEXT", EmrValueKind.TEXT),
            Map.entry("RICH_TEXT", EmrValueKind.TEXT),
            Map.entry("PATTERN", EmrValueKind.TEXT),
            Map.entry("DOCTOR_HOSPITAL", EmrValueKind.TEXT),
            Map.entry("NUMBER", EmrValueKind.NUMBER),
            Map.entry("NUMBER_TREND", EmrValueKind.NUMBER),
            Map.entry("NUMBER_UNIT", EmrValueKind.NUMBER_UNIT),
            Map.entry("DATE", EmrValueKind.DATE),
            Map.entry("TOGGLE", EmrValueKind.BOOLEAN),
            Map.entry("DROPDOWN", EmrValueKind.CHOICE),
            Map.entry("SINGLE_CHOICE", EmrValueKind.CHOICE),
            Map.entry("MULTI_SELECT", EmrValueKind.MULTI_CHOICE),
            Map.entry("CHECKLIST", EmrValueKind.MULTI_CHOICE),
            Map.entry("CHIPS", EmrValueKind.MULTI_CHOICE),
            Map.entry("SEARCH_LIST", EmrValueKind.ITEM_LIST),
            Map.entry("TABLE", EmrValueKind.TABLE),
            Map.entry("GRID", EmrValueKind.TABLE),
            Map.entry("TEMPLATE", EmrValueKind.STRUCTURED),
            Map.entry("DROPDOWNS", EmrValueKind.STRUCTURED),
            Map.entry("BODY_MAP", EmrValueKind.STRUCTURED),
            Map.entry("TOOTH_PICKER", EmrValueKind.STRUCTURED),
            Map.entry("LIBRARY", EmrValueKind.STRUCTURED),
            Map.entry("CALCULATED", EmrValueKind.DISPLAY_ONLY),
            Map.entry("FORMULA", EmrValueKind.DISPLAY_ONLY),
            Map.entry("RULE", EmrValueKind.DISPLAY_ONLY),
            Map.entry("REFERENCE", EmrValueKind.DISPLAY_ONLY),
            Map.entry("CHART", EmrValueKind.DISPLAY_ONLY),
            Map.entry("FILE", EmrValueKind.LATER),
            Map.entry("SIGNATURE", EmrValueKind.LATER),
            Map.entry("CALENDAR", EmrValueKind.LATER));

    private EmrFieldType() {
    }

    public static EmrValueKind valueKind(String type) {
        return type == null ? EmrValueKind.LATER : KINDS.getOrDefault(type, EmrValueKind.LATER);
    }

    public static boolean isKnown(String type) {
        return type != null && KINDS.containsKey(type);
    }
}
