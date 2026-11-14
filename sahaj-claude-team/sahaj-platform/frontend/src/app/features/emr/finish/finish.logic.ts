import { formatDisplayDate } from '../../../shared/format/ist-date';
import { formatInrPaise } from '../../../shared/format/inr';
import { pinLine } from '../components/body-map.logic';
import { isComponentValue } from '../components/component-values.logic';
import { followUpLabel } from '../components/follow-up.component';
import { ipdRowsOf } from '../components/ipd-orders.component';
import { EmrBodyMapValue, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import {
  EmrErOutcomeKind,
  EmrFinishBlocker,
  EmrFinishCheck,
  EmrFinishChoices,
  EmrFinishEffects,
  EmrFinishPrint,
  EmrFinishProfileEffects,
  EmrSignOutcome,
} from '../emr-finish.model';
import { EmrEncounterType, EmrLayoutSection } from '../emr.model';
import { PaperModel } from '../paper/case-paper.logic';
import { SectionState } from '../record/emr-record.logic';
import { EmrSpecialtyProfile } from '../specialty/specialty.model';
import { physioTitle } from './profile-lines.logic';

/**
 * Pure rules of the finish step (SAH-51-1, SAH-51-2, SAH-67-1, SAH-67-2): the words of the dialog, the
 * summary rows composed on screen from the record, the message after the sign. The server decides
 * readiness and the effects; this file only words them. Nothing here is logged or stored.
 */

export const NOT_RECORDED = 'Not recorded';
export const ALERT_FIRST_TEXT = 'Remove the drug or confirm the allergy alert before completing.';
export const SIGN_ERROR = 'We could not sign this visit. Nothing was saved. Try again.';
export const OFFLINE_SIGN = 'We could not reach the server. The visit is not signed. Try again.';
export const WHATSAPP_NOTE = 'Needs the WhatsApp service';

export interface FinishTitles {
  title: string;
  confirm: string;
  back: string;
}

/** The title and buttons of the dialog by encounter and profile (SAH-51-1, SAH-67-1, SAH-67-2, SAH-69-3, SAH-63-3). */
export function finishTitles(
  encounter: EmrEncounterType,
  profile: EmrSpecialtyProfile | null,
  dentalAllDone: boolean,
  physio: Extract<EmrFinishProfileEffects, { profile: 'physio' }> | null = null,
): FinishTitles {
  if (encounter === 'IPD_ROUND') {
    return { title: "Sign today's progress note", confirm: 'Sign note', back: 'Back to edit' };
  }
  if (encounter === 'ER_ASSESSMENT') {
    return { title: 'Sign emergency assessment', confirm: 'Sign assessment', back: 'Back to edit' };
  }
  if (profile === 'dental') {
    return { title: 'Send to billing?', confirm: dentalAllDone ? 'Confirm' : 'Confirm & book next sitting', back: 'Back' };
  }
  if (profile === 'physio') {
    return { title: physioTitle(physio), confirm: 'Confirm', back: 'Back' };
  }
  if (profile === 'og' || profile === 'paeds') {
    return { title: 'Complete this visit?', confirm: 'Complete & call next', back: 'Back' };
  }
  return { title: 'Check before completing', confirm: 'Print Rx & call next patient', back: 'Back to edit' };
}

// ---- summary rows (composed on screen from the values; nothing is typed here) ----

export interface SummaryRow {
  label: string;
  text: string;
}

function activeTexts(value: EmrListValue | null | undefined, attr?: (key: string | undefined) => string): string[] {
  return (value?.items ?? [])
    .filter((i) => i.active)
    .map((i) => {
      const extra = attr?.(i.attr1);
      return extra ? `${i.text} (${extra})` : i.text;
    });
}

function listsOf(values: Readonly<Record<string, Record<string, unknown>>>, kind: EmrListValue['kind']): EmrListValue | null {
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'EMR_LIST' && v.kind === kind) {
        return v;
      }
    }
  }
  return null;
}

function rxOf(values: Readonly<Record<string, Record<string, unknown>>>): EmrPrescriptionValue | null {
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'PRESCRIPTION') {
        return v;
      }
    }
  }
  return null;
}

function bodyOf(values: Readonly<Record<string, Record<string, unknown>>>): EmrBodyMapValue | null {
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'BODY_MAP') {
        return v;
      }
    }
  }
  return null;
}

function followOf(values: Readonly<Record<string, Record<string, unknown>>>): string | null {
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'FOLLOW_UP') {
        return followUpLabel(v.interval);
      }
    }
  }
  return null;
}

export interface SummaryInput {
  profile: EmrSpecialtyProfile | null;
  starterKey: string | null;
  encounter: EmrEncounterType;
  paper: PaperModel | null;
  values: Readonly<Record<string, Record<string, unknown>>>;
  layout: readonly EmrLayoutSection[];
  states: readonly SectionState[];
  attrLabel: (kind: 'COMPLAINTS' | 'DIAGNOSIS', key: string | undefined) => string;
}

/**
 * General Medicine: Complaints, General exam, Local findings, Diagnosis, Tests & services, Medicines and
 * Follow-up (mockup #DoctorGeneral, "Check before completing"). Every other screen shows one row per shown
 * section with its status (SAH-51-1); the profile's own rows (dental, physio, oncology) come from the
 * server's effects and are drawn by the dialog.
 */
export function summaryRows(input: SummaryInput): SummaryRow[] {
  if (input.starterKey === 'gm' && input.encounter === 'OPD_VISIT') {
    const complaints = activeTexts(listsOf(input.values, 'COMPLAINTS'), (k) => input.attrLabel('COMPLAINTS', k));
    const dx = activeTexts(listsOf(input.values, 'DIAGNOSIS'), (k) => input.attrLabel('DIAGNOSIS', k));
    const tests = [...activeTexts(listsOf(input.values, 'INVESTIGATIONS')), ...activeTexts(listsOf(input.values, 'SERVICES'))];
    const rx = rxOf(input.values)?.rows ?? [];
    const pins = bodyOf(input.values)?.pins ?? [];
    const follow = followOf(input.values);
    return [
      { label: 'Complaints', text: complaints.join(', ') || NOT_RECORDED },
      { label: 'General exam', text: input.paper?.vitalsLine ?? NOT_RECORDED },
      { label: 'Local findings', text: pins.length > 0 ? pins.map(pinLine).join(', ') : 'None' },
      { label: 'Diagnosis', text: dx.join(', ') || NOT_RECORDED },
      { label: 'Tests & services', text: tests.join(', ') || 'None' },
      { label: 'Medicines', text: rx.length > 0 ? `${rx.length} ${rx.length === 1 ? 'medicine' : 'medicines'} · ${rx.map((r) => r.name).join(', ')}` : NOT_RECORDED },
      { label: 'Follow-up', text: follow ? `In ${follow}` : NOT_RECORDED },
    ];
  }
  const doneBy = new Map(input.states.map((s) => [s.key, s.done]));
  return input.layout.map((section) => ({
    label: section.name,
    text: doneBy.get(section.key) ? 'Done' : section.required ? 'Required' : NOT_RECORDED,
  }));
}

/** IPD_ORDERS rows count for the round's summary line. */
export function ordersLine(values: Readonly<Record<string, Record<string, unknown>>>): string | null {
  let n = 0;
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'IPD_ORDERS') {
        n += ipdRowsOf(v).length;
      }
    }
  }
  return n > 0 ? `${n} new ${n === 1 ? 'medicine' : 'medicines'} to order` : null;
}

// ---- effects (what the sign will do) in words ----

export function billLineText(line: { name: string; qty: number; amountPaise: number; state: 'NEW' | 'ON_BILL' | 'ALREADY_BILLED' }): string {
  const qty = line.qty > 1 ? ` × ${line.qty}` : '';
  const base = `${line.name}${qty} · ${formatInrPaise(line.amountPaise)}`;
  return line.state === 'ALREADY_BILLED' ? `${base}. Already billed. Ask billing to adjust.` : base;
}

export function followUpEffectText(effect: NonNullable<EmrFinishEffects['followUp']>): string[] {
  const lines = [`Follow-up due ${formatDisplayDate(effect.dueDate)}. Books an appointment on that day.`];
  if (effect.firstFreeSlot) {
    lines.push(`First free slot: ${formatDisplayDate(effect.firstFreeSlot.date)}, ${effect.firstFreeSlot.time}.`);
  } else {
    lines.push('No free slot found. Book the appointment from Appointments.');
  }
  if (effect.ignoredInterval) {
    lines.push('The next sitting date is used for the follow-up.');
  }
  return lines;
}

export function patientRecordText(effect: NonNullable<EmrFinishEffects['patientRecord']>): string | null {
  if (effect.conflict) {
    return "The patient's record changed. Review before updating";
  }
  if (effect.fields.length === 0) {
    return null;
  }
  const names = effect.fields.map((f) => (f === 'ALLERGIES' ? 'Allergies' : 'Long-term conditions'));
  return `Patient record will be updated: ${names.join(', ')}`;
}

export const ER_OUTCOME_LABEL: Record<EmrErOutcomeKind, string> = {
  DISCHARGE: 'Discharge from ER',
  ADMIT: 'Admit to IPD',
  REFER_OUT: 'Refer out',
  SEND_TO_OPD: 'Send to OPD',
};

// ---- blockers ----

/** What the screen says when the dialog does not open (SAH-51-1). */
export function blockerMessage(blockers: readonly EmrFinishBlocker[]): string {
  const hasAlert = blockers.some((b) => b.key === 'ALLERGY_ALERT');
  if (hasAlert) {
    return ALERT_FIRST_TEXT;
  }
  return blockers.map((b) => b.message).join(' ');
}

export function firstBlockedSection(blockers: readonly EmrFinishBlocker[]): string | null {
  for (const b of blockers) {
    if (b.sectionKeys && b.sectionKeys.length > 0) {
      return b.sectionKeys[0];
    }
  }
  return null;
}

// ---- the message after the sign ----

function slotText(date: string | null | undefined, time: string | null | undefined): string {
  if (!date) {
    return 'date only';
  }
  return time ? `${formatDisplayDate(date)}, ${time}` : formatDisplayDate(date);
}

const INTERVAL_WORDS: Record<string, string> = { D3: '3 days', D7: '7 days', D14: '14 days', M1: '1 month' };

export interface AfterSign {
  message: string;
  /** Where the doctor goes next (the list the card was opened from). */
  target: 'QUEUE' | 'IPD' | 'EMERGENCY';
  /** The documents to print now, in order (the server's list, ADR-025 D19). */
  prints: EmrFinishPrint[];
}

/** The toast and the next screen after a successful sign (the client builds the message; the server sends ids and counts). */
export function afterSign(
  encounter: EmrEncounterType,
  profile: EmrSpecialtyProfile | null,
  outcome: EmrSignOutcome,
  choices: EmrFinishChoices,
  sessionNo: number | null,
  languageName = 'English',
): AfterSign {
  const prints = outcome.prints ?? [];
  if (encounter === 'IPD_ROUND') {
    return { message: 'Round done', target: 'IPD', prints };
  }
  if (encounter === 'ER_ASSESSMENT') {
    const kind = outcome.emergency?.outcome ?? choices.erOutcome?.kind ?? 'DISCHARGE';
    const text: Record<EmrErOutcomeKind, string> = {
      DISCHARGE: 'Assessment signed · discharged from ER',
      ADMIT: 'Assessment signed · admission requested',
      REFER_OUT: 'Assessment signed · referred out',
      SEND_TO_OPD: outcome.emergency?.tokenNo ? `Assessment signed · sent to OPD, token ${outcome.emergency.tokenNo}` : 'Assessment signed · sent to OPD',
    };
    return { message: text[kind], target: 'EMERGENCY', prints };
  }
  if (profile === 'dental') {
    const next = outcome.course?.nextSittingId ? choices.dental?.nextInterval : null;
    return {
      message: next ? `Sent to billing · next sitting booked in ${INTERVAL_WORDS[next] ?? next}` : 'Sent to billing',
      target: 'QUEUE',
      prints,
    };
  }
  if (profile === 'physio') {
    const n = sessionNo ?? 1;
    const message = outcome.course?.packageComplete
      ? `Session ${n} done · package complete`
      : `Session ${n} done · session ${n + 1} booked ${slotText(outcome.course?.nextAppointmentDate, outcome.course?.nextAppointmentTime)}`;
    return { message, target: 'QUEUE', prints };
  }
  if (prints.includes('CYCLE_RECORD')) {
    return { message: `Visit completed · cycle note saved, prescriptions printed in ${languageName}`, target: 'QUEUE', prints };
  }
  if (prints.includes('ADMISSION_LETTER')) {
    return { message: 'Visit completed · Go to ward and OT with the booking', target: 'QUEUE', prints };
  }
  return { message: 'Visit completed · calling next patient', target: 'QUEUE', prints };
}

/** True when the check says there is nothing left to carry over: the dental button reads "Confirm". */
export function dentalAllDone(check: EmrFinishCheck | null): boolean {
  const block = check?.effects.profile;
  return block?.profile === 'dental' && block.allRowsDone;
}
