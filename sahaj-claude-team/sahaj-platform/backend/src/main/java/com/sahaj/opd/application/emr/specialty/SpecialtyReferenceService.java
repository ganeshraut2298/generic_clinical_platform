package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.ServiceRepository.ServiceRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData.JointItem;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceRepository.MedicineRef;
import com.sahaj.opd.domain.emr.specialty.AncRules;
import com.sahaj.opd.domain.emr.specialty.ChemoRules;
import com.sahaj.opd.domain.emr.specialty.DentalRules;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.opd.domain.emr.specialty.PaedsRules;
import com.sahaj.opd.domain.emr.specialty.PhysioRules;
import com.sahaj.opd.domain.emr.specialty.RuleTag;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules;
import com.sahaj.opd.domain.emr.specialty.VitalsRules;
import com.sahaj.platform.api.ClinicUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /emr/specialty-reference/{profile} (ADR-023 D4): the lists, thresholds and reference tables a specialty screen needs
 * to compute live. The server recomputes everything on save and read and its answer wins. Every rule value carries the tag
 * "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed". Two clinic-specific parts: dental
 * suggestions and surgical procedures carry the clinic's tariff match (active service with the same name ignoring case
 * and spaces, H17) or null ("Not in tariff"); the sample rates of the mockup are never returned.
 */
@Service
public class SpecialtyReferenceService {

    private final EmrAccess access;
    private final SpecialtyReferenceRepository reference;
    private final ServiceRepository services;
    private final ObjectMapper mapper;

    public SpecialtyReferenceService(EmrAccess access, SpecialtyReferenceRepository reference,
                                     ServiceRepository services, ObjectMapper mapper) {
        this.access = access;
        this.reference = reference;
        this.services = services;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(ClinicUser user, String profile) {
        access.requireConsultationWrite(user);
        if (!SpecialtyProfiles.isProfile(profile)) {
            throw EmrProblemException.badRequest("Choose one of the seven specialty screens.");
        }
        return forProfile(user.tenantId(), profile);
    }

    /**
     * The same reference without a role check, for the reprint of a signed prescription (opd.yaml 0.6.1): the caller
     * (the reprint service) has already applied its own roles, which include the front desk and the pharmacist (ADR-025 H21).
     */
    @Transactional(readOnly = true)
    public ObjectNode forProfile(java.util.UUID tenantId, String profile) {
        Map<String, ServiceRow> tariff = new HashMap<>();
        for (ServiceRow service : services.findAll(tenantId, true)) {
            tariff.putIfAbsent(SpecialtyRecordSupport.normalise(service.name()), service);
        }
        Map<String, MedicineRef> medicines = reference.medicinesByName(medicineNames(profile));
        return build(profile, reference.load(), tariff, medicines);
    }

    /** The reference of a profile from the tables and the clinic tariff (a map of normalised name to service). */
    ObjectNode build(String profile, SpecialtyReferenceData data, Map<String, ServiceRow> tariff) {
        return build(profile, data, tariff, Map.of());
    }

    /**
     * The reference of a profile. {@code medicines} are the master medicines the lists point at, keyed by normalised name
     * (docs/api/opd.yaml 0.5.1: a list item with no master row has medicine null).
     */
    ObjectNode build(String profile, SpecialtyReferenceData data, Map<String, ServiceRow> tariff,
                     Map<String, MedicineRef> medicines) {
        ObjectNode root = mapper.createObjectNode();
        root.put("profile", profile);
        root.put("tagNote", RuleTag.TAG);
        ObjectNode lists = root.putObject("lists");
        ObjectNode thresholds = root.putObject("thresholds");
        switch (profile) {
            case "gm" -> gm(lists, thresholds);
            case "dental" -> dental(lists, thresholds, tariff);
            case "og" -> og(lists, thresholds, medicines);
            case "paeds" -> paeds(root, lists, thresholds, data, medicines);
            case "physio" -> physio(lists, thresholds, data);
            case "surgery" -> surgery(lists, thresholds, tariff);
            default -> onco(root, lists, thresholds, data, medicines);
        }
        return root;
    }

    // ---- item helpers ----

    /**
     * One reference item (docs/api/opd.yaml 0.5.1, EmrReferenceItem). Fields a list does not name are null: they are written
     * as null, never left out, so a client reads the same shape for every item.
     */
    private ObjectNode item(ArrayNode list, String key, String label, String sub, String group, Number number) {
        ObjectNode item = list.addObject();
        item.put("key", key);
        item.put("label", label);
        putText(item, "sub", sub);
        putText(item, "group", group);
        if (number == null) {
            item.putNull("number");
        } else {
            item.put("number", number.doubleValue());
        }
        item.putNull("note");
        item.putNull("defaultOn");
        item.putNull("defaults");
        item.putNull("medicine");
        return item;
    }

    private ObjectNode item(ArrayNode list, String key, String label) {
        return item(list, key, label, null, null, null);
    }

    private static void putText(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private void tariff(ObjectNode item, Map<String, ServiceRow> tariff, String name) {
        ServiceRow match = tariff.get(SpecialtyRecordSupport.normalise(name));
        if (match == null) {
            item.putNull("tariff");
        } else {
            ObjectNode node = item.putObject("tariff");
            node.put("serviceId", match.id().toString());
            node.put("pricePaise", match.pricePaise());
        }
    }

    /** The row defaults the client copies into the row it adds (EmrReferenceItem.defaults); only the named ones are set. */
    private ObjectNode defaults(ObjectNode item) {
        return item.putObject("defaults");
    }

    /** The seeded master medicine of a list item, or null when the master has no row of that name (a typed row then). */
    private void medicine(ObjectNode item, Map<String, MedicineRef> medicines, String name) {
        MedicineRef ref = medicines.get(SpecialtyRecordSupport.normalise(name));
        if (ref == null) {
            item.putNull("medicine");
        } else {
            ObjectNode node = item.putObject("medicine");
            node.put("medicineId", ref.id().toString());
            node.put("name", ref.name());
            putText(node, "form", ref.form());
        }
    }

    /** The master medicine names the lists of a profile can point at (looked up once per request). */
    static List<String> medicineNames(String profile) {
        List<String> names = new ArrayList<>();
        switch (profile) {
            case "og" -> names.addAll(List.of(AncSupplement.IRON.medicine, AncSupplement.CALCIUM.medicine,
                    AncSupplement.FOLIC.medicine));
            case "paeds" -> PaedsRules.DOSE_RULES.forEach(rule -> names.add(rule.name()));
            case "onco" -> OncologyRules.TAKE_HOME.forEach(line -> names.add(line.medicineName()));
            default -> {
                // no medicine list
            }
        }
        return names;
    }

    /** A follow-up or next-sitting interval: number = the count, group = DAYS or MONTHS (EmrReferenceItem). */
    private void interval(ArrayNode list, String key, String label, int count, String unit) {
        item(list, key, label, null, unit, count);
    }

    // ---- profiles ----

    private void gm(ObjectNode lists, ObjectNode t) {
        ArrayNode signs = lists.putArray("gmSigns");
        for (String sign : List.of("pallor", "icterus", "cyanosis", "clubbing", "lymphadenopathy", "oedema",
                "dehydration")) {
            item(signs, sign, capital(sign));
        }
        ArrayNode systems = lists.putArray("gmSystems");
        item(systems, "general", "General", "No pallor, icterus, cyanosis, oedema", null, null);
        item(systems, "cvs", "CVS", "S1 S2 heard, no murmur", null, null);
        item(systems, "rs", "RS", "Air entry equal, clear", null, null);
        item(systems, "pa", "P/A", "Soft, non-tender", null, null);
        item(systems, "cns", "CNS", "Conscious, oriented, no focal deficit", null, null);
        ArrayNode follow = lists.putArray("gmFollowUp");
        interval(follow, "3d", "3 days", 3, "DAYS");
        interval(follow, "7d", "7 days", 7, "DAYS");
        interval(follow, "15d", "15 days", 15, "DAYS");
        interval(follow, "1m", "1 month", 1, "MONTHS");
        interval(follow, "3m", "3 months", 3, "MONTHS");
        t.put("gmSystolicHigh", VitalsRules.GM_SYSTOLIC_HIGH);
        t.put("gmDiastolicHigh", VitalsRules.GM_DIASTOLIC_HIGH);
        t.put("gmSystolicLow", VitalsRules.GM_SYSTOLIC_LOW);
        t.put("gmPulseHigh", VitalsRules.GM_PULSE_HIGH);
        t.put("gmPulseLow", VitalsRules.GM_PULSE_LOW);
        t.put("gmSpo2Low", VitalsRules.GM_SPO2_LOW);
        t.put("gmFeverF", VitalsRules.GM_FEVER_F);
        t.put("gmTempLowF", VitalsRules.GM_TEMP_LOW_F);
        t.put("gmRespHigh", VitalsRules.GM_RESP_HIGH);
    }

    private void dental(ObjectNode lists, ObjectNode t, Map<String, ServiceRow> tariff) {
        ArrayNode conditions = lists.putArray("dentalConditions");
        for (String condition : DentalRules.CONDITIONS) {
            item(conditions, condition, condition.equals("TO_EXTRACT") ? "To extract" : capital(condition.toLowerCase()));
        }
        ArrayNode surfaces = lists.putArray("dentalSurfaces");
        DentalRules.SURFACES.forEach(s -> item(surfaces, s, s));
        ArrayNode mobility = lists.putArray("dentalMobility");
        for (int grade = 0; grade <= 3; grade++) {
            item(mobility, String.valueOf(grade), "G" + grade);
        }
        ArrayNode shades = lists.putArray("dentalShades");
        DentalRules.SHADES.forEach(s -> item(shades, s, s));
        ArrayNode gums = lists.putArray("dentalGumTones");
        item(gums, "PINK", "Pink");
        item(gums, "LIGHT_BROWN", "Light brown");
        item(gums, "DARK_BROWN", "Dark brown");
        ArrayNode suggestions = lists.putArray("dentalSuggestions");
        for (String condition : DentalRules.CONDITIONS) {
            for (DentalRules.Suggestion suggestion : DentalRules.suggestions(condition)) {
                ObjectNode node = item(suggestions, suggestion.procedureKey(), suggestion.label(), null, condition,
                        suggestion.phase());
                defaults(node).put("sittings", DentalRules.defaultSittings(suggestion.procedureKey()));
                tariff(node, tariff, suggestion.label());
            }
        }
        ArrayNode intervals = lists.putArray("dentalNextIntervals");
        DentalRules.NEXT_SITTING_DAYS.forEach(d -> interval(intervals, d + "d", d + " days", d, "DAYS"));
        interval(intervals, "1m", "1 month", 1, "MONTHS");
        ArrayNode labItems = lists.putArray("dentalLabItems");
        item(labItems, "CROWN", "Crown");
        item(labItems, "BRIDGE", "Bridge");
        item(labItems, "DENTURE", "Denture");
        item(labItems, "ALIGNER", "Aligner");
        t.put("dentalRootCanalSittings", DentalRules.ROOT_CANAL_SITTINGS);
        t.put("dentalMaxSittings", DentalRules.MAX_SITTINGS);
        t.put("dentalEstimateStepPaise", DentalRules.ESTIMATE_STEP_PAISE);
        t.put("dentalChildUnderYears", DentalRules.CHILD_UNDER_YEARS);
    }

    /** An antenatal supplement as drawn: key, label, usual frequency, the drawn note and the master medicine name. */
    enum AncSupplement {
        IRON("IRON", "Ferrous ascorbate 100 mg", "1-0-1", "After food", "Ferrous ascorbate 100 mg"),
        CALCIUM("CALCIUM_VIT_D3", "Calcium 500 mg + Vit D3", "0-1-0", "2 h apart from iron",
                "Calcium 500 mg + Vitamin D3"),
        FOLIC("FOLIC_ACID", "Folic acid 5 mg", "1-0-0", null, "Folic acid 5 mg");

        final String key;
        final String label;
        final String frequency;
        final String note;
        final String medicine;

        AncSupplement(String key, String label, String frequency, String note, String medicine) {
            this.key = key;
            this.label = label;
            this.frequency = frequency;
            this.note = note;
            this.medicine = medicine;
        }
    }

    private void og(ObjectNode lists, ObjectNode t, Map<String, MedicineRef> medicines) {
        ArrayNode symptoms = lists.putArray("ancSymptoms");
        item(symptoms, "HEADACHE", "Headache");
        item(symptoms, "BLURRED_VISION", "Blurred vision");
        item(symptoms, "BLEEDING_LEAKING", "Bleeding / leaking");
        item(symptoms, "REDUCED_MOVEMENTS", "Reduced movements");
        ArrayNode due = lists.putArray("ancDueOrders");
        AncRules.DUE_ORDERS.forEach(o -> item(due, o.key(), o.label(), o.window(), null, null));
        ArrayNode scans = lists.putArray("ancScans");
        AncRules.SCAN_WINDOWS.forEach(w -> item(scans, w.key(), scanLabel(w.key()), w.label(), null, null));
        ArrayNode supplements = lists.putArray("ancSupplements");
        for (AncSupplement supplement : AncSupplement.values()) {
            ObjectNode node = item(supplements, supplement.key, supplement.label);
            defaults(node).put("frequency", supplement.frequency);
            putText(node, "note", supplement.note);
            medicine(node, medicines, supplement.medicine);
        }
        ArrayNode gynae = lists.putArray("gynaeQuickOrders");
        item(gynae, "PAP_SMEAR", "Pap smear");
        item(gynae, "PELVIC_USG", "Pelvic USG");
        item(gynae, "THYROID_PROFILE", "Thyroid profile");
        item(gynae, "CBC", "CBC");
        item(gynae, "HORMONES", "Hormone panel (FSH, LH, AMH)");
        t.put("ancBpSystolicHigh", AncRules.BP_SYSTOLIC_HIGH);
        t.put("ancBpDiastolicHigh", AncRules.BP_DIASTOLIC_HIGH);
        t.put("ancHbMild", AncRules.HB_MILD_BELOW);
        t.put("ancHbModerate", AncRules.HB_MODERATE_BELOW);
        t.put("ancHbSevere", AncRules.HB_SEVERE_BELOW);
        t.put("ancFundalToleranceWeeks", AncRules.FUNDAL_TOLERANCE_WEEKS);
        t.put("ancFhrMin", AncRules.FHR_MIN);
        t.put("ancFhrMax", AncRules.FHR_MAX);
        t.put("ancNaegeleDays", AncRules.NAEGELE_DAYS);
    }

    private static String scanLabel(String key) {
        return switch (key) {
            case "DATING" -> "Dating scan";
            case "NT" -> "NT scan";
            case "ANOMALY" -> "Anomaly scan";
            case "GROWTH" -> "Growth scan";
            default -> "Doppler / NST";
        };
    }

    private void paeds(ObjectNode root, ObjectNode lists, ObjectNode t, SpecialtyReferenceData data,
                       Map<String, MedicineRef> medicines) {
        ArrayNode groups = lists.putArray("iapGroups");
        data.groups().forEach(g -> item(groups, g.key(), g.label(), g.fromValue() + " " + unit(g.fromUnit(), g.fromValue())
                + " to " + g.toValue() + " " + unit(g.toUnit(), g.toValue()), null, g.position()));
        ArrayNode vaccines = lists.putArray("iapVaccines");
        data.vaccines().forEach(v -> item(vaccines, v.key(), v.label(), null, v.groupKey(), v.position()));
        ArrayNode milestones = lists.putArray("milestones");
        data.milestones().forEach(m -> item(milestones, m.key(), m.label(), null, m.domain(), m.ageBandMonths()));
        ArrayNode doses = lists.putArray("paedsDoseRules");
        for (PaedsRules.DoseRule rule : PaedsRules.DOSE_RULES) {
            ObjectNode node = item(doses, rule.key(), rule.name(), null, null, rule.mgPerKg());
            defaults(node).put("frequencyText", rule.frequencyText());
            medicine(node, medicines, rule.name());
        }
        ArrayNode bands = root.putArray("growthBands");
        data.growthBands().stream().filter(b -> PaedsRules.CENTILE_MEASURES.contains(b.measure())).forEach(b -> {
            ObjectNode node = bands.addObject();
            node.put("measure", b.measure());
            if (b.sex() == null) {
                node.putNull("sex");
            } else {
                node.put("sex", b.sex());
            }
            node.put("ageMonthsFrom", b.ageMonthsFrom());
            node.put("ageMonthsTo", b.ageMonthsTo());
            if (b.upperExclusive() == null) {
                node.putNull("upperExclusive");
            } else {
                node.put("upperExclusive", b.upperExclusive());
            }
            node.put("label", b.label());
            node.put("alert", b.alert());
        });
        for (PaedsRules.DoseRule rule : PaedsRules.DOSE_RULES) {
            t.put("paeds" + camel(rule.key()) + "MgPerKg", rule.mgPerKg());
            t.put("paeds" + camel(rule.key()) + "MaxMg", rule.maxMg());
            t.put("paeds" + camel(rule.key()) + "MgPerMl", rule.mgPerMl());
        }
    }

    private static String unit(String unit, int value) {
        String word = unit.toLowerCase().replace("s", "");
        return value == 1 ? word : word + "s";
    }

    private static String camel(String key) {
        StringBuilder out = new StringBuilder();
        for (String part : key.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private void physio(ObjectNode lists, ObjectNode t, SpecialtyReferenceData data) {
        ArrayNode gait = lists.putArray("physioGaits");
        item(gait, "NORMAL", "Normal");
        item(gait, "ANTALGIC", "Antalgic");
        item(gait, "WITH_STICK", "With stick");
        item(gait, "WITH_WALKER", "With walker");
        ArrayNode modalities = lists.putArray("physioModalities");
        PhysioRules.MODALITY_DEFAULTS.forEach((key, params) -> item(modalities, key, PhysioRules.MODALITY_LABELS.get(key),
                params, null, null));
        ArrayNode rom = lists.putArray("jointRom");
        for (JointItem joint : data.jointItems().stream().filter(j -> "ROM".equals(j.kind())).toList()) {
            item(rom, joint.itemKey(), joint.label(), joint.normalFromDeg() + "–" + joint.normalToDeg() + "°",
                    joint.jointKey(), joint.normalToDeg());
        }
        ArrayNode mmt = lists.putArray("jointMmt");
        data.jointItems().stream().filter(j -> "MMT".equals(j.kind()))
                .forEach(j -> item(mmt, j.itemKey(), j.label(), null, j.jointKey(), null));
        ArrayNode tests = lists.putArray("jointSpecialTests");
        data.jointItems().stream().filter(j -> "SPECIAL_TEST".equals(j.kind()))
                .forEach(j -> item(tests, j.itemKey(), j.label(), null, j.jointKey(), null));
        ArrayNode scales = lists.putArray("outcomeScales");
        for (String scale : PhysioRules.OUTCOME_SCALES) {
            item(scales, scale, scale, null, null, PhysioRules.OUTCOME_SCALE_MAX.get(scale));
        }
        t.put("physioNprsMax", PhysioRules.NPRS_MAX);
        t.put("physioMmtMax", PhysioRules.MMT_MAX);
        t.put("physioPackageSessions", PhysioRules.PACKAGE_SESSIONS);
    }

    private void surgery(ObjectNode lists, ObjectNode t, Map<String, ServiceRow> tariff) {
        ArrayNode procedures = lists.putArray("surgeryProcedures");
        for (SurgeryRules.Procedure procedure : SurgeryRules.PROCEDURES) {
            ObjectNode node = item(procedures, procedure.key(), procedure.label());
            ObjectNode defaults = defaults(node);
            defaults.put("anaesthesia", procedure.anaesthesia());
            defaults.put("admission", procedure.admission());
            tariff(node, tariff, procedure.label());
        }
        ArrayNode payers = lists.putArray("surgeryPayers");
        item(payers, "SELF", "Self-pay");
        item(payers, "INSURANCE_TPA", "Insurance (TPA)");
        item(payers, "MJPJAY_PMJAY", "MJPJAY / PM-JAY");
        item(payers, "COMPANY", "Company");
        ArrayNode consent = lists.putArray("surgeryConsentItems");
        SurgeryRules.CONSENT_ITEMS.forEach(k -> item(consent, k, SurgeryRules.CONSENT_LABELS.get(k)));
        ArrayNode tests = lists.putArray("surgeryTests");
        SurgeryRules.TESTS.forEach(k -> item(tests, k, SurgeryRules.testLabel(k)));
        ArrayNode preop = lists.putArray("surgeryPreopOrders");
        SurgeryRules.PREOP_ORDERS.forEach(k -> item(preop, k, SurgeryRules.PREOP_LABELS.get(k)));
        ArrayNode blood = lists.putArray("surgeryBlood");
        item(blood, "NOT_NEEDED", "Not needed");
        item(blood, "GROUP_AND_SAVE", "Group and save");
        item(blood, "ONE_UNIT_RESERVED", "1 unit reserved");
        t.put("surgeryDepositPct", 50);
        t.put("surgeryDepositStepPaise", SurgeryRules.DEPOSIT_STEP_PAISE);
        t.put("surgeryOtherPayerDepositPaise", SurgeryRules.OTHER_PAYER_DEPOSIT_PAISE);
    }

    private void onco(ObjectNode root, ObjectNode lists, ObjectNode t, SpecialtyReferenceData data,
                      Map<String, MedicineRef> medicines) {
        ArrayNode symptoms = lists.putArray("oncSymptoms");
        for (String key : OncologyRules.SYMPTOMS) {
            item(symptoms, key, symptomLabel(key));
        }
        ArrayNode events = lists.putArray("oncEvents");
        for (String key : OncologyRules.EVENTS) {
            item(events, key, eventLabel(key));
        }
        ArrayNode systems = lists.putArray("oncSystems");
        for (OncologyRules.ExamSystem system : OncologyRules.SYSTEMS) {
            item(systems, system.key(), system.label(), system.hint(), null, null);
        }
        ArrayNode chips = lists.putArray("oncSystemChips");
        for (OncologyRules.ExamSystem system : OncologyRules.SYSTEMS) {
            for (String chip : system.chips()) {
                item(chips, chip, chip, null, system.key(), null);
            }
        }
        ArrayNode labs = lists.putArray("oncLabs");
        item(labs, "hb", "Haemoglobin", "g/dL", null, 0.2);
        item(labs, "anc", "Neutrophils (ANC)", "/µL", null, 100);
        item(labs, "plt", "Platelets", "×10³/µL", null, 10);
        item(labs, "cr", "Creatinine", "mg/dL", null, 0.1);
        item(labs, "bili", "Bilirubin", "mg/dL", null, 0.1);
        item(labs, "alt", "ALT (SGPT)", "U/L", null, 5);
        item(labs, "lvef", "Heart pumping (LVEF)", "%", null, 1);
        ArrayNode ecog = lists.putArray("oncEcog");
        for (int grade = 0; grade < OncologyRules.ECOG_TEXTS.size(); grade++) {
            item(ecog, String.valueOf(grade), OncologyRules.ECOG_TEXTS.get(grade));
        }
        ArrayNode decisions = lists.putArray("oncDecisions");
        item(decisions, "GIVE_AS_PLANNED", "Give as planned", "Full dose today", null, null);
        item(decisions, "GIVE_REDUCED", "Give, reduce dose", "Next level down", null, null);
        item(decisions, "DELAY_1_WEEK", "Delay 1 week", "Repeat counts, re-book", null, null);
        item(decisions, "CHANGE_REGIMEN", "Change regimen", "New protocol", null, null);
        item(decisions, "STOP", "Stop chemo", "Palliative / supportive care", null, null);
        ArrayNode deviation = lists.putArray("oncDeviationReasons");
        item(deviation, "PATIENT_PREFERENCE", "Patient preference");
        item(deviation, "TUMOUR_BOARD_ADVICE", "Tumour board advice");
        item(deviation, "COUNTS_RECOVERING", "Counts recovering, recheck tomorrow");
        item(deviation, "CLINICAL_JUDGEMENT", "Clinical judgement");
        item(deviation, "SCHEME_COST", "Scheme / cost reasons");
        ArrayNode levels = lists.putArray("oncDoseLevels");
        ChemoRules.LEVELS.forEach(l -> item(levels, String.valueOf(l), l + "%", null, null, l));
        ArrayNode reductions = lists.putArray("oncReductionReasons");
        ChemoRules.REDUCTION_REASONS.forEach(k -> item(reductions, k, ChemoOrderLogic.REDUCTION_LABELS.get(k)));
        ArrayNode takeHome = lists.putArray("oncTakeHome");
        for (OncologyRules.TakeHome line : OncologyRules.TAKE_HOME) {
            ObjectNode node = item(takeHome, line.key(), line.label());
            node.put("note", line.note());
            node.put("defaultOn", line.defaultOn());
            ObjectNode defaults = defaults(node);
            if (line.frequency() != null) {
                defaults.put("frequency", line.frequency());
            }
            if (line.frequencyText() != null) {
                defaults.put("frequencyText", line.frequencyText());
            }
            if (line.days() != null) {
                defaults.put("days", line.days());
            }
            medicine(node, medicines, line.medicineName());
        }
        ArrayNode referrals = lists.putArray("oncReferrals");
        OncologyRules.REFERRALS.forEach((key, label) -> item(referrals, key, label).put("defaultOn", false));
        t.put("oncBsaCap", OncologyRules.BSA_CAP);
        t.put("oncWeightChangeRecalculatePct", OncologyRules.WEIGHT_CHANGE_RECALCULATE_PCT);
        t.put("oncLabsFreshHours", OncologyRules.LABS_FRESH_HOURS);
        t.put("oncFeverC", OncologyRules.FEVER_C);
        t.put("oncEcogFitMax", OncologyRules.ECOG_FIT_MAX);
        t.put("oncLabHbMin", OncologyRules.HB_MIN);
        t.put("oncLabAncMin", OncologyRules.ANC_MIN);
        t.put("oncLabPltMin", OncologyRules.PLT_MIN);
        t.put("oncLabCreatinineMax", OncologyRules.CREATININE_MAX);
        t.put("oncLabBilirubinMax", OncologyRules.BILIRUBIN_MAX);
        t.put("oncLabAltMax", OncologyRules.ALT_MAX);
        t.put("oncLabLvefMin", OncologyRules.LVEF_MIN);
        t.put("oncDoxorubicinLifetime", ChemoRules.LIFETIME_LIMIT_MG_M2);
        t.put("oncDoxorubicinRedAbove", ChemoRules.LIFETIME_RED_ABOVE_MG_M2);
        t.put("oncDoseDeviationPct", ChemoRules.DEVIATION_PCT);
        t.put("oncSystolicCheckHigh", VitalsRules.ONC_SYSTOLIC_CHECK_HIGH);
        ArrayNode protocols = root.putArray("protocols");
        for (ChemoRules.Protocol protocol : data.protocols()) {
            ObjectNode node = protocols.addObject();
            node.put("key", protocol.key());
            node.put("label", protocol.label());
            node.put("subLine", protocol.subLine());
            node.put("intervalDays", protocol.intervalDays());
            node.put("emeticRisk", protocol.emeticRisk());
            node.put("suggestedOnChange", protocol.suggestedOnChange());
            ArrayNode drugs = node.putArray("drugs");
            for (ChemoRules.Drug drug : protocol.drugs()) {
                ObjectNode d = drugs.addObject();
                d.put("drugKey", drug.drugKey());
                d.put("name", drug.name());
                d.put("dosePerM2", drug.dosePerM2());
                d.put("infusionMinutes", drug.infusionMinutes());
                d.put("vialStepMg", drug.vialStepMg());
                d.put("vesicant", drug.vesicant());
                d.put("anthracycline", drug.anthracycline());
                if (drug.note() == null) {
                    d.putNull("note");
                } else {
                    d.put("note", drug.note());
                }
            }
            ArrayNode premeds = node.putArray("premeds");
            for (ChemoRules.Premed premed : protocol.premeds()) {
                ObjectNode p = premeds.addObject();
                p.put("key", premed.key());
                p.put("text", premed.text());
                p.put("minutes", premed.minutes());
            }
        }
    }

    private static String symptomLabel(String key) {
        return switch (key) {
            case "MOUTH_SORES" -> "Mouth sores";
            case "LOOSE_MOTIONS" -> "Loose motions";
            case "TINGLING_NUMBNESS" -> "Tingling / numbness";
            case "FEVER_LOW_COUNTS" -> "Fever with low counts";
            case "LOSS_OF_APPETITE" -> "Loss of appetite";
            default -> capital(key.toLowerCase().replace('_', ' '));
        };
    }

    private static String eventLabel(String key) {
        return switch (key) {
            case "ER_VISIT" -> "ER visit";
            case "FEVER_38_AT_HOME" -> "Fever ≥ 38 °C at home";
            case "BLOOD_TRANSFUSION" -> "Blood transfusion";
            case "MISSED_ORAL_MEDICINES" -> "Missed oral medicines";
            default -> capital(key.toLowerCase().replace('_', ' '));
        };
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

}
