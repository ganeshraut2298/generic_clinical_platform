/**
 * Types for the treatment course: the plan is `docs/api/opd.yaml` 0.7.0 (`/emr/courses/**`), the
 * money is `docs/api/billing.yaml` 0.4.0 (`/billing/courses/**`). Written by hand; no field is
 * invented. Amounts are integer paise. Patient and clinical text are for the screen only.
 */

export type TreatmentCourseKind = 'DENTAL' | 'ANC' | 'CHEMO' | 'SURGERY' | 'REHAB';
export type CourseUnit = 'SITTING' | 'VISIT' | 'CYCLE' | 'STEP' | 'SESSION';
export type CourseEncounterKind = 'OPD' | 'DAY_CARE' | 'IPD';
export type CourseSittingStatus = 'PLANNED' | 'TODAY' | 'DONE' | 'MISSED';
/** 0.7.0 (SAH-75-10): what the caller may do on a sitting now; the buttons are drawn from it, the server checks again. */
export type CourseSittingAction = 'MARK_DONE' | 'MISSED' | 'REBOOK' | 'RESCHEDULE' | 'REMOVE' | 'REQUEST_ADMISSION' | 'ADMIT';

export interface CourseAddOption {
  key: string;
  label: string;
  encounterKind: CourseEncounterKind;
}

export interface CourseSitting {
  id: string;
  sittingNo: number;
  encounterKind: CourseEncounterKind;
  encounterLabel: string;
  encounterRef: string | null;
  plannedDate: string | null;
  status: CourseSittingStatus;
  items: string[];
  chargePaise: number;
  note: string | null;
  admissionRequested: boolean;
  settledInIpd: boolean;
  ipdBillTotalPaise: number | null;
  opdVisitId: string | null;
  admissionId: string | null;
  /** 0.7.0: the number shown (S<n>, "Sitting n"): rank among the non-removed sittings; sittingNo stays the stable key. */
  displayNo: number;
  doneSource: 'MARK_DONE' | 'SIGN' | null;
  regimenKey: string | null;
  allowedActions: CourseSittingAction[];
}

export interface TreatmentCourseSummary {
  id: string;
  courseNo: string;
  kind: TreatmentCourseKind;
  unit: CourseUnit;
  name: string;
  startedOn: string;
  status: 'ACTIVE';
  sittingCount: number;
  doneCount: number;
}

export interface TreatmentCourseView extends TreatmentCourseSummary {
  patientId: string;
  doctorMembershipId: string;
  doctorName: string;
  note: string | null;
  revision: number;
  sittings: CourseSitting[];
  addOptions: CourseAddOption[];
  canPlan: boolean;
  canAdmit: boolean;
  accountNote: string;
  /** 0.7.0: the sub line adds "estimate signed by patient" only when true. */
  estimateSigned: boolean;
}

export interface CourseSittingPlanInput {
  addOptionKey: string;
  regimenKey?: string | null;
  plannedDate?: string | null;
  items?: string[];
  chargePaise?: number;
  note?: string | null;
}

export interface TreatmentCourseCreateInput {
  fromRecordId: string;
  kind: TreatmentCourseKind;
  name?: string | null;
  note?: string | null;
  sittings?: CourseSittingPlanInput[];
  /** 0.5.0 (SAH-69-1): DENTAL only; builds the sittings from the record's treatment plan (sittings must then be empty). */
  fromPlan?: boolean;
}

export interface CourseSittingAddInput {
  revision: number;
  addOptionKey: string;
  plannedDate?: string | null;
}

export interface CourseSittingChangeInput {
  revision: number;
  encounterKind: CourseEncounterKind;
  plannedDate: string | null;
  items: string[];
  chargePaise: number;
  note: string | null;
}

export interface CourseSittingLinkVisitInput {
  revision: number;
  visitId: string;
}

/** 0.7.0 (SAH-75-10): the bodies of the six sitting actions. */
export interface CourseSittingRevisionInput {
  revision: number;
}
export interface CourseSittingMarkDoneInput {
  revision: number;
  visitId?: string | null;
}
export interface CourseSittingDateInput {
  revision: number;
  /** Today or later, Asia/Kolkata (400 validation otherwise). */
  plannedDate: string;
}
export interface CourseSittingAdmissionRequestInput {
  revision: number;
  encounterKind?: 'DAY_CARE' | 'IPD' | null;
}

// ---- billing: the running account ----

export type PaymentMode = 'CASH' | 'UPI' | 'CARD';
export type CoursePaymentType = 'VISIT_PAYMENT' | 'FULL_BALANCE' | 'PART_PAYMENT' | 'ADVANCE' | 'IPD_DEPOSIT';

export interface CourseLedgerLine {
  kind: 'PAYMENT' | 'CREDIT_USED' | 'CARRIED_TO_IPD';
  date: string;
  type: CoursePaymentType | null;
  mode: PaymentMode | null;
  against: string;
  amountPaise: number;
  receiptNo: string | null;
}

export interface CourseAccount {
  courseId: string;
  courseNo: string;
  unit: CourseUnit;
  estimatePaise: number;
  paidPaise: number;
  creditPaise: number;
  dueFromEarlierPaise: number;
  todayChargePaise: number;
  dueTodayPaise: number;
  balancePaise: number;
  sittingCount: number;
  doneCount: number;
  receiptCount: number;
  nextPlannedAdmission: { sittingId: string; sittingNo: number; displayNo?: number; chargePaise: number; note: string | null } | null;
  suggestions: { partPaymentPaise: number | null; ipdDepositPaise: number | null };
  canCollect: boolean;
  ledger: CourseLedgerLine[];
}

export interface CoursePaymentCreate {
  type: CoursePaymentType;
  amountPaise: number;
  mode: PaymentMode;
  useCredit: boolean;
  againstSittingId?: string | null;
}

export interface CoursePaymentResult {
  receiptNo: string;
  account: CourseAccount;
}

export interface CourseReceipt {
  receiptNo: string;
  clinicName: string;
  clinicAddress: string | null;
  clinicPhone: string | null;
  date: string;
  patientName: string;
  uhid: string | null;
  courseNo: string;
  courseName: string;
  type: CoursePaymentType;
  mode: PaymentMode;
  against: string;
  amountPaise: number;
  collectedByName: string;
  balanceAfterPaise: number;
}
