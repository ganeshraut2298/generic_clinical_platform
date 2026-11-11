package com.sahaj.ipd.application;

import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.DischargeViews.TemplateOption;
import com.sahaj.ipd.application.DischargeViews.TypeOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** SAH-27 the fixed lists and small rules of discharge, in one place so the sheet, setup and tests agree. */
final class DischargeRules {

    static final String DEATH = "death";
    static final String NORMAL = "normal";
    static final String FOLLOW_UP_PREFIX = "Review after discharge · ";
    static final int REASON_MAX = 200;

    static final List<TypeOption> TYPES = List.of(new TypeOption("normal", "Normal"),
            new TypeOption("lama", "On request (LAMA)"), new TypeOption("referred", "Referred"),
            new TypeOption("death", "Death"));
    static final List<TemplateOption> TEMPLATES = List.of(new TemplateOption("general", "General medicine"),
            new TemplateOption("surgical", "Surgical"), new TemplateOption("maternity", "Maternity"),
            new TemplateOption("daycare", "Day-care"));
    static final Set<String> TYPE_KEYS = Set.of("normal", "lama", "referred", "death");
    static final Set<String> TEMPLATE_KEYS = Set.of("general", "surgical", "maternity", "daycare");

    /** Who may open the sheet and the setup page. */
    static final Set<String> READ_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR");
    /** OQ-27-1: who may sign the summary. */
    static final Set<String> SIGN_ROLES = Set.of("DOCTOR", "OWNER");
    /** OQ-27-1: who may complete a discharge. */
    static final Set<String> DISCHARGE_ROLES = Set.of("OWNER", "FRONT_DESK");

    private DischargeRules() {
    }

    /**
     * The template the sheet starts with: the one chosen in Discharge setup; when none is chosen, Maternity for a
     * maternity admission, Day-care for a day-care admission, Surgical for any other admission with a package (SAH-29,
     * mockup {@code ipdis}), otherwise General medicine.
     */
    static String defaultTemplate(String settingsTemplate, String admissionType, boolean hasPackage) {
        if (settingsTemplate != null) {
            return settingsTemplate;
        }
        if ("maternity".equals(admissionType)) {
            return "maternity";
        }
        if ("daycare".equals(admissionType)) {
            return "daycare";
        }
        if (hasPackage) {
            return "surgical";
        }
        return "general";
    }

    /** True when the latest sign-off was made on {@code today} (Asia/Kolkata); the caller has checked ADMITTED. */
    static boolean goingHomeToday(SignoffRow latest, LocalDate today) {
        return latest != null && latest.signedAt().atZone(RunningBill.IST).toLocalDate().equals(today);
    }

    static LocalDate istDate(Instant at) {
        return at.atZone(RunningBill.IST).toLocalDate();
    }

    /** "Review after discharge · <reason>" cut to 200 Unicode code points (the table counts characters, not UTF-16 units). */
    static String followUpReason(String admissionReason) {
        String full = FOLLOW_UP_PREFIX + admissionReason;
        if (full.codePointCount(0, full.length()) <= REASON_MAX) {
            return full;
        }
        return full.substring(0, full.offsetByCodePoints(0, REASON_MAX));
    }

    /** The label of a discharge type in lower case, as the SAH-27 toast and the escalation event write it. */
    static String typeLabelLower(String typeKey) {
        for (TypeOption option : TYPES) {
            if (option.key().equals(typeKey)) {
                return option.label().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return typeKey;
    }

    /** The outcome of the final bill: positive balance collected, negative refunded, zero settled. */
    static String outcome(long balancePaise) {
        if (balancePaise > 0) {
            return "collected";
        }
        return balancePaise < 0 ? "refunded" : "settled";
    }
}
