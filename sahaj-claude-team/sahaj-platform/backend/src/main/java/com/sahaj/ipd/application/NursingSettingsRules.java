package com.sahaj.ipd.application;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SAH-76-6 (ADR-027 D8) the rules of the nursing settings and the ward profiles that are not database work: the ranges of
 * every number, the profile name and the profile key made from the name. Pure Java. The messages are the contract's. No
 * Java code branches on a profile key or name.
 */
final class NursingSettingsRules {

    static final int MAX_PROFILES = 20;
    static final int NAME_MAX = 60;
    static final int DEFAULT_SORT = 100;
    static final String NAME_MESSAGE = "Enter a profile name.";
    static final String ON_OFF_MESSAGE = "Choose On or Off.";
    static final String PROFILE_MESSAGE = "Choose a profile from the list.";
    private static final Pattern KEY = Pattern.compile("^[a-z0-9-]{2,40}$");
    private static final int KEY_MAX = 40;

    private NursingSettingsRules() {
    }

    /** "Enter a number from 1 to 180." */
    static String range(int min, int max) {
        return "Enter a number from " + min + " to " + max + ".";
    }

    /** True for a text that is a valid profile key (path variable and body). */
    static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** The clinic settings checked; every failing field is reported together, in body order. */
    static NursingSettings settings(SettingsInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Boolean verification = bool(input == null ? null : input.medVerificationRequired());
        if (verification == null) {
            errors.add(new FieldMessage("medVerificationRequired", ON_OFF_MESSAGE));
        }
        Integer before = number(errors, "doseWindowBeforeMinutes", input == null ? null : input.doseWindowBeforeMinutes(),
                1, 180);
        Integer after = number(errors, "doseWindowAfterMinutes", input == null ? null : input.doseWindowAfterMinutes(),
                1, 180);
        Integer stat = number(errors, "statWindowMinutes", input == null ? null : input.statWindowMinutes(), 1, 120);
        Integer unack = number(errors, "unackFlagMinutes", input == null ? null : input.unackFlagMinutes(), 1, 240);
        Integer unackStat = number(errors, "unackFlagStatMinutes", input == null ? null : input.unackFlagStatMinutes(),
                1, 60);
        Integer undo = number(errors, "undoSeconds", input == null ? null : input.undoSeconds(), 1, 10);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        return new NursingSettings(verification, before, after, stat, unack, unackStat, undo);
    }

    /** A profile checked: the name, the verification, the vitals interval and the two optional dose windows. */
    static ProfileValues profile(ProfileInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        String name = name(input == null ? null : input.name());
        if (name == null) {
            errors.add(new FieldMessage("name", NAME_MESSAGE));
        }
        Boolean verification = bool(input == null ? null : input.medVerificationRequired());
        if (verification == null) {
            errors.add(new FieldMessage("medVerificationRequired", ON_OFF_MESSAGE));
        }
        Integer vitals = number(errors, "vitalsEveryMinutes", input == null ? null : input.vitalsEveryMinutes(), 15,
                1440);
        Integer before = optional(errors, "doseWindowBeforeMinutes",
                input == null ? null : input.doseWindowBeforeMinutes(), 1, 180);
        Integer after = optional(errors, "doseWindowAfterMinutes", input == null ? null : input.doseWindowAfterMinutes(),
                1, 180);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors, true);
        }
        return new ProfileValues(name, verification, vitals, before, after);
    }

    /** The checked values of a profile. */
    record ProfileValues(String name, boolean medVerificationRequired, int vitalsEveryMinutes, Integer doseWindowBeforeMin,
                         Integer doseWindowAfterMin) {
    }

    /** The name after NFC and trimming, 1 to 60 characters, no control or text-direction character; else null. */
    static String name(String raw) {
        if (raw == null) {
            return null;
        }
        String text = IpdText.nfc(raw);
        if (IpdText.hasControlOrBidi(text)) {
            return null;
        }
        String stripped = IpdText.strip(text);
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > NAME_MAX
                || !IpdText.hasLetterOrDigit(stripped)) {
            return null;
        }
        return stripped;
    }

    /**
     * The key made from a name: lower-case letters and digits, hyphens between words (accents removed), 2 to 40
     * characters. A name with no usable letter gives "profile". When the key is taken a number is added (-2, -3, ...), the
     * base cut so the result stays within 40 characters.
     */
    static String key(String name, Predicate<String> taken) {
        String base = slug(name);
        String key = base;
        int n = 2;
        while (taken.test(key)) {
            String suffix = "-" + n++;
            key = (base.length() + suffix.length() > KEY_MAX ? base.substring(0, KEY_MAX - suffix.length()) : base)
                    + suffix;
        }
        return key;
    }

    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > KEY_MAX) {
            slug = slug.substring(0, KEY_MAX).replaceAll("-+$", "");
        }
        return slug.length() < 2 ? "profile" : slug;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Integer number(List<FieldMessage> errors, String field, Object value, int min, int max) {
        Integer n = whole(value);
        if (n == null || n < min || n > max) {
            errors.add(new FieldMessage(field, range(min, max)));
            return null;
        }
        return n;
    }

    private static Integer optional(List<FieldMessage> errors, String field, Object value, int min, int max) {
        return value == null ? null : number(errors, field, value, min, max);
    }

    /** A JSON number without a fraction, else null (a text such as "60" is not a number here). */
    private static Integer whole(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? Integer.valueOf((int) (long) l) : null;
        }
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        return null;
    }
}
