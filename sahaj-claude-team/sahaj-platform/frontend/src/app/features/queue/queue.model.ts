/** Types for the SAH-9 `/opd/visits*` endpoints in `docs/api/opd.yaml`. No field is invented. */

export type VisitStage = 'WAITING' | 'VITALS' | 'WITH_DOCTOR' | 'COUNSEL' | 'BILLING' | 'DONE';

export type VisitType = 'NEW' | 'FOLLOW_UP';

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
  return type === 'FOLLOW_UP' ? 'Follow-up' : 'New';
}
