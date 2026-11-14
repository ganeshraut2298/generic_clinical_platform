/** Types for the SAH-9 `/opd/visits*` endpoints in `docs/api/opd.yaml`. No field is invented. */

export type VisitStage = 'WAITING' | 'VITALS' | 'WITH_DOCTOR' | 'COUNSEL' | 'BILLING' | 'DONE';

/** REVISIT (opd.yaml 0.3.0, SAH-52-2) and EMERGENCY (0.6.0, SAH-67-4) are set only by the doctor from the EMR. */
export type VisitType = 'NEW' | 'FOLLOW_UP' | 'REVISIT' | 'EMERGENCY';

export type TriageTone = 'crit' | 'warn' | 'good';

/** 0.6.0 (SAH-68-2). Present only in the answer of `PATCH /visits/{id}/visit-type`. */
export interface VisitFeeChange {
  outcome: 'UPDATED' | 'UNCHANGED' | 'ALREADY_BILLED';
  beforePaise: number;
  afterPaise: number;
}

export type VisitEmrStatus = 'NONE' | 'DRAFT' | 'SIGNED';

export type VisitSex = 'F' | 'M' | 'O';

export interface VisitCreate {
  patientId: string;
  doctorMembershipId: string;
}

export interface Visit {
  id: string;
  tokenNo: number;
  patientId: string;
  patientName: string;
  age: number;
  sex: VisitSex;
  doctorMembershipId: string;
  doctorName: string;
  visitType: VisitType;
  stage: VisitStage;
  feePaise: number;
  createdAt: string;
  /** The published workflow version the visit was issued under; its steps drive the card's moves. */
  workflowVersionNo: number;
  /** The EMR record of this visit (SAH-46, opd.yaml 0.3.0); null before the first "Open EMR". */
  emrRecordId?: string | null;
  emrStatus?: VisitEmrStatus;
  /** 0.6.0 (SAH-67-4). Set only when `visitType` is EMERGENCY: 1 is the most urgent. */
  triageLevel?: number | null;
  triageLevelName?: string | null;
  triageTone?: TriageTone | null;
  feeChange?: VisitFeeChange | null;
}

/** Mockup `STAGE_OF` labels, contract stage keys. */
export const STAGE_LABELS: Record<VisitStage, string> = {
  WAITING: 'Waiting',
  VITALS: 'Vitals',
  WITH_DOCTOR: 'With doctor',
  COUNSEL: 'Counselling',
  BILLING: 'Billing',
  DONE: 'Done',
};

/** Fixed forward order of the state machine (story §3). */
export const STAGE_ORDER: readonly VisitStage[] = [
  'WAITING',
  'VITALS',
  'WITH_DOCTOR',
  'COUNSEL',
  'BILLING',
  'DONE',
];

/** Stage keys whose detail screen (consultation, billing collection) is not built yet
 * (SAH-10/SAH-11); the card shows a placeholder note instead of a broken link. */
export const PLACEHOLDER_STAGES: readonly VisitStage[] = ['COUNSEL', 'BILLING'];

export function visitTypeLabel(type: VisitType): string {
  switch (type) {
    case 'FOLLOW_UP':
      return 'Follow-up';
    case 'REVISIT':
      return 'Revisit';
    case 'EMERGENCY':
      return 'Emergency';
    default:
      return 'New';
  }
}
