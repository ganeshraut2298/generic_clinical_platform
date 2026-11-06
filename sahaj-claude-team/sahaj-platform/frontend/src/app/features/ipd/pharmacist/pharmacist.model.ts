import { eventTime } from '../safety/safety.model';
import {
  EmrPatient,
  FrequencyKey,
  KeyLabel,
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
export type VerifyLockReason = 'HARD_STOP' | 'SELF' | 'IPD_READONLY';

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
}

/** Counts only (ADR-005 D3): no names, drugs or text. */
export interface PharmacistChecksSummary {
  waiting: number;
  /** 22 characters, opaque: compared for equality only. */
  changeToken: string;
  asOf: string;
}

export interface VerifyOrderInput {
  pin: string;
  checkToken: string;
}

export interface RejectOrderInput {
  reasonKey: RejectReasonKey | string;
}

export interface PharmacistActionResult {
  result: {
    orderId: string;
    drugName: string;
    outcome: 'VERIFIED' | 'REJECTED';
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

/** Why Verify is off, as the hint beside the button (mockup title; story; contract). */
export const LOCK_HINT: Record<VerifyLockReason, string> = {
  HARD_STOP: 'Hard stop: reject this order',
  SELF: 'The doctor who ordered this cannot verify it. Ask the pharmacist.',
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

/** The number on the Safety tab: the owner's open escalations plus the orders waiting. */
export function safetyTabCount(
  open: number | null | undefined,
  waiting: number | null | undefined,
): number {
  return (open ?? 0) + (waiting ?? 0);
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
