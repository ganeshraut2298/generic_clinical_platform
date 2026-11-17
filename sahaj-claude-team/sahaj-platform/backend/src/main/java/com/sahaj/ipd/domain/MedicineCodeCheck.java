package com.sahaj.ipd.domain;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * SAH-76-4 (ADR-027 D7, OQ-76-16): the medicine code box of the Give sheet. Pure Java.
 *
 * <p>The box is typed or sent by a keyboard-type scanner. It is normalised as the wristband (NFKC, white-space, control
 * and format characters removed, upper case) and compared with the codes of the ordered drug in the drug master. A box
 * over {@value #MAX_BOX} characters never matches. The box is free text: it is never logged, and only a normalised
 * code that is a code of the drug master may be written to the event log ({@link #codeForEvent}).
 */
public final class MedicineCodeCheck {

    /** A box longer than this is a mismatch, not normalised. */
    public static final int MAX_BOX = 100;

    /** What the event log shows for a scan that is not a code of the drug master. */
    public static final String UNKNOWN_CODE = "a code not in the medicine list";

    /** The shape of a stored code (ipd.drug_code): 4 to 64 of A-Z 0-9 and hyphen. */
    private static final Pattern CODE_SHAPE = Pattern.compile("^[A-Z0-9-]{4,64}$");

    private MedicineCodeCheck() {
    }

    /** What the check made of the box. */
    public enum Result { EMPTY, MISMATCH, MATCH }

    /** Null or empty after normalisation is EMPTY; over the limit is MISMATCH; otherwise compared with the drug's codes. */
    public static Result check(String box, Collection<String> codesOfOrderedDrug) {
        if (box == null) {
            return Result.EMPTY;
        }
        if (box.length() > MAX_BOX) {
            return Result.MISMATCH;
        }
        String code = normalise(box);
        if (code.isEmpty()) {
            return Result.EMPTY;
        }
        return codesOfOrderedDrug.contains(code) ? Result.MATCH : Result.MISMATCH;
    }

    /** The same normalisation as the wristband. */
    public static String normalise(String box) {
        return WristbandCheck.normalise(box);
    }

    /** True when the (normalised) text could be a stored code: the repository is only asked for such texts. */
    public static boolean looksLikeCode(String normalised) {
        return normalised != null && CODE_SHAPE.matcher(normalised).matches();
    }

    /**
     * The text for the deny entry "entered code": the normalised code when it is a code of the drug master
     * ({@code known}), otherwise the fixed words {@link #UNKNOWN_CODE}. A typed mobile number or note never reaches the log.
     */
    public static String codeForEvent(String box, boolean known) {
        if (!known || box == null || box.length() > MAX_BOX) {
            return UNKNOWN_CODE;
        }
        return normalise(box);
    }
}
