package com.sahaj.ipd.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAH-34 test helpers for the services that take the patient scope guard: a guard that reaches every admitted patient
 * (the state of every clinic at deploy, the switch off) and a ward-scope repository with no grant to review.
 */
final class ScopeStubs {

    private ScopeStubs() {
    }

    /** A guard whose scope is ALL for every caller: no refusal, no filter ({@code visibleAdmissionIds} is null). */
    static ScopeGuard unscoped() {
        ScopeGuard guard = mock(ScopeGuard.class);
        when(guard.scope(any(), any(), any(), any())).thenReturn(PatientScope.all());
        when(guard.scopeWithMembership(any(), any(), any(), any())).thenReturn(PatientScope.all());
        when(guard.visibleAdmissionIds(any(), any())).thenReturn(null);
        return guard;
    }

    /** A repository whose unreviewed mark is null: the owner's emergency access fields stay absent. */
    static WardScopeRepository repository() {
        WardScopeRepository repository = mock(WardScopeRepository.class);
        when(repository.unreviewedMark(any())).thenReturn(null);
        return repository;
    }
}
