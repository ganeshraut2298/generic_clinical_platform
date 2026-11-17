package com.sahaj.ipd.domain;

import java.util.List;
import java.util.Set;

/**
 * SAH-31 one medicine of the IPD catalogue ({@code ipd.drug}). Demo content from the mockup, not clinically
 * validated (OQ-31-1, blocking for go-live). {@code classes} drive the safety check; {@code highAlert} is the group
 * name shown as a pill, or null. SAH-76 (ADR-027 D8, D9, data item 14): {@code routeKey} is the default route,
 * {@code doseUnits} the units the doctor may choose (first preselected; demo content, Q3) and {@code timeCritical} the
 * flag from the drug master (none shipped).
 */
public record Drug(String key, String name, String route, Set<String> classes, String highAlert, RouteKey routeKey,
                   List<DoseUnit> doseUnits, boolean timeCritical) {

    public Drug {
        classes = Set.copyOf(classes);
        doseUnits = List.copyOf(doseUnits);
    }

    /**
     * A medicine with the built fields only: the route key is read from the route text (Oral when unknown), every
     * unit is allowed and it is not time critical. For unit tests; the database loader uses the full constructor.
     */
    public Drug(String key, String name, String route, Set<String> classes, String highAlert) {
        this(key, name, route, classes, highAlert, RouteKey.ofLabel(route).orElse(RouteKey.ORAL),
                List.of(DoseUnit.values()), false);
    }

    public boolean has(String drugClass) {
        return classes.contains(drugClass);
    }

    public boolean allowsUnit(DoseUnit unit) {
        return doseUnits.contains(unit);
    }
}
