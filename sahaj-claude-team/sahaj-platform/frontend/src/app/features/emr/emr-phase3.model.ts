import type { AgeDetail } from '../../shared/format/patient-age';
import type { EmrListKind, EmrPart, StarterKey, DepartmentKey, EmrRecordView } from './emr.model';

/**
 * Types for EMR phase 3, written by hand from `docs/api/opd.yaml` 0.4.0 (ADR-022): the EMR list
 * (SAH-56), the prescription and the allergy check (SAH-48), the body map (SAH-57), patient history
 * (SAH-50), the live case paper (SAH-55) and the form builder (SAH-59). No field is invented.
 * Everything here is clinical or patient data for the screen only: nothing is logged or stored in
 * the browser.
 */

// ---- EMR list (SAH-56) ----

export interface EmrListItem {
  id: string;
  itemKey: string | null;
  text: string;
  newToList: boolean;
  /** Complaints: duration. Diagnosis: type. Tests: priority. Advice: language. Services: qty. */
  attr1: string;
  /** Complaints: severity. Diagnosis: status. Tests: where. Advice: send by. Services: billing. */
  attr2: string;
  active: boolean;
  // Response only (server-stamped or read through other modules); never sent back.
  code?: string | null;
  addedAt?: string | null;
  addedByMembershipId?: string | null;
  addedByName?: string | null;
  unitPricePaise?: number | null;
  billed?: 'ON_BILL' | 'ON_PAID_BILL' | null;
}

export interface EmrListValue {
  component: 'EMR_LIST';
  kind: EmrListKind;
  schemaVersion: 1;
  items: EmrListItem[];
}

// ---- Prescription (SAH-48) ----

export type EmrRxFrequency = '1-0-0' | '1-0-1' | '0-0-1' | '1-1-1' | '0-1-0';
export type EmrRxWhen = 'AFTER_FOOD' | 'BEFORE_FOOD' | 'BEDTIME' | 'MORNING_EMPTY_STOMACH';
export type EmrRxSource = 'SEARCH' | 'FAVOURITE' | 'REPEAT' | 'COPY' | 'TYPED' | 'DOSE_BY_WEIGHT' | 'SPECIALTY_LIST';
export type EmrAllergyClassKey = 'SULFA' | 'PENICILLIN';

export interface EmrPrescriptionRow {
  id: string;
  medicineId: string | null;
  name: string;
  form: string | null;
  /** 0.5.0: null only when frequencyText is set (dose by weight, take-home lines). */
  frequency: EmrRxFrequency | null;
  days: number;
  when: EmrRxWhen;
  source: EmrRxSource;
  /** 0.5.0 (SAH-62-3, SAH-70-1): for example "4.5 ml (108 mg)". */
  doseText?: string | null;
  frequencyText?: string | null;
  note?: string | null;
  /** 0.5.0 (SAH-62-3): dose by weight; the server recomputes and refuses a mismatch. */
  calculation?: {
    doseRuleKey: 'PARACETAMOL_120_5' | 'PARACETAMOL_250_5' | 'IBUPROFEN_100_5';
    weightKg: number;
    mg: number;
    ml: number;
    capped?: boolean;
  } | null;
  allergyReview?: { allergyClass: string; confirmed: boolean; reviewedAt?: string | null; reviewedByMembershipId?: string | null } | null;
  addedAt?: string | null;
  addedByMembershipId?: string | null;
}

export interface EmrPrescriptionValue {
  component: 'PRESCRIPTION';
  schemaVersion: 1;
  rows: EmrPrescriptionRow[];
}

export interface EmrAllergyClass {
  classKey: EmrAllergyClassKey;
  label: string;
}

export interface EmrAllergyAlert {
  sectionKey: string;
  fieldKey: string;
  rowId: string;
  medicineKey: string;
  medicineName: string;
  allergyClass: EmrAllergyClassKey;
  allergyClassLabel: string;
  reviewed: boolean;
  reviewedAt: string | null;
  reviewedByName: string | null;
}

export interface EmrMedicineOption {
  medicineId: string;
  name: string;
  form: string;
  favourite: boolean;
  allergyClass: EmrAllergyClassKey | null;
  defaultFrequency: EmrRxFrequency;
  defaultDays: number;
  defaultWhen: EmrRxWhen;
  useCount?: number;
}

export interface EmrMedicinePage {
  items: EmrMedicineOption[];
  total: number;
  page: number;
  pageSize: number;
}

export interface EmrCopyItemsInput {
  source: 'LAST_SIGNED_PRESCRIPTION' | 'PAST_RECORD';
  sourceRecordId?: string | null;
  what: 'MEDICINES' | 'TESTS';
}

export interface EmrCopyItemsResult {
  added: number;
  skipped: number;
  record: EmrRecordView;
}

// ---- Body map (SAH-57) ----

export type BodySide = 'FRONT' | 'BACK';
export type BodyFinding =
  | 'WOUND'
  | 'SWELLING'
  | 'TENDERNESS'
  | 'BRUISE'
  | 'BURN'
  | 'RASH'
  | 'LUMP'
  | 'FRACTURE_SUSPECTED'
  | 'SCAR'
  | 'PAIN';
export type BodyCause =
  | 'NO_INJURY'
  | 'FALL'
  | 'ROAD_ACCIDENT'
  | 'ASSAULT'
  | 'ANIMAL_BITE'
  | 'BURN'
  | 'SPORTS'
  | 'WORK_INJURY';
export type BodySeverity = 'MILD' | 'MODERATE' | 'SEVERE';

export interface EmrBodyMapPin {
  no: number;
  side: BodySide;
  x: number;
  y: number;
  region: string;
  where: string;
  finding: BodyFinding;
  cause: BodyCause;
  severity: BodySeverity;
  sizeCm: number;
  note: string;
  mlc: boolean;
  recordedAt?: string | null;
  recordedByMembershipId?: string | null;
  recordedByName?: string | null;
}

export interface EmrBodyMapValue {
  component: 'BODY_MAP';
  schemaVersion: 1;
  pins: EmrBodyMapPin[];
  nextPinNo?: number;
  noFindingsConfirmed: boolean;
  legacyText?: string | null;
}

// ---- Case paper (SAH-55) ----

export type PrintLanguage = 'EN' | 'MR' | 'HI';
export type PaperSize = 'A4' | 'A5';

export interface EmrPrintPreferences {
  language: PrintLanguage | null;
  paperSize: PaperSize | null;
}

export type EmrCasePaperHeading =
  | 'CHIEF_COMPLAINTS'
  | 'ALLERGIES'
  | 'MEDICAL_HISTORY'
  | 'EXAMINATION'
  | 'DIAGNOSIS'
  | 'INVESTIGATIONS'
  | 'MEDICINES'
  | 'PROCEDURES'
  | 'ADVICE'
  | 'FOLLOW_UP'
  | 'VITALS_LINE'
  | 'NONE';

export interface EmrCasePaperContext {
  letterhead: { clinicName: string; address: string | null; phone: string | null };
  departmentName: string;
  doctor: { name: string; qualification: string | null; registrationNo: string | null };
  /** `ageDetail` 0.5.3 (SAH-73-4): the Paediatrics meta line prints it; other departments print `age`. */
  patient: { name: string; age: number | null; ageDetail?: AgeDetail | null; sex: 'F' | 'M' | 'O'; uhid: string | null };
  visitLine: string;
  clinicDefaults: { paperSize: PaperSize; language: PrintLanguage; capitals: boolean };
  chosen: EmrPrintPreferences;
  headings: { heading: EmrCasePaperHeading; en: string; mr: string; hi: string }[];
  sectionHeadings: { sectionKey: string; heading: EmrCasePaperHeading }[];
  signature: { signed: boolean; signerName: string | null; signedAt: string | null };
  /** 0.6.0 (SAH-51-5). Printed under the signature, newest last. */
  addenda?: { id: string; reason: string; text: string; createdAt: string; createdByName: string }[];
  printBlocked: 'ALLERGY_ALERT' | null;
  share: { available: false; reason: string };
}

// ---- Patient history (SAH-50) ----

export type PastVisitKind = 'OPD' | 'IPD' | 'IPD_ROUND';
export type PastVisitFilter = 'ALL' | 'OPD' | 'IPD';

export interface EmrPastVisit {
  kind: PastVisitKind;
  id: string;
  date: string;
  title: string;
  place: string;
  signed: boolean;
}

export interface EmrPastVisitPage {
  counts: { opd: number; ipd: number };
  someHidden: boolean;
  items: EmrPastVisit[];
  page: number;
  pageSize: 8;
  hasMore: boolean;
}

export interface EmrPastVisitDetail {
  kind: PastVisitKind;
  id: string;
  signed: boolean;
  lines: { label: string; value: string }[];
  medicines: { name: string; frequency: EmrRxFrequency; days: number; when: EmrRxWhen }[];
  results: { test: string; value: string; abnormal: boolean }[];
  tests: string[];
  /** 0.7.0 (SAH-75-8 G16-1): the heading of the medicines block; absent from an older server reads MEDICINES. */
  medicinesHeading?: 'MEDICINES' | 'DISCHARGE_MEDICINES' | 'TAKE_HOME_MEDICINES';
  canCopyMedicines: boolean;
  canRepeatTests: boolean;
  copyDisabledReason: 'RECORD_SIGNED' | null;
  open: { target: 'RECORD' | 'ADMISSION'; id: string };
}

export interface EmrKeyFacts {
  starterKey: StarterKey | null;
  facts: { key: string; label: string; value: string | null; important: boolean }[];
}

// ---- Form builder (SAH-59) ----

export type EmrFormStarterKey = 'diabetic_foot' | 'gcs' | 'morse' | 'mlc_injury' | 'pre_anaesthetic' | 'untitled';

export type EmrFormFieldType =
  | 'SHORT_TEXT'
  | 'LONG_TEXT'
  | 'NUMBER'
  | 'DATE'
  | 'TIME'
  | 'YES_NO'
  | 'SINGLE_CHOICE'
  | 'MULTI_CHOICE'
  | 'DROPDOWN'
  | 'VITALS_BLOCK'
  | 'BODY_MAP'
  | 'TOOTH_CHART'
  | 'MEDICINE_LIST'
  | 'LAB_TESTS'
  | 'DIAGNOSIS'
  | 'SCORE'
  | 'SCORE_BAND'
  | 'SECTION_HEADING'
  | 'INFO_NOTE'
  | 'SIGNATURE'
  | 'PHOTO'
  | 'FILE_UPLOAD'
  | 'HANDWRITING_PAD';

export type EmrFormWho = 'DOCTOR' | 'NURSE' | 'RECEPTION' | 'PATIENT';
export type EmrFormVisitType = 'NEW_CONSULT' | 'FOLLOW_UP' | 'PROCEDURE_VISIT' | 'TELECONSULT' | 'IPD_DAILY_ROUND';
export type EmrFormPrintLanguage = 'EN' | 'EN_MR' | 'EN_HI';
export type EmrFormPlace = 'EMERGENCY' | 'IPD_WARD';

export interface EmrFormOption {
  key: string;
  label: string;
  labelMr?: string | null;
  labelHi?: string | null;
  score: number;
}

export interface EmrFormScoreRule {
  kind: 'NUMBER_BELOW' | 'CHOSEN_COUNT_BELOW';
  fieldKey: string;
  threshold: number;
  points: number;
}

export interface EmrFormBand {
  from: number;
  label: string;
}

export interface EmrFormField {
  key: string;
  type: EmrFormFieldType;
  label: string;
  labelMr?: string | null;
  labelHi?: string | null;
  help?: string | null;
  required: boolean;
  width: number;
  unit?: string | null;
  min?: number | null;
  max?: number | null;
  normalFrom?: number | null;
  normalTo?: number | null;
  options?: EmrFormOption[];
  print: boolean;
  copyFromLast: boolean;
  whoCanFill: EmrFormWho[];
  standardCode?: string | null;
  showIf?: { fieldKey: string; optionKey: string } | null;
  score?: { sourceFieldKeys: string[]; rules: EmrFormScoreRule[] } | null;
  band?: { sourceFieldKey: string; bands: EmrFormBand[] } | null;
}

export interface EmrFormSettings {
  useIn: { departmentKeys: DepartmentKey[]; places: EmrFormPlace[] };
  emrPart: EmrPart;
  visitTypes: EmrFormVisitType[];
  whoCanFill: EmrFormWho[];
  printLanguage: EmrFormPrintLanguage;
  approvalRequired: boolean;
}

export interface EmrFormDefinition {
  schemaVersion: 1;
  name: string;
  settings: EmrFormSettings;
  fields: EmrFormField[];
}

export interface EmrFormDraftInput {
  revision: number;
  name: string;
  settings: EmrFormSettings;
  fields: EmrFormField[];
}

export type EmrFormState = 'DRAFT' | 'WAITING_APPROVAL' | 'SENT_BACK' | 'LIVE' | 'RETIRED';

export interface EmrFormSummary {
  formKey: string;
  name: string;
  state: EmrFormState;
  chipVersionNo: number;
  liveVersionNo: number;
  starterKey: EmrFormStarterKey | null;
  useIn: { departmentKeys: DepartmentKey[]; places: EmrFormPlace[] };
  emrPart: EmrPart;
  fieldCount: number;
  logicCount: number;
  scoredCount: number;
  updatedAt: string;
}

export interface EmrFormList {
  canEdit: boolean;
  canApprove: boolean;
  forms: EmrFormSummary[];
}

export interface EmrFormVersionRow {
  versionNo: number;
  state: 'DRAFT' | 'WAITING_APPROVAL' | 'SENT_BACK' | 'LIVE' | 'SUPERSEDED';
  at: string;
  byName: string;
}

export interface EmrFormView extends EmrFormSummary {
  canEdit: boolean;
  canApprove: boolean;
  revision: number;
  definition: EmrFormDefinition;
  sentBackReason: string | null;
  versions: EmrFormVersionRow[];
}

export interface EmrFormStarterSummary {
  starterKey: EmrFormStarterKey;
  name: string;
  description: string;
  suitedFor: string;
}

export interface EmrFormStarterDetail extends EmrFormStarterSummary {
  definition: EmrFormDefinition;
}

export interface EmrFormVitalsValue {
  bpSystolic?: number | null;
  bpDiastolic?: number | null;
  pulse?: number | null;
  spo2?: number | null;
  temperatureF?: number | null;
  weightKg?: number | null;
  heightCm?: number | null;
}
