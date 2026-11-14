package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrFormModel.Band;
import com.sahaj.opd.application.emr.EmrFormModel.BandSetup;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Option;
import com.sahaj.opd.application.emr.EmrFormModel.Score;
import com.sahaj.opd.application.emr.EmrFormModel.ScoreRule;
import com.sahaj.opd.application.emr.EmrFormModel.Settings;
import com.sahaj.opd.application.emr.EmrFormModel.ShowIf;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure rules of the form builder (SAH-59; ADR-022 D11): the structure checked on every save of a draft, the extra checks
 * at publish, and the score arithmetic (rule C-9, tagged, not clinically reviewed). Messages are fixed plain English and
 * name a field by its key, never by what was typed in it. Nothing here is patient data.
 */
public final class EmrFormRules {

    public static final Set<String> TYPES = Set.of("SHORT_TEXT", "LONG_TEXT", "NUMBER", "DATE", "TIME", "YES_NO",
            "SINGLE_CHOICE", "MULTI_CHOICE", "DROPDOWN", "VITALS_BLOCK", "BODY_MAP", "TOOTH_CHART", "MEDICINE_LIST",
            "LAB_TESTS", "DIAGNOSIS", "SCORE", "SCORE_BAND", "SECTION_HEADING", "INFO_NOTE", "SIGNATURE", "PHOTO",
            "FILE_UPLOAD", "HANDWRITING_PAD");
    static final Set<String> CHOICE = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "DROPDOWN");
    static final Set<String> SHOW_IF_SOURCES = Set.of("YES_NO", "SINGLE_CHOICE", "MULTI_CHOICE", "DROPDOWN");
    static final Set<String> ROLES = Set.of("DOCTOR", "NURSE", "RECEPTION", "PATIENT");
    static final Set<String> VISIT_TYPES = Set.of("NEW_CONSULT", "FOLLOW_UP", "PROCEDURE_VISIT", "TELECONSULT",
            "IPD_DAILY_ROUND");
    static final Set<String> PLACES = Set.of("EMERGENCY", "IPD_WARD");
    static final Set<String> LANGUAGES = Set.of("EN", "EN_MR", "EN_HI");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final BigDecimal TEN_THOUSAND_MILLION = new BigDecimal("1000000");

    private EmrFormRules() {
    }

    private static void add(List<FieldMessage> errors, String field, String message) {
        if (errors.size() < 100) {
            errors.add(new FieldMessage(field, message));
        }
    }

    private static boolean plain(String text, int min, int max) {
        return text != null && text.strip().length() >= min && text.length() <= max && !HTML.matcher(text).find();
    }

    private static boolean plainOrNull(String text, int max) {
        return text == null || (text.length() <= max && !HTML.matcher(text).find());
    }

    // ---- every save of a draft ----

    /**
     * The structure of a draft: returns every problem found (empty when the draft may be stored). The set of departments
     * a form may be used in comes from the caller (the clinic's departments visible to doctors).
     */
    public static List<FieldMessage> checkDraft(String name, Settings settings, List<Field> fields,
                                                Set<String> departmentKeys) {
        List<FieldMessage> errors = new ArrayList<>();
        if (!plain(name, 1, 80)) {
            add(errors, "name", "Enter a form name of 1 to 80 characters.");
        }
        checkSettings(settings, departmentKeys, errors);
        List<Field> list = fields == null ? List.of() : fields;
        if (list.size() > EmrFormModel.MAX_FIELDS) {
            add(errors, "fields", "A form has at most 80 fields.");
            return errors;
        }
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Field field = list.get(i);
            String where = "fields[" + i + "]";
            if (field == null || field.key() == null || !KEY.matcher(field.key()).matches()) {
                add(errors, where + ".key", "Use a key of lower-case letters, digits and underscores, starting with a letter.");
                continue;
            }
            if (position.put(field.key(), i) != null) {
                add(errors, where + ".key", "Field key " + field.key() + " is used twice.");
            }
        }
        for (int i = 0; i < list.size(); i++) {
            Field field = list.get(i);
            if (field != null && field.key() != null && KEY.matcher(field.key()).matches()) {
                checkField(field, i, list, position, errors);
            }
        }
        return errors;
    }

    private static void checkSettings(Settings settings, Set<String> departmentKeys, List<FieldMessage> errors) {
        if (settings == null || settings.useIn() == null) {
            add(errors, "settings", "Send the form settings.");
            return;
        }
        List<String> departments = settings.useIn().departmentKeys() == null ? List.of()
                : settings.useIn().departmentKeys();
        List<String> places = settings.useIn().places() == null ? List.of() : settings.useIn().places();
        if (new HashSet<>(departments).size() != departments.size()
                || !departmentKeys.containsAll(departments)) {
            add(errors, "settings.useIn.departmentKeys", "Choose departments from this clinic that doctors can use.");
        }
        if (new HashSet<>(places).size() != places.size() || !PLACES.containsAll(places)) {
            add(errors, "settings.useIn.places", "Choose Emergency or IPD ward.");
        }
        if (!EmrModel.PARTS.contains(settings.emrPart())) {
            add(errors, "settings.emrPart", "Choose Assessment or Treatment.");
        }
        checkSet(settings.visitTypes(), VISIT_TYPES, "settings.visitTypes", "Choose visit types from the list.", errors);
        checkSet(settings.whoCanFill(), ROLES, "settings.whoCanFill", "Choose who can fill from the list.", errors);
        if (settings.printLanguage() == null || !LANGUAGES.contains(settings.printLanguage())) {
            add(errors, "settings.printLanguage", "Choose English, English with Marathi or English with Hindi.");
        }
        if (settings.approvalRequired() == null) {
            add(errors, "settings.approvalRequired", "Choose yes or no.");
        }
    }

    private static void checkSet(List<String> values, Set<String> allowed, String field, String message,
                                 List<FieldMessage> errors) {
        if (values == null) {
            add(errors, field, message);
        } else if (new HashSet<>(values).size() != values.size() || !allowed.containsAll(values)) {
            add(errors, field, message);
        }
    }

    private static void checkField(Field field, int index, List<Field> all, Map<String, Integer> position,
                                   List<FieldMessage> errors) {
        String where = "fields[" + index + "]";
        if (field.type() == null || !TYPES.contains(field.type())) {
            add(errors, where + ".type", "Choose a field type from the list.");
            return;
        }
        if (!plain(field.label(), 1, 200)) {
            add(errors, where + ".label", "Enter a label of 1 to 200 characters.");
        }
        if (!plainOrNull(field.labelMr(), 200) || !plainOrNull(field.labelHi(), 200)) {
            add(errors, where + ".label", "A translated label is up to 200 characters.");
        }
        if (!plainOrNull(field.help(), 500)) {
            add(errors, where + ".help", "Help text is up to 500 characters.");
        }
        if (field.required() == null || field.print() == null || field.copyFromLast() == null) {
            add(errors, where, "Choose yes or no for required, print and copy from last visit.");
        }
        if (field.width() == null || field.width() < 1 || field.width() > 6) {
            add(errors, where + ".width", "Width is from 1 to 6 columns.");
        }
        if (!plainOrNull(field.unit(), 20) || !plainOrNull(field.standardCode(), 60)) {
            add(errors, where + ".unit", "A unit is up to 20 characters and a standard code up to 60.");
        }
        if (field.whoCanFill() == null || new HashSet<>(field.whoCanFill()).size() != field.whoCanFill().size()
                || !ROLES.containsAll(field.whoCanFill())) {
            add(errors, where + ".whoCanFill", "Choose who can fill from the list.");
        }
        checkRanges(field, where, errors);
        checkOptions(field, where, errors);
        checkShowIf(field, where, all, position, index, errors);
        checkScore(field, where, all, position, errors);
        checkBand(field, where, all, position, errors);
    }

    private static void checkRanges(Field field, String where, List<FieldMessage> errors) {
        boolean number = "NUMBER".equals(field.type());
        if (!number && (field.min() != null || field.max() != null || field.normalFrom() != null
                || field.normalTo() != null)) {
            add(errors, where + ".min", "Limits are for number fields only.");
            return;
        }
        for (BigDecimal value : new BigDecimal[] {field.min(), field.max(), field.normalFrom(), field.normalTo()}) {
            if (value != null && value.abs().compareTo(TEN_THOUSAND_MILLION) > 0) {
                add(errors, where + ".min", "A limit is between -1000000 and 1000000.");
                return;
            }
        }
        if (field.min() != null && field.max() != null && field.min().compareTo(field.max()) > 0) {
            add(errors, where + ".min", "The lowest value cannot be above the highest.");
        }
        if (field.normalFrom() != null && field.normalTo() != null && field.normalFrom().compareTo(field.normalTo()) > 0) {
            add(errors, where + ".normalFrom", "The normal range must run from low to high.");
        }
    }

    private static void checkOptions(Field field, String where, List<FieldMessage> errors) {
        List<Option> options = field.options() == null ? List.of() : field.options();
        if (!CHOICE.contains(field.type())) {
            if ("YES_NO".equals(field.type())) {
                for (Option option : options) {
                    if (option == null || !Set.of("yes", "no").contains(option.key())) {
                        add(errors, where + ".options", "A yes or no field has the options yes and no only.");
                        return;
                    }
                }
            } else if (!options.isEmpty()) {
                add(errors, where + ".options", "Only choice fields have options.");
            }
            return;
        }
        if (options.isEmpty() || options.size() > 30) {
            add(errors, where + ".options", "Add 1 to 30 options.");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            String at = where + ".options[" + i + "]";
            if (option == null || option.key() == null || !KEY.matcher(option.key()).matches()
                    || !keys.add(option.key())) {
                add(errors, at + ".key", "Each option needs its own key of lower-case letters, digits and underscores.");
                continue;
            }
            if (!plain(option.label(), 1, 120) || !plainOrNull(option.labelMr(), 120)
                    || !plainOrNull(option.labelHi(), 120)) {
                add(errors, at + ".label", "Enter an option label of 1 to 120 characters.");
            }
            if (option.score() == null || option.score() < -100 || option.score() > 100) {
                add(errors, at + ".score", "A score is from -100 to 100.");
            }
        }
    }

    private static Set<String> optionKeys(Field source) {
        if ("YES_NO".equals(source.type())) {
            return Set.of("yes", "no");
        }
        Set<String> keys = new HashSet<>();
        if (source.options() != null) {
            for (Option option : source.options()) {
                if (option != null && option.key() != null) {
                    keys.add(option.key());
                }
            }
        }
        return keys;
    }

    private static void checkShowIf(Field field, String where, List<Field> all, Map<String, Integer> position, int index,
                                    List<FieldMessage> errors) {
        ShowIf showIf = field.showIf();
        if (showIf == null) {
            return;
        }
        Integer at = showIf.fieldKey() == null ? null : position.get(showIf.fieldKey());
        if (at == null || at >= index) {
            add(errors, where + ".showIf", "Show this field only after an earlier field. Field " + field.key()
                    + " points to a later or missing field.");
            return;
        }
        Field source = all.get(at);
        if (!SHOW_IF_SOURCES.contains(source.type()) || showIf.optionKey() == null
                || !optionKeys(source).contains(showIf.optionKey())) {
            add(errors, where + ".showIf", "Choose a yes or no, choice or dropdown field and one of its options.");
        }
    }

    private static void checkScore(Field field, String where, List<Field> all, Map<String, Integer> position,
                                   List<FieldMessage> errors) {
        Score score = field.score();
        if (!"SCORE".equals(field.type())) {
            if (score != null) {
                add(errors, where + ".score", "Only a score field adds up scores.");
            }
            return;
        }
        if (score == null || score.sourceFieldKeys() == null || score.sourceFieldKeys().size() > 40
                || new HashSet<>(score.sourceFieldKeys()).size() != score.sourceFieldKeys().size()) {
            add(errors, where + ".score", "Choose the fields this score adds up.");
            return;
        }
        for (String key : score.sourceFieldKeys()) {
            Integer at = position.get(key);
            if (at == null || key.equals(field.key())) {
                add(errors, where + ".score", "Score source " + key + " is not a field of this form.");
            }
        }
        List<ScoreRule> rules = score.rules() == null ? List.of() : score.rules();
        if (rules.size() > 10) {
            add(errors, where + ".score.rules", "A score has at most 10 rules.");
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            ScoreRule rule = rules.get(i);
            String at = where + ".score.rules[" + i + "]";
            Integer target = rule == null || rule.fieldKey() == null ? null : position.get(rule.fieldKey());
            if (rule == null || target == null || rule.threshold() == null || rule.points() == null
                    || rule.points() < -100 || rule.points() > 100) {
                add(errors, at, "A rule needs a field of this form, a threshold and points from -100 to 100.");
                continue;
            }
            String type = all.get(target).type();
            boolean fits = "NUMBER_BELOW".equals(rule.kind()) && "NUMBER".equals(type)
                    || "CHOSEN_COUNT_BELOW".equals(rule.kind()) && "MULTI_CHOICE".equals(type);
            if (!fits) {
                add(errors, at, "This rule does not fit the type of its field.");
            }
        }
    }

    private static void checkBand(Field field, String where, List<Field> all, Map<String, Integer> position,
                                  List<FieldMessage> errors) {
        BandSetup band = field.band();
        if (!"SCORE_BAND".equals(field.type())) {
            if (band != null) {
                add(errors, where + ".band", "Only a score band field has bands.");
            }
            return;
        }
        if (band == null || band.bands() == null || band.bands().isEmpty() || band.bands().size() > 10) {
            add(errors, where + ".band", "Add 1 to 10 bands.");
            return;
        }
        Integer source = band.sourceFieldKey() == null ? null : position.get(band.sourceFieldKey());
        if (source == null || !"SCORE".equals(all.get(source).type())) {
            add(errors, where + ".band", "A band reads a score field of this form.");
        }
        for (Band one : band.bands()) {
            if (one == null || one.from() == null || !plain(one.label(), 1, 120)) {
                add(errors, where + ".band", "Each band needs a starting score and a label of up to 120 characters.");
                return;
            }
        }
    }

    // ---- who uses a field (a field that is a source cannot be removed) ----

    /** Field keys that other fields in the list depend on, with the dependent fields' keys (for the refusal text). */
    public static Map<String, List<String>> usedBy(List<Field> fields) {
        Map<String, List<String>> users = new HashMap<>();
        for (Field field : fields == null ? List.<Field>of() : fields) {
            List<String> sources = new ArrayList<>();
            if (field.showIf() != null && field.showIf().fieldKey() != null) {
                sources.add(field.showIf().fieldKey());
            }
            if (field.score() != null) {
                if (field.score().sourceFieldKeys() != null) {
                    sources.addAll(field.score().sourceFieldKeys());
                }
                if (field.score().rules() != null) {
                    for (ScoreRule rule : field.score().rules()) {
                        if (rule != null && rule.fieldKey() != null) {
                            sources.add(rule.fieldKey());
                        }
                    }
                }
            }
            if (field.band() != null && field.band().sourceFieldKey() != null) {
                sources.add(field.band().sourceFieldKey());
            }
            for (String source : new HashSet<>(sources)) {
                users.computeIfAbsent(source, k -> new ArrayList<>()).add(field.key());
            }
        }
        return users;
    }

    // ---- publish ----

    /** The extra checks at Send for approval, Approve and Publish. Empty when the form may go live. */
    public static List<FieldMessage> checkPublishable(Definition definition) {
        List<FieldMessage> errors = new ArrayList<>();
        List<Field> fields = definition.fields() == null ? List.of() : definition.fields();
        if (fields.isEmpty()) {
            add(errors, "fields", "Add at least one field.");
        }
        Settings settings = definition.settings();
        boolean anywhere = settings != null && settings.useIn() != null
                && ((settings.useIn().departmentKeys() != null && !settings.useIn().departmentKeys().isEmpty())
                || (settings.useIn().places() != null && !settings.useIn().places().isEmpty()));
        if (!anywhere) {
            add(errors, "settings.useIn", "Pick at least one department in Form settings first.");
        }
        Map<String, Field> byKey = new HashMap<>();
        for (Field field : fields) {
            byKey.put(field.key(), field);
        }
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (!"SCORE_BAND".equals(field.type()) || field.band() == null) {
                continue;
            }
            String where = "fields[" + i + "].band";
            Field source = byKey.get(field.band().sourceFieldKey());
            if (source == null || !"SCORE".equals(source.type())) {
                add(errors, where, "Pick a score for the band.");
                continue;
            }
            BigDecimal previous = null;
            boolean ascending = true;
            for (Band band : field.band().bands()) {
                if (previous != null && band.from().compareTo(previous) <= 0) {
                    ascending = false;
                }
                previous = band.from();
            }
            if (!ascending) {
                add(errors, where, "Bands must run from the lowest score to the highest, each one higher than the last.");
            }
            BigDecimal lowest = lowestTotal(source, byKey);
            if (field.band().bands().get(0).from().compareTo(lowest) > 0) {
                add(errors, where, "The first band must start at or below the lowest possible score (" + lowest.stripTrailingZeros().toPlainString() + ").");
            }
        }
        return errors;
    }

    // ---- scores (rule C-9, tagged) ----

    /** The lowest total a score can reach when its sources are answered (no answer scores 0). */
    public static BigDecimal lowestTotal(Field scoreField, Map<String, Field> byKey) {
        BigDecimal total = BigDecimal.ZERO;
        for (String key : scoreField.score().sourceFieldKeys()) {
            Field source = byKey.get(key);
            if (source == null || source.options() == null) {
                continue;
            }
            int min = 0;
            boolean first = true;
            int negatives = 0;
            for (Option option : source.options()) {
                int s = option.score() == null ? 0 : option.score();
                min = first ? s : Math.min(min, s);
                first = false;
                negatives += Math.min(0, s);
            }
            total = total.add(BigDecimal.valueOf("MULTI_CHOICE".equals(source.type()) ? negatives
                    : first ? 0 : min));
        }
        for (ScoreRule rule : scoreField.score().rules()) {
            total = total.add(BigDecimal.valueOf(Math.min(0, rule.points())));
        }
        return total;
    }

    /**
     * The total of a score field from the answers (option keys per field key; numbers per field key). Blank (null) until
     * one source is answered. Sums the chosen options' scores (every chosen option of a multi-choice) and the points of
     * each rule that fires.
     */
    public static BigDecimal total(Field scoreField, Map<String, Field> byKey, Map<String, List<String>> chosen,
                                   Map<String, BigDecimal> numbers) {
        boolean answered = false;
        BigDecimal total = BigDecimal.ZERO;
        for (String key : scoreField.score().sourceFieldKeys()) {
            Field source = byKey.get(key);
            List<String> picked = chosen.get(key);
            if (source == null || picked == null || picked.isEmpty() || source.options() == null) {
                continue;
            }
            answered = true;
            for (Option option : source.options()) {
                if (picked.contains(option.key())) {
                    total = total.add(BigDecimal.valueOf(option.score() == null ? 0 : option.score()));
                }
            }
        }
        for (ScoreRule rule : scoreField.score().rules()) {
            if ("NUMBER_BELOW".equals(rule.kind())) {
                BigDecimal value = numbers.get(rule.fieldKey());
                if (value != null) {
                    answered = true;
                    if (value.compareTo(rule.threshold()) < 0) {
                        total = total.add(BigDecimal.valueOf(rule.points()));
                    }
                }
            } else {
                List<String> picked = chosen.get(rule.fieldKey());
                if (picked != null) {
                    answered = true;
                    if (BigDecimal.valueOf(picked.size()).compareTo(rule.threshold()) < 0) {
                        total = total.add(BigDecimal.valueOf(rule.points()));
                    }
                }
            }
        }
        return answered ? total : null;
    }

    /** The band label for a total: the band with the highest {@code from} not above it; null for a blank total. */
    public static String bandLabel(BandSetup band, BigDecimal total) {
        if (total == null) {
            return null;
        }
        Band chosen = null;
        for (Band one : band.bands()) {
            if (one.from().compareTo(total) <= 0 && (chosen == null || one.from().compareTo(chosen.from()) > 0)) {
                chosen = one;
            }
        }
        return chosen == null ? null : chosen.label();
    }

    /** Field, setting and version counts for the list: fields, fields with logic (show-if) and scored fields. */
    public static int[] counts(List<Field> fields) {
        int logic = 0;
        int scored = 0;
        for (Field field : fields == null ? List.<Field>of() : fields) {
            if (field.showIf() != null) {
                logic++;
            }
            if ("SCORE".equals(field.type())) {
                scored++;
            }
        }
        return new int[] {fields == null ? 0 : fields.size(), logic, scored};
    }
}
