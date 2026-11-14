/**
 * Types of the EMR phase 5 endpoints (finish, sign, edit hold, addenda, admission request, vitals, IPD
 * order options, free slots, triage levels, reprint), written by hand from `docs/api/opd.yaml` 0.6.1
 * (SAH-51, SAH-67, SAH-68; ADR-025 D2, D7, D15, D19). No field is invented. Everything here is for the
 * screen only: nothing is logged or stored in the browser.
 */

import type { VisitStage } from '../queue/queue.model';
import type { EmrCasePaperContext, EmrCasePaperHeading } from './emr-phase3.model';
import type { EmrEncounterType, EmrRecordView, EmrSnapshot } from './emr.model';
import type { EmrSpecialtyReference, EmrSpecialtyView, OncDecisionKey } from './specialty/specialty.model';

// ---- Edit hold (SAH-51-4) ----

export interface EmrEditHold {
  holderMembershipId: string;
  holderName: string;
  expiresAt: string;
  mine: boolean;
  canTakeOver: boolean;
}

export interface EmrEditHoldInput {
  takeOver?: boolean;
}

// ---- Addenda (SAH-51-5) ----

export interface EmrAddendum {
  id: string;
  reason: string;
  text: string;
  createdAt: string;
  createdByName: string;
}

export interface EmrAddendumInput {
  reason: string;
  text: string;
}

export const ADDENDUM_REASON_MAX = 200;
export const ADDENDUM_TEXT_MAX = 4000;

// ---- Admission (SAH-67-3) ----

export type EmrAdmissionStateKey = 'NONE' | 'ADVISED' | 'REQUESTED' | 'ADMITTED' | 'ALREADY_ADMITTED';

export interface EmrAdmissionState {
  state: EmrAdmissionStateKey;
  requestId: string | null;
  admissionId: string | null;
  ipNumber: string | null;
}

export type EmrAdmissionRequestType = 'planned' | 'emergency' | 'daycare';

export interface EmrAdmissionRequestInput {
  admissionType: EmrAdmissionRequestType;
  plannedStayDays?: number | null;
  note?: string | null;
}

export interface EmrAdmissionRequestView {
  id: string;
  status: 'OPEN' | 'ADMITTED';
  admissionType: EmrAdmissionRequestType;
  plannedStayDays: number | null;
  createdAt: string;
  admissionId: string | null;
  ipNumber: string | null;
}

export interface EmrAdmissionPrefill {
  patient: {
    patientId: string | null;
    name: string;
    age: number | null;
    sex: 'F' | 'M' | 'O';
    mobile: string | null;
  };
  suggestedType: EmrAdmissionRequestType;
  diagnoses: string[];
  procedure: string | null;
  plannedStayDays: number | null;
  emergencyCaseId: string | null;
  openRequest: EmrAdmissionRequestView | null;
  openAdmission: { admissionId: string; ipNumber: string } | null;
  canAdmitDirectly: boolean;
}

// ---- Follow-up and IPD orders components (ADR-025 D6, D8) ----

export type EmrFollowUpInterval = 'D3' | 'D7' | 'D15' | 'M1' | 'M3';

export interface EmrFollowUpValue {
  component: 'FOLLOW_UP';
  schemaVersion: 1;
  interval: EmrFollowUpInterval | null;
  /** Computed by the server; ignored when sent. */
  dueDate?: string | null;
}

export interface EmrIpdOrderRow {
  id: string;
  drugKey: string;
  frequencyKey: string;
  /** Set at sign by ipd; ignored when sent. */
  orderId?: string | null;
  orderedAt?: string | null;
}

export interface EmrIpdOrdersValue {
  component: 'IPD_ORDERS';
  schemaVersion: 1;
  rows: EmrIpdOrderRow[];
}

export interface EmrKeyLabel {
  key: string;
  label: string;
}

export interface EmrIpdOrderOptions {
  drugs: { key: string; name: string; route: string; highAlert: string | null }[];
  frequencies: EmrKeyLabel[];
  overrideReasons: EmrKeyLabel[];
}

// ---- Free slots, triage levels ----

export interface EmrFreeSlot {
  date: string;
  time: string;
}

export interface EmrTriageLevel {
  level: number;
  name: string;
  description: string;
  tone: 'crit' | 'warn' | 'good';
}

export interface EmrTriageScale {
  scale: 3 | 5;
  clinicDefault: boolean;
  levels: EmrTriageLevel[];
}

// ---- Vitals read (SAH-68-1) ----

export interface EmrVitalsReading {
  sourceEntryId: string;
  source: 'IPD_NURSING_CHART';
  recordedByName: string;
  recordedAt: string;
  bpSystolic?: number | null;
  bpDiastolic?: number | null;
  pulse?: number | null;
  spo2?: number | null;
  temperature?: { value: number; unit: '°C' | '°F' } | null;
  respiratoryRate?: number | null;
}

export interface EmrEncounterVitals {
  current: EmrVitalsReading | null;
  earlier: { recordedByName: string; recordedAt: string } | null;
  sources: { opd: 'NOT_CAPTURED'; ipd: 'AVAILABLE' | 'NOT_APPLICABLE'; er: 'NOT_CAPTURED' };
}

// ---- Finish choices, check and sign (SAH-51-1, SAH-51-2) ----

export type EmrErOutcomeKind = 'DISCHARGE' | 'ADMIT' | 'REFER_OUT' | 'SEND_TO_OPD';

export interface EmrErOutcome {
  kind: EmrErOutcomeKind;
  referTo?: string | null;
  referNote?: string | null;
  opdPatientId?: string | null;
  admission?: EmrAdmissionRequestInput | null;
}

export type EmrDentalNextInterval = 'D3' | 'D7' | 'D14' | 'M1';

export interface EmrFinishChoices {
  erOutcome?: EmrErOutcome | null;
  ipdOrderReasons?: { rowId: string; overrideReasonKey: string; checkToken: string }[];
  dental?: { nextInterval: EmrDentalNextInterval | null } | null;
  physio?: { nextSlot: { date: string; time?: string | null } | null } | null;
  confirmAllergyRemoval?: boolean;
  share?: { whatsappPrescription?: boolean };
}

export type EmrFinishBlockerKey =
  | 'STILL_NEEDED'
  | 'ALLERGY_ALERT'
  | 'IPD_ORDER_BLOCKED'
  | 'IPD_ORDER_NEEDS_REASON'
  | 'ENCOUNTER_CLOSED'
  | 'CASE_CLOSED'
  | 'HELD_BY_OTHER'
  | 'ER_OUTCOME_REQUIRED'
  | 'PATIENT_REQUIRED'
  | 'PATIENT_FACTS_TOO_LONG'
  | 'PROFILE';

export interface EmrFinishBlocker {
  key: EmrFinishBlockerKey;
  message: string;
  sectionKeys?: string[];
  rowIds?: string[];
}

export interface EmrFinishBillLine {
  name: string;
  qty: number;
  amountPaise: number;
  state: 'NEW' | 'ON_BILL' | 'ALREADY_BILLED';
  source?: 'SERVICE' | 'DENTAL_PLAN';
}

export interface EmrFinishOrderCheck {
  rowId: string;
  drugName: string;
  frequencyLabel: string;
  outcome: 'PASS' | 'NEEDS_REASON' | 'BLOCKED' | 'ORDERED';
  texts: string[];
  checkToken: string | null;
}

export interface EmrFinishEffects {
  stage?: { outcome: 'WILL_MOVE' | 'ALREADY_MOVED'; to: VisitStage | null } | null;
  followUp?: {
    interval: EmrFollowUpInterval | null;
    dueDate: string;
    firstFreeSlot: EmrFreeSlot | null;
    ignoredInterval?: boolean;
  } | null;
  billLines?: EmrFinishBillLine[];
  billTotalPaise?: number;
  patientRecord?: { fields: ('ALLERGIES' | 'LONG_TERM_CONDITIONS')[]; removals: string[]; conflict: boolean } | null;
  ipdOrders?: EmrFinishOrderCheck[];
  investigationsAdvised?: string | null;
  emergency?: { triageLevel: number; triageLevelName: string; outcome: EmrErOutcomeKind | null } | null;
  /** 0.6.1 (ADR-025 D7): the block of the record's profile; null without a profile. Display only. */
  profile?: EmrFinishProfileEffects | null;
  /** 0.6.1 (ADR-025 D19): the documents the client prints after the sign, in this order. */
  prints?: EmrFinishPrint[];
}

/** 0.6.1 (ADR-025 D19): a document the client prints by itself after a 200 of the sign, one browser print each. */
export type EmrFinishPrint = 'PRESCRIPTION' | 'CYCLE_RECORD' | 'CHEMO_CALENDAR' | 'ADMISSION_LETTER' | 'PREOP_SHEET';

export interface EmrFinishGmEffects {
  profile: 'gm';
}

export interface EmrFinishDentalEffects {
  profile: 'dental';
  doneRows: {
    rowId: string;
    /** "<tooth> · <procedureText>". */
    name: string;
    sitting: { no: number; of: number };
    /** Null: no tariff match and no typed amount (no bill line: "Not in tariff"). */
    amountPaise: number | null;
  }[];
  estimateTotalPaise: number;
  allRowsDone: boolean;
  carriedRowCount: number;
  nextSittingInterval: EmrDentalNextInterval | null;
  nextSittingDate: string | null;
  nextSittingSlot: EmrFreeSlot | null;
}

export interface EmrFinishOgEffects {
  profile: 'og';
  mode: 'ANC' | 'GYNAE';
  ancVisitNo: number | null;
  gestationalAge: { weeks: number; days: number } | null;
  bpSystolic: number | null;
  bpDiastolic: number | null;
  hbGdl: number | null;
  orderedLabels: string[];
  nextAncWeeks: number | null;
}

export interface EmrFinishPaedsEffects {
  profile: 'paeds';
  weightKg: number | null;
  weightNote: string | null;
  vaccinesGivenToday: { vaccineKey: string; label: string }[];
  nextDue: { vaccineKey: string; label: string; groupLabel: string } | null;
}

export interface EmrFinishPhysioEffects {
  profile: 'physio';
  courseId: string | null;
  sessionNo: number | null;
  packageSize: number | null;
  nextSessionNo: number | null;
  packageComplete: boolean;
  painScore: number | null;
  previousPainScore: number | null;
}

export interface EmrFinishOncoEffects {
  profile: 'onco';
  cycleNo: number | null;
  decision: OncDecisionKey | null;
  orderStatus: 'NONE' | 'DRAFT' | 'SENT' | 'HELD';
  nextCycleNo: number | null;
  nextCycleLabel: 'NEXT_CHEMO' | 'REBOOKED_CHEMO' | null;
  nextCycleDate: string | null;
  nextCycleSittingId: string | null;
  cycleChargePaise: number | null;
  followUpFromNextCycle: boolean;
}

export interface EmrFinishSurgeryEffects {
  profile: 'surgery';
  bookState: 'NOT_BOOKED' | 'READY' | 'BOOKED' | 'CHANGED';
  procedureText: string | null;
  otDate: string | null;
  slotText: string | null;
  admission: 'DAY_CARE' | 'IPD_1_DAY' | 'IPD_1_2_DAYS' | 'IPD_2_DAYS' | 'IPD_4_5_DAYS' | null;
  admissionRequested: boolean;
  fitnessText: string | null;
}

/** 0.6.1 (ADR-025 D7): one block per profile, chosen by its `profile` property. */
export type EmrFinishProfileEffects =
  | EmrFinishGmEffects
  | EmrFinishDentalEffects
  | EmrFinishOgEffects
  | EmrFinishPaedsEffects
  | EmrFinishPhysioEffects
  | EmrFinishOncoEffects
  | EmrFinishSurgeryEffects;

export interface EmrFinishCheck {
  ready: boolean;
  blockers: EmrFinishBlocker[];
  encounterType: EmrEncounterType;
  effects: EmrFinishEffects;
  info: string[];
  share: {
    whatsapp: { available: false; reason: string };
    abha: { available: false; reason: string };
  };
}

export interface EmrSignInput {
  revision: number;
  choices: EmrFinishChoices;
}

export interface EmrSignOutcome {
  stage: { outcome: 'MOVED' | 'ALREADY_MOVED'; to: VisitStage | null } | null;
  followUp: {
    id: string;
    dueDate: string;
    appointmentDate: string | null;
    appointmentTime: string | null;
    noFreeSlot?: boolean;
  } | null;
  billLines: number;
  billTotalPaise: number;
  ipdOrders: { rowId: string; orderId: string }[];
  round: { admissionId: string; roundDate: string } | null;
  emergency: {
    outcome: EmrErOutcomeKind;
    admissionRequestId: string | null;
    opdVisitId: string | null;
    tokenNo: number | null;
  } | null;
  patientRecord: 'UPDATED' | 'NOT_CHANGED' | 'CONFLICT';
  course: {
    sittingDoneId?: string | null;
    nextSittingId?: string | null;
    nextAppointmentDate?: string | null;
    nextAppointmentTime?: string | null;
    packageComplete?: boolean;
  } | null;
  /** 0.6.1 (ADR-025 D19): the documents to print now, in order. Always present from 0.6.1, also on a replay. */
  prints: EmrFinishPrint[];
}

export interface EmrSignResult {
  /** True when this answers a repeat of the same Idempotency-Key; nothing was done again. */
  replayed: boolean;
  outcome: EmrSignOutcome;
  record: EmrRecordView;
}

// ---- Reprint (SAH-51-6) ----

export interface EmrReprint {
  visitId: string;
  recordId: string;
  reprint: true;
  signedAt: string;
  context: EmrCasePaperContext;
  sections: { key: string; name: string; heading: EmrCasePaperHeading; values: Record<string, unknown> }[];
  addenda: EmrAddendum[];
  followUpDate?: string | null;
  /** 0.6.1 (ADR-025 D15): the frozen snapshot reduced to the printed sections (same order as `sections`), with field definitions and option labels. */
  layout: EmrSnapshot;
  /** 0.6.1: the profile's computed block as of the signing day; null without a profile. */
  specialty: EmrSpecialtyView | null;
  /** 0.6.1: the profile's reference (list labels); null without a profile. */
  specialtyReference: EmrSpecialtyReference | null;
}

// ---- Finish problems (409 bodies carry extensions) ----

export interface EmrBlockersExtension {
  blockers?: EmrFinishBlocker[];
  holderName?: string;
  rows?: { rowId: string; outcome: string; texts?: string[] }[];
}
