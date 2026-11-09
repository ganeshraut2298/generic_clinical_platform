package com.sahaj.ipd.domain.card;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SAH-37 (ADR-015 D3): the fixed Sahaj content of the patient card setup, the only copy: sections, fields, data groups,
 * quick actions, layouts and the label limit. {@code GET /card-setup} serialises {@link #catalogue()} from these
 * constants, so the browser holds no copy. Pure: no Spring, no I/O.
 *
 * <p>Only fields that have data in phase 1 are listed (OQ-37-3): no Workflow stage, no Assigned nurse, no Last nursing
 * note (DEC-027).
 */
public final class CardCatalogue {

    /** Longest label, in code points (OQ-37-8). */
    public static final int LABEL_MAX = 40;
    /** Raw label length (UTF-16 units) refused before the text is normalised (V14). */
    public static final int LABEL_RAW_MAX = 400;

    public static final String COMPACT = "compact";
    public static final String STANDARD = "standard";
    public static final String EXPANDED = "expanded";
    public static final List<String> LAYOUTS = List.of(COMPACT, STANDARD, EXPANDED);
    public static final List<Integer> CARDS_PER_ROW = List.of(2, 3, 4);

    public static final String HEADER = "header";
    public static final String STATUS = "status";
    public static final String STAY = "stay";
    public static final String CARE = "care";
    public static final String VITALS = "vitals";
    public static final String CLINICAL = "clinical";
    public static final String BILLING = "billing";

    public static final String G_PATIENT = "patient";
    public static final String G_CONTACT = "contact";
    public static final String G_CLINICAL = "clinical";
    public static final String G_VITALS = "vitals";
    public static final String G_BILLING = "billing";

    public static final String NAME = "name";
    public static final String UHID = "uhid";
    public static final String ALERTS = "alerts";
    public static final String EWS = "ews";
    public static final String ALLERGIES = "allergies";
    public static final String MOBILE = "mobile";
    public static final String DIAGNOSIS = "diagnosis";
    public static final String BP = "bp";
    public static final String PULSE = "pulse";
    public static final String SPO2 = "spo2";
    public static final String TEMP = "temp";
    public static final String RR = "rr";
    public static final String LAST_RECORDED = "lastRecorded";

    /**
     * The fields that widen what the ward list carries when the owner switches them on (security review SAH-37,
     * finding 1b; DEC-028 O3): the diagnosis, the vitals fields and the mobile number. Field keys only, in this order.
     */
    public static final List<String> SENSITIVE_FACTS = List.of(DIAGNOSIS, BP, PULSE, SPO2, TEMP, RR, LAST_RECORDED,
            MOBILE);

    public static final String NURSING = "nursing";
    public static final String EMR = "emr";
    public static final String TRANSFER = "transfer";
    public static final String DISCHARGE = "discharge";
    public static final String DEPOSIT_ACTION = "deposit";

    /** The setup rows: key, name, description, locked (header: always on and first), safety (always on). */
    public record SectionDef(String key, String name, String description, boolean locked, boolean safety) {
    }

    /**
     * One field. {@code required} is Patient name only; {@code safety} is UHID, Alerts, Early warning and Allergies.
     * {@code standardSection} is where the Sahaj standard card places it and where repair puts it back; it is not
     * part of the answer.
     */
    public record FieldDef(String key, String name, String group, boolean required, boolean safety,
                           List<String> allowedSections, @JsonInclude(JsonInclude.Include.NON_NULL) String unit,
                           @JsonIgnore String standardSection) {

        /** A field whose label cannot be changed and which cannot be switched off. */
        public boolean locked() {
            return required || safety;
        }
    }

    public record GroupDef(String key, String name, List<String> visibleTo) {
    }

    public record ActionDef(String key, String name, String icon, String description, List<String> usableBy,
                            boolean allowedInReadOnly) {
    }

    /** The answer part {@code catalogue} of {@code GET /card-setup}. */
    public record Catalogue(List<SectionDef> sections, List<FieldDef> fields, List<GroupDef> groups,
                            List<ActionDef> actions, List<String> viewAsRoles, int labelMaxLength) {
    }

    private static final List<String> ANY_SECTION = List.of(HEADER, STATUS, STAY, CARE, VITALS, CLINICAL, BILLING);

    public static final List<SectionDef> SECTIONS = List.of(
            new SectionDef(HEADER, "Card header", "Name, identifiers and bed · always first", true, false),
            new SectionDef(STATUS, "Workflow status", "Alerts", false, true),
            new SectionDef(STAY, "Admission and bed", "Dates, ward and room", false, false),
            new SectionDef(CARE, "Care team", "Doctor", false, false),
            new SectionDef(VITALS, "Vitals", "Latest readings with status", false, true),
            new SectionDef(CLINICAL, "Clinical", "Diagnosis and allergies", false, true),
            new SectionDef(BILLING, "Billing", "Running bill and deposit", false, false));

    public static final List<FieldDef> FIELDS = List.of(
            field(NAME, "Patient name", G_PATIENT, true, false, List.of(HEADER), null, HEADER),
            field(UHID, "UHID", G_PATIENT, false, true, ANY_SECTION, null, HEADER),
            field("ageSex", "Age and gender", G_PATIENT, false, false, ANY_SECTION, null, HEADER),
            field("bed", "Bed", G_PATIENT, false, false, ANY_SECTION, null, HEADER),
            field("ipNumber", "IP number", G_PATIENT, false, false, ANY_SECTION, null, HEADER),
            field(MOBILE, "Mobile", G_CONTACT, false, false, ANY_SECTION, null, HEADER),
            field(ALERTS, "Alerts", G_PATIENT, false, true, ANY_SECTION, null, STATUS),
            field("admittedOn", "Admitted on", G_PATIENT, false, false, ANY_SECTION, null, STAY),
            field("lengthOfStay", "Length of stay", G_PATIENT, false, false, ANY_SECTION, null, STAY),
            field("ward", "Ward", G_PATIENT, false, false, ANY_SECTION, null, STAY),
            field("room", "Room", G_PATIENT, false, false, ANY_SECTION, null, STAY),
            field("admissionType", "Admission type", G_PATIENT, false, false, ANY_SECTION, null, STAY),
            field("doctor", "Doctor", G_PATIENT, false, false, ANY_SECTION, null, CARE),
            field(BP, "BP", G_VITALS, false, false, ANY_SECTION, "mmHg", VITALS),
            field(PULSE, "Pulse", G_VITALS, false, false, ANY_SECTION, "/min", VITALS),
            field(SPO2, "SpO₂", G_VITALS, false, false, ANY_SECTION, "%", VITALS),
            field(TEMP, "Temp", G_VITALS, false, false, ANY_SECTION, "°C", VITALS),
            field(RR, "RR", G_VITALS, false, false, ANY_SECTION, "/min", VITALS),
            field(EWS, "Early warning", G_VITALS, false, true, ANY_SECTION, null, VITALS),
            field(LAST_RECORDED, "Last recorded", G_VITALS, false, false, ANY_SECTION, null, VITALS),
            field(DIAGNOSIS, "Diagnosis", G_CLINICAL, false, false, ANY_SECTION, null, CLINICAL),
            field(ALLERGIES, "Allergies", G_CLINICAL, false, true, ANY_SECTION, null, CLINICAL),
            field("runningBill", "Running bill", G_BILLING, false, false, ANY_SECTION, null, BILLING),
            field("deposit", "Deposit", G_BILLING, false, false, ANY_SECTION, null, BILLING),
            field("payer", "Payer", G_BILLING, false, false, ANY_SECTION, null, BILLING));

    public static final List<GroupDef> GROUPS = groups();

    public static final List<ActionDef> ACTIONS = List.of(
            new ActionDef(NURSING, "Nursing", "nurse", "Record vitals and a nursing note",
                    CardAccessRule.roles(CardAccessRule.NURSING), true),
            new ActionDef(EMR, "EMR", "emr", "Clinical record, medicines and results",
                    CardAccessRule.roles(CardAccessRule.EMR), true),
            new ActionDef(TRANSFER, "Transfer", "swap", "Move to another bed",
                    CardAccessRule.roles(CardAccessRule.TRANSFER), false),
            new ActionDef(DISCHARGE, "Discharge", "door", "Summary, final bill and bed release",
                    CardAccessRule.roles(CardAccessRule.DISCHARGE), false),
            new ActionDef(DEPOSIT_ACTION, "Deposit", "deposit", "Running bill and deposit",
                    CardAccessRule.roles(CardAccessRule.DEPOSIT), false));

    private static final Catalogue CATALOGUE = new Catalogue(SECTIONS, FIELDS, GROUPS, ACTIONS,
            CardAccessRule.VIEW_AS_ROLES, LABEL_MAX);

    private CardCatalogue() {
    }

    /** The catalogue as sent in {@code GET /card-setup}. */
    public static Catalogue catalogue() {
        return CATALOGUE;
    }

    public static Optional<SectionDef> section(String key) {
        return SECTIONS.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    public static Optional<FieldDef> field(String key) {
        return FIELDS.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    public static Optional<ActionDef> action(String key) {
        return ACTIONS.stream().filter(a -> a.key().equals(key)).findFirst();
    }

    public static List<String> sectionKeys() {
        return SECTIONS.stream().map(SectionDef::key).toList();
    }

    public static List<String> fieldKeys() {
        return FIELDS.stream().map(FieldDef::key).toList();
    }

    public static List<String> actionKeys() {
        return ACTIONS.stream().map(ActionDef::key).toList();
    }

    /** Standard name of a section key, or the key itself when unknown. */
    public static String sectionName(String key) {
        return section(key).map(SectionDef::name).orElse(key);
    }

    public static String fieldName(String key) {
        return field(key).map(FieldDef::name).orElse(key);
    }

    public static String actionName(String key) {
        return action(key).map(ActionDef::name).orElse(key);
    }

    private static FieldDef field(String key, String name, String group, boolean required, boolean safety,
                                  List<String> allowed, String unit, String standardSection) {
        return new FieldDef(key, name, group, required, safety, allowed, unit, standardSection);
    }

    private static List<GroupDef> groups() {
        List<GroupDef> list = new ArrayList<>();
        list.add(new GroupDef(G_PATIENT, "Patient and admission", CardAccessRule.groupRoles(G_PATIENT)));
        list.add(new GroupDef(G_CONTACT, "Contact details", CardAccessRule.groupRoles(G_CONTACT)));
        list.add(new GroupDef(G_CLINICAL, "Clinical details", CardAccessRule.groupRoles(G_CLINICAL)));
        list.add(new GroupDef(G_VITALS, "Vitals and early warning", CardAccessRule.groupRoles(G_VITALS)));
        list.add(new GroupDef(G_BILLING, "Billing", CardAccessRule.groupRoles(G_BILLING)));
        return List.copyOf(list);
    }
}
