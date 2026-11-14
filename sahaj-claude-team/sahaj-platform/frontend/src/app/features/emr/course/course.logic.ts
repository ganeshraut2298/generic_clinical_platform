import { formatDisplayDate } from '../../../shared/format/ist-date';
import { formatInr } from '../../../shared/format/inr';
import { ACCENT } from '../specialty/specialty-registry';
import {
  CourseAccount,
  CourseEncounterKind,
  CoursePaymentType,
  CourseSitting,
  CourseSittingAction,
  CourseSittingStatus,
  CourseUnit,
  PaymentMode,
  TreatmentCourseKind,
} from './course.model';

/**
 * Pure rules of the treatment course page (SAH-58; mockup #TreatmentCourse). The words, the payment
 * types and the suggested amounts are rules from the EMR mockup, accepted by the human in DEC-039,
 * not clinically or commercially reviewed (B-1). The sample rates of the mockup are never used:
 * every amount comes from the server (the clinic's tariff and the billing account).
 */

export const UNIT_WORD: Record<CourseUnit, string> = {
  SITTING: 'Sitting',
  VISIT: 'Visit',
  CYCLE: 'Cycle',
  STEP: 'Step',
  SESSION: 'Session',
};

export const UNIT_LETTER = (unit: CourseUnit): string => UNIT_WORD[unit].charAt(0);

export const STATUS_WORD: Record<CourseSittingStatus, string> = { PLANNED: 'Planned', TODAY: 'Today', DONE: 'Done', MISSED: 'Missed' };

export const ENCOUNTER_WORD: Record<CourseEncounterKind, string> = { OPD: 'OPD', DAY_CARE: 'Day care', IPD: 'IPD' };

export const MODES: readonly { key: PaymentMode; label: string }[] = [
  { key: 'UPI', label: 'UPI' },
  { key: 'CASH', label: 'Cash' },
  { key: 'CARD', label: 'Card' },
];

export const TYPE_WORD: Record<CoursePaymentType, string> = {
  VISIT_PAYMENT: 'Visit payment',
  FULL_BALANCE: 'Full balance',
  PART_PAYMENT: 'Part payment',
  ADVANCE: 'Advance',
  IPD_DEPOSIT: 'IPD deposit',
};

export type PayChoice = 'today' | 'balance' | 'partial' | 'advance';

export const AMOUNT_STEP_PAISE = 50_000;
export const NOTE_ACCOUNT = 'one running account: advances and part payments carry across visits';

export function rupees(paise: number): string {
  return formatInr(paise / 100);
}

/** The number shown for a sitting: the renumbered one (0.7.0), else the stable one. */
export function numberOf(sitting: Pick<CourseSitting, 'sittingNo'> & { displayNo?: number }): number {
  return sitting.displayNo ?? sitting.sittingNo;
}

export function sittingLabel(sitting: CourseSitting, unit: CourseUnit): string {
  return `${UNIT_LETTER(unit)}${numberOf(sitting)}`;
}

/** "OPD visit · OPD-10231", "IPD · Delivery admission · IP-2232" or "... · request" (not linked yet). */
export function encounterText(s: CourseSitting): string {
  if (s.encounterKind === 'OPD') {
    return s.encounterRef ? `OPD visit · ${s.encounterRef}` : 'OPD visit · booked';
  }
  const head = s.encounterKind === 'DAY_CARE' ? 'Day care' : 'IPD';
  return `${head} · ${s.encounterLabel}${s.encounterRef ? ` · ${s.encounterRef}` : ' · request'}`;
}

/** The charge text: "Billed in IPD · ₹ x" for a stay linked to an admission, else the planned charge. */
export function chargeText(s: CourseSitting): string {
  if (s.settledInIpd) {
    return s.ipdBillTotalPaise != null ? `Billed in IPD · ${rupees(s.ipdBillTotalPaise)}` : 'Billed in IPD';
  }
  return rupees(s.chargePaise);
}

export interface Stat {
  label: string;
  value: string;
  note: string;
  tone: 'ink' | 'good' | 'crit';
}

/** The four figures above the timeline: estimate, paid, advance or earlier due, due today. */
export function stats(a: CourseAccount): Stat[] {
  const unit = UNIT_WORD[a.unit].toLowerCase();
  const credit = a.creditPaise > 0;
  return [
    { label: 'Course estimate', value: rupees(a.estimatePaise), note: `${a.sittingCount} ${unit}s · ${a.doneCount} done`, tone: 'ink' },
    { label: 'Paid so far', value: rupees(a.paidPaise), note: `${a.receiptCount} receipt(s)`, tone: 'good' },
    {
      label: credit ? 'Advance balance' : 'Due from earlier',
      value: rupees(credit ? a.creditPaise : a.dueFromEarlierPaise),
      note: credit ? 'Used against next charges' : 'Not paid yet',
      tone: credit || a.dueFromEarlierPaise === 0 ? 'good' : 'crit',
    },
    {
      label: 'Due today',
      value: rupees(a.dueTodayPaise),
      note: a.todayChargePaise > 0 ? `Today’s ${unit} ${rupees(a.todayChargePaise)}${credit ? ' less advance' : ''}` : `No ${unit} today`,
      tone: a.dueTodayPaise > 0 ? 'crit' : 'good',
    },
  ];
}

export interface PayOption {
  key: PayChoice;
  label: string;
  sub: string;
}

export function payOptions(a: CourseAccount, useCredit: boolean): PayOption[] {
  const unit = UNIT_WORD[a.unit].toLowerCase();
  const credit = a.creditPaise > 0;
  const today = a.todayChargePaise > 0 ? rupees(a.todayChargePaise) : 'Nothing today';
  const earlier = a.dueFromEarlierPaise > 0 ? ` + earlier due ${rupees(a.dueFromEarlierPaise)}` : '';
  const advance = credit && useCredit && a.todayChargePaise > 0 ? ` − advance ${rupees(Math.min(a.creditPaise, a.todayChargePaise))}` : '';
  const next = a.nextPlannedAdmission;
  return [
    { key: 'today', label: `Today’s ${unit}`, sub: `${today}${earlier}${advance}` },
    { key: 'balance', label: 'Full course balance', sub: 'Clears the whole estimate' },
    { key: 'partial', label: 'Part payment', sub: 'Any amount now, rest later' },
    {
      key: 'advance',
      label: next ? 'Advance / IPD deposit' : 'Advance',
      sub: next ? `Suggested deposit for the admission: ${rupees(a.suggestions.ipdDepositPaise ?? 0)}` : `Kept as credit for next ${unit}s`,
    },
  ];
}

/** What is due today for "Today's <unit>": with the advance first, credit is taken off; without it the full amount is asked. */
export function todayAmountPaise(a: CourseAccount, useCredit: boolean): number {
  const gross = a.todayChargePaise + a.dueFromEarlierPaise;
  return useCredit ? Math.max(0, gross - a.creditPaise) : gross;
}

/** The amount each payment type starts with (the doctor or billing can change it). */
export function suggestedPaise(choice: PayChoice, a: CourseAccount, useCredit: boolean): number {
  switch (choice) {
    case 'today':
      return todayAmountPaise(a, useCredit);
    case 'balance':
      return a.balancePaise;
    case 'partial':
      return a.suggestions.partPaymentPaise ?? 0;
    default:
      return a.nextPlannedAdmission ? (a.suggestions.ipdDepositPaise ?? 0) : 0;
  }
}

export function apiType(choice: PayChoice, a: CourseAccount): CoursePaymentType {
  switch (choice) {
    case 'today':
      return 'VISIT_PAYMENT';
    case 'balance':
      return 'FULL_BALANCE';
    case 'partial':
      return 'PART_PAYMENT';
    default:
      return a.nextPlannedAdmission ? 'IPD_DEPOSIT' : 'ADVANCE';
  }
}

/** Types that may not go over the balance; an Advance or IPD deposit stays as credit. */
export function capsAtBalance(type: CoursePaymentType): boolean {
  return type === 'VISIT_PAYMENT' || type === 'FULL_BALANCE' || type === 'PART_PAYMENT';
}

export function amountProblem(amountPaise: number, type: CoursePaymentType, a: CourseAccount): string | null {
  if (amountPaise <= 0) {
    return 'Nothing to collect for this choice.';
  }
  if (amountPaise % 100 !== 0) {
    return 'Enter whole rupees.';
  }
  if (capsAtBalance(type) && amountPaise > a.balancePaise) {
    return `The balance on the course is ${rupees(a.balancePaise)}. Enter that or less.`;
  }
  return null;
}

export function amountHint(choice: PayChoice, a: CourseAccount, amountPaise: number): string {
  if (amountPaise <= 0) {
    return choice === 'advance' ? 'Use − / + to set the amount.' : 'Nothing to collect for this choice.';
  }
  if (choice === 'advance') {
    return `Kept as credit and adjusted automatically in later bills${a.nextPlannedAdmission ? ', including the IPD bill.' : '.'}`;
  }
  if (choice === 'partial') {
    return `Balance after this: ${rupees(Math.max(0, a.balancePaise - amountPaise))}. Use − / + to change.`;
  }
  return 'Use − / + to change the amount.';
}

export function collectLabel(amountPaise: number, mode: PaymentMode): string {
  const word = MODES.find((m) => m.key === mode)?.label ?? mode;
  return amountPaise > 0 ? `Collect ${rupees(amountPaise)} by ${word}` : 'Nothing to collect';
}

export function payNote(a: CourseAccount): string {
  const unit = UNIT_WORD[a.unit].toLowerCase();
  return `Charges are added when a ${unit} is marked done. Advances and IPD deposits stay as credit and are used first. Balance on the course: ${rupees(a.balancePaise)}.`;
}

/** "RC-12" to 12. */
export function receiptNumber(receiptNo: string | null): number | null {
  const m = receiptNo ? /(\d+)$/.exec(receiptNo) : null;
  return m ? Number(m[1]) : null;
}

/** Whole rupees to paise; null when the text is not a whole positive number of rupees. */
export function parseRupees(text: string): number | null {
  const t = text.trim().replace(/,/g, '');
  if (!/^\d{1,9}$/.test(t)) {
    return null;
  }
  return Number(t) * 100;
}

/** The words of a drawn action button (TreatmentCourse.dc.html:249-285). */
export function actionLabel(action: CourseSittingAction, unit: CourseUnit, sitting: CourseSitting): string {
  switch (action) {
    case 'MARK_DONE':
      return `Mark ${UNIT_WORD[unit].toLowerCase()} done`;
    case 'MISSED':
      return 'Patient did not come';
    case 'REBOOK':
      return 'Rebook';
    case 'RESCHEDULE':
      return `Reschedule: ${sitting.plannedDate ? formatDisplayDate(sitting.plannedDate) : 'set a date'} ▾`;
    case 'REMOVE':
      return 'Remove';
    case 'REQUEST_ADMISSION':
      return sitting.encounterKind === 'OPD' ? 'Move to day care / IPD' : 'Request admission';
    case 'ADMIT':
      return 'Admit now';
  }
}

/** The toast after "Mark <unit> done": the charge comes from the server's sitting, the browser adds nothing up. */
export function doneToast(unit: CourseUnit, sitting: CourseSitting): string {
  const head = `${UNIT_WORD[unit]} ${numberOf(sitting)} done`;
  return sitting.settledInIpd ? `${head} · billed in IPD` : `${head} · ${rupees(sitting.chargePaise)} added to the account`;
}

/** Today or later, as a yyyy-mm-dd string against today's IST date (a date choice never goes into the past). */
export function dateProblem(date: string, today: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    return 'Choose a date.';
  }
  return date < today ? 'Choose today or a later date.' : null;
}

/** Accent of the course page and panel by kind (ADR-026 D1): DENTAL dental, ANC og, REHAB physio, SURGERY surgery, CHEMO onco. */
export const KIND_ACCENT: Record<TreatmentCourseKind, string> = {
  DENTAL: ACCENT.dental,
  ANC: ACCENT.og,
  REHAB: ACCENT.physio,
  SURGERY: ACCENT.surgery,
  CHEMO: ACCENT.onco,
};
