package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.ipd.domain.RejectReason;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SAH-31 rules that are not database work: who may do what with medicine orders, the checks of the three request
 * bodies, the fixed texts of the contract ({@code docs/api/ipd.yaml}, ADR-009) and the clinical event templates
 * (ADR-009 D7). The event templates hold numbers, medicine names (fixed reference text) and fixed words only: never a
 * patient name, an allergy text or a note.
 */
final class MedicineRules {

    /** Who may place and stop orders (ADR-009 D1). */
    static final Set<String> ORDER_ROLES = Set.of("OWNER", "DOCTOR");
    /** Who may open the EMR sheet. */
    static final Set<String> EMR_ROLES = Set.of("OWNER", "DOCTOR", "NURSE");
    /** Who may verify and reject orders, and read the pharmacist list (DEC-017). */
    static final Set<String> PHARMACIST_ROLES = Set.of("PHARMACIST", "OWNER");

    static final String RELOAD_MESSAGE = "Reload the page and try again.";
    static final String ONLY_DOCTOR = "Only a doctor can do this.";
    static final String EMR_HIDDEN = "Clinical details are hidden for your role.";
    static final String ONLY_PHARMACIST = "Only the pharmacist or the clinic owner can check orders.";
    static final String SELF_VERIFICATION =
            "The doctor who ordered this cannot verify it. Ask the pharmacist.";
    static final String NOT_ADMITTED =
            "This patient is not admitted any more. Medicines cannot be ordered or changed.";
    static final String CLIENT_KEY_REUSED =
            "This order was already placed with different values. Reload the page and try again.";
    static final String DRUG_MESSAGE = "Choose a medicine from the list";
    static final String FREQUENCY_MESSAGE = "Choose how often";
    static final String OVERRIDE_MESSAGE = "Choose a reason from the list";
    static final String PIN_MESSAGE = "Enter the 4-digit PIN.";
    static final String REJECT_MESSAGE = "Choose a reason";
    static final String ALREADY_STOPPED = "This order is already stopped.";
    static final String REJECTED_CANNOT_STOP = "This order was rejected by the pharmacist. It cannot be stopped.";
    static final String ALREADY_REJECTED = "This order was already rejected.";
    static final String STOPPED_BY_DOCTOR = "The doctor stopped this order.";
    static final String HARD_STOP_ON_ORDER = "Hard stop on this order. Reject it.";
    static final String CHECKS_CHANGED = "The checks on this order changed. Review them and try again.";

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final Pattern PIN = Pattern.compile("^[0-9]{4}$");
    private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,3}$");
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_-]{32}$");

    private MedicineRules() {
    }

    // ---- checked bodies ----

    /** The checked body of an order. The reason and the token are optional. */
    record Order(UUID clientKey, Drug drug, Frequency frequency, OverrideReason reason, String checkToken) {

        @Override
        public String toString() {
            return "Order[drugKey=" + drug.key() + "]";
        }
    }

    /** The checked body of verify. The PIN is never logged: hidden from {@code toString}. */
    record Verify(String pin, String checkToken) {

        @Override
        public String toString() {
            return "Verify[redacted]";
        }
    }

    /** Every field error together, in the order clientKey, drugKey, frequencyKey, overrideReasonKey, checkToken. */
    static Order order(DrugCatalogue catalogue, MedicineOrderInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        UUID clientKey = ClientKeys.parse(input == null ? null : input.clientKey());
        if (clientKey == null) {
            errors.add(new FieldMessage("clientKey", RELOAD_MESSAGE));
        }
        Drug drug = input == null ? null : catalogue.find(input.drugKey()).orElse(null);
        if (drug == null) {
            errors.add(new FieldMessage("drugKey", DRUG_MESSAGE));
        }
        Frequency frequency = input == null ? null : Frequency.ofKey(input.frequencyKey()).orElse(null);
        if (frequency == null) {
            errors.add(new FieldMessage("frequencyKey", FREQUENCY_MESSAGE));
        }
        OverrideReason reason = null;
        if (input != null && input.overrideReasonKey() != null) {
            reason = OverrideReason.ofKey(input.overrideReasonKey()).orElse(null);
            if (reason == null) {
                errors.add(new FieldMessage("overrideReasonKey", OVERRIDE_MESSAGE));
            }
        }
        String token = input == null ? null : input.checkToken();
        if (token != null && !TOKEN.matcher(token).matches()) {
            errors.add(new FieldMessage("checkToken", RELOAD_MESSAGE));
        }
        fail(errors);
        return new Order(clientKey, drug, frequency, reason, token);
    }

    /** Fields in the order pin, checkToken. */
    static Verify verify(VerifyOrderInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        String pin = input == null ? null : input.pin();
        if (pin == null || !PIN.matcher(pin).matches()) {
            errors.add(new FieldMessage("pin", PIN_MESSAGE));
        }
        String token = input == null ? null : input.checkToken();
        if (token == null || !TOKEN.matcher(token).matches()) {
            errors.add(new FieldMessage("checkToken", RELOAD_MESSAGE));
        }
        fail(errors);
        return new Verify(pin, token);
    }

    static RejectReason reject(RejectOrderInput input) {
        RejectReason reason = input == null ? null : RejectReason.ofKey(input.reasonKey()).orElse(null);
        if (reason == null) {
            fail(List.of(new FieldMessage("reasonKey", REJECT_MESSAGE)));
        }
        return reason;
    }

    private static void fail(List<FieldMessage> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
    }

    // ---- paging ----

    /** The page size: whole number 1 to 100, default 50; null when the text is not valid. */
    static Integer limit(String text) {
        if (text == null) {
            return DEFAULT_LIMIT;
        }
        if (!WHOLE.matcher(text).matches()) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value < 1 || value > MAX_LIMIT ? null : value;
    }

    /**
     * The opaque cursor of the pharmacist list (ADR-009 D8): base64url, without padding, of the order time in epoch
     * microseconds (8 bytes) and the order id (16 bytes), 32 characters. Not signed: a decoded cursor only moves the
     * start of a page inside the caller's own clinic.
     */
    static String encodeCursor(MedicineOrderRepository.Position position) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, position.orderedAt());
        ByteBuffer bytes = ByteBuffer.allocate(24);
        bytes.putLong(micros).putLong(position.id().getMostSignificantBits())
                .putLong(position.id().getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** The position, or null when the text is not a cursor this server made. */
    static MedicineOrderRepository.Position decodeCursor(String cursor) {
        if (cursor == null || !CURSOR.matcher(cursor).matches()) {
            return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
        long micros = bytes.getLong();
        if (micros < 0) {
            return null;
        }
        return new MedicineOrderRepository.Position(Instant.EPOCH.plus(micros, ChronoUnit.MICROS),
                new UUID(bytes.getLong(), bytes.getLong()));
    }

    // ---- clinical event templates (ADR-009 D7) ----

    static String orderedText(Drug drug, Frequency frequency, OverrideReason reason) {
        String text = "Ordered " + drug.name() + " " + frequency.lowerLabel();
        return reason == null ? text : text + " · override: " + reason.label();
    }

    static String blockedText(Drug drug, String firstHardStopAsLogged) {
        return "Order blocked: " + drug.name() + " · " + firstHardStopAsLogged;
    }

    static String stoppedText(Drug drug) {
        return "Stopped " + drug.name();
    }

    static String verifiedText(Drug drug, boolean withReason) {
        return "Verified " + drug.name() + (withReason ? " · accepted doctor's reason" : "");
    }

    static String rejectedText(Drug drug, RejectReason reason) {
        return "Rejected " + drug.name() + " · " + reason.label();
    }

    static final String OPENED_TEXT = "Opened the clinical record";
}
