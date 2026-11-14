package com.sahaj.opd.application.emr.specialty;

import static com.sahaj.opd.application.emr.specialty.Sp.arr;
import static com.sahaj.opd.application.emr.specialty.Sp.bool;
import static com.sahaj.opd.application.emr.specialty.Sp.dateN;
import static com.sahaj.opd.application.emr.specialty.Sp.dateTimeN;
import static com.sahaj.opd.application.emr.specialty.Sp.e;
import static com.sahaj.opd.application.emr.specialty.Sp.eN;
import static com.sahaj.opd.application.emr.specialty.Sp.header;
import static com.sahaj.opd.application.emr.specialty.Sp.i;
import static com.sahaj.opd.application.emr.specialty.Sp.iN;
import static com.sahaj.opd.application.emr.specialty.Sp.n;
import static com.sahaj.opd.application.emr.specialty.Sp.nN;
import static com.sahaj.opd.application.emr.specialty.Sp.obj;
import static com.sahaj.opd.application.emr.specialty.Sp.opt;
import static com.sahaj.opd.application.emr.specialty.Sp.req;
import static com.sahaj.opd.application.emr.specialty.Sp.s;
import static com.sahaj.opd.application.emr.specialty.Sp.sN;
import static com.sahaj.opd.application.emr.specialty.Sp.set;
import static com.sahaj.opd.application.emr.specialty.Sp.srv;
import static com.sahaj.opd.application.emr.specialty.Sp.uuid;
import static com.sahaj.opd.application.emr.specialty.Sp.uuidN;

import com.sahaj.opd.application.emr.specialty.Sp.Shape;
import java.util.Map;

/**
 * The closed shape of every specialty component value of docs/api/opd.yaml 0.5.2 (EmrSpecialtyValue and the Sp*
 * schemas). The limits are input sanity limits (ADR-023 H18), not clinical thresholds. A property marked read-only in
 * the contract is {@code srv}: dropped when a client sends it and set by {@link SpecialtyValues}.
 */
final class SpecialtyShapes {

    private static final Shape SIGN = Sp.nullable(e("ABSENT", "PRESENT"));

    /** An EmrBodyMapValue: its pins and stamps are checked and stamped by the shared body map rules (SAH-75-5). */
    private static final Shape BODY_MAP = (v, p, errors) -> {
        if (!v.isObject()) {
            errors.add(p, "Send the body map as an object.");
            return null;
        }
        return v;
    };

    private static final Shape SYSTEM = Sp.nullable(obj(
            req("state", e("NORMAL", "ABNORMAL")),
            opt("text", sN(1000)),
            opt("chips", Sp.set(s(1, 40), 10))));

    private static final Map<String, Shape> SHAPES = Map.ofEntries(
            Map.entry("VITALS_LOG", obj(header("VITALS_LOG", req("entries", arr(obj(
                    req("id", uuid()),
                    opt("bpSystolic", iN(40, 300)),
                    opt("bpDiastolic", iN(20, 200)),
                    opt("pulse", iN(20, 250)),
                    opt("spo2", iN(50, 100)),
                    opt("temperature", Sp.nullable(obj(req("value", n(30, 110, 0.1)), req("unit", e("F", "C"))))),
                    opt("respRate", iN(4, 80)),
                    opt("pain", iN(0, 10)),
                    opt("weightKg", nN(0.3, 300, 0.1)),
                    opt("heightCm", nN(30, 250, 0.5)),
                    srv("how"), srv("recorded")), 20))))),
            Map.entry("EXAM_GENERAL", obj(header("EXAM_GENERAL",
                    req("signs", obj(opt("pallor", SIGN), opt("icterus", SIGN), opt("cyanosis", SIGN),
                            opt("clubbing", SIGN), opt("lymphadenopathy", SIGN), opt("oedema", SIGN),
                            opt("dehydration", SIGN))),
                    req("systems", obj(opt("general", SYSTEM), opt("cvs", SYSTEM), opt("rs", SYSTEM), opt("pa", SYSTEM),
                            opt("cns", SYSTEM)))))),
            Map.entry("TOOTH_CHART", obj(header("TOOTH_CHART",
                    req("numbering", e("FDI", "UNIVERSAL", "PALMER")),
                    req("dentition", e("ADULT", "CHILD")),
                    req("showNumbers", bool()),
                    req("teeth", arr(obj(
                            req("tooth", i(11, 85)),
                            req("condition", e("HEALTHY", "CARIES", "FILLED", "RCT", "CROWN", "MISSING", "TO_EXTRACT",
                                    "IMPLANT")),
                            req("surfaces", set(e("M", "O", "D", "B", "L"), 5)),
                            req("mobility", i(0, 3))), 52)),
                    req("shade", e("A1", "B1", "A2", "A3", "C2")),
                    req("gum", e("PINK", "LIGHT_BROWN", "DARK_BROWN")),
                    req("allHealthyConfirmed", bool())))),
            Map.entry("DENTAL_PLAN", obj(header("DENTAL_PLAN",
                    req("rows", arr(obj(
                            req("id", uuid()),
                            req("tooth", i(11, 85)),
                            req("procedureKey", eN("COMPOSITE_FILLING", "ROOT_CANAL", "PFM_CROWN", "REPLACE_FILLING",
                                    "CROWN_RECEMENTATION", "IMPLANT_WITH_CROWN", "BRIDGE", "EXTRACTION",
                                    "IMPLANT_REVIEW")),
                            req("procedureText", s(1, 160)),
                            srv("serviceId"),
                            req("phase", i(1, 3)),
                            req("status", e("PLANNED", "IN_PROGRESS", "DONE")),
                            req("sittingsPlanned", i(1, 6)),
                            req("currentSitting", i(1, 6)),
                            opt("typedEstimatePaise", Sp.nullable(Sp.n(0, 10_000_000_000.0, 100))),
                            srv("estimatePaise"), srv("estimateSource"), srv("sittingSharesPaise"), srv("doneOn")), 60)),
                    opt("signEstimate", bool()),
                    srv("estimate"), srv("courseId")))),
            Map.entry("LAB_WORK", obj(header("LAB_WORK", req("items", arr(obj(
                    req("id", uuid()),
                    req("planRowId", uuidN()),
                    req("tooth", iN(11, 85)),
                    req("item", e("CROWN", "BRIDGE", "DENTURE", "ALIGNER")),
                    req("labName", sN(80)),
                    req("dueDate", dateN())), 20))))),
            Map.entry("CONSENT_CHECKLIST", obj(header("CONSENT_CHECKLIST", req("items", arr(obj(
                    req("key", s(1, 40)),
                    opt("formLabel", sN(80)),
                    req("confirmed", bool()),
                    srv("confirmedStamp")), 60))))),
            Map.entry("ANC_CHECK", obj(header("ANC_CHECK",
                    opt("datingScanEdd", dateN()),
                    req("today", obj(
                            opt("weightKg", nN(25, 200, 0.1)),
                            opt("bpSystolic", iN(40, 300)),
                            opt("bpDiastolic", iN(20, 200)),
                            opt("fundalHeightCm", iN(5, 50)),
                            opt("fetalHeartBpm", Sp.nullable(Sp.n(50, 250, 2))),
                            opt("hbGdl", nN(2, 20, 0.1)))),
                    req("symptoms", set(e("HEADACHE", "BLURRED_VISION", "BLEEDING_LEAKING", "REDUCED_MOVEMENTS"), 4)),
                    req("orders", arr(obj(
                            req("key", e("PIH_WORKUP", "NST", "OGTT_75G", "TD_2ND_DOSE", "GROWTH_SCAN_FORM_F")),
                            srv("ordered")), 10)),
                    opt("anaemiaPlanAdded", bool()),
                    opt("supplements", set(e("IRON", "CALCIUM_VIT_D3", "FOLIC_ACID"), 3)),
                    req("scans", arr(obj(
                            req("key", e("DATING", "NT", "ANOMALY", "GROWTH", "DOPPLER_NST")),
                            req("done", bool()),
                            opt("finding", sN(200))), 5)),
                    opt("nextAncWeeks", iN(4, 42)),
                    opt("planNote", sN(200)),
                    srv("pregnancyCourseId")))),
            Map.entry("FORM_F", obj(header("FORM_F", req("filledAndSigned", bool()), srv("marked"), srv("scanKey"),
                    srv("scanRemoved")))),
            Map.entry("GYNAE_EXAM", obj(header("GYNAE_EXAM",
                    opt("perAbdomen", sN(1000)), opt("perSpeculum", sN(1000)), opt("perVaginum", sN(1000)),
                    opt("chaperoneName", sN(80)), opt("chaperoneDeclined", bool())))),
            Map.entry("GROWTH", obj(header("GROWTH",
                    opt("weightKg", nN(0.3, 150, 0.1)),
                    opt("lengthCm", nN(25, 200, 0.5)),
                    opt("headCircCm", nN(20, 70, 0.5)),
                    srv("previous")))),
            Map.entry("IMMUNIZATION", obj(header("IMMUNIZATION",
                    req("givenToday", arr(obj(req("vaccineKey", s(2, 40)), srv("given")), 30)),
                    req("scheduleReviewed", bool())))),
            Map.entry("MILESTONES", obj(header("MILESTONES",
                    req("ageBandMonths", i(0, 216)),
                    req("achieved", set(s(1, 40), 40)),
                    req("notYet", set(s(1, 40), 40))))),
            Map.entry("PAIN_SCORE", obj(header("PAIN_SCORE", req("score", iN(0, 10)), opt("site", sN(120)),
                    opt("siteMarker", Sp.nullable(obj(req("side", e("FRONT")), req("x", n(0, 1)), req("y", n(0, 1)),
                            req("region", s(2, 40)))))))),
            Map.entry("POSTURE_GAIT", obj(header("POSTURE_GAIT",
                    req("gait", eN("NORMAL", "ANTALGIC", "WITH_STICK", "WITH_WALKER"))))),
            Map.entry("ROM_TABLE", obj(header("ROM_TABLE",
                    req("joint", s(1, 40)),
                    req("rows", arr(obj(
                            req("movement", s(1, 40)),
                            req("rightDeg", iN(-30, 200)),
                            req("leftDeg", iN(-30, 200))), 20))))),
            Map.entry("MMT_TABLE", obj(header("MMT_TABLE", req("rows", arr(obj(
                    req("group", s(1, 60)), req("right", iN(0, 5)), req("left", iN(0, 5))), 20))))),
            Map.entry("SPECIAL_TESTS", obj(header("SPECIAL_TESTS",
                    req("joint", s(1, 40)),
                    req("results", arr(obj(req("test", s(1, 60)),
                            req("result", e("NOT_DONE", "POSITIVE", "NEGATIVE"))), 20))))),
            Map.entry("GOALS", obj(header("GOALS", req("goals", arr(obj(
                    req("id", uuid()), req("text", s(1, 200)), req("achieved", bool()), srv("achievedOn")), 20))))),
            Map.entry("MODALITIES", obj(header("MODALITIES", req("given", arr(obj(
                    req("key", eN("IFT", "HOT_PACK", "ULTRASOUND", "TENS", "EXERCISE_THERAPY", "TAPING")),
                    req("text", s(1, 60)),
                    req("params", sN(80))), 20))))),
            Map.entry("HOME_EXERCISES", obj(header("HOME_EXERCISES", req("items", arr(obj(
                    req("id", uuid()), req("name", s(1, 120)),
                    opt("sets", iN(1, 20)), opt("reps", iN(1, 100)), opt("holdSeconds", iN(0, 600))), 30))))),
            Map.entry("SESSION_NOTE", obj(header("SESSION_NOTE", req("note", sN(2000))))),
            Map.entry("SURGICAL_EXAM", obj(header("SURGICAL_EXAM",
                    req("abdomen", set(e("SOFT", "TENDER_RIGHT_UPPER", "TENDER_RIGHT_LOWER", "GUARDING", "DISTENDED",
                            "LUMP_FELT"), 6)),
                    opt("murphySign", eN("POSITIVE", "NEGATIVE")),
                    opt("jaundice", eN("NO", "YES")),
                    req("herniaSites", set(e("NORMAL", "RIGHT_INGUINAL", "LEFT_INGUINAL", "UMBILICAL", "INCISIONAL"), 5)),
                    opt("perRectal", eN("NOT_NEEDED", "NORMAL", "PILES", "FISSURE", "MASS"))))),
            Map.entry("SURGERY_FITNESS", obj(header("SURGERY_FITNESS",
                    opt("asa", eN("I", "II", "III", "IV")),
                    req("otherIllness", set(e("NONE", "DIABETES", "HIGH_BP", "HEART_DISEASE", "ASTHMA_COPD",
                            "KIDNEY_DISEASE"), 6)),
                    opt("bloodThinner", eN("NONE", "ASPIRIN", "CLOPIDOGREL", "WARFARIN_DOAC")),
                    req("pac", e("NOT_DONE", "FIT", "FIT_WITH_CAUTION", "UNFIT")),
                    req("tests", arr(obj(
                            req("key", e("CBC", "LFT", "KFT", "PT_INR", "BLOOD_SUGAR", "ECG", "CHEST_XRAY", "HIV",
                                    "HBSAG", "HCV", "BLOOD_GROUP")),
                            req("state", e("PENDING", "NORMAL", "ABNORMAL")),
                            opt("result", sN(200))), 11))))),
            Map.entry("SURGERY_PLAN", obj(header("SURGERY_PLAN",
                    req("procedureKey", eN("LAP_CHOLECYSTECTOMY", "OPEN_CHOLECYSTECTOMY", "LAP_HERNIA_TEP",
                            "OPEN_MESH_HERNIOPLASTY", "LAP_APPENDICECTOMY", "HAEMORRHOIDECTOMY", "EXCISION_LIPOMA",
                            "INCISION_DRAINAGE")),
                    req("procedureText", sN(160)),
                    opt("approach", sN(200)),
                    req("anaesthesia", eN("GA", "SPINAL", "LOCAL", "LOCAL_SEDATION")),
                    req("admission", eN("DAY_CARE", "IPD_1_DAY", "IPD_1_2_DAYS", "IPD_2_DAYS", "IPD_4_5_DAYS")),
                    opt("otDate", dateN()),
                    opt("slotText", sN(40)),
                    req("payer", e("SELF", "INSURANCE_TPA", "MJPJAY_PMJAY", "COMPANY")),
                    opt("typedPackagePaise", Sp.nullable(Sp.n(0, 10_000_000_000.0, 100))),
                    srv("packagePaise"), srv("packageSource"), srv("booking")))),
            Map.entry("PREAUTH", obj(header("PREAUTH", req("sent", bool()), opt("sentOn", dateN()),
                    opt("reference", sN(60))))),
            Map.entry("PREOP_ORDERS", obj(header("PREOP_ORDERS",
                    req("ticked", set(e("NIL_BY_MOUTH", "INHALER_MORNING", "ANTIBIOTIC_INDUCTION", "PART_PREPARATION",
                            "CLOT_PREVENTION", "ADMIT_7AM_OT_10AM"), 6)),
                    req("extraLines", arr(s(1, 200), 10)),
                    opt("blood", eN("NOT_NEEDED", "GROUP_AND_SAVE", "ONE_UNIT_RESERVED"))))),
            Map.entry("ONC_TOX", obj(header("ONC_TOX",
                    req("symptoms", arr(obj(
                            req("key", e("NAUSEA", "VOMITING", "MOUTH_SORES", "LOOSE_MOTIONS", "TINGLING_NUMBNESS",
                                    "FATIGUE", "FEVER_LOW_COUNTS", "PAIN", "LOSS_OF_APPETITE")),
                            req("patientGrade", iN(0, 4)),
                            req("confirmedGrade", iN(0, 4))), 9)),
                    req("events", set(e("ER_VISIT", "ADMITTED", "FEVER_38_AT_HOME", "BLOOD_TRANSFUSION", "ANTIBIOTICS",
                            "MISSED_ORAL_MEDICINES"), 6))))),
            Map.entry("ONC_EXAM", obj(header("ONC_EXAM",
                    opt("heightCm", nN(50, 250, 0.5)),
                    opt("weightKg", nN(2, 300, 0.1)),
                    req("capAt2", bool()),
                    req("signs", obj(opt("pallor", SIGN), opt("icterus", SIGN), opt("cyanosis", SIGN),
                            opt("clubbing", SIGN), opt("lymphNodes", SIGN), opt("pedalOedema", SIGN),
                            opt("dehydration", SIGN))),
                    req("lesions", arr(obj(
                            req("id", uuid()),
                            req("name", s(1, 60)),
                            req("baselineCm", n(0, 50, 0.1)),
                            srv("lastCm"),
                            req("todayCm", nN(0, 50, 0.1))), 10)),
                    req("newLesionSeen", bool()),
                    req("breastSkin", set(e("SKIN_REDNESS", "PEAU_D_ORANGE", "ULCERATION", "NIPPLE_RETRACTION",
                            "FIXED_TO_CHEST_WALL", "LUMP_NOW_MOBILE"), 6)),
                    req("systems", obj(opt("cvs", SYSTEM), opt("rs", SYSTEM), opt("pa", SYSTEM), opt("cns", SYSTEM),
                            opt("bone", SYSTEM))),
                    req("line", obj(
                            req("device", eN("PORT", "PICC", "PERIPHERAL")),
                            req("checks", set(e("FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING", "NO_PAIN",
                                    "DRESSING_CLEAN", "FLUSHED_4_WEEKS"), 6)))),
                    opt("bodyMap", Sp.nullable(BODY_MAP))))),
            Map.entry("ONC_LABS", obj(header("ONC_LABS",
                    req("drawnAt", dateTimeN()),
                    req("values", obj(
                            opt("hb", nN(1, 25, 0)), opt("anc", iN(0, 100000)), opt("plt", iN(0, 2000)),
                            opt("cr", nN(0, 30, 0)), opt("bili", nN(0, 50, 0)), opt("alt", iN(0, 5000)),
                            opt("lvef", iN(5, 90)))),
                    srv("source")))),
            Map.entry("ONC_ECOG", obj(header("ONC_ECOG", req("grade", iN(0, 4))))),
            Map.entry("CHEMO_PLAN", obj(header("CHEMO_PLAN",
                    req("takeHome", set(e("ONDANSETRON_8", "DEXAMETHASONE_4", "PANTOPRAZOLE_40", "PEGFILGRASTIM_6",
                            "BENZYDAMINE_MOUTHWASH", "LOPERAMIDE_2"), 6)),
                    opt("nextCycleDate", dateN()),
                    opt("imagingText", sN(200)),
                    req("referrals", set(e("RADIOTHERAPY", "SURGICAL_ONCOLOGY", "PALLIATIVE_CARE", "DIETITIAN",
                            "PSYCHO_ONCOLOGY_COUNSELLING", "FERTILITY_COUNSELLING", "SOCIAL_WORKER_SCHEME_PAPERS",
                            "CARDIOLOGY_ECHO"), 8)),
                    opt("cycleNote", sN(4000))))),
            Map.entry("OUTCOME_SCALES", obj(header("OUTCOME_SCALES", req("rows", arr(obj(
                    req("scale", e("ODI", "NDI", "WOMAC", "LEFS")),
                    req("score", iN(0, 100))), 4))))),
            Map.entry("DENTAL_MED_HISTORY", obj(header("DENTAL_MED_HISTORY",
                    req("conditions", set(e("DIABETES", "HIGH_BP", "HEART_DISEASE", "THYROID", "ASTHMA",
                            "BLEEDING_DISORDER", "EPILEPSY", "PREGNANT", "NONE"), 9)),
                    req("medicines", set(e("BLOOD_THINNERS", "ASPIRIN", "BISPHOSPHONATES", "STEROIDS",
                            "THYROID_MEDICINE", "NONE"), 6)),
                    req("allergies", set(e("PENICILLIN", "LOCAL_ANAESTHETIC", "LATEX", "NSAIDS", "NONE"), 5)),
                    req("reviewedWithPatient", bool()), srv("review")))),
            Map.entry("DENTAL_DONE", obj(header("DENTAL_DONE"))),
            Map.entry("WEIGHT_DOSE", obj(header("WEIGHT_DOSE"))));

    private SpecialtyShapes() {
    }

    /** The shape of a component value; null for an unknown component. */
    static Shape shape(String component) {
        return SHAPES.get(component);
    }

    static boolean known(String component) {
        return SHAPES.containsKey(component);
    }

}
