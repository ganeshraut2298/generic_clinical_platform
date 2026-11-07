package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
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
    static final String NOT_DUE = "This dose is not due yet. It can be given from 1 hour before its time.";
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
     * The checked format of the body: clientKey, slotAt, outcome (every format error together; the detail is the first).
     * The bedside fields and the reason are checked later, one message at a time.
     */
    record Body(UUID clientKey, Instant slotAt, boolean given) {

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
        Instant slotAt = slot(input == null ? null : input.slotAt());
        if (slotAt == null) {
            errors.add(new FieldMessage("slotAt", MedicineRules.RELOAD_MESSAGE));
        }
        String outcome = input == null ? null : input.outcome();
        boolean given = OUTCOME_GIVEN.equals(outcome);
        if (!given && !OUTCOME_NOT_GIVEN.equals(outcome)) {
            errors.add(new FieldMessage("outcome", MedicineRules.RELOAD_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        return new Body(clientKey, slotAt, given);
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

    // ---- clinical event templates (ADR-011 D9; wording changed forward only by ADR-014 D11: the record must not claim a
    // scan that may not have happened; events written before keep "wristband scanned") ----

    /** {@code ck_clinical_event_text}: an event text is 1 to 300 characters. */
    static final int MAX_EVENT_TEXT = 300;
    private static final String ELLIPSIS = "…";
    private static final String SECOND_CHECK = " · second check by ";

    static String gaveText(Drug drug, Instant slotAt, String secondNurseName) {
        String text = "Gave " + drug.name() + " (" + DoseSlots.clock(slotAt) + " dose) · identity checked against UHID";
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
