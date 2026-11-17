/**
 * SAH-76 shared order types, mirroring docs/api/ipd.yaml 0.4.0 (OrderDetails, OrderChangeLine,
 * OrderHold, OrderQuery, OrderPriority, NoticeCount). Hand-written; never add a field that is not
 * in the contract.
 */
export type { KeyLabel } from '../emr/emr.model';

export type OrderPriority = 'ROUTINE' | 'URGENT' | 'STAT';
export type OrderKind = 'SCHEDULED' | 'PRN' | 'ONCE';
export type OrderFrequencyKey = 'od' | 'bd' | 'tds' | 'q8' | 'prn';
export type RouteKey =
  | 'ORAL'
  | 'IV'
  | 'IV_INFUSION'
  | 'IM'
  | 'SC'
  | 'SUBLINGUAL'
  | 'INHALED'
  | 'TOPICAL'
  | 'RECTAL'
  | 'NASOGASTRIC';
export type DoseUnitKey = 'TABLET' | 'CAPSULE' | 'MG' | 'G' | 'MCG' | 'ML' | 'IU' | 'UNIT' | 'MMOL';

export interface OrderDetails {
  kind: OrderKind;
  doseAmount: number | null;
  doseUnit: DoseUnitKey | null;
  routeKey: RouteKey;
  routeLabel: string;
  frequencyKey: OrderFrequencyKey | null;
  frequencyLabel: string;
  startAt: string;
  endAt: string | null;
  durationDays: number | null;
  /** Clinical text: on screen only. */
  indication: string | null;
  priority: OrderPriority;
  prnMinIntervalMinutes: number | null;
  prnMaxPer24h: number | null;
  /** False when verification was not needed, true when verified, null while waiting. */
  pharmacistChecked: boolean | null;
}

export type OrderChangeField =
  | 'dose'
  | 'route'
  | 'frequency'
  | 'start'
  | 'end'
  | 'indication'
  | 'priority'
  | 'prnInterval'
  | 'prnMax';

export interface OrderChangeLine {
  field: OrderChangeField;
  label: string;
  before: string;
  after: string;
}

export interface OrderHold {
  heldAt: string;
  heldByName: string;
  /** Clinical text: null on the MAR and for roles without clinical access. */
  reason: string | null;
}

/** Clinical text: sent only to OWNER, DOCTOR and PHARMACIST. */
export interface OrderQuery {
  question: string;
  askedByName: string;
  askedAt: string;
  reply: string | null;
  repliedByName: string | null;
  repliedAt: string | null;
}

export interface NoticeCount {
  count: number;
  stat: number;
  overdue: number;
}

// ---- SAH-76-1: how an order version is written on screen ----

/** Labels of every DoseUnitKey (docs/api/ipd.yaml). */
export const DOSE_UNIT_LABEL: Record<DoseUnitKey, string> = {
  TABLET: 'tablet',
  CAPSULE: 'capsule',
  MG: 'mg',
  G: 'g',
  MCG: 'mcg',
  ML: 'ml',
  IU: 'IU',
  UNIT: 'units',
  MMOL: 'mmol',
};

export const PRIORITY_LABEL: Record<OrderPriority, string> = {
  ROUTINE: 'Routine',
  URGENT: 'Urgent',
  STAT: 'STAT',
};

export const DOSE_AS_IN_NAME = 'Dose as in the medicine name';
export const NOT_PHARMACIST_CHECKED = 'Not pharmacist checked';
export const UNTIL_STOPPED = 'Until stopped';

/** "1 tablet", "650 mg"; "Dose as in the medicine name" for a built order with no amount. */
export function doseText(details: Pick<OrderDetails, 'doseAmount' | 'doseUnit'>): string {
  if (details.doseAmount === null || details.doseAmount === undefined) {
    return DOSE_AS_IN_NAME;
  }
  const unit = details.doseUnit ? ` ${DOSE_UNIT_LABEL[details.doseUnit]}` : '';
  return `${details.doseAmount}${unit}`;
}

/** The frequency as the small line shows it: "STAT" for a one-dose order, else the label. */
export function frequencyText(details: Pick<OrderDetails, 'kind' | 'frequencyLabel'>): string {
  return details.kind === 'ONCE' ? 'STAT' : details.frequencyLabel;
}

export function durationText(details: Pick<OrderDetails, 'kind' | 'durationDays'>): string | null {
  if (details.kind === 'ONCE') {
    return null;
  }
  if (details.durationDays === null || details.durationDays === undefined) {
    return UNTIL_STOPPED;
  }
  return details.durationDays === 1 ? 'for 1 day' : `for ${details.durationDays} days`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;
const IST_OFFSET_MS = 330 * 60_000;

/** "24 Oct, 10:00 AM" in IST (the contract's wording for a start). Empty for a bad instant. */
export function dayAndTime(instant: string | null | undefined): string {
  const t = instant ? Date.parse(instant) : Number.NaN;
  if (Number.isNaN(t)) {
    return '';
  }
  const d = new Date(t + IST_OFFSET_MS);
  const h = d.getUTCHours();
  const m = String(d.getUTCMinutes()).padStart(2, '0');
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}, ${h % 12 || 12}:${m} ${h < 12 ? 'AM' : 'PM'}`;
}

/** "11:05 AM" in IST. Empty for a bad instant. */
export function clock(instant: string | null | undefined): string {
  const full = dayAndTime(instant);
  return full === '' ? '' : full.slice(full.indexOf(',') + 2);
}

/**
 * "1 tablet · Oral · Once daily · from 24 Oct, 10:00 AM · for 5 days" (OrderDetails): dose, route, frequency,
 * start and duration. A STAT order has no duration.
 */
export function detailsLine(details: OrderDetails): string {
  const parts = [doseText(details), details.routeLabel, frequencyText(details)];
  const start = dayAndTime(details.startAt);
  if (start !== '') {
    parts.push(`from ${start}`);
  }
  const duration = durationText(details);
  if (duration !== null) {
    parts.push(duration);
  }
  return parts.join(' · ');
}

/** "6 h", "90 min", "1 h 30 min". */
export function intervalText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) {
    return '';
  }
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) {
    return `${m} min`;
  }
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/** "Changed: Dose: 1 tablet → 2 tablet". */
export const changeLineText = (line: OrderChangeLine): string =>
  `Changed: ${line.label}: ${line.before} → ${line.after}`;

// ---- SAH-76-5: what the nurses did with an order, for the doctor ----

/** OrderExecution in docs/api/ipd.yaml. */
export interface OrderExecution {
  notGivenToday: number;
  nextDueAt: string | null;
  lateCount: number;
  acknowledged: boolean;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
}

export interface ExecutionLine {
  key: 'last' | 'next' | 'late' | 'notGivenToday' | 'ack';
  text: string;
  warn: boolean;
}

/** The slice of a dose record the execution lines read. */
export interface ExecutionDose {
  outcome: 'GIVEN' | 'HELD' | 'REFUSED';
  reason: { label: string } | null;
  recordedAt: string;
  recordedByName: string;
}

/**
 * The lines under an order row (OrderExecution, NEW): when the latest live record is not given, "Not given ·
 * <reason> · <time> · <nurse>" in amber; else "Last dose: Given <time> by <nurse>"; then "<n> late" or "Next due
 * <time>" (a PRN order has no next due); then "Not given today: <n>" when above 0; then the acknowledgement.
 * No text says the doctor was notified.
 */
export function executionLines(
  execution: OrderExecution,
  lastDose: ExecutionDose | null,
  kind: OrderKind,
): ExecutionLine[] {
  const lines: ExecutionLine[] = [];
  if (lastDose && lastDose.outcome !== 'GIVEN') {
    lines.push({
      key: 'last',
      text: `Not given · ${lastDose.reason?.label ?? ''} · ${clock(lastDose.recordedAt)} · ${lastDose.recordedByName}`,
      warn: true,
    });
  } else if (lastDose) {
    lines.push({
      key: 'last',
      text: `Last dose: Given ${clock(lastDose.recordedAt)} by ${lastDose.recordedByName}`,
      warn: false,
    });
  }
  if (execution.lateCount > 0) {
    lines.push({ key: 'late', text: `${execution.lateCount} late`, warn: true });
  } else if (kind !== 'PRN' && execution.nextDueAt) {
    lines.push({ key: 'next', text: `Next due ${clock(execution.nextDueAt)}`, warn: false });
  }
  if (execution.notGivenToday > 0) {
    lines.push({ key: 'notGivenToday', text: `Not given today: ${execution.notGivenToday}`, warn: true });
  }
  lines.push({
    key: 'ack',
    text:
      execution.acknowledged && execution.acknowledgedByName
        ? `Acknowledged by ${execution.acknowledgedByName} at ${clock(execution.acknowledgedAt)}`
        : 'Not acknowledged yet',
    warn: false,
  });
  return lines;
}

// ---- SAH-76-3: nurse order notices (OrderNotice and friends in docs/api/ipd.yaml) ----

export type OrderNoticeKind = 'NEW' | 'CHANGED' | 'HELD' | 'RESUMED' | 'STOPPED';

export const NOTICE_KIND_LABEL: Record<OrderNoticeKind, string> = {
  NEW: 'New',
  CHANGED: 'Changed',
  HELD: 'On hold',
  RESUMED: 'Resumed',
  STOPPED: 'Stopped',
};

export interface StatLine {
  dueBy: string;
  windowMinutes: number;
  minutesLeft: number | null;
  minutesOverdue: number | null;
}

export interface OrderNotice {
  id: string;
  admissionId: string;
  orderId: string;
  orderVersion: number;
  kind: OrderNoticeKind;
  priority: OrderPriority;
  drugKey: string;
  drugName: string;
  details: OrderDetails;
  changes: OrderChangeLine[];
  /** Clinical text: on screen only. */
  reason: string | null;
  createdAt: string;
  createdByName: string;
  acknowledgedAt: string | null;
  acknowledgedByName: string | null;
  waitingMinutes: number | null;
  overdue: boolean;
  stat: StatLine | null;
  canAcknowledge: boolean;
}

export interface OrderNoticeList {
  admissionId: string;
  asOf: string;
  state: 'trial' | 'on' | 'readonly';
  status: 'ADMITTED' | 'DISCHARGED';
  open: NoticeCount;
  items: OrderNotice[];
  canAcknowledge: boolean;
}

export interface OrderNoticePageRow {
  notice: OrderNotice;
  /** On screen only; never logged. */
  patientName: string;
  bedCode: string;
}

export interface OrderNoticePage {
  asOf: string;
  open: NoticeCount;
  items: OrderNoticePageRow[];
  nextCursor: string | null;
}

export interface OrderNoticeSummaryRow {
  admissionId: string;
  count: number;
  stat: number;
  overdue: number;
  statDueBy: string | null;
}

export interface OrderNoticeSummary {
  asOf: string;
  total: NoticeCount;
  byAdmission: OrderNoticeSummaryRow[];
  /** 22 characters, opaque: compared for equality only. */
  changeToken: string;
}
