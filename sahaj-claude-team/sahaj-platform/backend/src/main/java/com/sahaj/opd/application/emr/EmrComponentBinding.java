package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which library fields are shared components and which siblings they cover (ADR-022 D2). The binding is fixed in code,
 * never stored: it is applied whenever a layout or a library section is read, so a department version stored in phase 2
 * is bound the same way. The first field of a section that matches a component's rule is the component field; the
 * sibling fields named for that section are COVERED (not rendered, not stored, never required).
 */
public final class EmrComponentBinding {

    public static final String EMR_LIST = "EMR_LIST";
    public static final String PRESCRIPTION = "PRESCRIPTION";
    public static final String BODY_MAP = "BODY_MAP";
    /** SAH-51-6 (ADR-025 D6): the follow-up interval of an OPD visit, bound to followup.next_visit. */
    public static final String FOLLOW_UP = "FOLLOW_UP";
    /** SAH-67-1 (ADR-025 D8): the new medicines of an IPD round, in place of PRESCRIPTION, ordered through ipd at sign. */
    public static final String IPD_ORDERS = "IPD_ORDERS";

    private static final Map<String, String> LIST_KIND_BY_MASTER = Map.of(
            "complaints", "COMPLAINTS",
            "diagnoses", "DIAGNOSIS",
            "investigations", "INVESTIGATIONS",
            "advice", "ADVICE");

    /** Sibling fields replaced by the component, per section key. */
    private static final Map<String, Set<String>> COVERED = Map.of(
            "complaints", Set.of("duration", "severity"),
            "diagnosis", Set.of("final_diagnosis", "type"),
            "investigations", Set.of("radiology"),
            "rx", Set.of("dose_frequency", "duration", "timing", "instructions"),
            "followup", Set.of("reason"));

    private EmrComponentBinding() {
    }

    /**
     * ADR-025 D8: on an IPD round record the Prescription field is the IPD_ORDERS component (ipd's own medicine catalogue).
     * The layout itself is unchanged; this is applied when the record is read.
     */
    public static List<FieldDefinition> forIpdRound(List<FieldDefinition> fields) {
        List<FieldDefinition> out = new ArrayList<>(fields.size());
        for (FieldDefinition field : fields) {
            if (field.component() != null && PRESCRIPTION.equals(field.component().type())) {
                out.add(field.asComponent(new ComponentRef(IPD_ORDERS, null)));
            } else {
                out.add(field);
            }
        }
        return List.copyOf(out);
    }

    public static List<FieldDefinition> bind(String sectionKey, List<FieldDefinition> fields) {
        Set<String> covered = COVERED.getOrDefault(sectionKey, Set.of());
        List<FieldDefinition> bound = new ArrayList<>(fields.size());
        boolean listTaken = false;
        boolean rxTaken = false;
        for (FieldDefinition field : fields) {
            ComponentRef ref = null;
            if ("followup".equals(sectionKey) && "next_visit".equals(field.key())) {
                ref = new ComponentRef(FOLLOW_UP, null);
            } else if ("BODY_MAP".equals(field.type())) {
                ref = new ComponentRef(BODY_MAP, null);
            } else if ("SEARCH_LIST".equals(field.type()) || "CHIPS".equals(field.type())) {
                if ("SERVICES".equals(field.optionsSource()) && !listTaken) {
                    ref = new ComponentRef(EMR_LIST, "SERVICES");
                    listTaken = true;
                } else if ("MASTER".equals(field.optionsSource()) && "medicines".equals(field.master()) && !rxTaken) {
                    ref = new ComponentRef(PRESCRIPTION, null);
                    rxTaken = true;
                } else if ("MASTER".equals(field.optionsSource()) && LIST_KIND_BY_MASTER.containsKey(field.master())
                        && !covered.contains(field.key()) && !listTaken) {
                    ref = new ComponentRef(EMR_LIST, LIST_KIND_BY_MASTER.get(field.master()));
                    listTaken = true;
                }
            }
            if (ref != null) {
                bound.add(field.asComponent(ref));
            } else if (covered.contains(field.key())) {
                bound.add(field.asCovered());
            } else {
                bound.add(field);
            }
        }
        return List.copyOf(bound);
    }
}
