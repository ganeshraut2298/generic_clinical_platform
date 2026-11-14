import { formatDisplayDate, isoWeekday } from '../../../shared/format/ist-date';
import { formatInrPaise } from '../../../shared/format/inr';
import { isComponentValue } from '../components/component-values.logic';
import { EmrListValue } from '../emr-phase3.model';
import { EmrFinishBillLine, EmrFinishEffects, EmrFinishPrint, EmrFinishProfileEffects } from '../emr-finish.model';
import { EmrLayoutSection } from '../emr.model';
import { scalarText } from '../paper/case-paper.logic';
import { decisionLabel } from '../specialty/onco.logic';

/**
 * The profile lines of "Check before completing" (ADR-025 D2, D7, D19; opd.yaml 0.6.1): composed from
 * the server's named profile block with the words of the mockup's completion dialogs where drawn
 * (#DoctorDental, #DoctorGynae, #DoctorPaeds, #DoctorPhysio) and the H18 defaults where not drawn
 * (oncology, surgery). The section rows stay with the shared composer. Screen only: never logged.
 */

const NOT_RECORDED = 'Not recorded';
export const NOT_IN_TARIFF = 'Not in tariff';
export const NO_FREE_SLOT = 'No free slot found. Book the appointment from Appointments.';

const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
const INTERVAL_WORDS: Record<string, string> = { D3: '3 days', D7: '7 days', D14: '14 days', M1: '1 month' };

type Block<K extends EmrFinishProfileEffects['profile']> = Extract<EmrFinishProfileEffects, { profile: K }>;

/** `2026-11-19` -> `Thu 19 Nov 2026` (the date with its day name, as the oncology facts cell). */
export function dateWithDay(iso: string): string {
  return `${DAY_NAMES[isoWeekday(iso)]} ${formatDisplayDate(iso)}`;
}

export function profileBlock<K extends EmrFinishProfileEffects['profile']>(effects: EmrFinishEffects, key: K): Block<K> | null {
  const block = effects.profile;
  return block && block.profile === key ? (block as Block<K>) : null;
}

/** What the record itself supplies to the lines (the server's block holds only what the record view cannot). */
export interface ProfileRecordFacts {
  /** Medicine names on the prescription, in order. */
  medicines: string[];
  /** Gynae lines are composed from the record as drawn. */
  gynae: { cycle: string | null; flow: string | null; contraception: string | null; orders: string[] };
  /** Home exercises on the record (physio "n home exercises"). */
  homeExercises: number;
  /** Modalities given today (physio "Treatment: ..."). */
  modalities: string[];
}

export function emptyRecordFacts(): ProfileRecordFacts {
  return { medicines: [], gynae: { cycle: null, flow: null, contraception: null, orders: [] }, homeExercises: 0, modalities: [] };
}

type Values = Readonly<Record<string, Record<string, unknown>>>;

function activeTexts(values: Values, kind: EmrListValue['kind']): string[] {
  const out: string[] = [];
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (isComponentValue(v) && v.component === 'EMR_LIST' && v.kind === kind) {
        out.push(...v.items.filter((i) => i.active).map((i) => i.text));
      }
    }
  }
  return out;
}

function fieldText(layout: readonly EmrLayoutSection[], values: Values, sectionKey: string, fieldKey?: string): string | null {
  const section = layout.find((s) => s.key === sectionKey);
  if (!section) {
    return null;
  }
  const texts: string[] = [];
  for (const field of section.fields) {
    if (fieldKey && field.key !== fieldKey) {
      continue;
    }
    const text = scalarText(field, values[sectionKey]?.[field.key]);
    if (text) {
      texts.push(text);
    }
  }
  return texts.length > 0 ? texts.join(', ') : null;
}

/** Reads the facts the lines need from what is on screen (the record values and layout). */
export function recordFacts(layout: readonly EmrLayoutSection[], values: Values): ProfileRecordFacts {
  const medicines: string[] = [];
  let homeExercises = 0;
  let modalities: string[] = [];
  for (const section of Object.values(values)) {
    for (const v of Object.values(section)) {
      if (!isComponentValue(v)) {
        continue;
      }
      if (v.component === 'PRESCRIPTION') {
        medicines.push(...v.rows.map((r) => r.name));
      } else if (v.component === 'HOME_EXERCISES') {
        homeExercises += v.items.length;
      } else if (v.component === 'MODALITIES') {
        modalities = v.given.map((g) => g.text).filter((t) => t);
      }
    }
  }
  return {
    medicines,
    homeExercises,
    modalities,
    gynae: {
      cycle: fieldText(layout, values, 'menstrual', 'cycle')?.toLowerCase() ?? null,
      flow: fieldText(layout, values, 'menstrual', 'flow')?.toLowerCase() ?? null,
      contraception: fieldText(layout, values, 'contraception'),
      orders: activeTexts(values, 'INVESTIGATIONS'),
    },
  };
}

/** The bill lines the generic list shows: dental plan charges are drawn once, in the done rows. */
export function visibleBillLines(effects: EmrFinishEffects): EmrFinishBillLine[] {
  const lines = effects.billLines ?? [];
  return effects.profile?.profile === 'dental' ? lines.filter((l) => l.source !== 'DENTAL_PLAN') : lines;
}

function dentalLines(b: Block<'dental'>): string[] {
  return b.doneRows.map((r) => {
    const sitting = r.sitting.of > 1 ? ` — sitting ${r.sitting.no} of ${r.sitting.of}` : '';
    const charge = r.amountPaise === null ? NOT_IN_TARIFF : formatInrPaise(r.amountPaise);
    return `• ${r.name}${sitting} · ${charge}`;
  });
}

/** "Next sitting: 7 days" with the date and slot; nothing when every row is done. */
export function dentalNextSittingLines(b: Block<'dental'>): string[] {
  if (b.allRowsDone) {
    return [];
  }
  const interval = b.nextSittingInterval ? (INTERVAL_WORDS[b.nextSittingInterval] ?? b.nextSittingInterval) : NOT_RECORDED;
  const head = `Next sitting: ${interval}`;
  if (!b.nextSittingDate) {
    return [head];
  }
  const date = formatDisplayDate(b.nextSittingDate);
  return b.nextSittingSlot ? [`${head} · ${date}, ${b.nextSittingSlot.time}`] : [`${head} · ${date}`, NO_FREE_SLOT];
}

function ogLines(b: Block<'og'>, facts: ProfileRecordFacts): string[] {
  if (b.mode === 'GYNAE') {
    const g = facts.gynae;
    const parts = [g.cycle ? `cycle ${g.cycle}` : null, g.flow ? `flow ${g.flow}` : null].filter((p): p is string => p !== null);
    return [
      `Gynae visit${parts.length > 0 ? ' · ' + parts.join(', ') : ''}`,
      `Contraception: ${g.contraception ?? NOT_RECORDED}`,
      `Orders: ${g.orders.join(', ') || 'none'}`,
    ];
  }
  const age = b.gestationalAge ? `${b.gestationalAge.weeks}w ${b.gestationalAge.days}d` : NOT_RECORDED;
  const bp = b.bpSystolic !== null && b.bpDiastolic !== null ? `${b.bpSystolic}/${b.bpDiastolic}` : NOT_RECORDED;
  const hb = b.hbGdl !== null ? b.hbGdl.toFixed(1) : NOT_RECORDED;
  const lines = [
    `ANC visit${b.ancVisitNo !== null ? ' ' + b.ancVisitNo : ''} at ${age} · BP ${bp} · Hb ${hb}`,
    `Ordered: ${b.orderedLabels.join(', ') || 'nothing'}`,
  ];
  if (b.nextAncWeeks !== null) {
    lines.push(`Next ANC at ${b.nextAncWeeks} weeks`);
  }
  return lines;
}

function paedsLines(b: Block<'paeds'>, facts: ProfileRecordFacts): string[] {
  const lines = [
    b.weightKg === null ? `Weight: ${NOT_RECORDED}` : `Weight ${b.weightKg} kg${b.weightNote ? ` (${b.weightNote})` : ''}`,
    `Vaccines given today: ${b.vaccinesGivenToday.map((v) => v.label).join(', ') || 'none'}`,
  ];
  if (b.nextDue) {
    lines.push(`Next due: ${b.nextDue.label} (${b.nextDue.groupLabel})`);
  }
  lines.push(`Prescription: ${facts.medicines.join('; ') || 'none'}`);
  return lines;
}

/** "Complete session n?" ("Complete this visit?" when no session is linked). */
export function physioTitle(b: Block<'physio'> | null): string {
  return b && b.sessionNo !== null ? `Complete session ${b.sessionNo}?` : 'Complete this visit?';
}

function physioLines(b: Block<'physio'>, facts: ProfileRecordFacts): string[] {
  const lines = [
    b.painScore !== null
      ? `Pain ${b.painScore}/10${b.previousPainScore !== null ? ` (was ${b.previousPainScore} last session)` : ''}`
      : `Pain: ${NOT_RECORDED}`,
    `Treatment: ${facts.modalities.join(', ') || 'none'}`,
    `${facts.homeExercises} home exercises`,
  ];
  if (b.sessionNo !== null) {
    lines.push(b.packageComplete ? `Session ${b.sessionNo} done · package complete` : `Session ${b.sessionNo}${b.packageSize !== null ? ` of ${b.packageSize}` : ''} will be marked done`);
  }
  return lines;
}

function oncoLines(b: Block<'onco'>): string[] {
  const decision = decisionLabel(b.decision);
  const lines = [`Cycle ${b.cycleNo ?? NOT_RECORDED.toLowerCase()}${decision ? ` · ${decision}` : ''}`];
  if (b.nextCycleDate) {
    const word = b.nextCycleLabel === 'REBOOKED_CHEMO' ? 'Re-booked chemo' : 'Next chemo';
    lines.push(`${word} ${dateWithDay(b.nextCycleDate)}${b.nextCycleNo !== null ? ` · cycle ${b.nextCycleNo}` : ''}`);
  }
  return lines;
}

function surgeryLines(b: Block<'surgery'>): string[] {
  if (b.bookState !== 'BOOKED') {
    return [];
  }
  const when = [b.otDate ? formatDisplayDate(b.otDate) : null, b.slotText].filter((x) => x).join(', ');
  return [`OT booking: ${[b.procedureText, when].filter((x) => x).join(' · ')}`];
}

/** The profile lines of the dialog in order. Empty for General Medicine and records without a profile. */
export function profileLines(effects: EmrFinishEffects, facts: ProfileRecordFacts): string[] {
  const b = effects.profile;
  if (!b) {
    return [];
  }
  switch (b.profile) {
    case 'dental':
      return dentalLines(b);
    case 'og':
      return ogLines(b, facts);
    case 'paeds':
      return paedsLines(b, facts);
    case 'physio':
      return physioLines(b, facts);
    case 'onco':
      return oncoLines(b);
    case 'surgery':
      return surgeryLines(b);
    default:
      return [];
  }
}

/** The footer line naming what prints besides the prescription ("Cycle record, take-home medicines and calendar will print."). */
export function printsLine(prints: readonly EmrFinishPrint[] | undefined): string | null {
  const list = prints ?? [];
  if (list.includes('CYCLE_RECORD')) {
    return `Cycle record, take-home medicines${list.includes('CHEMO_CALENDAR') ? ' and calendar' : ''} will print.`;
  }
  const letter = list.includes('ADMISSION_LETTER');
  const preop = list.includes('PREOP_SHEET');
  if (letter || preop) {
    return `${letter ? 'Admission letter' : ''}${letter && preop ? ' and ' : ''}${preop ? (letter ? 'pre-op instructions' : 'Pre-op instructions') : ''} will print.`;
  }
  return null;
}
