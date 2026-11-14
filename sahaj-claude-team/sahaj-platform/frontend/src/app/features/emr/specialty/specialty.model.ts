/**
 * Types for EMR phase 4 (ADR-023), written by hand from `docs/api/opd.yaml` 0.5.1: the specialty
 * profiles and their component values, the computed view, the reference lists, the history and the
 * chemotherapy order. No field is invented. All of it is clinical data for the screen only: nothing
 * here is logged or kept in the browser.
 *
 * Every rule value used on screen is "value from the EMR mockup, accepted by the human in DEC-039,
 * not clinically reviewed" (called "tagged"). The server recomputes and its answer wins.
 */

import type { EmrRxFrequency } from '../emr-phase3.model';

export const TAG_NOTE = 'value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed';

/** The closed set of reference list names of contract 0.5.1 (ADR-023 D13.1): 44 names. */
export const REFERENCE_LIST_NAMES = [
  'gmSigns', 'gmSystems', 'gmFollowUp',
  'dentalConditions', 'dentalSurfaces', 'dentalMobility', 'dentalShades', 'dentalGumTones', 'dentalSuggestions', 'dentalNextIntervals', 'dentalLabItems',
  'ancSymptoms', 'ancDueOrders', 'ancScans', 'ancSupplements', 'gynaeQuickOrders',
  'iapGroups', 'iapVaccines', 'milestones', 'paedsDoseRules',
  'physioGaits', 'physioModalities', 'jointRom', 'jointMmt', 'jointSpecialTests', 'outcomeScales',
  'surgeryProcedures', 'surgeryPayers', 'surgeryConsentItems', 'surgeryTests', 'surgeryPreopOrders', 'surgeryBlood',
  'oncSymptoms', 'oncEvents', 'oncSystems', 'oncSystemChips', 'oncLabs', 'oncEcog', 'oncDecisions', 'oncDeviationReasons', 'oncDoseLevels', 'oncReductionReasons', 'oncTakeHome', 'oncReferrals',
] as const;
export type ReferenceListName = (typeof REFERENCE_LIST_NAMES)[number];

export type EmrSpecialtyProfile = 'gm' | 'dental' | 'og' | 'paeds' | 'physio' | 'onco' | 'surgery';

export type EmrSpecialtyComponent =
  | 'VITALS_LOG'
  | 'EXAM_GENERAL'
  | 'TOOTH_CHART'
  | 'DENTAL_PLAN'
  | 'DENTAL_DONE'
  | 'LAB_WORK'
  | 'CONSENT_CHECKLIST'
  | 'ANC_CHECK'
  | 'FORM_F'
  | 'GYNAE_EXAM'
  | 'GROWTH'
  | 'IMMUNIZATION'
  | 'MILESTONES'
  | 'WEIGHT_DOSE'
  | 'PAIN_SCORE'
  | 'POSTURE_GAIT'
  | 'ROM_TABLE'
  | 'MMT_TABLE'
  | 'SPECIAL_TESTS'
  | 'GOALS'
  | 'MODALITIES'
  | 'HOME_EXERCISES'
  | 'SESSION_NOTE'
  | 'SURGICAL_EXAM'
  | 'SURGERY_FITNESS'
  | 'SURGERY_PLAN'
  | 'PREAUTH'
  | 'PREOP_ORDERS'
  | 'ONC_TOX'
  | 'ONC_EXAM'
  | 'ONC_LABS'
  | 'ONC_ECOG'
  | 'CHEMO_PLAN'
  | 'OUTCOME_SCALES';

export type EmrScreenMode = 'ANC' | 'GYNAE' | 'FIRST_VISIT' | 'SESSION';

/** Server stamp: when and by whom. Read only. */
export interface SpStamp {
  at: string;
  byMembershipId: string;
  byName?: string | null;
}

export type SpSignState = 'ABSENT' | 'PRESENT' | null;

export interface SpSystemFinding {
  state: 'NORMAL' | 'ABNORMAL';
  text?: string | null;
  chips?: string[];
}

interface SpBase<C extends EmrSpecialtyComponent> {
  component: C;
  schemaVersion: 1;
}

export interface SpEmpty extends SpBase<'DENTAL_DONE' | 'WEIGHT_DOSE'> {
  component: 'DENTAL_DONE' | 'WEIGHT_DOSE';
}

export interface SpVitalsEntry {
  id: string;
  bpSystolic?: number | null;
  bpDiastolic?: number | null;
  pulse?: number | null;
  spo2?: number | null;
  temperature?: { value: number; unit: 'F' | 'C' } | null;
  respRate?: number | null;
  pain?: number | null;
  weightKg?: number | null;
  heightCm?: number | null;
  how?: 'TYPED';
  recorded?: SpStamp;
}
export interface SpVitalsLog extends SpBase<'VITALS_LOG'> {
  entries: SpVitalsEntry[];
}

export interface SpExamGeneral extends SpBase<'EXAM_GENERAL'> {
  signs: Partial<Record<'pallor' | 'icterus' | 'cyanosis' | 'clubbing' | 'lymphadenopathy' | 'oedema' | 'dehydration', SpSignState>>;
  systems: Partial<Record<'general' | 'cvs' | 'rs' | 'pa' | 'cns', SpSystemFinding | null>>;
}

export type ToothCondition = 'HEALTHY' | 'CARIES' | 'FILLED' | 'RCT' | 'CROWN' | 'MISSING' | 'TO_EXTRACT' | 'IMPLANT';
export type ToothSurface = 'M' | 'O' | 'D' | 'B' | 'L';
export type ToothShade = 'A1' | 'B1' | 'A2' | 'A3' | 'C2';
export type GumTone = 'PINK' | 'LIGHT_BROWN' | 'DARK_BROWN';
export interface SpTooth {
  tooth: number;
  condition: ToothCondition;
  surfaces: ToothSurface[];
  mobility: number;
}
export interface SpToothChart extends SpBase<'TOOTH_CHART'> {
  numbering: 'FDI' | 'UNIVERSAL' | 'PALMER';
  dentition: 'ADULT' | 'CHILD';
  showNumbers: boolean;
  teeth: SpTooth[];
  shade: ToothShade;
  gum: GumTone;
  allHealthyConfirmed: boolean;
}

export type DentalProcedureKey =
  | 'COMPOSITE_FILLING'
  | 'ROOT_CANAL'
  | 'PFM_CROWN'
  | 'REPLACE_FILLING'
  | 'CROWN_RECEMENTATION'
  | 'IMPLANT_WITH_CROWN'
  | 'BRIDGE'
  | 'EXTRACTION'
  | 'IMPLANT_REVIEW';
export type PlanStatus = 'PLANNED' | 'IN_PROGRESS' | 'DONE';
export interface SpPlanRow {
  id: string;
  tooth: number;
  procedureKey: DentalProcedureKey | null;
  procedureText: string;
  serviceId?: string | null;
  phase: number;
  status: PlanStatus;
  sittingsPlanned: number;
  currentSitting: number;
  typedEstimatePaise?: number | null;
  estimatePaise?: number | null;
  estimateSource?: 'TARIFF' | 'TYPED' | 'NONE';
  sittingSharesPaise?: number[];
  doneOn?: string | null;
}
export type EstimateState = 'NOT_SIGNED' | 'SIGNED' | 'CHANGED';
export interface SpDentalPlan extends SpBase<'DENTAL_PLAN'> {
  rows: SpPlanRow[];
  /** Write only: the tick "Estimate signed by patient". */
  signEstimate?: boolean;
  estimate?: { state: EstimateState; totalPaise: number; signed?: SpStamp | null; signedTotalPaise?: number | null };
  courseId?: string | null;
}

export type LabItemKind = 'CROWN' | 'BRIDGE' | 'DENTURE' | 'ALIGNER';
export interface SpLabItem {
  id: string;
  planRowId: string | null;
  tooth: number | null;
  item: LabItemKind;
  labName: string | null;
  dueDate: string | null;
}
export interface SpLabWork extends SpBase<'LAB_WORK'> {
  items: SpLabItem[];
}

export interface SpConsentItem {
  key: string;
  formLabel?: string | null;
  confirmed: boolean;
  confirmedStamp?: SpStamp | null;
}
export interface SpConsentChecklist extends SpBase<'CONSENT_CHECKLIST'> {
  items: SpConsentItem[];
}

export type AncSymptom = 'HEADACHE' | 'BLURRED_VISION' | 'BLEEDING_LEAKING' | 'REDUCED_MOVEMENTS';
export type AncOrderKey = 'PIH_WORKUP' | 'NST' | 'OGTT_75G' | 'TD_2ND_DOSE' | 'GROWTH_SCAN_FORM_F';
export type AncScanKey = 'DATING' | 'NT' | 'ANOMALY' | 'GROWTH' | 'DOPPLER_NST';
export type AncSupplement = 'IRON' | 'CALCIUM_VIT_D3' | 'FOLIC_ACID';
export interface SpAncToday {
  weightKg?: number | null;
  bpSystolic?: number | null;
  bpDiastolic?: number | null;
  fundalHeightCm?: number | null;
  fetalHeartBpm?: number | null;
  hbGdl?: number | null;
}
export interface SpAncCheck extends SpBase<'ANC_CHECK'> {
  datingScanEdd?: string | null;
  today: SpAncToday;
  symptoms: AncSymptom[];
  orders: { key: AncOrderKey; ordered?: SpStamp | null }[];
  anaemiaPlanAdded?: boolean;
  supplements?: AncSupplement[];
  scans: { key: AncScanKey; done: boolean; finding?: string | null }[];
  nextAncWeeks?: number | null;
  planNote?: string | null;
  pregnancyCourseId?: string | null;
}
export interface SpFormF extends SpBase<'FORM_F'> {
  filledAndSigned: boolean;
  marked?: SpStamp | null;
  scanKey?: string | null;
  scanRemoved?: boolean;
}
export interface SpGynaeExam extends SpBase<'GYNAE_EXAM'> {
  perAbdomen?: string | null;
  perSpeculum?: string | null;
  perVaginum?: string | null;
  chaperoneName?: string | null;
  chaperoneDeclined?: boolean;
}

export interface SpGrowth extends SpBase<'GROWTH'> {
  weightKg?: number | null;
  lengthCm?: number | null;
  headCircCm?: number | null;
  previous?: { date: string; weightKg?: number | null; lengthCm?: number | null; headCircCm?: number | null } | null;
}
export interface SpImmunization extends SpBase<'IMMUNIZATION'> {
  givenToday: { vaccineKey: string; given?: SpStamp | null }[];
  scheduleReviewed: boolean;
}
export interface SpMilestones extends SpBase<'MILESTONES'> {
  ageBandMonths: number;
  achieved: string[];
  notYet: string[];
}

export interface SpPainScore extends SpBase<'PAIN_SCORE'> {
  score: number | null;
  site?: string | null;
}
export type Gait = 'NORMAL' | 'ANTALGIC' | 'WITH_STICK' | 'WITH_WALKER';
export interface SpPostureGait extends SpBase<'POSTURE_GAIT'> {
  gait: Gait | null;
}
export interface SpRomTable extends SpBase<'ROM_TABLE'> {
  joint: string;
  rows: { movement: string; rightDeg: number | null; leftDeg: number | null }[];
}
export interface SpMmtTable extends SpBase<'MMT_TABLE'> {
  rows: { group: string; right: number | null; left: number | null }[];
}
export type TestResult = 'NOT_DONE' | 'POSITIVE' | 'NEGATIVE';
export interface SpSpecialTests extends SpBase<'SPECIAL_TESTS'> {
  joint: string;
  results: { test: string; result: TestResult }[];
}
export interface SpGoals extends SpBase<'GOALS'> {
  goals: { id: string; text: string; achieved: boolean; achievedOn?: string | null }[];
}
export type ModalityKey = 'IFT' | 'HOT_PACK' | 'ULTRASOUND' | 'TENS' | 'EXERCISE_THERAPY' | 'TAPING';
export interface SpModalities extends SpBase<'MODALITIES'> {
  given: { key: ModalityKey | null; text: string; params: string | null }[];
}
export interface SpHomeExercises extends SpBase<'HOME_EXERCISES'> {
  items: { id: string; name: string; sets?: number | null; reps?: number | null; holdSeconds?: number | null }[];
}
export interface SpSessionNote extends SpBase<'SESSION_NOTE'> {
  note: string | null;
}

export interface SpSurgicalExam extends SpBase<'SURGICAL_EXAM'> {
  abdomen: ('SOFT' | 'TENDER_RIGHT_UPPER' | 'TENDER_RIGHT_LOWER' | 'GUARDING' | 'DISTENDED' | 'LUMP_FELT')[];
  murphySign?: 'POSITIVE' | 'NEGATIVE' | null;
  jaundice?: 'NO' | 'YES' | null;
  herniaSites: ('NORMAL' | 'RIGHT_INGUINAL' | 'LEFT_INGUINAL' | 'UMBILICAL' | 'INCISIONAL')[];
  perRectal?: 'NOT_NEEDED' | 'NORMAL' | 'PILES' | 'FISSURE' | 'MASS' | null;
}
export type FitnessTestKey = 'CBC' | 'LFT' | 'KFT' | 'PT_INR' | 'BLOOD_SUGAR' | 'ECG' | 'CHEST_XRAY' | 'HIV' | 'HBSAG' | 'HCV' | 'BLOOD_GROUP';
export type FitnessTestState = 'PENDING' | 'NORMAL' | 'ABNORMAL';
export type PacState = 'NOT_DONE' | 'FIT' | 'FIT_WITH_CAUTION' | 'UNFIT';
export interface SpSurgeryFitness extends SpBase<'SURGERY_FITNESS'> {
  asa?: 'I' | 'II' | 'III' | 'IV' | null;
  otherIllness: ('NONE' | 'DIABETES' | 'HIGH_BP' | 'HEART_DISEASE' | 'ASTHMA_COPD' | 'KIDNEY_DISEASE')[];
  bloodThinner?: 'NONE' | 'ASPIRIN' | 'CLOPIDOGREL' | 'WARFARIN_DOAC' | null;
  pac: PacState;
  tests: { key: FitnessTestKey; state: FitnessTestState; result?: string | null }[];
}
export type SurgeryProcedureKey =
  | 'LAP_CHOLECYSTECTOMY'
  | 'OPEN_CHOLECYSTECTOMY'
  | 'LAP_HERNIA_TEP'
  | 'OPEN_MESH_HERNIOPLASTY'
  | 'LAP_APPENDICECTOMY'
  | 'HAEMORRHOIDECTOMY'
  | 'EXCISION_LIPOMA'
  | 'INCISION_DRAINAGE';
export type Anaesthesia = 'GA' | 'SPINAL' | 'LOCAL' | 'LOCAL_SEDATION';
export type AdmissionPlan = 'DAY_CARE' | 'IPD_1_DAY' | 'IPD_1_2_DAYS' | 'IPD_2_DAYS' | 'IPD_4_5_DAYS';
export type SurgeryPayer = 'SELF' | 'INSURANCE_TPA' | 'MJPJAY_PMJAY' | 'COMPANY';
export interface SpSurgeryPlan extends SpBase<'SURGERY_PLAN'> {
  procedureKey: SurgeryProcedureKey | null;
  procedureText: string | null;
  approach?: string | null;
  anaesthesia: Anaesthesia | null;
  admission: AdmissionPlan | null;
  otDate?: string | null;
  slotText?: string | null;
  payer: SurgeryPayer;
  typedPackagePaise?: number | null;
  packagePaise?: number | null;
  packageSource?: 'TARIFF' | 'TYPED' | 'NONE';
  booking?: { stamp: SpStamp; courseId: string; sittingId?: string | null; admissionRequested: boolean } | null;
}
export interface SpPreauth extends SpBase<'PREAUTH'> {
  sent: boolean;
  sentOn?: string | null;
  reference?: string | null;
}
export type PreopKey = 'NIL_BY_MOUTH' | 'INHALER_MORNING' | 'ANTIBIOTIC_INDUCTION' | 'PART_PREPARATION' | 'CLOT_PREVENTION' | 'ADMIT_7AM_OT_10AM';
/** SpPreopOrders.blood (opd.yaml 0.5.2, ADR-023 D14): the mockup's single-choice Blood chips. */
export type PreopBlood = 'NOT_NEEDED' | 'GROUP_AND_SAVE' | 'ONE_UNIT_RESERVED';
export interface SpPreopOrders extends SpBase<'PREOP_ORDERS'> {
  ticked: PreopKey[];
  extraLines: string[];
  blood?: PreopBlood | null;
}

export type ToxKey =
  | 'NAUSEA'
  | 'VOMITING'
  | 'MOUTH_SORES'
  | 'LOOSE_MOTIONS'
  | 'TINGLING_NUMBNESS'
  | 'FATIGUE'
  | 'FEVER_LOW_COUNTS'
  | 'PAIN'
  | 'LOSS_OF_APPETITE';
export type OncEvent = 'ER_VISIT' | 'ADMITTED' | 'FEVER_38_AT_HOME' | 'BLOOD_TRANSFUSION' | 'ANTIBIOTICS' | 'MISSED_ORAL_MEDICINES';
export interface SpOncTox extends SpBase<'ONC_TOX'> {
  symptoms: { key: ToxKey; patientGrade: number | null; confirmedGrade: number | null }[];
  events: OncEvent[];
}
export type OncSystemKey = 'cvs' | 'rs' | 'pa' | 'cns' | 'bone';
export interface SpLesion {
  id: string;
  name: string;
  baselineCm: number;
  lastCm?: number | null;
  todayCm: number | null;
}
export type LineDevice = 'PORT' | 'PICC' | 'PERIPHERAL';
export type LineCheck = 'FLUSHES_FREELY' | 'BLOOD_RETURN' | 'NO_REDNESS_SWELLING' | 'NO_PAIN' | 'DRESSING_CLEAN' | 'FLUSHED_4_WEEKS';
export type BreastSkin = 'SKIN_REDNESS' | 'PEAU_D_ORANGE' | 'ULCERATION' | 'NIPPLE_RETRACTION' | 'FIXED_TO_CHEST_WALL' | 'LUMP_NOW_MOBILE';
export interface SpOncExam extends SpBase<'ONC_EXAM'> {
  heightCm?: number | null;
  weightKg?: number | null;
  capAt2: boolean;
  signs: Partial<Record<'pallor' | 'icterus' | 'cyanosis' | 'clubbing' | 'lymphNodes' | 'pedalOedema' | 'dehydration', SpSignState>>;
  lesions: SpLesion[];
  newLesionSeen: boolean;
  breastSkin: BreastSkin[];
  systems: Partial<Record<OncSystemKey, SpSystemFinding | null>>;
  line: { device: LineDevice | null; checks: LineCheck[] };
}
export interface SpOncLabs extends SpBase<'ONC_LABS'> {
  drawnAt: string | null;
  values: Partial<Record<'hb' | 'anc' | 'plt' | 'cr' | 'bili' | 'alt' | 'lvef', number | null>>;
  source?: 'TYPED';
}
export interface SpOncEcog extends SpBase<'ONC_ECOG'> {
  grade: number | null;
}
export type TakeHome = 'ONDANSETRON_8' | 'DEXAMETHASONE_4' | 'PANTOPRAZOLE_40' | 'PEGFILGRASTIM_6' | 'BENZYDAMINE_MOUTHWASH' | 'LOPERAMIDE_2';
export type Referral =
  | 'RADIOTHERAPY'
  | 'SURGICAL_ONCOLOGY'
  | 'PALLIATIVE_CARE'
  | 'DIETITIAN'
  | 'PSYCHO_ONCOLOGY_COUNSELLING'
  | 'FERTILITY_COUNSELLING'
  | 'SOCIAL_WORKER_SCHEME_PAPERS'
  | 'CARDIOLOGY_ECHO';
export interface SpChemoPlan extends SpBase<'CHEMO_PLAN'> {
  takeHome: TakeHome[];
  nextCycleDate?: string | null;
  imagingText?: string | null;
  referrals: Referral[];
  cycleNote?: string | null;
}

export type OutcomeScaleKey = 'ODI' | 'NDI' | 'WOMAC' | 'LEFS';
/** 0.5.1 (ADR-023 D13): one typed total score per scale; the scales' ranges are the sanity limits (H18, H20). */
export interface SpOutcomeScales extends SpBase<'OUTCOME_SCALES'> {
  rows: { scale: OutcomeScaleKey; score: number | null }[];
}

export type EmrSpecialtyValue =
  | SpEmpty
  | SpVitalsLog
  | SpExamGeneral
  | SpToothChart
  | SpDentalPlan
  | SpLabWork
  | SpConsentChecklist
  | SpAncCheck
  | SpFormF
  | SpGynaeExam
  | SpGrowth
  | SpImmunization
  | SpMilestones
  | SpPainScore
  | SpPostureGait
  | SpRomTable
  | SpMmtTable
  | SpSpecialTests
  | SpGoals
  | SpModalities
  | SpHomeExercises
  | SpSessionNote
  | SpSurgicalExam
  | SpSurgeryFitness
  | SpSurgeryPlan
  | SpPreauth
  | SpPreopOrders
  | SpOncTox
  | SpOncExam
  | SpOncLabs
  | SpOncEcog
  | SpChemoPlan
  | SpOutcomeScales;

export type OncDecisionKey = 'GIVE_AS_PLANNED' | 'GIVE_REDUCED' | 'DELAY_1_WEEK' | 'CHANGE_REGIMEN' | 'STOP';
export type OncDeviationReason = 'PATIENT_PREFERENCE' | 'TUMOUR_BOARD_ADVICE' | 'COUNTS_RECOVERING' | 'CLINICAL_JUDGEMENT' | 'SCHEME_COST';

export interface EmrScreenInput {
  mode?: EmrScreenMode | null;
  oncDecision?: { choice: OncDecisionKey; reasonKey?: OncDeviationReason | null } | null;
}

export interface EmrStillNeededItem {
  key: string;
  sectionKey: string | null;
  label: string;
  count: number | null;
}

export interface EmrFlag {
  field: string;
  label: string;
  alert: boolean;
}

export interface EmrSpecialtyView {
  profile: EmrSpecialtyProfile;
  mode: EmrScreenMode | null;
  tagNote: string;
  /** 0.5.1: sections the effective mode hides; the client's own table is a fallback only. */
  hiddenSectionKeys?: string[];
  vitalsFlags?: EmrFlag[];
  dental?: {
    estimateTotalPaise: number;
    estimateState: EstimateState;
    doneTodayRowIds: string[];
    childTabDefault: boolean;
  } | null;
  og?: {
    gestationalAge: { weeks: number; days: number } | null;
    edd: string | null;
    eddSource: 'LMP' | 'DATING_SCAN' | 'NONE';
    daysToEdd: number | null;
    obstetricScore: string | null;
    weightChangeKg: number | null;
    notes: EmrFlag[];
    alerts: ('POSSIBLE_PRE_ECLAMPSIA' | 'ANAEMIA_MILD' | 'ANAEMIA_MODERATE' | 'ANAEMIA_SEVERE' | 'WARNING_SIGN')[];
    riskChips: EmrFlag[];
    scanStates: { key: string; state: 'DONE' | 'DUE' | 'LATER' }[];
    dueOrders: { key: string; window: string; ordered: boolean }[];
    formFRequired: boolean;
    formFCount: { complete: number; total: number } | null;
  } | null;
  paeds?: {
    /** 0.5.3: false shows "Date of birth not recorded..." (SO-73-6). Missing (older server) counts as recorded. */
    dateOfBirthRecorded?: boolean;
    /** 0.5.3: for the printed vaccination card only. Never logged or stored. */
    dateOfBirth?: string | null;
    ageMonths: number | null;
    ageDays: number | null;
    growthNotes: EmrFlag[];
    vaccines: { vaccineKey: string; state: 'GIVEN' | 'DUE_TODAY' | 'OVERDUE' | 'LATER'; givenOn?: string | null; givenHere?: boolean }[];
    dueTodayCount: number;
    overdueCount: number;
    nextDue: { vaccineKey: string; groupLabel: string } | null;
    milestonesSetUp: boolean;
    doseWeightKg: number | null;
    doseWeightChangedRowIds: string[];
  } | null;
  physio?: {
    sessionNo: number | null;
    sessionsPlanned: number | null;
    packageName: string | null;
    romPercents: { movement: string; rightPct: number | null; normal: string | null }[];
    outcomeScales?: { scale: OutcomeScaleKey; previousScore: number | null; previousDate: string | null; todayScore: number | null }[];
  } | null;
  surgery?: {
    fitness: { state: 'FIT' | 'TESTS_PENDING' | 'PAC_NOT_DONE' | 'NOT_FIT'; text: string };
    depositPaise: number | null;
    payerChip: string;
    bookState: 'NOT_BOOKED' | 'READY' | 'BOOKED' | 'CHANGED';
    bookMissing: string[];
    consentCount: { ticked: number; total: number };
  } | null;
  onco?: EmrOncoView | null;
}

export type OncResponse = 'COMPLETE_RESPONSE' | 'PARTIAL_RESPONSE' | 'STABLE_DISEASE' | 'PROGRESSIVE_DISEASE';
export type OncAlert = 'GRADE_3_PLUS' | 'LAB_OUT_OF_RANGE' | 'FEVER' | 'WEIGHT_CHANGE_10' | 'CUMULATIVE_OVER_400' | 'PORT_NO_BLOOD_RETURN';
export interface EmrOncoView {
  bsa: number | null;
  bsaUsed: number | null;
  capped: boolean;
  cycle1WeightKg: number | null;
  weightChangePct: number | null;
  recalculateDoses: boolean;
  lesionChanges: { id: string; pctFromBaseline: number | null; colour: 'GREEN' | 'RED' | 'NONE' }[];
  sums: { baseline: number; previous: number; today: number; nadir: number; pctFromBaseline: number; pctFromNadir: number };
  response: OncResponse | null;
  labFails: { key: string; direction: 'BELOW_NEEDED' | 'ABOVE_NEEDED' }[];
  labsBadge: string;
  labsFresh: boolean | null;
  fever: boolean;
  feverOrAdmissionEvent: boolean;
  /** 0.5.1: protocol TC or a fever or admission event; Pegfilgrastim starts ticked with the note "suggested: fever / regimen". */
  pegfilgrastimSuggested?: boolean;
  worstGrade: number | null;
  ecogText: string | null;
  lineReady: boolean | null;
  suggestion: OncDecisionKey | null;
  reasons: { key: 'ECOG' | 'COUNTS_FEVER' | 'WORST_GRADE' | 'RESPONSE' | 'HEART' | 'LINE'; ok: boolean; text: string }[];
  decision: {
    choice: OncDecisionKey;
    reasonKey: OncDeviationReason | null;
    suggestionAtDecision: OncDecisionKey;
    stamp: SpStamp;
  } | null;
  factsChanged: boolean;
  alerts: OncAlert[];
  cumulativeDoxorubicinMgM2: number | null;
  cycleNo: number | null;
  nextCycle: { defaultDate: string; label: 'NEXT_CHEMO' | 'REBOOKED_CHEMO'; bloodTestDate: string; echo: string } | null;
  orderStatus: 'NONE' | 'DRAFT' | 'SENT' | 'HELD';
}

// ---- reference (GET /emr/specialty-reference/{profile}) ----

export interface EmrReferenceItem {
  key: string;
  label: string;
  sub?: string | null;
  group?: string | null;
  number?: number | null;
  tariff?: { serviceId: string; pricePaise: number } | null;
  /** 0.5.1: the drawn reason or note ("Nausea", "2 h apart from iron"); goes to the prescription row note. */
  note?: string | null;
  /** 0.5.1: ticked at the start where the list has a start state (oncTakeHome, oncReferrals). */
  defaultOn?: boolean | null;
  /** 0.5.1: starting values the client copies into the row it adds. */
  defaults?: {
    frequency?: EmrRxFrequency | null;
    frequencyText?: string | null;
    days?: number | null;
    sittings?: number | null;
    anaesthesia?: Anaesthesia | null;
    admission?: AdmissionPlan | null;
  } | null;
  /** 0.5.1: the seeded master medicine, so the added row keeps the allergy check; null adds a typed row. */
  medicine?: { medicineId: string; name: string; form: string | null } | null;
}

export type ChemoProtocolKey = 'AC' | 'PACLI_3W' | 'PACLI_WEEKLY' | 'TC';
export type EmeticRisk = 'HIGH' | 'MODERATE' | 'LOW';
export interface ChemoProtocol {
  key: ChemoProtocolKey;
  label: string;
  subLine: string;
  intervalDays: number;
  emeticRisk: EmeticRisk;
  suggestedOnChange: boolean;
  drugs: {
    drugKey: string;
    name: string;
    dosePerM2: number;
    infusionMinutes: number;
    vialStepMg: number;
    vesicant: boolean;
    anthracycline: boolean;
    note?: string | null;
  }[];
  premeds: { key: string; text: string; minutes: number }[];
}

export interface EmrGrowthBand {
  measure: string;
  sex: string | null;
  ageMonthsFrom: number;
  ageMonthsTo: number;
  upperExclusive: number | null;
  label: string;
  alert: boolean;
}

export interface EmrSpecialtyReference {
  profile: EmrSpecialtyProfile;
  tagNote: string;
  lists?: Partial<Record<ReferenceListName, EmrReferenceItem[]>>;
  thresholds?: Record<string, number>;
  protocols?: ChemoProtocol[];
  growthBands?: EmrGrowthBand[];
}

// ---- history (GET /emr/records/{id}/specialty-history) ----

export interface EmrHistoryVitals {
  recordedAt: string;
  recordedByName: string;
  bp: string | null;
  pulse: number | null;
  spo2: number | null;
  temperature: string | null;
  respRate: number | null;
  pain: number | null;
  how: string;
  flags?: EmrFlag[];
}
export interface EmrSpecialtyHistory {
  profile: EmrSpecialtyProfile;
  someHidden: boolean;
  vitals?: EmrHistoryVitals[];
  trend?: {
    bp: { date: string; systolic: number; diastolic: number }[];
    sugar: { date: string; mgdl: number }[];
  } | null;
  ancVisits?: {
    date: string;
    gestationalAge: string | null;
    weightKg: number | null;
    bp: string | null;
    fundalHeightCm: number | null;
    hbGdl: number | null;
    note: string | null;
    today: boolean;
  }[];
  growthPoints?: { date: string; ageMonths: number | null; weightKg: number | null; lengthCm: number | null; headCircCm: number | null }[];
  oncologyCycles?: {
    cycleNo: number;
    date: string;
    protocolKey: string;
    levelPct: number;
    weightKg: number | null;
    bsaUsed: number | null;
    labs: Record<string, number | null>;
    grades: Record<string, number | null> | null;
  }[];
  physioProgress?: {
    sessionsDone: number;
    sessionsPlanned: number | null;
    painFirst: number | null;
    painLast: number | null;
    romFirst: Record<string, unknown> | null;
    romLast: Record<string, unknown> | null;
    goalsAchieved: string[];
    onlyFirstVisit: boolean;
  } | null;
}

// ---- surgery booking ----

export interface SurgeryBookingResult {
  record: import('../emr.model').EmrRecordView;
  courseId: string;
  sittingId: string | null;
  admissionRequested: boolean;
  message: string;
}

// ---- chemotherapy order ----

export type ChemoStatus = 'NONE' | 'DRAFT' | 'SENT' | 'HELD';
export type ChemoLevel = 100 | 90 | 80 | 75;
export type ChemoScheme = 'APPROVED' | 'PREAUTH_RAISED' | 'NOT_IN_PACKAGE' | 'SELF_PAY';
export type ReductionReasonKey =
  | 'LOW_NEUTROPHILS_LAST_CYCLE'
  | 'FEVER_LOW_COUNTS'
  | 'GRADE_3_SIDE_EFFECT'
  | 'NEUROPATHY'
  | 'LIVER_FUNCTION'
  | 'FRAILTY';

export interface ChemoOrderInput {
  revision: number;
  protocolKey: ChemoProtocolKey;
  levelPct: ChemoLevel;
  reductionReasonKey?: ReductionReasonKey | null;
  reductionReasonText?: string | null;
  finalDoses?: { drugKey: string; finalMg: number }[];
  premedsNotGiven?: string[];
  allergyConfirmed?: boolean;
  consentNewSigned?: boolean;
  scheme?: ChemoScheme;
  priorOutsideDoxorubicinMgM2?: number;
}

export type ChemoCheckKey =
  | 'DECISION_RECORDED'
  | 'FIT_TO_TREAT'
  | 'LABS_72H'
  | 'BSA_TODAY'
  | 'CUMULATIVE_DOSE'
  | 'REDUCTION_REASON'
  | 'ALLERGIES_REVIEWED'
  | 'CONSENT_REGIMEN'
  | 'SCHEME_APPROVAL'
  | 'LINE_VESICANT';

export interface ChemoOrderView {
  id?: string | null;
  recordId: string;
  status: ChemoStatus;
  revision?: number;
  decisionRecorded: boolean;
  decision?: OncDecisionKey | null;
  protocolKey: ChemoProtocolKey | null;
  protocolTags?: Record<string, 'IN_PLAN' | 'SUGGESTED' | 'NEW_CONSENT'>;
  levelPct: ChemoLevel;
  reductionReason?: string | null;
  bsa?: { raw: number | null; used: number | null; capped: boolean; source: string };
  weightChangePct?: number | null;
  recalculated?: boolean;
  drugs: {
    drugKey: string;
    name: string;
    dosePerM2: number;
    calculatedMg: number | null;
    roundedMg: number | null;
    finalMg: number | null;
    finalEdited: boolean;
    moreThan5Pct: boolean;
    vesicant?: boolean;
    lifetime: { earlierMgM2: number; thisCycleMgM2: number; totalMgM2: number; limitMgM2: number; red: boolean } | null;
    infusionMinutes?: number;
    note?: string | null;
  }[];
  premeds: { key: string; text: string; minutes: number; given: boolean }[];
  emeticRisk: EmeticRisk | null;
  labsDrawnAt?: string | null;
  allergyConfirmed?: boolean;
  consentNewSigned?: boolean;
  scheme?: string | null;
  priorOutsideDoxorubicinMgM2?: number;
  checks: {
    key: ChemoCheckKey;
    passed: boolean;
    detail: string;
    action?: 'GO' | 'CONFIRM' | 'UNDO' | 'MARK_SIGNED' | 'RAISE_PREAUTH' | null;
  }[];
  passedCount: number;
  totalCount: number;
  checker?: { membershipId: string; name: string; role: string; namedAt: string } | null;
  secondCheck: {
    state: 'NOT_NAMED' | 'WAITING' | 'CONFIRMED' | 'CLEARED';
    confirmedByName?: string | null;
    confirmedRole?: string | null;
    via?: 'SIGN_IN' | 'PIN' | null;
    confirmedAt?: string | null;
  };
  stages: { decided?: boolean; dosesChecked?: boolean; secondCheck?: boolean; pharmacy?: 'NOT_SENT' | 'SENT'; chair?: 'LATER' };
  send: { ready?: boolean; note?: string; holdMode?: boolean };
  sentAt?: string | null;
  sentByName?: string | null;
  hold?: { kind: 'DELAY' | 'STOP'; reason: string; date: string } | null;
  factsChangedSinceSent?: boolean;
  fingerprint: string;
}

export interface ChemoCheckerOption {
  membershipId: string;
  name: string;
  role: 'NURSE' | 'PHARMACIST' | 'DOCTOR' | 'VISITING_DOCTOR' | 'OWNER';
  hasPin: boolean;
}
export interface ChemoCheckerInput {
  revision: number;
  checkerMembershipId: string | null;
}
export interface ChemoSecondCheckPinInput {
  orderRevision: number;
  fingerprint: string;
  checkerMembershipId: string;
  pin: string;
}
export interface ChemoOrderActionInput {
  revision: number;
  fingerprint: string;
}
export interface ChemoHoldInput {
  revision: number;
  reasonText?: string | null;
}
export interface ChemoCheckPage {
  items: {
    orderId: string;
    patientName: string;
    uhid: string | null;
    age: number | null;
    sex: 'F' | 'M' | 'O';
    prescriberName: string;
    protocolLabel: string;
    namedAt: string;
  }[];
  total: number;
  page: number;
  pageSize: number;
}
export interface ChemoCheckSummary {
  orderId: string;
  patientName: string;
  uhid: string | null;
  age: number | null;
  sex: 'F' | 'M' | 'O';
  prescriberName: string;
  protocolLabel: string;
  levelPct: number;
  reductionReason?: string | null;
  drugs: { name: string; calculatedMg: number; roundedMg: number; finalMg: number; moreThan5Pct: boolean }[];
  bsa: { used: number; capped: boolean; source: string };
  labsDrawnAt: string | null;
  decision: OncDecisionKey;
  checks: { key: string; passed: boolean; detail: string }[];
  secondCheck: { state: 'WAITING' | 'CONFIRMED'; via?: string | null; confirmedAt?: string | null };
  fingerprint: string;
  canConfirm: boolean;
}

// ---- visit templates (SAH-60-4) ----

export interface EmrVisitTemplateItems {
  complaints?: { itemKey: string | null; text: string; newToList: boolean }[];
  diagnoses?: { itemKey: string | null; text: string; newToList: boolean }[];
  investigations?: { itemKey: string | null; text: string; newToList: boolean }[];
  services?: { itemKey: string | null; text: string; newToList: boolean }[];
  medicines?: {
    medicineId: string | null;
    name: string;
    frequency?: string | null;
    days?: number | null;
    when?: string | null;
  }[];
  advice?: { itemKey: string | null; text: string; newToList: boolean }[];
  repeatLastRx?: boolean;
}
export interface EmrVisitTemplate {
  id: string;
  name: string;
  subLine: string | null;
  items: EmrVisitTemplateItems;
}
export interface EmrVisitTemplateInput {
  name: string;
  subLine?: string | null;
  items: EmrVisitTemplateItems;
}
