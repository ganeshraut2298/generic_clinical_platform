package com.sahaj.ipd.application;

import com.sahaj.ipd.application.VitalsViews.EwsSummary;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.domain.EarlyWarningScore;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.ipd.domain.VitalsDue;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SAH-30 rules that are not database work: who may do what, the format and plausible range of the five typed
 * vitals, the note text rule, and the fixed texts of the clinical event log. The note and the vitals are clinical
 * data: no message here echoes what was typed.
 */
final class VitalsRules {

    /** Who may open the Nursing sheet (OQ-30-3). */
    static final Set<String> READ_ROLES = Set.of("OWNER", "NURSE", "DOCTOR");
    /** Who may save vitals and a note (OQ-30-3). */
    static final Set<String> RECORD_ROLES = Set.of("OWNER", "NURSE");

    static final int NOTE_MAX = 1000;
    /** The most UTF-16 code units a raw note may have before any normalisation (contract: maxLength 4000). */
    static final int NOTE_RAW_MAX = 4000;
    static final String FORMAT_MESSAGE = "Enter all five vitals. BP as 120/80.";
    static final String CLIENT_KEY_MESSAGE = "Reload the page and try again.";
    static final String BP_RANGE_MESSAGE = "Check the BP. Systolic 40 to 300, diastolic 20 to 200.";
    static final String PULSE_RANGE_MESSAGE = "Check the pulse. Enter 1 to 300 per minute.";
    static final String SPO2_RANGE_MESSAGE = "Check the SpO\u2082. Enter 1 to 100 %.";
    static final String TEMP_RANGE_MESSAGE = "Check the temperature. Enter 25.0 to 45.0 \u00b0C.";
    static final String RR_RANGE_MESSAGE = "Check the RR. Enter 1 to 300 per minute.";
    static final String NOTE_CHARACTERS_MESSAGE = "Note cannot contain control or text-direction characters";
    static final String NOTE_LENGTH_MESSAGE = "Keep the note to 1,000 characters.";

    private static final Pattern BP = Pattern.compile("^([0-9]{2,3})/([0-9]{2,3})$");
    private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,3}$");
    private static final Pattern TEMP = Pattern.compile("^([0-9]{1,2})(?:[.]([0-9]))?$");

    private VitalsRules() {
    }

    /** The checked values of a save; {@code note} is null when no note is to be stored. */
    record Valid(int systolic, int diastolic, int pulse, int spo2, int tempTenths, int rr, String note) {

        @Override
        public String toString() {
            return "Valid[redacted]";
        }
    }

    /** The client key as a UUID, or the contract's 400 on the field clientKey. */
    static UUID clientKey(VitalsInput input) {
        UUID key = ClientKeys.parse(input == null ? null : input.clientKey());
        if (key != null) {
            return key;
        }
        throw new InvalidInputException("clientKey", CLIENT_KEY_MESSAGE);
    }

    /** All field errors together, in the order bp, pulse, spo2, temp, rr, note. */
    static Valid validate(VitalsInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        int systolic = 0;
        int diastolic = 0;
        Matcher bp = match(BP, input.bp());
        if (bp == null) {
            errors.add(new FieldMessage("bp", FORMAT_MESSAGE));
        } else {
            systolic = Integer.parseInt(bp.group(1));
            diastolic = Integer.parseInt(bp.group(2));
            if (systolic < 40 || systolic > 300 || diastolic < 20 || diastolic > 200) {
                errors.add(new FieldMessage("bp", BP_RANGE_MESSAGE));
            }
        }
        int pulse = whole("pulse", input.pulse(), 1, 300, PULSE_RANGE_MESSAGE, errors);
        int spo2 = whole("spo2", input.spo2(), 1, 100, SPO2_RANGE_MESSAGE, errors);
        int tempTenths = 0;
        Matcher temp = match(TEMP, input.temp());
        if (temp != null) {
            tempTenths = Integer.parseInt(temp.group(1)) * 10 + (temp.group(2) == null ? 0
                    : Integer.parseInt(temp.group(2)));
        }
        if (temp == null || tempTenths == 0) {
            errors.add(new FieldMessage("temp", FORMAT_MESSAGE));
        } else if (tempTenths < 250 || tempTenths > 450) {
            errors.add(new FieldMessage("temp", TEMP_RANGE_MESSAGE));
        }
        int rr = whole("rr", input.rr(), 1, 300, RR_RANGE_MESSAGE, errors);
        String note = note(input.note(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        return new Valid(systolic, diastolic, pulse, spo2, tempTenths, rr, note);
    }

    private static Matcher match(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text.strip());
        return m.matches() ? m : null;
    }

    /** A whole number of 1 to 3 digits above 0 and inside the range; 0 when it failed (the error is added). */
    private static int whole(String field, String text, int min, int max, String rangeMessage,
                             List<FieldMessage> errors) {
        Matcher m = match(WHOLE, text);
        int value = m == null ? 0 : Integer.parseInt(text.strip());
        if (m == null || value == 0) {
            errors.add(new FieldMessage(field, FORMAT_MESSAGE));
            return 0;
        }
        if (value < min || value > max) {
            errors.add(new FieldMessage(field, rangeMessage));
            return 0;
        }
        return value;
    }

    /**
     * The optional note (ADR-006 D7b): a raw note longer than 4,000 UTF-16 code units is refused first, so NFC never
     * runs on an unbounded string; then Unicode NFC, CR LF and CR become LF, a tab becomes one space, the IPD text
     * rule on the whole text before stripping (a line break is the one allowed control character; ADR-006 Q7), then
     * stripped. Empty after stripping means no note (null). More than 1,000 code points is a field error.
     */
    private static String note(String raw, List<FieldMessage> errors) {
        if (raw == null) {
            return null;
        }
        if (raw.length() > NOTE_RAW_MAX) {
            errors.add(new FieldMessage("note", NOTE_LENGTH_MESSAGE));
            return null;
        }
        String text = IpdText.nfc(raw).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        // A line break is replaced by a space for the check only: a joiner next to it is then as misplaced as it
        // is next to a line break, and no other character changes its neighbours.
        if (IpdText.hasControlOrBidi(text.replace('\n', ' '))) {
            errors.add(new FieldMessage("note", NOTE_CHARACTERS_MESSAGE));
            return null;
        }
        String stripped = IpdText.strip(text);
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > NOTE_MAX) {
            errors.add(new FieldMessage("note", NOTE_LENGTH_MESSAGE));
            return null;
        }
        return stripped;
    }

    // ---- fixed texts of the clinical event log (ADR-006 D7) ----

    static String recordedText(int score, Risk risk) {
        return "Recorded vitals \u00b7 EWS " + score + " (" + risk.word() + ")";
    }

    static String raisedText(int score, Risk risk, int targetMinutes) {
        return "Raised early warning escalation \u00b7 EWS " + score + " (" + risk.word() + ") \u00b7 doctor review "
                + "within " + (targetMinutes == EarlyWarningScore.HIGH_TARGET_MINUTES ? "15 min" : "1 hour");
    }

    static String updatedText(int score, Risk risk) {
        return "Escalation updated \u00b7 EWS now " + score + " (" + risk.word() + ")";
    }

    // SAH-33 (ADR-007 D10): fixed wording and numbers only; never the plan, a name or a vital value.

    static String acknowledgedText(int minutes) {
        return "Acknowledged escalation after " + minutes + " min";
    }

    static String closedText(ResponseAction action) {
        return "Escalation closed: " + action.label();
    }

    static String seniorCalledText(int minutes) {
        return "Called the senior doctor: escalation not seen after " + minutes + " min";
    }

    /** ADR-007 R4: a nurse saw that another team member had already called; no name and no role in the text. */
    static String seniorAlreadyCalledText() {
        return "Senior doctor already called · seen by another team member · no new call recorded";
    }

    static String reopenedText(int score) {
        return "Escalation re-opened · EWS now " + score + " (high) · doctor review within 15 min";
    }

    /** {@code typeLabel} is the discharge type label in lower case: "death", "on request (lama)" or "referred". */
    static String closedAtDischargeText(String typeLabel) {
        return "Escalation closed at discharge (" + typeLabel + ") · no doctor response recorded";
    }

    // ---- shared by the Nursing sheet and the admission sheet ----

    /** True when the role may see vitals data (the Nursing button, the score and vitals due). */
    static boolean canRead(String role) {
        return role != null && READ_ROLES.contains(role);
    }

    /**
     * The vitals due flag of an admission; null for a role without vitals access (ADR-006 Q6).
     */
    static Boolean vitalsDue(String role, boolean admitted, Instant lastVitals, Instant now) {
        return canRead(role) ? VitalsDue.isDue(admitted, lastVitals, now) : null;
    }

    static EwsSummary summary(VitalsRepository.VitalsRow row) {
        return row == null ? null : new EwsSummary(row.ewsScore(), row.ewsRisk(), row.recordedAt());
    }
}
