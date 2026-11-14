package com.sahaj.opd.domain.emr.specialty;

/** A computed note shown with text and colour, never colour alone (EmrFlag). */
public record Flag(String field, String label, boolean alert) {
}
