package com.sahaj.ipd.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SAH-32 the wristband check of the Give sheet (ADR-011 D4). Pure Java.
 *
 * <p>The box holds what a keyboard-type barcode scanner types or what the nurse types. Both the box and the patient's
 * UHID are normalised the same way (NFKC; every white-space, control and format character removed, so a scanner's
 * trailing CR, LF or Tab and text-direction marks vanish; upper case with {@link Locale#ROOT}) and must then be
 * equal. A box longer than 200 UTF-16 units is not normalised and never matches. The box is free text: it is never
 * logged, and only text in the shape of a UHID may be written to the event log ({@link #scanForEvent}).
 */
public final class WristbandCheck {

    /** A box longer than this is a mismatch, not normalised. */
    public static final int MAX_BOX = 200;
    /** What the event log shows for a scan that does not look like a UHID. */
    public static final String NOT_A_UHID = "text that is not a UHID";

    /**
     * What patient.next_uhid() produces: a prefix of 2 to 6 capital letters (today always "PT"), a hyphen and the
     * zero-padded sequence number (at least 5 digits). Nine digits is a billion patients in one clinic. The shape is
     * kept short on purpose: a 10-digit mobile number or a 14-digit ABHA number can never pass as a UHID, and the
     * longest match (16 characters) is far inside the 40 characters of identity_scan (review R2, S6). A UHID with
     * more digits is only shown as "text that is not a UHID" in a deny entry; the check itself ({@link #check})
     * compares any UHID.
     */
    private static final Pattern UHID_SHAPE = Pattern.compile("^[A-Z]{2,6}-[0-9]{5,9}$");

    private WristbandCheck() {
    }

    /** What the check made of the box. */
    public enum Result { EMPTY, MISMATCH, MATCH }

    /** Null or an empty box after normalisation is EMPTY; a box over 200 units is MISMATCH; otherwise compared. */
    public static Result check(String box, String uhid) {
        if (box == null) {
            return Result.EMPTY;
        }
        if (box.length() > MAX_BOX) {
            return Result.MISMATCH;
        }
        String scan = normalise(box);
        if (scan.isEmpty()) {
            return Result.EMPTY;
        }
        return uhid != null && scan.equals(normalise(uhid)) ? Result.MATCH : Result.MISMATCH;
    }

    /** NFKC, white-space, control and format characters removed, upper case ({@link Locale#ROOT}). Null gives "". */
    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean drop = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || type == Character.CONTROL
                    || type == Character.FORMAT;
            if (!drop) {
                kept.appendCodePoint(cp);
            }
        });
        return kept.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * The text for the deny entry "scanned <scan>": the normalised box when it has the UHID shape
     * ({@code ^[A-Z]{2,6}-[0-9]{5,9}$}, so at most 16 characters), otherwise the fixed words
     * {@link #NOT_A_UHID}. A typed name or note never reaches the event log.
     */
    public static String scanForEvent(String box) {
        if (box == null || box.length() > MAX_BOX) {
            return NOT_A_UHID;
        }
        String scan = normalise(box);
        return UHID_SHAPE.matcher(scan).matches() ? scan : NOT_A_UHID;
    }
}
