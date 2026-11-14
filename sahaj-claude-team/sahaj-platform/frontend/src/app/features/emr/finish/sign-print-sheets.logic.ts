import { formatDisplayDate } from '../../../shared/format/ist-date';
import { formatInrPaise } from '../../../shared/format/inr';
import { EmrFinishOncoEffects, EmrFinishPrint, EmrFinishSurgeryEffects } from '../emr-finish.model';
import { decisionLabel, REFERRALS, RESPONSE_LABEL } from '../specialty/onco.logic';
import { PrintSheet } from '../specialty/print-sheet';
import {
  ChemoOrderView,
  EmrOncoView,
  EmrSpecialtyReference,
  EmrSpecialtyView,
  SpChemoPlan,
  SpPreauth,
  SpPreopOrders,
  SpSurgeryPlan,
} from '../specialty/specialty.model';
import { ADMISSION_LABEL, ANAESTHESIA_LABEL, BLOOD_CHIPS, PAYER_LABEL, PREOP_LABEL } from '../specialty/surgery.logic';
import { dateWithDay } from './profile-lines.logic';

/**
 * The documents printed by the browser right after a sign (ADR-025 D19, opd.yaml 0.6.1
 * `EmrSignOutcome.prints`): cycle record, chemo calendar, admission letter and pre-op sheet. Each is built
 * from answers the client already has (the record's values, the server's computed view, the profile
 * block of the last check, the chemo order, the profile reference): no print endpoint, nothing stored,
 * never logged. Headings are English until reviewed Marathi and Hindi words are supplied (OQ-45-32); the
 * admission letter prints facts and headings only, with no clinic wording (H19).
 */

export type SheetOf = (title: string, sections: PrintSheet['sections'], footer?: string) => PrintSheet;

export const FEVER_WARNING = 'Call if 38 °C or above.';
export const SYMPTOM_CHECK = 'Check and note your symptoms every day.';
export const WARD_ORDERS_LINE = 'Medicine orders are placed by the ward';
export const PRINT_NAME: Record<EmrFinishPrint, string> = {
  PRESCRIPTION: 'the prescription',
  CYCLE_RECORD: 'the cycle record',
  CHEMO_CALENDAR: 'the chemo calendar',
  ADMISSION_LETTER: 'the admission letter',
  PREOP_SHEET: 'the pre-op instructions',
};

const ORDER_STATUS: Record<ChemoOrderView['status'], string> = { NONE: 'No order', DRAFT: 'Draft', SENT: 'Sent to pharmacy', HELD: 'On hold' };

function text(...parts: (string | null | undefined | false)[]): string {
  return parts.filter((p): p is string => !!p).join(' · ');
}

function section(heading: string, lines: (string | null | undefined | false)[]): PrintSheet['sections'][number] {
  return { heading, lines: lines.filter((l): l is string => !!l) };
}

// ---- cycle record (SAH-70-2) ----

export interface CycleRecordInput {
  sheetOf: SheetOf;
  block: EmrFinishOncoEffects | null;
  onco: EmrOncoView | null;
  plan: SpChemoPlan | null;
  order: ChemoOrderView | null;
  reference: EmrSpecialtyReference | null;
}

export function cycleRecordSheet(i: CycleRecordInput): PrintSheet {
  const cycleNo = i.block?.cycleNo ?? i.onco?.cycleNo ?? null;
  const decision = decisionLabel(i.block?.decision ?? i.onco?.decision?.choice ?? null);
  const bsa = i.onco?.bsaUsed ?? null;
  const response = i.onco?.response ? RESPONSE_LABEL[i.onco.response] : null;
  const sections = [
    section('Cycle', [cycleNo !== null && `Cycle ${cycleNo}`, decision && `Decision: ${decision}`, bsa !== null && `BSA ${bsa.toFixed(2)} m²`, response && `Response: ${response}`]),
  ];
  const o = i.order;
  if (o && o.status !== 'NONE') {
    const protocol = i.reference?.protocols?.find((p) => p.key === o.protocolKey)?.label ?? o.protocolKey;
    const premedsGiven = o.premeds.filter((p) => p.given).map((p) => p.text);
    const premedsNot = o.premeds.filter((p) => !p.given).map((p) => p.text);
    sections.push(
      section('Chemotherapy order', [
        protocol && `Protocol: ${protocol} at ${o.levelPct}%`,
        ...o.drugs.map((d) => text(d.name, d.finalMg !== null ? `${d.finalMg} mg` : d.roundedMg !== null ? `${d.roundedMg} mg` : null)),
        premedsGiven.length > 0 && `Pre-medicines given: ${premedsGiven.join(', ')}`,
        premedsNot.length > 0 && `Pre-medicines not given: ${premedsNot.join(', ')}`,
        `Order: ${ORDER_STATUS[o.status]}`,
        o.secondCheck.state === 'CONFIRMED' && `Second check: confirmed${o.secondCheck.confirmedByName ? ` by ${o.secondCheck.confirmedByName}` : ''}`,
        o.hold && `${o.hold.kind === 'STOP' ? 'Chemo stopped' : 'Chemo delayed'}: ${o.hold.reason}`,
      ]),
    );
  } else if (i.block?.orderStatus === 'HELD') {
    sections.push(section('Chemotherapy order', ['On hold']));
  }
  const note = (i.plan?.cycleNote ?? '').split('\n').map((l) => l.trim());
  sections.push(section('Cycle note', note));
  const referrals = (i.plan?.referrals ?? []).map((k) => REFERRALS.find((r) => r.key === k)?.label ?? k);
  if (referrals.length > 0) {
    sections.push(section('Referrals', referrals));
  }
  return i.sheetOf('Cycle record', sections);
}

// ---- chemo calendar (SAH-70-1) ----

export interface ChemoCalendarInput {
  sheetOf: SheetOf;
  block: EmrFinishOncoEffects | null;
  onco: EmrOncoView | null;
  plan: SpChemoPlan | null;
}

export function chemoCalendarSheet(i: ChemoCalendarInput): PrintSheet {
  const date = i.plan?.nextCycleDate ?? i.block?.nextCycleDate ?? i.onco?.nextCycle?.defaultDate ?? null;
  const label = (i.block?.nextCycleLabel ?? i.onco?.nextCycle?.label) === 'REBOOKED_CHEMO' ? 'Re-booked chemo' : 'Next chemo';
  const cycleNo = i.block?.nextCycleNo ?? null;
  const next = i.onco?.nextCycle;
  const sections = [
    section('Next chemo', [date && text(`${label} ${dateWithDay(date)}`, cycleNo !== null && `cycle ${cycleNo}`)]),
    section('Blood test reminders', [next && text(dateWithDay(next.bloodTestDate), 'CBC, kidney, liver', 'day before')]),
    section('Echo', [next?.echo]),
    section('Imaging', [i.plan?.imagingText]),
    section('Fever warning', [FEVER_WARNING]),
    section('Daily symptom check', [SYMPTOM_CHECK]),
  ];
  return i.sheetOf('Chemotherapy calendar', sections.filter((s) => s.lines.length > 0));
}

// ---- admission letter and pre-op sheet (SAH-71-3) ----

export interface AdmissionLetterInput {
  sheetOf: SheetOf;
  block: EmrFinishSurgeryEffects | null;
  plan: SpSurgeryPlan | null;
  preauth: SpPreauth | null;
  view: EmrSpecialtyView | null;
}

function otWhen(date: string | null | undefined, slot: string | null | undefined): string {
  return [date ? formatDisplayDate(date) : null, slot].filter((x) => x).join(', ');
}

export function admissionLetterSheet(i: AdmissionLetterInput): PrintSheet {
  const p = i.plan;
  const b = i.block;
  const procedure = b?.procedureText ?? p?.procedureText ?? null;
  const anaesthesia = p?.anaesthesia ? ANAESTHESIA_LABEL[p.anaesthesia] : null;
  const admission = b?.admission ?? p?.admission ?? null;
  const when = otWhen(b?.otDate ?? p?.otDate, b?.slotText ?? p?.slotText);
  const payer = i.view?.surgery?.payerChip ?? (p ? PAYER_LABEL[p.payer] : null);
  const preauth =
    p && p.payer !== 'SELF'
      ? i.preauth?.sent
        ? text('Sent', i.preauth.sentOn ? formatDisplayDate(i.preauth.sentOn) : null, i.preauth.reference ? `ref ${i.preauth.reference}` : null)
        : 'Pending'
      : null;
  const sections = [
    section('Procedure', [text(procedure, p?.approach)]),
    section('Anaesthesia', [anaesthesia]),
    section('Stay', [admission ? ADMISSION_LABEL[admission] : null]),
    section('OT date and slot', [when]),
    section('Who pays', [payer]),
    section('Package', [p?.packagePaise != null ? formatInrPaise(p.packagePaise) : null]),
    section('Deposit at admission', [i.view?.surgery?.depositPaise != null ? formatInrPaise(i.view.surgery.depositPaise) : null]),
    section('Pre-authorisation', [preauth]),
    section('Ward bed', [b?.admissionRequested ? 'Requested with the booking' : null]),
  ];
  return i.sheetOf('Admission letter', sections.filter((s) => s.lines.length > 0));
}

export interface PreopSheetInput {
  sheetOf: SheetOf;
  block: EmrFinishSurgeryEffects | null;
  plan: SpSurgeryPlan | null;
  preop: SpPreopOrders | null;
  reference: EmrSpecialtyReference | null;
}

export function preopSheet(i: PreopSheetInput): PrintSheet {
  const labels = new Map((i.reference?.lists?.surgeryPreopOrders ?? []).map((r) => [r.key, r.label]));
  const ticked = (i.preop?.ticked ?? []).map((k) => labels.get(k) ?? PREOP_LABEL[k] ?? k);
  const extra = (i.preop?.extraLines ?? []).map((l) => l.trim());
  const bloodLabels = new Map((i.reference?.lists?.surgeryBlood ?? []).map((r) => [r.key, r.label]));
  const blood = i.preop?.blood ? (bloodLabels.get(i.preop.blood) ?? BLOOD_CHIPS.find((c) => c.key === i.preop?.blood)?.label ?? null) : null;
  const sections = [
    section('OT date and slot', [otWhen(i.block?.otDate ?? i.plan?.otDate, i.block?.slotText ?? i.plan?.slotText)]),
    section('Pre-op instructions', [...ticked, ...extra]),
    section('Blood', [blood]),
  ];
  return i.sheetOf('Pre-op instructions', sections.filter((s) => s.lines.length > 0), WARD_ORDERS_LINE);
}
