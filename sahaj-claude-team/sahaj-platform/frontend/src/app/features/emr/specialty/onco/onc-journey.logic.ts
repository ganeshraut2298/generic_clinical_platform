import { OncResponse } from '../specialty.model';
import {
  CancerIntent,
  CancerJourney,
  CancerJourneyInput,
  CancerSetting,
  CancerStageGroup,
  EmrOncoView,
  JourneyCycleState,
} from '../specialty.model';
import { RESPONSE_LABEL, RESPONSE_SHORT } from '../onco.logic';

/**
 * The Cancer journey snapshot of the Oncology screen (SAH-75-7; DoctorOncology.dc.html:37-60, 757-778) as text:
 * five cells built from the server's strip, "Not recorded" for what is missing. Stage groups, intents, settings
 * and the line list are ours and not clinically reviewed (ADR-026 H6). Clinical data: never logged.
 */

export const NOT_RECORDED = 'Not recorded';
export const NO_PLAN = 'No chemotherapy plan';
export const NOT_MEASURED = 'Not measured';

export const STAGE_GROUPS: readonly CancerStageGroup[] = ['0', 'I', 'IA', 'IB', 'IC', 'II', 'IIA', 'IIB', 'IIC', 'III', 'IIIA', 'IIIB', 'IIIC', 'IV', 'IVA', 'IVB', 'IVC'];
export const INTENT_LABEL: Readonly<Record<CancerIntent, string>> = { CURATIVE: 'Curative', PALLIATIVE: 'Palliative' };
export const SETTING_LABEL: Readonly<Record<CancerSetting, string>> = {
  NEOADJUVANT: 'Neoadjuvant',
  ADJUVANT: 'Adjuvant',
  DEFINITIVE: 'Definitive',
  ADVANCED: 'Advanced / metastatic',
};
export const LINES: readonly number[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "26 Jul" from 2026-07-26. */
export function shortDate(iso: string): string {
  const [, m, d] = iso.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]}`;
}

/** 1 gives "1st", 2 "2nd", 3 "3rd", then "4th" to "10th". */
export function ordinal(n: number): string {
  const suffix = n === 1 ? 'st' : n === 2 ? 'nd' : n === 3 ? 'rd' : 'th';
  return `${n}${suffix}`;
}

const join = (parts: readonly (string | null | undefined)[]): string | null => {
  const kept = parts.filter((p): p is string => !!p && p.trim() !== '');
  return kept.length > 0 ? kept.join(' · ') : null;
};

export interface JourneyCell {
  readonly label: string;
  readonly line1: string;
  readonly line2: string | null;
}

export function diagnosisCell(diagnosisText: string | null, j: CancerJourney | null): JourneyCell {
  return {
    label: 'Diagnosis',
    line1: diagnosisText ?? NOT_RECORDED,
    line2: join([j?.stageGroup ? `Stage ${j.stageGroup}` : null, j?.tnmText]),
  };
}

export function biologyCell(j: CancerJourney | null): JourneyCell {
  return {
    label: 'Biology',
    line1: j?.markersText ?? NOT_RECORDED,
    line2: join([j?.ki67Pct != null ? `Ki-67 ${j.ki67Pct}%` : null, j?.geneticsText]),
  };
}

export function intentCell(j: CancerJourney | null): JourneyCell {
  const first = join([j?.intent ? INTENT_LABEL[j.intent] : null, j?.setting ? SETTING_LABEL[j.setting].toLowerCase() : null]);
  const second = join([j?.lineNo != null ? `${ordinal(j.lineNo)} line` : null, j?.boardDate ? `board ${shortDate(j.boardDate)}` : null]);
  return { label: 'Intent · line', line1: first ?? NOT_RECORDED, line2: second };
}

export const CYCLE_TIP: Readonly<Record<JourneyCycleState, string>> = {
  GIVEN: 'Given',
  TODAY: 'Today',
  PLANNED: 'Planned',
  DELAYED: 'Delayed 1 week',
  MISSED: 'Missed',
};

/** "Cycle 4 of 8 · day 1", or "Cycle 4 delayed" while today's decision is Delay. */
export function cycleText(plan: { cycleNo: number | null; cycleCount: number; cycles: readonly { state: JourneyCycleState }[] }): string {
  const delayed = plan.cycles.some((c) => c.state === 'DELAYED');
  if (plan.cycleNo === null) {
    return `${plan.cycleCount} cycles planned`;
  }
  return delayed ? `Cycle ${plan.cycleNo} delayed` : `Cycle ${plan.cycleNo} of ${plan.cycleCount} · day 1`;
}

export type ResponseTone = 'good' | 'warn' | 'crit' | 'ink';

/** "PR · -42%" and "Partial response since baseline"; "Not measured" without a measured lesion. */
export function responseCell(onco: Pick<EmrOncoView, 'response' | 'sums'> | null): { line1: string; line2: string | null; tone: ResponseTone } {
  const response: OncResponse | null = onco?.response ?? null;
  if (!response || !onco) {
    return { line1: NOT_MEASURED, line2: null, tone: 'ink' };
  }
  const pct = Math.round(onco.sums.pctFromBaseline);
  return {
    line1: `${RESPONSE_SHORT[response]} · ${pct > 0 ? '+' : ''}${pct}%`,
    line2: `${RESPONSE_LABEL[response]} since baseline`,
    tone: response === 'PROGRESSIVE_DISEASE' ? 'crit' : response === 'STABLE_DISEASE' ? 'warn' : 'good',
  };
}

/** "Doxorubicin 180 / 450 mg/m²", or null (the chip is hidden) when there is no earlier dose. */
export function doxorubicinChip(watch: EmrOncoView['watch'] | undefined): string | null {
  if (!watch || watch.doxorubicinGivenMgM2 === null) {
    return null;
  }
  return `Doxorubicin ${watch.doxorubicinGivenMgM2} / ${watch.doxorubicinLimitMgM2} mg/m²`;
}

// ---- the edit form (ours; OQ-75-7) ----

export interface JourneyForm {
  stageGroup: CancerStageGroup | '';
  tnmText: string;
  markersText: string;
  ki67Pct: string;
  geneticsText: string;
  intent: CancerIntent | '';
  setting: CancerSetting | '';
  lineNo: string;
  boardDate: string;
}

export function formOf(j: CancerJourney | null): JourneyForm {
  return {
    stageGroup: j?.stageGroup ?? '',
    tnmText: j?.tnmText ?? '',
    markersText: j?.markersText ?? '',
    ki67Pct: j?.ki67Pct != null ? String(j.ki67Pct) : '',
    geneticsText: j?.geneticsText ?? '',
    intent: j?.intent ?? '',
    setting: j?.setting ?? '',
    lineNo: j?.lineNo != null ? String(j.lineNo) : '',
    boardDate: j?.boardDate ?? '',
  };
}

export const LIMITS = { tnmText: 40, markersText: 80, geneticsText: 60, ki67Max: 100, boardDaysAhead: 365 } as const;

/** Problems with the entered values, by field; empty when the form can be saved. Mirrors the contract limits. */
export function formProblems(f: JourneyForm, today: string, addDaysFn: (iso: string, days: number) => string): Partial<Record<keyof JourneyForm, string>> {
  const out: Partial<Record<keyof JourneyForm, string>> = {};
  if (f.tnmText.length > LIMITS.tnmText) {
    out.tnmText = `Use at most ${LIMITS.tnmText} characters.`;
  }
  if (f.markersText.length > LIMITS.markersText) {
    out.markersText = `Use at most ${LIMITS.markersText} characters.`;
  }
  if (f.geneticsText.length > LIMITS.geneticsText) {
    out.geneticsText = `Use at most ${LIMITS.geneticsText} characters.`;
  }
  if (f.ki67Pct.trim() !== '') {
    const n = Number(f.ki67Pct);
    if (!Number.isInteger(n) || n < 0 || n > LIMITS.ki67Max) {
      out.ki67Pct = 'Enter a whole number from 0 to 100.';
    }
  }
  if (f.boardDate !== '') {
    if (f.boardDate < '1900-01-01' || f.boardDate > addDaysFn(today, LIMITS.boardDaysAhead)) {
      out.boardDate = 'Choose a date not more than a year ahead.';
    }
  }
  return out;
}

/** The save body: every field is sent (an emptied field clears it); the version read guards against a stale form. */
export function inputOf(f: JourneyForm, expectedVersion: number): CancerJourneyInput {
  const text = (v: string): string | null => (v.trim() === '' ? null : v.trim());
  return {
    expectedVersion,
    stageGroup: f.stageGroup === '' ? null : f.stageGroup,
    tnmText: text(f.tnmText),
    markersText: text(f.markersText),
    ki67Pct: f.ki67Pct.trim() === '' ? null : Number(f.ki67Pct),
    geneticsText: text(f.geneticsText),
    intent: f.intent === '' ? null : f.intent,
    setting: f.setting === '' ? null : f.setting,
    lineNo: f.lineNo === '' ? null : Number(f.lineNo),
    boardDate: f.boardDate === '' ? null : f.boardDate,
  };
}
