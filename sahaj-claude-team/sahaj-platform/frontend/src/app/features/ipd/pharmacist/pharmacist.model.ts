import { eventTime } from '../safety/safety.model';
import type { OrderChangeLine, OrderDetails, OrderQuery } from '../orders/order.model';
import { changeLineText, clock } from '../orders/order.model';
import {
  EmrPatient,
  FrequencyKey,
  KeyLabel,
  MedicineOrderStatus,
  OrderCheck,
  OrderFinding,
  RejectReasonKey,
  severityWord,
} from '../emr/emr.model';

/**
 * SAH-31 pharmacist check (mockup `ipSafety` panel, `pendRow`, `sfver`, `sfverok`, `sfrej`,
 * `sfrejok`). Types follow `PharmacistChecks`, `PharmacistCheckRow`, `PharmacistChecksSummary`,
 * `VerifyOrderInput`, `RejectOrderInput` and `PharmacistActionResult` in docs/api/ipd.yaml. The
 * findings are the server's, run again on every read. Names, allergy text and findings are on screen
 * only: never logged or stored. The PIN is never part of a model that is kept.
 */
/** 0.4.3 (ADR-027 A9): SELF_CHANGED is the caller who wrote the current version and did not place the order. */
export type VerifyLockReason = 'HARD_STOP' | 'SELF' | 'SELF_CHANGED' | 'IPD_READONLY';

export interface PharmacistCheckRow {
  orderId: string;
  admissionId: string;
  drugKey: string;
  drugName: string;
  highAlert: string | null;
  frequencyKey: FrequencyKey;
  frequencyLabel: string;
  patient: EmrPatient;
  orderedByName: string;
  orderedAt: string;
  overrideReason: KeyLabel | null;
  check: OrderCheck;
  canVerify: boolean;
  verifyLockReason: VerifyLockReason | null;
  canReject: boolean;
  /** SAH-76: PENDING, or QUERIED (pill "Queried", no Verify and no Query doctor). */
  status: Extract<MedicineOrderStatus, 'PENDING' | 'QUERIED'>;
  /** Sent back on Verify and Query doctor. */
  orderVersion: number;
  details: OrderDetails;
  changes: OrderChangeLine[];
  /** Clinical text: PHARMACIST and OWNER only, on screen only. */
  changeReason: string | null;
  query: OrderQuery | null;
  canQuery: boolean;
}

export interface PharmacistChecks {
  state: 'trial' | 'on' | 'readonly';
  asOf: string;
  /** All PENDING orders of ADMITTED admissions of the clinic, not only this page. */
  waiting: number;
  /** The signed-in user's display name, for "Verified by". */
  verifierName: string;
  rejectReasons: KeyLabel[];
  items: PharmacistCheckRow[];
  nextCursor: string | null;
  /** SAH-76: QUERIED orders of ADMITTED admissions (listed with the pill "Queried"; not in waiting). */
  queried: number;
  /** SAH-76: open Review after dose items. */
  reviewsOpen: number;
}

/** Counts only (ADR-005 D3): no names, drugs or text. */
export interface PharmacistChecksSummary {
  waiting: number;
  /** 22 characters, opaque: compared for equality only. */
  changeToken: string;
  asOf: string;
  queried: number;
  reviewsOpen: number;
}

export interface VerifyOrderInput {
  pin: string;
  checkToken: string;
  /** SAH-76: the version shown in the sheet. */
  orderVersion: number;
}

export interface QueryOrderInput {
  clientKey: string;
  orderVersion: number;
  question: string;
}

/** SAH-76-2: one STAT dose given before the pharmacist check (PharmacistReviewRow). */
export interface PharmacistReviewRow {
  id: string;
  orderId: string;
  admissionId: string;
  orderVersion: number;
  drugKey: string;
  drugName: string;
  details: OrderDetails;
  patient: EmrPatient;
  doseId: string;
  givenAt: string;
  givenByName: string;
  orderStatus: MedicineOrderStatus;
  check: OrderCheck;
  canMarkReviewed: boolean;
  canQuery: boolean;
}

export interface PharmacistReviewPage {
  asOf: string;
  open: number;
  items: PharmacistReviewRow[];
  nextCursor: string | null;
}

export interface RejectOrderInput {
  reasonKey: RejectReasonKey | string;
}

export interface PharmacistActionResult {
  result: {
    orderId: string;
    drugName: string;
    outcome: 'VERIFIED' | 'REJECTED' | 'QUERIED';
    replayed: boolean;
    actorName: string;
    rejectReason: KeyLabel | null;
  };
  checks: PharmacistChecks;
}

// ---- Texts (exact, from the mockup and docs/api/ipd.yaml) ----

export const PANEL_TITLE = 'Pharmacist check';
export const PANEL_SUBTITLE =
  'Every new order is checked before the first dose. The person who ordered cannot verify it.';
export const NO_WAITING = 'No orders waiting.';
export const SHOW_MORE = 'Show more orders';
export const TILE_TITLE = 'Pharmacist checks';
export const TILE_TEXT = 'Orders waiting before the first dose';
export const CHECKS_LOAD_ERROR = 'We could not load the pharmacist checks. Try again.';
export const MORE_ERROR = 'We could not load more orders. Try again.';
export const VERIFY_ERROR = 'We could not verify that order. Try again.';
export const REJECT_ERROR = 'We could not reject that order. Try again.';
export const KEY_RELOAD = 'Reload the page and try again.';
export const PIN_FORMAT = 'Enter the 4-digit PIN.';
export const NO_FINDINGS = 'No allergy, duplicate or interaction found.';
export const REASON_MISSING = 'Choose a reason';

export const REVIEW_TITLE = 'Review after dose';
export const REVIEW_EMPTY = 'No doses to review.';
export const REVIEW_LOAD_ERROR = 'We could not load the doses to review. Try again.';
export const REVIEW_ERROR = 'We could not mark that dose reviewed. Try again.';
export const QUERY_ERROR = 'We could not send that question. Try again.';
export const QUESTION_MISSING = 'Write your question for the doctor.';
export const QUESTION_LONG = 'Keep the question to 300 characters.';
export const QUESTION_MAX = 300;
export const STAT_HARD_STOP_HINT = 'Hard stop: the doctor must act. Query the doctor.';

/** "Review after dose (2)". */
export const reviewHeading = (open: number): string => `${REVIEW_TITLE} (${open})`;
/** "Given before pharmacist check at 11:05 AM by Sr. Anjali" (clock is IST). */
export const givenBeforeLine = (row: Pick<PharmacistReviewRow, 'givenAt' | 'givenByName'>): string =>
  `Given before pharmacist check at ${clock(row.givenAt)} by ${row.givenByName}`;
export const queriedToast = (drugName: string): string =>
  `${drugName} queried. The doctor sees your question on the EMR.`;
export const reviewedToast = (drugName: string): string => `${drugName} marked reviewed.`;
export const changeLines = (row: Pick<PharmacistCheckRow, 'changes'>): string[] => row.changes.map(changeLineText);

/** Why Verify is off, as the hint beside the button (mockup title; story; contract). */
export const LOCK_HINT: Record<VerifyLockReason, string> = {
  HARD_STOP: 'Hard stop: reject this order',
  SELF: 'The doctor who ordered this cannot verify it. Ask the pharmacist.',
  SELF_CHANGED: 'The doctor who changed this order cannot verify it. Ask the pharmacist.',
  IPD_READONLY: 'IPD is read-only. Nothing can be changed.',
};

/** "Allergies not recorded" when the field is null or empty, else the text as stored. */
export function allergyLabel(patient: Pick<EmrPatient, 'allergyText'>): string {
  const text = patient.allergyText?.trim() ?? '';
  return text === '' ? 'Allergies not recorded' : text;
}

/** "Asha Patil · bed 101-A · Penicillin · ordered by Dr. Meera at 11:05 AM" (mockup `pendRow`). */
export function rowLine(row: PharmacistCheckRow, asOf: string): string {
  return `${row.patient.name} · bed ${row.patient.bedCode} · ${allergyLabel(row.patient)} · ordered by ${row.orderedByName} at ${eventTime(row.orderedAt, asOf)}`;
}

/** Bold part of a row: "<drug> · <frequency>". */
export const rowTitle = (row: Pick<PharmacistCheckRow, 'drugName' | 'frequencyLabel'>): string =>
  `${row.drugName} · ${row.frequencyLabel}`;

/** "Major: Bleeding risk ..." for the panel rows (plain); the sheet shows the severity in bold. */
export const warningLine = (finding: OrderFinding): string =>
  `${severityWord(finding.severity)}: ${finding.text}`;

export const verifyTitle = (row: Pick<PharmacistCheckRow, 'drugName'>): string =>
  `Pharmacist check · ${row.drugName}`;
export const verifySubtitle = (row: PharmacistCheckRow): string =>
  `${row.patient.name} · ${row.patient.uhid} · ordered by ${row.orderedByName}`;
export const rejectTitle = (row: Pick<PharmacistCheckRow, 'drugName'>): string =>
  `Reject · ${row.drugName}`;
export const rejectSubtitle = (row: PharmacistCheckRow): string =>
  `${row.patient.name} · ordered by ${row.orderedByName}`;

export const verifiedToast = (drugName: string, actorName: string): string =>
  `${drugName} verified by ${actorName}. It is now on the MAR.`;
export const rejectedToast = (label: string): string => `Order rejected · ${label}.`;

/**
 * The number on the Safety tab (mockup `sfCount`): open escalations, plus (owner) the orders waiting,
 * plus the late doses of the Medicine round (SAH-35). Doses that are only due are not added. For the owner,
 * SAH-34 adds the emergency access grants not yet reviewed (OQ-34-30, a deviation from the mockup's count).
 */
export function safetyTabCount(
  open: number | null | undefined,
  waiting: number | null | undefined,
  dosesLate: number | null | undefined = 0,
  emergencyAccessWaiting: number | null | undefined = 0,
): number {
  return (open ?? 0) + (waiting ?? 0) + (dosesLate ?? 0) + (emergencyAccessWaiting ?? 0);
}

/**
 * Whether a summary differs from the last one seen: a different count, or the same count with
 * another token. With nothing seen yet, only a count that differs from the one on screen counts
 * (the token is not known for the list that was read).
 */
export function summaryChanged(
  last: Pick<PharmacistChecksSummary, 'waiting' | 'changeToken'> | null,
  next: Pick<PharmacistChecksSummary, 'waiting' | 'changeToken'>,
  shownWaiting: number | null,
): boolean {
  if (last === null) {
    return shownWaiting !== null && shownWaiting !== next.waiting;
  }
  return last.waiting !== next.waiting || last.changeToken !== next.changeToken;
}
