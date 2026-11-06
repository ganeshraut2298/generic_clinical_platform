package com.sahaj.patient.web;

import com.sahaj.patient.application.ConsentService.ConsentEntryView;
import com.sahaj.patient.application.ConsentService.ConsentStateView;
import java.time.Instant;

/** {@code PatientConsentState} in {@code docs/api/patient.yaml}: latest row per kind, null when never recorded. */
public record ConsentStateResponse(Entry records, Entry promotional) {

    /** {@code ConsentEntry}. */
    public record Entry(String kind, boolean given, Instant recordedAt, String recordedByName) {

        static Entry from(ConsentEntryView view) {
            return view == null ? null
                    : new Entry(view.kind(), view.given(), view.recordedAt(), view.recordedByName());
        }
    }

    public static ConsentStateResponse from(ConsentStateView view) {
        return new ConsentStateResponse(Entry.from(view.records()), Entry.from(view.promotional()));
    }
}
