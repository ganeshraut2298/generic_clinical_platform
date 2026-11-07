package com.sahaj.platform.api;

import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.domain.StaffPinRow;
import java.util.List;
import java.util.UUID;

/**
 * The result of {@link StaffPinPort#checkColleague}: the PIN outcome and, only when it is OK, the colleague's user id
 * (SAH-32, ADR-011 D7). Numbers, states and ids only: it never holds the PIN, a hash, a name or a mobile number, and
 * its {@link #toString()} prints the outcome and numbers only (no ids).
 *
 * <p>Normally call {@code checkColleague(...).requireOk()}. It returns the colleague's user id for a right PIN and
 * otherwise throws, with texts that never name the colleague:
 * <ul>
 *   <li>{@link StaffPinException} with {@link StaffPinException.Subject#COLLEAGUE}: problem types {@code wrong-pin}
 *       (403), {@code pin-locked} (403, {@code retryAfterSeconds}), {@code pin-not-set} (409; every reason, also the try
 *       that switched the PIN off), {@code rate-limit} (429), {@code pin-unavailable} (503) and, from SAH-38, {@code colleague-pin-limit} (429, no
 *       retryAfterSeconds) and {@code pin-check-failed} (503);</li>
 *   <li>{@link InvalidFieldsException} (400 {@code validation}, detail = the message) on field
 *       {@value #NOT_ELIGIBLE_FIELD}: "Choose a nurse from the list." for {@link PinCheck.Outcome#NOT_ELIGIBLE}.</li>
 * </ul>
 *
 * @param pin             what happened; never null
 * @param colleagueUserId the colleague's user id; non-null exactly when {@code pin} is OK
 */
public record ColleaguePinCheck(PinCheck pin, UUID colleagueUserId) {

    /** The request field the not-eligible answer points at (the MAR's {@code DoseInput.secondNurseId}). */
    public static final String NOT_ELIGIBLE_FIELD = "secondNurseId";

    /** The not-eligible text (contract wording of {@code docs/api/ipd.yaml}). */
    public static final String NOT_ELIGIBLE_TEXT = "Choose a nurse from the list.";

    public ColleaguePinCheck {
        if (pin == null) {
            throw new IllegalArgumentException("pin is required");
        }
        if (pin.isOk() != (colleagueUserId != null)) {
            throw new IllegalArgumentException("colleagueUserId is present exactly when the PIN is OK");
        }
    }

    /** A right PIN of the colleague with this user id. */
    public static ColleaguePinCheck ok(UUID colleagueUserId) {
        PinCheck right = new PinCheck(PinCheck.Outcome.OK, StaffPinRow.MAX_TRIES, null, 0);
        return new ColleaguePinCheck(right, colleagueUserId);
    }

    /** Any outcome other than OK. */
    public static ColleaguePinCheck failed(PinCheck pin) {
        return new ColleaguePinCheck(pin, null);
    }

    /** The named person cannot be the second checker (not an active member with the role, or the caller). */
    public static ColleaguePinCheck notEligible() {
        return failed(new PinCheck(PinCheck.Outcome.NOT_ELIGIBLE, 0, null, 0));
    }

    public PinCheck.Outcome outcome() {
        return pin.outcome();
    }

    public boolean isOk() {
        return pin.isOk();
    }

    /**
     * Returns the colleague's user id for a right PIN; otherwise throws the exception described on the type.
     *
     * @return the colleague's user id; never null
     */
    public UUID requireOk() {
        if (pin.outcome() == PinCheck.Outcome.OK) {
            return colleagueUserId;
        }
        if (pin.outcome() == PinCheck.Outcome.NOT_ELIGIBLE) {
            throw new InvalidFieldsException(List.of(new FieldMessage(NOT_ELIGIBLE_FIELD, NOT_ELIGIBLE_TEXT)), true);
        }
        throw StaffPinException.of(pin, StaffPinException.Subject.COLLEAGUE);
    }

    /** Outcome and numbers only: no ids, no PIN. */
    @Override
    public String toString() {
        return "ColleaguePinCheck[" + pin.toString().substring("PinCheck[".length());
    }
}
