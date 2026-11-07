import { formatDayMonth, istToday } from '../../config/config-format';
import { timeOfInstant } from '../emergency/emergency.model';
import { KEY_RELOAD, Mar, MAR_LOCK_LINES, clockText } from '../mar/mar.model';

/**
 * SAH-33 Safety board (mockup `ipSafety`, `escRow`, `sfban`). Types follow `SafetyBoard`,
 * `EscalationRow`, `ClinicalEventPage` and the action inputs in docs/api/ipd.yaml. Every minute count
 * and every overdue flag is the server's (`asOf`); the browser clock is never read for them.
 */
export type EscalationStatus = 'OPEN' | 'ACK' | 'DONE';
export type EscalationRisk = 'MEDIUM' | 'HIGH';
export type EscalationResponseAction =
  | 'BEDSIDE_REVIEW'
  | 'TREATMENT_CHANGED'
  | 'MOVED_ICU_HDU'
  | 'RAPID_RESPONSE'
  | 'NO_CHANGE'
  | 'CLOSED_AT_DISCHARGE';
export type SafetyView = 'DOCTOR' | 'NURSE';

export interface EscalationResponseActionOption {
  key: EscalationResponseAction;
  label: string;
}

export interface EscalationResponse {
  action: EscalationResponseAction;
  actionLabel: string;
  /** Clinical text: on screen only, for OWNER, DOCTOR and NURSE. Null only for CLOSED_AT_DISCHARGE. */
  note: string | null;
  closedByName: string;
  closedAt: string;
}

export interface EscalationRow {
  id: string;
  admissionId: string;
  version: number;
  patientName: string;
  bedCode: string;
  ewsScore: number;
  ewsRisk: EscalationRisk;
  targetMinutes: 15 | 60;
  status: EscalationStatus;
  raisedAt: string;
  raisedByName: string;
  minutesSinceRaised: number;
  reopenedAt: string | null;
  waitingMinutes: number;
  pastTarget: boolean;
  seniorCalls: number;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  response: EscalationResponse | null;
  canAcknowledge: boolean;
  canRespond: boolean;
  canCallSenior: boolean;
}

/** Counts only, no names (ADR-005 D3): the body of `GET /safety/summary`. */
export interface SafetyCounts {
  open: number;
  pastTarget: number;
  /** SAH-35: dose slots in state DUE (not counting LATE) of the Medicine round; always the full count. */
  dosesDue: number;
  /** SAH-35: dose slots in state LATE; the tile's "N overdue" and the dose term of the tab number. */
  dosesLate: number;
  /**
   * Opaque, 22 characters (ADR-007 R1): a hash of the id and version of every open escalation. Only
   * compared for equality: it differs when one is raised, acknowledged, closed, re-opened or scores
   * higher, which the two numbers alone can miss.
   */
  changeToken: string;
  asOf: string;
}

export interface SafetyBannerItem {
  escalationId: string;
  admissionId: string;
  patientName: string;
  ewsScore: number;
}

/** `AdmissionList.safety`: null for every role without a Safety tab. */
export interface AdmissionSafety {
  counts: SafetyCounts;
  pastTarget: SafetyBannerItem[];
}

export type MedicineRoundSlotState = 'LATE' | 'DUE';

/** One dose slot of today with no dose record: `MedicineRoundRow` in docs/api/ipd.yaml (SAH-35). */
export interface MedicineRoundRow {
  admissionId: string;
  orderId: string;
  /** The dose time of today as an instant; matched against `MarSlot.slotAt`. */
  slotAt: string;
  state: MedicineRoundSlotState;
  /** LATE: whole hours late, rounded down (at least 1); null for DUE. */
  lateHours: number | null;
  drugName: string;
  /** Not null for a high-alert medicine (the class is not shown here). */
  highAlert: string | null;
  patientName: string;
  bedCode: string;
}

export interface MedicineRound {
  canGive: boolean;
  giveLockReason: 'ROLE' | 'IPD_READONLY' | null;
  truncated: boolean;
  rows: MedicineRoundRow[];
}

export interface SafetyBoard {
  state: 'trial' | 'on' | 'readonly';
  view: SafetyView;
  asOf: string;
  counts: SafetyCounts;
  /** NURSE only; null for DOCTOR. */
  yourPatients: number | null;
  responseActions: EscalationResponseActionOption[];
  escalations: EscalationRow[];
  /** SAH-35: the panel "Medicine round" (OWNER, DOCTOR, NURSE). */
  medicineRound: MedicineRound;
}

export interface EscalationVersionInput {
  version: number;
}

export interface EscalationResponseInput {
  version: number;
  action: EscalationResponseAction;
  note: string;
}

export interface SeniorCallInput {
  version: number;
  seniorCalls: number;
}

export interface EscalationActionResult {
  asOf: string;
  replayed: boolean;
  escalation: EscalationRow;
}

export type ClinicalEventKind = 'give' | 'order' | 'verify' | 'esc' | 'view' | 'bg' | 'deny' | 'stop' | 'info';

export interface ClinicalEvent {
  id: string;
  kind: ClinicalEventKind;
  text: string;
  at: string;
  actorName: string;
  admissionId: string | null;
  patientName: string | null;
  bedCode: string | null;
}

export interface ClinicalEventPage {
  asOf: string;
  items: ClinicalEvent[];
  nextCursor: string | null;
}

// ---- Text (mockup wording, contract wording where the mockup differs: DEC-015) ----

export const PANEL_TITLE = 'Early warning escalations';
export const PANEL_SUBTITLE =
  'High score: doctor within 15 min. Medium: within 1 hour. A nurse can call the senior doctor if the target passes.';
export const NO_ESCALATIONS = 'No escalations. Scores of 5 and above raise one automatically when vitals are saved.';
export const AUDIT_TITLE = 'Clinical audit trail';
export const AUDIT_SUBTITLE_DOCTOR =
  'Every order, check, dose, escalation and record opened. Entries cannot be edited or deleted.';
export const AUDIT_SUBTITLE_NURSE = 'Your actions and your patients. Entries cannot be edited or deleted.';
export const NO_EVENTS = 'Nothing recorded yet.';
export const SHOW_EARLIER = 'Show earlier entries';
export const FIRST_PAGE = 14;
export const NEXT_PAGE = 50;
export const PLAN_MAX = 1000;
export const PLAN_EMPTY = 'Write the plan and when vitals are next due.';
export const PLAN_TOO_LONG = 'Keep the plan to 1,000 characters.';
export const PLAN_CHARACTERS = 'Plan cannot contain control or text-direction characters';
export const ACTION_MISSING = 'Choose what was done';
export const KEY_ERROR = 'Reload the page and try again.';
export const LOAD_ERROR = 'We could not load the Safety board. Try again.';
export const EVENTS_ERROR = 'We could not load the audit trail. Try again.';
export const ACTION_ERROR = 'We could not do that. Try again.';
export const RESPONSE_ERROR = 'We could not record the response. What you typed is kept. Try again.';
export const NOT_ADMITTED = 'This patient is not admitted any more.';
export const SAFETY_LABEL = 'Safety';
export const OPEN_BOARD = 'Open safety board';

/** "35 min ago", "1 h ago", "2 h 5 min ago": whole minutes the server sent, never the device clock. */
export function agoMinutesText(minutes: number): string {
  const m = Number.isFinite(minutes) ? Math.max(0, Math.floor(minutes)) : 0;
  if (m < 60) {
    return `${m} min ago`;
  }
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? `${h} h ${rest} min ago` : `${h} h ago`;
}

export const targetText = (row: Pick<EscalationRow, 'targetMinutes'>): string =>
  row.targetMinutes === 15 ? '15 min' : '1 hour';

export const riskWord = (risk: EscalationRisk): string => (risk === 'HIGH' ? 'High' : 'Medium');

/** "Raised by Nurse Priya 12 min ago · target 15 min · escalated to senior 2×". */
export function raisedLine(row: EscalationRow): string {
  const calls = row.seniorCalls > 0 ? ` · escalated to senior ${row.seniorCalls}×` : '';
  return `Raised by ${row.raisedByName} ${agoMinutesText(row.minutesSinceRaised)} · target ${targetText(row)}${calls}`;
}

export interface RowPill {
  text: string;
  /** Maps to the pill classes p-crit, p-warn, p-good. */
  tone: 'crit' | 'warn' | 'good';
}

/** Not seen (red), Overdue · not seen (red, row highlighted), Doctor reviewing (amber), Closed (green). */
export function statusPill(row: Pick<EscalationRow, 'status' | 'pastTarget'>): RowPill {
  if (row.status === 'DONE') {
    return { text: 'Closed', tone: 'good' };
  }
  if (row.status === 'ACK') {
    return { text: 'Doctor reviewing', tone: 'warn' };
  }
  return { text: row.pastTarget ? 'Overdue · not seen' : 'Not seen', tone: 'crit' };
}

/** The line under the raised line: the acknowledgement (eye) or the recorded response (check). */
export function detailLine(row: EscalationRow): { icon: 'eye' | 'check'; text: string } | null {
  if (row.status === 'ACK') {
    return {
      icon: 'eye',
      text: `Acknowledged by ${row.acknowledgedByName ?? 'Team member'} · response not recorded yet`,
    };
  }
  if (row.status === 'DONE' && row.response) {
    const parts = [row.response.actionLabel, row.response.note, row.acknowledgedByName].filter(
      (p): p is string => typeof p === 'string' && p.trim() !== '',
    );
    return { icon: 'check', text: parts.join(' · ') };
  }
  return null;
}

// ---- Red notice (top of the board) ----

export function noticeHead(pastTarget: number): string {
  return `${pastTarget} ${pastTarget === 1 ? 'escalation' : 'escalations'} past the response target.`;
}

/** "Asha Patil (EWS 8) raised 17 min ago; Ravi Shah (EWS 6) raised 1 h 5 min ago" then the ending. */
export function noticeBody(board: Pick<SafetyBoard, 'escalations' | 'view'>): string {
  const items = board.escalations
    .filter((e) => e.pastTarget)
    .map((e) => `${e.patientName} (EWS ${e.ewsScore}) raised ${agoMinutesText(e.waitingMinutes)}`)
    .join('; ');
  const ending = board.view === 'DOCTOR' ? ' Acknowledge, then record what you did.' : ' Call the senior doctor now.';
  return `${items}.${ending}`;
}

/** Tile "Escalations open": the small text and whether it is red. */
export function openTileText(counts: Pick<SafetyCounts, 'pastTarget'>): { text: string; critical: boolean } {
  return counts.pastTarget > 0
    ? { text: `${counts.pastTarget} past target`, critical: true }
    : { text: 'All within target', critical: false };
}

export const YOUR_PATIENTS_TEXT = 'In your wards';

// ---- Banner on the Admitted patients tab ----

/** "Asha Patil · EWS 8, Ravi Shah · EWS 6" (bold part of the banner). */
export function bannerNames(items: readonly SafetyBannerItem[]): string {
  return items.map((i) => `${i.patientName} · EWS ${i.ewsScore}`).join(', ');
}

export const BANNER_TAIL = ' escalated and not seen by a doctor within target.';

// ---- Response sheet ----

export const responseTitle = (row: Pick<EscalationRow, 'patientName'>): string => `Response · ${row.patientName}`;

/** "EWS 8 (high) · raised 17 min ago by Priya". */
export function responseSubtitle(row: EscalationRow): string {
  return `EWS ${row.ewsScore} (${row.ewsRisk.toLowerCase()}) · raised ${agoMinutesText(row.minutesSinceRaised)} by ${row.raisedByName}`;
}

/** Refused by the server: control characters except LF (tab is turned into a space first), and text-direction or invisible marks. */
const REFUSED_RANGES: readonly (readonly [number, number])[] = [
  [0x00, 0x09],
  [0x0b, 0x1f],
  [0x7f, 0x9f],
  [0x061c, 0x061c],
  [0x200b, 0x200b],
  [0x200e, 0x200f],
  [0x2028, 0x202e],
  [0x2060, 0x2064],
  [0x2066, 0x2069],
  [0xfeff, 0xfeff],
];

const hasRefused = (text: string): boolean =>
  Array.from(text).some((ch) => {
    const code = ch.codePointAt(0) ?? 0;
    return REFUSED_RANGES.some(([from, to]) => code >= from && code <= to);
  });

/** The plan as the server counts it: NFC, line breaks as LF, a tab as one space, stripped. */
export function normalisePlan(text: string): string {
  return text.normalize('NFC').replace(/\r\n|\r/g, '\n').replace(/\t/g, ' ').trim();
}

export function planLength(text: string): number {
  return Array.from(normalisePlan(text)).length;
}

/** Counter under the plan: "1,012 / 1,000" (code points, as the server counts them). */
export const planCounterText = (length: number): string =>
  `${length.toLocaleString('en-IN')} / ${PLAN_MAX.toLocaleString('en-IN')}`;

export interface ResponseDraft {
  action: string;
  note: string;
}

export type ResponseField = 'action' | 'note';
export type ResponseErrors = Partial<Record<ResponseField, string>>;

/** Same steps and messages as `EscalationResponseInput.note`; the server checks everything again. */
export function validateResponse(
  draft: ResponseDraft,
  actions: readonly EscalationResponseActionOption[],
): ResponseErrors {
  const errors: ResponseErrors = {};
  if (!actions.some((a) => a.key === draft.action)) {
    errors.action = ACTION_MISSING;
  }
  const raw = draft.note.normalize('NFC').replace(/\r\n|\r/g, '\n').replace(/\t/g, ' ');
  if (hasRefused(raw)) {
    errors.note = PLAN_CHARACTERS;
  } else if (raw.trim() === '') {
    errors.note = PLAN_EMPTY;
  } else if (planLength(draft.note) > PLAN_MAX) {
    errors.note = PLAN_TOO_LONG;
  }
  return errors;
}

export function toResponseInput(
  version: number,
  draft: ResponseDraft,
): EscalationResponseInput {
  return { version, action: draft.action as EscalationResponseAction, note: draft.note };
}

// ---- Toasts ----

export const ackToast = (patientName: string): string =>
  `Acknowledged. Record what you did for ${patientName} after you see the patient.`;
export const closedToast = (patientName: string): string => `Escalation for ${patientName} closed and recorded`;
/** DEC-015 OQ-33-3: nothing is sent in phase 1, so the toast never says anyone was called. */
export const callToast = (patientName: string): string =>
  `Call recorded for ${patientName}. No alert is sent: phone the senior doctor now.`;

/** "Could not refresh. Showing data from 11:05 AM." from the last good answer's `asOf`. */
export function staleBoardText(asOf: string | null | undefined): string {
  const time = timeOfInstant(asOf);
  return time === '' ? 'Could not refresh.' : `Could not refresh. Showing data from ${time}.`;
}

// ---- Audit trail ----

/** Icon of an entry by kind (mockup `kic`). */
export const KIND_ICON = {
  give: 'pill',
  order: 'emr',
  verify: 'shieldu',
  esc: 'siren',
  view: 'eye',
  bg: 'key',
  deny: 'alert',
  stop: 'x',
  info: 'history',
} as const satisfies Record<ClinicalEventKind, string>;

export function kindIcon(kind: string): (typeof KIND_ICON)[ClinicalEventKind] {
  return (KIND_ICON as Record<string, (typeof KIND_ICON)[ClinicalEventKind]>)[kind] ?? 'history';
}

/** "11:05 AM" when the IST date of `at` is the IST date of `asOf`, else "24 Oct, 11:05 AM". */
export function eventTime(at: string, asOf: string): string {
  const when = new Date(at);
  const now = new Date(asOf);
  if (Number.isNaN(when.getTime()) || Number.isNaN(now.getTime())) {
    return '';
  }
  const time = timeOfInstant(at);
  const day = istToday(when);
  return day === istToday(now) ? time : `${formatDayMonth(day)}, ${time}`;
}

/** The small line: "Dr. Meera" plus " · Asha Patil · bed 101-A" when the entry belongs to an admission. */
export function eventLine(event: ClinicalEvent): string {
  if (event.admissionId === null) {
    return event.actorName;
  }
  const bed = event.bedCode ? ` · bed ${event.bedCode}` : '';
  return `${event.actorName} · ${event.patientName ?? 'Patient'}${bed}`;
}

export const auditSubtitle = (view: SafetyView): string =>
  view === 'DOCTOR' ? AUDIT_SUBTITLE_DOCTOR : AUDIT_SUBTITLE_NURSE;

/**
 * A reload of the first page while older entries are showing: the newest entries replace the top,
 * the older ones stay. When the two pages share no entry (more than a page of new entries came in)
 * the list starts over from the fresh page, so no gap is ever shown.
 */
export function mergeEvents(
  current: { items: ClinicalEvent[]; nextCursor: string | null },
  fresh: ClinicalEventPage,
): { items: ClinicalEvent[]; nextCursor: string | null } {
  if (current.items.length <= fresh.items.length) {
    return { items: fresh.items, nextCursor: fresh.nextCursor };
  }
  const freshIds = new Set(fresh.items.map((e) => e.id));
  if (!current.items.some((e) => freshIds.has(e.id))) {
    return { items: fresh.items, nextCursor: fresh.nextCursor };
  }
  return {
    items: [...fresh.items, ...current.items.filter((e) => !freshIds.has(e.id))],
    nextCursor: current.nextCursor,
  };
}

// ---- Medicine round (SAH-35, mockup `ipSafety` tile `kpi('Doses due or overdue')`, `doseRow`) ----

export const DOSES_TILE_TITLE = 'Doses due or overdue';
export const ROUND_TITLE = 'Medicine round';
export const ROUND_HEADING_ID = 'sf-round-h';
export const NO_DOSES = 'No doses due right now.';
/** Contract wording (OQ-35-4, OQ-35-9); not in the mockup. */
export const ROUND_TRUNCATED = 'Showing the 200 most overdue doses.';
export const HIGH_ALERT_PILL = 'High-alert';
export const DUE_NOW = 'Due now';
export const GIVE_LABEL = 'Give';
/** DEC-018 text (also `MAR_LOCK_LINES.IPD_READONLY`). */
export const ROUND_READONLY_LINE = MAR_LOCK_LINES.IPD_READONLY;
export const GIVE_STOPPED = 'This medicine was stopped. Its doses can no longer be recorded.';
export const GIVE_RECORDED = 'This dose is already recorded.';
export const GIVE_DISCHARGED = 'This patient is not admitted any more. Doses cannot be recorded.';
/** The SAH-32 text of the MAR tab read (DEC-018 build notes). */
export const ROUND_MAR_ERROR = 'We could not load the medicines. Try again.';

/** The number on the tile: Due plus Late (the same set as the rows, before the 200 limit). */
export const dosesTileNumber = (counts: Pick<SafetyCounts, 'dosesDue' | 'dosesLate'>): number =>
  (counts.dosesDue ?? 0) + (counts.dosesLate ?? 0);

/** Small text of the tile: "3 overdue", and "0 overdue" when nobody is late (mockup). */
export const dosesTileText = (counts: Pick<SafetyCounts, 'dosesLate'>): string =>
  `${counts.dosesLate ?? 0} overdue`;

/** "Doses due in the next hour and doses not given on time. Clock: 11:05 AM." (server clock, IST). */
export function roundSubtitle(asOf: string | null | undefined): string {
  return `Doses due in the next hour and doses not given on time. Clock: ${timeOfInstant(asOf)}.`;
}

/** "Asha Patil · bed 101-A · 8 AM dose". */
export const roundRowLine = (row: Pick<MedicineRoundRow, 'patientName' | 'bedCode' | 'slotAt'>): string =>
  `${row.patientName} · bed ${row.bedCode} · ${clockText(row.slotAt)} dose`;

/** "2 h late" (red) or "Due now" (amber); the words carry the meaning, never the colour alone. */
export function roundStatusPill(row: Pick<MedicineRoundRow, 'state' | 'lateHours'>): RowPill {
  return row.state === 'LATE'
    ? { text: `${Math.max(1, row.lateHours ?? 1)} h late`, tone: 'crit' }
    : { text: DUE_NOW, tone: 'warn' };
}

/** Accessible name of the button: "Give Tab. Paracetamol 650 mg to Asha Patil". */
export const giveName = (row: Pick<MedicineRoundRow, 'drugName' | 'patientName'>): string =>
  `Give ${row.drugName} to ${row.patientName}`;

/** A stable key of one dose: the same order and slot is the same row after a reload. */
export const roundKey = (row: Pick<MedicineRoundRow, 'orderId' | 'slotAt'>): string => `${row.orderId}|${row.slotAt}`;

export type GiveCheck =
  | { open: true; order: Mar['orders'][number]; slot: Mar['orders'][number]['slots'][number] }
  | { open: false; message: string };

/**
 * The re-check after the MAR read (ADR-012 D4): the Give sheet opens only when the slot is still
 * givable. The texts are the existing SAH-32 and DEC-018 ones; no new problem text.
 */
export function checkGive(mar: Mar, row: Pick<MedicineRoundRow, 'orderId' | 'slotAt'>): GiveCheck {
  if (mar.status === 'DISCHARGED') {
    return { open: false, message: GIVE_DISCHARGED };
  }
  const order = mar.orders.find((o) => o.orderId === row.orderId);
  if (!order) {
    return { open: false, message: GIVE_STOPPED };
  }
  const at = Date.parse(row.slotAt);
  const slot = order.slots.find((s) => Date.parse(s.slotAt) === at);
  if (!slot) {
    return { open: false, message: KEY_RELOAD };
  }
  if (slot.state === 'GIVEN' || slot.state === 'HELD' || slot.state === 'REFUSED') {
    return { open: false, message: GIVE_RECORDED };
  }
  if (!mar.canGive && mar.giveLockReason === 'IPD_READONLY') {
    return { open: false, message: ROUND_READONLY_LINE };
  }
  if (!mar.canGive || !slot.canGive || (slot.state !== 'DUE' && slot.state !== 'LATE')) {
    return { open: false, message: KEY_RELOAD };
  }
  return { open: true, order, slot };
}

// ---- Counts ----

type Compared = Pick<SafetyCounts, 'open' | 'pastTarget' | 'dosesDue' | 'dosesLate' | 'changeToken'>;

/** The poll compares the escalation numbers, the dose numbers and the token (the token also catches a swap). */
export const sameCounts = (a: Compared, b: Compared): boolean =>
  a.open === b.open &&
  a.pastTarget === b.pastTarget &&
  a.dosesDue === b.dosesDue &&
  a.dosesLate === b.dosesLate &&
  a.changeToken === b.changeToken;
