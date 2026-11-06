import { ApiError } from '../../../core/http/problem-detail';
import { problemMessage } from '../../config/config-format';
import {
  DoseRow,
  EmrPatient,
  FrequencyKey,
  KeyLabel,
  NotGivenReasonKey,
  STATUS_PILL,
} from '../emr/emr.model';

export type { DoseOutcome, DoseRow, NotGivenReasonKey } from '../emr/emr.model';

/**
 * SAH-32 Medicines (MAR) tab of the Nursing sheet and the Give sheet (mockup `sfNsTabs`, `sfMar`,
 * `sfSlotChip`, `sfMedHead`, `sfGive`, `sfgiveok`, `sfnogive`). Types follow `Mar`, `MarOrder`,
 * `MarSlot`, `DoseRow`, `DoseInput` and `DoseRecorded` in docs/api/ipd.yaml; no field is invented.
 * Every state, count and rule is the server's (ADR-011): the client only draws what it is told and
 * never reads the device clock. Names, UHID and allergy text are on screen only: nothing here is
 * logged or stored. The scanned wristband text and the second nurse's PIN live in form controls only.
 */

export type MarSlotState =
  | 'GIVEN'
  | 'HELD'
  | 'REFUSED'
  | 'NOT_RECORDED'
  | 'WAITING'
  | 'BEFORE_START'
  | 'LATE'
  | 'DUE'
  | 'LATER';

export type MarGiveLockReason = 'ROLE' | 'IPD_READONLY' | 'NOT_ADMITTED';

export interface MarSlot {
  /** The dose time of today as an instant: sent back as `DoseInput.slotAt`. */
  slotAt: string;
  state: MarSlotState;
  /** For LATE: whole hours late, rounded down (at least 1). */
  lateHours: number | null;
  canGive: boolean;
  dose: DoseRow | null;
}

export interface MarOrder {
  orderId: string;
  drugKey: string;
  drugName: string;
  route: string;
  highAlert: string | null;
  frequencyKey: FrequencyKey;
  frequencyLabel: string;
  orderedByName: string;
  status: 'PENDING' | 'ACTIVE';
  slots: MarSlot[];
}

export interface SecondNurseOption {
  /** Sent as `DoseInput.secondNurseId`. */
  membershipId: string;
  name: string;
}

export interface Mar {
  admissionId: string;
  status: 'ADMITTED' | 'DISCHARGED';
  state: 'trial' | 'on' | 'readonly';
  /** Database clock of the answer: the base of "today" and of the subtitle time. */
  asOf: string;
  patient: EmrPatient;
  doseCount: number;
  canGive: boolean;
  giveLockReason: MarGiveLockReason | null;
  orders: MarOrder[];
  secondNurses: SecondNurseOption[];
  notGivenReasons: KeyLabel[];
}

export interface DoseInput {
  clientKey: string;
  slotAt: string;
  outcome: 'GIVEN' | 'NOT_GIVEN';
  wristband?: string | null;
  identityConfirmed?: boolean | null;
  secondNurseId?: string | null;
  secondNursePin?: string | null;
  reasonKey?: NotGivenReasonKey | string | null;
}

export interface DoseRecorded {
  /** True when an earlier request with the same clientKey recorded this dose (HTTP 200). */
  replayed: boolean;
  dose: DoseRow;
  mar: Mar;
}

// ---- Texts (exact, from the mockup and docs/api/ipd.yaml) ----

export const TAB_VITALS = 'Vitals and notes';
export const TAB_MAR = 'Medicines (MAR)';
export const NO_MEDICINES = 'No medicines on the chart. The doctor orders them from EMR.';
export const MAR_CLOSING_LINE =
  "Every dose needs the wristband scan and the patient's name and date of birth. High-alert medicines also need a second nurse.";
export const PENDING_LOCK = 'Cannot be given until the pharmacist checks the order.';
export const HIGH_ALERT_HINT = 'High-alert medicine: a second nurse checks every dose';
/** ROLE shows no line (the buttons are disabled, mockup). */
export const MAR_LOCK_LINES: Record<'IPD_READONLY' | 'NOT_ADMITTED', string> = {
  IPD_READONLY: 'IPD is read-only. Doses cannot be recorded.',
  NOT_ADMITTED: 'This patient is not admitted any more.',
};

export const STEP_ONE = '1 · Confirm the patient with two identifiers';
export const WRISTBAND_LABEL = 'Wristband MRN';
export const WRISTBAND_PLACEHOLDER = 'Scan the wristband or type the MRN';
export const SAID_LABEL = 'The patient said their name and date of birth, or a relative confirmed them';
export const SECOND_NURSE_SMALL = 'A second nurse checks the drug, dose, patient and route, then enters their PIN.';
export const CHECKED_BY_LABEL = 'Checked by';
export const CHOOSE_NURSE = 'Choose a nurse';
export const THEIR_PIN_LABEL = 'Their PIN';
export const PIN_PLACEHOLDER = '4 digits';
export const REASON_LABEL = 'Reason';
export const CHOOSE_REASON = 'Choose a reason';

/** Texts that are not in the mockup or the contract: the tech lead lists them in the handoff. */
export const MAR_LOAD_ERROR = 'We could not load the medicines. Try again.';
export const MAR_LOADING = 'Loading the medicines';
export const GIVE_ERROR = 'We could not record the dose. What you entered is kept, except the PIN. Try again.';
export const KEY_RELOAD = 'Reload the page and try again.';

// ---- Tabs ----

export type NursingTab = 'vit' | 'mar';
export const NURSING_TABS: readonly NursingTab[] = ['vit', 'mar'];

/** The tab a key moves to (arrows wrap, Home and End jump), or null when the key is not a tab key. */
export function tabAfterKey(key: string, current: NursingTab): NursingTab | null {
  const at = NURSING_TABS.indexOf(current);
  const last = NURSING_TABS.length - 1;
  switch (key) {
    case 'ArrowRight':
      return NURSING_TABS[(at + 1) % NURSING_TABS.length];
    case 'ArrowLeft':
      return NURSING_TABS[(at - 1 + NURSING_TABS.length) % NURSING_TABS.length];
    case 'Home':
      return NURSING_TABS[0];
    case 'End':
      return NURSING_TABS[last];
    default:
      return null;
  }
}

/** The count on the tab: only above 0 (AC 1). */
export function countBadge(count: number | null | undefined): number | null {
  return count !== null && count !== undefined && count > 0 ? count : null;
}

// ---- Times (IST, mockup `sfHm`) ----

const IST_OFFSET_MS = 330 * 60_000;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;

/** The IST wall clock of an instant, held in the UTC fields of a Date. Null for a bad instant. */
function ist(instant: string | null | undefined): Date | null {
  const t = instant ? Date.parse(instant) : Number.NaN;
  return Number.isNaN(t) ? null : new Date(t + IST_OFFSET_MS);
}

/** "8 AM", "2 PM", "8:30 AM", "8:04 AM": minutes left out when 0 (mockup `sfHm`). */
export function clockText(instant: string | null | undefined): string {
  const d = ist(instant);
  if (!d) {
    return '';
  }
  const h = d.getUTCHours();
  const m = d.getUTCMinutes();
  return `${h % 12 || 12}${m ? ':' + String(m).padStart(2, '0') : ''} ${h < 12 ? 'AM' : 'PM'}`;
}

function istDay(instant: string): string {
  const d = ist(instant);
  return d ? d.toISOString().slice(0, 10) : '';
}

/** "8 AM" for a slot of the IST day of `asOf`, otherwise "24 Oct, 8 PM". */
export function slotWhen(slotAt: string, asOf: string): string {
  const d = ist(slotAt);
  if (!d) {
    return '';
  }
  const time = clockText(slotAt);
  return istDay(slotAt) === istDay(asOf) ? time : `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}, ${time}`;
}

/** The last word of a name: "Sr. Anjali Pawar" is "Pawar". */
export function lastWord(name: string): string {
  const words = name.trim().split(/\s+/);
  return words[words.length - 1] ?? '';
}

// ---- MAR tab ----

export function marSubtitle(mar: Pick<Mar, 'patient' | 'asOf'>): string {
  return `Bed ${mar.patient.bedCode} · medicine round, today ${clockText(mar.asOf)}`;
}

/** Small line under the medicine name: "<route> · <frequency> · <ordering doctor>". */
export function marOrderLine(order: Pick<MarOrder, 'route' | 'frequencyLabel' | 'orderedByName'>): string {
  return `${order.route} · ${order.frequencyLabel} · ${order.orderedByName}`;
}

export function highAlertPill(order: Pick<MarOrder, 'highAlert'>): string {
  return `High-alert · ${order.highAlert ?? ''}`;
}

export const orderPill = (order: Pick<MarOrder, 'status'>) => STATUS_PILL[order.status];

export type ChipTone = 'ok' | 'grey' | 'later' | 'crit' | 'warn';

export interface SlotChip {
  kind: 'done' | 'static' | 'button';
  tone: ChipTone;
  time: string;
  /** The small text under the time. */
  small: string;
  /** The hover and screen-reader hint ("Given by <nurse>, checked by <second nurse>"), or null. */
  hint: string | null;
}

/** What one slot shows, by its state (mockup `sfSlotChip`; contract wording for the two extra states). */
export function slotChip(slot: MarSlot): SlotChip {
  const time = clockText(slot.slotAt);
  const dose = slot.dose;
  switch (slot.state) {
    case 'GIVEN': {
      const by = dose ? lastWord(dose.recordedByName) : '';
      const checked = dose?.secondCheckByName ? `, checked by ${dose.secondCheckByName}` : '';
      return {
        kind: 'done',
        tone: 'ok',
        time,
        small: dose ? `${clockText(dose.recordedAt)} · ${by}` : '',
        hint: dose ? `Given by ${dose.recordedByName}${checked}` : null,
      };
    }
    case 'HELD':
    case 'REFUSED': {
      const reason = dose?.reason?.label ?? '';
      return {
        kind: 'static',
        tone: 'grey',
        time,
        small: `${slot.state === 'HELD' ? 'Not given' : 'Refused'} · ${reason}`,
        hint: reason || null,
      };
    }
    case 'NOT_RECORDED':
      return { kind: 'static', tone: 'grey', time, small: 'Not recorded', hint: null };
    case 'WAITING':
      return { kind: 'static', tone: 'grey', time, small: 'Waiting for pharmacist', hint: null };
    case 'BEFORE_START':
      return { kind: 'static', tone: 'grey', time, small: 'Before the order started', hint: null };
    case 'LATER':
      return { kind: 'static', tone: 'later', time, small: 'Later today', hint: null };
    case 'LATE':
      return {
        kind: 'button',
        tone: 'crit',
        time,
        small: `${Math.max(1, slot.lateHours ?? 1)} h late · Give`,
        hint: null,
      };
    case 'DUE':
      return { kind: 'button', tone: 'warn', time, small: 'Due now · Give', hint: null };
  }
}

// ---- Give sheet ----

export const giveTitle = (order: Pick<MarOrder, 'drugName'>): string => `Give · ${order.drugName}`;

/** "<time> dose · <route> · <frequency>" plus " · 1 h late" when late. */
export function giveSubtitle(
  order: Pick<MarOrder, 'route' | 'frequencyLabel'>,
  slot: Pick<MarSlot, 'slotAt' | 'state' | 'lateHours'>,
): string {
  const late = slot.state === 'LATE' ? ` · ${Math.max(1, slot.lateHours ?? 1)} h late` : '';
  return `${clockText(slot.slotAt)} dose · ${order.route} · ${order.frequencyLabel}${late}`;
}

export const highAlertStepTitle = (order: Pick<MarOrder, 'highAlert'>): string =>
  `2 · Second nurse check · high-alert ${(order.highAlert ?? '').toLowerCase()}`;

/** "2 · If you are not giving it", or "3 ·" when the high-alert step is there. */
export const notGivingStepTitle = (highAlert: boolean): string =>
  `${highAlert ? '3' : '2'} · If you are not giving it`;

export const givenToast = (drugName: string, patientName: string): string =>
  `${drugName} recorded as given to ${patientName}`;

export const notGivenToast = (drugName: string, reasonLabel: string): string =>
  `Recorded: ${drugName} not given · ${reasonLabel}. The doctor sees this on the chart.`;

export const mismatchToast = (patientName: string): string =>
  `Wristband does not match ${patientName}. Stop. Check the patient before giving anything.`;

export const mismatchLine = (patientName: string): string =>
  `Wrong patient. This band is not ${patientName}'s.`;

/** The fields the server can refuse on the Give sheet, in the order of the sheet. */
export type GiveField = 'wristband' | 'identityConfirmed' | 'secondNurseId' | 'secondNursePin' | 'reasonKey';
export const GIVE_FIELD_ORDER: readonly GiveField[] = [
  'wristband',
  'identityConfirmed',
  'secondNurseId',
  'secondNursePin',
  'reasonKey',
];
export type GiveErrors = Partial<Record<GiveField, string>>;

/** What a failed Give or Record not given means for the sheet (docs/api/ipd.yaml POST /medicine-orders/{id}/doses). */
export type GiveFailure =
  | { kind: 'mismatch' }
  | { kind: 'fields'; errors: GiveErrors; message: string }
  | { kind: 'pin'; message: string; focus: 'nurse' | 'pin' }
  | { kind: 'reload'; message: string }
  | { kind: 'key-reused'; message: string }
  | { kind: 'notice'; message: string; lost: boolean };

/** 409 answers after which the sheet closes and the MAR is read again (contract list, plus `ipd-readonly`). */
const RELOAD_SLUGS = [
  'dose-recorded',
  'order-state',
  'order-not-verified',
  'dose-not-due',
  'dose-before-start',
  'not-admitted',
  'ipd-readonly',
] as const;

const BEDSIDE_FIELDS: readonly string[] = GIVE_FIELD_ORDER;

/** Status 0 (no connection), a server error or anything that is not an API answer: the dose may have been recorded. */
export function isLostAnswer(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

export function classifyGiveError(error: unknown): GiveFailure {
  if (!(error instanceof ApiError)) {
    return { kind: 'notice', message: GIVE_ERROR, lost: true };
  }
  const slug = error.problem.type.split('/').pop() ?? '';
  const message = problemMessage(error, GIVE_ERROR);
  if (error.status === 503 && slug === 'pin-unavailable') {
    // Nothing was recorded: the PIN check is switched off on the server (fail closed).
    return { kind: 'pin', message, focus: 'pin' };
  }
  if (isLostAnswer(error)) {
    return { kind: 'notice', message: GIVE_ERROR, lost: true };
  }
  switch (error.status) {
    case 400: {
      const items = error.fieldErrors;
      if (items.some((f) => f.field === 'slotAt')) {
        return { kind: 'reload', message };
      }
      if (items.some((f) => f.field === 'clientKey')) {
        return { kind: 'key-reused', message: items.find((f) => f.field === 'clientKey')?.message || KEY_RELOAD };
      }
      const errors: GiveErrors = {};
      for (const item of items) {
        if (BEDSIDE_FIELDS.includes(item.field)) {
          errors[item.field as GiveField] ??= item.message || message;
        }
      }
      // "High-alert medicine: a second nurse must check it ..." comes on both fields: say it once, under the PIN.
      if (errors.secondNurseId && errors.secondNurseId === errors.secondNursePin) {
        delete errors.secondNurseId;
      }
      if (Object.keys(errors).length > 0) {
        return { kind: 'fields', errors, message };
      }
      return { kind: 'notice', message, lost: false };
    }
    case 403:
      if (slug === 'wrong-pin') {
        return { kind: 'pin', message, focus: 'pin' };
      }
      if (slug === 'pin-locked') {
        return { kind: 'pin', message, focus: 'nurse' };
      }
      return { kind: 'notice', message, lost: false };
    case 409:
      if (slug === 'wristband-mismatch') {
        return { kind: 'mismatch' };
      }
      if (slug === 'pin-not-set') {
        return { kind: 'pin', message, focus: 'nurse' };
      }
      if (slug === 'client-key-reused') {
        return { kind: 'key-reused', message };
      }
      if ((RELOAD_SLUGS as readonly string[]).includes(slug)) {
        return { kind: 'reload', message };
      }
      return { kind: 'notice', message, lost: false };
    case 429:
      return { kind: 'pin', message, focus: 'pin' };
    default:
      return { kind: 'notice', message, lost: false };
  }
}

/** The request body for Given. The second nurse and the PIN go only with a high-alert medicine. */
export function givenInput(
  clientKey: string,
  slot: Pick<MarSlot, 'slotAt'>,
  highAlert: boolean,
  entered: { wristband: string; said: boolean; nurse: string; pin: string },
): DoseInput {
  return {
    clientKey,
    slotAt: slot.slotAt,
    outcome: 'GIVEN',
    wristband: entered.wristband,
    identityConfirmed: entered.said,
    ...(highAlert
      ? { secondNurseId: entered.nurse === '' ? null : entered.nurse, secondNursePin: entered.pin }
      : {}),
  };
}

/** The request body for Record not given: no wristband, statement, nurse or PIN. */
export function notGivenInput(clientKey: string, slot: Pick<MarSlot, 'slotAt'>, reason: string): DoseInput {
  return { clientKey, slotAt: slot.slotAt, outcome: 'NOT_GIVEN', reasonKey: reason === '' ? null : reason };
}

/**
 * What the server compares when the same clientKey comes again (slot, outcome, reason, second nurse).
 * A different value under the same key is a 409 `client-key-reused`, so the sheet makes a new key.
 */
export function requestSignature(input: DoseInput): string {
  return [input.outcome, input.reasonKey ?? '', input.secondNurseId ?? ''].join('|');
}

// ---- EMR: the "Last dose" line (OQ-32-4, ADR-011 D11) ----

export interface LastDoseLine {
  text: string;
  /** Not given and Refused show in the warn colour. */
  warn: boolean;
}

/** "Last dose 8 AM: Given · 8:04 AM · Pawar", "Last dose 8 AM: Refused · Patient refused", "Last dose 8 AM: Not given · Nil by mouth". */
export function lastDoseLine(dose: DoseRow | null, asOf: string): LastDoseLine | null {
  if (!dose) {
    return null;
  }
  const slot = slotWhen(dose.slotAt, asOf);
  const reason = dose.reason?.label ?? '';
  switch (dose.outcome) {
    case 'GIVEN':
      return {
        text: `Last dose ${slot}: Given · ${clockText(dose.recordedAt)} · ${lastWord(dose.recordedByName)}`,
        warn: false,
      };
    case 'REFUSED':
      return { text: `Last dose ${slot}: Refused · ${reason}`, warn: true };
    case 'HELD':
      return { text: `Last dose ${slot}: Not given · ${reason}`, warn: true };
  }
}
