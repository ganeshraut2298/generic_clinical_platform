package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MarViews.CorrectionInput;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.OverrideInput;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.InErrorReason;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.ScanOverrideReason;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SAH-32 rules that are not database work: who may read the MAR and who may give, the checked request body, the fixed
 * texts of the contract ({@code docs/api/ipd.yaml}, ADR-011) and the clinical event templates (ADR-011 D9). The event
 * templates hold times, medicine names (fixed reference text), the second nurse's display name and, for a wristband
 * mismatch, a UHID-shaped scan only: never a patient name, an allergy text, a note or a PIN.
 */
final class DoseRules {

    /** Who may give a dose or record that it was not given (OQ-32-3). */
    static final Set<String> GIVE_ROLES = Set.of("OWNER", "NURSE");
    /** Who may read the MAR; DOCTOR reads only. */
    static final Set<String> MAR_ROLES = Set.of("OWNER", "NURSE", "DOCTOR");

    static final String ONLY_GIVERS = "Only nurses and the clinic owner can give medicines.";
    static final String MAR_HIDDEN = "You cannot open the medicine round.";
    static final String SCAN_FIRST = "Scan the wristband first.";
    static final String MISMATCH = "Wristband does not match this patient. Stop. Check the patient before giving anything.";
    static final String CONFIRM = "Confirm the second identifier: name and date of birth.";
    static final String HIGH_ALERT = "High-alert medicine: a second nurse must check it and enter a 4-digit PIN.";
    static final String DIFFERENT_NURSE = "The second check must be a different nurse.";
    static final String CHOOSE_NURSE = "Choose a nurse from the list.";
    static final String CHOOSE_REASON = "Choose why the dose is not given.";
    static final String NOT_ADMITTED = "This patient is not admitted any more. Doses cannot be recorded.";
    static final String NOT_VERIFIED = "This order is not checked by the pharmacist yet.";
    static final String STOPPED = "This medicine was stopped. Its doses can no longer be recorded.";
    static final String REJECTED = "This order was rejected by the pharmacist. It cannot be given.";
    static final String NOT_A_DOSE_TIME =
            "This is not a dose time of this medicine today. Reload the page and try again.";
    static final String BEFORE_START = "This dose time is before the pharmacist checked the order.";
    static final String ON_HOLD = "This order is on hold. Do not give it.";
    static final String ORDER_CHANGED = "This order was changed. Reload and check the new order.";
    static final String CANNOT_NOW = "This dose cannot be given now. Reload the page.";
    static final String SCAN_MEDICINE = "Scan or type the medicine code.";
    static final String CHOOSE_OVERRIDE = "Choose why you cannot scan.";
    static final String OVERRIDE_NOTE = "Write what you checked instead.";
    static final String OVERRIDE_AFTER_MISMATCH_TITLE = "Override not allowed";
    static final String OVERRIDE_AFTER_MISMATCH =
            "A scan for this dose did not match in the last 15 minutes. Check the patient and the medicine, then scan the medicine code.";
    static final String WRITE_WHAT_HAPPENED = "Write what happened.";
    static final String CHOOSE_IN_ERROR = "Choose why it is in error.";
    static final String ONLY_CORRECTORS = "Only nurses and the clinic owner can correct a dose.";
    static final String ONLY_RECORDER = "Only the nurse who recorded this dose can undo it.";
    static final String CANNOT_UNDO = "This dose cannot be undone.";
    static final String CANNOT_MARK_ERROR = "This dose cannot be marked in error.";
    static final String UNDO_EXPIRED = "The time to undo has passed. Mark it as recorded in error instead.";
    static final String ERROR_WINDOW_CLOSED = "This dose is from an earlier day and cannot be changed.";
    static final int NOTE_MIN = 4;
    static final int NOTE_MAX = 200;

    /** The IST time as the contract writes it in a PRN answer: "2:30 PM" (minutes always shown). */
    static String clockText(Instant instant) {
        java.time.ZonedDateTime time = instant.atZone(DoseSlots.IST);
        int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        return hour + ":" + (time.getMinute() < 10 ? "0" : "") + time.getMinute() + (time.getHour() < 12 ? " AM" : " PM");
    }

    /** The text of a dose that is not due yet: names the window in force (ADR-027 D2). */
    static String notDue(int windowBeforeMin) {
        return "This dose is not due yet. It can be given from " + windowBeforeMin + " min before its time.";
    }
    static final String RECORDED = "This dose is already recorded.";
    static final String KEY_REUSED = "This dose was already recorded with different values. Reload the page.";
    static final String LOCK_READONLY = "IPD is read-only. Doses cannot be recorded.";
    static final String LOCK_NOT_ADMITTED = "This patient is not admitted any more.";

    static final String OUTCOME_GIVEN = "GIVEN";
    static final String OUTCOME_NOT_GIVEN = "NOT_GIVEN";

    private static final Pattern PIN = Pattern.compile("^[0-9]{4}$");

    private DoseRules() {
    }

    /**
     * The checked format of the body: clientKey, slotAt (when sent), outcome, orderVersion (every format error together;
     * the detail is the first). {@code slotAt} is null for a PRN dose: whether it is required is decided once the order is
     * known. The bedside fields and the reason are checked later, one message at a time.
     */
    record Body(UUID clientKey, Instant slotAt, boolean given, int orderVersion) {

        @Override
        public String toString() {
            return "Body[given=" + given + "]";
        }
    }

    static Body body(DoseInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        UUID clientKey = ClientKeys.parse(input == null ? null : input.clientKey());
        if (clientKey == null) {
            errors.add(new FieldMessage("clientKey", MedicineRules.RELOAD_MESSAGE));
        }
        String slotText = input == null ? null : input.slotAt();
        Instant slotAt = slot(slotText);
        if (slotText != null && slotAt == null) {
            errors.add(new FieldMessage("slotAt", MedicineRules.RELOAD_MESSAGE));
        }
        String outcome = input == null ? null : input.outcome();
        boolean given = OUTCOME_GIVEN.equals(outcome);
        if (!given && !OUTCOME_NOT_GIVEN.equals(outcome)) {
            errors.add(new FieldMessage("outcome", MedicineRules.RELOAD_MESSAGE));
        }
        Integer version = orderVersion(input == null ? null : input.orderVersion());
        if (version == null) {
            errors.add(new FieldMessage("orderVersion", MedicineRules.RELOAD_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        return new Body(clientKey, slotAt, given, version);
    }

    /** The order version as sent: a whole number of 1 or more (a JSON number or its text), else null. */
    static Integer orderVersion(Object value) {
        if (value instanceof Integer i) {
            return i >= 1 ? i : null;
        }
        if (value instanceof Long l) {
            return l >= 1 && l <= Integer.MAX_VALUE ? Integer.valueOf((int) (long) l) : null;
        }
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.strip());
                return parsed >= 1 ? Integer.valueOf(parsed) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Instant slot(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.strip()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** True when the PIN is exactly four ASCII digits. */
    static boolean pinFormat(String pin) {
        return pin != null && PIN.matcher(pin).matches();
    }

    /** The second nurse's membership id, or null when it is missing or not a canonical UUID. */
    static UUID secondNurse(DoseInput input) {
        return input == null ? null : ClientKeys.parse(input.secondNurseId());
    }

    /** True when the box was ticked: only the JSON value {@code true} counts. */
    static boolean confirmed(DoseInput input) {
        return input != null && Boolean.TRUE.equals(input.identityConfirmed());
    }

    static NotGivenReason reason(DoseInput input) {
        return input == null ? null : NotGivenReason.ofKey(input.reasonKey()).orElse(null);
    }

    /** The manual override: its reason and the trimmed note (clinical text). */
    record ScanOverride(ScanOverrideReason reason, String note) {
        @Override
        public String toString() {
            return "ScanOverride[reason=" + reason + "]";
        }
    }

    /** True when the body carries an override object (valid or not): the medicine code may then be empty (the wristband never). */
    static boolean hasOverride(DoseInput input) {
        return input != null && input.override() != null;
    }

    /** The override, checked: reason key of the three accepted on input (400 field override.reasonKey), then a note of 4 to 200 characters after trimming. */
    static ScanOverride override(DoseInput input) {
        OverrideInput o = input.override();
        ScanOverrideReason reason = ScanOverrideReason.ofInputKey(o.reasonKey()).orElse(null);
        if (reason == null) {
            throw new InvalidInputException("override.reasonKey", CHOOSE_OVERRIDE);
        }
        String note = o.note() == null ? "" : o.note().strip();
        if (note.length() < NOTE_MIN || note.length() > NOTE_MAX) {
            throw new InvalidInputException("override.note", OVERRIDE_NOTE);
        }
        return new ScanOverride(reason, note);
    }

    /** The note of a NOT_GIVEN dose with reason OTHER: 4 to 200 characters after trimming (400 field note). */
    static String otherNote(DoseInput input) {
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() < NOTE_MIN || note.length() > NOTE_MAX) {
            throw new InvalidInputException("note", WRITE_WHAT_HAPPENED);
        }
        return note;
    }

    /** The client key of an undo or an in-error mark (400 field clientKey). */
    static UUID correctionKey(CorrectionInput input) {
        UUID key = ClientKeys.parse(input == null ? null : input.clientKey());
        if (key == null) {
            throw new InvalidInputException("clientKey", MedicineRules.RELOAD_MESSAGE);
        }
        return key;
    }

    /** The reason of an in-error mark (400 field reasonKey). */
    static InErrorReason inErrorReason(CorrectionInput input) {
        return InErrorReason.ofKey(input.reasonKey())
                .orElseThrow(() -> new InvalidInputException("reasonKey", CHOOSE_IN_ERROR));
    }

    // ---- clinical event templates (ADR-011 D9; wording changed forward only by ADR-014 D11: the record must not claim a
    // scan that may not have happened; events written before keep "wristband scanned") ----

    /** {@code ck_clinical_event_text}: an event text is 1 to 300 characters. */
    static final int MAX_EVENT_TEXT = 300;
    private static final String ELLIPSIS = "…";
    private static final String SECOND_CHECK = " · second check by ";

    static String gaveText(Drug drug, Instant slotAt, String secondNurseName) {
        return gaveText(drug, slotAt, secondNurseName, null, false);
    }

    /** SAH-76: " · scan override: <reason label>" for an override and " · before pharmacist check" for a STAT dose. */
    static String gaveText(Drug drug, Instant slotAt, String secondNurseName, ScanOverrideReason override,
                           boolean beforeVerification) {
        String text = "Gave " + drug.name() + " (" + DoseSlots.clock(slotAt) + " dose) · identity checked against UHID";
        if (override != null) {
            text += " · scan override: " + override.label();
        }
        if (beforeVerification) {
            text += " · before pharmacist check";
        }
        if (secondNurseName == null) {
            return fit(text);
        }
        // The name is the only part of unbounded length (a member name may be 200 characters): shorten it, with an
        // ellipsis, so the whole text always fits the database limit; the rest of the wording is never cut.
        int room = MAX_EVENT_TEXT - text.length() - SECOND_CHECK.length();
        return fit(text + SECOND_CHECK + shorten(secondNurseName, room));
    }

    static String notGivenText(Drug drug, Instant slotAt, NotGivenReason reason) {
        return fit("Did not give " + drug.name() + " (" + DoseSlots.clock(slotAt) + " dose) · " + reason.label());
    }

    /** The deny entry of a medicine code mismatch; {@code code} is a code of the drug master or the fixed words. */
    static String medicineDeniedText(String code, Drug drug) {
        return fit("Stopped at the medicine check: entered " + code + ", expected " + drug.name());
    }

    static String undoneText(Drug drug) {
        return fit("Undid the dose of " + drug.name());
    }

    static String inErrorText(Drug drug, InErrorReason reason) {
        return fit("Marked the dose of " + drug.name() + " recorded in error: " + reason.label());
    }

    static String deniedText(String scan, String uhid, Drug drug) {
        return fit("Stopped at the identity check: entered " + scan + ", expected " + uhid + " (" + drug.name() + ")");
    }

    /** {@code value} cut to at most {@code room} characters, the last being an ellipsis when something was cut. */
    private static String shorten(String value, int room) {
        if (value.length() <= room) {
            return value;
        }
        if (room <= ELLIPSIS.length()) {
            return room <= 0 ? "" : ELLIPSIS;
        }
        int end = room - ELLIPSIS.length();
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** The last guard: whatever the parts, the text never goes over the database limit. */
    private static String fit(String text) {
        return shorten(text, MAX_EVENT_TEXT);
    }
}
