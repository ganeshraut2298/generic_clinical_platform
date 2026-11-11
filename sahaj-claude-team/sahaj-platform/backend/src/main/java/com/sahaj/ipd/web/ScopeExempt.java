package com.sahaj.ipd.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SAH-34 (ADR-017 D3, AC 21, AC 22): the written exemption of an IPD endpoint from the patient scope guard, with the
 * reason: configuration, bed state, the Emergency queue, the pharmacist check, admitting a patient, or the owner's
 * ward-assignment setup. Each reason is on the exemption list of the design note.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScopeExempt {

    /** Why this endpoint reads or writes no patient of a ward. */
    String value();
}
