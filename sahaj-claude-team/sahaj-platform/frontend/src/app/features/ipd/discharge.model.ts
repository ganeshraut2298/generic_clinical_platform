import { formatInrPaise } from '../../shared/format/inr';
import type { AdmissionDetail, AdmissionList, BillLine, PayMode } from './ipd.model';

/** Types for the SAH-27 endpoints in `docs/api/ipd.yaml` (discharge, final bill, Discharge setup). */
export type DischargeType = 'normal' | 'lama' | 'referred' | 'death';
export type DischargeTemplateKey = 'general' | 'surgical' | 'maternity' | 'daycare';

export interface DischargeTypeOption {
  key: DischargeType;
  label: string;
}

export interface DischargeTemplateOption {
  key: DischargeTemplateKey;
  label: string;
}

/** Labels of the four types, in the order of the Discharge setup page (mockup `disTypes`). */
export const DISCHARGE_TYPE_LABEL: Record<DischargeType, string> = {
  normal: 'Normal',
  lama: 'On request (LAMA)',
  referred: 'Referred',
  death: 'Death',
};

export const DISCHARGE_TEMPLATE_LABEL: Record<DischargeTemplateKey, string> = {
  general: 'General medicine',
  surgical: 'Surgical',
  maternity: 'Maternity',
  daycare: 'Day-care',
};

export const FOLLOW_UP_DAYS_MIN = 1;
export const FOLLOW_UP_DAYS_MAX = 30;

export type FollowUpFee = 'free' | 'half' | 'full';

export interface DischargeSettingsInput {
  templateKey?: DischargeTemplateKey | null;
  approvalChain: boolean;
  followUpDays: number;
}

export interface DischargeSettings {
  templateKey: DischargeTemplateKey | null;
  approvalChain: boolean;
  followUpDays: number;
  types: DischargeTypeOption[];
  templates: DischargeTemplateOption[];
  opdFollowUpRule: { days: number; fee: FollowUpFee };
  canEdit: boolean;
}

export interface DischargeSignature {
  type: DischargeType;
  templateKey: DischargeTemplateKey;
  signedByName: string;
  signedAt: string;
}

export interface DischargePreview {
  diagnosis: string;
  stayDays: number;
  bedCode: string;
  doctorName: string;
  doctorShortName: string;
  doctorSpecialty: string | null;
}

export interface DischargeSheetBill {
  totalPaise: number;
  depositsPaise: number;
  balancePaise: number;
}

/** `DischargeSheet`: data of the Discharge sheet (mockup `openDis`). */
export interface DischargeSheet {
  admissionId: string;
  ipNumber: string;
  patientName: string;
  bedCode: string;
  dayNumber: number;
  approvalChain: boolean;
  types: DischargeTypeOption[];
  templates: DischargeTemplateOption[];
  defaultType: DischargeType;
  defaultTemplateKey: DischargeTemplateKey;
  signature: DischargeSignature | null;
  canSign: boolean;
  canDischarge: boolean;
  canRefund: boolean;
  preview: DischargePreview;
  bill: DischargeSheetBill;
  followUpDays: number;
  followUpDate: string;
  /** SAH-33 (ADR-007 D6). An early warning escalation is not closed (OPEN or ACK). Boolean only: no score. */
  openEscalation: boolean;
  /**
   * SAH-33 review fix (ADR-007 R2). The latest sign-off is not later than the start of that
   * escalation's response clock, so it does not cover it: the sheet shows the box unticked, and for
   * death, lama and referred the warning below. Boolean only.
   */
  signoffBeforeEscalation: boolean;
}

export interface DischargeSignoffInput {
  type: DischargeType;
  templateKey: DischargeTemplateKey;
}

export interface DischargeInput extends DischargeSignoffInput {
  expectedTotalPaise: number;
  /** The sheet's `bill.depositsPaise` the user saw; a deposit added since answers 409 bill-changed. */
  expectedDepositsPaise: number;
  mode?: PayMode | null;
}

export interface DischargeRecord {
  type: DischargeType;
  templateKey: DischargeTemplateKey;
  signedByName: string;
  signedAt: string;
  dischargedByName: string;
  dischargedAt: string;
  followUpDays: number;
  followUpDate: string | null;
}

export interface FinalBill {
  lines: BillLine[];
  billDays: number;
  totalPaise: number;
  depositsPaise: number;
  balancePaise: number;
  outcome: 'collected' | 'refunded' | 'settled';
  mode: PayMode | null;
  settledByName: string;
  settledAt: string;
}

export type DischargeStepState = 'done' | 'cur' | 'todo';

export interface DischargeStep {
  label: string;
  state: DischargeStepState;
  /** The number shown in the circle; a done step shows a check instead. */
  number: number;
}

/**
 * Numbered steps of the Discharge sheet (AC2). Approval chain on: three steps; off: two. A signed
 * summary makes the first step done and the second current; the others keep their number.
 * Billing clearance, Nursing handover and Final bill are completed together by the discharge button.
 */
export function dischargeSteps(approvalChain: boolean, signed: boolean): DischargeStep[] {
  const labels = approvalChain
    ? ['Doctor signs the summary', 'Billing clearance', 'Nursing handover']
    : ['Summary signed', 'Final bill'];
  const current = signed ? 1 : 0;
  return labels.map((label, i) => ({
    label,
    number: i + 1,
    state: i === 0 && signed ? 'done' : i === current ? 'cur' : 'todo',
  }));
}

/** The signature applies only when its type and template are the ones chosen on the sheet (AC5). */
export function signatureApplies(
  signature: DischargeSignature | null | undefined,
  type: DischargeType,
  templateKey: DischargeTemplateKey,
): boolean {
  return !!signature && signature.type === type && signature.templateKey === templateKey;
}

/**
 * "Signed by Dr. Anil Rao · 24 Oct 2026, 11:05 AM IST": the server's signer and time, shown under the
 * sign box once a signature applies. Any doctor or the owner can sign, so the name is the signer's,
 * not the admitting doctor's. A signature from an earlier day shows its own date. `formatWhen` gives
 * `24 Oct 2026, 11:05 AM` in IST.
 */
export function signedByText(
  signature: DischargeSignature | null | undefined,
  formatWhen: (iso: string) => string,
): string {
  if (!signature) {
    return '';
  }
  const when = formatWhen(signature.signedAt);
  return when ? `Signed by ${signature.signedByName} · ${when} IST` : `Signed by ${signature.signedByName}`;
}

/** Hint beside the disabled discharge button for a role that cannot complete the discharge. */
export const DISCHARGE_ROLE_HINT = 'Front desk or the owner completes the discharge';

/** "Collect ₹X and discharge" / "Refund ₹X and discharge" / "Discharge" (AC6). */
export function dischargeButtonText(balancePaise: number, format: (paise: number) => string): string {
  if (balancePaise > 0) {
    return `Collect ${format(balancePaise)} and discharge`;
  }
  if (balancePaise < 0) {
    return `Refund ${format(-balancePaise)} and discharge`;
  }
  return 'Discharge';
}

/** Label of the last row of the Final bill block: "To collect now", "To refund" or "Settled". */
export function balanceLabel(balancePaise: number): string {
  return balancePaise > 0 ? 'To collect now' : balancePaise < 0 ? 'To refund' : 'Settled';
}

/** "Collect by" / "Refund by"; only shown when the balance is not 0. */
export function payModeLabel(balancePaise: number): string {
  return balancePaise > 0 ? 'Collect by' : 'Refund by';
}

/** "3 days · bed 101-A · Dr. Meera Kulkarni · Medicine"; "1 day" when 1; no specialty part when null. */
export function stayText(preview: DischargePreview): string {
  const days = `${preview.stayDays} ${preview.stayDays === 1 ? 'day' : 'days'}`;
  return [days, `bed ${preview.bedCode}`, preview.doctorName, preview.doctorSpecialty]
    .filter((p) => !!p)
    .join(' · ');
}

/** "OPD review on 31 Oct 2026 with Dr. Meera Kulkarni", or "—" for Death. */
export function followUpText(
  preview: DischargePreview,
  type: DischargeType,
  followUpDate: string,
  formatDate: (iso: string) => string,
): string {
  return type === 'death'
    ? '—'
    : `OPD review on ${formatDate(followUpDate)} with ${preview.doctorShortName}`;
}

/** Notice under the Final bill block (AC6). */
export function dischargeNotice(
  type: DischargeType,
  followUpDate: string,
  formatDate: (iso: string) => string,
): string {
  return type === 'death'
    ? 'The death summary cannot be printed in this phase. No WhatsApp messages are sent. No follow-up is added.'
    : `The discharge summary and final bill are not sent yet (WhatsApp is not in this phase). An OPD follow-up is added for ${formatDate(followUpDate)}.`;
}

/**
 * Toast after a discharge (AC8): "Asha Patil discharged (normal) · ₹1,500 collected by UPI · bed
 * 101-A sent for cleaning · follow-up on 31 Oct 2026". The type is in lower case; the follow-up
 * part is left out when there is none (Death).
 */
export function dischargeToast(admission: AdmissionDetail, formatDate: (iso: string) => string): string {
  const type = admission.discharge?.type ?? 'normal';
  const bill = admission.finalBill;
  const amount = bill ? formatInrPaise(Math.abs(bill.balancePaise)) : '';
  let money = 'bill settled';
  if (bill?.outcome === 'collected') {
    money = `${amount} collected by ${bill.mode}`;
  } else if (bill?.outcome === 'refunded') {
    money = `${amount} refunded by ${bill.mode}`;
  }
  const parts = [
    `${admission.patientName} discharged (${DISCHARGE_TYPE_LABEL[type].toLowerCase()})`,
    money,
    `bed ${admission.bedCode} sent for cleaning`,
  ];
  const followUp = admission.discharge?.followUpDate;
  if (type !== 'death' && followUp) {
    parts.push(`follow-up on ${formatDate(followUp)}`);
  }
  return parts.join(' · ');
}

/** Tile "Going home today": the number is discharged plus planned (AC10). */
export function goingHomeCount(list: Pick<AdmissionList, 'dischargedToday' | 'goingHomeToday'>): number {
  return (list.dischargedToday ?? 0) + (list.goingHomeToday ?? 0);
}

/** "2 discharged · 1 planned" */
export function goingHomeText(list: Pick<AdmissionList, 'dischargedToday' | 'goingHomeToday'>): string {
  return `${list.dischargedToday ?? 0} discharged · ${list.goingHomeToday ?? 0} planned`;
}

/** Hint under "Follow-up after discharge" on the Discharge setup page (AC12). */
export function followUpRuleHint(rule: { days: number; fee: FollowUpFee } | null | undefined): string {
  const fee = rule?.fee === 'free' ? 'free' : 'charged';
  const days = rule?.days ?? 6;
  return `Adds an OPD follow-up. The fee follows your follow-up rule (${fee} within ${days} days).`;
}

/** SAH-33 (AC14). Normal: the server refuses until a doctor records the response (409 escalation-open). */
export const ESCALATION_OPEN_NORMAL =
  'This patient has an early warning escalation that is not closed. A doctor must record the response first.';
/** SAH-33 (contract wording, pending clinical sign-off): death, lama and referred close it at discharge. */
export const ESCALATION_OPEN_CLOSES =
  'This patient has an early warning escalation that is not closed. Completing this discharge closes it as "Closed at discharge", with no doctor response recorded.';

/**
 * SAH-33 review fix (security M1, ADR-007 R2): the doctor signed before the escalation started, so
 * the server refuses death, lama and referred (409 `escalation-open`, this text) until a doctor
 * records the response or signs again.
 */
export const ESCALATION_OPEN_SIGNED_EARLIER =
  'This patient has an early warning escalation that started after the doctor signed the summary. A doctor must record the response or sign the summary again.';

/**
 * The warning above the footer of the Discharge sheet, or '' when no escalation is open. For death,
 * lama and referred it is the "signed earlier" text when the sign-off does not cover the escalation,
 * else the close-at-discharge text; normal keeps its own text.
 */
export function openEscalationWarning(
  openEscalation: boolean | undefined,
  type: DischargeType,
  signoffBeforeEscalation = false,
): string {
  if (!openEscalation) {
    return '';
  }
  if (type === 'normal') {
    return ESCALATION_OPEN_NORMAL;
  }
  return signoffBeforeEscalation ? ESCALATION_OPEN_SIGNED_EARLIER : ESCALATION_OPEN_CLOSES;
}
