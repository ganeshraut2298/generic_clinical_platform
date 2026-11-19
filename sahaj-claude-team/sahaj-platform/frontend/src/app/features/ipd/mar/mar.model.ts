import { ApiError } from '../../../core/http/problem-detail';
import { problemMessage } from '../../config/config-format';
import { DoseRow, EmrPatient, KeyLabel, NotGivenReasonKey } from '../emr/emr.model';
import { DoseUnitKey, NoticeCount, OrderDetails, OrderFrequencyKey, OrderHold } from '../orders/order.model';

export type { DoseOutcome, DoseRow, NotGivenReasonKey } from '../emr/emr.model';

/**
 * SAH-32 Medicines (MAR) tab of the Nursing sheet and the Give sheet (mockup `sfNsTabs`, `sfMar`,
 * `sfSlotChip`, `sfMedHead`, `sfGive`, `sfgiveok`, `sfnogive`), extended by SAH-76-4 and 76-5 (ADR-027,
 * docs/api/ipd.yaml 0.4.0; reference: Nursing workspace, Medication). Types follow `Mar`, `MarOrder`,
 * `MarSlot`, `DoseRow`, `DoseInput`, `DoseRecorded` and `CorrectionInput`; no field is invented.
 * Every state, count and rule is the server's: the client only draws what it is told and never reads
 * the device clock. Names, UHID and allergy text are on screen only: nothing here is logged or
 * stored. The scanned wristband text, the medicine code, the override note and the second nurse's PIN
 * live in form controls only.
 */

export type MarSlotState =
  | 'GIVEN'
  | 'HELD'
  | 'REFUSED'
  | 'NOT_RECORDED'
  | 'STOPPED'
  | 'REPLACED'
  | 'ON_HOLD'
  | 'WAITING'
  | 'BEFORE_START'
  | 'LATE'
  | 'DUE'
  | 'LATER';

export type MarGiveLockReason = 'ROLE' | 'IPD_READONLY' | 'NOT_ADMITTED';
export type MarOrderStatus = 'PENDING' | 'QUERIED' | 'ACTIVE' | 'ON_HOLD' | 'REJECTED' | 'STOPPED';
export type ScanMethod = 'SCAN' | 'OVERRIDE';
/** `OverrideReasonForScanKey`: what a dose recorded before 0.4.3 may carry (WRISTBAND_DAMAGED is read only). */
export type OverrideReasonKey = 'WRISTBAND_DAMAGED' | 'NO_BARCODE' | 'SCANNER_DOWN' | 'EMERGENCY';
/** `OverrideReasonForScanInputKey` (0.4.3, ADR-027 A9): the reasons a nurse may send for a medicine-code override. */
export type OverrideReasonInputKey = 'NO_BARCODE' | 'SCANNER_DOWN' | 'EMERGENCY';
export const OVERRIDE_INPUT_KEYS: readonly OverrideReasonInputKey[] = ['NO_BARCODE', 'SCANNER_DOWN', 'EMERGENCY'];
export type InErrorReasonKey = 'NOT_GIVEN_ACTUALLY' | 'WRONG_PATIENT' | 'WRONG_TIME' | 'DUPLICATE';

/** `DoseCorrectionRow`: set for a record marked in error (UNDO records are not listed on the MAR). */
export interface DoseCorrection {
  kind: 'UNDO' | 'IN_ERROR';
  reason: KeyLabel | null;
  correctedByName: string;
  correctedAt: string;
}

/** `DoseRow` of 0.4.0: the SAH-32 fields plus the version, the way it was given and the correction state. */
export interface MarDoseRow extends DoseRow {
  orderVersion: number;
  method: ScanMethod | null;
  overrideReason: KeyLabel | null;
  beforeVerification: boolean;
  correction: DoseCorrection | null;
  undoSecondsLeft: number;
  canUndo: boolean;
  canMarkInError: boolean;
}

export interface MarSlot {
  /** The dose time of today as an instant: sent back as `DoseInput.slotAt`. */
  slotAt: string;
  state: MarSlotState;
  /** For LATE: whole hours late, rounded down (at least 1). */
  lateHours: number | null;
  /** For LATE: whole minutes after the slot; for a STAT dose, after the end of the STAT window. */
  lateMinutes: number | null;
  canGive: boolean;
  /** The version this slot belongs to: sent as `DoseInput.orderVersion`. */
  orderVersion: number;
  /** For STOPPED, REPLACED, ON_HOLD: the doctor and the time. */
  stateBy: string | null;
  stateAt: string | null;
  /** Records of this slot marked in error, oldest first (shown struck through). */
  history: MarDoseRow[];
  /** True when giving this slot now is a STAT dose before the pharmacist check. */
  beforeVerification: boolean;
  dose: MarDoseRow | null;
}

/** 0.4.3: NOT_STARTED (before the current version's start) and ENDED (at or after its end) are new. */
export type PrnLockReason = 'TOO_SOON' | 'MAX_REACHED' | 'NOT_VERIFIED' | 'ON_HOLD' | 'STOPPED' | 'NOT_STARTED' | 'ENDED';

export interface PrnLine {
  indication: string | null;
  minIntervalMinutes: number;
  maxPer24h: number;
  givenLast24h: number;
  lastGivenAt: string | null;
  nextAllowedAt: string | null;
  lockReason: PrnLockReason | null;
  canGive: boolean;
}

export interface MarOrder {
  orderId: string;
  drugKey: string;
  drugName: string;
  route: string;
  highAlert: string | null;
  /** Null for a STAT (ONCE) order; `prn` for a PRN order. */
  frequencyKey: OrderFrequencyKey | null;
  frequencyLabel: string;
  orderedByName: string;
  status: MarOrderStatus;
  orderVersion: number;
  changed: boolean;
  details: OrderDetails;
  hold: OrderHold | null;
  prn: PrnLine | null;
  hasMedicineCode: boolean;
  lastGivenAt: string | null;
  acknowledged: boolean;
  /** True for a block with nothing due or later: it sits behind "Show <n>". */
  folded: boolean;
  slots: MarSlot[];
}

export interface SecondNurseOption {
  /** Sent as `DoseInput.secondNurseId`. */
  membershipId: string;
  name: string;
}

export interface MarCounters {
  late: number;
  dueNow: number;
  given: number;
}

export interface MarWindows {
  beforeMinutes: number;
  afterMinutes: number;
  statMinutes: number;
  undoSeconds: number;
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
  counters: MarCounters;
  foldedCount: number;
  windows: MarWindows;
  overrideReasons: KeyLabel[];
  inErrorReasons: KeyLabel[];
  ordersToAcknowledge: NoticeCount;
  orders: MarOrder[];
  secondNurses: SecondNurseOption[];
  notGivenReasons: KeyLabel[];
}

export interface ScanOverrideInput {
  reasonKey: OverrideReasonKey | string | null;
  note: string | null;
}

export interface DoseInput {
  clientKey: string;
  /** Required for a scheduled or STAT order; absent for a PRN order. */
  slotAt?: string | null;
  orderVersion: number;
  outcome: 'GIVEN' | 'NOT_GIVEN';
  wristband?: string | null;
  medicineCode?: string | null;
  override?: ScanOverrideInput | null;
  /** NOT_GIVEN with reason OTHER: 4 to 200 characters. Clinical text. */
  note?: string | null;
  identityConfirmed?: boolean | null;
  secondNurseId?: string | null;
  secondNursePin?: string | null;
  reasonKey?: NotGivenReasonKey | string | null;
}

export interface DoseRecorded {
  /** True when an earlier request with the same clientKey recorded this dose (HTTP 200). */
  replayed: boolean;
  dose: MarDoseRow;
  mar: Mar;
}

/** Body of POST /doses/{id}/undo and /doses/{id}/recorded-in-error. */
export interface CorrectionInput {
  clientKey: string;
  /** Recorded in error only. */
  reasonKey?: InErrorReasonKey | string | null;
}

// ---- Texts (exact, from the mockup, the reference and docs/api/ipd.yaml) ----

export const TAB_VITALS = 'Vitals and notes';
export const TAB_MAR = 'Medicines (MAR)';
export const NO_MEDICINES = 'No medicines on the chart. The doctor orders them from EMR.';
export const MAR_CLOSING_LINE =
  "Every dose needs the wristband scan and the patient's name and date of birth. High-alert medicines also need a second nurse.";
export const PENDING_LOCK = 'Cannot be given until the pharmacist checks the order.';
export const QUERIED_LOCK = 'Cannot be given: the pharmacist has a query for the doctor.';
export const HIGH_ALERT_HINT = 'High-alert medicine: a second nurse checks every dose';
/** ROLE shows no line (the buttons are disabled, mockup). */
export const MAR_LOCK_LINES: Record<'IPD_READONLY' | 'NOT_ADMITTED', string> = {
  IPD_READONLY: 'IPD is read-only. Doses cannot be recorded.',
  NOT_ADMITTED: 'This patient is not admitted any more.',
};
/** Reference text: shown when nothing is due or late. */
export const NOTHING_DUE = 'Nothing due right now. Medicines with nothing due are folded away.';
/** Reference text, right of the counters. */
export const MAR_HEADER_HINT = 'Due doses open the scan flow · tap a given dose to mark it recorded in error';
export const MEDICINES_LIST_LABEL = 'Medicines on the chart';

export const STEP_ONE = '1 · Confirm the patient with two identifiers';
export const STEP_CODE = '2 · Scan or type the medicine code';
export const WRISTBAND_LABEL = 'Wristband MRN';
export const WRISTBAND_PLACEHOLDER = 'Scan the wristband or type the MRN';
export const CODE_LABEL = 'Medicine code';
export const CODE_PLACEHOLDER = 'Scan the medicine or type its code';
export const SAID_LABEL = 'The patient said their name and date of birth, or a relative confirmed them';
/** The contract's text (docs/api/ipd.yaml, `secondNurseId` and `secondNursePin`), also shown before sending (DEC-025 G-9). */
export const HIGH_ALERT_SECOND_CHECK =
  'High-alert medicine: a second nurse must check it and enter a 4-digit PIN.';
export const SECOND_NURSE_SMALL = 'A second nurse checks the drug, dose, patient and route, then enters their PIN.';
export const CHECKED_BY_LABEL = 'Checked by';
export const CHOOSE_NURSE = 'Choose a nurse';
export const THEIR_PIN_LABEL = 'Their PIN';
export const PIN_PLACEHOLDER = '4 digits';
/**
 * The hasMedicineCode hint under the code box, and the contract's detail of 409 `no-code-loaded` (ipd.yaml 0.4.4):
 * the same words, so it is also the fallback when the answer carries no detail. The problem title "No code loaded" is not shown.
 */
export const NO_CODE_LOADED =
  'No code is loaded for this medicine. Use Manual override: Medicine has no barcode.';
export const STAT_NOTICE =
  'STAT: this dose can be given before the pharmacist check. The pharmacist reviews it after.';

// ---- Manual override (D4; reference texts) ----

// 0.4.3 (ADR-027 A9): the override covers the medicine code only; the wristband is always scanned. NEW texts (OQ-76-28).
export const OVERRIDE_OPEN = 'Cannot scan the medicine? Manual override';
export const OVERRIDE_CANCEL = 'Cancel override';
export const OVERRIDE_LINE =
  'Only when the medicine code cannot be scanned. The wristband must still match. The override, your reason and your name go to the pharmacy and quality team.';
export const OVERRIDE_NOTE_LABEL = 'What you checked instead, e.g. the strip label read against the chart';
/** 409 `override-after-mismatch`: the contract's detail, used only when the answer carries none. */
export const OVERRIDE_AFTER_MISMATCH =
  'A scan for this dose did not match in the last 15 minutes. Check the patient and the medicine, then scan the medicine code.';
/** PrnLine.lockReason NOT_STARTED and ENDED (NEW, OQ-76-28). */
export const PRN_NOT_STARTED = 'Not started yet';
export const PRN_ENDED = 'Ended';
export const OVERRIDE_REASON_LEGEND = 'Why you cannot scan';
export const OVERRIDE_REASON_MISSING = 'Choose why you cannot scan.';
export const OVERRIDE_NOTE_MISSING = 'Write what you checked instead.';
/** The override note needs at least 4 characters after trimming (story 76-4). */
export const OVERRIDE_NOTE_MIN = 4;

// ---- SAH-38 (ADR-014 D3, D5, D10). NEW texts: the mockup shows none of them. ----

/**
 * The colleague's wrong PIN never shows a count of tries left (security review S4, ADR-011 D7): a
 * guesser is not told when to stop. The fixed text is used whatever the detail says.
 */
export const WRONG_PIN_COLLEAGUE = 'That PIN is not right.';
/** T1 (429 `colleague-pin-limit`): used only when the problem carries no detail. */
export const COLLEAGUE_PIN_LIMIT = 'Too many wrong PIN tries for this nurse. Choose another nurse.';
/** T7 (503 `pin-check-failed`): used only when the problem carries no detail. */
export const PIN_CHECK_FAILED = 'We could not check the PIN. Try again.';
/**
 * T6, the ward line. Shown under "Their PIN" for a high-alert medicine after pin-locked, pin-not-set,
 * rate-limit or colleague-pin-limit, and whenever there is no other nurse to choose. There is no
 * bypass, no "give anyway" and no paper option behind it.
 */
export const WARD_LINE =
  'No second PIN works? Do not give this medicine yet. Choose another nurse, or ask the nurse in charge or the clinic owner. Never share a PIN.';
export const REASON_LABEL = 'Reason';
export const CHOOSE_REASON = 'Choose a reason';
/** The key of the not-given reason that needs a note (OQ-76-26). */
export const OTHER_REASON_KEY = 'OTHER';
export const OTHER_NOTE_LABEL = 'What happened';
export const OTHER_NOTE_MIN = 4;

/** Texts that are not in the mockup or the contract: the tech lead lists them in the handoff. */
export const MAR_LOAD_ERROR = 'We could not load the medicines. Try again.';
export const MAR_LOADING = 'Loading the medicines';
export const GIVE_ERROR = 'We could not record the dose. What you entered is kept, except the PIN. Try again.';
export const KEY_RELOAD = 'Reload the page and try again.';
/** 400 `orderVersion` or 409 `order-changed` without a detail. */
export const ORDER_CHANGED = 'This order was changed. Reload and check the new order.';

// ---- Tabs ----

export type NursingTab = 'vit' | 'mar' | 'ord';
export const NURSING_TABS: readonly NursingTab[] = ['vit', 'mar', 'ord'];

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

/** "2:00 PM", "2:30 PM": always with minutes, as the server writes "Next dose allowed at <h:mm AM/PM>". */
export function clockWithMinutes(instant: string | null | undefined): string {
  const d = ist(instant);
  if (!d) {
    return '';
  }
  const h = d.getUTCHours();
  return `${h % 12 || 12}:${String(d.getUTCMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
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

// ---- Order summary ----

const DOSE_UNIT_LABEL: Record<DoseUnitKey, string> = {
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

/** "1 tablet", "500 mg"; null for a built order ("Dose as in the medicine name"). */
export function doseText(details: Pick<OrderDetails, 'doseAmount' | 'doseUnit'>): string | null {
  if (details.doseAmount === null || details.doseAmount === undefined) {
    return null;
  }
  const unit = details.doseUnit ? DOSE_UNIT_LABEL[details.doseUnit] : '';
  return `${details.doseAmount} ${unit}`.trim();
}

/** "4 h", "30 min", "1 h 30 min". */
export function intervalText(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) {
    return `${m} min`;
  }
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

// ---- MAR tab ----

export function marSubtitle(mar: Pick<Mar, 'patient' | 'asOf'>): string {
  return `Bed ${mar.patient.bedCode} · medicine round, today ${clockText(mar.asOf)}`;
}

/** Small line under the medicine name: "<dose> · <route> · <frequency> · <ordering doctor>" (the dose when the order has one). */
export function marOrderLine(
  order: Pick<MarOrder, 'route' | 'frequencyLabel' | 'orderedByName'> & { details?: Pick<OrderDetails, 'doseAmount' | 'doseUnit'> },
): string {
  const dose = order.details ? doseText(order.details) : null;
  return [dose, order.route, order.frequencyLabel, order.orderedByName].filter((p) => !!p).join(' · ');
}

export function highAlertPill(order: Pick<MarOrder, 'highAlert'>): string {
  return `High-alert · ${order.highAlert ?? ''}`;
}

/** The marker next to the order name: "Changed · v2" (version above 1). */
export const changedMarker = (order: Pick<MarOrder, 'changed' | 'orderVersion'>): string | null =>
  order.changed ? `Changed · v${order.orderVersion}` : null;

export const ORDER_PILL: Record<MarOrderStatus, { text: string; css: string }> = {
  PENDING: { text: 'Pharmacist check', css: 'p-warn' },
  QUERIED: { text: 'Queried', css: 'p-warn' },
  ACTIVE: { text: 'Active', css: 'p-good' },
  ON_HOLD: { text: 'On hold', css: 'p-wait' },
  REJECTED: { text: 'Rejected', css: 'p-crit' },
  STOPPED: { text: 'Stopped', css: 'p-wait' },
};

export const orderPill = (order: Pick<MarOrder, 'status'>) => ORDER_PILL[order.status];

/** The line with the lock icon under a block that cannot be given, or null. */
export function orderLockLine(order: Pick<MarOrder, 'status' | 'hold' | 'slots'>): string | null {
  switch (order.status) {
    case 'PENDING':
      // A STAT dose may be given before the check: its own notice is in the Give sheet.
      return order.slots.some((s) => s.beforeVerification && s.canGive) ? null : PENDING_LOCK;
    case 'QUERIED':
      return QUERIED_LOCK;
    case 'ON_HOLD':
      return order.hold ? `On hold by ${order.hold.heldByName}` : 'On hold';
    default:
      return null;
  }
}

/** "Show 3 medicines with nothing due now" or "Hide medicines with nothing due" (reference). */
export function foldToggleLabel(count: number, open: boolean): string {
  return open
    ? 'Hide medicines with nothing due'
    : `Show ${count} medicine${count > 1 ? 's' : ''} with nothing due now`;
}

/** Orders shown now: every block that is not folded, and the folded ones when the button is open. */
export function visibleOrders(orders: MarOrder[], showFolded: boolean): MarOrder[] {
  return showFolded ? orders : orders.filter((o) => !o.folded);
}

export const nothingDue = (mar: Pick<Mar, 'counters' | 'orders'>, showFolded: boolean): boolean =>
  mar.orders.length > 0 && mar.counters.late + mar.counters.dueNow === 0 && !showFolded;

export interface PrnView {
  indication: string | null;
  interval: string;
  used: string;
  /** The lock line, or null when the line can be given. */
  lock: string | null;
  canGive: boolean;
}

/** The PRN line: chip "PRN", the indication, "Minimum interval 4 h", "2 of 4 in 24 h", the lock when there is one. */
export function prnView(prn: PrnLine, order: Pick<MarOrder, 'hold'>): PrnView {
  let lock: string | null = null;
  switch (prn.lockReason) {
    case 'TOO_SOON':
      lock = `Too soon. Next dose allowed at ${clockWithMinutes(prn.nextAllowedAt)}.`;
      break;
    case 'MAX_REACHED':
      lock = `Daily maximum reached. Next dose allowed at ${clockWithMinutes(prn.nextAllowedAt)}.`;
      break;
    case 'NOT_VERIFIED':
      lock = PENDING_LOCK;
      break;
    case 'ON_HOLD':
      lock = order.hold ? `On hold by ${order.hold.heldByName}` : 'On hold';
      break;
    case 'STOPPED':
      lock = 'This medicine was stopped. Its doses can no longer be recorded.';
      break;
    case 'NOT_STARTED':
      lock = PRN_NOT_STARTED;
      break;
    case 'ENDED':
      lock = PRN_ENDED;
      break;
    default:
      break;
  }
  return {
    indication: prn.indication,
    interval: `Minimum interval ${intervalText(prn.minIntervalMinutes)}`,
    used: `${prn.givenLast24h} of ${prn.maxPer24h} in 24 h`,
    lock,
    canGive: prn.canGive && prn.lockReason === null,
  };
}

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

/** "N h late" from `lateHours`, or the minutes when the window is short and the slot is under an hour late. */
export function lateText(slot: Pick<MarSlot, 'lateHours' | 'lateMinutes'>): string {
  const minutes = slot.lateMinutes;
  if (minutes !== null && minutes !== undefined && minutes < 60) {
    return `${Math.max(1, minutes)} min late`;
  }
  const hours = minutes !== null && minutes !== undefined ? Math.floor(minutes / 60) : (slot.lateHours ?? 1);
  return `${Math.max(1, hours)} h late`;
}

const STOPPED_TEXT = (slot: MarSlot): string =>
  `Stopped${slot.stateBy ? ` by ${slot.stateBy}` : ''}${slot.stateAt ? ` at ${clockText(slot.stateAt)}` : ''}`;

/** What one slot shows, by its state (mockup `sfSlotChip`; reference and contract wording for the new states). */
export function slotChip(
  slot: MarSlot,
  order?: { status?: MarOrderStatus; details?: Pick<OrderDetails, 'kind'> },
): SlotChip {
  const time = clockText(slot.slotAt);
  const dose = slot.dose;
  const stat = order?.details?.kind === 'ONCE';
  switch (slot.state) {
    case 'GIVEN': {
      const by = dose ? lastWord(dose.recordedByName) : '';
      const checked = dose?.secondCheckByName ? `, checked by ${dose.secondCheckByName}` : '';
      const override = dose?.method === 'OVERRIDE';
      const small = !dose ? '' : override ? `${clockText(dose.recordedAt)} · override` : `${clockText(dose.recordedAt)} · ${by}`;
      return {
        kind: 'done',
        tone: 'ok',
        time,
        small,
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
      return {
        kind: 'static',
        tone: 'grey',
        time,
        small: order?.status === 'QUERIED' ? 'Queried' : 'Waiting for pharmacist',
        hint: null,
      };
    case 'BEFORE_START':
      return { kind: 'static', tone: 'grey', time, small: 'Before the order started', hint: null };
    case 'ON_HOLD':
      return {
        kind: 'static',
        tone: 'grey',
        time,
        small: slot.stateBy ? `On hold by ${slot.stateBy}` : 'On hold',
        hint: null,
      };
    case 'STOPPED':
      return { kind: 'static', tone: 'grey', time, small: STOPPED_TEXT(slot), hint: null };
    case 'REPLACED':
      return {
        kind: 'static',
        tone: 'grey',
        time,
        small: `Replaced by a change${slot.stateBy ? ` by ${slot.stateBy}` : ''}${slot.stateAt ? ` at ${clockText(slot.stateAt)}` : ''}`,
        hint: null,
      };
    case 'LATER':
      return { kind: 'static', tone: 'later', time, small: 'Later today', hint: null };
    case 'LATE':
      return {
        kind: 'button',
        tone: 'crit',
        time,
        small: stat
          ? `STAT · overdue by ${Math.max(1, slot.lateMinutes ?? 1)} min · Give`
          : `${lateText(slot)} · Give`,
        hint: null,
      };
    case 'DUE':
      return { kind: 'button', tone: 'warn', time, small: stat ? 'STAT · Give' : 'Due now · Give', hint: null };
  }
}

/** The two parts of the line under a slot for a record marked in error: the original (struck through) and the correction. */
export function historyLine(dose: MarDoseRow): { original: string; note: string } {
  const c = dose.correction;
  const why = c?.reason?.label ? `: ${c.reason.label}` : '';
  return {
    original: `Given ${clockText(dose.recordedAt)} by ${dose.recordedByName}`,
    note: c ? `recorded in error by ${c.correctedByName} at ${clockText(c.correctedAt)}${why}` : '',
  };
}

// ---- Recorded in error panel (D6; reference texts) ----

export const IN_ERROR_HEAD =
  'Mark as recorded in error · the original entry is kept, struck through, with your name, time and reason';
export const IN_ERROR_ERROR = 'We could not mark the dose. Try again.';

/** "Given 11:05 AM by <nurse> · wristband and medicine scanned" or "... · scan override: <reason>", then " · checked by <second nurse>". */
export function doseDetailLine(dose: MarDoseRow): string {
  const how =
    dose.method === 'OVERRIDE'
      ? `scan override: ${dose.overrideReason?.label ?? ''}`.trim()
      : 'wristband and medicine scanned';
  const second = dose.secondCheckByName ? ` · checked by ${dose.secondCheckByName}` : '';
  return `Given ${clockText(dose.recordedAt)} by ${dose.recordedByName} · ${how}${second}`;
}

export const inErrorToast = (reasonLabel: string): string =>
  `Marked as recorded in error: ${reasonLabel.toLowerCase()}. The original entry is kept.`;

// ---- Undo toast (D6; reference texts) ----

export const givenToast = (drugName: string, override = false): string =>
  `${drugName} recorded as given${override ? ' (override, reason saved)' : ''}`;
export const undoneToast = (drugName: string): string => `Undone. ${drugName} is due again.`;
export const UNDO_ERROR = 'We could not undo the dose. Try again.';

// ---- Give sheet ----

export const giveTitle = (order: Pick<MarOrder, 'drugName'>): string => `Give · ${order.drugName}`;

/** "<time> dose · <route> · <frequency>" plus " · 1 h late" when late; a PRN line has no slot. */
export function giveSubtitle(
  order: Pick<MarOrder, 'route' | 'frequencyLabel'>,
  slot: Pick<MarSlot, 'slotAt' | 'state' | 'lateHours' | 'lateMinutes'> | null,
): string {
  if (!slot) {
    return `${order.route} · ${order.frequencyLabel}`;
  }
  const late = slot.state === 'LATE' ? ` · ${lateText(slot)}` : '';
  return `${clockText(slot.slotAt)} dose · ${order.route} · ${order.frequencyLabel}${late}`;
}

export const highAlertStepTitle = (order: Pick<MarOrder, 'highAlert'>): string =>
  `3 · Second nurse check · high-alert ${(order.highAlert ?? '').toLowerCase()}`;

/** "3 · If you are not giving it", or "4 ·" when the high-alert step is there. */
export const notGivingStepTitle = (highAlert: boolean): string =>
  `${highAlert ? '4' : '3'} · If you are not giving it`;

export const notGivenToast = (drugName: string, reasonLabel: string): string =>
  `Recorded: ${drugName} not given · ${reasonLabel}. The doctor sees this on the chart.`;

/** OQ-76-18: no name of any patient. */
export const MISMATCH_LINE =
  'Wrong patient. This band belongs to another patient. Check the patient before giving anything.';
export const CHECKED_BY_SYSTEM = 'Checked by the system';

export interface CheckLine {
  text: string;
  /** True: a plain line the MAR can vouch for. False: a line only the server's check can confirm on Given. */
  known: boolean;
  /** An amber line (late, allergies not recorded). */
  warn: boolean;
}

/**
 * The lines of "Checked by the system" (story 76-4), built from the MAR the sheet holds. The patient
 * and the medicine are compared by the server when Given is tapped, so those two lines say so instead
 * of showing a tick the client cannot know. The weight-based dose line is left out: no medicine ships
 * with one (BR-17).
 */
export function checkLines(
  mar: Pick<Mar, 'patient' | 'asOf'>,
  order: Pick<MarOrder, 'route' | 'lastGivenAt'>,
  slot: Pick<MarSlot, 'state' | 'lateMinutes' | 'lateHours'> | null,
): CheckLine[] {
  const allergy = ((): CheckLine => {
    switch (mar.patient.allergyStatus) {
      case 'NONE_KNOWN':
        return { text: 'No known allergy', known: true, warn: false };
      case 'NOT_RECORDED':
        return { text: 'Allergies not recorded', known: true, warn: true };
      default:
        return {
          text: `Allergy checked · no clash with ${mar.patient.allergyText ?? ''}`.trim(),
          known: true,
          warn: false,
        };
    }
  })();
  const late = slot?.state === 'LATE';
  const lateMinutes = slot?.lateMinutes ?? (slot?.lateHours ? slot.lateHours * 60 : 0);
  return [
    { text: 'Right patient', known: false, warn: false },
    { text: 'Right medicine and dose', known: false, warn: false },
    { text: `Right route · ${order.route}`, known: true, warn: false },
    late
      ? { text: `Late: ${lateMinutes} min after schedule`, known: true, warn: true }
      : { text: 'Right time', known: true, warn: false },
    allergy,
    {
      text: order.lastGivenAt ? `Last given ${slotWhen(order.lastGivenAt, mar.asOf)}` : 'No earlier dose',
      known: true,
      warn: false,
    },
  ];
}

/** The fields the server can refuse on the Give sheet, in the order of the sheet. */
export type GiveField =
  | 'wristband'
  | 'medicineCode'
  | 'overrideReason'
  | 'overrideNote'
  | 'identityConfirmed'
  | 'secondNurseId'
  | 'secondNursePin'
  | 'reasonKey'
  | 'note';
export const GIVE_FIELD_ORDER: readonly GiveField[] = [
  'wristband',
  'medicineCode',
  'overrideReason',
  'overrideNote',
  'identityConfirmed',
  'secondNurseId',
  'secondNursePin',
  'reasonKey',
  'note',
];
export type GiveErrors = Partial<Record<GiveField, string>>;

/** The contract's field names where they differ from the sheet's. */
const FIELD_ALIAS: Record<string, GiveField> = {
  'override.reasonKey': 'overrideReason',
  'override.note': 'overrideNote',
};

/** What a failed Give or Record not given means for the sheet (docs/api/ipd.yaml POST /medicine-orders/{id}/doses). */
export type GiveFailure =
  | { kind: 'mismatch' }
  | { kind: 'code-mismatch'; message: string }
  | { kind: 'override-refused'; message: string }
  | { kind: 'no-code-loaded'; message: string }
  | { kind: 'prn-lock'; message: string }
  | { kind: 'fields'; errors: GiveErrors; message: string }
  | { kind: 'pin'; message: string; focus: 'nurse' | 'pin'; ward: boolean }
  | { kind: 'reload'; message: string }
  | { kind: 'key-reused'; message: string }
  | { kind: 'notice'; message: string; lost: boolean };

/** 409 answers after which the sheet closes and the MAR is read again (contract list, plus `ipd-readonly`). */
const RELOAD_SLUGS = [
  'dose-recorded',
  'order-state',
  'order-not-verified',
  'order-on-hold',
  'order-changed',
  'dose-not-due',
  'dose-before-start',
  'not-admitted',
  'ipd-readonly',
] as const;

/** Exactly 4 ASCII digits, as the server requires of `secondNursePin`. */
export function isFourDigitPin(pin: string): boolean {
  return /^[0-9]{4}$/.test(pin);
}

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
    // Nothing was recorded: the PIN check is switched off on the server (fail closed). No ward line:
    // "choose another nurse" would not help.
    return { kind: 'pin', message, focus: 'pin', ward: false };
  }
  if (error.status === 503 && slug === 'pin-check-failed') {
    // SAH-38 (ADR-014 D5, T7): the PIN store could not be read or written in time. Nothing was
    // recorded and nothing counted, whether the PIN was right or wrong. Try again; no ward line.
    return { kind: 'pin', message: error.problem.detail ?? PIN_CHECK_FAILED, focus: 'pin', ward: false };
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
      if (items.some((f) => f.field === 'orderVersion')) {
        return { kind: 'reload', message: items.find((f) => f.field === 'orderVersion')?.message || ORDER_CHANGED };
      }
      if (items.some((f) => f.field === 'clientKey')) {
        return { kind: 'key-reused', message: items.find((f) => f.field === 'clientKey')?.message || KEY_RELOAD };
      }
      const errors: GiveErrors = {};
      for (const item of items) {
        const field = FIELD_ALIAS[item.field] ?? (item.field as GiveField);
        if (GIVE_FIELD_ORDER.includes(field)) {
          errors[field] ??= item.message || message;
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
        return { kind: 'pin', message: WRONG_PIN_COLLEAGUE, focus: 'pin', ward: false };
      }
      if (slug === 'pin-locked') {
        return { kind: 'pin', message, focus: 'nurse', ward: true };
      }
      return { kind: 'notice', message, lost: false };
    case 409:
      if (slug === 'wristband-mismatch') {
        return { kind: 'mismatch' };
      }
      if (slug === 'medicine-mismatch') {
        return { kind: 'code-mismatch', message };
      }
      if (slug === 'override-after-mismatch') {
        return { kind: 'override-refused', message: error.problem.detail || OVERRIDE_AFTER_MISMATCH };
      }
      if (slug === 'no-code-loaded') {
        // 0.4.4 (H1, DEC-065 A): not a STOP. Nothing was stopped or recorded; the override is open to the nurse at once.
        return { kind: 'no-code-loaded', message: error.problem.detail || NO_CODE_LOADED };
      }
      if (slug === 'prn-too-soon' || slug === 'prn-max-reached') {
        return { kind: 'prn-lock', message };
      }
      if (slug === 'pin-not-set') {
        return { kind: 'pin', message, focus: 'nurse', ward: true };
      }
      if (slug === 'client-key-reused') {
        return { kind: 'key-reused', message };
      }
      if ((RELOAD_SLUGS as readonly string[]).includes(slug)) {
        return { kind: 'reload', message };
      }
      return { kind: 'notice', message, lost: false };
    case 429:
      if (slug === 'colleague-pin-limit') {
        // SAH-38 (ADR-014 D3, T1): the pair cap. No count and no retry time; another nurse works at once.
        return { kind: 'pin', message: error.problem.detail ?? COLLEAGUE_PIN_LIMIT, focus: 'nurse', ward: true };
      }
      return { kind: 'pin', message, focus: 'pin', ward: slug === 'rate-limit' };
    default:
      return { kind: 'notice', message, lost: false };
  }
}

/** What the nurse has entered on the Give sheet. */
export interface GivenEntry {
  wristband: string;
  said: boolean;
  code: string;
  /** Set while the manual override is open. */
  override: { reasonKey: string; note: string } | null;
  nurse: string;
  pin: string;
}

/** The slot and version an order's dose is recorded under: a PRN line has no slot. */
function target(order: Pick<MarOrder, 'orderVersion'>, slot: Pick<MarSlot, 'slotAt' | 'orderVersion'> | null) {
  return {
    orderVersion: slot?.orderVersion ?? order.orderVersion,
    ...(slot ? { slotAt: slot.slotAt } : {}),
  };
}

/** The request body for Given. The second nurse and the PIN go only with a high-alert medicine. */
export function givenInput(
  clientKey: string,
  order: Pick<MarOrder, 'orderVersion'>,
  slot: Pick<MarSlot, 'slotAt' | 'orderVersion'> | null,
  highAlert: boolean,
  entered: GivenEntry,
): DoseInput {
  return {
    clientKey,
    ...target(order, slot),
    outcome: 'GIVEN',
    wristband: entered.wristband,
    medicineCode: entered.code,
    // 0.4.3: the override covers the medicine code only; with a code typed or scanned it is not sent.
    override: entered.override && entered.code.trim() === ''
      ? { reasonKey: entered.override.reasonKey === '' ? null : entered.override.reasonKey, note: entered.override.note }
      : null,
    identityConfirmed: entered.said,
    ...(highAlert
      ? { secondNurseId: entered.nurse === '' ? null : entered.nurse, secondNursePin: entered.pin }
      : {}),
  };
}

/** The request body for Record not given: no wristband, statement, nurse or PIN. The note goes with the reason Other. */
export function notGivenInput(
  clientKey: string,
  order: Pick<MarOrder, 'orderVersion'>,
  slot: Pick<MarSlot, 'slotAt' | 'orderVersion'> | null,
  reason: string,
  note = '',
): DoseInput {
  return {
    clientKey,
    ...target(order, slot),
    outcome: 'NOT_GIVEN',
    reasonKey: reason === '' ? null : reason,
    ...(reason === OTHER_REASON_KEY ? { note } : {}),
  };
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
