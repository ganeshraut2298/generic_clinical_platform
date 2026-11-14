/**
 * Types for the Doctor EMR endpoints under `/api/v1/opd/emr/**` and `PATCH /visits/{id}/visit-type`,
 * written by hand from `docs/api/opd.yaml` 0.4.0 (SAH-45 to SAH-48, SAH-50, SAH-52 to SAH-59).
 * No field is invented. Patient names, allergy and chronic text and typed field values are for the
 * screen only: nothing here is logged or stored in the browser.
 */

import type { AgeDetail } from '../../shared/format/patient-age';
import type { EmrAllergyAlert, EmrFormDefinition, EmrPrintPreferences } from './emr-phase3.model';
import type { EmrAddendum, EmrAdmissionState, EmrEditHold } from './emr-finish.model';
import type {
  EmrScreenInput,
  EmrSpecialtyComponent,
  EmrSpecialtyProfile,
  EmrSpecialtyView,
  EmrStillNeededItem,
} from './specialty/specialty.model';

export type EmrListKind = 'COMPLAINTS' | 'DIAGNOSIS' | 'INVESTIGATIONS' | 'ADVICE' | 'SERVICES';

export type StarterKey =
  | 'gm'
  | 'dental'
  | 'og'
  | 'paeds'
  | 'physio'
  | 'ortho'
  | 'eye'
  | 'ent'
  | 'derm'
  | 'diab'
  | 'cardio'
  | 'ayush'
  | 'onco'
  | 'surgery'
  | 'psych';

/** A starter key, or the server-made `d_<10 characters>` key of an added department. */
export type DepartmentKey = string;
export type SectionKey = string;
export type FieldKey = string;

export type EmrPart = 'ASSESSMENT' | 'TREATMENT';
export type EmrSectionKind = 'COMMON' | 'SPECIALTY' | 'COMPLIANCE';
export type EmrFillerRole = 'DOCTOR' | 'NURSE' | 'RECEPTION';
export type EmrLayoutSource = 'PUBLISHED' | 'STARTER';
export type EmrChoiceSource = 'DOCTOR_SPECIALTY' | 'DOCTOR_DEPARTMENT' | 'CLINIC_DEFAULT' | 'SWITCHED';
export type EmrMaster = 'complaints' | 'diagnoses' | 'medicines' | 'investigations' | 'advice';

/** The 30 field types of the library. A value this client does not know is shown as "Not available yet". */
export type EmrFieldType =
  | 'TEXT'
  | 'RICH_TEXT'
  | 'NUMBER'
  | 'NUMBER_UNIT'
  | 'NUMBER_TREND'
  | 'CALCULATED'
  | 'FORMULA'
  | 'RULE'
  | 'REFERENCE'
  | 'DATE'
  | 'CALENDAR'
  | 'TOGGLE'
  | 'DROPDOWN'
  | 'DROPDOWNS'
  | 'SINGLE_CHOICE'
  | 'MULTI_SELECT'
  | 'CHECKLIST'
  | 'CHIPS'
  | 'SEARCH_LIST'
  | 'TABLE'
  | 'GRID'
  | 'PATTERN'
  | 'TEMPLATE'
  | 'DOCTOR_HOSPITAL'
  | 'CHART'
  | 'BODY_MAP'
  | 'TOOTH_PICKER'
  | 'LIBRARY'
  | 'FILE'
  | 'SIGNATURE';

export type EmrValueKind =
  | 'TEXT'
  | 'NUMBER'
  | 'NUMBER_UNIT'
  | 'DATE'
  | 'BOOLEAN'
  | 'CHOICE'
  | 'MULTI_CHOICE'
  | 'ITEM_LIST'
  | 'TABLE'
  | 'STRUCTURED'
  | 'DISPLAY_ONLY'
  | 'LATER'
  | 'COMPONENT'
  | 'COVERED';

export type EmrOptionsSource = 'NONE' | 'FIELD' | 'MASTER' | 'SERVICES';

export interface EmrFieldOption {
  key: FieldKey;
  label: string;
  isNone: boolean;
  /** 0.7.0 (SAH-75-6): shown red when chosen (a habit, an abnormal finding). False when absent. */
  alert?: boolean;
  /** 0.7.1 (ADR-026 D13): the second line of a tile ("Barrier", "Daily pill"). Null or absent when the option has none. */
  hint?: string | null;
}

export interface EmrTableColumn {
  key: FieldKey;
  label: string;
}

/** 0.4.0 (ADR-022 D2): which shared component a COMPONENT field is. */
export interface EmrFieldComponent {
  type: 'EMR_LIST' | 'PRESCRIPTION' | 'BODY_MAP' | 'FOLLOW_UP' | 'IPD_ORDERS';
  listKind: EmrListKind | null;
  billsVisit?: boolean;
}

export interface EmrFieldDefinition {
  key: FieldKey;
  label: string;
  type: EmrFieldType;
  valueKind: EmrValueKind;
  meta: string;
  required: boolean;
  optionsSource: EmrOptionsSource;
  master: EmrMaster | null;
  allowFreeText: boolean;
  options: EmrFieldOption[];
  columns: EmrTableColumn[];
  min: number | null;
  max: number | null;
  component?: EmrFieldComponent | null;
  /** 0.7.0 (SAH-75-6): CHIPS draws a choice as round chips (one, or many); DEFAULT when absent. */
  presentation?: 'DEFAULT' | 'CHIPS';
}

/** One section of a layout with its settings and definition. */
export interface EmrLayoutSection {
  key: SectionKey;
  /** FORM (0.4.0, SAH-59): a clinic form as a custom section; its key is the form key and `fields` is empty. */
  source: 'LIBRARY' | 'FORM';
  formKey?: string | null;
  formVersionNo?: number | null;
  form?: EmrFormDefinition | null;
  name: string;
  kind: EmrSectionKind;
  description: string;
  part: EmrPart;
  position: number;
  shown: boolean;
  required: boolean;
  print: boolean;
  copy: boolean;
  collapsed: boolean;
  whoCanFill: EmrFillerRole[];
  hideLocked: boolean;
  hideLockReason: string | null;
  fields: EmrFieldDefinition[];
  /** 0.5.0 (ADR-023 D1): only in a record snapshot, set when the record's profile has an editor for this section. */
  specialtyEditor?: { component: EmrSpecialtyComponent; profile: EmrSpecialtyProfile } | null;
}

/** The eight properties the server accepts per section on Save draft, Publish and Create. */
export interface EmrLayoutSettingsSection {
  key: SectionKey;
  part: EmrPart;
  shown: boolean;
  required: boolean;
  print: boolean;
  copy: boolean;
  collapsed: boolean;
  whoCanFill: EmrFillerRole[];
}

export interface EmrLayoutSettingsInput {
  revision: number;
  origin: 'EDIT' | 'RESET';
  sections: EmrLayoutSettingsSection[];
}

export interface EmrStarterSummary {
  starterKey: StarterKey;
  name: string;
  initials: string;
  colour: string;
  description: string | null;
  sectionCount: number;
  specialtySectionCount: number;
  offAtStart: string[];
}

export interface EmrStarterDetail extends EmrStarterSummary {
  layoutVersion: number;
  sections: EmrLayoutSection[];
}

export type EmrDepartmentStatus = 'TEMPLATE' | 'DRAFT' | 'LIVE';

export interface EmrDepartmentSummary {
  departmentKey: DepartmentKey;
  name: string;
  initials: string;
  colour: string;
  starterKey: StarterKey | null;
  sourceKind: 'STARTER' | 'BLANK' | 'COPY';
  copiedFromKey: DepartmentKey | null;
  status: EmrDepartmentStatus;
  publishedVersion: number;
  hasDraft: boolean;
  sectionsTotal: number;
  sectionsOn: number;
  requiredOn: number;
  doctorCount: number;
  rooms: string[];
}

export interface EmrDepartmentList {
  canEdit: boolean;
  departments: EmrDepartmentSummary[];
}

export interface EmrDepartmentView extends EmrDepartmentSummary {
  canEdit: boolean;
  revision: number;
  layoutSource: 'DRAFT' | 'PUBLISHED' | 'STARTER';
  sections: EmrLayoutSection[];
}

export interface EmrDraftSaveResult {
  outcome: 'SAVED' | 'NOTHING_NEW';
  department: EmrDepartmentView;
}

export interface EmrPublishResult {
  outcome: 'PUBLISHED' | 'NOTHING_TO_PUBLISH';
  publishedVersion: number;
  department: EmrDepartmentView;
}

export interface EmrResetTarget {
  departmentKey: DepartmentKey;
  basis: 'STARTER' | 'BLANK';
  sections: EmrLayoutSettingsSection[];
}

export interface EmrDepartmentCreateInput {
  name: string;
  initials?: string | null;
  colour?: string | null;
  source: {
    kind: 'STARTER' | 'BLANK' | 'COPY';
    starterKey?: StarterKey;
    copyFromDepartmentKey?: DepartmentKey;
  };
  copyPrintFlags: boolean;
  sections?: EmrLayoutSettingsSection[];
  doctorMembershipIds: string[];
  rooms: string[];
}

export interface EmrDoctorOption {
  membershipId: string;
  name: string;
  role: 'DOCTOR' | 'VISITING_DOCTOR';
  degree: string | null;
}

// ---- Doctor context (GET /emr/me) ----

export interface EmrDepartmentChoice {
  departmentKey: DepartmentKey;
  departmentName: string;
  starterKey: StarterKey | null;
  layoutSource: EmrLayoutSource;
  versionNo: number | null;
  choiceSource: EmrChoiceSource;
}

export interface EmrDoctorContext {
  membershipId: string;
  name: string;
  role: 'OWNER' | 'DOCTOR' | 'VISITING_DOCTOR';
  specialty: string | null;
  degree: string | null;
  registrationNo: string | null;
  missing: ('DEGREE' | 'REGISTRATION_NO')[];
  department: EmrDepartmentChoice;
  departments: { departmentKey: DepartmentKey; name: string }[];
}

// ---- EMR record (POST /emr/records/open, GET/PUT /emr/records/{id}) ----

export type EmrEncounterType = 'OPD_VISIT' | 'IPD_ROUND' | 'ER_ASSESSMENT';
export type EmrStageOutcome = 'MOVED' | 'ALREADY_WITH_DOCTOR' | 'ALREADY_UPDATED' | 'NOT_APPLICABLE';
export type EmrSectionStatus = 'DONE' | 'REQUIRED' | 'OPTIONAL';
export type EmrReadOnlyReason = 'SIGNED' | 'ENCOUNTER_CLOSED' | 'IPD_READ_ONLY' | 'HISTORY' | 'HELD_BY_OTHER';
/** EMERGENCY (0.6.0, SAH-67-4) needs a triage level. */
export type EmrVisitType = 'NEW' | 'FOLLOW_UP' | 'REVISIT' | 'EMERGENCY';

export interface EmrOpenInput {
  encounterType: EmrEncounterType;
  encounterId: string;
}

export interface EmrRecordHeader {
  patientId: string | null;
  patientName: string;
  initials: string;
  age: number | null;
  /** 0.5.3 (SAH-73-4). Null when there is no patient record. The Paediatrics header shows it; other departments show `age`. */
  ageDetail?: AgeDetail | null;
  sex: 'F' | 'M' | 'O';
  uhid: string | null;
  allergyText: string | null;
  /**
   * 0.7.1 (ADR-026 D12). NONE_RECORDED: the record says "no known allergy" (grey chip on Paediatrics and Oncology only);
   * allergyText is then null. Absent from an older server: treated as not recorded.
   */
  allergyStatus?: 'RECORDED' | 'NONE_RECORDED' | 'NOT_RECORDED';
  chronicText: string | null;
  /** 0.4.0 (SAH-57-3): true when a body map of this record has an MLC pin. */
  mlc?: boolean;
  lastVisitDaysAgo: number | null;
  visitOrdinal: number | null;
  visitType: EmrVisitType | null;
  /** 0.6.0 (SAH-67-4). OPD visit of type EMERGENCY only. */
  triageLevel?: number | null;
  triageLevelName?: string | null;
  doctor: { membershipId: string; name: string; room: string | null };
  admission: { admissionId: string; ipNumber: string; dayNumber: number; status: string } | null;
  emergency: { caseId: string; triageLevel: number; triageLevelName: string; status: string } | null;
}

export interface EmrSnapshot {
  departmentKey: DepartmentKey;
  departmentName: string;
  starterKey: StarterKey | null;
  layoutSource: EmrLayoutSource;
  versionNo: number | null;
  choiceSource: EmrChoiceSource;
  takenAt: string;
  sections: EmrLayoutSection[];
}

export interface EmrRecordSection {
  key: SectionKey;
  status: EmrSectionStatus;
  values: Record<string, unknown>;
  copiedFrom: { recordId: string; date: string } | null;
  copyPendingConfirmation: boolean;
  updatedAt: string | null;
  /** 0.5.0: generic values saved before the section had a specialty editor; read only. */
  legacyValues?: Record<string, unknown> | null;
  /** 0.6.0 (SAH-68-1). "Recorded by <name> · Nurse at <time>" while the values are the nurse's entry. */
  prefilledFrom?: {
    source: 'IPD_NURSING_CHART';
    sourceEntryId: string;
    recordedByName: string;
    recordedAt: string;
  } | null;
}

export interface EmrPartProgress {
  part: EmrPart;
  requiredDone: number;
  requiredTotal: number;
  complete: boolean;
  sectionsDone: number;
  sectionsTotal: number;
}

export type EmrIpdState = 'OFF' | 'TRIAL' | 'ON' | 'READ_ONLY';

export interface EmrRecordView {
  id: string;
  encounterType: EmrEncounterType;
  encounterId: string;
  roundDate: string | null;
  status: 'DRAFT' | 'SIGNED';
  readOnly: boolean;
  readOnlyReason: EmrReadOnlyReason | null;
  /** 0.6.0 (SAH-51-2, SAH-51-4, SAH-51-5, SAH-67-3). */
  signedAt?: string | null;
  signedByName?: string | null;
  editHold?: EmrEditHold | null;
  addenda?: EmrAddendum[];
  admission?: EmrAdmissionState;
  canAddAddendum?: boolean;
  /** 0.4.0 (SAH-48-2): drug-class clashes of active prescription rows with the picked allergy classes. */
  allergyAlerts?: EmrAllergyAlert[];
  /** 0.4.0: ALLERGY_ALERT replaces "Still needed" in the footer and blocks Complete. */
  completeBlockers?: 'ALLERGY_ALERT'[];
  /** 0.4.0 (SAH-48-1): null disables "Repeat last Rx" with "No earlier prescription". */
  lastSignedPrescription?: { recordId: string; date: string } | null;
  printPreferences?: EmrPrintPreferences;
  /** 0.4.0: false. 0.6.0 (ADR-025 D8): true on IPD_ROUND records, whose Prescription field is the IPD_ORDERS component. */
  prescriptionIsOrder?: boolean;
  revision: number;
  header: EmrRecordHeader;
  layout: EmrSnapshot;
  sections: EmrRecordSection[];
  parts: EmrPartProgress[];
  stillNeeded: SectionKey[];
  readyToComplete: boolean;
  /** 0.5.0 (ADR-023 D3): the footer list in order, with the profile's labels and items. */
  stillNeededItems?: EmrStillNeededItem[];
  /** 0.5.0: computed specialty results; null for the generic profile, IPD rounds and Emergency. */
  specialty?: EmrSpecialtyView | null;
  uiState: { part: EmrPart; sectionKey: SectionKey | null };
  admissionAdvised: boolean;
  referralLine: string | null;
  departmentOptions: { departmentKey: DepartmentKey; name: string }[];
  ipd: { state: EmrIpdState; canSwitchOn: boolean };
}

export interface EmrOpenResult {
  created: boolean;
  stageOutcome: EmrStageOutcome;
  record: EmrRecordView;
}

export interface EmrRecordSectionInput {
  key: SectionKey;
  values: Record<string, unknown>;
  confirmCopy?: boolean;
  /** 0.6.0 (SAH-68-1): the nurse entry the values were pre-filled from. */
  prefill?: { source: 'IPD_NURSING_CHART'; sourceEntryId: string } | null;
}

export interface EmrRecordDraftInput {
  revision: number;
  uiState: { part: EmrPart; sectionKey: SectionKey | null };
  sections: EmrRecordSectionInput[];
  /** 0.5.0: screen facts (visit mode, oncology decision); omitted leaves them unchanged. */
  screen?: EmrScreenInput | null;
  /** 0.6.0 (SAH-51-3). AUTO: the 30-second tick or the part switch; no audit entry. MANUAL is the default ("Save draft"). */
  saveKind?: 'MANUAL' | 'AUTO';
}

export interface EmrSwitchDepartmentInput {
  departmentKey: DepartmentKey;
  revision: number;
}

export interface EmrAdmissionAdviceInput {
  revision: number;
  referralLine?: string | null;
}

// ---- Value shapes stored per field (EmrValueKind) ----

export interface EmrNumberUnitValue {
  value: number;
  unit: string;
}

export interface EmrItemListEntry {
  itemKey: string | null;
  text: string;
  newToList: boolean;
}

export interface EmrStructuredValue {
  text: string;
}

// ---- Searches used by the Search list field ----

export interface EmrMasterItem {
  id: string;
  name: string;
  specialtyHint: StarterKey | null;
  nameMr?: string | null;
  nameHi?: string | null;
}

export interface EmrMasterPage {
  items: EmrMasterItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface EmrQuickOption {
  itemKey: string;
  name: string;
  icd10Code: string | null;
  form: string | null;
  favourite: boolean | null;
  useCount: number;
  lastUsedAt: string | null;
}

export interface EmrQuickOptionList {
  master: EmrMaster;
  items: EmrQuickOption[];
}

// ---- Words of the mockup ----

export const PART_LABEL: Record<EmrPart, string> = {
  ASSESSMENT: 'Assessment',
  TREATMENT: 'Treatment',
};

export const PART_NUMBER: Record<EmrPart, string> = {
  ASSESSMENT: '1',
  TREATMENT: '2',
};

export const FILLER_LABEL: Record<EmrFillerRole, string> = {
  DOCTOR: 'Doctor',
  NURSE: 'Nurse / assistant',
  RECEPTION: 'Reception',
};

export const FILLER_ORDER: readonly EmrFillerRole[] = ['DOCTOR', 'NURSE', 'RECEPTION'];

export const STATUS_LABEL: Record<EmrDepartmentStatus, string> = {
  TEMPLATE: 'Template',
  DRAFT: 'Draft',
  LIVE: 'Live',
};

/** Mockup `FIELD type` words for the library's field types. */
export const FIELD_TYPE_LABEL: Record<EmrFieldType, string> = {
  TEXT: 'Text',
  RICH_TEXT: 'Rich text',
  NUMBER: 'Number',
  NUMBER_UNIT: 'Number + unit',
  NUMBER_TREND: 'Number + trend',
  CALCULATED: 'Calculated',
  FORMULA: 'Formula',
  RULE: 'Rule',
  REFERENCE: 'Reference',
  DATE: 'Date',
  CALENDAR: 'Calendar',
  TOGGLE: 'Toggle',
  DROPDOWN: 'Dropdown',
  DROPDOWNS: 'Dropdowns',
  SINGLE_CHOICE: 'Single choice',
  MULTI_SELECT: 'Multi-select',
  CHECKLIST: 'Checklist',
  CHIPS: 'Chips',
  SEARCH_LIST: 'Search list',
  TABLE: 'Table',
  GRID: 'Grid',
  PATTERN: 'Pattern',
  TEMPLATE: 'Template',
  DOCTOR_HOSPITAL: 'Doctor / hospital',
  CHART: 'Chart',
  BODY_MAP: 'Body map',
  TOOTH_PICKER: 'Tooth picker',
  LIBRARY: 'Library',
  FILE: 'File',
  SIGNATURE: 'Signature',
};

export function fieldTypeLabel(type: string): string {
  return FIELD_TYPE_LABEL[type as EmrFieldType] ?? 'Not available yet';
}
