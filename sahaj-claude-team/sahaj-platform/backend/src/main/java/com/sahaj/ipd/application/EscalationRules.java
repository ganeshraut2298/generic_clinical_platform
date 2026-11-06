package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAH-33 rules that are not database work: who may do what on the Safety board, and the checks of the three request
 * bodies (contract {@code docs/api/ipd.yaml}). The plan is clinical text: no message echoes what was typed, and the
 * checked values hide it from {@code toString}.
 */
final class EscalationRules {

    /** Who may read the board, the summary and the audit trail (DEC-015 OQ-33-1). */
    static final Set<String> READ_ROLES = Set.of("OWNER", "DOCTOR", "NURSE");
    /** Who may acknowledge and record a response. */
    static final Set<String> DOCTOR_ROLES = Set.of("OWNER", "DOCTOR");
    /** Who may call the senior doctor. */
    static final Set<String> NURSE_ROLES = Set.of("NURSE");

    static final int PLAN_MAX = 1000;
    /** The most UTF-16 code units a raw plan may have before any normalisation (contract: maxLength 4000). */
    static final int PLAN_RAW_MAX = 4000;
    static final String RELOAD_MESSAGE = "Reload the page and try again.";
    static final String ACTION_MESSAGE = "Choose what was done";
    static final String PLAN_REQUIRED_MESSAGE = "Write the plan and when vitals are next due.";
    static final String PLAN_LENGTH_MESSAGE = "Keep the plan to 1,000 characters.";
    static final String PLAN_CHARACTERS_MESSAGE = "Plan cannot contain control or text-direction characters";

    private EscalationRules() {
    }

    /** The checked body of acknowledge. */
    record Acknowledge(long version) {
    }

    /** The checked body of respond. The plan is hidden from {@code toString}. */
    record Respond(long version, ResponseAction action, String note) {

        @Override
        public String toString() {
            return "Respond[redacted]";
        }
    }

    /** The checked body of call-senior. */
    record CallSenior(long version, int seniorCalls) {
    }

    static Acknowledge acknowledge(VersionInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Long version = whole(input == null ? null : input.version(), Long.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", RELOAD_MESSAGE));
        }
        fail(errors);
        return new Acknowledge(version);
    }

    /** All field errors together, in the order version, action, note; the detail is the first message. */
    static Respond respond(ResponseInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Long version = whole(input == null ? null : input.version(), Long.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", RELOAD_MESSAGE));
        }
        ResponseAction action = ResponseAction.doctorChoice(input == null ? null : input.action()).orElse(null);
        if (action == null) {
            errors.add(new FieldMessage("action", ACTION_MESSAGE));
        }
        String note = plan(input == null ? null : input.note(), errors);
        fail(errors);
        return new Respond(version, action, note);
    }

    static CallSenior callSenior(SeniorCallInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Long version = whole(input == null ? null : input.version(), Long.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", RELOAD_MESSAGE));
        }
        Long calls = whole(input == null ? null : input.seniorCalls(), Integer.MAX_VALUE);
        if (calls == null) {
            errors.add(new FieldMessage("seniorCalls", RELOAD_MESSAGE));
        }
        fail(errors);
        return new CallSenior(version, calls.intValue());
    }

    private static void fail(List<FieldMessage> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
    }

    /**
     * A whole number from 0 to {@code max} read from the JSON as it arrived: null when the node is missing, null, not
     * a JSON integer (a string such as "3" or a fraction is never converted) or out of range.
     */
    static Long whole(JsonNode node, long max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        return value < 0 || value > max ? null : value;
    }

    /**
     * The plan and next vitals (ADR-007 OQ-33-14), the rules of the SAH-30 note in this order, each failure one
     * field error on {@code note} that stops the plan checks: (1) a raw plan longer than 4,000 UTF-16 code units is
     * refused before any normalisation; (2) Unicode NFC; (3) CR LF and CR become LF, a tab becomes one space; (4) the
     * IPD text rule with the line break as the one allowed control character; (5) stripped: empty is refused, more
     * than 1,000 Unicode code points is refused. Returns the stored text, or null when an error was added.
     */
    static String plan(String raw, List<FieldMessage> errors) {
        if (raw == null) {
            errors.add(new FieldMessage("note", PLAN_REQUIRED_MESSAGE));
            return null;
        }
        if (raw.length() > PLAN_RAW_MAX) {
            errors.add(new FieldMessage("note", PLAN_LENGTH_MESSAGE));
            return null;
        }
        String text = IpdText.nfc(raw).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        // A line break is replaced by a space for the check only, as for the nursing note.
        if (IpdText.hasControlOrBidi(text.replace('\n', ' '))) {
            errors.add(new FieldMessage("note", PLAN_CHARACTERS_MESSAGE));
            return null;
        }
        String stripped = IpdText.strip(text);
        if (stripped.isEmpty()) {
            errors.add(new FieldMessage("note", PLAN_REQUIRED_MESSAGE));
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > PLAN_MAX) {
            errors.add(new FieldMessage("note", PLAN_LENGTH_MESSAGE));
            return null;
        }
        return stripped;
    }
}
