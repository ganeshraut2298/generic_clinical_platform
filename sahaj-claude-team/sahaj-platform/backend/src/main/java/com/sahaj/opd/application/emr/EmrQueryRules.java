package com.sahaj.opd.application.emr;

import com.sahaj.platform.api.InvalidInputException;

/**
 * Input rules shared by the EMR searches and the quick-option read (ADR-019 D3, D10). Every message is fixed text;
 * none echoes what was typed. The search text is never logged.
 */
public final class EmrQueryRules {

    public static final int PAGE_SIZE = 20;
    public static final int MAX_QUERY_LENGTH = 100;
    public static final int MAX_PAGE = 500;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    private EmrQueryRules() {
    }

    /** The trimmed search text; null becomes empty; over 100 characters is a 400 validation on field q. */
    public static String searchText(String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.length() > MAX_QUERY_LENGTH) {
            throw new InvalidInputException("q", "Search text must be 100 characters or fewer.");
        }
        return text;
    }

    /** Null means no specialty; anything but the four template codes is a 400 validation on field specialty. */
    public static String specialty(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        if (!EmrKeys.isSpecialtyCode(code)) {
            throw new InvalidInputException("specialty", "Choose one of gp, dental, paeds or homeo.");
        }
        return code;
    }

    /** Null means page 0; outside 0 to 500 is a 400 validation on field page. */
    public static int page(Integer page) {
        int value = page == null ? 0 : page;
        if (value < 0 || value > MAX_PAGE) {
            throw new InvalidInputException("page", "Page must be between 0 and 500.");
        }
        return value;
    }

    /** Null means 10; outside 1 to 50 is a 400 validation on field limit. */
    public static int limit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw new InvalidInputException("limit", "Limit must be between 1 and 50.");
        }
        return value;
    }
}
