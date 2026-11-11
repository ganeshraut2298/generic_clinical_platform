package com.sahaj.ipd.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SAH-34 (ADR-017 D3): marks an IPD endpoint whose service applies the patient scope guard. Every handler under
 * {@code /api/v1/ipd} carries exactly one of {@code @ScopeGuarded} and {@link ScopeExempt}; {@code IpdScopeCoverageTest}
 * fails otherwise, and checks the set against the {@code x-scope-guard} operations of {@code docs/api/ipd.yaml}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScopeGuarded {

    /** What the endpoint hands to the guard. */
    Kind value();

    enum Kind {
        /** A set read: rows are filtered (lists, the bed board mask, the Safety board, the clinical audit trail). */
        LIST,
        /** A path with an admission id: refused when outside the scope (writes after the admission lock). */
        ADMISSION,
        /** A path with a medicine order id: the order's admission is found first. */
        ORDER,
        /** A path with an escalation id: the escalation's admission is found first. */
        ESCALATION,
        /** Emergency access endpoints (the picker, open, the owner's list and review). */
        GRANT
    }
}
