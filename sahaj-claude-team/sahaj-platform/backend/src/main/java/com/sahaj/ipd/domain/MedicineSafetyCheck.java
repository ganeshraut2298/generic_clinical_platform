package com.sahaj.ipd.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SAH-31 the one safety-check rule class (ADR-009 D2). Pure Java, no Spring and no I/O. The order form, the verify,
 * the pharmacist list and the EMR sheet all call it, so the four never disagree.
 *
 * <p>Input: the medicine catalogue, the patient's allergy text (free text from the patient file), the live orders of
 * the admission (Pharmacist check and Active, in chart order, without the order being checked) and the candidate
 * medicine. Output: {@link OrderCheck}, with the findings in a fixed order (the "first message" is deterministic) and
 * a 22-character fingerprint of the allergy status and the findings.
 *
 * <p>The rules, the keywords, the texts and the three interactions are the demo content of the mockup (OQ-31-1,
 * OQ-31-4, OQ-31-6): not clinically validated, blocking for go-live. Keyword matching of free text is a stop-gap
 * that goes away with structured allergies.
 *
 * <p>The allergy text, the medicine names and the findings are clinical data: {@code toString} of every record here
 * leaves them out, so a log line can never hold them.
 */
public final class MedicineSafetyCheck {

    public static final String PENICILLIN = "penicillin";
    public static final String SULFA = "sulfa";
    public static final String CEPHALOSPORIN = "cephalosporin";

    static final String CROSS_REACTION_TEXT =
            "Penicillin allergy: possible cross-reaction with cephalosporins. Confirm what reaction the patient had.";
    static final String NOT_RECORDED_TEXT =
            "Allergies are not recorded. Ask the patient and record them before the first dose.";

    /** The chip of the identity bar (AC2). */
    public enum AllergyStatus { NONE_KNOWN, NOT_RECORDED, RECORDED }

    /** HARD_STOP for a hard stop; a warning is Major or Moderate. */
    public enum Severity {
        HARD_STOP("Hard stop"), MAJOR("Major"), MODERATE("Moderate");

        private final String word;

        Severity(String word) {
            this.word = word;
        }

        /** The word shown before a warning text: "Major: ...". */
        public String word() {
            return word;
        }
    }

    /** Which rule found it. The first three are hard stops, the others warnings. */
    public enum FindingCode {
        ALLERGY_PENICILLIN, ALLERGY_SULFA, DUPLICATE, ALLERGY_CROSS_CEPHALOSPORIN, ALLERGIES_NOT_RECORDED, INTERACTION
    }

    /**
     * One finding. {@code text} may hold the patient's allergy text (screen only). {@code loggedText} is the wording for
     * the clinical event of a blocked order: the class word replaces the free text; null for a warning.
     */
    public record Finding(FindingCode code, Severity severity, String otherDrugKey, String text, String loggedText) {

        public boolean hardStop() {
            return severity == Severity.HARD_STOP;
        }

        /** "Major: text" or "Moderate: text", the line stored with an order and shown in the queue. */
        public String warningLine() {
            return severity.word() + ": " + text;
        }

        @Override
        public String toString() {
            return "Finding[code=" + code + ", severity=" + severity + "]";
        }
    }

    /** The result of one check. */
    public record OrderCheck(AllergyStatus allergyStatus, List<Finding> hardStops, List<Finding> warnings,
                             String checkToken) {

        public OrderCheck {
            hardStops = List.copyOf(hardStops);
            warnings = List.copyOf(warnings);
        }

        /** Any hard stop: the order cannot be placed. */
        public boolean blocked() {
            return !hardStops.isEmpty();
        }

        /** No hard stop but warnings: the doctor must give a reason. */
        public boolean needsReason() {
            return hardStops.isEmpty() && !warnings.isEmpty();
        }

        /** The wording of the first hard stop for the clinical event, or null when there is none. */
        public String firstHardStopLogged() {
            return hardStops.isEmpty() ? null : hardStops.get(0).loggedText();
        }

        @Override
        public String toString() {
            return "OrderCheck[allergyStatus=" + allergyStatus + ", hardStops=" + hardStops.size() + ", warnings="
                    + warnings.size() + "]";
        }
    }

    private final DrugCatalogue catalogue;

    public MedicineSafetyCheck(DrugCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * The chip for an allergy text, first that applies: a known allergy class is named (RECORDED); the text says
     * "none known" or is one of the none words of the shared list of patient.api (none, nil, nka, nkda, none known,
     * no known allergy, no known allergies, no known drug allergy, no known drug allergies; whole text, any case,
     * trailing full stops ignored) (NONE_KNOWN, H20: "No known allergy" written at sign is no allergy); the text is null, blank,
     * says "not recorded" or is exactly "unknown", "not known" or "?" in any case (NOT_RECORDED); otherwise RECORDED.
     */
    public static AllergyStatus allergyStatus(String allergyText) {
        String lower = normalised(allergyText);
        if (classNamed(lower, PENICILLIN) || classNamed(lower, SULFA)) {
            return AllergyStatus.RECORDED;
        }
        if (lower.contains("none known") || com.sahaj.patient.api.AllergyStatus.isNoneWord(lower)) {
            return AllergyStatus.NONE_KNOWN;
        }
        if (lower.isEmpty() || lower.contains("not recorded") || NOT_RECORDED_TEXTS.contains(lower)) {
            return AllergyStatus.NOT_RECORDED;
        }
        return AllergyStatus.RECORDED;
    }

    /**
     * Checks {@code candidate} against the allergy text and the live orders.
     *
     * @param liveDrugKeys the drug keys of the live orders (Pharmacist check and Active) in chart order, without the
     *                     order being checked
     */
    public OrderCheck check(Drug candidate, String allergyText, List<String> liveDrugKeys) {
        AllergyStatus status = allergyStatus(allergyText);
        String lower = normalised(allergyText);
        String shown = allergyText == null ? "" : strip(allergyText);
        List<Finding> all = new ArrayList<>();

        // 1 and 1b: the allergy classes in the order penicillin, sulfa.
        if (classNamed(lower, PENICILLIN)) {
            if (candidate.has(PENICILLIN)) {
                all.add(new Finding(FindingCode.ALLERGY_PENICILLIN, Severity.HARD_STOP, null,
                        "Documented allergy: " + shown + ". " + candidate.name() + " is a penicillin.",
                        "Documented penicillin allergy. " + candidate.name() + " is a penicillin."));
            }
            if (candidate.has(CEPHALOSPORIN)) {
                all.add(new Finding(FindingCode.ALLERGY_CROSS_CEPHALOSPORIN, Severity.MODERATE, null,
                        CROSS_REACTION_TEXT, null));
            }
        }
        if (classNamed(lower, SULFA) && candidate.has(SULFA)) {
            all.add(new Finding(FindingCode.ALLERGY_SULFA, Severity.HARD_STOP, null,
                    "Documented allergy: " + shown + ". " + candidate.name() + " is a sulfa drug.",
                    "Documented sulfa allergy. " + candidate.name() + " is a sulfa drug."));
        }
        // 2: allergies not recorded.
        if (status == AllergyStatus.NOT_RECORDED) {
            all.add(new Finding(FindingCode.ALLERGIES_NOT_RECORDED, Severity.MODERATE, null, NOT_RECORDED_TEXT,
                    null));
        }
        // 3: the same medicine is already live.
        if (liveDrugKeys.contains(candidate.key())) {
            String text = candidate.name()
                    + " is already on the chart. Change the existing order instead of adding a second one.";
            all.add(new Finding(FindingCode.DUPLICATE, Severity.HARD_STOP, candidate.key(), text, text));
        }
        // 4: interactions, each live order in chart order against each rule in rule order.
        for (String liveKey : liveDrugKeys) {
            Drug onChart = catalogue.find(liveKey)
                    .orElseThrow(() -> new IllegalStateException("A live order names a medicine not in the catalogue"));
            for (DrugInteraction rule : catalogue.interactions()) {
                if (rule.matches(candidate, onChart)) {
                    all.add(new Finding(FindingCode.INTERACTION, rule.severity(), liveKey,
                            rule.text() + " (" + onChart.name() + " is on the chart).", null));
                }
            }
        }

        List<Finding> hardStops = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        lines.add(status.name());
        for (Finding f : all) {
            (f.hardStop() ? hardStops : warnings).add(f);
            lines.add(f.code() + "|" + f.severity() + "|" + (f.otherDrugKey() == null ? "-" : f.otherDrugKey()));
        }
        return new OrderCheck(status, hardStops, warnings, Fingerprint.of(lines));
    }

    // ---- allergy text ----

    /**
     * The keywords of the two classes (security review S1): spelling variants, brand names and abbreviations. The
     * stem {@code cillin} covers every "-cillin" word (penicillin, ampicillin, cloxacillin, amoxycillin,
     * flucloxacillin). The stems are deliberately wide (a false hit only adds a hard stop, the safer direction);
     * the list is demo content and still needs clinical sign-off (OQ-31-1, OQ-31-4).
     */
    private static final List<String> PENICILLIN_KEYWORDS =
            List.of("penicillin", "cillin", "amoxic", "amoxy", "augmentin", "pcn");
    private static final List<String> SULFA_KEYWORDS =
            List.of("sulf", "sulph", "cotrimox", "co-trimox", "septran", "bactrim");

    /** The whole texts that say nothing about allergies (security review S1): they count as not recorded. */
    private static final List<String> NOT_RECORDED_TEXTS = List.of("unknown", "not known", "?");

    private static boolean classNamed(String lower, String drugClass) {
        return anyKeyword(lower, PENICILLIN.equals(drugClass) ? PENICILLIN_KEYWORDS : SULFA_KEYWORDS);
    }

    private static boolean anyKeyword(String lower, List<String> keywords) {
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** NFC, stripped at both ends, lower case with the root locale; empty for null. */
    private static String normalised(String text) {
        if (text == null) {
            return "";
        }
        return strip(Normalizer.normalize(text, Normalizer.Form.NFC)).toLowerCase(Locale.ROOT);
    }

    /** White space and Unicode space separators removed at both ends (ADR-007 R6 stripping). */
    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && blank(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && blank(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }
}
