package com.sahaj.patient.web;

import com.sahaj.patient.application.ConsentInput;

/** {@code ConsentInput} in {@code docs/api/patient.yaml}; validated by the application layer. */
public record ConsentAnswerRequest(String kind, Boolean given) {

    public ConsentInput toInput() {
        return new ConsentInput(kind, given);
    }
}
