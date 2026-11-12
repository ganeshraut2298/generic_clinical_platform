package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.regex.Pattern;

/** The key formats and the four template keys of SAH-43 and SAH-44 (ADR-019 D4). */
public final class EmrKeys {

    /** The four template specialties, in the order the API lists them. */
    public static final List<String> TEMPLATE_KEYS = List.of("gp", "dental", "paeds", "homeo");

    public static final String DEFAULT_TEMPLATE_KEY = "gp";

    private static final Pattern TEMPLATE_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,19}$");
    private static final Pattern SECTION_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    private EmrKeys() {
    }

    public static boolean isWellFormedTemplateKey(String key) {
        return key != null && TEMPLATE_KEY.matcher(key).matches();
    }

    public static boolean isWellFormedSectionKey(String key) {
        return key != null && SECTION_KEY.matcher(key).matches();
    }

    public static boolean isSpecialtyCode(String code) {
        return code != null && TEMPLATE_KEYS.contains(code);
    }
}
