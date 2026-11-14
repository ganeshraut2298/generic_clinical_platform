package com.sahaj.opd.domain.emr.specialty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Chemotherapy order rules (SAH-66-1, SAH-66-2, SAH-66-3; rule C-8). Every number is "value from the EMR mockup, accepted
 * by the human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}), except the 5 percent deviation flag, which is
 * an analyst addition (ADR-023 D4). The doses, the lifetime dose and the ten checks are worked out here and recomputed by
 * the server on every sheet save, record draft save and send; the client's numbers are never trusted.
 */
public final class ChemoRules {

    /** C-8: the dose levels in percent. */
    public static final List<Integer> LEVELS = List.of(100, 90, 80, 75);
    /** C-8: the lifetime doxorubicin limit in mg/m2; the check passes at or below it. */
    public static final double LIFETIME_LIMIT_MG_M2 = 450.0;
    /** C-8: the lifetime dose is shown red above this (mg/m2). */
    public static final double LIFETIME_RED_ABOVE_MG_M2 = 400.0;
    /** Analyst addition: a final dose more than this percent from the calculated dose is flagged. */
    public static final double DEVIATION_PCT = 5.0;
    /** C-8: the reasons for a reduced level. */
    public static final List<String> REDUCTION_REASONS = List.of("LOW_NEUTROPHILS_LAST_CYCLE", "FEVER_LOW_COUNTS",
            "GRADE_3_SIDE_EFFECT", "NEUROPATHY", "LIVER_FUNCTION", "FRAILTY");
    /** The scheme states; SELF_PAY is not drawn (ADR-023 H5) and passes. */
    public static final Set<String> SCHEMES = Set.of("APPROVED", "PREAUTH_RAISED", "NOT_IN_PACKAGE", "SELF_PAY");

    private ChemoRules() {
    }

    public record Drug(String drugKey, String name, double dosePerM2, int infusionMinutes, int vialStepMg,
                       boolean vesicant, boolean anthracycline, String note) {
    }

    public record Premed(String key, String text, int minutes) {
    }

    public record Protocol(String key, String label, String subLine, int intervalDays, String emeticRisk,
                           boolean suggestedOnChange, List<Drug> drugs, List<Premed> premeds) {

        public boolean hasVesicant() {
            return drugs.stream().anyMatch(Drug::vesicant);
        }

        public Drug drug(String drugKey) {
            return drugs.stream().filter(d -> d.drugKey().equals(drugKey)).findFirst().orElse(null);
        }
    }

    // ---- doses ----

    /** One drug of the order: calculated, rounded to the vial step and final (edited or rounded). */
    public record DoseLine(String drugKey, double calculatedMg, double roundedMg, double finalMg, boolean edited,
                           boolean moreThan5Pct) {
    }

    /** calculated = dose per m2 x BSA used x level / 100, one decimal. No BSA gives no dose (null). */
    public static Double calculatedMg(Drug drug, Double bsaUsed, int levelPct) {
        if (bsaUsed == null) {
            return null;
        }
        return BigDecimal.valueOf(drug.dosePerM2()).multiply(BigDecimal.valueOf(bsaUsed))
                .multiply(BigDecimal.valueOf(levelPct)).divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** rounded = round(calculated / vial step) x vial step, to the nearest whole vial step. */
    public static double roundedMg(double calculatedMg, int vialStepMg) {
        return BigDecimal.valueOf(calculatedMg).divide(BigDecimal.valueOf(vialStepMg), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(vialStepMg)).doubleValue();
    }

    /** True when the final dose is more than 5 percent from the calculated dose ("More than 5% from calculated"). */
    public static boolean moreThan5Pct(double finalMg, double calculatedMg) {
        return calculatedMg > 0 && Math.abs(finalMg - calculatedMg) > calculatedMg * DEVIATION_PCT / 100.0;
    }

    /** The dose line of a drug; {@code editedFinalMg} null takes the rounded dose. Null when there is no BSA. */
    public static DoseLine doseLine(Drug drug, Double bsaUsed, int levelPct, Double editedFinalMg) {
        Double calculated = calculatedMg(drug, bsaUsed, levelPct);
        if (calculated == null) {
            return null;
        }
        double rounded = roundedMg(calculated, drug.vialStepMg());
        boolean edited = editedFinalMg != null && Double.compare(editedFinalMg, rounded) != 0;
        double finalMg = editedFinalMg == null ? rounded : editedFinalMg;
        return new DoseLine(drug.drugKey(), calculated, rounded, finalMg, edited, moreThan5Pct(finalMg, calculated));
    }

    /** Decision Give, reduce dose pre-selects the next level down; the lowest level stays. */
    public static int nextLevelDown(int levelPct) {
        int index = LEVELS.indexOf(levelPct);
        return index < 0 || index == LEVELS.size() - 1 ? levelPct : LEVELS.get(index + 1);
    }

    // ---- lifetime anthracycline dose ----

    /** This cycle's anthracycline mg/m2 = the sum of the dose per m2 x level / 100 of the anthracycline drugs. */
    public static double thisCycleMgM2(Protocol protocol, int levelPct) {
        double sum = 0;
        for (Drug drug : protocol.drugs()) {
            if (drug.anthracycline()) {
                sum += drug.dosePerM2() * levelPct / 100.0;
            }
        }
        return OncologyRules.round(sum, 2);
    }

    public record Lifetime(double earlierMgM2, double thisCycleMgM2, double totalMgM2, boolean red, boolean withinLimit) {
    }

    /** Earlier (outside this clinic plus sent here) + this cycle; red above 400, within limit at 450 or less. */
    public static Lifetime lifetime(double earlierMgM2, double thisCycleMgM2) {
        double total = OncologyRules.round(earlierMgM2 + thisCycleMgM2, 2);
        return new Lifetime(OncologyRules.round(earlierMgM2, 2), thisCycleMgM2, total,
                total > LIFETIME_RED_ABOVE_MG_M2, total <= LIFETIME_LIMIT_MG_M2);
    }

    // ---- the ten safety checks ----

    /** What the checks read. */
    public record CheckFacts(String decision, boolean countOrOrganFail, boolean fever, Boolean labsFresh,
                             boolean weightAndHeightRecorded, boolean hasAnthracycline, Lifetime lifetime, int levelPct,
                             boolean reductionReasonGiven, boolean allergyConfirmed, boolean earlierSentOfProtocol,
                             boolean consentNewSigned, String scheme, boolean hasVesicant, Boolean lineReady) {
    }

    /** One check; {@code action} is the button the sheet offers (GO, CONFIRM, UNDO, MARK_SIGNED, RAISE_PREAUTH) or null. */
    public record Check(String key, boolean passed, String detail, String action) {
    }

    /** True for the two Give decisions. */
    public static boolean isGive(String decision) {
        return "GIVE_AS_PLANNED".equals(decision) || "GIVE_REDUCED".equals(decision);
    }

    /** The nine checks as drawn, and the tenth "line ready for vesicant" only when the protocol has a vesicant drug (H4). */
    public static List<Check> checks(CheckFacts f) {
        List<Check> checks = new ArrayList<>();
        boolean decisionOk = isGive(f.decision());
        checks.add(new Check("DECISION_RECORDED", decisionOk, f.decision() == null
                ? "No decision yet. Finish the assessment and confirm today's decision."
                : decisionOk ? "Decision recorded: give" : "Decision is not a give decision", decisionOk ? null : "GO"));
        boolean fit = !f.countOrOrganFail() && !f.fever();
        checks.add(new Check("FIT_TO_TREAT", fit, fit ? "Counts, organ tests OK · no fever"
                : f.fever() && !f.countOrOrganFail() ? "Fever · rule out infection before chemo"
                : "A count or organ test is out of range", fit ? null : "GO"));
        boolean fresh = Boolean.TRUE.equals(f.labsFresh());
        checks.add(new Check("LABS_72H", fresh, f.labsFresh() == null ? "Labs not recorded"
                : fresh ? "Labs drawn within 72 hours" : "Labs older than 72 hours", fresh ? null : "GO"));
        checks.add(new Check("BSA_TODAY", f.weightAndHeightRecorded(), f.weightAndHeightRecorded()
                ? "BSA from today's weight" : "Record today's weight and height", f.weightAndHeightRecorded() ? null
                : "GO"));
        boolean cumulativeOk = !f.hasAnthracycline() || (f.lifetime() != null && f.lifetime().withinLimit());
        checks.add(new Check("CUMULATIVE_DOSE", cumulativeOk, !f.hasAnthracycline() ? "No lifetime limit"
                : cumulativeOk ? "Doxorubicin under 450 mg/m²" : "Doxorubicin above 450 mg/m²", null));
        boolean reasonOk = f.levelPct() == 100 || f.reductionReasonGiven();
        checks.add(new Check("REDUCTION_REASON", reasonOk, reasonOk ? (f.levelPct() == 100 ? "Full dose"
                : "Reason for reduction recorded") : "Pick a reason above", null));
        checks.add(new Check("ALLERGIES_REVIEWED", f.allergyConfirmed(), f.allergyConfirmed()
                ? "No known drug allergy · confirmed" : "Confirm the drug allergies", f.allergyConfirmed() ? null
                : "CONFIRM"));
        boolean consentOk = f.earlierSentOfProtocol() || f.consentNewSigned();
        checks.add(new Check("CONSENT_REGIMEN", consentOk, consentOk ? "Consent for this regimen"
                : "New consent needed for this regimen", consentOk ? null : "MARK_SIGNED"));
        boolean schemeOk = "APPROVED".equals(f.scheme()) || "PREAUTH_RAISED".equals(f.scheme())
                || "SELF_PAY".equals(f.scheme());
        checks.add(new Check("SCHEME_APPROVAL", schemeOk, f.scheme() == null ? "Choose the scheme state"
                : switch (f.scheme()) {
                    case "APPROVED" -> "Scheme or TPA approved";
                    case "PREAUTH_RAISED" -> "Pre-authorisation raised";
                    case "SELF_PAY" -> "Self-pay · not needed";
                    default -> "Not in the scheme package";
                }, schemeOk ? null : "RAISE_PREAUTH"));
        if (f.hasVesicant()) {
            boolean lineOk = Boolean.TRUE.equals(f.lineReady());
            checks.add(new Check("LINE_VESICANT", lineOk, f.lineReady() == null ? "Record the line"
                    : lineOk ? "Line ready for vesicant" : "Port: no blood return yet", lineOk ? null : "GO"));
        }
        return checks;
    }

    public static int passedCount(List<Check> checks) {
        int passed = 0;
        for (Check check : checks) {
            if (check.passed()) {
                passed++;
            }
        }
        return passed;
    }

    public static boolean allPassed(List<Check> checks) {
        return passedCount(checks) == checks.size();
    }

    // ---- the fingerprint ----

    /** The version tag that starts the canonical text (ADR-023 D14 item 2). A new input or shown value needs a new tag. */
    public static final String FINGERPRINT_VERSION = "chemo-fp-v2";

    /** One drug of the order as the checker sees it: calculated, rounded and final mg (null before a BSA is known). */
    public record CanonDrug(String drugKey, Double calculatedMg, Double roundedMg, Double finalMg) {
    }

    /**
     * Everything the checker relies on (ADR-023 D14 item 2): every doctor input, every server-computed value of the sheet
     * and the summary, each check's key and pass state, and the named checker. Display text, relative times, the revision
     * and save timestamps are not part of it.
     */
    public record Canon(
            // doctor inputs
            String protocolKey, int levelPct, String reductionReasonKey, String reductionReasonText,
            Map<String, Double> editedFinalDoses, Set<String> premedsNotGiven, boolean allergyConfirmed,
            boolean consentNewSigned, String scheme, Double priorOutsideDoxorubicinMgM2,
            // computed values
            Double weightKg, Double heightCm, Double bsaRaw, Double bsaUsed, boolean bsaCapped, boolean capAt2,
            String bsaSource, List<CanonDrug> drugs, Double lifetimeEarlierMgM2, Double lifetimeThisCycleMgM2,
            Double lifetimeTotalMgM2, OncologyRules.Labs labs, Instant labsDrawnAt, String decision,
            // checks and the named checker
            List<Check> checks, UUID checkerMembershipId) {
    }

    /**
     * SHA-256 of the canonical text (UTF-8): the tag {@link #FINGERPRINT_VERSION}, then {@code name=value}, one per line, in
     * a fixed order. Sets are sorted by key, drugs follow the protocol, numbers are plain decimals without trailing zeros,
     * a missing value is {@code null}, booleans are {@code true} or {@code false}, and a line break inside a text is
     * escaped so a typed text cannot add a line. "What the checker confirmed is what is sent": any change in it changes
     * the fingerprint, so a confirmation never covers a changed order.
     */
    public static String fingerprint(Canon canon) {
        return RuleTag.sha256(canonicalText(canon));
    }

    /** The canonical text itself (also used by the tests; it holds clinical data and is never logged). */
    public static String canonicalText(Canon c) {
        StringBuilder text = new StringBuilder(FINGERPRINT_VERSION).append('\n');
        line(text, "protocol", c.protocolKey());
        line(text, "level", c.levelPct());
        line(text, "reductionReasonKey", c.reductionReasonKey());
        line(text, "reductionReasonText", c.reductionReasonText());
        new TreeMap<>(c.editedFinalDoses() == null ? Map.<String, Double>of() : c.editedFinalDoses())
                .forEach((key, mg) -> line(text, "editedFinalDose." + key, mg));
        new TreeSet<>(c.premedsNotGiven() == null ? Set.<String>of() : c.premedsNotGiven())
                .forEach(key -> line(text, "premedNotGiven", key));
        line(text, "allergyConfirmed", c.allergyConfirmed());
        line(text, "consentNewSigned", c.consentNewSigned());
        line(text, "scheme", c.scheme());
        line(text, "priorOutsideDoxorubicinMgM2", c.priorOutsideDoxorubicinMgM2());
        line(text, "weightKg", c.weightKg());
        line(text, "heightCm", c.heightCm());
        line(text, "bsaRaw", c.bsaRaw());
        line(text, "bsaUsed", c.bsaUsed());
        line(text, "bsaCapped", c.bsaCapped());
        line(text, "capAt2", c.capAt2());
        line(text, "bsaSource", c.bsaSource());
        for (CanonDrug drug : c.drugs()) {
            line(text, "drug." + drug.drugKey() + ".calculatedMg", drug.calculatedMg());
            line(text, "drug." + drug.drugKey() + ".roundedMg", drug.roundedMg());
            line(text, "drug." + drug.drugKey() + ".finalMg", drug.finalMg());
        }
        line(text, "lifetime.earlierMgM2", c.lifetimeEarlierMgM2());
        line(text, "lifetime.thisCycleMgM2", c.lifetimeThisCycleMgM2());
        line(text, "lifetime.totalMgM2", c.lifetimeTotalMgM2());
        var labs = c.labs();
        line(text, "labs.hb", labs == null ? null : labs.hb());
        line(text, "labs.anc", labs == null ? null : labs.anc());
        line(text, "labs.plt", labs == null ? null : labs.plt());
        line(text, "labs.cr", labs == null ? null : labs.cr());
        line(text, "labs.bili", labs == null ? null : labs.bili());
        line(text, "labs.alt", labs == null ? null : labs.alt());
        line(text, "labs.lvef", labs == null ? null : labs.lvef());
        line(text, "labs.drawnAt", c.labsDrawnAt());
        line(text, "decision", c.decision());
        for (Check check : c.checks()) {
            line(text, "check." + check.key(), check.passed());
        }
        line(text, "checker", c.checkerMembershipId());
        return text.toString();
    }

    private static void line(StringBuilder text, String name, Object value) {
        text.append(name).append('=').append(canonical(value)).append('\n');
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double || value instanceof Float) {
            BigDecimal plain = BigDecimal.valueOf(((Number) value).doubleValue());
            return plain.signum() == 0 ? "0" : plain.stripTrailingZeros().toPlainString();
        }
        if (value instanceof String string) {
            return string.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        }
        return value.toString();
    }
}
