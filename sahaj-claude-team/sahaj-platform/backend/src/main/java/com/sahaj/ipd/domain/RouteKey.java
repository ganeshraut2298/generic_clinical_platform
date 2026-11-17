package com.sahaj.ipd.domain;

import java.util.Optional;

/**
 * SAH-76-1 (ADR-027 data item 16, OQ-76-8): the route list of a medicine order, in select order. Demo content for
 * sign-off, not clinically reviewed (Q3). The keys are also checked by {@code ck_medicine_order_version_route}.
 */
public enum RouteKey {

    ORAL("Oral"),
    IV("IV"),
    IV_INFUSION("IV infusion"),
    IM("IM"),
    SC("SC"),
    SUBLINGUAL("Sublingual"),
    INHALED("Inhaled"),
    TOPICAL("Topical"),
    RECTAL("Rectal"),
    NASOGASTRIC("Nasogastric");

    private final String label;

    RouteKey(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    /** The route with this key, or empty for any other text (case matters). */
    public static Optional<RouteKey> ofKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (RouteKey r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** The route of the built drug text ("Oral", "IV", "IV infusion", "SC"), or empty when the text is not on the list. */
    public static Optional<RouteKey> ofLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        for (RouteKey r : values()) {
            if (r.label.equalsIgnoreCase(label.trim())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
