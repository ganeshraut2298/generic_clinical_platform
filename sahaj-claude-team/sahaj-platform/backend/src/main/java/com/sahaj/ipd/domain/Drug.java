package com.sahaj.ipd.domain;

import java.util.Set;

/**
 * SAH-31 one medicine of the IPD catalogue ({@code ipd.drug}). Demo content from the mockup, not clinically
 * validated (OQ-31-1, blocking for go-live). {@code classes} drive the safety check; {@code highAlert} is the group
 * name shown as a pill, or null.
 */
public record Drug(String key, String name, String route, Set<String> classes, String highAlert) {

    public Drug {
        classes = Set.copyOf(classes);
    }

    public boolean has(String drugClass) {
        return classes.contains(drugClass);
    }
}
