package com.sahaj.ipd.domain.card;

/**
 * SAH-37 the IPD free-text rule (V12), handed in by the application so that the domain package keeps no dependency on
 * the application package: {@code IpdText.hasControlOrBidi} and {@code IpdText.strip}.
 */
public interface TextRules {

    /** True when the (NFC) text holds a control, invisible or text-direction character. */
    boolean hasControlOrBidi(String text);

    /** The text with white space and space separators removed at both ends. */
    String strip(String text);
}
