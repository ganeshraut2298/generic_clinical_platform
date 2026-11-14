package com.sahaj.patient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.patient.application.DateOfBirthInput;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * SAH-73 (ADR-024 D5): reads {@code dateOfBirth} of a request from its JSON node, so that a missing property, an
 * explicit null, a date and something unreadable are four different answers. The unreadable case never keeps the text
 * (and never raises a Jackson error, whose message would quote it): it becomes the field error "Enter a valid date of
 * birth." in the service.
 */
final class DateOfBirthJson {

    private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private DateOfBirthJson() {
    }

    /** A Java null (property missing) is absent; a JSON null clears; a string YYYY-MM-DD that exists is a date. */
    static DateOfBirthInput read(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return DateOfBirthInput.ABSENT;
        }
        if (node.isNull()) {
            return DateOfBirthInput.CLEARED;
        }
        if (!node.isTextual() || !ISO_DATE.matcher(node.textValue()).matches()) {
            return DateOfBirthInput.INVALID;
        }
        try {
            return DateOfBirthInput.of(LocalDate.parse(node.textValue()));
        } catch (DateTimeParseException e) {
            return DateOfBirthInput.INVALID;
        }
    }
}
