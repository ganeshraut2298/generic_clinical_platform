package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.List;
import java.util.UUID;

/**
 * Path ids and query parameters arrive as text and are read here, after the gate has answered (docs/api/pharmacy.yaml,
 * general rule 2: module off 403, access level 403, then 400 for a path id or parameter, then 404 or the store guard). A
 * path id that is not a UUID is 400 {@code bad-request}; a wrong query value is 400 {@code validation} with a field error.
 * The submitted value is never echoed.
 */
final class PharmacyParams {

    private PharmacyParams() {
    }

    /** A path id. */
    static UUID pathId(String raw) {
        if (raw == null) {
            throw PharmacyProblemException.badRequest();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            throw PharmacyProblemException.badRequest();
        }
    }

    /** {@code true} or {@code false}; absent or empty means {@code defaultValue}. */
    static boolean bool(String name, String raw, boolean defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw invalid(name, "Choose a valid option");
    }

    /** An optional UUID query value; absent or empty means not given, anything else that is not a UUID is 400. */
    static UUID optionalUuid(String name, String raw, String message) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            throw invalid(name, message);
        }
    }

    /** An optional store role; absent or empty means no filter. */
    static StoreRole optionalStoreRole(String name, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return StoreRole.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid(name, "Choose a valid option");
        }
    }

    /** A whole number query value between {@code min} and {@code max}; absent or empty means {@code defaultValue}. */
    static int intValue(String name, String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // falls through to the field error below
        }
        throw invalid(name, "Enter a whole number from " + min + " to " + max);
    }

    /** An optional value of an enum; absent or empty means no filter. */
    static <E extends Enum<E>> E optionalEnum(String name, String raw, Class<E> type) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid(name, "Choose a valid option");
        }
    }

    /** A whole number held as a decimal by the body reader (so 2.5 is refused, not cut to 2); null when it is not one in range. */
    static Integer wholeNumber(java.math.BigDecimal value, int min, int max) {
        if (value == null || value.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (value.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || value.compareTo(java.math.BigDecimal.valueOf(max)) > 0) {
            return null;
        }
        return value.intValueExact();
    }

    private static InvalidFieldsException invalid(String name, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(name, message)));
    }
}
